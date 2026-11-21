package music.ai.recommend.platform

import music.ai.recommend.model.Song

actual class AiScanner actual constructor() {
    actual suspend fun scanSongs(
        songs: List<Song>,
        onProgress: (Float, String, Int, Long) -> Unit
    ) {
        // Not implemented for Android in this module
    }

    actual fun stop() {}

    actual fun release() {}
}
