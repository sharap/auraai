package music.ai.recommend.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.os.Build
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import music.ai.recommend.model.Song
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What the scanner is doing right now, for the settings screen. */
sealed interface ScanStage {
    data object Idle : ScanStage
    data class DownloadingModel(val progress: ModelProgress) : ScanStage
    data object PreparingModel : ScanStage
    data class Scanning(val title: String, val done: Int, val total: Int, val etrSeconds: Long) : ScanStage
    data class Failed(val reason: String) : ScanStage
}

class AiScanner(
    context: Context,
    private val models: ModelRepository,
    private val embeddings: EmbeddingStore,
    /**
     * Whether another device has announced itself recently; see `PeerTransport.somebodyNear`.
     *
     * Asked before waiting for anything, because waiting is only sensible when there is somebody
     * to wait for. False in a build or a test with no transport, and then a scan starts at once as
     * it always did.
     */
    private val peerNear: () -> Boolean = { false },
    /**
     * How many conversations have happened, in either direction; see
     * `PeerTransport.conversationsHad`.
     *
     * The wait ends when this moves without the vectors moving: the other device has been and
     * gone and brought nothing, so there is nothing to wait for. An event rather than a number of
     * seconds, which matters because the devices this runs on are not equally fast and the one
     * doing the waiting is usually the slower.
     */
    private val conversationsHad: () -> Int = { 0 }
) {
    private val appContext = context.applicationContext

    /** The prints of this device's files, so that what is asked for is named by its sound. */
    private val prints = SoundPrintStore.getInstance(context)
    private val audioProcessor = AudioProcessor()

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    @Volatile
    private var isStopping = false

    /**
     * @return true once the audio model is resident and a session is open.
     */
    private suspend fun loadModel(onStage: (ScanStage) -> Unit): Boolean {
        if (ortSession != null) return true

        val variant = models.audioVariant.value
        if (!models.isAvailable(variant.assets)) {
            onStage(ScanStage.DownloadingModel(models.progress.value))
            if (!models.ensure(variant.assets)) {
                onStage(ScanStage.Failed(models.progress.value.error ?: "download_failed"))
                return false
            }
        }

        onStage(ScanStage.PreparingModel)
        return try {
            val modelFile = models.fileForInference(variant.asset)
            if (modelFile == null) {
                onStage(ScanStage.Failed("model_missing"))
                return false
            }
            val env = OrtEnvironment.getEnvironment()
            // No execution provider is added on purpose. NNAPI was benchmarked on this model at
            // 883 ms per track against 868 ms for ONNX Runtime's own CPU kernels, i.e. no gain for
            // a provider that is deprecated from Android 15 and adds driver risk. The default
            // thread pool also picks the right width: forcing 8 threads measured 45% slower than
            // the default on a big.LITTLE phone, because the little cores hold the batch back.
            val options = OrtSession.SessionOptions()
            ortEnv = env
            ortSession = env.createSession(modelFile.absolutePath, options)
            // Stamped now rather than at the end, so an interrupted scan still records which export
            // the embeddings it did write came from.
            models.recordEmbeddingsVariant(variant)
            Log.d(TAG, "ONNX audio model loaded (${variant.name})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ONNX model", e)
            onStage(ScanStage.Failed(e.message ?: "load_failed"))
            false
        }
    }

    fun stop() {
        isStopping = true
    }

    /**
     * Frees the ~280 MB inference session. Worth doing as soon as a scan ends: the text encoder
     * holds its own session, and keeping both alive is what pushes low-memory devices into the
     * killer.
     */
    fun release() {
        runCatching { ortSession?.close() }
        ortSession = null
        ortEnv = null
    }

    suspend fun scanSongs(
        songs: List<Song>,
        onStage: (ScanStage) -> Unit
    ) = withContext(Dispatchers.IO) {
        isStopping = false

        if (!loadModel(onStage)) return@withContext

        val scannedIds = embeddings.ids()
        // Files refused earlier are left alone unless the file itself has changed since; see
        // [AnalysisBacklog]. Without this a library's broken files — a download that stopped at
        // its ID3 tag, a playlist named .mp3 — would be decoded again on every scan for ever.
        val pathsById = songs.associate { it.id to it.path }
        val pending = AnalysisBacklog.pending(
            tracks = songs.map { it.id },
            analysed = scannedIds,
            refusals = embeddings.refusals(),
            stampOf = { id -> stampOf(pathsById[id]) }
        ).toHashSet()
        var songsToProcess = songs.filter { it.id in pending }

        // Asking is a hundred times cheaper than answering. A print costs a couple of
        // milliseconds — a seek and a quarter of a megabyte — and lets the other device recognise
        // the track by its sound; analysing the same track costs seconds of decoding and
        // inference. So the backlog is named first, and then whatever the other device is still
        // handing over is given a moment to arrive before any of it is computed here.
        prints.refresh(songsToProcess.associate { it.id to it.path })
        val askedAt = System.currentTimeMillis()
        val asked = songsToProcess.size
        if (letVectorsArrive(asked, onStage)) {
            val arrived = embeddings.ids()
            songsToProcess = songsToProcess.filter { it.id !in arrived }
            Log.i(
                TAG,
                "Waited ${System.currentTimeMillis() - askedAt} ms for the other device: " +
                    "${asked - songsToProcess.size} of $asked arrived, " +
                    "${songsToProcess.size} are left to analyse"
            )
        } else {
            // Said out loud, because the silent version of this line is how a whole library came
            // to be analysed twice without anything in the log admitting it: the decision not to
            // wait looked exactly like no decision at all.
            Log.i(
                TAG,
                "Nothing arrived in the ${System.currentTimeMillis() - askedAt} ms before the " +
                    "scan set off, so all $asked will be analysed here"
            )
        }

        val total = songsToProcess.size

        if (total == 0) {
            onStage(ScanStage.Scanning("", songs.size, songs.size, 0L))
            return@withContext
        }

        val startTime = System.currentTimeMillis()
        try {
            coroutineScope {
                // Decoding a track and running inference on the previous one use different
                // resources and are independent, so they run concurrently: per track the cost
                // becomes the larger of the two stages instead of their sum. Measured on an
                // 8-core phone that is roughly 1.93 s -> 1.23 s.
                val prepared = Channel<Prepared>(Channel.RENDEZVOUS)

                launch { decodeInto(prepared, songsToProcess) }

                var done = 0
                for (item in prepared) {
                    if (isStopping) break
                    currentCoroutineContext().ensureActive()

                    val elapsed = System.currentTimeMillis() - startTime
                    val avgPerSong = if (done > 0) elapsed / done else 0L
                    val etrSeconds = ((total - done) * avgPerSong) / 1000

                    onStage(
                        ScanStage.Scanning(
                            title = item.song.title,
                            done = done + scannedIds.size,
                            total = songs.size,
                            etrSeconds = etrSeconds
                        )
                    )

                    try {
                        // A track that would not decode is remembered as a refusal, so the next
                        // scan does not keep retrying it — but as a refusal and not as a vector of
                        // zeros, so that it stays out of the similarity space, is never offered to
                        // another device as though it had been analysed, and is reconsidered if the
                        // file is ever replaced by a whole one.
                        val features = item.features
                        if (features == null) {
                            embeddings.refuse(item.song.id, stampOf(item.song.path))
                        } else {
                            embeddings.put(item.song.id, runInference(features))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing song ${item.song.title}", e)
                    }
                    done++
                }

                if (isStopping) prepared.cancel()
            }

            if (!isStopping) {
                onStage(ScanStage.Scanning("", songs.size, songs.size, 0L))
            }
        } finally {
            // Also runs when the user stops the scan, so the ~280 MB session is not left resident.
            release()
        }
    }

    /**
     * What the file looks like now, for remembering or expiring a refusal.
     *
     * A length of zero — a file that has gone, or that this process may not read — gives an empty
     * stamp, which [AnalysisBacklog] reads as "do not reconsider on this evidence".
     */
    private fun stampOf(path: String?): String {
        if (path.isNullOrEmpty()) return ""
        return try {
            val file = File(path)
            AnalysisBacklog.stamp(file.length(), file.lastModified())
        } catch (e: SecurityException) {
            ""
        }
    }

    /** What the decoding side hands to the inference side. */
    private class Prepared(val song: Song, val features: FloatArray?)

    /**
     * Decodes and featurises each song, handing the result over one at a time.
     *
     * Features land in one of two preallocated buffers rather than a fresh array per track: over a
     * 2000-track library that would be half a gigabyte of garbage. A rendezvous channel makes the
     * rotation safe — the send of the second buffer only completes once the consumer has taken it,
     * which is after it finished with the first, so the buffer about to be refilled is never the one
     * being read.
     */
    /**
     * Waits while vectors are still arriving from the other device, and no longer.
     *
     * The moment a scan starts, the app asks its peer for everything this device is missing — and
     * what comes back costs nothing to use. Analysing a track the other device has already
     * analysed is the one kind of work here that is purely wasted, and on a phone it is also
     * battery.
     *
     * Bounded by events and not by a clock, which is the only way to bound it. Three of them end
     * the wait: vectors stopped arriving after arriving, the other device has been and gone
     * without bringing any, or nobody was there to begin with. A measured number of seconds would
     * end it wrongly on at least one device, because the phone doing the waiting may be a cheap
     * one and the thing it is waiting for runs somewhere else entirely — the two have no reason to
     * be in proportion.
     *
     * What makes the third case fast rather than a thirty-second timer is the beacon: this device
     * announces how many vectors it is missing, and a peer that hears it dials at once instead of
     * on its own schedule. That matters most where it is needed most — a phone under a VPN cannot
     * dial at all, so without the announcement its only hope was to be called by chance.
     *
     * @return true when something arrived, so the caller knows to look again.
     */
    private suspend fun letVectorsArrive(waiting: Int, onStage: (ScanStage) -> Unit): Boolean {
        if (waiting == 0) return false
        // Nobody has announced themselves, so there is nobody to ask and nothing to wait for. A
        // library with one device pays nothing at all for this.
        if (!runCatching { peerNear() }.getOrDefault(false)) return false
        val conversationsBefore = runCatching { conversationsHad() }.getOrDefault(0)
        var held = embeddings.ids().size
        val before = held
        var quiet = 0
        var rounds = 0
        while (!isStopping && rounds++ < MOST_ROUNDS) {
            delay(ARRIVAL_PAUSE_MS)
            currentCoroutineContext().ensureActive()
            val now = embeddings.ids().size
            if (now > held) {
                quiet = 0
                held = now
                // Said, because otherwise this is a minute of a screen that claims to be starting
                // and visibly is not.
                onStage(ScanStage.Scanning("", held, held + waiting, 0L))
            } else {
                quiet++
                // The other device answered and brought nothing of ours. Nothing is coming, and
                // the answer arrived as fast as the network allows rather than as fast as some
                // constant guessed on one developer's phone.
                val answered = runCatching { conversationsHad() }.getOrDefault(0) >
                    conversationsBefore
                if (held == before && answered) break
            }
            // Something has arrived, so more is probably coming, and the gaps between one round
            // and the next are seconds: measured once at two seconds of patience, the scan set off
            // after half the library had arrived and computed 2308 vectors that were on their way
            // to it. Nothing has arrived yet and nobody has answered either, so the wait stands —
            // bounded only by [MOST_ROUNDS], which is there for a peer that never replies at all.
            if (held > before && quiet >= PATIENT_ROUNDS) break
        }
        return held > before
    }

    private suspend fun decodeInto(channel: SendChannel<Prepared>, songs: List<Song>) {
        val buffers = arrayOf(FloatArray(AudioProcessor.FEATURE_SIZE), FloatArray(AudioProcessor.FEATURE_SIZE))
        var slot = 0
        try {
            for (song in songs) {
                if (isStopping) break
                currentCoroutineContext().ensureActive()

                // It may have arrived since the list was made: a pass runs while this walks the
                // library, and a vector in hand is worth more than the one this would compute.
                if (embeddings.has(song.id)) continue

                val features = try {
                    val pcm = decodeExcerpt(song)
                    if (pcm.isEmpty()) null else audioProcessor.extractFeatures(pcm)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error decoding ${song.title}", e)
                    null
                }

                if (features == null) {
                    channel.send(Prepared(song, null))
                } else {
                    val buffer = buffers[slot]
                    features.copyInto(buffer)
                    slot = 1 - slot
                    channel.send(Prepared(song, buffer))
                }
            }
        } finally {
            channel.close()
        }
    }

    private fun runInference(features: FloatArray): FloatArray {
        val session = ortSession ?: return FloatArray(EMBEDDING_SIZE)
        val env = ortEnv ?: return FloatArray(EMBEDDING_SIZE)

        return try {
            // CLAP expects [batch, 1, frames, mels] -> [1, 1, 1001, 64]
            val shape = longArrayOf(1, 1, 1001, 64)
            OnnxTensor.createTensor(env, FloatBuffer.wrap(features), shape).use { tensor ->
                session.run(mapOf("input_features" to tensor)).use { output ->
                    @Suppress("UNCHECKED_CAST")
                    val result = output.get(0).value as Array<FloatArray>
                    normalize(result[0])
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed", e)
            FloatArray(EMBEDDING_SIZE)
        }
    }

    private fun normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = kotlin.math.sqrt(norm)
        if (norm > 0f) {
            for (i in v.indices) v[i] /= norm
        }
        return v
    }

    /**
     * Decode timing is measured separately from inference; see ProviderBenchmarkTest.
     *
     * @param startUsOverride where to take the excerpt from instead of the middle. Only an
     *   experiment passes it — DecodeDumpTest, measuring how far an embedding moves when the window
     *   shifts, which is what decides whether embeddings can be carried between devices at all.
     */
    internal fun decodeForBenchmark(
        song: Song,
        stats: DecodeStats? = null,
        startUsOverride: Long? = null
    ): FloatArray = decodeExcerpt(song, stats, startUsOverride)

    /**
     * The excerpt, from whichever decoder is right for the file.
     *
     * mp3 goes to [Mp3Excerpt] rather than to the platform: MediaCodec's mp3 output starts 576
     * samples later than ffmpeg's and its seek over a variable bitrate lands hundreds of samples
     * further off, which is enough to make an embedding from this device unusable on another. A
     * file that will not decode that way still falls back here, because a local embedding is worth
     * more than none; what it cannot do is travel, and the sync record says which decoder made it.
     *
     * @param startUsOverride only an experiment passes it, and it needs the platform path, which
     *   is the one whose window is being measured.
     */
    private fun decodeExcerpt(
        song: Song,
        stats: DecodeStats? = null,
        startUsOverride: Long? = null
    ): FloatArray {
        if (startUsOverride == null && SharedDecoder.handles(song.path)) {
            val samples = runCatching { SharedDecoder.decode(File(song.path)) }
                .getOrElse { FloatArray(0) }
            if (samples.isNotEmpty()) return samples
            Log.w(TAG, "Shared decoder gave nothing for ${song.title}, using the platform one")
        }
        return decodeAudioChunk(song, Excerpt.LENGTH_MS, stats, startUsOverride)
    }

    /** Counters for the decode, filled in only when a benchmark asks for them. */
    internal class DecodeStats {
        @Volatile var inputs = 0
        @Volatile var outputs = 0
        @Volatile var samples = 0
        @Volatile var codecName = "?"
        /** Nanos spent inside MediaCodec calls, as opposed to waiting for the codec. */
        @Volatile var inCallNanos = 0L
        override fun toString() =
            "codec=$codecName inputs=$inputs outputs=$outputs samples=$samples inCall=${inCallNanos / 1_000_000}ms"
    }

    /**
     * Decodes the excerpt [Excerpt] picks and returns exactly [Excerpt.SAMPLES] mono 48 kHz
     * samples, unless the track is genuinely shorter than that.
     *
     * Both exactnesses are deliberate, and both were bought with measurements. The excerpt used to
     * begin wherever `SEEK_TO_CLOSEST_SYNC` happened to land — up to tens of milliseconds past the
     * point asked for — while its end stayed put, so it also came out short of ten seconds: 478981
     * samples instead of 480000 on one mp3. Being short is the expensive part, because the feature
     * extractor then takes the repeat-and-pad branch meant for tracks shorter than the excerpt and
     * appends silence, which on that track moved the embedding by a cosine of 0.10. The late start
     * cost another 0.04 on top. Neither is recoverable later, so the samples are pinned here:
     * seek to the sync point *before* the target, drop the lead-in by its timestamp, and stop once
     * ten seconds have been collected.
     *
     * Driven by MediaCodec's asynchronous callbacks rather than by polling. The polling form issued
     * a dequeueInput/dequeueOutput pair per compressed frame — for a ten-second excerpt that is
     * nearly 800 round trips to the codec service, and it measured around 10 ms per frame however
     * tight the loop was made. In asynchronous mode the codec hands buffers over as they are ready
     * and there is nothing to poll.
     *
     * Every callback runs on [codecThread]; the calling thread only waits and then reads, with the
     * latch providing the happens-before.
     */
    private fun decodeAudioChunk(
        song: Song,
        durationMs: Long,
        stats: DecodeStats? = null,
        startUsOverride: Long? = null
    ): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var callbackThread: HandlerThread? = null
        try {
            extractor.setDataSource(song.path)
            val trackIndex = selectAudioTrack(extractor)
            if (trackIndex < 0) return FloatArray(0)

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val startTimeUs = startUsOverride ?: (Excerpt.startMs(duration / 1000) * 1000)
            // The previous sync point rather than the closest one. The closest may sit after the
            // point asked for, and a window that starts late cannot be made whole afterwards;
            // starting early can, because the lead-in is dropped by timestamp as it arrives.
            extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return FloatArray(0)
            val initialRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val initialChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

            val sink = PcmSink(
                sampleRate = initialRate,
                channels = initialChannels,
                startUs = startTimeUs
            )
            // Input stops when the excerpt is full, not when the clock says the window ended.
            // Bounding it by time meant that whatever the lead-in cost at the front was simply
            // missing from the back: an m4a came out 1900 samples short that way, and a shortfall
            // is read by the feature extractor as a track shorter than ten seconds and finished
            // with silence — worth a cosine of 0.15 on that track. The time bound stays as a
            // backstop a second past the window, so a decoder that emits nothing cannot walk the
            // whole file.
            val inputLimitUs = startTimeUs + durationMs * 1000 + 1_000_000
            val finished = CountDownLatch(1)

            callbackThread = HandlerThread("aura-decode").apply { start() }
            codec = MediaCodec.createDecoderByType(mime)
            stats?.let { it.codecName = runCatching { codec.name }.getOrDefault("?") }
            codec.setCallback(
                object : MediaCodec.Callback() {
                    private var inputDone = false

                    override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                        if (finished.count == 0L) return
                        try {
                            if (inputDone || isStopping) {
                                mc.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                                return
                            }
                            val t0 = System.nanoTime()
                            val buffer = mc.getInputBuffer(index)
                            // Pack as many compressed frames into one buffer as will fit. Each
                            // MediaCodec call costs milliseconds of framework round trip regardless
                            // of payload, and an MP3 frame is about a kilobyte, so filling the
                            // buffer cuts the number of input calls by an order of magnitude.
                            var filled = 0
                            var firstTimestamp = 0L
                            if (buffer != null) {
                                while (true) {
                                    val size = extractor.readSampleData(buffer, filled)
                                    if (size < 0) {
                                        inputDone = true
                                        break
                                    }
                                    if (filled == 0) firstTimestamp = extractor.sampleTime
                                    filled += size
                                    extractor.advance()
                                    if (sink.isFull ||
                                        extractor.sampleTime > inputLimitUs ||
                                        extractor.sampleTime < 0
                                    ) {
                                        inputDone = true
                                        break
                                    }
                                    // Packing several frames into one input buffer needs to know
                                    // how big the next one is, and that was only exposed in
                                    // Android 9. Below it, one frame per buffer — slower, but the
                                    // call does not exist there and would crash the scan.
                                    val next = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                        extractor.sampleSize
                                    } else {
                                        -1
                                    }
                                    if (next <= 0 || filled + next > buffer.capacity()) break
                                }
                            }
                            if (filled == 0) {
                                mc.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                                stats?.let { it.inCallNanos += System.nanoTime() - t0 }
                            } else {
                                mc.queueInputBuffer(index, 0, filled, firstTimestamp, 0)
                                stats?.let { it.inputs++; it.inCallNanos += System.nanoTime() - t0 }
                            }
                        } catch (e: Exception) {
                            sink.failure = e
                            finished.countDown()
                        }
                    }

                    override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                        if (finished.count == 0L) {
                            runCatching { mc.releaseOutputBuffer(index, false) }
                            return
                        }
                        val t0 = System.nanoTime()
                        try {
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                                mc.getOutputBuffer(index)?.let { out ->
                                    out.position(info.offset)
                                    out.limit(info.offset + info.size)
                                    sink.append(out.asShortBuffer(), info.presentationTimeUs)
                                    stats?.let { it.outputs++ }
                                }
                            }
                            mc.releaseOutputBuffer(index, false)
                            stats?.let { it.inCallNanos += System.nanoTime() - t0 }
                            val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            if (endOfStream || sink.isFull || isStopping) finished.countDown()
                        } catch (e: Exception) {
                            sink.failure = e
                            finished.countDown()
                        }
                    }

                    override fun onOutputFormatChanged(mc: MediaCodec, newFormat: MediaFormat) {
                        // The decoder reports the PCM layout it actually produces, which need not
                        // match the container's track format.
                        if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sink.sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            sink.channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }

                    override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                        sink.failure = e
                        finished.countDown()
                    }
                },
                Handler(callbackThread.looper)
            )
            codec.configure(format, null, null, 0)
            codec.start()

            if (!finished.await(DECODE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "Decoding timed out for ${song.title}")
            }
            sink.failure?.let { throw it }

            stats?.let { it.samples = sink.size }
            return Excerpt.resample(sink.toFloatArray(), sink.sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "Decoding failed for ${song.title}", e)
            return FloatArray(0)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            callbackThread?.quitSafely()
        }
    }

    /**
     * Mono PCM accumulator holding exactly one excerpt, and the place where the excerpt's start is
     * pinned down.
     *
     * The decoder is fed from the sync point *before* the requested start, so the samples that come
     * out first belong to the lead-in and have to go. How many of them there are follows from the
     * timestamp the codec reports for its first buffer. They are dropped as they arrive rather than
     * trimmed afterwards, which keeps the buffer the size of the excerpt instead of the size of the
     * excerpt plus however far back the sync point happened to be.
     *
     * Only ever touched from the codec callback thread while decoding, and from the calling thread
     * once the latch has been released.
     */
    private class PcmSink(sampleRate: Int, channels: Int, private val startUs: Long) {

        private var buffer = FloatArray(Excerpt.sourceSamplesNeeded(sampleRate))

        /**
         * The rate the decoder actually produces, which need not be the one the container claims.
         * Changing it resizes the buffer, because how many samples make ten seconds depends on it.
         */
        var sampleRate: Int = sampleRate
            set(value) {
                if (value == field || value <= 0) return
                field = value
                val wanted = Excerpt.sourceSamplesNeeded(value)
                if (wanted > buffer.size) buffer = buffer.copyOf(wanted)
            }

        var channels: Int = channels

        var size = 0
            private set

        @Volatile
        var failure: Exception? = null

        /** Lead-in samples still to be discarded; negative until the first buffer says how many. */
        private var toDrop = -1

        val isFull: Boolean get() = size >= buffer.size

        fun append(shorts: java.nio.ShortBuffer, presentationTimeUs: Long) {
            val channelCount = channels.coerceAtLeast(1)
            if (toDrop < 0) {
                // A decoder that reports no timestamps, or one that began past the point asked for,
                // leaves nothing to drop — taking what it gives is better than guessing.
                val lateBy = startUs - presentationTimeUs
                toDrop = if (lateBy <= 0) 0 else (lateBy * sampleRate / 1_000_000L).toInt()
            }
            while (shorts.hasRemaining() && size < buffer.size) {
                var sum = 0f
                var read = 0
                while (read < channelCount && shorts.hasRemaining()) {
                    sum += shorts.get() / 32768f
                    read++
                }
                if (read == 0) break
                if (toDrop > 0) toDrop-- else buffer[size++] = sum / read
            }
        }

        fun toFloatArray(): FloatArray = if (size == buffer.size) buffer else buffer.copyOf(size)
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) return i
        }
        return -1
    }

    private companion object {

        /**
         * How long one quiet moment is: how often the wait looks up to see what has changed.
         *
         * A second, which is short against anything it is waiting for — a conversation opening, a
         * round of fifty vectors arriving — and cheap, because looking costs a count of a map
         * already in memory. There is deliberately no constant here for *how long to wait*: that
         * is decided by what happens, not by a number. See [letVectorsArrive].
         */
        private const val ARRIVAL_PAUSE_MS = 1_000L

        /**
         * Something has arrived, so wait for the stream to really stop. Ten seconds of quiet,
         * because the gap between one conversation and the next is a few seconds and a scan that
         * sets off into the middle of a restore computes what is already on its way to it.
         */
        private const val PATIENT_ROUNDS = 10

        /** However patient, not for ever: five minutes is longer than a library takes to arrive. */
        private const val MOST_ROUNDS = 300
        const val TAG = "AiScanner"
        const val EMBEDDING_SIZE = 512
        /** Upper bound on one track's decode, so a stuck codec cannot hang the scan. */
        const val DECODE_TIMEOUT_SECONDS = 30L
    }
}
