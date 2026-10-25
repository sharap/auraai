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

/**
 * A second door into CLAP's text space, for queries written in Russian.
 *
 * CLAP's own text tower only ever saw English. Fed Russian it answers with something plausible and
 * meaningless: measured against the thirty-five prompts this app names albums with, a Russian query
 * landed on the right one 0% of the time, at a mean rank of 18 out of 35 — chance. The fix is the
 * Multilingual-CLIP recipe: a small multilingual encoder (`sergeyzh/rubert-mini-sts`, 33M
 * parameters) trained to reproduce what CLAP's text tower produces for the English translation of
 * the same phrase. Audio takes no part in it, so every embedding already in the library stays
 * valid — the student simply arrives at the same place from another language. On the same thirty-five
 * prompts it lands first 77% of the time and within three 91%.
 *
 * Bigger students were tried. A twelve-layer one (LaBSE-en-ru, 129M) is better again — a Russian
 * query reproduces half of what its English twin returns, against 45% here — but it does not
 * survive int8: a single layer of it costs more accuracy than this whole model does, and shipping
 * it would mean half a gigabyte.
 *
 * Trained against *this app's* quantised CLAP export, not a fresh checkpoint from the Hub: the
 * student has to match the embeddings the app actually searches with.
 */
class RuTextEncoder(private val models: ModelRepository) {

    private val tokenizer = WordPieceTokenizer { models.openStream(ModelAsset.RU_VOCAB) }
    private val mutex = Mutex()

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    /** True when encoding would need a download first. */
    fun needsDownload(): Boolean = !models.isAvailable(ModelAsset.forRussianText)

    fun pendingDownloadBytes(): Long = models.pendingDownloadBytes(ModelAsset.forRussianText)

    /**
     * Encodes [text] into a unit-length vector in CLAP's text space.
     *
     * @return the embedding, or null when the weights are unavailable or inference failed; callers
     *   fall back to plain text search rather than scoring every track against nonsense.
     */
    suspend fun encode(text: String): FloatArray? = withContext(Dispatchers.Default) {
        val session = session() ?: return@withContext null
        val env = ortEnv ?: return@withContext null

        try {
            val tokens = tokenizer.tokenize(text, WordPieceTokenizer.MAX_TOKENS)
            val shape = longArrayOf(1, tokens.size.toLong())
            // Unlike CLAP's graph this one takes a mask, so padding would be harmless; there is
            // simply none to apply, since one query is one row of its own length.
            val mask = LongArray(tokens.size) { 1L }
            OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), shape).use { ids ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).use { attention ->
                    session.run(mapOf("input_ids" to ids, "attention_mask" to attention)).use { output ->
                        @Suppress("UNCHECKED_CAST")
                        val result = output.get(0).value as Array<FloatArray>
                        // The graph normalises already; doing it again costs 512 multiplications
                        // and removes any doubt about what a cosine against it means.
                        normalize(result[0])
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Russian text encoding failed", e)
            null
        }
    }

    /** Guarded by a mutex: two searches in flight must not each build their own 33 MB session. */
    private suspend fun session(): OrtSession? {
        ortSession?.let { return it }
        return mutex.withLock { ortSession ?: build() }
    }

    private suspend fun build(): OrtSession? = withContext(Dispatchers.IO) {
        try {
            if (!models.ensure(ModelAsset.forRussianText)) return@withContext null
            val modelFile = models.fileForInference(ModelAsset.RU_TEXT_MODEL) ?: return@withContext null

            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            ortEnv = env
            env.createSession(modelFile.absolutePath, options).also {
                ortSession = it
                Log.d(TAG, "Russian text model loaded")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load the Russian text model", e)
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
        if (norm > 0f) for (i in v.indices) v[i] /= norm
        return v
    }

    companion object {
        private const val TAG = "RuTextEncoder"

        /**
         * Whether a query should go through this encoder rather than CLAP's own.
         *
         * Any Cyrillic at all is enough. A mixed query ("Мельница remix") goes here too: a fifth of
         * the student's training fed it the English side precisely so that it stays usable for
         * English, while CLAP would make nothing of the Cyrillic half.
         */
        fun handles(text: String): Boolean = text.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
    }
}
