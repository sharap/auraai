package music.ai.recommend.sync

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import music.ai.recommend.db.AppDatabase
import music.ai.recommend.history.PlayEvent
import music.ai.recommend.history.PlayHistory
import music.ai.recommend.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The whole round trip, on a device: one device's listens into a folder and out again as another
 * device's history, through the real database.
 *
 * Every part of this has tests of its own. What those cannot show is the order things happen in —
 * whether a listen taken in is kept apart from this device's own, whether reading the same folder
 * twice takes anything twice, whether a listen about a track that has not arrived yet is held or
 * lost. That order is where the damage would be, and it is only visible end to end.
 */
@RunWith(AndroidJUnit4::class)
class SyncCoordinatorTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val history get() = PlayHistory.getInstance(context)
    private val dao get() = AppDatabase.getDatabase(context).playEventDao()

    private val first = "aa".repeat(16)
    private val second = "bb".repeat(16)
    private val now = 1_800_000_000_000L

    private lateinit var folder: File

    private val songs = listOf(
        song(5001L, "Imagine Dragons", "Believer", "/music/Imagine Dragons - Believer.mp3", 207_000),
        song(5002L, "", "", "/music/Мельница - Ночная кобыла.mp3", 210_000),
        song(5003L, "Secret Garden", "Atlantia", "/music/01 - Atlantia.m4a", 179_000)
    )

    private fun song(id: Long, artist: String, title: String, path: String, duration: Long) = Song(
        id = id,
        title = title,
        artist = artist,
        album = "",
        albumId = 0,
        duration = duration,
        uri = Uri.EMPTY,
        path = path,
        folderName = "music"
    )

    @Before
    fun emptyEverything() {
        dao.deleteAll()
        folder = File(context.cacheDir, "sync-round-trip").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tidyUp() {
        dao.deleteAll()
        folder.deleteRecursively()
    }

    private fun coordinator(deviceId: String, library: List<Song> = songs): SyncCoordinator<Long> {
        val state = SyncState.load(File(folder, "state-$deviceId.json"))
        val sink = SafSyncFolder.fromDirectory(context, folder, deviceId)
        return SyncCoordinator(deviceId, state, PlayHistoryStore(history) { library }, { sink })
    }

    private fun listen(songId: Long, at: Long, playedMs: Long = 180_000) =
        PlayEvent(songId, at, playedMs, 200_000)

    @Test
    fun whatOneDeviceHeardTheOtherEndsUpHolding() = runBlocking {
        history.record(listen(5001L, now - 300_000))
        history.record(listen(5002L, now - 200_000))
        history.record(listen(5003L, now - 100_000))
        assertEquals(3, dao.count())

        val pushed = coordinator(first).push(now, force = true)
        assertEquals(3, pushed.written)
        assertEquals(0, pushed.skipped)

        val pulled = coordinator(second).pull(now)
        assertEquals(3, pulled.takenIn)
        assertEquals(0, pulled.waiting)

        // Three heard here and three taken in, kept apart by the device that heard them.
        assertEquals(6, dao.count())
        val taken = dao.fromOtherDevicesSince(0)
        assertEquals(3, taken.size)
        assertEquals(listOf(first), taken.map { it.deviceId }.distinct())
        assertEquals(listOf(5001L, 5002L, 5003L), taken.map { it.songId }.sorted())
        // And the track each one is about was worked out from its description, not sent.
        assertEquals(listOf(180_000L, 180_000L, 180_000L), taken.map { it.playedMs })
    }

    @Test
    fun whatCameFromElsewhereNeverGoesBackOut() = runBlocking {
        // The one that would be expensive to get wrong: a third device would see the same listen
        // twice, under two names, and nothing would ever tell them apart again.
        //
        // Both coordinators here share one database, so what is "heard here" is the same two rows
        // for either of them. The telling number is therefore how much the second one exports:
        // the two local listens, and not the two it just took in.
        history.record(listen(5001L, now - 300_000))
        history.record(listen(5002L, now - 200_000))
        assertEquals(2, coordinator(first).push(now, force = true).written)
        assertEquals(2, coordinator(second).pull(now).takenIn)
        assertEquals(4, dao.count())

        assertEquals(2, coordinator(second).push(now, force = true).written)
        assertEquals(2, dao.notYetExported(0, 100).size)
        assertEquals(listOf(""), dao.notYetExported(0, 100).map { it.deviceId }.distinct())
    }

    @Test
    fun readingTheSameFolderTwiceTakesNothingTwice() = runBlocking {
        history.record(listen(5001L, now - 300_000))
        coordinator(first).push(now, force = true)

        val reader = coordinator(second)
        assertEquals(1, reader.pull(now).takenIn)
        assertEquals(0, reader.pull(now).takenIn)
        // Even by a reader that has forgotten where it got to.
        assertEquals(0, coordinator(second).pull(now).takenIn)
        assertEquals(1, dao.fromOtherDevicesSince(0).size)
    }

    @Test
    fun aListenAboutATrackThatHasNotArrivedIsKeptAndMatchedLater() = runBlocking {
        history.record(listen(5003L, now - 100_000))
        val pushed = coordinator(first).push(now, force = true)
        assertEquals("отправлено: $pushed", 1, pushed.written)

        // The other device has the listen but not the file.
        val withoutIt = coordinator(second, library = songs.take(2))
        val pulled = withoutIt.pull(now)
        assertEquals("прочитано: ${pulled.read?.merge?.accepted?.size}, файлов " +
            "${pulled.read?.filesRead}, пропущено ${pulled.read?.filesSkipped}", 1,
            pulled.read?.merge?.accepted?.size)
        assertEquals(0, pulled.takenIn)
        assertEquals(1, pulled.waiting)
        assertEquals(0, dao.fromOtherDevicesSince(0).size)

        // The file is copied over and scanned. The same state file, a larger library.
        assertEquals(1, coordinator(second).pull(now).takenIn)
        assertEquals(1, dao.fromOtherDevicesSince(0).size)
        assertEquals(5003L, dao.fromOtherDevicesSince(0).single().songId)
    }

    @Test
    fun aListenAboutAFileThatIsGoneDoesNotStopTheOutbox() = runBlocking {
        history.record(listen(9999L, now - 300_000))     // no such song any more
        history.record(listen(5001L, now - 200_000))

        val pushed = coordinator(first).push(now, force = true)
        assertEquals(1, pushed.written)
        assertEquals(1, pushed.skipped)
        assertEquals(1, coordinator(second).pull(now).takenIn)

        // And the watermark moved past it, so the next pass has nothing left to say at all —
        // neither the listen that went out nor the one that could not be described.
        val sender = coordinator(first)
        val nothingLeft = sender.push(now, force = true)
        assertEquals(0, nothingLeft.written)
        assertEquals(0, nothingLeft.skipped)
    }

    @Test
    fun nothingHappensWithoutAFolder() = runBlocking {
        history.record(listen(5001L, now - 300_000))
        val state = SyncState.load(File(folder, "state-none.json"))
        val adrift = SyncCoordinator(first, state, PlayHistoryStore(history) { songs }, { null })
        assertEquals(0, adrift.push(now, force = true).written)
        assertEquals(0, adrift.pull(now).takenIn)
        assertEquals(1, dao.count())
    }

    @Test
    fun aPassWithoutALibraryTakesNothingAndMarksNothingRead() = runBlocking {
        // The expensive mistake would be the quiet one. With no library nothing can be placed, so
        // every record would go to the waiting list — which is capped — while the progress map
        // would say those batches had been read. A folder holding more than the cap would lose its
        // oldest for good. So the pass does not happen: on the next one, with a library, the same
        // records are read and placed.
        history.record(listen(5001L, now - 300_000))
        assertEquals(1, coordinator(first).push(now, force = true).written)

        val stateFile = File(folder, "state-$second.json")
        val notScannedYet = SyncCoordinator(
            second,
            SyncState.load(stateFile),
            PlayHistoryStore(history) { emptyList() },
            { SafSyncFolder.fromDirectory(context, folder, second) }
        )
        val nothing = notScannedYet.pull(now)
        assertEquals(0, nothing.takenIn)
        assertEquals(0, nothing.waiting)
        assertEquals("папка не должна быть прочитана", null, nothing.read)

        // Nothing was written down as read, so the same records arrive once the library is there.
        assertEquals(1, coordinator(second).pull(now).takenIn)
        assertEquals(1, dao.fromOtherDevicesSince(0).size)
    }

    @Test
    fun theOutboxCarriesOnWhereItLeftOff() = runBlocking {
        history.record(listen(5001L, now - 300_000))
        coordinator(first).push(now, force = true)

        history.record(listen(5002L, now - 200_000))
        // A new coordinator, as after a restart: the state file says where the last one got to.
        val afterRestart = coordinator(first)
        assertEquals(1, afterRestart.push(now, force = true).written)

        val taken = coordinator(second).pull(now)
        assertEquals(2, taken.takenIn)
        assertTrue("пропусков быть не должно: ${taken.gaps}", taken.gaps.isEmpty())
    }
}
