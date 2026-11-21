package music.ai.recommend

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.Equalizer
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class PlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var equalizer: Equalizer? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val exoPlayer = ExoPlayer.Builder(this).build()
        player = exoPlayer

        try {
            equalizer = Equalizer(0, exoPlayer.audioSessionId).apply {
                enabled = true
            }
        } catch (e: Exception) {
            Log.e("PlaybackService", "Failed to create Equalizer", e)
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
                            }
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, resultBundle))
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
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
