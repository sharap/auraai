package music.ai.recommend.sync

/**
 * Reads the other devices' listening history out of the sync folder.
 *
 * The work it avoids matters as much as the work it does. Yesterday's file is finished forever, so
 * once it has been read there is no reason to open it again; a file older than the local store keeps
 * need never be opened at all, which its name already says; and today's file is only read from
 * where the last pass stopped. What is left is proportional to what arrived rather than to how long
 * the two devices have been syncing.
 *
 * The one case worth being careful about is the last line of a file that is still being written. A
 * file copied mid-append ends in half a line, and counting that half as read would lose the batch
 * for good once the rest of it arrived. So a final line that will not decode is left unconsumed and
 * met again next time — while a line in the *middle* that will not decode is passed over, because
 * in a file that is only ever appended to the only line that can be half-written is the last one,
 * and anything else there is damage that will not improve by waiting.
 *
 * [progress] is how many lines of each file have been taken in. It is read here and never written:
 * what this pass consumed comes back in [Outcome.advanced], for the caller to fold in **after** the
 * records have been stored. A store that fails has to leave the folder looking unread, or the batch
 * is marked read and never arrives. Losing the map entirely costs a re-read of everything and no
 * records, since the merge turns away what is already held.
 *
 * The desktop port carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
class HistoryReader(
    private val ownDeviceId: String,
    private val source: SyncSource,
    private val progress: MutableMap<String, Int>
) {

    data class Outcome(
        val merge: HistoryMerge.Result,
        /** Files that had something new in them. */
        val filesRead: Int,
        /** Files passed over: already finished, or older than the store keeps. */
        val filesSkipped: Int,
        /** Files that could not be read this time and will be met again. */
        val filesUnavailable: Int,
        /** Lines that would not decode, the half-written tails among them. */
        val linesUnreadable: Int,
        /** How many lines of each file this pass consumed, for the caller to record afterwards. */
        val advanced: Map<String, Int>
    )

    /**
     * @param known what this device already holds, so the merge can turn it away.
     * @param keepDays how long the local store keeps history; zero or less keeps everything.
     */
    fun read(known: Set<HistoryMerge.Known>, now: Long, keepDays: Int): Outcome {
        val batches = ArrayList<SyncBatch>()
        val advanced = HashMap<String, Int>()
        var filesRead = 0
        var filesSkipped = 0
        var filesUnavailable = 0
        var linesUnreadable = 0

        val oldestDay = if (keepDays > 0) dayOf(now - keepDays * DAY_MS) else null

        for (deviceId in source.devices().sorted()) {
            if (deviceId == ownDeviceId) continue
            if (!SyncIdentity.isValid(deviceId)) continue
            for (path in source.files(deviceId, DIRECTORY).sorted()) {
                // Anything else in the folder is somebody else's business — above all a file
                // Syncthing is still transferring. See [DayFile].
                if (!DayFile.isOurs(path)) continue
                val key = "$deviceId/$path"
                var consumed = progress[key] ?: 0
                if (oldestDay != null && isBefore(path, oldestDay)) {
                    filesSkipped++
                    continue
                }
                val lines = source.lines(deviceId, path)
                if (lines == null) {
                    filesUnavailable++
                    continue
                }
                if (lines.size < consumed) {
                    // A file that has shrunk was rewritten, which the format forbids. Reading it
                    // again from the top is the safe answer: the merge turns away what is already
                    // held, so the cost is one pass and not a duplicate.
                    consumed = 0
                }
                if (lines.size <= consumed) {
                    filesSkipped++
                    continue
                }
                filesRead++

                val fresh = lines.subList(consumed, lines.size)
                var lastFailed = false
                for ((offset, line) in fresh.withIndex()) {
                    val batch = SyncBatch.decode(line)
                    if (batch == null) {
                        if (line.isNotBlank()) linesUnreadable++
                        lastFailed = offset == fresh.size - 1 && line.isNotBlank()
                        continue
                    }
                    batches.add(batch)
                }
                advanced[key] = lines.size - if (lastFailed) 1 else 0
            }
        }

        val merge = HistoryMerge.merge(batches, known, ownDeviceId, now, keepDays)
        return Outcome(merge, filesRead, filesSkipped, filesUnavailable, linesUnreadable, advanced)
    }

    private companion object {

        const val DAY_MS = 24 * 60 * 60 * 1000L

        /** The directory history files live in, inside a device's own. */
        const val DIRECTORY = "history"

        /** `history/2026-11-05.jsonl` and `history/2026-11-05/0003.jsonl` alike. */
        val DAY_IN_PATH = Regex("""history/(\d{4}-\d{2}-\d{2})""")

        fun dayOf(atMs: Long): String {
            val (year, month, day) = HistoryWriter.utcDate(atMs)
            return "%04d-%02d-%02d".format(year, month, day)
        }

        /**
         * Whether a file is entirely older than [oldestDay] and so holds nothing the store would
         * keep. Day names sort as dates do, which is the one thing that makes this cheap: it is a
         * string comparison on the name and no file is opened. A path that does not carry a day is
         * read, because there is nothing to conclude from it.
         */
        fun isBefore(path: String, oldestDay: String): Boolean {
            val day = DAY_IN_PATH.find(path)?.groupValues?.get(1) ?: return false
            return day < oldestDay
        }
    }
}
