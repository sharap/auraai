package music.ai.recommend.sync

/**
 * Reads the other devices' embeddings out of the sync folder.
 *
 * A sibling of [HistoryReader], and the differences are all consequences of one fact: a vector has
 * no date. It describes a file, so it never goes stale and never falls out of a window — which
 * means there is no day old enough to skip without opening, the one cheap trick the history reader
 * leans on. What is left is the progress map: a file already read is not read again, and a file
 * being appended to is read from where the last pass stopped.
 *
 * It also stops early. A first exchange is a whole library — four thousand records, eleven
 * megabytes of text, and every one of them to be matched against this library and stored — and
 * doing that in one pass on a phone would be a long stall at the worst moment, with all of it in
 * memory at once. So a pass takes [maxRecords] and the rest waits for the next one; the backfill
 * happens over a few passes and nothing is lost in between, because progress is only ever recorded
 * for lines actually handed over.
 *
 * [progress] is how many lines of each file have been taken in. It is read here and never written:
 * what this pass consumed comes back in [Outcome.advanced], for the caller to fold in **after** the
 * vectors are stored. A store that fails must leave the folder looking unread. The map is shared
 * with the history reader, and the keys cannot collide because they carry the file's own directory.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
class VectorReader(
    private val ownDeviceId: String,
    private val source: SyncSource,
    private val progress: MutableMap<String, Int>,
    /** What can open a sealed batch; see [Sealing]. */
    private val sealing: () -> Sealing? = { null }
) {

    /** One device's vector, as read. */
    data class Incoming(val deviceId: String, val record: VectorRecord)

    data class Outcome(
        val records: List<Incoming>,
        /** Files that had something new in them. */
        val filesRead: Int,
        /** Files already read to the end. */
        val filesSkipped: Int,
        /** Files that could not be read this time and will be met again. */
        val filesUnavailable: Int,
        /** Lines that would not decode, the half-written tails among them. */
        val linesUnreadable: Int,
        /** Of those, the ones that are sealed and did not open; see [BatchReader.Outcome]. */
        val linesSealed: Int,
        /** True when the limit was reached and there is more waiting in the folder. */
        val more: Boolean,
        /**
         * How many lines of each file this pass consumed, for the caller to fold into [progress]
         * **after** the vectors are stored.
         *
         * Handed back rather than written here on purpose: a store that fails must leave the folder
         * looking unread, or the batch is marked read and never arrives. Recording it inside the
         * read would make that window last until the next restart.
         */
        val advanced: Map<String, Int>
    )

    /**
     * @param maxRecords how many vectors this pass may take. The line being read is always finished,
     *   so a little more than this may come back; what matters is that it is bounded.
     */
    fun read(maxRecords: Int = MAX_RECORDS): Outcome {
        // The walking lives in [BatchReader]; what is particular here is that no day is ever too
        // old to open — a vector describes a file, not a moment — and that the pass is bounded.
        val outcome = BatchReader(ownDeviceId, source, progress, sealing)
            .read(inside = VectorWriter.DIRECTORY, maxRecords = maxRecords)

        val records = ArrayList<Incoming>()
        var unreadable = outcome.linesUnreadable
        for (batch in outcome.batches) {
            // Attributed by the name inside the batch and not by the directory it was found in,
            // which is what history does too. It costs nothing — a vector is judged by the model,
            // the version, the decoder and the excerpt, not by who handed it over — and it leaves
            // room for a device that can see two others to pass their files along to each other.
            if (batch.deviceId == ownDeviceId) continue
            for (json in batch.records) {
                val record = VectorRecord.fromJson(json)
                if (record == null) unreadable++ else records.add(Incoming(batch.deviceId, record))
            }
        }

        return Outcome(
            records = records,
            filesRead = outcome.filesRead,
            filesSkipped = outcome.filesSkipped,
            filesUnavailable = outcome.filesUnavailable,
            linesUnreadable = unreadable,
            linesSealed = outcome.linesSealed,
            more = outcome.more,
            advanced = outcome.advanced
        )
    }

    companion object {

        /**
         * How many vectors one pass takes.
         *
         * Five hundred is about fourteen megabytes of samples' worth of analysis saved, a second or
         * two of work, and a megabyte and a half of text — small enough not to be felt, large
         * enough that a four thousand track backfill is nine passes rather than a hundred.
         */
        const val MAX_RECORDS = 500
    }
}
