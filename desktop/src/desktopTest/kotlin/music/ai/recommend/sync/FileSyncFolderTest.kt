package music.ai.recommend.sync

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sync folder as this machine reaches it, and the round trip through it.
 *
 * The shape on disk is the agreement between the two apps, so most of this is about what ends up
 * where: a device writes only its own directory, a day is one growing file, and a day written by a
 * phone that could not append — a directory of batches — is still readable here.
 */
class FileSyncFolderTest {

    private val first = "aa".repeat(16)
    private val second = "bb".repeat(16)

    /** 2026-11-05T12:00:00Z, so the day name in every expectation is fixed. */
    private val now = 1_793_880_000_000L

    private lateinit var root: File

    @BeforeTest
    fun emptyFolder() {
        root = File(System.getProperty("java.io.tmpdir"), "aimusic-sync-test-${System.nanoTime()}")
        root.mkdirs()
    }

    @AfterTest
    fun tidyUp() {
        root.deleteRecursively()
    }

    private fun folder(deviceId: String = first) = FileSyncFolder(root, deviceId)

    private fun record(at: Long, artist: String, title: String) = PlayRecord(
        playedAt = at,
        playedMs = 180_000,
        durationMs = 200_000,
        track = TrackKey(hash = null, durationMs = 200_000, artist = artist, title = title)
    )

    @Test
    fun aBatchLandsInThisDevicesOwnDirectoryAndReadsBack() {
        assertTrue(folder().append(HistoryWriter.dayFile(now), listOf("один", "два")))

        val file = File(root, "aura/v1/$first/history/2026-11-05.jsonl")
        assertTrue(file.isFile, "не создан ${file.path}")
        assertEquals("один\nдва\n", file.readText())
        assertEquals(listOf("один", "два"), folder().lines(first, "history/2026-11-05.jsonl"))
    }

    @Test
    fun aDayIsOneGrowingFile() {
        val day = HistoryWriter.dayFile(now)
        folder().append(day, listOf("первая"))
        folder().append(day, listOf("вторая"))
        // Separate objects on purpose: nothing may be kept open between passes, since Syncthing
        // and the owner both move this folder about underneath us.
        assertEquals(listOf("первая", "вторая"), folder().lines(first, day))
        assertEquals(
            1,
            File(root, "aura/v1/$first/history").listFiles()!!.size,
            "день должен быть одним файлом"
        )
    }

    @Test
    fun onlyDeviceDirectoriesAreListedAsDevices() {
        folder(first).append(HistoryWriter.dayFile(now), listOf("своё"))
        folder(second).append(HistoryWriter.dayFile(now), listOf("чужое"))
        File(root, "aura/v1/readme.txt").writeText("что это за папка")

        assertEquals(listOf(first, second), folder().devices().sorted())
    }

    @Test
    fun aDayWrittenAsADirectoryOfBatchesIsStillReadable() {
        // What a phone whose provider will not append writes instead. This side never writes it,
        // and has to read it for as long as such a phone exists.
        File(root, "aura/v1/$second/history/2026-11-05").mkdirs()
        File(root, "aura/v1/$second/history/2026-11-05/0001.jsonl").writeText("первая\n")
        File(root, "aura/v1/$second/history/2026-11-05/0002.jsonl").writeText("вторая\n")
        File(root, "aura/v1/$second/history/2026-11-04.jsonl").writeText("вчера\n")

        assertEquals(
            listOf(
                "history/2026-11-04.jsonl",
                "history/2026-11-05/0001.jsonl",
                "history/2026-11-05/0002.jsonl"
            ),
            folder().files(second, "history").sorted()
        )
        assertEquals(listOf("вторая"), folder().lines(second, "history/2026-11-05/0002.jsonl"))
    }

    @Test
    fun aFileThatIsNotThereIsNotNowRatherThanEmpty() {
        // The difference matters to the reader: an empty list would mark the file as read.
        assertNull(folder().lines(second, "history/2026-11-05.jsonl"))
        assertEquals(emptyList(), folder().devices())
        assertEquals(emptyList(), folder().files(second, "history"))
    }

    @Test
    fun aPathThatWouldLeaveTheFolderIsRefused() {
        assertFalse(folder().append("../escaped.jsonl", listOf("нет")))
        assertFalse(folder().append("history/../../escaped.jsonl", listOf("нет")))
        assertNull(folder().lines(second, "../escaped.jsonl"))
        assertFalse(File(root.parentFile, "escaped.jsonl").exists())
    }

    @Test
    fun aFolderThatIsNotThereIsAFalseAndNotACrash() {
        val gone = FileSyncFolder({ null }, first)
        assertFalse(gone.isUsable())
        assertFalse(gone.append(HistoryWriter.dayFile(now), listOf("нет")))
        assertEquals(emptyList(), gone.devices())
        assertNull(gone.lines(second, "history/2026-11-05.jsonl"))

        val unmounted = FileSyncFolder(File(root, "нет такого каталога"), first)
        assertFalse(unmounted.isUsable())
        assertTrue(folder().isUsable())
    }

    @Test
    fun whatThisMachineWritesAnotherDeviceCanRead() {
        // The copies of the writer and the reader are shared with the Android app and tested there.
        // What this says is that they work over *this* folder, which is the part that is new here.
        val writer = HistoryWriter(first, folder())
        writer.offer(record(now - 200_000, "мельница", "ночная кобыла"))
        writer.offer(record(now - 100_000, "imagine dragons", "believer"))
        assertEquals(2, writer.flush(now, force = true))

        val progress = HashMap<String, Int>()
        // The reader hands back what it consumed instead of recording it — a store that fails has
        // to leave the folder looking unread — so this does what its caller does.
        val outcome = HistoryReader(second, folder(second), progress).read(emptySet(), now, 30)
            .also { progress.putAll(it.advanced) }
        assertEquals(2, outcome.merge.accepted.size)
        assertEquals(listOf(first, first), outcome.merge.accepted.map { it.deviceId })
        assertEquals(
            listOf("believer", "ночная кобыла"),
            outcome.merge.accepted.map { it.record.track.title }.sorted()
        )
        assertEquals(0, outcome.linesUnreadable)

        // And a second pass over the same folder takes nothing twice.
        val again = HistoryReader(second, folder(second), progress).read(emptySet(), now, 30)
            .also { progress.putAll(it.advanced) }
        assertEquals(0, again.merge.accepted.size)
        assertEquals(1, again.filesSkipped)
    }

    @Test
    fun aDeviceNeverReadsItsOwnDirectory() {
        val writer = HistoryWriter(first, folder())
        writer.offer(record(now - 100_000, "мельница", "ночная кобыла"))
        writer.flush(now, force = true)

        val outcome = HistoryReader(first, folder(), HashMap()).read(emptySet(), now, 30)
        assertEquals(0, outcome.merge.accepted.size)
        assertEquals(0, outcome.filesRead)
    }
}
