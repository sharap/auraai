package music.ai.recommend.platform

import music.ai.recommend.model.Song

actual class MusicPlayer actual constructor() {
    actual var onFinished: (() -> Unit)? = null
    actual fun play(song: Song) {}
    actual fun pause() {}
    actual fun resume() {}
    actual fun stop() {}
    actual fun seekTo(position: Long) {}
    actual fun setEqBand(index: Int, level: Float) {}
    // The system mixer owns volume on Android; nothing here to set.
    actual var volumePercent: Int
        get() = 100
        set(value) {}

    actual val isPlaying: Boolean = false
    actual val currentPosition: Long = 0L
    actual val duration: Long = 0L
    actual fun release() {}
}
