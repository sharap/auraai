package music.ai.recommend

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.Equalizer
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import music.ai.recommend.history.PlayHistory
import music.ai.recommend.history.PlayTracker

class PlaybackService : MediaSessionService() {

    companion object {
        const val COMMAND_SET_EQ_ENABLED = "SET_EQ_ENABLED"
        const val COMMAND_GET_AUDIO_OPTIONS = "GET_AUDIO_OPTIONS"
        const val COMMAND_SET_PAUSE_ON_DISCONNECT = "SET_PAUSE_ON_DISCONNECT"
        const val COMMAND_SET_AUDIO_FOCUS = "SET_AUDIO_FOCUS"

        /** Shared with MusicViewModel, which is the only other writer. */
        const val PREFS = "music_prefs"
        const val KEY_EQ_ENABLED = "eq_enabled"
        const val KEY_PAUSE_ON_DISCONNECT = "pause_on_disconnect"
        const val KEY_HANDLE_AUDIO_FOCUS = "handle_audio_focus"

        private const val TAG = "PlaybackService"
    }

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var equalizer: Equalizer? = null

    // Listens are recorded here rather than in the ViewModel: playback outlives the UI, and most
    // listening happens with the screen off, where no ViewModel is around to see it.
    private val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val history by lazy { PlayHistory.getInstance(this) }
    private val tracker = PlayTracker { event -> historyScope.launch { history.record(event) } }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val exoPlayer = ExoPlayer.Builder(this).build()
        player = exoPlayer

        // Applied from preferences at startup rather than fixed at build time, so both can be
        // switched from settings without restarting playback.
        exoPlayer.addListener(historyListener(exoPlayer))
        historyScope.launch { history.prune() }

        applyPauseOnDisconnect(exoPlayer, preferences().getBoolean(KEY_PAUSE_ON_DISCONNECT, true))
        applyAudioFocus(exoPlayer, preferences().getBoolean(KEY_HANDLE_AUDIO_FOCUS, true))

        try {
            equalizer = Equalizer(0, exoPlayer.audioSessionId).apply {
                // Read straight from preferences rather than waiting for the controller to connect,
                // so a user who turned the equalizer off does not hear it applied for a moment on
                // every start.
                enabled = isEqualizerEnabled()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create Equalizer", e)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            action = "OPEN_PLAYER"
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val extras = Bundle().apply {
            putInt("AUDIO_SESSION_ID", exoPlayer.audioSessionId)
        }

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(pendingIntent)
            .setExtras(extras)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val availableSessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand("GET_EQ_PARAMS", Bundle.EMPTY))
                        .add(SessionCommand("SET_EQ_BAND", Bundle.EMPTY))
                        .add(SessionCommand(COMMAND_SET_EQ_ENABLED, Bundle.EMPTY))
                        .add(SessionCommand(COMMAND_GET_AUDIO_OPTIONS, Bundle.EMPTY))
                        .add(SessionCommand(COMMAND_SET_PAUSE_ON_DISCONNECT, Bundle.EMPTY))
                        .add(SessionCommand(COMMAND_SET_AUDIO_FOCUS, Bundle.EMPTY))
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(availableSessionCommands)
                        .setSessionExtras(extras)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        "GET_EQ_PARAMS" -> {
                            val resultBundle = Bundle()
                            equalizer?.let { eq ->
                                resultBundle.putInt("num_bands", eq.numberOfBands.toInt())
                                val minMax = eq.bandLevelRange
                                resultBundle.putInt("min_level", minMax[0].toInt())
                                resultBundle.putInt("max_level", minMax[1].toInt())
                                
                                val freqs = IntArray(eq.numberOfBands.toInt()) { i -> eq.getCenterFreq(i.toShort()) }
                                resultBundle.putIntArray("center_freqs", freqs)
                                
                                val levels = IntArray(eq.numberOfBands.toInt()) { i -> eq.getBandLevel(i.toShort()).toInt() }
                                resultBundle.putIntArray("band_levels", levels)
                                resultBundle.putBoolean("enabled", eq.enabled)
                            }
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, resultBundle))
                        }
                        COMMAND_GET_AUDIO_OPTIONS -> {
                            val bundle = Bundle().apply {
                                putBoolean(KEY_PAUSE_ON_DISCONNECT, preferences().getBoolean(KEY_PAUSE_ON_DISCONNECT, true))
                                putBoolean(KEY_HANDLE_AUDIO_FOCUS, preferences().getBoolean(KEY_HANDLE_AUDIO_FOCUS, true))
                            }
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, bundle))
                        }
                        COMMAND_SET_PAUSE_ON_DISCONNECT -> {
                            val enabled = args.getBoolean("enabled", true)
                            player?.let { applyPauseOnDisconnect(it, enabled) }
                            preferences().edit().putBoolean(KEY_PAUSE_ON_DISCONNECT, enabled).apply()
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                        COMMAND_SET_AUDIO_FOCUS -> {
                            val enabled = args.getBoolean("enabled", true)
                            player?.let { applyAudioFocus(it, enabled) }
                            preferences().edit().putBoolean(KEY_HANDLE_AUDIO_FOCUS, enabled).apply()
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                        COMMAND_SET_EQ_ENABLED -> {
                            val enabled = args.getBoolean("enabled", true)
                            // Band levels are kept either way, so switching back on restores the
                            // curve the user had set.
                            runCatching { equalizer?.enabled = enabled }
                                .onFailure { Log.e(TAG, "Could not toggle the equalizer", it) }
                            setEqualizerEnabled(enabled)
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                        "SET_EQ_BAND" -> {
                            val band = args.getInt("band", -1)
                            val level = args.getInt("level", 0)
                            if (band != -1) {
                                equalizer?.setBandLevel(band.toShort(), level.toShort())
                            }
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                }
            })
            .build()
    }

    /** Pause instead of continuing on the speaker when headphones are pulled out. */
    @OptIn(UnstableApi::class)
    private fun applyPauseOnDisconnect(player: ExoPlayer, enabled: Boolean) {
        runCatching { player.setHandleAudioBecomingNoisy(enabled) }
            .onFailure { Log.e(TAG, "Could not set becoming-noisy handling", it) }
    }

    /**
     * Whether to take audio focus: pause for calls, duck for notifications, stop when another app
     * starts playing. Declaring the attributes is what lets the system route and mix correctly, so
     * they are set either way; only the focus handling follows the setting.
     */
    @OptIn(UnstableApi::class)
    private fun applyAudioFocus(player: ExoPlayer, handleFocus: Boolean) {
        val attributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        runCatching { player.setAudioAttributes(attributes, handleFocus) }
            .onFailure { Log.e(TAG, "Could not set audio attributes", it) }
    }

    private fun preferences() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun isEqualizerEnabled(): Boolean = preferences().getBoolean(KEY_EQ_ENABLED, true)

    private fun setEqualizerEnabled(enabled: Boolean) {
        preferences().edit().putBoolean(KEY_EQ_ENABLED, enabled).apply()
    }

    /**
     * Feeds the tracker. Media3 reports the position of the track being left in
     * [Player.Listener.onPositionDiscontinuity], which fires before the transition callback, so
     * the furthest position is known by the time the track is closed.
     */
    private fun historyListener(exoPlayer: ExoPlayer) = object : Player.Listener {
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            tracker.progress(oldPosition.positionMs)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val songId = mediaItem?.mediaId?.toLongOrNull()
            if (songId == null) {
                tracker.finished(System.currentTimeMillis())
                return
            }
            tracker.started(songId, exoPlayer.duration.orZero(), System.currentTimeMillis())
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> tracker.progress(exoPlayer.currentPosition, exoPlayer.duration.orZero())
                // Reaching the end counts as the whole track even if the last position reported
                // was a second short of it.
                Player.STATE_ENDED -> {
                    tracker.progress(exoPlayer.duration.orZero())
                    tracker.finished(System.currentTimeMillis())
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // A pause may be the end of the listen; recording the position now means nothing is
            // lost if the service is killed while paused.
            tracker.progress(exoPlayer.currentPosition, exoPlayer.duration.orZero())
        }
    }

    private fun Long.orZero(): Long = if (this == C.TIME_UNSET || this < 0) 0 else this

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        player?.let { tracker.progress(it.currentPosition, it.duration.orZero()) }
        tracker.finished(System.currentTimeMillis())
        equalizer?.release()
        equalizer = null
        mediaSession?.run {
            player?.release()
            release()
            mediaSession = null
        }
        player = null
        super.onDestroy()
    }
}
