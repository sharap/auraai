package music.ai.recommend.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The fixture both implementations have to agree on.
 *
 * `src/test/resources/sync/history-fixture.jsonl` is a day's file as another device might leave it,
 * with every case a reader has to survive in it on purpose: a listen told of twice, a device whose
 * clock is two days fast, a listen older than the local store keeps, a record naming no track, a
 * missing batch, a batch sealed in a way this version cannot read, a line left half-written when
 * the file was copied, and this device's own batch. If the desktop port folds this file in and gets
 * a different answer, one of the two is wrong, and the history is the one thing in the app that
 * cannot be recomputed.
 */
class HistoryMergeTest {

    private val fixture = File("src/test/resources/sync/history-fixture.jsonl")

    /** The clock the fixture was written against. */
    private val now = 1_800_000_000_000L

    private fun decoded(): Pair<List<SyncBatch>, Int> {
        val lines = fixture.readLines().filter { it.isNotBlank() }
        val batches = lines.mapNotNull { SyncBatch.decode(it) }
        return batches to (lines.size - batches.size)
    }

    @Test
    fun theUnreadableLinesAreSkippedAndTheRestSurvive() {
        val (batches, skipped) = decoded()
        // The sealed batch and the half-written line; a reader loses those two and nothing else.
        assertEquals(2, skipped)
        assertEquals(5, batches.size)
    }

    @Test
    fun theFixtureFoldsInToExactlyThis() {
        val (batches, _) = decoded()
        val result = HistoryMerge.merge(
            batches = batches,
            known = emptySet(),
            ownDeviceId = "self",
            now = now,
            keepDays = 365
        )
        assertEquals(5, result.accepted.size)
        assertEquals(1, result.alreadyHeld)
        assertEquals(1, result.tooOld)
        assertEquals(1, result.fromTheFuture)
        assertEquals(1, result.unreadable)
        assertEquals(mapOf("phone" to listOf(3L)), result.gaps)

        // Oldest first within a device, and devices in a fixed order, so that two readers that
        // took the files in a different order still insert the same rows in the same sequence.
        assertEquals(
            listOf("desktop", "phone", "phone", "phone", "phone"),
            result.accepted.map { it.deviceId }
        )
        assertEquals(
            listOf("ночная кобыла", "believer", "самый дорогой человек", "track 1", "atlantia"),
            result.accepted.map { it.record.track.title }
        )
    }

    @Test
    fun aSecondPassTakesNothingNew() {
        val (batches, _) = decoded()
        val first = HistoryMerge.merge(batches, emptySet(), "self", now, 365)
        val known = first.accepted.map { HistoryMerge.Known(it.deviceId, it.record.playedAt) }.toSet()
        val second = HistoryMerge.merge(batches, known, "self", now, 365)
        assertTrue(second.accepted.isEmpty())
        assertEquals(6, second.alreadyHeld)
    }

    @Test
    fun keepingEverythingAcceptsTheOldListenToo() {
        val (batches, _) = decoded()
        val result = HistoryMerge.merge(batches, emptySet(), "self", now, keepDays = 0)
        assertEquals(6, result.accepted.size)
        assertEquals(0, result.tooOld)
    }

    @Test
    fun aDeviceMergingItsOwnFileTakesNothing() {
        val (batches, _) = decoded()
        val result = HistoryMerge.merge(batches, emptySet(), "phone", now, 365)
        assertTrue(result.accepted.none { it.deviceId == "phone" })
        assertNull(result.gaps["phone"])
    }

    @Test
    fun theFractionIsWhatTheTwoAppsJudgeAListenBy() {
        val (batches, _) = decoded()
        val result = HistoryMerge.merge(batches, emptySet(), "self", now, 365)
        val believer = result.accepted.first { it.record.track.title == "believer" }
        assertEquals(0.9f, believer.record.fraction, 1e-6f)
        val atlantia = result.accepted.first { it.record.track.title == "atlantia" }
        assertEquals(5_000f / 300_000f, atlantia.record.fraction, 1e-6f)
    }

    @Test
    fun aRecordSurvivesBeingWrittenAndReadBack() {
        val original = PlayRecord(
            playedAt = now - 1000,
            playedMs = 123_456,
            durationMs = 234_567,
            track = TrackKey("ab".repeat(32), 234_567, "мельница", "дороги")
        )
        val line = SyncBatch("phone", 42, SyncBatch.SEALING_NONE, listOf(original.toJson())).encode()!!
        val batch = SyncBatch.decode(line)!!
        assertEquals("phone", batch.deviceId)
        assertEquals(42L, batch.sequence)
        assertEquals(original, PlayRecord.fromJson(batch.records.single()))
    }

    @Test
    fun anEmptyBatchIsStillAValidLine() {
        val line = SyncBatch("phone", 1, SyncBatch.SEALING_NONE, emptyList()).encode()!!
        assertEquals(emptyList<Any>(), SyncBatch.decode(line)!!.records)
    }

    @Test
    fun rubbishIsNotMistakenForABatch() {
        assertNull(SyncBatch.decode(""))
        assertNull(SyncBatch.decode("   "))
        assertNull(SyncBatch.decode("not json at all"))
        assertNull(SyncBatch.decode("[1,2,3]"))
        assertNull(SyncBatch.decode("""{"n":1,"e":"none","ct":[]}"""))
        assertNull(SyncBatch.decode("""{"d":"","n":1,"e":"none","ct":[]}"""))
        assertNull(SyncBatch.decode("""{"d":"phone","e":"none","ct":[]}"""))
        assertNull(SyncBatch.decode("""{"d":"phone","n":1,"ct":[]}"""))
        assertNull(SyncBatch.decode("""{"d":"phone","n":1,"e":"none"}"""))
    }
}
