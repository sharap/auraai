package music.ai.recommend.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.LongBuffer

class ClapTextEncoder(private val models: ModelRepository) {

    private val tokenizer = BpeTokenizer(models)
    private val mutex = Mutex()

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    /** True when encoding would need a download first. */
    fun needsDownload(): Boolean = !models.isAvailable(ModelAsset.forText)

    fun pendingDownloadBytes(): Long = models.pendingDownloadBytes(ModelAsset.forText)

    /**
     * Encodes [text] into a unit-length CLAP embedding.
     *
     * @return the embedding, or null if the weights are unavailable or inference failed — callers
     *   should fall back to plain text search rather than scoring against a zero vector, which
     *   produced a meaningless similarity for every track.
     */
    suspend fun encode(text: String): FloatArray? = withContext(Dispatchers.Default) {
        val session = session() ?: return@withContext null
        val env = ortEnv ?: return@withContext null

        try {
            // The exported graph declares a dynamic sequence_length and takes no attention_mask, so
            // the tensor is sized to the real token count. Padding it out to a fixed 77 would have
            // the model attend to the padding and skew the embedding.
            val tokens = tokenizer.tokenize(text, MAX_TOKENS)
            OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), longArrayOf(1, tokens.size.toLong())).use { tensor ->
                session.run(mapOf("input_ids" to tensor)).use { output ->
                    @Suppress("UNCHECKED_CAST")
                    val result = output.get(0).value as Array<FloatArray>
                    normalize(result[0])
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Text encoding failed", e)
            null
        }
    }

    /**
     * Downloads the text weights if needed and opens the session. Guarded by a mutex so several
     * in-flight searches cannot each build their own ~126 MB session.
     */
    private suspend fun session(): OrtSession? {
        ortSession?.let { return it }
        return mutex.withLock {
            ortSession ?: build()
        }
    }

    private suspend fun build(): OrtSession? = withContext(Dispatchers.IO) {
        try {
            if (!models.ensure(ModelAsset.forText)) return@withContext null
            val modelFile = models.fileForInference(ModelAsset.TEXT_MODEL) ?: return@withContext null

            val env = OrtEnvironment.getEnvironment()
            // See AiScanner: NNAPI measured no faster than the default CPU provider here.
            val options = OrtSession.SessionOptions()
            ortEnv = env
            env.createSession(modelFile.absolutePath, options).also {
                ortSession = it
                Log.d(TAG, "Text model loaded successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load text model", e)
            null
        }
    }

    fun release() {
        runCatching { ortSession?.close() }
        ortSession = null
        ortEnv = null
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
        const val TAG = "ClapTextEncoder"
        const val MAX_TOKENS = 77
    }
}
