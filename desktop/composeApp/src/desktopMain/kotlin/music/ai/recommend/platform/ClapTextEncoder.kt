package music.ai.recommend.platform

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import music.ai.recommend.ai.BpeTokenizer
import java.io.File
import java.nio.LongBuffer

actual class ClapTextEncoder actual constructor() {
    private val vocabFileName = "vocab.json"
    private val mergesFileName = "merges.txt"

    /** Guards session construction, so several in-flight searches cannot each build a ~126 MB one. */
    private val mutex = Mutex()

    @Volatile
    private var ortEnv: OrtEnvironment? = null

    @Volatile
    private var ortSession: OrtSession? = null

    @Volatile
    private var tokenizer: BpeTokenizer? = null

    private fun loadModel() {
        if (ortSession != null) return
        try {
            // The weights are downloaded, not bundled; a caller that has not made sure of that
            // gets no encoder rather than a half-loaded one.
            val modelFile = ModelRepository.instance.localFile(ModelAsset.TEXT_MODEL)
            if (!modelFile.isFile) return

            val vocabContent = readResourceText(vocabFileName) ?: return
            val mergesContent = readResourceText(mergesFileName) ?: return

            tokenizer = BpeTokenizer(vocabContent, mergesContent)

            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            ortEnv = env
            ortSession = env.createSession(modelFile.absolutePath, options)
            println("ClapTextEncoder: Model and tokenizer loaded successfully")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun readResourceText(name: String): String? {
        val paths = listOf(
            "composeResources/aimusic.composeapp.generated.resources/files/$name",
            "composeResources/files/$name",
            "files/$name",
            name
        )
        for (path in paths) {
            val stream = javaClass.classLoader.getResourceAsStream(path) ?: javaClass.getResourceAsStream("/$path")
            if (stream != null) {
                return stream.bufferedReader().use { it.readText() }
            }
        }
        return null
    }

    /**
     * Encodes [text] into a unit-length CLAP embedding.
     *
     * @return the embedding, or null if the weights are unavailable or inference failed — callers
     *   should fall back to plain text search rather than scoring against a zero vector, which
     *   gives a meaningless similarity for every track.
     */
    /** True when encoding would need a download first. */
    fun needsDownload(): Boolean = !ModelRepository.instance.isAvailable(ModelAsset.TEXT_MODEL)

    actual suspend fun encode(text: String): FloatArray? = withContext(Dispatchers.IO) {
        if (needsDownload()) return@withContext null
        if (ortSession == null) mutex.withLock { loadModel() }
        val session = ortSession ?: return@withContext null
        val env = ortEnv ?: return@withContext null
        val tok = tokenizer ?: return@withContext null

        try {
            // The exported graph declares a dynamic sequence_length and takes no attention_mask, so
            // the tensor is sized to the real token count. Padding it out to a fixed 77 had the
            // model attend to the padding, and since the pad id was a hard-coded 49407 — the token
            // `Ġbehav` in this vocabulary — a short query was drowned by ~72 repeats of it.
            val tokens = tok.tokenize(text, MAX_TOKENS)
            OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), longArrayOf(1, tokens.size.toLong())).use { tensor ->
                session.run(mapOf("input_ids" to tensor)).use { output ->
                    @Suppress("UNCHECKED_CAST")
                    val result = output.get(0).value as Array<FloatArray>
                    normalize(result[0])
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    actual fun release() {
        runCatching { ortSession?.close() }
        ortSession = null
        ortEnv = null
        tokenizer = null
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

    private companion object {
        const val MAX_TOKENS = 77
    }
}
