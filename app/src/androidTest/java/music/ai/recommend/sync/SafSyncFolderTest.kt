package music.ai.recommend.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The plumbing, on a device, against a real directory.
 *
 * Everything above this — the record format, the merge, the writer, the reader, the matcher — is
 * shared code with its own tests on a desktop JVM. What those cannot reach is the storage access
 * framework: whether a document can be appended to, what a provider does when asked to, and
 * whether a day written as separate files reads back as one day. So this runs the round trip the
 * two apps will actually perform, writing and reading through [SafSyncFolder].
 *
 * A plain directory stands in for the folder the owner would pick. That is the one thing not under
 * test — a tree needs a person to grant it — and every line below it is the same.
 */
@RunWith(AndroidJUnit4::class)
class SafSyncFolderTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val own = "11".repeat(16)
    private val other = "22".repeat(16)
    private val now = 1_800_000_000_000L

    private lateinit var directory: File

    @Before
    fun emptyFolder() {
        directory = File(context.cacheDir, "sync-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun folder(deviceId: String) = SafSyncFolder.fromDirectory(context, directory, deviceId)

    private fun listen(at: Long, title: String) = PlayRecord(
        playedAt = at,
        playedMs = 180_000,
        durationMs = 200_000,
        track = TrackKey(null, 200_000, "artist", title)
    )

    @Test
    fun anEmptyFolderIsUsableAndSaysNothing() {
        val folder = folder(own)
        assertTrue(folder.isUsable())
        assertEquals(emptyList<String>(), folder.devices())
        assertEquals(emptyList<String>(), folder.files(other, "history"))
        assertNull(folder.lines(other, "history/2027-01-15.jsonl"))
    }

    @Test
    fun whatOneDeviceWritesAnotherReads() {
        val writer = HistoryWriter(other, folder(other))
        val sent = listOf(
            listen(now - 300_000, "believer"),
            listen(now - 200_000, "ночная кобыла"),
            listen(now - 100_000, "atlantia")
        )
        sent.forEach { writer.offer(it) }
        assertEquals(3, writer.flush(now, force = true))

        // A different device, with its own id, reading the same folder.
        val reader = HistoryReader(own, folder(own), HashMap())
        val outcome = reader.read(emptySet(), now, 365)
        assertEquals(1, outcome.filesRead)
        assertEquals(sent, outcome.merge.accepted.map { it.record })
        assertEquals(emptyMap<String, List<Long>>(), outcome.merge.gaps)
    }

    @Test
    fun aDeviceDoesNotReadItsOwnRecordsBack() {
        val writer = HistoryWriter(own, folder(own))
        writer.offer(listen(now, "mine"))
        writer.flush(now, force = true)

        val outcome = HistoryReader(own, folder(own), HashMap()).read(emptySet(), now, 365)
        assertTrue(outcome.merge.accepted.isEmpty())
        assertEquals(listOf(own), folder(own).devices())
    }

    @Test
    fun severalFlushesLandInOneDaysFileOrOneDaysDirectory() {
        val sink = folder(other)
        val writer = HistoryWriter(other, sink)
        repeat(3) { batch ->
            writer.offer(listen(now + batch * 1000L, "track $batch"))
            assertEquals(1, writer.flush(now + batch * 1000L, force = true))
        }
        // Either shape is allowed; what matters is that all three batches are there and in order.
        val paths = sink.files(other, "history")
        assertTrue("файлы: $paths", paths.isNotEmpty())
        assertTrue("все в дне 2027-01-15: $paths", paths.all { it.startsWith("history/2027-01-15") })

        val outcome = HistoryReader(own, folder(own), HashMap()).read(emptySet(), now + 5000, 365)
        assertEquals(listOf("track 0", "track 1", "track 2"),
            outcome.merge.accepted.map { it.record.track.title })
        assertEquals(listOf(1L, 2L, 3L), outcome.merge.accepted.map { it.sequence })
    }

    @Test
    fun whetherThisProviderCanAppendIsSettledBeforeAnyDataIsWritten() {
        val sink = folder(other)
        val writer = HistoryWriter(other, sink)
        writer.offer(listen(now, "first"))
        writer.flush(now, force = true)
        // Known from the very first write, because it is answered on a file of its own. Finding
        // out by trying it on real data is how one batch came to be written twice.
        val verdict = sink.appendWorks
        assertTrue("провайдер ничего не сказал про дописывание", verdict != null)

        writer.offer(listen(now + 1000, "second"))
        writer.flush(now + 1000, force = true)
        assertEquals(verdict, sink.appendWorks)

        val paths = sink.files(other, "history")
        if (verdict == true) {
            assertEquals(listOf("history/2027-01-15.jsonl"), paths)
            assertEquals(2, sink.lines(other, paths.single())!!.size)
        } else {
            assertTrue("должен был разложить по файлам: $paths", paths.size >= 2)
        }
        // Whichever shape it chose, every batch is there exactly once.
        val outcome = HistoryReader(own, folder(own), HashMap()).read(emptySet(), now + 5000, 365)
        assertEquals(listOf("first", "second"), outcome.merge.accepted.map { it.record.track.title })
        assertEquals(0, outcome.merge.alreadyHeld)
        // And nothing of the probe is left behind.
        assertTrue("зонд не убран: $paths", paths.none { it.contains("probe") })
    }

    @Test
    fun aSecondPassReadsOnlyWhatArrivedSince() {
        val sink = folder(other)
        val writer = HistoryWriter(other, sink)
        writer.offer(listen(now, "first"))
        writer.flush(now, force = true)

        val progress = HashMap<String, Int>()
        val reader = HistoryReader(own, folder(own), progress)
        val first = reader.read(emptySet(), now, 365)
        assertEquals(1, first.merge.accepted.size)

        val held = first.merge.accepted.map { HistoryMerge.Known(it.deviceId, it.record.playedAt) }.toSet()
        assertEquals(0, reader.read(held, now, 365).filesRead)

        writer.offer(listen(now + 60_000, "second"))
        writer.flush(now + 60_000, force = true)
        val third = reader.read(held, now + 60_000, 365)
        assertEquals(listOf("second"), third.merge.accepted.map { it.record.track.title })
        assertEquals(0, third.merge.alreadyHeld)
    }

    @Test
    fun aFolderThatIsNotThereIsNotUsableAndBreaksNothing() {
        val missing = File(context.cacheDir, "sync-test/not/created/by/anyone")
        val folder = SafSyncFolder.fromDirectory(context, missing, own)
        // fromDirectory makes it, so take it away again underneath.
        folder.isUsable()
        missing.deleteRecursively()
        File(context.cacheDir, "sync-test").deleteRecursively()
        assertEquals(emptyList<String>(), folder.devices())
        assertNull(folder.lines(other, "history/2027-01-15.jsonl"))
    }

    @Test
    fun theFolderLayoutIsWhatTheFormatSays() {
        val writer = HistoryWriter(other, folder(other))
        writer.offer(listen(now, "believer"))
        writer.flush(now, force = true)
        val written = directory.walkTopDown().filter { it.isFile }.map {
            it.relativeTo(directory).path
        }.toList()
        assertEquals(1, written.size)
        assertTrue("путь: ${written.single()}",
            written.single().startsWith("aura/v1/$other/history/2027-01-15"))
    }
}
