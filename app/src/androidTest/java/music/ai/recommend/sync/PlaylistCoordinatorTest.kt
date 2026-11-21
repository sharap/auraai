package music.ai.recommend.sync

import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import music.ai.recommend.history.PlayHistory
import music.ai.recommend.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Playlists across the folder, on this phone's own library.
 *
 * The replay, the transport and the round trip are tested without a device. What only this can show
 * is the Android end of it: a playlist here is a set of MediaStore ids, which mean nothing on the
 * other side, so they have to be described by their tags — and those tags come from this library
 * rather than from a fixture.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistCoordinatorTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val first = "aa".repeat(16)
    private val second = "bb".repeat(16)
    private val now = 1_793_880_000_000L

    private lateinit var folder: File
    private lateinit var tracks: List<Song>

    /** What a device was shown after a pass. */
    private class Shown {
        var favourites: List<Long> = emptyList()
        var playlists: Map<String, List<Long>> = emptyMap()
    }

    @Before
    fun findSomeTracks() {
        folder = File(context.cacheDir, "playlist-round-trip").apply {
            deleteRecursively()
            mkdirs()
        }
        tracks = libraryHead(3)
        assumeTrue("нужны треки в фонотеке", tracks.size == 3)
    }

    @After
    fun tidyUp() {
        folder.deleteRecursively()
    }

    private fun libraryHead(wanted: Int): List<Song> {
        val found = ArrayList<Song>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION
            ),
            null,
            null,
            "${MediaStore.Audio.Media._ID} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext() && found.size < wanted) {
                found += Song(
                    id = cursor.getLong(0),
                    title = cursor.getString(2).orEmpty(),
                    artist = cursor.getString(3).orEmpty(),
                    album = "",
                    albumId = 0,
                    duration = cursor.getLong(4),
                    uri = "",
                    path = cursor.getString(1).orEmpty(),
                    folderName = ""
                )
            }
        }
        return found
    }

    private fun coordinator(
        deviceId: String,
        shown: Shown,
        favourites: Set<Long> = emptySet(),
        playlists: List<Pair<String, List<Long>>> = emptyList(),
        library: List<Song> = tracks
    ): SyncCoordinator<Long> = SyncCoordinator(
        deviceId = deviceId,
        state = SyncState.load(File(folder, "state-$deviceId.json")),
        store = PlayHistoryStore(PlayHistory.getInstance(context), { library }),
        folder = { SafSyncFolder.fromDirectory(context, folder, deviceId) },
        playlists = PlaylistShelf(
            songs = { library },
            favouriteIds = { favourites },
            storedPlaylists = { playlists },
            onShow = { f, p -> shown.favourites = f; shown.playlists = p }
        ),
        playlistLog = PlaylistLog(File(folder, "log-$deviceId.jsonl"))
    )

    @Test
    fun whatOnePhoneHasTheOtherDeviceEndsUpShowing() = runBlocking {
        val here = Shown()
        val there = Shown()

        val pushed = coordinator(
            first, here,
            favourites = setOf(tracks[0].id),
            playlists = listOf("вечер" to listOf(tracks[0].id, tracks[1].id))
        ).pushPlaylists(now)
        // One favourite, one playlist created, two tracks in it.
        assertEquals("отправлено: $pushed", 4, pushed.seeded)
        assertEquals(4, pushed.written)

        val pulled = coordinator(second, there).pullPlaylists(now)
        assertEquals("принято: $pulled", 4, pulled.takenIn)
        assertEquals(0, pulled.unmatched)

        // The ids on the other side are its own, found from the tags — which here is the same
        // library, so they come back the same ids.
        assertEquals(listOf(tracks[0].id), there.favourites)
        assertEquals(mapOf("вечер" to listOf(tracks[0].id, tracks[1].id)), there.playlists)
    }

    @Test
    fun aTrackDescribedByTagsIsFoundAgainByTags() = runBlocking {
        // The Android end of the whole thing: a MediaStore id means nothing elsewhere, so what
        // travels is the tags, and what comes back has to be the same track.
        val shelf = PlaylistShelf(
            songs = { tracks },
            favouriteIds = { setOf(tracks[2].id) },
            storedPlaylists = { emptyList() },
            onShow = { _, _ -> }
        )
        val described = shelf.current()!!.favourites.single()
        assertEquals(PlaylistShelf.keyOf(tracks[2]), described)
        assertTrue("описание пустое: $described", described.title.isNotEmpty() || described.hash != null)

        val matcher = TrackMatcher(tracks.map { TrackMatcher.Entry(it.id, PlaylistShelf.keyOf(it)) })
        assertEquals(tracks[2].id, matcher.resolve(described))
    }

    @Test
    fun aChangeMadeAfterTheSeedTravelsToo() = runBlocking {
        val here = Shown()
        val there = Shown()
        coordinator(first, here, favourites = setOf(tracks[0].id)).pushPlaylists(now)

        // A heart tapped afterwards, as the view model records it.
        val log = PlaylistLog(File(folder, "log-$first.jsonl"))
        log.appendOwn { seq ->
            listOf(PlaylistOp(PlaylistOp.Kind.ADD, seq, first, now, null, PlaylistShelf.keyOf(tracks[1])))
        }
        assertEquals(1, coordinator(first, here, favourites = setOf(tracks[0].id)).pushPlaylists(now).written)

        coordinator(second, there).pullPlaylists(now)
        assertEquals(setOf(tracks[0].id, tracks[1].id), there.favourites.toSet())
    }

    @Test
    fun nothingIsSeededBeforeTheLibraryIsRead() = runBlocking {
        val here = Shown()
        val tooEarly = coordinator(
            first, here,
            favourites = setOf(tracks[0].id),
            library = emptyList()
        ).pushPlaylists(now)
        assertEquals(0, tooEarly.seeded)
        assertEquals(0, tooEarly.written)

        // And the chance is not spent: with a library, it seeds.
        assertEquals(
            1,
            coordinator(first, here, favourites = setOf(tracks[0].id)).pushPlaylists(now).seeded
        )
    }
}
