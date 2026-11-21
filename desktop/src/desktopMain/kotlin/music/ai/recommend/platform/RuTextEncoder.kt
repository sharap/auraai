package music.ai.recommend.platform

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import music.ai.recommend.ai.WordPieceTokenizer
import java.nio.LongBuffer

/**
 * A second door into CLAP's text space, for queries written in Russian.
 *
 * CLAP's own text tower only ever saw English. Fed Russian it answers with something plausible and
 * meaningless: measured on this library, a Russian query for "man singing in russian" put nothing
 * right in its first five, while the same query through this encoder got four of five. The fix is
 * the Multilingual-CLIP recipe: a small multilingual encoder (`sergeyzh/rubert-mini-sts`, 33M
 * parameters) trained to reproduce what CLAP's text tower produces for the English translation of
 * the same phrase. Audio takes no part in it, so every embedding already in the library stays
 * valid — the student simply arrives at the same place from another language.
 *
 * The same file as the phone's, by checksum, and distilled against the same quantised CLAP export,
 * which is what makes a query typed here comparable with the vectors analysed there.
 *
 * Two differences from the phone, both because this is a desktop:
 * the vocabulary ships inside the jar instead of being a downloadable asset, and loading is guarded
 * by a mutex over a session built on [Dispatchers.IO] rather than over an Android context.
 */
internal class RuTextEncoder {

    private val mutex = Mutex()

    @Volatile
    private var ortEnv: OrtEnvironment? = null

    @Volatile
    private var ortSession: OrtSession? = null

    @Volatile
    private var tokenizer: WordPieceTokenizer? = null

    /** True when encoding would need a download first. */
    fun needsDownload(): Boolean =
        !ModelRepository.instance.isAvailable(ModelAsset.forRussianText)

    fun pendingDownloadBytes(): Long =
        ModelRepository.instance.pendingDownloadBytes(ModelAsset.forRussianText)

    /**
     * Encodes [text] into a unit-length vector in CLAP's text space.
     *
     * @return the embedding, or null when the weights are unavailable or inference failed; callers
     *   fall back to plain text search rather than scoring every track against nonsense.
     */
    suspend fun encode(text: String): FloatArray? = withContext(Dispatchers.IO) {
        if (needsDownload()) return@withContext null
        if (ortSession == null) mutex.withLock { load() }
        val session = ortSession ?: return@withContext null
        val env = ortEnv ?: return@withContext null
        val tok = tokenizer ?: return@withContext null

        try {
            val tokens = tok.tokenize(text, WordPieceTokenizer.MAX_TOKENS)
            val shape = longArrayOf(1, tokens.size.toLong())
            // Unlike CLAP's graph this one takes a mask, so padding would be harmless; there is
            // simply none to apply, since one query is one row of its own length.
            val mask = LongArray(tokens.size) { 1L }
            OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), shape).use { ids ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).use { attention ->
                    session.run(mapOf("input_ids" to ids, "attention_mask" to attention)).use { output ->
                        @Suppress("UNCHECKED_CAST")
                        val result = output.get(0).value as Array<FloatArray>
                        normalizeUnit(result[0])
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Called under [mutex]: two searches in flight must not each build their own 33 MB session. */
    private fun load() {
        if (ortSession != null) return
        try {
            val modelFile = ModelRepository.instance.localFile(ModelAsset.RU_TEXT_MODEL)
            if (!modelFile.isFile) return
            // Checked for presence and then handed over as a supplier: the tokenizer parses the
            // megabyte of vocabulary when something first asks it to encode, not here. The check
            // stays because of what it buys — a caller that has not made sure of the resource gets
            // no encoder rather than one that throws on first use.
            if (BundledResource.open(VOCAB)?.use { } == null) return

            tokenizer = WordPieceTokenizer {
                checkNotNull(BundledResource.open(VOCAB)) { "$VOCAB is missing" }
            }
            val env = OrtEnvironment.getEnvironment()
            ortEnv = env
            ortSession = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
            println("RuTextEncoder: Russian text model and vocabulary loaded")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun release() {
        runCatching { ortSession?.close() }
        ortSession = null
        ortEnv = null
        tokenizer = null
    }

    private companion object {
        const val VOCAB = "ru_vocab.txt"
    }
}
