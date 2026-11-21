package music.ai.recommend.platform

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import music.ai.recommend.model.Song
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.atomic.AtomicLong

/** The MPRIS root interface: what the player is, and the two things a shell may ask of it. */
@DBusInterfaceName("org.mpris.MediaPlayer2")
interface MediaPlayer2 : DBusInterface {
    fun Raise()
    fun Quit()
}

/** The MPRIS transport interface — this is what media keys and panel widgets call. */
@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
interface MediaPlayer2Player : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offsetUs: Long)
    fun SetPosition(trackId: DBusPath, positionUs: Long)
    fun OpenUri(uri: String)
}

/**
 * Publishes playback on the session bus as `org.mpris.MediaPlayer2.AiMusic`.
 *
 * MPRIS is what a Linux desktop already speaks, so implementing it is what makes the keyboard's
 * media keys, the panel's player widget, Bluetooth headset buttons and `playerctl` all work at
 * once — none of them need to know anything about this app.
 *
 * Everything here degrades quietly: desktopMain also builds for macOS and Windows, where there is
 * no session bus at all, and a missing external-control channel is not a reason to fail startup.
 */
actual class RemoteControlService actual constructor(private val remote: PlaybackRemote) {

    private var connection: DBusConnection? = null
    private var scope: CoroutineScope? = null
    private val trackCounter = AtomicLong()

    /** Rebuilt whenever the track changes; MPRIS wants a distinct object path per track. */
    @Volatile
    private var trackPath = DBusPath("$OBJECT_PATH/TrackList/NoTrack")

    actual fun start() {
        if (connection != null) return
        val bus = try {
            DBusConnectionBuilder.forSessionBus().build()
        } catch (e: Throwable) {
            println("RemoteControl: no D-Bus session bus, external control is off (${e.message})")
            return
        }
        try {
            bus.requestBusName(BUS_NAME)
            bus.exportObject(OBJECT_PATH, MprisObject())
            connection = bus
            observeState(bus)
            println("RemoteControl: MPRIS published as $BUS_NAME")
        } catch (e: Throwable) {
            println("RemoteControl: could not publish MPRIS (${e.message})")
            runCatching { bus.disconnect() }
        }
    }

    actual fun stop() {
        scope?.cancel()
        scope = null
        connection?.let { bus ->
            runCatching { bus.unExportObject(OBJECT_PATH) }
            runCatching { bus.releaseBusName(BUS_NAME) }
            runCatching { bus.disconnect() }
        }
        connection = null
    }

    /**
     * Mirrors state changes onto the bus.
     *
     * Position is deliberately not signalled: MPRIS treats it as a poll-only property precisely so
     * that a playing track does not put a signal on the bus every second.
     */
    private fun observeState(bus: DBusConnection) {
        // Without a handler a failure in here dies inside the coroutine: the bus simply stops
        // being updated and nothing anywhere says so.
        val errors = CoroutineExceptionHandler { _, e ->
            println("RemoteControl: state publishing stopped: $e")
        }
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + errors)
        scope = newScope

        combine(
            remote.nowPlaying,
            remote.playing,
            remote.stopped,
            remote.volumePercent,
            combine(remote.canGoNext, remote.canGoPrevious) { next, previous -> next to previous }
        ) { song, playing, stopped, volume, moves ->
            PlayerState(song, playing, stopped, volume, moves.first, moves.second)
        }
            .distinctUntilChanged()
            .onEach { state ->
                if (state.song?.path != lastSongPath) {
                    lastSongPath = state.song?.path
                    trackPath = DBusPath("$OBJECT_PATH/Track/${trackCounter.incrementAndGet()}")
                }
                val changed = LinkedHashMap<String, Variant<*>>().apply {
                    put("PlaybackStatus", Variant(playbackStatus()))
                    put("Metadata", Variant(metadata(state.song), "a{sv}"))
                    put("Volume", Variant(state.volume / 100.0))
                    put("CanGoNext", Variant(state.canNext))
                    put("CanGoPrevious", Variant(state.canPrevious))
                    put("CanPlay", Variant(state.song != null))
                    put("CanPause", Variant(state.song != null))
                    put("CanSeek", Variant(state.song != null))
                }
                runCatching {
                    bus.sendMessage(
                        Properties.PropertiesChanged(OBJECT_PATH, PLAYER_INTERFACE, changed, ArrayList())
                    )
                }.onFailure { println("RemoteControl: could not publish state change: ${it.message}") }
            }
            .launchIn(newScope)
    }

    private data class PlayerState(
        val song: Song?,
        val playing: Boolean,
        val stopped: Boolean,
        val volume: Int,
        val canNext: Boolean,
        val canPrevious: Boolean
    )

    @Volatile
    private var lastSongPath: String? = null

    private fun playbackStatus(): String = when {
        remote.playing.value -> "Playing"
        remote.stopped.value || remote.nowPlaying.value == null -> "Stopped"
        else -> "Paused"
    }

    /**
     * Built as a plain [LinkedHashMap] and [ArrayList], never with `buildMap`/`listOf`: dbus-java
     * marshals by concrete type, and Kotlin's builder and singleton collections are classes it
     * refuses with "Exporting non-exportable type". That failure surfaced on Get as an error reply
     * and, inside the change signal, as nothing at all — the widget simply never updated.
     */
    private fun metadata(song: Song?): Map<String, Variant<*>> {
        val map = LinkedHashMap<String, Variant<*>>()
        map["mpris:trackid"] = Variant(trackPath, "o")
        if (song == null) return map
        // Microseconds throughout MPRIS, where the app works in milliseconds.
        map["mpris:length"] = Variant(song.duration * 1000L)
        map["xesam:title"] = Variant(song.title)
        map["xesam:artist"] = Variant(ArrayList(listOf(song.artist)), "as")
        map["xesam:album"] = Variant(song.album)
        map["xesam:url"] = Variant(fileUrl(song))
        if (song.year.isNotBlank()) map["xesam:contentCreated"] = Variant(song.year)
        return map
    }

    /**
     * `java.io.File.toURI()` renders "file:/path", while xesam:url is expected in the "file:///path"
     * form every other player and file manager produces.
     */
    private fun fileUrl(song: Song): String {
        val uri = song.uri
        return if (uri.startsWith("file:/") && !uri.startsWith("file://")) {
            "file://" + uri.removePrefix("file:")
        } else {
            uri
        }
    }

    /** One object serving all three interfaces at `/org/mpris/MediaPlayer2`, as MPRIS requires. */
    private inner class MprisObject : MediaPlayer2, MediaPlayer2Player, Properties {

        override fun getObjectPath(): String = OBJECT_PATH

        // --- org.mpris.MediaPlayer2 -------------------------------------------------------
        override fun Raise() = remote.remoteRaise()

        // Quit is advertised as unsupported (CanQuit = false), so this is deliberately inert:
        // a stray panel click should not take the player down mid-track.
        override fun Quit() = Unit

        // --- org.mpris.MediaPlayer2.Player ------------------------------------------------
        override fun Next() = remote.remoteNext()
        override fun Previous() = remote.remotePrevious()
        override fun Pause() = remote.remotePause()
        override fun PlayPause() = remote.remotePlayPause()
        override fun Stop() = remote.remoteStop()
        override fun Play() = remote.remotePlay()

        override fun Seek(offsetUs: Long) = remote.remoteSeekBy(offsetUs / 1000L)

        override fun SetPosition(trackId: DBusPath, positionUs: Long) {
            // A position for a track that has since changed must be ignored, or a late click on
            // the widget's progress bar would seek the track that replaced it.
            if (trackId.path != trackPath.path) return
            remote.remoteSeekTo(positionUs / 1000L)
        }

        override fun OpenUri(uri: String) {
            remote.remoteOpenUri(uri)
        }

        // --- org.freedesktop.DBus.Properties -----------------------------------------------
        /**
         * Returns the [Variant] itself rather than its value.
         *
         * `Get` is declared to return `v`, and a Variant already carries the signature the value
         * needs. Handing back the unwrapped value made dbus-java infer one, which it can do for a
         * Double but not for the `a{sv}` of Metadata — that failed with "Can't wrap class
         * java.util.LinkedHashMap in an unqualified Variant".
         */
        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
            GetAll(interfaceName)[propertyName] as A

        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {
            when (propertyName) {
                "Volume" -> (value as? Double)?.let { remote.remoteSetVolume((it * 100).toInt()) }
                // Rate is fixed at 1.0 and the rest are read-only; silently ignoring a write is
                // what the spec asks for here.
                else -> Unit
            }
        }

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = when (interfaceName) {
            ROOT_INTERFACE -> mapOf(
                "CanQuit" to Variant(false),
                "CanRaise" to Variant(true),
                "HasTrackList" to Variant(false),
                "Identity" to Variant("AiMusic"),
                // Set by the packaging, which is the only place that knows what the installed
                // .desktop file is called; a shell uses it to find the app's icon and name.
                "DesktopEntry" to Variant(System.getProperty("aimusic.desktopEntry") ?: "aimusic"),
                "SupportedUriSchemes" to Variant(ArrayList(listOf("file")), "as"),
                "SupportedMimeTypes" to Variant(
                    ArrayList(listOf("audio/mpeg", "audio/flac", "audio/mp4", "audio/ogg", "audio/x-wav")), "as"
                )
            )
            PLAYER_INTERFACE -> {
                val song = remote.nowPlaying.value
                mapOf(
                    "PlaybackStatus" to Variant(playbackStatus()),
                    "Metadata" to Variant(metadata(song), "a{sv}"),
                    "Position" to Variant(remote.positionMs.value * 1000L),
                    "Volume" to Variant(remote.volumePercent.value / 100.0),
                    "Rate" to Variant(1.0),
                    "MinimumRate" to Variant(1.0),
                    "MaximumRate" to Variant(1.0),
                    "CanGoNext" to Variant(remote.canGoNext.value),
                    "CanGoPrevious" to Variant(remote.canGoPrevious.value),
                    "CanPlay" to Variant(song != null),
                    "CanPause" to Variant(song != null),
                    "CanSeek" to Variant(song != null),
                    "CanControl" to Variant(true)
                )
            }
            else -> emptyMap()
        }
    }

    private companion object {
        const val BUS_NAME = "org.mpris.MediaPlayer2.AiMusic"
        const val OBJECT_PATH = "/org/mpris/MediaPlayer2"
        const val ROOT_INTERFACE = "org.mpris.MediaPlayer2"
        const val PLAYER_INTERFACE = "org.mpris.MediaPlayer2.Player"
    }
}
