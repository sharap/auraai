package music.ai.recommend.platform

import music.ai.recommend.model.Song

expect class AiScanner() {
    suspend fun scanSongs(
        songs: List<Song>,
        onProgress: (Float, String, Int, Long) -> Unit
    )
    fun stop()

    /** Closes the inference session. The audio model is ~280 MB of resident memory. */
    fun release()
}
