package music.ai.recommend.platform

import music.ai.recommend.model.Song

expect class MusicPlayer() {
    var onFinished: (() -> Unit)?
    fun play(song: Song)
    fun pause()
    fun resume()
    fun stop()
    fun seekTo(position: Long)
    val isPlaying: Boolean
    val currentPosition: Long
    val duration: Long
    fun setEqBand(index: Int, level: Float)
    /** 0..100, as MPRIS and the desktop mixers express it. */
    var volumePercent: Int
    fun release()
}
