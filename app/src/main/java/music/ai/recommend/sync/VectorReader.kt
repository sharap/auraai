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
 * The desktop port carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
class VectorReader(
    private val ownDeviceId: String,
    private val source: SyncSource,
    private val progress: MutableMap<String, Int>
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
        val records = ArrayList<Incoming>()
        val advanced = HashMap<String, Int>()
        var filesRead = 0
        var filesSkipped = 0
        var filesUnavailable = 0
        var linesUnreadable = 0
        var more = false

        for (deviceId in source.devices().sorted()) {
            if (deviceId == ownDeviceId) continue
            if (!SyncIdentity.isValid(deviceId)) continue
            for (path in source.files(deviceId, VectorWriter.DIRECTORY).sorted()) {
                if (records.size >= maxRecords) {
                    more = true
                    continue
                }
                val key = "$deviceId/$path"
                var consumed = progress[key] ?: 0
                val lines = source.lines(deviceId, path)
                if (lines == null) {
                    filesUnavailable++
                    continue
                }
                if (lines.size < consumed) {
                    // A file that has shrunk was rewritten, which the format forbids. Reading it
                    // again from the top is the safe answer: a vector already held is simply not
                    // stored twice.
                    consumed = 0
                }
                if (lines.size <= consumed) {
                    filesSkipped++
                    continue
                }
                filesRead++

                var taken = consumed
                var lastFailed = false
                for (index in consumed until lines.size) {
                    val line = lines[index]
                    val batch = SyncBatch.decode(line)
                    if (batch == null) {
                        if (line.isNotBlank()) linesUnreadable++
                        // Only the final line of a file can be half-written, since the file is only
                        // ever appended to. One in the middle is damage that waiting will not mend.
                        lastFailed = index == lines.size - 1 && line.isNotBlank()
                        taken = index + 1
                        continue
                    }
                    // Attributed by the name inside the batch and not by the directory it was
                    // found in, which is what the history reader does too. It costs nothing — a
                    // vector is judged by the model, the version, the decoder and the excerpt, not
                    // by who handed it over — and it leaves room for a device that can see two
                    // others to pass their files along to each other.
                    if (batch.deviceId != ownDeviceId) {
                        for (json in batch.records) {
                            val record = VectorRecord.fromJson(json)
                            if (record == null) {
                                linesUnreadable++
                            } else {
                                records.add(Incoming(batch.deviceId, record))
                            }
                        }
                    }
                    taken = index + 1
                    if (records.size >= maxRecords && index < lines.size - 1) {
                        more = true
                        break
                    }
                }
                advanced[key] = if (lastFailed) taken - 1 else taken
            }
        }

        return Outcome(records, filesRead, filesSkipped, filesUnavailable, linesUnreadable, more, advanced)
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
