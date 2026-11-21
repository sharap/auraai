package music.ai.recommend.platform

import music.ai.recommend.model.Song
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter

actual class MusicPlayer actual constructor() {
    private val factory = try {
        MediaPlayerFactory()
    } catch (e: Exception) {
        null
    }
    
    private val mediaPlayer = factory?.mediaPlayers()?.newEmbeddedMediaPlayer()
    private val equalizer = factory?.equalizer()?.newEqualizer()
    
    actual var onFinished: (() -> Unit)? = null
    private var _duration = 0L

    /** Whether this app asked for the stop, so that only the other kind is worth a line. */
    @Volatile
    private var stopping = false

    /** Whether the cache has been full for the current track; see the buffering event. */
    @Volatile
    private var filled = false

    @Volatile
    private var saidAt = 0L

    init {
        equalizer?.let { eq ->
            mediaPlayer?.audio()?.setEqualizer(eq)
        }
        mediaPlayer?.events()?.addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun finished(mediaPlayer: MediaPlayer?) {
                onFinished?.invoke()
            }

            override fun lengthChanged(mediaPlayer: MediaPlayer?, newLength: Long) {
                _duration = newLength
            }

            /**
             * Said out loud, because until now the player reported nothing but what it started.
             *
             * A complaint that playback "stutters or breaks up" could not be told apart from a
             * complaint about the machine: the log held one line per track and no word about
             * errors, stops or re-buffering. These three cover what the player itself knows.
             */
            override fun error(mediaPlayer: MediaPlayer?) {
                println("MusicPlayer: the player reports an error on the current track")
            }

            override fun stopped(mediaPlayer: MediaPlayer?) {
                // Only the ones nobody asked for: a track that ends normally raises finished(),
                // and a pause or a deliberate stop goes through this app's own calls.
                if (!stopping) println("MusicPlayer: stopped without being asked to")
            }

            /**
             * Re-buffering, which is what a stutter is from the player's side.
             *
             * Reported only once the cache has been full once for this track — the climb from 0 to
             * 100 at the start of every track is not a fault — and at most once a second, because a
             * stutter raises these in bursts and a log that floods is a log nobody reads.
             */
            override fun buffering(mediaPlayer: MediaPlayer?, newCache: Float) {
                if (newCache >= 100f) {
                    filled = true
                    return
                }
                if (!filled) return
                val now = System.currentTimeMillis()
                if (now - saidAt < 1_000L) return
                saidAt = now
                println("MusicPlayer: buffering again at ${newCache.toInt()}% of the cache")
            }
        })
    }

    actual fun play(song: Song) {
        try {
            println("MusicPlayer: Playing path ${song.path}")
            filled = false
            stopping = false
            mediaPlayer?.media()?.play(song.path)
        } catch (e: Exception) {
            println("MusicPlayer: Error playing ${song.title}: ${e.message}")
        }
    }

    actual fun pause() {
        mediaPlayer?.controls()?.pause()
    }

    actual fun resume() {
        mediaPlayer?.controls()?.play()
    }

    actual fun stop() {
        // Marked before the call, so the event that follows is recognised as the one we asked for.
        stopping = true
        mediaPlayer?.controls()?.stop()
    }

    actual fun seekTo(position: Long) {
        mediaPlayer?.controls()?.setTime(position)
    }

    actual fun setEqBand(index: Int, level: Float) {
        try {
            equalizer?.setAmp(index, level)
        } catch (e: Exception) {}
    }

    actual var volumePercent: Int
        get() = mediaPlayer?.audio()?.volume()?.coerceIn(0, 100) ?: 0
        set(value) {
            mediaPlayer?.audio()?.setVolume(value.coerceIn(0, 100))
        }

    actual val isPlaying: Boolean
        get() = mediaPlayer?.status()?.isPlaying ?: false

    actual val currentPosition: Long
        get() = mediaPlayer?.status()?.time() ?: 0L

    actual val duration: Long
        get() = _duration

    actual fun release() {
        mediaPlayer?.release()
        factory?.release()
    }
}
