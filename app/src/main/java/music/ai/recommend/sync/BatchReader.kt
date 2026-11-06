package music.ai.recommend.sync

/**
 * Walking the other devices' files in a folder and handing back the batches in them.
 *
 * Everything that is the same for history, vectors and playlist changes lives here, and it is the
 * part that is easy to get subtly wrong: which files are ours to open, how far each has been read,
 * what to do with a line that will not decode, and when to stop. Three copies of that is how one
 * of them comes to be quietly different.
 *
 * The one case worth being careful about is the last line of a file that is still being written. A
 * file copied mid-append ends in half a line, and counting that half as read would lose the batch
 * for good once the rest of it arrived. So a final line that will not decode is left unconsumed and
 * met again next time — while a line in the *middle* that will not decode is passed over, because
 * in a file that is only ever appended to the only line that can be half-written is the last one,
 * and anything else there is damage that will not improve by waiting.
 *
 * [progress] is read and never written: what a pass consumed comes back in [Outcome.advanced], for
 * the caller to fold in **after** whatever was read has been stored. A store that fails has to
 * leave the folder looking unread, or the batch is marked read and never arrives.
 *
 * The desktop port carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
class BatchReader(
    private val ownDeviceId: String,
    private val source: SyncSource,
    private val progress: Map<String, Int>
) {

    data class Outcome(
        /** Every batch read this pass, in the order the files gave them. */
        val batches: List<SyncBatch>,
        /** Files that had something new in them. */
        val filesRead: Int,
        /** Files passed over: already read to the end, or older than the caller cares about. */
        val filesSkipped: Int,
        /** Files that could not be read this time and will be met again. */
        val filesUnavailable: Int,
        /** Lines that would not decode, the half-written tails among them. */
        val linesUnreadable: Int,
        /** True when the pass stopped at its limit and more is waiting in the folder. */
        val more: Boolean,
        /** How many lines of each file this pass consumed; see [progress]. */
        val advanced: Map<String, Int>
    )

    /**
     * @param inside `history`, `vectors` or `playlists`.
     * @param maxRecords how many records this pass may take. The line being read is always
     *   finished, so a little more than this may come back; what matters is that it is bounded.
     * @param oldestDay a day name, `2026-11-05`, before which a file holds nothing the caller
     *   keeps. Null reads every day there is, which is what a log that is the truth needs.
     */
    fun read(inside: String, maxRecords: Int = Int.MAX_VALUE, oldestDay: String? = null): Outcome {
        val batches = ArrayList<SyncBatch>()
        val advanced = HashMap<String, Int>()
        var filesRead = 0
        var filesSkipped = 0
        var filesUnavailable = 0
        var linesUnreadable = 0
        var records = 0
        var more = false

        for (deviceId in source.devices().sorted()) {
            if (deviceId == ownDeviceId) continue
            if (!SyncIdentity.isValid(deviceId)) continue
            for (path in source.files(deviceId, inside).sorted()) {
                // Anything else in the folder is somebody else's business — above all a file
                // Syncthing is still transferring. See [DayFile].
                if (!DayFile.isOurs(path)) continue
                if (records >= maxRecords) {
                    more = true
                    continue
                }
                if (oldestDay != null && isBefore(path, oldestDay)) {
                    filesSkipped++
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
                    // again from the top is the safe answer: what is already held is turned away
                    // further up, so the cost is one pass and not a duplicate.
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
                        lastFailed = index == lines.size - 1 && line.isNotBlank()
                        taken = index + 1
                        continue
                    }
                    batches.add(batch)
                    records += batch.records.size
                    taken = index + 1
                    if (records >= maxRecords && index < lines.size - 1) {
                        more = true
                        break
                    }
                }
                advanced[key] = if (lastFailed) taken - 1 else taken
            }
        }

        return Outcome(batches, filesRead, filesSkipped, filesUnavailable, linesUnreadable, more, advanced)
    }

    private companion object {

        /** `history/2026-11-05.jsonl` and `history/2026-11-05/0003.jsonl` alike. */
        val DAY_IN_PATH = Regex("""[a-z]+/(\d{4}-\d{2}-\d{2})""")

        /**
         * Whether a file is entirely older than [oldestDay]. Day names sort as dates do, which is
         * the one thing that makes this cheap: a string comparison on the name, and no file opened.
         * A path that does not carry a day is read, because there is nothing to conclude from it.
         */
        fun isBefore(path: String, oldestDay: String): Boolean {
            val day = DAY_IN_PATH.find(path)?.groupValues?.get(1) ?: return false
            return day < oldestDay
        }
    }
}
