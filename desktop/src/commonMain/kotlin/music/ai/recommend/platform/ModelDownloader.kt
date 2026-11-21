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
 * The CLAP weights, which are fetched on first use rather than shipped.
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

    /** Bytes still to fetch for everything the app uses. */
    fun pendingBytes(): Long

    /** Downloads whatever is missing. @return true once everything is present. */
    suspend fun ensureAll(): Boolean

    /** Removes the downloaded weights. @return bytes freed. */
    suspend fun deleteDownloaded(): Long
}
