package music.ai.recommend.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryReaderTest {

    /** A folder held in memory, that can also be made briefly unreadable. */
    private class Folder : SyncSource {
        val contents = LinkedHashMap<String, MutableList<String>>()
        val unreadable = HashSet<String>()
        var reads = 0

        fun put(deviceId: String, path: String, lines: List<String>) {
            contents.getOrPut("$deviceId/$path") { ArrayList() }.addAll(lines)
        }

        override fun devices(): List<String> = contents.keys.map { it.substringBefore('/') }.distinct()

        override fun files(deviceId: String, inside: String): List<String> = contents.keys
            .filter { it.startsWith("$deviceId/$inside/") }
            .map { it.substringAfter('/') }

        override fun lines(deviceId: String, relativePath: String): List<String>? {
            val key = "$deviceId/$relativePath"
            if (key in unreadable) return null
            reads++
            return contents[key]
        }
    }

    private val own = "11".repeat(16)
    private val phone = "22".repeat(16)
    private val desktop = "33".repeat(16)

    private val now = 1_800_000_000_000L
    private val today = HistoryWriter.dayFile(now)

    private fun listen(at: Long, title: String) = PlayRecord(
        playedAt = at,
        playedMs = 180_000,
        durationMs = 200_000,
        track = TrackKey(null, 200_000, "artist", title)
    )

    /** Every record gets its own instant: two listens at the same millisecond are one listen. */
    private fun batch(deviceId: String, sequence: Long, vararg titles: String): String {
        val records = titles.mapIndexed { i, title ->
            listen(now - 3_600_000L + sequence * 60_000L + i * 1_000L, title)
        }
        return SyncBatch(deviceId, sequence, SyncBatch.SEALING_NONE, records.map { it.toJson() }).encode()
    }

    /**
     * The reader as its caller uses it: read, then record what the pass consumed.
     *
     * The reader does not touch the progress map itself — a store that fails has to leave the
     * folder looking unread — so whoever calls it folds the answer in afterwards, and so does this.
     */
    private class Pass(private val reader: HistoryReader, val progress: MutableMap<String, Int>) {
        fun read(known: Set<HistoryMerge.Known>, now: Long, keepDays: Int): HistoryReader.Outcome =
            reader.read(known, now, keepDays).also { progress.putAll(it.advanced) }
    }

    private fun reader(folder: Folder, progress: MutableMap<String, Int> = HashMap()): Pair<Pass, MutableMap<String, Int>> =
        Pass(HistoryReader(own, folder, progress), progress) to progress

    @Test
    fun anEmptyFolderYieldsNothing() {
        val (reader, _) = reader(Folder())
        val outcome = reader.read(emptySet(), now, 365)
        assertTrue(outcome.merge.accepted.isEmpty())
        assertEquals(0, outcome.filesRead)
    }

    @Test
    fun everyDeviceButThisOneIsRead() {
        val folder = Folder()
        folder.put(own, today, listOf(batch(own, 1, "mine")))
        folder.put(phone, today, listOf(batch(phone, 1, "believer")))
        folder.put(desktop, today, listOf(batch(desktop, 1, "ночная кобыла")))
        val (reader, _) = reader(folder)
        val outcome = reader.read(emptySet(), now, 365)
        assertEquals(2, outcome.filesRead)
        // Devices in the order their ids sort in, so two readers that happened to list the folder
        // differently still insert the same rows in the same sequence.
        assertEquals(listOf("believer", "ночная кобыла"), outcome.merge.accepted.map { it.record.track.title })
    }

    @Test
    fun aDirectoryThatIsNotADeviceIsLeftAlone() {
        val folder = Folder()
        folder.put("not-a-device-id", today, listOf(batch(phone, 1, "believer")))
        folder.put(".stfolder", today, listOf(batch(phone, 2, "also not")))
        val (reader, _) = reader(folder)
        assertEquals(0, reader.read(emptySet(), now, 365).filesRead)
    }

    @Test
    fun aFinishedFileIsNotOpenedTwice() {
        val folder = Folder()
        folder.put(phone, today, listOf(batch(phone, 1, "believer")))
        val (reader, progress) = reader(folder)

        val first = reader.read(emptySet(), now, 365)
        assertEquals(1, first.merge.accepted.size)
        assertEquals(1, progress["$phone/$today"])

        val held = first.merge.accepted.map { HistoryMerge.Known(it.deviceId, it.record.playedAt) }.toSet()
        val second = reader.read(held, now, 365)
        assertEquals(0, second.filesRead)
        assertEquals(1, second.filesSkipped)
        assertTrue(second.merge.accepted.isEmpty())
    }

    @Test
    fun aGrowingFileIsReadOnlyFromWhereItStopped() {
        val folder = Folder()
        folder.put(phone, today, listOf(batch(phone, 1, "first")))
        val (reader, progress) = reader(folder)
        val first = reader.read(emptySet(), now, 365)
        val held = first.merge.accepted.map { HistoryMerge.Known(it.deviceId, it.record.playedAt) }.toMutableSet()

        folder.put(phone, today, listOf(batch(phone, 2, "second"), batch(phone, 3, "third")))
        val second = reader.read(held, now, 365)
        assertEquals(1, second.filesRead)
        // Only the two new batches were parsed, so nothing was already held.
        assertEquals(0, second.merge.alreadyHeld)
        assertEquals(listOf("second", "third"), second.merge.accepted.map { it.record.track.title })
        assertEquals(3, progress["$phone/$today"])
    }

    @Test
    fun aHalfWrittenLastLineIsMetAgainWhenTheRestArrives() {
        val folder = Folder()
        val complete = batch(phone, 2, "believer")
        folder.put(phone, today, listOf(batch(phone, 1, "first"), complete.substring(0, 40)))
        val (reader, progress) = reader(folder)

        val first = reader.read(emptySet(), now, 365)
        assertEquals(1, first.merge.accepted.size)
        assertEquals(1, first.linesUnreadable)
        // One line consumed, not two: the half line is left for next time.
        assertEquals(1, progress["$phone/$today"])

        // The file arrives in full.
        folder.contents["$phone/$today"]!![1] = complete
        val held = first.merge.accepted.map { HistoryMerge.Known(it.deviceId, it.record.playedAt) }.toSet()
        val second = reader.read(held, now, 365)
        assertEquals(listOf("believer"), second.merge.accepted.map { it.record.track.title })
        assertEquals(2, progress["$phone/$today"])
    }

    @Test
    fun aDamagedLineInTheMiddleIsPassedOverRatherThanWaitedFor() {
        val folder = Folder()
        folder.put(phone, today, listOf(batch(phone, 1, "first"), "}{ rubbish", batch(phone, 3, "third")))
        val (reader, progress) = reader(folder)
        val outcome = reader.read(emptySet(), now, 365)
        assertEquals(listOf("first", "third"), outcome.merge.accepted.map { it.record.track.title })
        assertEquals(1, outcome.linesUnreadable)
        assertEquals(3, progress["$phone/$today"])
        assertEquals(mapOf(phone to listOf(2L)), outcome.merge.gaps)
    }

    @Test
    fun aFileOlderThanTheStoreKeepsIsNeverOpened() {
        val folder = Folder()
        val old = HistoryWriter.dayFile(now - 400 * 24 * 60 * 60 * 1000L)
        folder.put(phone, old, listOf(batch(phone, 1, "pruned")))
        folder.put(phone, today, listOf(batch(phone, 2, "believer")))
        val (reader, _) = reader(folder)
        val outcome = reader.read(emptySet(), now, 365)
        assertEquals(1, outcome.filesRead)
        assertEquals(1, outcome.filesSkipped)
        assertEquals(1, folder.reads)
        assertEquals(listOf("believer"), outcome.merge.accepted.map { it.record.track.title })
    }

    @Test
    fun keepingEverythingOpensTheOldFileToo() {
        val folder = Folder()
        val old = HistoryWriter.dayFile(now - 400 * 24 * 60 * 60 * 1000L)
        folder.put(phone, old, listOf(batch(phone, 1, "kept")))
        val (reader, _) = reader(folder)
        assertEquals(1, reader.read(emptySet(), now, keepDays = 0).merge.accepted.size)
    }

    @Test
    fun aFileThatCannotBeReadNowIsMetAgainLater() {
        val folder = Folder()
        folder.put(phone, today, listOf(batch(phone, 1, "believer")))
        folder.unreadable.add("$phone/$today")
        val (reader, progress) = reader(folder)

        val first = reader.read(emptySet(), now, 365)
        assertEquals(1, first.filesUnavailable)
        assertTrue(first.merge.accepted.isEmpty())
        assertTrue(progress.isEmpty())

        folder.unreadable.clear()
        assertEquals(1, reader.read(emptySet(), now, 365).merge.accepted.size)
    }

    @Test
    fun aDayWrittenAsSeveralFilesReadsTheSame() {
        // What a provider without an append mode leaves behind.
        val folder = Folder()
        folder.put(phone, "history/2027-01-15/0001.jsonl", listOf(batch(phone, 1, "first")))
        folder.put(phone, "history/2027-01-15/0002.jsonl", listOf(batch(phone, 2, "second")))
        val (reader, _) = reader(folder)
        val outcome = reader.read(emptySet(), now, 365)
        assertEquals(2, outcome.filesRead)
        assertEquals(listOf("first", "second"), outcome.merge.accepted.map { it.record.track.title })
    }

    @Test
    fun aFileThatWasRewrittenIsReadFromTheTopAgain() {
        val folder = Folder()
        folder.put(phone, today, listOf(batch(phone, 1, "a"), batch(phone, 2, "b"), batch(phone, 3, "c")))
        val (reader, progress) = reader(folder)
        val first = reader.read(emptySet(), now, 365)
        assertEquals(3, progress["$phone/$today"])

        // Against the rules, but a reader that stopped for good would be worse.
        folder.contents["$phone/$today"] = mutableListOf(batch(phone, 1, "a"))
        val held = first.merge.accepted.map { HistoryMerge.Known(it.deviceId, it.record.playedAt) }.toSet()
        val second = reader.read(held, now, 365)
        assertEquals(1, second.filesRead)
        assertEquals(1, second.merge.alreadyHeld)
        assertEquals(1, progress["$phone/$today"])
    }

    @Test
    fun whatTheWriterWroteIsWhatTheReaderReads() {
        // The round trip, with nothing in between but the two shared files.
        val written = LinkedHashMap<String, MutableList<String>>()
        val sink = object : SyncSink {
            override fun append(relativePath: String, lines: List<String>): Boolean {
                written.getOrPut(relativePath) { ArrayList() }.addAll(lines)
                return true
            }
        }
        val writer = HistoryWriter(phone, sink)
        val sent = listOf(
            listen(now - 300_000, "believer"),
            listen(now - 200_000, "ночная кобыла"),
            listen(now - 100_000, "atlantia")
        )
        sent.forEach { writer.offer(it) }
        assertEquals(3, writer.flush(now, force = true))

        val folder = Folder()
        written.forEach { (path, lines) -> folder.put(phone, path, lines) }
        val (reader, _) = reader(folder)
        val outcome = reader.read(emptySet(), now, 365)
        assertEquals(sent, outcome.merge.accepted.map { it.record })
        assertEquals(emptyMap<String, List<Long>>(), outcome.merge.gaps)
    }
}
