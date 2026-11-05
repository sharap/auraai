package music.ai.recommend.sync

import music.ai.recommend.ai.Mp3Excerpt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outbox and the reader for embeddings, over a folder that is a map.
 *
 * What is worth pinning here is the policy, because it is where this differs from history and the
 * difference is deliberate: no timer, a batch when there are enough, a bounded pass on the way in,
 * and nothing marked read that was not handed over.
 */
class VectorTransportTest {

    private val phone = "aa".repeat(16)
    private val desktop = "bb".repeat(16)
    private val model = "3ecc72d27740e2a09ced20cf22fd6244122e5e506008763a0f368b3b4ff6eac8"

    /** 2026-11-05T12:00:00Z. */
    private val now = 1_793_880_000_000L

    /** A folder that is a map, so the policy is tested without a disk anywhere near it. */
    private class Folder : SyncFolder {
        val contents = LinkedHashMap<String, MutableList<String>>()
        var refuse = false
        val unreadable = HashSet<String>()

        override fun append(relativePath: String, lines: List<String>): Boolean {
            if (refuse) return false
            contents.getOrPut("$OWNER/$relativePath") { ArrayList() }.addAll(lines)
            return true
        }

        fun put(deviceId: String, path: String, lines: List<String>) {
            contents.getOrPut("$deviceId/$path") { ArrayList() }.addAll(lines)
        }

        override fun devices(): List<String> =
            contents.keys.map { it.substringBefore('/') }.distinct()

        override fun files(deviceId: String, inside: String): List<String> = contents.keys
            .filter { it.startsWith("$deviceId/$inside/") }
            .map { it.substringAfter('/') }

        override fun lines(deviceId: String, relativePath: String): List<String>? {
            val key = "$deviceId/$relativePath"
            if (key in unreadable) return null
            return contents[key]
        }

        companion object {
            /** Whose directory [append] writes into; the writer is told the same id. */
            const val OWNER = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        }
    }

    private fun vector(seed: Int) = FloatArray(512) { ((it + seed) % 13) * 0.07f + 0.01f }

    /**
     * A pass as the coordinator makes it: read, then record what was consumed.
     *
     * The reader does not touch the progress map itself — a store that fails has to leave the
     * folder looking unread — so every caller folds the answer in afterwards, and so does this.
     */
    private fun pass(
        folder: Folder,
        progress: MutableMap<String, Int>,
        reader: String = desktop,
        maxRecords: Int = VectorReader.MAX_RECORDS
    ): VectorReader.Outcome =
        VectorReader(reader, folder, progress).read(maxRecords).also { progress.putAll(it.advanced) }

    private fun record(
        title: String,
        decoder: String = Mp3Excerpt.DECODER,
        floats: FloatArray = vector(1)
    ) = VectorRecord(
        track = TrackKey(null, 210_000, "мельница", title),
        excerptStartMs = 100_000L,
        decoder = decoder,
        model = model,
        analysisVersion = 3,
        vector = floats
    )

    @Test
    fun aBatchGoesOutWhenThereAreEnoughOfThemAndNotBefore() {
        val folder = Folder()
        val writer = VectorWriter(phone, folder)
        repeat(VectorWriter.BATCH_RECORDS - 1) { assertTrue(writer.offer(record("трек $it"))) }

        // No timer: unlike a listen, a vector is worth the same tomorrow, so nothing goes out on
        // the strength of time having passed.
        assertEquals(0, writer.flush(now))
        assertEquals(0, writer.flush(now + 24 * 60 * 60 * 1000L))
        assertEquals(VectorWriter.BATCH_RECORDS - 1, writer.pending)

        writer.offer(record("ещё один"))
        assertEquals(VectorWriter.BATCH_RECORDS, writer.flush(now))
        assertEquals(0, writer.pending)
        assertEquals(1L, writer.sequence)
        assertEquals(
            listOf("${Folder.OWNER}/vectors/2026-11-05.jsonl"),
            folder.contents.keys.toList()
        )
    }

    @Test
    fun whatIsLeftOverGoesOutWhenTheRunIsOver() {
        val folder = Folder()
        val writer = VectorWriter(phone, folder)
        writer.offer(record("один"))
        writer.offer(record("два"))
        assertEquals(2, writer.flush(now, force = true))
        assertEquals(0, writer.flush(now, force = true))
    }

    @Test
    fun aFolderThatWillNotTakeThemKeepsThemWaiting() {
        val folder = Folder()
        folder.refuse = true
        val writer = VectorWriter(phone, folder)
        writer.offer(record("один"))
        assertEquals(0, writer.flush(now, force = true))
        assertEquals(1, writer.pending)
        assertEquals(0L, writer.sequence)

        // And the batch number is only spent once the batch is really on disk, so a reader never
        // sees a gap where there was only an unreachable folder.
        folder.refuse = false
        assertEquals(1, writer.flush(now, force = true))
        assertEquals(1L, writer.sequence)
    }

    @Test
    fun aVectorThatCannotTravelIsNotEvenTakenIntoTheOutbox() {
        val folder = Folder()
        val writer = VectorWriter(phone, folder)
        // A platform decoder: the other side is certain to refuse it, and for a library mostly in
        // formats without a shared decoder that would be nearly all of the folder spent on nothing.
        assertFalse(writer.offer(record("из m4a", decoder = "mediacodec-android")))
        // The all-zero marker for a file that could not be decoded is not an embedding.
        assertFalse(writer.offer(record("битый", floats = FloatArray(512))))
        assertEquals(0, writer.pending)
        assertEquals(0, writer.flush(now, force = true))
    }

    @Test
    fun whatOneDeviceWroteTheOtherReads() {
        val folder = Folder()
        val writer = VectorWriter(Folder.OWNER, folder)
        writer.offer(record("один"))
        writer.offer(record("два", floats = vector(2)))
        assertEquals(2, writer.flush(now, force = true))

        val progress = HashMap<String, Int>()
        val outcome = pass(folder, progress)
        assertEquals(2, outcome.records.size)
        assertEquals(listOf("один", "два"), outcome.records.map { it.record.track.title })
        assertEquals(listOf(Folder.OWNER), outcome.records.map { it.deviceId }.distinct())
        assertTrue(outcome.records[0].record.vector.contentEquals(vector(1)))
        assertFalse(outcome.more)

        // Read again and nothing comes twice.
        val again = pass(folder, progress)
        assertEquals(0, again.records.size)
        assertEquals(1, again.filesSkipped)
    }

    @Test
    fun aDeviceDoesNotReadItsOwnVectorsBack() {
        val folder = Folder()
        val writer = VectorWriter(Folder.OWNER, folder)
        writer.offer(record("свой"))
        writer.flush(now, force = true)

        val outcome = pass(folder, HashMap(), reader = Folder.OWNER)
        assertEquals(0, outcome.records.size)
        assertEquals(0, outcome.filesRead)
    }

    @Test
    fun aPassIsBoundedAndTheRestWaitsForTheNext() {
        val folder = Folder()
        val writer = VectorWriter(Folder.OWNER, folder)
        // Four batches of fifty, as a library's backfill arrives.
        repeat(4 * VectorWriter.BATCH_RECORDS) { writer.offer(record("трек $it")) }
        repeat(4) { assertEquals(VectorWriter.BATCH_RECORDS, writer.flush(now)) }

        val progress = HashMap<String, Int>()
        val first = pass(folder, progress, maxRecords = 60)
        // The line being read is always finished, so a batch is never half taken.
        assertEquals(100, first.records.size)
        assertTrue(first.more)

        val second = pass(folder, progress, maxRecords = 60)
        assertEquals(100, second.records.size)
        assertFalse("больше ничего быть не должно", second.more)

        // Nothing was lost and nothing came twice.
        val titles = (first.records + second.records).map { it.record.track.title }
        assertEquals(200, titles.size)
        assertEquals(200, titles.toSet().size)
        assertEquals(0, pass(folder, progress).records.size)
    }

    @Test
    fun aHalfWrittenTailIsLeftForNextTime() {
        val folder = Folder()
        val good = SyncBatch(phone, 1, SyncBatch.SEALING_NONE, listOf(record("целая").toJson()))
        folder.put(phone, "vectors/2026-11-05.jsonl", listOf(good.encode()))
        val truncated = SyncBatch(phone, 2, SyncBatch.SEALING_NONE, listOf(record("обрезанная").toJson()))
            .encode().dropLast(40)
        folder.put(phone, "vectors/2026-11-05.jsonl", listOf(truncated))

        val progress = HashMap<String, Int>()
        val first = pass(folder, progress)
        assertEquals(listOf("целая"), first.records.map { it.record.track.title })
        assertEquals(1, first.linesUnreadable)
        assertEquals(1, progress["$phone/vectors/2026-11-05.jsonl"])

        // The rest of that line arrives, and the batch is read whole.
        folder.contents["$phone/vectors/2026-11-05.jsonl"]!!.let { lines ->
            lines[1] = SyncBatch(phone, 2, SyncBatch.SEALING_NONE, listOf(record("обрезанная").toJson())).encode()
        }
        val second = pass(folder, progress)
        assertEquals(listOf("обрезанная"), second.records.map { it.record.track.title })
    }

    @Test
    fun aFileThatCannotBeReadNowIsMetAgain() {
        val folder = Folder()
        val writer = VectorWriter(Folder.OWNER, folder)
        writer.offer(record("один"))
        writer.flush(now, force = true)
        folder.unreadable.add("${Folder.OWNER}/vectors/2026-11-05.jsonl")

        val progress = HashMap<String, Int>()
        val blocked = pass(folder, progress)
        assertEquals(0, blocked.records.size)
        assertEquals(1, blocked.filesUnavailable)
        assertTrue("ничего не должно считаться прочитанным", progress.isEmpty())

        folder.unreadable.clear()
        assertEquals(1, pass(folder, progress).records.size)
    }

    @Test
    fun whatSyncthingLeavesBehindIsNotRead() {
        // Found on a live exchange: a phone had read twelve lines out of a file Syncthing was
        // still transferring, and kept a note of its progress through a name that would not exist
        // a second later. A file being filled block by block is the one place where "only the last
        // line can be half-written" stops being true.
        val folder = Folder()
        val writer = VectorWriter(Folder.OWNER, folder)
        writer.offer(record("настоящая"))
        writer.flush(now, force = true)
        // As Syncthing names a transfer in progress, and as it used to.
        folder.put(Folder.OWNER, "vectors/.syncthing.2026-11-05.jsonl.tmp", listOf("половина стр"))
        folder.put(Folder.OWNER, "vectors/~syncthing~2026-11-05.jsonl.tmp", listOf("и ещё"))
        folder.put(Folder.OWNER, "vectors/2026-11-05.jsonl.tmp", listOf("и это"))

        val progress = HashMap<String, Int>()
        val outcome = pass(folder, progress)
        assertEquals(listOf("настоящая"), outcome.records.map { it.record.track.title })
        assertEquals(0, outcome.linesUnreadable)
        assertEquals(setOf("${Folder.OWNER}/vectors/2026-11-05.jsonl"), progress.keys)
    }

    @Test
    fun bothShapesOfADayAreOurs() {
        assertTrue(DayFile.isOurs("history/2026-11-05.jsonl"))
        assertTrue(DayFile.isOurs("vectors/2026-11-05.jsonl"))
        assertTrue(DayFile.isOurs("history/2026-11-05/0003.jsonl"))
        for (other in listOf(
            "vectors/.syncthing.2026-11-05.jsonl.tmp",
            "vectors/~syncthing~2026-11-05.jsonl.tmp",
            "vectors/2026-11-05.jsonl.tmp",
            "vectors/2026-11-05.jsonl.bak",
            "vectors/readme.txt",
            "vectors/2026-11.jsonl",
            "vectors/2026-11-05/.hidden.jsonl",
            "2026-11-05.jsonl"
        )) {
            assertFalse(other, DayFile.isOurs(other))
        }
    }

    @Test
    fun historyAndVectorsDoNotStepOnEachOther() {
        // One progress map, one device directory, two readers. The keys carry the file's own
        // directory, so neither can mark the other's file as read.
        val folder = Folder()
        VectorWriter(phone, folder).let { it.offer(record("вектор")); it.flush(now, force = true) }
        val listen = PlayRecord(now, 180_000, 210_000, TrackKey(null, 210_000, "мельница", "вектор"))
        folder.put(phone, HistoryWriter.dayFile(now), listOf(
            SyncBatch(phone, 1, SyncBatch.SEALING_NONE, listOf(listen.toJson())).encode()
        ))

        val progress = HashMap<String, Int>()
        val vectors = pass(folder, progress)
        val history = HistoryReader(desktop, folder, progress).read(emptySet(), now, 30)
            .also { progress.putAll(it.advanced) }
        assertEquals(1, vectors.records.size)
        assertEquals(1, history.merge.accepted.size)
        assertEquals(
            setOf("$phone/history/2026-11-05.jsonl", "${Folder.OWNER}/vectors/2026-11-05.jsonl"),
            progress.keys
        )
    }
}
