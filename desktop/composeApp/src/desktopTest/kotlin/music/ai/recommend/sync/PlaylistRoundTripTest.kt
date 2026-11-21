package music.ai.recommend.sync

import kotlinx.coroutines.runBlocking
import music.ai.recommend.db.DesktopMusicDao
import music.ai.recommend.model.Song
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Playlists and favourites across the folder, end to end.
 *
 * The replay and the transport are tested on their own. What only an end-to-end test shows is the
 * part the owner notices: two devices that each already had playlists before any of this existed,
 * meeting for the first time. Both put what they had into the log, and both then show the same
 * thing — the union, with two playlists of one name becoming one.
 */
class PlaylistRoundTripTest {

    private val first = "aa".repeat(16)
    private val second = "bb".repeat(16)
    private val now = 1_793_880_000_000L

    private lateinit var baseDir: File
    private lateinit var folder: File

    private val tracks = listOf(
        song("/music/Imagine Dragons - Believer.mp3", "Imagine Dragons", "Believer", 207_000),
        song("/music/Мельница - Ночная кобыла.mp3", "Мельница", "Ночная кобыла", 210_000),
        song("/music/Secret Garden - Atlantia.mp3", "Secret Garden", "Atlantia", 179_000)
    )

    private fun song(path: String, artist: String, title: String, duration: Long) = Song(
        id = 0, title = title, artist = artist, album = "", albumId = 0,
        duration = duration, uri = path, path = path, folderName = "music"
    )

    private fun keyOf(song: Song) = TrackKey.describe(
        artist = song.artist,
        title = song.title,
        fileName = song.path.substringAfterLast('/'),
        durationMs = song.duration
    )

    @Test
    fun theShelfDescribesWhatThisMachineHasAndFindsItAgain() = runBlocking {
        // This port's own end of it: a playlist here is a list of paths, and a path on one machine
        // says nothing about where the same music sits on the other, so what travels is the tags.
        val shelf = M3uShelf(
            songs = { tracks },
            favouritePaths = { setOf(tracks[1].path) },
            storedPlaylists = { listOf("вечер" to listOf(tracks[0].path)) },
            onShow = { _, _ -> }
        )
        val described = shelf.current()!!
        assertEquals(listOf(keyOf(tracks[1])), described.favourites)
        assertEquals(mapOf("вечер" to listOf(keyOf(tracks[0]))), described.playlists)

        val matcher = TrackMatcher(tracks.map { TrackMatcher.Entry(it.path, keyOf(it)) })
        assertEquals(tracks[1].path, matcher.resolve(described.favourites.single()))

        // And a machine that has none of it describes nothing rather than an empty shelf, so the
        // first pass has nothing to seed and says so.
        val bare = M3uShelf({ tracks }, { emptySet() }, { emptyList() }, { _, _ -> })
        assertEquals(null, bare.current())
    }

    /** A device's playlists: what it had before any of this, and what it is shown afterwards. */
    private class Shelf(
        private val had: PlaylistReplay.State?
    ) : PlaylistStore<String> {
        var shownFavourites: List<String> = emptyList()
        var shownPlaylists: Map<String, List<String>> = emptyMap()
        var timesAsked = 0

        override suspend fun current(): PlaylistReplay.State? {
            timesAsked++
            return had
        }

        override suspend fun show(favourites: List<String>, playlists: Map<String, List<String>>) {
            shownFavourites = favourites
            shownPlaylists = playlists
        }
    }

    @BeforeTest
    fun emptyEverything() {
        baseDir = File(System.getProperty("java.io.tmpdir"), "aimusic-playlists-${System.nanoTime()}")
        folder = File(baseDir, "shared").apply { mkdirs() }
    }

    @AfterTest
    fun tidyUp() {
        baseDir.deleteRecursively()
    }

    private val dao by lazy { DesktopMusicDao(File(baseDir, "data")) }

    private fun coordinator(
        deviceId: String,
        shelf: Shelf,
        library: List<Song> = tracks
    ): SyncCoordinator<String> = SyncCoordinator(
        deviceId = deviceId,
        state = SyncState.load(File(baseDir, "state-$deviceId.json")),
        store = PlayLogStore(dao, { library }),
        folder = { FileSyncFolder(folder, deviceId) },
        playlists = shelf,
        playlistLog = PlaylistLog(File(baseDir, "log-$deviceId.jsonl"))
    )

    @Test
    fun twoDevicesThatEachHadPlaylistsEndUpWithBoth() = runBlocking {
        val onPhone = Shelf(
            PlaylistReplay.State(
                favourites = listOf(keyOf(tracks[0])),
                playlists = mapOf("вечер" to listOf(keyOf(tracks[0])))
            )
        )
        val onDesktop = Shelf(
            PlaylistReplay.State(
                favourites = listOf(keyOf(tracks[1])),
                playlists = mapOf(
                    "вечер" to listOf(keyOf(tracks[1])),
                    "работа" to listOf(keyOf(tracks[2]))
                )
            )
        )

        // Each puts what it had into the log and offers it.
        val pushedByPhone = coordinator(first, onPhone).pushPlaylists(now)
        assertEquals(3, pushedByPhone.seeded, "посеяно телефоном: $pushedByPhone")
        assertEquals(3, pushedByPhone.written)
        val pushedByDesktop = coordinator(second, onDesktop).pushPlaylists(now)
        assertEquals(5, pushedByDesktop.seeded, "посеяно десктопом: $pushedByDesktop")

        // And each reads the other's.
        coordinator(first, onPhone).pullPlaylists(now)
        coordinator(second, onDesktop).pullPlaylists(now)

        // Both show the same thing: the union, and one "вечер" holding the tracks of both.
        assertEquals(onDesktop.shownPlaylists, onPhone.shownPlaylists)
        assertEquals(onDesktop.shownFavourites.toSet(), onPhone.shownFavourites.toSet())
        assertEquals(setOf("вечер", "работа"), onPhone.shownPlaylists.keys)
        assertEquals(
            setOf(tracks[0].path, tracks[1].path),
            onPhone.shownPlaylists.getValue("вечер").toSet()
        )
        assertEquals(listOf(tracks[2].path), onPhone.shownPlaylists.getValue("работа"))
        assertEquals(setOf(tracks[0].path, tracks[1].path), onPhone.shownFavourites.toSet())
    }

    @Test
    fun whatWasAlreadyThereIsSeededOnceAndOnlyOnce() = runBlocking {
        val shelf = Shelf(
            PlaylistReplay.State(emptyList(), mapOf("вечер" to listOf(keyOf(tracks[0]))))
        )
        assertEquals(2, coordinator(first, shelf).pushPlaylists(now).seeded)
        // Asking again would describe the same playlist under new counters and add every track in
        // it a second time.
        assertEquals(0, coordinator(first, shelf).pushPlaylists(now).seeded)
        assertEquals(0, coordinator(first, shelf).pushPlaylists(now).written)
        assertEquals(1, shelf.timesAsked)
    }

    @Test
    fun aChangeMadeAfterTheSeedTravelsToo() = runBlocking {
        val onPhone = Shelf(PlaylistReplay.State(emptyList(), emptyMap()))
        val onDesktop = Shelf(PlaylistReplay.State(emptyList(), emptyMap()))
        coordinator(first, onPhone).pushPlaylists(now)

        // A heart tapped afterwards: the app writes it into its own log, and the pass carries it.
        val log = PlaylistLog(File(baseDir, "log-$first.jsonl"))
        log.append(listOf(
            PlaylistOp(PlaylistOp.Kind.ADD, log.nextSeq(), first, now, null, keyOf(tracks[1]))
        ))
        assertEquals(1, coordinator(first, onPhone).pushPlaylists(now).written)

        coordinator(second, onDesktop).pullPlaylists(now)
        assertEquals(listOf(tracks[1].path), onDesktop.shownFavourites)
    }

    @Test
    fun aTrackTheOtherDeviceDoesNotHaveIsSimplyNotShownYet() = runBlocking {
        val onPhone = Shelf(
            PlaylistReplay.State(emptyList(), mapOf("вечер" to tracks.map { keyOf(it) }))
        )
        coordinator(first, onPhone).pushPlaylists(now)

        // The other device knows one of the three.
        val onDesktop = Shelf(PlaylistReplay.State(emptyList(), emptyMap()))
        val pulled = coordinator(second, onDesktop, library = tracks.take(1)).pullPlaylists(now)
        assertEquals(2, pulled.unmatched)
        assertEquals(listOf(tracks[0].path), onDesktop.shownPlaylists.getValue("вечер"))

        // The files are copied over. The same log, a larger library, and the playlist fills in —
        // without anything new having arrived in the folder.
        coordinator(second, onDesktop).pullPlaylists(now)
        assertEquals(tracks.map { it.path }, onDesktop.shownPlaylists.getValue("вечер"))
    }

    @Test
    fun aRemovalOnOneDeviceReachesTheOther() = runBlocking {
        val onPhone = Shelf(
            PlaylistReplay.State(emptyList(), mapOf("вечер" to listOf(keyOf(tracks[0]), keyOf(tracks[1]))))
        )
        val onDesktop = Shelf(PlaylistReplay.State(emptyList(), emptyMap()))
        coordinator(first, onPhone).pushPlaylists(now)
        coordinator(second, onDesktop).pullPlaylists(now)
        assertEquals(2, onDesktop.shownPlaylists.getValue("вечер").size)

        // Taken out on the desktop. Its own log already holds the phone's changes, so the counter
        // it chooses is higher than theirs and the removal answers the addition.
        val desktopLog = PlaylistLog(File(baseDir, "log-$second.jsonl"))
        desktopLog.append(listOf(
            PlaylistOp(PlaylistOp.Kind.REMOVE, desktopLog.nextSeq(), second, now, "вечер", keyOf(tracks[0]))
        ))
        coordinator(second, onDesktop).pushPlaylists(now)
        coordinator(first, onPhone).pullPlaylists(now)

        assertEquals(listOf(tracks[1].path), onPhone.shownPlaylists.getValue("вечер"))
        assertTrue(onPhone.shownPlaylists.getValue("вечер").none { it == tracks[0].path })
    }

    @Test
    fun theOrderOneDeviceDraggedReachesTheOther() = runBlocking {
        // An order is the one operation that carries a list rather than a track, so this is here
        // to show that the list survives the whole way: written to the log, pushed as a line into
        // the folder, read by the other device, replayed, matched against a library that knows
        // these tracks by different paths, and shown in the order that was asked for.
        val onPhone = Shelf(
            PlaylistReplay.State(
                favourites = emptyList(),
                playlists = mapOf("вечер" to tracks.map { keyOf(it) })
            )
        )
        val onDesktop = Shelf(PlaylistReplay.State(emptyList(), emptyMap()))
        coordinator(first, onPhone).pushPlaylists(now)
        coordinator(second, onDesktop).pullPlaylists(now)
        assertEquals(tracks.map { it.path }, onDesktop.shownPlaylists.getValue("вечер"))

        // Dragged about on the desktop: the last track to the front.
        val wanted = listOf(tracks[2], tracks[0], tracks[1])
        val desktopLog = PlaylistLog(File(baseDir, "log-$second.jsonl"))
        desktopLog.append(listOf(
            PlaylistOp(
                kind = PlaylistOp.Kind.ORDER,
                seq = desktopLog.nextSeq(),
                deviceId = second,
                at = now,
                playlist = "вечер",
                tracks = wanted.map { keyOf(it) }
            )
        ))
        // The device that made it shows it by replaying its own log, which is what the app does
        // after a change rather than keeping what the change did to the list.
        coordinator(second, onDesktop).showPlaylists()
        assertEquals(wanted.map { it.path }, onDesktop.shownPlaylists.getValue("вечер"))

        coordinator(second, onDesktop).pushPlaylists(now)
        coordinator(first, onPhone).pullPlaylists(now)
        assertEquals(wanted.map { it.path }, onPhone.shownPlaylists.getValue("вечер"))
    }

    @Test
    fun nothingIsSeededBeforeTheLibraryIsRead() = runBlocking {
        // The one mistake here that cannot be undone: an empty log put in place and marked done.
        // Before the library is read a playlist cannot be described, because a track is named by
        // its tags, so the pass has to wait rather than conclude that there was nothing.
        val shelf = Shelf(PlaylistReplay.State(emptyList(), mapOf("вечер" to listOf(keyOf(tracks[0])))))
        val tooEarly = coordinator(first, shelf, library = emptyList()).pushPlaylists(now)
        assertEquals(0, tooEarly.seeded)
        assertEquals(0, shelf.timesAsked)

        // And once it is read, the seeding happens as it should.
        assertEquals(2, coordinator(first, shelf).pushPlaylists(now).seeded)
    }

    @Test
    fun nothingHappensWithoutAFolder() = runBlocking {
        val shelf = Shelf(PlaylistReplay.State(emptyList(), mapOf("вечер" to listOf(keyOf(tracks[0])))))
        val adrift = SyncCoordinator(
            deviceId = first,
            state = SyncState.load(File(baseDir, "state-none.json")),
            store = PlayLogStore(dao, { tracks }),
            folder = { null },
            playlists = shelf,
            playlistLog = PlaylistLog(File(baseDir, "log-none.jsonl"))
        )
        assertEquals(0, adrift.pushPlaylists(now).written)
        assertEquals(0, adrift.pushPlaylists(now).seeded)
        assertEquals(0, adrift.pullPlaylists(now).takenIn)
        // And nothing was seeded either, so the first real pass still has it all to offer.
        assertEquals(0, shelf.timesAsked)
    }
}
