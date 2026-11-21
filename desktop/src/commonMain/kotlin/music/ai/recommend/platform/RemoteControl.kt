package music.ai.recommend.platform

import kotlinx.coroutines.flow.StateFlow
import music.ai.recommend.model.Song

/**
 * What an outside controller may do to playback, and what it may read back.
 *
 * Deliberately narrower than the ViewModel: a remote gets transport and state, not the library,
 * the playlists or the scan.
 */
interface PlaybackRemote {
    val nowPlaying: StateFlow<Song?>
    val playing: StateFlow<Boolean>

    /**
     * True after a stop, as opposed to a pause.
     *
     * MPRIS distinguishes the two: Stopped keeps the track known but rewound, and a later Play
     * starts it from the beginning. Reporting a stop as Paused left panel widgets showing a
     * pause icon for a player that was not going to resume where it left off.
     */
    val stopped: StateFlow<Boolean>
    val positionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    val volumePercent: StateFlow<Int>
    val canGoNext: StateFlow<Boolean>
    val canGoPrevious: StateFlow<Boolean>

    fun remotePlay()
    fun remotePause()
    fun remotePlayPause()
    fun remoteStop()
    fun remoteNext()
    fun remotePrevious()
    fun remoteSeekBy(offsetMs: Long)
    fun remoteSeekTo(positionMs: Long)
    fun remoteSetVolume(percent: Int)

    /**
     * Plays the track at [uri], if the library has it.
     *
     * @return true when something was played. MPRIS advertises which schemes a player accepts, so
     *   this has to actually work for the "file" this app claims to support.
     */
    fun remoteOpenUri(uri: String): Boolean

    /** Brings the window forward, for the MPRIS "Raise" that panel widgets send on click. */
    fun remoteRaise()
}

/**
 * Publishes playback to whatever the desktop uses for external control, so media keys, panel
 * widgets and command-line tools can drive it.
 */
expect class RemoteControlService(remote: PlaybackRemote) {
    fun start()
    fun stop()
}
