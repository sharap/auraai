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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The folder with the sealing on, end to end.
 *
 * The crypto has its own tests; what only this can show is that a sealed folder still *works* —
 * that two devices sharing a phrase exchange exactly what they did before, that one with the wrong
 * phrase gets nothing rather than something wrong, and that setting the phrase after the fact goes
 * back for what was skipped while it could not be read.
 *
 * And one assertion that is the whole point of the feature: that the files on disk do not contain
 * what was listened to.
 */
class SealedRoundTripTest {

    private val first = "aa".repeat(16)
    private val second = "bb".repeat(16)
    private val now = 1_793_880_000_000L
    private val phrase = "корвет уходит в море"

    private lateinit var baseDir: File
    private lateinit var folder: File

    private val songs = listOf(
        song("Imagine Dragons", "Believer", "/music/Imagine Dragons - Believer.mp3", 207_000),
        song("Мельница", "Ночная кобыла", "/music/Мельница - Ночная кобыла.mp3", 210_000)
    )

    private fun song(artist: String, title: String, path: String, duration: Long) = Song(
        id = 0, title = title, artist = artist, album = "", albumId = 0,
        duration = duration, uri = path, path = path, folderName = "music"
    )

    @BeforeTest
    fun emptyEverything() {
        baseDir = File(System.getProperty("java.io.tmpdir"), "aimusic-sealed-${System.nanoTime()}")
        folder = File(baseDir, "shared").apply { mkdirs() }
    }

    @AfterTest
    fun tidyUp() {
        baseDir.deleteRecursively()
    }

    private val dao by lazy { DesktopMusicDao(File(baseDir, "data")) }

    /** What the coordinator said was worth noticing, so a silent failure can be caught. */
    private val said = ArrayList<String>()

    private fun coordinator(
        deviceId: String,
        sealing: Sealing? = GcmSealing(phrase)
    ): SyncCoordinator<String> = SyncCoordinator(
        deviceId = deviceId,
        state = SyncState.load(File(baseDir, "state-$deviceId.json")),
        store = PlayLogStore(dao, { songs }),
        folder = { FileSyncFolder(folder, deviceId) },
        note = { said.add(it) },
        sealing = { sealing }
    )

    private suspend fun heard(path: String, at: Long) {
        dao.appendPlayEvent(PlayEventEntity(path, at, 180_000, 200_000))
    }

    private fun filesInFolder(): List<File> =
        folder.walkTopDown().filter { it.isFile }.toList()

    @Test
    fun whatOneDeviceSealsTheOtherOpensAndTheFolderSaysNothing() = runBlocking {
        heard(songs[0].path, now - 300_000)
        heard(songs[1].path, now - 200_000)

        assertEquals(2, coordinator(first).push(now, force = true).written)

        // The files are there and they do not say what was listened to. This is the assertion the
        // whole feature exists for: on Android the folder is not app-private, so anything holding
        // the storage permission can read it — and a listening history is a minute-by-minute
        // record of when somebody is awake, at home and out.
        val written = filesInFolder()
        assertTrue(written.isNotEmpty())
        for (file in written) {
            val text = file.readText()
            assertFalse(text.contains("Believer"), file.name)
            assertFalse(text.contains("believer"), file.name)
            assertFalse(text.contains("кобыла"), file.name)
            assertFalse(text.contains("мельница"), file.name)
            assertTrue(text.contains("a256gcm"), file.name)
        }

        // And the other device, with the same phrase, is none the wiser: it gets what it always got.
        val pulled = coordinator(second).pull(now)
        assertEquals(2, pulled.takenIn)
        val taken = dao.playEventsFromOtherDevices(0)
        assertEquals(songs.map { it.path }.sorted(), taken.map { it.path }.sorted())
    }

    @Test
    fun aDeviceWithAnotherPhraseGetsNothingRatherThanSomethingWrong() = runBlocking {
        heard(songs[0].path, now - 300_000)
        coordinator(first).push(now, force = true)

        val stranger = coordinator(second, GcmSealing("корвет остаётся в порту"))
        assertEquals(0, stranger.pull(now).takenIn)
        assertEquals(0, dao.playEventsFromOtherDevices(0).size)

        // And it says so. This is the one way the whole arrangement fails — the phrase typed here
        // is not the phrase typed there — and it would otherwise fail in silence: a batch that
        // does not open is counted as unreadable and stepped over, like a half-written line.
        assertEquals(1, stranger.unopenedBatches)
        assertTrue(said.any { it.contains("Could not open") }, said.toString())

        // A device with no phrase at all is the same thing from the folder's side, and is told the
        // same way.
        val without = coordinator(second, null)
        assertEquals(0, without.pull(now).takenIn)
        assertEquals(0, dao.playEventsFromOtherDevices(0).size)
        assertEquals(1, without.unopenedBatches)

        // And none of those passes wrote the batch off: a line that did not open is left
        // unconsumed, so the device that gets the phrase right afterwards finds it still there.
        // Nothing else would — the state's name for the phrase covers a change of phrase, and
        // this covers the one failure that leaves no trace to change.
        val state = File(baseDir, "state-$second.json").readText()
        assertTrue(state.contains("2026-11-05.jsonl\":0"), state)
        assertEquals(1, coordinator(second).pull(now).takenIn)
    }

    @Test
    fun settingThePhraseAfterwardsGoesBackForWhatWasSkipped() = runBlocking {
        // The mistake this guards against is quiet and permanent: a batch that could not be
        // opened is counted as unreadable and marked read, so without noticing that the phrase
        // changed, everything the other device sealed before today would stay unread for ever.
        heard(songs[0].path, now - 300_000)
        heard(songs[1].path, now - 200_000)
        coordinator(first).push(now, force = true)

        // Read once with no phrase: nothing arrives, and the folder is now marked as read.
        assertEquals(0, coordinator(second, null).pull(now).takenIn)
        assertEquals(0, dao.playEventsFromOtherDevices(0).size)

        // The owner types the phrase on this device too, and the pass that follows goes back for
        // what it stepped over.
        assertEquals(2, coordinator(second).pull(now).takenIn)
        assertEquals(2, dao.playEventsFromOtherDevices(0).size)
    }

    @Test
    fun aFolderWrittenBeforeThePhraseStaysReadable() = runBlocking {
        // Years of listening are already in the folder in the clear. Sealing from today must not
        // make any of it unreadable.
        heard(songs[0].path, now - 300_000)
        assertEquals(1, coordinator(first, null).push(now, force = true).written)

        heard(songs[1].path, now - 200_000)
        assertEquals(1, coordinator(first).push(now, force = true).written)

        // One device, two kinds of line in its files; the reader takes both.
        assertEquals(2, coordinator(second).pull(now).takenIn)
        assertEquals(songs.map { it.path }.sorted(),
            dao.playEventsFromOtherDevices(0).map { it.path }.sorted())
    }

    @Test
    fun aBatchNumberIsNotSpentOnABatchThatCouldNotBeSealed() = runBlocking {
        // Sealing that fails must behave exactly like a folder that will not take the write: the
        // records stay, the number is not spent, and the next pass offers them again. Otherwise
        // the other device sees a gap and reports a lost batch for ever.
        val refusing = object : Sealing {
            override val name = GcmSealing.NAME
            override val fingerprint = "refusing"
            override fun seal(deviceId: String, sequence: Long, plaintext: String) = null
            override fun open(deviceId: String, sequence: Long, nonce: String, sealed: String) = null
        }
        heard(songs[0].path, now - 300_000)
        assertEquals(0, coordinator(first, refusing).push(now, force = true).written)
        assertTrue(filesInFolder().none { it.name.endsWith(".jsonl") })

        // And once it can be sealed, it goes out as batch one and arrives as one listen.
        assertEquals(1, coordinator(first).push(now, force = true).written)
        assertEquals(1, coordinator(second).pull(now).takenIn)
    }
}
