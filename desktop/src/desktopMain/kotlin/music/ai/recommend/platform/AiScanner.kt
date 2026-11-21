package music.ai.recommend.platform

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import music.ai.recommend.sync.PeerPresence
import music.ai.recommend.ai.AnalysisBacklog
import music.ai.recommend.ai.AudioProcessor
import music.ai.recommend.ai.DesktopAudioDecoder
import music.ai.recommend.ai.Excerpt
import music.ai.recommend.ai.SharedDecoder
import music.ai.recommend.db.AppDatabase
import music.ai.recommend.db.EmbeddingEntity
import music.ai.recommend.db.getAppDatabase
import music.ai.recommend.db.DesktopMusicDao
import kotlinx.coroutines.delay
import music.ai.recommend.ai.SoundPrint
import music.ai.recommend.model.Song
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

actual class AiScanner actual constructor() {
    private val models = ModelRepository.instance
    private val db = getAppDatabase()
    private val audioProcessor = AudioProcessor()
    private val audioDecoder = DesktopAudioDecoder()

    // Strict single-threaded mode for absolute stability. AudioProcessor reuses its working
    // buffers between calls, so this permit is also what keeps featurisation correct.
    private val semaphore = Semaphore(1)

    @Volatile
    private var ortEnv: OrtEnvironment? = null

    @Volatile
    private var ortSession: OrtSession? = null

    // Written by stop() from the UI thread, read from the scan coroutines.
    @Volatile
    private var isStopping = false

    /** Set when something has gone wrong for the whole library, not just one track. */
    @Volatile
    private var fatalError: String? = null

    private suspend fun loadModel(onStatus: (String) -> Unit): Boolean {
        if (ortSession != null) return true
        // 268 MB over the network the first time; the scan cannot start without it, so the
        // download is part of starting rather than something the user has to arrange first.
        if (!models.isAvailable(ModelAsset.AUDIO_MODEL)) {
            onStatus("Downloading the audio model...")
            if (!models.ensure(listOf(ModelAsset.AUDIO_MODEL))) {
                onStatus("Download failed: ${models.progress.value.error ?: "unknown error"}")
                return false
            }
        }
        return try {
            val modelFile = models.localFile(ModelAsset.AUDIO_MODEL)
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            ortEnv = env
            ortSession = env.createSession(modelFile.absolutePath, options)
            println("AiScanner: Model loaded successfully")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            onStatus("Error loading AI model")
            false
        }
    }

    actual fun stop() {
        isStopping = true
    }

    /**
     * Frees the ~280 MB inference session. Worth doing as soon as a scan ends: the text encoder
     * holds its own session, and keeping both alive is what pushes a modest machine into swap.
     */
    actual fun release() {
        runCatching { ortSession?.close() }
        ortSession = null
        ortEnv = null
    }

    /**
     * Names the backlog by its sound, so that what is asked for can be recognised.
     *
     * Cheap enough to do for the whole backlog: measured at 0.19 ms a file here. The prints are
     * usually already there — the library pass takes them — and this is for the tracks that have
     * only just appeared.
     */
    private suspend fun takePrints(songs: List<music.ai.recommend.model.Song>) {
        if (songs.isEmpty()) return
        try {
            val library = songs.associate { it.path to it.path }
            val held = db.musicDao().soundPrints()
            val wanted = SoundPrint.missing(
                tracks = library,
                // Only rows that actually carry a print count as having one. A row may hold a
                // stamp and an excerpt start and no print at all — naming an excerpt is asked of
                // every track a peer mentions, hashing only of the ones about to be analysed — and
                // reading such a row as "already hashed" is how a track quietly never gets one.
                held = held.mapNotNull { (path, row) -> row.print?.let { path to row.stamp } }.toMap(),
                stampOf = ::stampOf
            )
            if (wanted.isEmpty()) return
            val taken = LinkedHashMap<String, music.ai.recommend.db.MusicDao.FilePrint>()
            for (path in wanted) {
                val stamp = stampOf(path)
                if (stamp.isEmpty()) continue
                val print = SoundPrint.of(File(path)) ?: continue
                taken[path] = music.ai.recommend.db.MusicDao.FilePrint(
                    stamp = stamp,
                    print = print,
                    excerptStartMs = held[path]?.takeIf { it.stamp == stamp }?.excerptStartMs
                )
            }
            if (taken.isNotEmpty()) db.musicDao().rememberSoundPrints(taken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("AiScanner: could not take the prints of the backlog: ${e.message}")
        }
    }

    /**
     * Waits while vectors are still arriving from the other device, and no longer.
     *
     * The moment a scan starts, the app asks its peer for everything this machine is missing, and
     * what comes back costs nothing to use. Analysing a track the other device has already
     * analysed is the one kind of work here that is purely wasted.
     *
     * Bounded by what is actually happening rather than by a timer: while the number of vectors
     * held keeps growing, something is being handed over and waiting is right; the first quiet
     * moment means there is nobody to wait for, which is also the answer when there is no other
     * device at all.
     *
     * @return true when something arrived, so the caller knows to look again.
     */
    /**
     * Stands aside while a conversation with the other device is open, and says that it is.
     *
     * The analysis holds a permit and runs the model; a conversation at the same time is two jobs
     * on one processor, and the one arriving over the socket is work somebody else has already
     * done. Between tracks and not inside one, so whatever is being decoded now finishes.
     *
     * Measured on the pair this was written against: a round of fifty vectors took 0.6 s while the
     * other device was analysing and 0.35 s while it was not. Polled briskly rather than once a
     * second, because the signal includes this device's own dial attempts and a failing one lasts
     * milliseconds; bounded by [ASIDE_ROUNDS] rather than by trust in the socket.
     */
    private suspend fun stepAsideForTheConversation(
        done: Int,
        total: Int,
        onProgress: (Float, String, Int, Long) -> Unit
    ) {
        if (!PeerPresence.conversationInFlight()) return
        val from = System.currentTimeMillis()
        var held = db.musicDao().getAllEmbeddings().size
        var movedAt = from
        var rounds = 0
        while (!isStopping && PeerPresence.conversationInFlight() && rounds++ < ASIDE_ROUNDS) {
            // Said, so that an analysis which has visibly stopped says why it has stopped.
            val dealtWith = db.musicDao().dealtWithNow() ?: done
            onProgress(
                dealtWith.toFloat() / total.coerceAtLeast(1),
                "Taking what the other device already has...",
                dealtWith,
                -1L
            )
            delay(ASIDE_POLL_MS)
            val now = db.musicDao().getAllEmbeddings().size
            if (now > held) {
                held = now
                movedAt = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - movedAt >= ASIDE_QUIET_MS) {
                // The socket is open and nothing is coming down it, which is what the other device
                // disappearing looks like from here: a dropped peer holds a connection until the
                // read times out, twenty seconds, and there is work here that nobody is sending.
                break
            }
        }
        println(
            "AiScanner: stood aside for ${System.currentTimeMillis() - from} ms; " +
                "$held vectors held when the analysis resumed"
        )
    }

    private suspend fun letVectorsArrive(waiting: Int, onArrival: (Int) -> Unit): Boolean {
        if (waiting == 0) return false
        // Nobody has announced themselves, so there is nobody to ask and nothing to wait for; see
        // PeerPresence. A library with one device pays nothing at all for this.
        if (!PeerPresence.somebodyNear()) return false
        val conversationsBefore = PeerPresence.conversationsHad()
        var held = db.musicDao().getAllEmbeddings().size
        val before = held
        var quiet = 0
        var rounds = 0
        while (!isStopping && rounds++ < MOST_ROUNDS) {
            delay(ARRIVAL_PAUSE_MS)
            val now = db.musicDao().getAllEmbeddings().size
            if (now > held) {
                quiet = 0
                held = now
                // Said, because otherwise this is a minute of a screen that claims to be starting
                // and visibly is not.
                onArrival(held)
            } else {
                quiet++
                // The other device answered and brought nothing of ours, so nothing is coming.
                // An event rather than a count of seconds: the two devices are not equally fast,
                // and a constant that suits one is wrong on the other.
                if (held == before && PeerPresence.conversationsHad() > conversationsBefore) break
            }
            // Something has arrived, so more is probably coming, and the gaps between one round and
            // the next are seconds: measured once at two seconds of patience, a phone set off after
            // half its library had arrived and computed 2308 vectors that were on their way to it.
            if (held > before && quiet >= PATIENT_ROUNDS) break
        }
        return held > before
    }

    actual suspend fun scanSongs(
        songs: List<Song>,
        onProgress: (Float, String, Int, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        isStopping = false
        fatalError = null

        // Checked before the model is unpacked: without ffmpeg every track would "fail to decode",
        // and the scan would report success having analysed nothing.
        if (!audioDecoder.isAvailable) {
            onProgress(0f, "ffmpeg not found. Install ffmpeg and add it to PATH.", 0, -1L)
            return@withContext
        }
        if (!loadModel { status -> onProgress(0f, status, 0, -1L) }) return@withContext

        try {
            val dao = db.musicDao()

            // A vector whose file is gone is no longer thrown away here, and that is deliberate.
            // The listening history outlives the files: a track deleted a year ago is still in the
            // profile and still in the daily playlist, and its vector is the only record of what it
            // sounded like. Dropping it quietly on the next scan would leave history nothing could
            // place. The owner clears them from the settings screen instead, and the ones the
            // history still mentions stay even then.
            val scannedPathsInDb = dao.getAllEmbeddings().mapTo(HashSet()) { it.path }
            // Files refused earlier are left alone unless the file itself has changed since; see
            // AnalysisBacklog. Without it a library's broken files — a download that stopped at its
            // ID3 tag, a playlist saved as .mp3 — would be decoded again on every scan for ever.
            val pending = AnalysisBacklog.pending(
                tracks = songs.map { it.path },
                analysed = scannedPathsInDb,
                refusals = dao.refusals(),
                stampOf = ::stampOf
            ).toHashSet()
            var songsToProcess = songs.filter { it.path in pending }

            // Asking is a hundred times cheaper than answering. A print costs a fifth of a
            // millisecond — a seek and a quarter of a megabyte — and lets the other device
            // recognise the track by its sound; analysing the same track costs seconds of decoding
            // and inference. So the backlog is named first, and whatever the other device is still
            // handing over is given a moment to arrive before any of it is computed here.
            takePrints(songsToProcess)
            if (letVectorsArrive(songsToProcess.size) { held ->
                    onProgress(
                        held.toFloat() / songs.size.coerceAtLeast(1),
                        "Taking what the other device already has...",
                        held,
                        -1L
                    )
                }
            ) {
                val arrived = dao.getAllEmbeddings().mapTo(HashSet()) { it.path }
                songsToProcess = songsToProcess.filter { it.path !in arrived }
                println("AiScanner: after asking the other device, ${songsToProcess.size} are left")
            }

            val alreadyScannedCount = songs.size - songsToProcess.size
            val totalToProcess = songsToProcess.size

            if (totalToProcess == 0) {
                onProgress(1f, "Complete", songs.size, 0L)
                return@withContext
            }

            onProgress(alreadyScannedCount.toFloat() / songs.size, "Starting analysis...", alreadyScannedCount, -1L)

            val startTime = System.currentTimeMillis()
            val processedInThisSession = AtomicInteger(0)

            val jobs = songsToProcess.map { song ->
                async {
                    if (isStopping || fatalError != null) return@async
                    semaphore.withPermit {
                        if (isStopping || fatalError != null) return@async
                        stepAsideForTheConversation(
                            alreadyScannedCount + processedInThisSession.get(),
                            songs.size,
                            onProgress
                        )
                        if (isStopping || fatalError != null) return@async
                        try {
                            withTimeoutOrNull(45.seconds) {
                                // What this machine has dealt with now, asked afresh, and not
                                // the count taken when the analysis started plus what it has done
                                // since. The stale version is how a bar collapses from nine tenths
                                // to a twentieth the moment a handover ends: the vectors that just
                                // arrived are missing from the baseline, so the screen forgets
                                // them. Same quantity the handover itself reports.
                                val dealtWith = db.musicDao().dealtWithNow()
                                    ?: (alreadyScannedCount + processedInThisSession.get())
                                onProgress(
                                    dealtWith.toFloat() / songs.size,
                                    "Analyzing: ${song.title}",
                                    dealtWith,
                                    -1L
                                )

                                if (!File(song.path).exists()) return@withTimeoutOrNull
                                // It may have arrived since the list was made: a pass runs while
                                // this walks the library, and a vector in hand is worth more than
                                // the one this would compute.
                                if (dao.hasEmbedding(song.path)) return@withTimeoutOrNull

                                val audioData = decodeExcerpt(song.path, song.duration)

                                // A track that would not decode is remembered as a refusal, so the
                                // next scan does not keep retrying it — but as a refusal and not as
                                // a vector of zeros, so it stays out of the similarity space, is
                                // never offered to another device as though it had been analysed,
                                // and is reconsidered if the file is ever replaced by a whole one.
                                if (audioData.isEmpty()) {
                                    dao.refuseEmbedding(song.path, stampOf(song.path))
                                } else {
                                    val embedding = runInference(audioProcessor.extractFeatures(audioData))
                                    dao.insertEmbedding(EmbeddingEntity(song.path, embedding.toList()))
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: DesktopAudioDecoder.DecodeUnavailable) {
                            // The installation is broken, not this track: stop rather than write
                            // a library's worth of zero vectors that would never be retried.
                            fatalError = "ffmpeg stopped working: ${e.message}"
                        } catch (e: Exception) {
                            println("AiScanner: Error on ${song.title}: ${e.message}")
                        } finally {
                            val currentCount = processedInThisSession.incrementAndGet()
                            val currentTime = System.currentTimeMillis()
                            val elapsed = currentTime - startTime
                            val avgTimePerSong = elapsed / currentCount.coerceAtLeast(1)
                            val remainingCount = totalToProcess - currentCount
                            val etrSeconds = (remainingCount * avgTimePerSong) / 1000

                            val dealtWithNow = db.musicDao().dealtWithNow()
                                ?: (alreadyScannedCount + currentCount)
                            onProgress(
                                dealtWithNow.toFloat() / songs.size,
                                song.title,
                                dealtWithNow,
                                etrSeconds
                            )

                            if (currentCount % 10 == 0) (dao as? DesktopMusicDao)?.forceSave()
                        }
                    }
                }
            }

            jobs.awaitAll()
            (dao as? DesktopMusicDao)?.forceSave()

            val failure = fatalError
            when {
                failure != null -> onProgress(1f, failure, songs.size, 0L)
                !isStopping -> onProgress(1f, "Complete", songs.size, 0L)
            }
        } finally {
            // Also runs when the user stops the scan, so the ~280 MB session is not left resident.
            release()
        }
    }

    /**
     * What the file looks like now, for remembering or expiring a refusal.
     *
     * A length of zero — a file that has gone, or that cannot be read at all — gives an empty
     * stamp, which [AnalysisBacklog] reads as "do not reconsider on this evidence".
     */
    private fun stampOf(path: String): String = try {
        val file = File(path)
        AnalysisBacklog.stamp(file.length(), file.lastModified())
    } catch (e: SecurityException) {
        ""
    }

    private fun runInference(features: FloatArray): FloatArray {
        val session = ortSession ?: return FloatArray(EMBEDDING_SIZE)
        val env = ortEnv ?: return FloatArray(EMBEDDING_SIZE)
        return try {
            // CLAP expects [batch, 1, frames, mels] -> [1, 1, 1001, 64]
            val shape = longArrayOf(1, 1, FRAMES, MELS)
            OnnxTensor.createTensor(env, FloatBuffer.wrap(features), shape).use { tensor ->
                session.run(mapOf("input_features" to tensor)).use { output ->
                    @Suppress("UNCHECKED_CAST")
                    val result = output.get(0).value as Array<FloatArray>
                    normalize(result[0])
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
     * The excerpt, from whichever decoder is right for the file.
     *
     * mp3, flac and wav go to the shared decoders in `ai/`, which are the same code the Android
     * app runs. For mp3 the platform decoders disagree about where a frame sits — measured against
     * ffmpeg, Android's begins its output 576 samples later and its seek over a variable bitrate
     * lands hundreds of samples further off again. For flac the samples do agree, being lossless,
     * but everything around them does not: this side used to hand ffmpeg a start in seconds and
     * ask it for 48 kHz, so its own resampler ran where the phone runs [Excerpt.resample]. Either
     * way an embedding from one device was useless on the other.
     *
     * What is left for ffmpeg is m4a and the rest, whose embeddings stay on this machine until
     * those formats have a shared decoder too.
     */
    private fun decodeExcerpt(path: String, durationMs: Long): FloatArray {
        if (SharedDecoder.handles(path)) {
            val samples = runCatching { SharedDecoder.decode(File(path)) }.getOrElse { FloatArray(0) }
            if (samples.isNotEmpty()) return samples
            println("AiScanner: shared decoder gave nothing for $path, using ffmpeg")
        }
        return audioDecoder.decodeChunk(path, CHUNK_MS, durationMs)
    }

    private companion object {

        /**
         * How long one quiet moment is: how often the wait looks up to see what has changed.
         *
         * A second, which is short against anything it is waiting for and cheap to ask. There is
         * deliberately no constant for *how long to wait*: that is decided by what happens, not by
         * a number. See [letVectorsArrive].
         */
        private const val ARRIVAL_PAUSE_MS = 1_000L

        /** How often the pause between tracks looks up; see [stepAsideForTheConversation]. */
        private const val ASIDE_POLL_MS = 200L

        /** The same five minutes as [MOST_ROUNDS], counted in the briefer poll above. */
        private const val ASIDE_ROUNDS = 1_500

        /**
         * How long the pause tolerates an open conversation that is sending nothing.
         *
         * Three seconds, against a round of fifty vectors measured at 0.35 s: five slow rounds, so
         * a live handover is never cut short, while a peer that vanished mid-conversation does not
         * cost the twenty idle seconds its socket takes to time out.
         */
        private const val ASIDE_QUIET_MS = 3_000L

        /**
         * Something has arrived, so wait for the stream to really stop. Ten seconds of quiet,
         * because the gap between one conversation and the next is a few seconds.
         */
        private const val PATIENT_ROUNDS = 10

        /** However patient, not for ever: five minutes is longer than a library takes to arrive. */
        private const val MOST_ROUNDS = 300

        const val EMBEDDING_SIZE = 512
        const val CHUNK_MS = Excerpt.LENGTH_MS
        const val FRAMES = 1001L
        const val MELS = 64L
    }
}
