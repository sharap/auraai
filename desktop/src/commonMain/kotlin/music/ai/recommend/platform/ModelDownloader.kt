package music.ai.recommend.platform

import kotlinx.coroutines.flow.StateFlow

/** Progress of an in-flight model download. */
data class ModelProgress(
    val running: Boolean = false,
    val currentFile: String? = null,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val error: String? = null
) {
    val fraction: Float
        get() = if (bytesTotal <= 0L) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
}

/**
 * The model weights, which are fetched on first use rather than shipped.
 *
 * They are 408 MB and not this project's to redistribute, so the repository carries the code and
 * the tokenizer's vocabulary while the weights come from Hugging Face, checked against a known
 * size and sha256 before they are used.
 */
expect class ModelDownloader() {
    val progress: StateFlow<ModelProgress>

    fun audioModelReady(): Boolean

    /**
     * sha256 of the audio model this app analyses with.
     *
     * Asked of the platform because only it knows which export it has: two devices may hold
     * different ones — an int8 and an fp32 of the same network produce different vectors — and this
     * is the only name a variant has that cannot be got wrong. It travels with every embedding.
     */
    fun audioModelSha256(): String
    fun textModelReady(): Boolean

    /**
     * The Russian query encoder, which is asked about separately from the two above.
     *
     * It is optional in a way they are not: without it the app still analyses and still searches,
     * just not in Russian, and it is 32 MB nobody who types only English should be made to fetch.
     */
    fun russianModelReady(): Boolean

    /** Bytes still to fetch for the CLAP weights, which is what the app needs to work at all. */
    fun pendingBytes(): Long

    /** Bytes still to fetch for the Russian encoder. */
    fun russianPendingBytes(): Long

    /** Downloads whatever CLAP is missing. @return true once both towers are present. */
    suspend fun ensureAll(): Boolean

    /** Downloads the Russian encoder. @return true once it is present. */
    suspend fun ensureRussian(): Boolean

    /** Removes the downloaded weights. @return bytes freed. */
    suspend fun deleteDownloaded(): Long
}
