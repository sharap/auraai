package music.ai.recommend.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryWriterTest {

    /** A sink that keeps what it was given, and can be told to refuse. */
    private class Recording(var accepting: Boolean = true) : SyncSink {
        val written = LinkedHashMap<String, MutableList<String>>()
        var calls = 0

        override fun append(relativePath: String, lines: List<String>): Boolean {
            calls++
            if (!accepting) return false
            written.getOrPut(relativePath) { ArrayList() }.addAll(lines)
            return true
        }
    }

    private val now = 1_800_000_000_000L

    private fun listen(at: Long, title: String) = PlayRecord(
        playedAt = at,
        playedMs = 180_000,
        durationMs = 200_000,
        track = TrackKey(null, 200_000, "artist", title)
    )

    private fun writer(sink: SyncSink, startingSequence: Long = 0L) =
        HistoryWriter("ab".repeat(16), sink, startingSequence)

    @Test
    fun nothingWaitingWritesNothing() {
        val sink = Recording()
        assertEquals(0, writer(sink).flush(now, force = true))
        assertEquals(0, sink.calls)
    }

    @Test
    fun theFirstBatchOfARunGoesOutAtOnce() {
        // Deliberate: a device coming back after being away should say so rather than hold its
        // first batch for a quarter of an hour.
        val sink = Recording()
        val writer = writer(sink)
        writer.offer(listen(now - 1000, "believer"))
        assertTrue(writer.isDue(now))
        assertEquals(1, writer.flush(now))
    }

    @Test
    fun afterThatItBatches() {
        val sink = Recording()
        val writer = writer(sink)
        writer.offer(listen(now, "first"))
        writer.flush(now)

        writer.offer(listen(now + 1000, "second"))
        assertFalse(writer.isDue(now + 1000))
        assertEquals(0, writer.flush(now + 1000))
        assertEquals(1, writer.pending)

        // A quarter of an hour later the part-full batch goes.
        val later = now + HistoryWriter.BATCH_INTERVAL_MS
        assertTrue(writer.isDue(later))
        assertEquals(1, writer.flush(later))
    }

    @Test
    fun twentyRecordsAreWorthWritingOnTheirOwn() {
        val sink = Recording()
        val writer = writer(sink)
        writer.offer(listen(now, "first"))
        writer.flush(now)

        for (i in 1 until HistoryWriter.BATCH_RECORDS) {
            writer.offer(listen(now + i * 1000L, "track $i"))
            assertFalse("на $i записях ещё рано", writer.isDue(now + i * 1000L))
        }
        writer.offer(listen(now + 100_000, "the twentieth"))
        assertTrue(writer.isDue(now + 100_000))
        assertEquals(HistoryWriter.BATCH_RECORDS, writer.flush(now + 100_000))
    }

    @Test
    fun forcingWritesWhateverIsWaiting() {
        val sink = Recording()
        val writer = writer(sink)
        writer.offer(listen(now, "first"))
        writer.flush(now)
        writer.offer(listen(now + 1000, "second"))
        assertEquals(1, writer.flush(now + 1000, force = true))
        assertEquals(0, writer.pending)
    }

    @Test
    fun aFolderThatWillNotTakeTheBatchCostsADelayAndNotARecord() {
        val sink = Recording(accepting = false)
        val writer = writer(sink)
        writer.offer(listen(now, "believer"))
        assertEquals(0, writer.flush(now, force = true))
        assertEquals(1, writer.pending)
        assertEquals(0L, writer.sequence)

        sink.accepting = true
        assertEquals(1, writer.flush(now + 1000, force = true))
        assertEquals(1L, writer.sequence)
        assertEquals(0, writer.pending)
    }

    @Test
    fun numberingCarriesOnFromWhereItLeftOff() {
        val sink = Recording()
        val writer = writer(sink, startingSequence = 41L)
        writer.offer(listen(now, "believer"))
        writer.flush(now, force = true)
        assertEquals(42L, writer.sequence)
        val batch = SyncBatch.decode(sink.written.values.single().single())!!
        assertEquals(42L, batch.sequence)
        assertEquals("ab".repeat(16), batch.deviceId)
        assertEquals(SyncBatch.SEALING_NONE, batch.sealing)
    }

    @Test
    fun oneBatchPerFlushAndTheRecordsSurviveIt() {
        val sink = Recording()
        val writer = writer(sink)
        writer.offer(listen(now - 2000, "believer"))
        writer.offer(listen(now - 1000, "ночная кобыла"))
        writer.flush(now, force = true)

        val lines = sink.written.values.single()
        assertEquals(1, lines.size)
        val batch = SyncBatch.decode(lines.single())!!
        assertEquals(
            listOf("believer", "ночная кобыла"),
            batch.records.map { PlayRecord.fromJson(it)!!.track.title }
        )
    }

    @Test
    fun theFileIsNamedForTheDayOfWriting() {
        val sink = Recording()
        val writer = writer(sink)
        writer.offer(listen(now, "believer"))
        writer.flush(now, force = true)
        assertEquals("history/2027-01-15.jsonl", sink.written.keys.single())
    }

    @Test
    fun aBatchJustAfterMidnightGoesIntoTheNewDaysFile() {
        val sink = Recording()
        val writer = writer(sink)
        val newYear = 1_735_689_600_000L            // 2025-01-01T00:00:00Z
        writer.offer(listen(newYear - 60_000, "last of the year"))
        writer.flush(newYear + 1000, force = true)
        assertEquals("history/2025-01-01.jsonl", sink.written.keys.single())
    }

    @Test
    fun theDateIsTheUtcCivilDate() {
        assertEquals(Triple(1970, 1, 1), HistoryWriter.utcDate(0))
        assertEquals(Triple(2027, 1, 15), HistoryWriter.utcDate(1_800_000_000_000L))
        assertEquals(Triple(2025, 11, 5), HistoryWriter.utcDate(1_762_300_800_000L))
        assertEquals(Triple(2024, 2, 29), HistoryWriter.utcDate(1_709_164_800_000L))
        assertEquals(Triple(2024, 12, 31), HistoryWriter.utcDate(1_735_689_599_999L))
        assertEquals(Triple(2025, 1, 1), HistoryWriter.utcDate(1_735_689_600_000L))
        // Before the epoch, where a plain division would round the wrong way.
        assertEquals(Triple(1969, 12, 31), HistoryWriter.utcDate(-86_400_000L))
    }

    @Test
    fun anEveningOfListeningComesToAHandfulOfWrites() {
        // Thirty tracks back to back, flushed whenever the writer says so. How many times the
        // folder is touched is what Syncthing turns into radio wake-ups, so it is the number worth
        // asserting on: a couple an hour, not one per track.
        val sink = Recording()
        val writer = writer(sink)
        var at = now
        repeat(30) {
            writer.offer(listen(at, "track $it"))
            writer.flush(at)
            at += 3 * 60 * 1000L + 30_000L
        }
        writer.flush(at, force = true)
        val lines = sink.written.values.flatten()
        assertTrue("записей в папку: ${lines.size}", lines.size <= 5)
        val records = lines.mapNotNull { SyncBatch.decode(it) }.sumOf { it.records.size }
        assertEquals(30, records)
    }
}
