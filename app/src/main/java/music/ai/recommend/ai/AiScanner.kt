package music.ai.recommend.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import music.ai.recommend.model.Song
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

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
    private val embeddings: EmbeddingStore
) {
    private val appContext = context.applicationContext
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
        val songsToProcess = songs.filter { it.id !in scannedIds }
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
                        // A track that would not decode is still stored, as a zero vector, so the
                        // next scan does not keep retrying it. Cosine similarity against it is
                        // zero, so it never surfaces as a recommendation.
                        val embedding = item.features?.let { runInference(it) } ?: FloatArray(EMBEDDING_SIZE)
                        embeddings.put(item.song.id, embedding)
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
    private suspend fun decodeInto(channel: SendChannel<Prepared>, songs: List<Song>) {
        val buffers = arrayOf(FloatArray(AudioProcessor.FEATURE_SIZE), FloatArray(AudioProcessor.FEATURE_SIZE))
        var slot = 0
        try {
            for (song in songs) {
                if (isStopping) break
                currentCoroutineContext().ensureActive()

                val features = try {
                    val pcm = decodeAudioChunk(song, CHUNK_MS)
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

    /** Decode timing is measured separately from inference; see ProviderBenchmarkTest. */
    internal fun decodeForBenchmark(song: Song, stats: DecodeStats? = null): FloatArray =
        decodeAudioChunk(song, CHUNK_MS, stats)

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
     * Decodes [durationMs] from the middle of the track and returns mono 48 kHz samples.
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
    private fun decodeAudioChunk(song: Song, durationMs: Long, stats: DecodeStats? = null): FloatArray {
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
            val startTimeUs = max(0L, (duration / 2) - (durationMs * 1000 / 2))
            extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return FloatArray(0)
            val initialRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val initialChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

            // Enough room for the requested span at the source rate, plus slack for a late sync frame.
            val sink = PcmSink(
                buffer = FloatArray(((durationMs / 1000.0) * initialRate).toInt() + initialRate),
                sampleRate = initialRate,
                channels = initialChannels
            )
            val endTimeUs = startTimeUs + durationMs * 1000
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
                                    if (extractor.sampleTime > endTimeUs || extractor.sampleTime < 0) {
                                        inputDone = true
                                        break
                                    }
                                    val next = extractor.sampleSize
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
                                    sink.append(out.asShortBuffer())
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
            return resample(sink.toFloatArray(), sink.sampleRate, TARGET_SAMPLE_RATE)
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
     * Mono PCM accumulator. Only ever touched from the codec callback thread while decoding, and
     * from the calling thread once the latch has been released.
     */
    private class PcmSink(private val buffer: FloatArray, var sampleRate: Int, var channels: Int) {
        var size = 0
            private set

        @Volatile
        var failure: Exception? = null

        val isFull: Boolean get() = size >= buffer.size

        fun append(shorts: java.nio.ShortBuffer) {
            val channelCount = channels.coerceAtLeast(1)
            while (shorts.hasRemaining() && size < buffer.size) {
                var sum = 0f
                var read = 0
                while (read < channelCount && shorts.hasRemaining()) {
                    sum += shorts.get() / 32768f
                    read++
                }
                if (read > 0) buffer[size++] = sum / read
            }
        }

        fun toFloatArray(): FloatArray = if (size == buffer.size) buffer else buffer.copyOf(size)
    }

    private fun resample(data: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || data.isEmpty()) return data
        val ratio = from.toDouble() / to.toDouble()
        val newSize = (data.size / ratio).toInt()
        val resampled = FloatArray(newSize)
        for (i in 0 until newSize) {
            val oldPos = i * ratio
            val index = oldPos.toInt()
            val fraction = (oldPos - index).toFloat()
            resampled[i] = if (index + 1 < data.size) {
                data[index] * (1 - fraction) + data[index + 1] * fraction
            } else {
                data[data.size - 1]
            }
        }
        return resampled
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) return i
        }
        return -1
    }

    private companion object {
        const val TAG = "AiScanner"
        const val EMBEDDING_SIZE = 512
        const val CHUNK_MS = 10_000L
        const val TARGET_SAMPLE_RATE = 48000
        /** Upper bound on one track's decode, so a stuck codec cannot hang the scan. */
        const val DECODE_TIMEOUT_SECONDS = 30L
    }
}
