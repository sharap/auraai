package music.ai.recommend.sync

import kotlinx.coroutines.runBlocking
import music.ai.recommend.db.DesktopMusicDao
import music.ai.recommend.db.PlayEventEntity
import music.ai.recommend.model.Song
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole round trip on this machine: one device's listens into a folder and out again as
 * another device's history, through the real log.
 *
 * Every part of this has tests of its own, and the coordinator's ordering is shared with the phone
 * and tested there on a device. What this says is that the ordering holds over *this* port's log
 * and *this* port's folder, where a track is a path rather than a number — and that is exactly the
 * join the shared code cannot speak for.
 */
class SyncRoundTripTest {

    private val first = "aa".repeat(16)
    private val second = "bb".repeat(16)
    private val now = 1_793_880_000_000L

    private lateinit var baseDir: File
    private lateinit var folder: File

    private val songs = listOf(
        song("Imagine Dragons", "Believer", "/music/Imagine Dragons - Believer.mp3", 207_000),
        song("", "", "/music/Мельница - Ночная кобыла.mp3", 210_000),
        song("Secret Garden", "Atlantia", "/music/01 - Atlantia.m4a", 179_000)
    )

    private fun song(artist: String, title: String, path: String, duration: Long) = Song(
        id = 0,
        title = title,
        artist = artist,
        album = "",
        albumId = 0,
        duration = duration,
        uri = path,
        path = path,
        folderName = "music"
    )

    @BeforeTest
    fun emptyEverything() {
        val stamp = System.nanoTime()
        baseDir = File(System.getProperty("java.io.tmpdir"), "aimusic-round-trip-$stamp")
        folder = File(baseDir, "shared")
        folder.mkdirs()
    }

    @AfterTest
    fun tidyUp() {
        baseDir.deleteRecursively()
    }

    /** One log, shared by both coordinators, as two devices would each have their own. */
    private val dao by lazy { DesktopMusicDao(File(baseDir, "data")) }

    private fun coordinator(deviceId: String, library: List<Song> = songs): SyncCoordinator<String> {
        val state = SyncState.load(File(baseDir, "state-$deviceId.json"))
        return SyncCoordinator(
            deviceId = deviceId,
            state = state,
            store = PlayLogStore(dao, { library }),
            folder = { FileSyncFolder(folder, deviceId) }
        )
    }

    private suspend fun heard(path: String, at: Long, playedMs: Long = 180_000) {
        dao.appendPlayEvent(PlayEventEntity(path, at, playedMs, 200_000))
    }

    @Test
    fun whatOneDeviceHeardTheOtherEndsUpHolding() = runBlocking {
        heard(songs[0].path, now - 300_000)
        heard(songs[1].path, now - 200_000)
        heard(songs[2].path, now - 100_000)

        val pushed = coordinator(first).push(now, force = true)
        assertEquals(3, pushed.written)
        assertEquals(0, pushed.skipped)

        val pulled = coordinator(second).pull(now)
        assertEquals(3, pulled.takenIn)
        assertEquals(0, pulled.waiting)

        // Three heard here and three taken in, kept apart by the device that heard them.
        assertEquals(6, dao.playEvents().size)
        val taken = dao.playEventsFromOtherDevices(0)
        assertEquals(3, taken.size)
        assertEquals(listOf(first), taken.map { it.deviceId }.distinct())
        // And the track each one is about was worked out from its description, not sent.
        assertEquals(songs.map { it.path }.sorted(), taken.map { it.path }.sorted())
    }

    @Test
    fun whatCameFromElsewhereNeverGoesBackOut() = runBlocking {
        // The one that would be expensive to get wrong: a third device would see the same listen
        // twice, under two names, and nothing would ever tell them apart again.
        //
        // Both coordinators here share one log, so what is "heard here" is the same two records
        // for either of them. The telling number is therefore how much the second one exports:
        // the two local listens, and not the two it just took in.
        heard(songs[0].path, now - 300_000)
        heard(songs[1].path, now - 200_000)
        assertEquals(2, coordinator(first).push(now, force = true).written)
        assertEquals(2, coordinator(second).pull(now).takenIn)
        assertEquals(4, dao.playEvents().size)

        assertEquals(2, coordinator(second).push(now, force = true).written)
        assertEquals(2, dao.playEventsToExport(0, 100).size)
        assertEquals(listOf(""), dao.playEventsToExport(0, 100).map { it.deviceId }.distinct())
    }

    @Test
    fun readingTheSameFolderTwiceTakesNothingTwice() = runBlocking {
        heard(songs[0].path, now - 300_000)
        coordinator(first).push(now, force = true)

        val reader = coordinator(second)
        assertEquals(1, reader.pull(now).takenIn)
        assertEquals(0, reader.pull(now).takenIn)
        // Even by a reader that has forgotten where it got to.
        assertEquals(0, coordinator(second).pull(now).takenIn)
        assertEquals(1, dao.playEventsFromOtherDevices(0).size)
    }

    @Test
    fun aListenAboutATrackThatHasNotArrivedIsKeptAndMatchedLater() = runBlocking {
        heard(songs[2].path, now - 100_000)
        assertEquals(1, coordinator(first).push(now, force = true).written)

        // The other device has the listen but not the file.
        val pulled = coordinator(second, library = songs.take(2)).pull(now)
        assertEquals(1, pulled.read?.merge?.accepted?.size)
        assertEquals(0, pulled.takenIn)
        assertEquals(1, pulled.waiting)
        assertEquals(0, dao.playEventsFromOtherDevices(0).size)

        // The file is copied over and scanned. The same state file, a larger library.
        assertEquals(1, coordinator(second).pull(now).takenIn)
        assertEquals(songs[2].path, dao.playEventsFromOtherDevices(0).single().path)
    }

    @Test
    fun aListenAboutAFileThatIsGoneDoesNotStopTheOutbox() = runBlocking {
        heard("/music/никого - ничего.mp3", now - 300_000)   // no such track any more
        heard(songs[0].path, now - 200_000)

        val pushed = coordinator(first).push(now, force = true)
        assertEquals(1, pushed.written)
        assertEquals(1, pushed.skipped)
        assertEquals(1, coordinator(second).pull(now).takenIn)

        // And the watermark moved past it, so the next pass has nothing left to say at all —
        // neither the listen that went out nor the one that could not be described.
        val nothingLeft = coordinator(first).push(now, force = true)
        assertEquals(0, nothingLeft.written)
        assertEquals(0, nothingLeft.skipped)
    }

    @Test
    fun nothingHappensWithoutAFolder() = runBlocking {
        heard(songs[0].path, now - 300_000)
        val state = SyncState.load(File(baseDir, "state-none.json"))
        val adrift = SyncCoordinator(first, state, PlayLogStore(dao, { songs }), { null })
        assertEquals(0, adrift.push(now, force = true).written)
        assertEquals(0, adrift.pull(now).takenIn)
        assertEquals(1, dao.playEvents().size)
    }

    @Test
    fun aPassWithoutALibraryTakesNothingAndMarksNothingRead() = runBlocking {
        // The expensive mistake would be the quiet one. With no library nothing can be placed, so
        // every record would go to the waiting list — which is capped — while the progress map
        // would say those batches had been read. A folder holding more than the cap would lose its
        // oldest for good. So the pass does not happen: on the next one, with a library, the same
        // records are read and placed.
        heard(songs[0].path, now - 300_000)
        assertEquals(1, coordinator(first).push(now, force = true).written)

        val nothing = coordinator(second, library = emptyList()).pull(now)
        assertEquals(0, nothing.takenIn)
        assertEquals(0, nothing.waiting)
        assertEquals(null, nothing.read, "папка не должна быть прочитана")

        // Nothing was written down as read, so the same records arrive once the library is there.
        assertEquals(1, coordinator(second).pull(now).takenIn)
        assertEquals(1, dao.playEventsFromOtherDevices(0).size)
    }

    @Test
    fun theOutboxCarriesOnWhereItLeftOff() = runBlocking {
        heard(songs[0].path, now - 300_000)
        coordinator(first).push(now, force = true)

        heard(songs[1].path, now - 200_000)
        // A new coordinator, as after a restart: the state file says where the last one got to.
        assertEquals(1, coordinator(first).push(now, force = true).written)

        val taken = coordinator(second).pull(now)
        assertEquals(2, taken.takenIn)
        assertTrue(taken.gaps.isEmpty(), "пропусков быть не должно: ${taken.gaps}")
    }
}
