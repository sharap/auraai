package music.ai.recommend.sync

import kotlinx.coroutines.runBlocking
import music.ai.recommend.db.DesktopMusicDao
import music.ai.recommend.model.Song
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The playlist of the day across the folder, end to end.
 *
 * The record and the comparison are tested on their own. What only this can show is the part that
 * matters to somebody with two devices: one of them builds today's mix, and the other stops
 * building its own and shows that one — in the same order, found again by tags although the two
 * libraries keep the files in different places.
 */
class MixRoundTripTest {

    private val first = "aa".repeat(16)
    private val second = "bb".repeat(16)
    private val now = 1_793_880_000_000L
    private val today = "2026-11-06"
    private val yesterday = "2026-11-05"

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

    /** A device's playlist of the day: what it has, and what it is handed. */
    private class Shelf(
        private val day: String,
        var held: MixStore.Offered<String>? = null
    ) : MixStore<String> {
        var adoptedFrom: String? = null
        var timesAdopted = 0

        override suspend fun today(): String = day

        override suspend fun current(): MixStore.Offered<String>? = held

        override suspend fun adopt(day: String, attempt: Int, from: String, tracks: List<String>) {
            held = MixStore.Offered(day, attempt, from, tracks)
            adoptedFrom = from
            timesAdopted++
        }
    }

    @BeforeTest
    fun emptyEverything() {
        baseDir = File(System.getProperty("java.io.tmpdir"), "aimusic-mix-${System.nanoTime()}")
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
        mix = shelf
    )

    private fun built(attempt: Int = 0, songs: List<Song> = tracks, day: String = today) =
        MixStore.Offered(day, attempt, null, songs.map { it.path })

    @Test
    fun whatOneDeviceBuiltTheOtherShows() = runBlocking {
        val onPhone = Shelf(today, built(songs = listOf(tracks[2], tracks[0], tracks[1])))
        val onDesktop = Shelf(today)

        assertEquals(1, coordinator(first, onPhone).pushMix(now).written)
        assertTrue(coordinator(second, onDesktop).pullMix(now).adopted)

        // The same tracks in the same order, and it knows whose mix it is showing.
        assertEquals(
            listOf(tracks[2].path, tracks[0].path, tracks[1].path),
            onDesktop.held?.tracks
        )
        assertEquals(first, onDesktop.adoptedFrom)
        assertEquals(today, onDesktop.held?.day)
    }

    @Test
    fun theDeviceThatAdoptedDoesNotPublishItBack() = runBlocking {
        val onPhone = Shelf(today, built())
        val onDesktop = Shelf(today)
        coordinator(first, onPhone).pushMix(now)
        coordinator(second, onDesktop).pullMix(now)

        // Claiming an adopted mix would put two records of one list in the folder, and the two
        // devices would then take turns winning the day on the strength of their names.
        assertEquals(0, coordinator(second, onDesktop).pushMix(now).written)
        assertEquals(1, onDesktop.timesAdopted)

        // And meeting it again changes nothing: the mix it holds is already that one.
        assertEquals(false, coordinator(second, onDesktop).pullMix(now).adopted)
        assertEquals(1, onDesktop.timesAdopted)
    }

    @Test
    fun oneMixGoesOutOnceAndTheNextOneGoesOutAtOnce() = runBlocking {
        val onPhone = Shelf(today, built())
        assertEquals(1, coordinator(first, onPhone).pushMix(now).written)
        // Nothing has changed, so there is nothing to say.
        assertEquals(0, coordinator(first, onPhone).pushMix(now).written)

        // The owner asked for a different mix, which is what raising the attempt means.
        onPhone.held = built(attempt = 1, songs = listOf(tracks[1], tracks[0]))
        assertEquals(1, coordinator(first, onPhone).pushMix(now).written)
    }

    @Test
    fun theMixThisDeviceAskedForBeatsTheOneThatArrived() = runBlocking {
        val onPhone = Shelf(today, built(attempt = 1))
        val onDesktop = Shelf(today, built(attempt = 2, songs = listOf(tracks[1])))
        coordinator(first, onPhone).pushMix(now)

        // The desktop's own is a later attempt — the owner asked for it here — so it stands.
        assertEquals(false, coordinator(second, onDesktop).pullMix(now).adopted)
        assertEquals(listOf(tracks[1].path), onDesktop.held?.tracks)

        // The phone then asks for another, twice over, and that one wins.
        onPhone.held = built(attempt = 3, songs = listOf(tracks[2], tracks[0]))
        coordinator(first, onPhone).pushMix(now)
        assertTrue(coordinator(second, onDesktop).pullMix(now).adopted)
        assertEquals(listOf(tracks[2].path, tracks[0].path), onDesktop.held?.tracks)
    }

    @Test
    fun aMixForAnotherDayIsNotTodaysMix() = runBlocking {
        // Near midnight, or two time zones: a record for a day this device is not living in says
        // nothing about what to show now.
        val onPhone = Shelf(yesterday, built(day = yesterday))
        val onDesktop = Shelf(today)
        coordinator(first, onPhone).pushMix(now)

        assertEquals(false, coordinator(second, onDesktop).pullMix(now).adopted)
        assertNull(onDesktop.held)
    }

    @Test
    fun aMixIsNotTakenInBeforeTheLibraryIsRead() = runBlocking {
        // Nothing keeps these records: read once, and whatever was not used is gone. So a pass
        // before the library is read has to leave the folder looking unread, or the first mix of
        // the day would be lost on whichever device happened to start up second.
        val onPhone = Shelf(today, built())
        coordinator(first, onPhone).pushMix(now)

        val onDesktop = Shelf(today)
        val tooEarly = coordinator(second, onDesktop, library = emptyList()).pullMix(now)
        assertEquals(false, tooEarly.adopted)
        assertNull(onDesktop.held)

        // And once the library is there, the same record is still waiting.
        assertTrue(coordinator(second, onDesktop).pullMix(now).adopted)
        assertEquals(tracks.map { it.path }, onDesktop.held?.tracks)
    }

    @Test
    fun aMixNamingTracksThisLibraryDoesNotHaveIsNotTaken() = runBlocking {
        val onPhone = Shelf(today, built())
        coordinator(first, onPhone).pushMix(now)

        // A device whose library has none of it shows nothing rather than an empty day, and the
        // record is counted as unmatched so that it can be seen in the log.
        val stranger = Shelf(today)
        val elsewhere = listOf(song("/other/Nothing - In Common.mp3", "Nothing", "In Common", 100_000))
        val outcome = coordinator(second, stranger, library = elsewhere).pullMix(now)
        assertEquals(false, outcome.adopted)
        assertEquals(3, outcome.unmatched)
    }
}
