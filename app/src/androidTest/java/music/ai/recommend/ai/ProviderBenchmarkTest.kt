package music.ai.recommend.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.media.MediaFormat
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.FloatBuffer
import kotlin.math.sin
import kotlin.random.Random

/**
 * Answers two questions that decide where scan-time optimisation is worth spending effort:
 *
 *  1. Does `SessionOptions.addNnapi()` actually beat ONNX Runtime's own CPU kernels here? It is
 *     currently applied unconditionally and was never measured.
 *  2. How does feature extraction compare with inference? If inference dominates, optimising the
 *     FFT further buys very little.
 *
 * Opt-in, because it takes minutes and loads a 281 MB model several times:
 *
 *     am instrument -w -e bench true \
 *       -e class music.ai.recommend.ai.ProviderBenchmarkTest \
 *       music.ai.recommend.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class ProviderBenchmarkTest {

    private val tag = "ProviderBench"

    /**
     * The non-inference half of a scan: pulling ten seconds of PCM out of a real track, and turning
     * it into log-mel features. Cheap to run, and it decides whether pipelining the two stages (or
     * optimising the FFT further) is worth anything next to ~870 ms of inference.
     */
    @Test
    fun measureDecodeAndFeatures() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("pass -e bench true to run", args.getString("bench") == "true")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val processor = AudioProcessor()

        val audio = syntheticAudio()
        repeat(2) { processor.extractFeatures(audio) }
        val featureTimes = LongArray(FEATURE_REPS) { measure { processor.extractFeatures(audio) } }
        Log.i(tag, "features: ${summary(featureTimes)}")

        val songs = music.ai.recommend.scanner.MusicScanner()
            .scanMusic(context)
            .flatMap { it.songs }
            .take(DECODE_SONGS)
        if (songs.isEmpty()) {
            Log.i(tag, "decode: no music on device, skipped")
            return
        }

        val scanner = AiScanner(context, ModelRepository.getInstance(context), EmbeddingStore.getInstance(context))
        val decodeTimes = ArrayList<Long>(songs.size)
        val realFeatureTimes = ArrayList<Long>(songs.size)
        for (song in songs) {
            var pcm = FloatArray(0)
            decodeTimes += measure { pcm = scanner.decodeForBenchmark(song) }
            if (pcm.isEmpty()) continue
            realFeatureTimes += measure { processor.extractFeatures(pcm) }
        }
        Log.i(tag, "decode (${decodeTimes.size} real tracks): ${summary(decodeTimes.toLongArray())}")
        Log.i(tag, "features on real audio: ${summary(realFeatureTimes.toLongArray())}")
    }

    /**
     * Decodes the same tracks twice. A large gap between the passes means the cost is file I/O
     * rather than the codec, which changes what is worth optimising.
     */
    @Test
    fun measureDecodeColdVersusWarm() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("pass -e bench true to run", args.getString("bench") == "true")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val songs = music.ai.recommend.scanner.MusicScanner()
            .scanMusic(context)
            .flatMap { it.songs }
            .take(DECODE_SONGS)
        if (songs.isEmpty()) return

        val scanner = AiScanner(context, ModelRepository.getInstance(context), EmbeddingStore.getInstance(context))
        repeat(3) { pass ->
            val times = songs.map { song ->
                val stats = AiScanner.DecodeStats()
                val ms = measure { scanner.decodeForBenchmark(song, stats) }
                if (pass == 0) Log.i(tag, "  ${ms}ms $stats")
                ms
            }
            Log.i(tag, "decode pass $pass: ${summary(times.toLongArray())}")
        }
    }

    /** Splits decoding into opening/seeking the container versus actually running the codec. */
    @Test
    fun measureDecodeBreakdown() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("pass -e bench true to run", args.getString("bench") == "true")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val songs = music.ai.recommend.scanner.MusicScanner()
            .scanMusic(context)
            .flatMap { it.songs }
            .take(DECODE_SONGS)
        if (songs.isEmpty()) {
            Log.i(tag, "breakdown: no music on device")
            return
        }

        val scanner = AiScanner(context, ModelRepository.getInstance(context), EmbeddingStore.getInstance(context))
        for (song in songs) {
            val sizeMb = java.io.File(song.path).length() / 1_000_000.0
            var mime = "?"
            var openMs = 0L
            var seekMs = 0L

            val extractor = android.media.MediaExtractor()
            try {
                openMs = measure {
                    extractor.setDataSource(song.path)
                    val index = (0 until extractor.trackCount).first {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    }
                    mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: "?"
                    extractor.selectTrack(index)
                }
                val duration = extractor.getTrackFormat(0).getLong(MediaFormat.KEY_DURATION)
                val buffer = java.nio.ByteBuffer.allocate(1 shl 16)
                seekMs = measure {
                    extractor.seekTo(
                        maxOf(0L, duration / 2 - 5_000_000L),
                        android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC
                    )
                    extractor.readSampleData(buffer, 0) // forces the seek to actually resolve
                }
            } catch (e: Exception) {
                Log.i(tag, "breakdown: ${song.title} failed: ${e.message}")
                continue
            } finally {
                runCatching { extractor.release() }
            }

            val totalMs = measure { scanner.decodeForBenchmark(song) }
            Log.i(
                tag,
                "breakdown: %.1fMB %s open=%dms seek=%dms total=%dms".format(sizeMb, mime, openMs, seekMs, totalMs)
            )
        }
    }

    /**
     * Quantized against full-precision audio tower: does the int8 export load and run at all, how
     * much faster is it, and how close are the embeddings it produces?
     *
     * Downloads whichever variant is missing, so allow for a 78 MB fetch on the first run.
     */
    @Test
    fun compareAudioVariants() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("pass -e bench true to run", args.getString("bench") == "true")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = ModelRepository.getInstance(context)
        val processor = AudioProcessor()
        val features = processor.extractFeatures(syntheticAudio()).copyOf()
        val env = OrtEnvironment.getEnvironment()

        val embeddings = HashMap<AudioModelVariant, FloatArray>()
        for (variant in AudioModelVariant.entries) {
            val ready = runBlocking { models.ensure(variant.assets) }
            if (!ready) {
                Log.i(tag, "${variant.name}: unavailable, skipped")
                continue
            }
            val file = runBlocking { models.fileForInference(variant.asset) } ?: continue

            var session: OrtSession? = null
            try {
                val loadMs = measure { session = env.createSession(file.absolutePath, OrtSession.SessionOptions()) }
                val active = session ?: continue
                repeat(WARMUP) { runOnce(env, active, features) }
                val times = LongArray(INFER_REPS) { measure { runOnce(env, active, features) } }
                embeddings[variant] = embed(env, active, features)
                Log.i(
                    tag,
                    "${variant.name}: ${file.length() / 1_000_000} MB, load ${loadMs}ms, infer ${summary(times)}"
                )
            } catch (e: Throwable) {
                Log.i(tag, "${variant.name}: FAILED (${e.javaClass.simpleName}: ${e.message})")
            } finally {
                runCatching { session?.close() }
            }
        }

        val quantized = embeddings[AudioModelVariant.QUANTIZED]
        val full = embeddings[AudioModelVariant.FULL]
        if (quantized != null && full != null) {
            var dot = 0f
            for (i in quantized.indices) dot += quantized[i] * full[i]
            // Both are unit length, so the dot product is the cosine. Near 1.0 means the quantized
            // embeddings sit in essentially the same place and recommendations should hold up.
            Log.i(tag, "cosine(quantized, full) = %.4f".format(dot))
        }
    }

    private fun embed(env: OrtEnvironment, session: OrtSession, features: FloatArray): FloatArray =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(features), longArrayOf(1, 1, 1001, 64)).use { tensor ->
            session.run(mapOf("input_features" to tensor)).use { output ->
                @Suppress("UNCHECKED_CAST")
                val raw = (output.get(0).value as Array<FloatArray>)[0]
                var norm = 0f
                for (x in raw) norm += x * x
                norm = kotlin.math.sqrt(norm)
                if (norm > 0f) FloatArray(raw.size) { raw[it] / norm } else raw
            }
        }

    /**
     * The question that actually decides whether the quantized export is safe to default to: across
     * a real library, does it put the same tracks next to each other?
     *
     * A high per-track cosine is necessary but not sufficient — what the user sees in "play similar"
     * is a ranking, so this compares the neighbour lists the two models produce over the same set.
     *
     *     am instrument -w -e bench true -e tracks 40 \
     *       -e class music.ai.recommend.ai.ProviderBenchmarkTest#compareVariantsOnRealTracks ...
     */
    @Test
    fun compareVariantsOnRealTracks() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("pass -e bench true to run", args.getString("bench") == "true")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = ModelRepository.getInstance(context)
        val wanted = args.getString("tracks")?.toIntOrNull() ?: 40

        val songs = music.ai.recommend.scanner.MusicScanner()
            .scanMusic(context)
            .flatMap { it.songs }
            .distinctBy { it.id }
            .take(wanted)
        assumeTrue("not enough music on device", songs.size >= 10)

        // Decode and featurise once; both models then see byte-identical input.
        val scanner = AiScanner(context, models, EmbeddingStore.getInstance(context))
        val processor = AudioProcessor()
        val titles = ArrayList<String>()
        val featureSets = ArrayList<FloatArray>()
        for (song in songs) {
            val pcm = scanner.decodeForBenchmark(song)
            if (pcm.isEmpty()) continue
            featureSets += processor.extractFeatures(pcm).copyOf()
            titles += song.title
        }
        Log.i(tag, "prepared ${featureSets.size} tracks")
        assumeTrue("too few decodable tracks", featureSets.size >= 10)

        val env = OrtEnvironment.getEnvironment()
        val byVariant = HashMap<AudioModelVariant, Array<FloatArray>>()
        for (variant in AudioModelVariant.entries) {
            if (!runBlocking { models.ensure(variant.assets) }) continue
            val file = runBlocking { models.fileForInference(variant.asset) } ?: continue
            var session: OrtSession? = null
            try {
                session = env.createSession(file.absolutePath, OrtSession.SessionOptions())
                val active = session
                byVariant[variant] = Array(featureSets.size) { embed(env, active, featureSets[it]) }
                Log.i(tag, "${variant.name}: embedded ${featureSets.size} tracks")
            } catch (e: Throwable) {
                Log.i(tag, "${variant.name}: FAILED ${e.message}")
            } finally {
                runCatching { session?.close() }
            }
        }

        val quantized = byVariant[AudioModelVariant.QUANTIZED] ?: return
        val full = byVariant[AudioModelVariant.FULL] ?: return
        val n = quantized.size

        // 1. How far each track moves.
        val selfCos = FloatArray(n) { cosine(quantized[it], full[it]) }.sortedArray()
        Log.i(
            tag,
            "per-track cosine(quantized, full): median %.4f  min %.4f  p10 %.4f"
                .format(selfCos[n / 2], selfCos.first(), selfCos[n / 10])
        )

        // 2. How far apart different tracks are under the full model — the scale that movement has
        //    to be judged against.
        val across = ArrayList<Float>(n * (n - 1) / 2)
        for (i in 0 until n) for (j in i + 1 until n) across += cosine(full[i], full[j])
        across.sort()
        Log.i(
            tag,
            "inter-track cosine (full): median %.4f  p10 %.4f  p90 %.4f"
                .format(across[across.size / 2], across[across.size / 10], across[across.size * 9 / 10])
        )

        // 3. The ranking the user actually sees.
        val simQ = similarityMatrix(quantized)
        val simF = similarityMatrix(full)
        for (k in intArrayOf(1, 5, 10)) {
            if (k >= n) continue
            var overlap = 0
            for (i in 0 until n) {
                val a = topK(simQ, i, k).toSet()
                val b = topK(simF, i, k)
                overlap += b.count { it in a }
            }
            Log.i(tag, "top-$k neighbour agreement: %.1f%%".format(100.0 * overlap / (n * k)))
        }

        // 4. Where the full model's nearest neighbour lands in the quantized ranking.
        var worstRank = 0
        var sumRank = 0
        for (i in 0 until n) {
            val best = topK(simF, i, 1).first()
            val rank = topK(simQ, i, n - 1).indexOf(best)
            sumRank += rank
            if (rank > worstRank) worstRank = rank
        }
        Log.i(tag, "rank of full's #1 under quantized: mean %.2f  worst %d (of ${n - 1})"
            .format(sumRank.toDouble() / n, worstRank))
    }

    private fun similarityMatrix(embeddings: Array<FloatArray>): Array<FloatArray> =
        Array(embeddings.size) { i -> FloatArray(embeddings.size) { j -> cosine(embeddings[i], embeddings[j]) } }

    private fun topK(sim: Array<FloatArray>, i: Int, k: Int): List<Int> =
        sim[i].indices.filter { it != i }.sortedByDescending { sim[i][it] }.take(k)

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    /**
     * Steady-state cost of a real scan, to check that decoding and inference actually overlap.
     *
     * Sequentially the per-track cost is decode + features + inference; pipelined it should fall to
     * roughly the larger of (decode + features) and inference. Timed between consecutive progress
     * callbacks, which are exactly one pipeline cycle apart, so model loading is excluded.
     *
     * Analyses a handful of not-yet-scanned tracks for real, which is the point — anything less
     * would not exercise the handover.
     */
    @Test
    fun measureScanThroughput() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("pass -e bench true to run", args.getString("bench") == "true")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = ModelRepository.getInstance(context)
        val store = EmbeddingStore.getInstance(context)
        val count = args.getString("tracks")?.toIntOrNull() ?: 12

        val all = music.ai.recommend.scanner.MusicScanner()
            .scanMusic(context)
            .flatMap { it.songs }
            .distinctBy { it.id }
        val alreadyScanned = runBlocking { store.ids() }
        val songs = (all.filter { it.id !in alreadyScanned }.ifEmpty { all }).take(count)
        assumeTrue("not enough music on device", songs.size >= 5)

        // A fully analysed library has nothing left to scan, so drop these rows and let the run put
        // them back. Same tracks, same model, same values: the database ends up where it started.
        val database = music.ai.recommend.db.AppDatabase.getDatabase(context)
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM embeddings WHERE songId IN (${songs.joinToString(",") { it.id.toString() }})"
        )
        Log.i(tag, "library=${alreadyScanned.size} analysed, re-analysing ${songs.size}")

        val scanner = AiScanner(context, models, store)
        val stamps = ArrayList<Long>()
        runBlocking {
            scanner.scanSongs(songs) { stage ->
                if (stage is ScanStage.Scanning && stage.title.isNotEmpty()) {
                    stamps += System.nanoTime()
                }
            }
        }

        if (stamps.size < 3) {
            Log.i(tag, "throughput: too few samples (${stamps.size})")
            return
        }
        // Drop the first interval: it still contains the pipeline filling up.
        val deltas = (2 until stamps.size)
            .map { (stamps[it] - stamps[it - 1]) / 1_000_000 }
            .sorted()
        Log.i(
            tag,
            "pipelined scan: median ${deltas[deltas.size / 2]}ms/track " +
                "(min ${deltas.first()}, max ${deltas.last()}, n=${deltas.size}) " +
                "variant=${models.audioVariant.value.name}"
        )
    }

    /**
     * The same work done strictly one stage after another, as the baseline the pipeline is measured
     * against. Runs a warm-up pass over the same files first, so that neither figure is skewed by
     * cold reads — the pipelined run is timed on warm files too.
     */
    @Test
    fun measureSequentialThroughput() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("pass -e bench true to run", args.getString("bench") == "true")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = ModelRepository.getInstance(context)
        val count = args.getString("tracks")?.toIntOrNull() ?: 12
        val songs = music.ai.recommend.scanner.MusicScanner()
            .scanMusic(context).flatMap { it.songs }.distinctBy { it.id }.take(count)
        assumeTrue("not enough music", songs.size >= 5)

        val variant = models.audioVariant.value
        assumeTrue("model missing", runBlocking { models.ensure(variant.assets) })
        val file = runBlocking { models.fileForInference(variant.asset) } ?: return

        val scanner = AiScanner(context, models, EmbeddingStore.getInstance(context))
        val processor = AudioProcessor()
        val env = OrtEnvironment.getEnvironment()
        var session: OrtSession? = null
        try {
            session = env.createSession(file.absolutePath, OrtSession.SessionOptions())
            val active = session

            // Warm-up: pull every file through the page cache once.
            for (song in songs) scanner.decodeForBenchmark(song)

            val times = songs.map { song ->
                measure {
                    val pcm = scanner.decodeForBenchmark(song)
                    if (pcm.isNotEmpty()) runOnce(env, active, processor.extractFeatures(pcm))
                }
            }.sorted()
            Log.i(
                tag,
                "sequential scan: median ${times[times.size / 2]}ms/track " +
                    "(min ${times.first()}, max ${times.last()}, n=${times.size}) variant=${variant.name}"
            )
        } finally {
            runCatching { session?.close() }
        }
    }

    /**
     * Re-derives embeddings for tracks already in the database and compares them with what is
     * stored. The rewrite swapped the decoder from polling to callbacks; if it changed the PCM at
     * all, every embedding in the library would silently drift, so this has to come out at 1.0.
     */
    @Test
    fun verifyDecodeStillProducesSameEmbeddings() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("pass -e bench true to run", args.getString("bench") == "true")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = ModelRepository.getInstance(context)
        val store = EmbeddingStore.getInstance(context)
        val stored = runBlocking { store.all() }
        val count = args.getString("tracks")?.toIntOrNull() ?: 8

        val songs = music.ai.recommend.scanner.MusicScanner()
            .scanMusic(context).flatMap { it.songs }.distinctBy { it.id }
            .filter { stored.containsKey(it.id) }
            .take(count)
        assumeTrue("nothing analysed to compare against", songs.size >= 3)

        val variant = models.audioVariant.value
        val file = runBlocking { models.fileForInference(variant.asset) } ?: return
        val scanner = AiScanner(context, models, store)
        val processor = AudioProcessor()
        val env = OrtEnvironment.getEnvironment()
        var session: OrtSession? = null
        try {
            session = env.createSession(file.absolutePath, OrtSession.SessionOptions())
            val active = session
            val cosines = ArrayList<Float>()
            for (song in songs) {
                val pcm = scanner.decodeForBenchmark(song)
                if (pcm.isEmpty()) {
                    Log.i(tag, "decode-check: ${song.title} produced no audio")
                    continue
                }
                val fresh = embed(env, active, processor.extractFeatures(pcm))
                cosines += cosine(fresh, stored.getValue(song.id))
            }
            cosines.sort()
            Log.i(
                tag,
                "decode-check vs stored (${cosines.size} tracks, variant=${variant.name}): " +
                    "min %.6f median %.6f max %.6f".format(cosines.first(), cosines[cosines.size / 2], cosines.last())
            )
        } finally {
            runCatching { session?.close() }
        }
    }

    /**
     * Does fixing the log-mel features actually improve retrieval?
     *
     * The embeddings already in the database were produced by the old extractor; this recomputes
     * them with the corrected one and scores both against the same text queries. Text and audio
     * share a projection space, so the cosine says how well an audio embedding lines up with a
     * description. If the old features were out of distribution, every track scored much the same
     * for any query — a low spread — and the fix should raise both the peak and the spread.
     */
    @Test
    fun compareTextAlignmentOldVersusNewFeatures() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("pass -e bench true to run", args.getString("bench") == "true")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = ModelRepository.getInstance(context)
        val store = EmbeddingStore.getInstance(context)
        val old = runBlocking { store.all() }
        val count = args.getString("tracks")?.toIntOrNull() ?: 30

        val songs = music.ai.recommend.scanner.MusicScanner()
            .scanMusic(context).flatMap { it.songs }.distinctBy { it.id }
            .filter { old.containsKey(it.id) }
            .take(count)
        assumeTrue("nothing stored to compare with", songs.size >= 10)

        val file = runBlocking { models.fileForInference(models.audioVariant.value.asset) } ?: return
        val scanner = AiScanner(context, models, store)
        val processor = AudioProcessor()
        val env = OrtEnvironment.getEnvironment()

        val fresh = HashMap<Long, FloatArray>()
        var session: OrtSession? = null
        try {
            session = env.createSession(file.absolutePath, OrtSession.SessionOptions())
            for (song in songs) {
                val pcm = scanner.decodeForBenchmark(song)
                if (pcm.isEmpty()) continue
                fresh[song.id] = embed(env, session, processor.extractFeatures(pcm))
            }
        } finally {
            runCatching { session?.close() }
        }
        val usable = songs.filter { fresh.containsKey(it.id) }
        Log.i(tag, "alignment: ${usable.size} tracks recomputed")

        val encoder = ClapTextEncoder(models)
        val queries = listOf(
            "heavy metal guitar", "calm solo piano", "electronic dance music",
            "female singing voice", "acoustic folk guitar", "aggressive fast drums",
            "ambient atmospheric soundscape", "hip hop beat with bass"
        )
        var oldSpread = 0.0
        var newSpread = 0.0
        var oldPeak = 0.0
        var newPeak = 0.0
        for (query in queries) {
            val q = runBlocking { encoder.encode(query) } ?: continue
            val oldSims = usable.map { cosine(q, old.getValue(it.id)) }
            val newSims = usable.map { cosine(q, fresh.getValue(it.id)) }
            oldSpread += stdDev(oldSims); newSpread += stdDev(newSims)
            oldPeak += oldSims.max(); newPeak += newSims.max()
            Log.i(
                tag,
                "  %-32s old: max %.4f sd %.4f   new: max %.4f sd %.4f"
                    .format(query, oldSims.max(), stdDev(oldSims), newSims.max(), stdDev(newSims))
            )
        }
        encoder.release()
        val n = queries.size
        Log.i(
            tag,
            "alignment mean over %d queries — old: peak %.4f spread %.4f | new: peak %.4f spread %.4f"
                .format(n, oldPeak / n, oldSpread / n, newPeak / n, newSpread / n)
        )
    }

    private fun stdDev(values: List<Float>): Double {
        val mean = values.average()
        return kotlin.math.sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    @Test
    fun compareExecutionProviders() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("pass -e bench true to run", args.getString("bench") == "true")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = ModelRepository.getInstance(context)
        assumeTrue("audio model not on device", models.isAvailable(AudioModelVariant.QUANTIZED.assets))
        val modelFile = runBlocking { models.fileForInference(AudioModelVariant.QUANTIZED.asset) }
        assumeTrue("audio model file missing", modelFile != null)

        val cores = Runtime.getRuntime().availableProcessors()
        Log.i(tag, "=== device: $cores cores, model ${modelFile!!.length() / 1_000_000} MB ===")

        // ---- feature extraction, the part the FFT rewrite would touch ----
        val audio = syntheticAudio()
        val processor = AudioProcessor()
        repeat(2) { processor.extractFeatures(audio) }
        val featureTimes = LongArray(FEATURE_REPS) {
            measure { processor.extractFeatures(audio) }
        }
        Log.i(tag, "features: ${summary(featureTimes)}")

        val features = processor.extractFeatures(audio).copyOf()

        // ---- inference, per execution provider ----
        val configs = buildList {
            add("cpu-default" to { o: OrtSession.SessionOptions -> })
            add("nnapi" to { o: OrtSession.SessionOptions -> o.addNnapi() })
            for (threads in intArrayOf(1, 2, 4, cores)) {
                add("cpu-${threads}t" to { o: OrtSession.SessionOptions -> o.setIntraOpNumThreads(threads) })
            }
            add("xnnpack" to { o: OrtSession.SessionOptions ->
                o.setIntraOpNumThreads(1) // XNNPACK manages its own pool
                o.addXnnpack(mapOf("intra_op_num_threads" to "4"))
            })
        }

        val env = OrtEnvironment.getEnvironment()
        for ((name, configure) in configs) {
            var session: OrtSession? = null
            try {
                val options = OrtSession.SessionOptions()
                configure(options)
                val loadMs = measure { session = env.createSession(modelFile.absolutePath, options) }
                val active = session ?: continue

                repeat(WARMUP) { runOnce(env, active, features) }
                val times = LongArray(INFER_REPS) { measure { runOnce(env, active, features) } }
                Log.i(tag, "$name: load ${loadMs}ms, infer ${summary(times)}")
            } catch (e: Throwable) {
                Log.i(tag, "$name: UNAVAILABLE (${e.javaClass.simpleName}: ${e.message})")
            } finally {
                runCatching { session?.close() }
            }
        }
        Log.i(tag, "=== done ===")
    }

    private fun runOnce(env: OrtEnvironment, session: OrtSession, features: FloatArray) {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(features), longArrayOf(1, 1, 1001, 64)).use { tensor ->
            session.run(mapOf("input_features" to tensor)).use { it.get(0).value }
        }
    }

    /** Ten seconds of tone plus noise at 48 kHz — the shape the scanner really feeds in. */
    private fun syntheticAudio(): FloatArray {
        val random = Random(7)
        return FloatArray(481_024) { i ->
            (0.4 * sin(2 * Math.PI * 440 * i / 48000) +
                0.2 * sin(2 * Math.PI * 3150 * i / 48000) +
                0.05 * (random.nextFloat() - 0.5f)).toFloat()
        }
    }

    private inline fun measure(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun summary(times: LongArray): String {
        val sorted = times.sortedArray()
        return "median ${sorted[sorted.size / 2]}ms (min ${sorted.first()}, max ${sorted.last()}, n=${times.size})"
    }

    private companion object {
        const val WARMUP = 2
        const val INFER_REPS = 5
        const val FEATURE_REPS = 5
        const val DECODE_SONGS = 8
    }
}
