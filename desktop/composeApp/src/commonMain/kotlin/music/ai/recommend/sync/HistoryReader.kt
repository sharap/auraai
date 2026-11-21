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
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
class HistoryReader(
    private val ownDeviceId: String,
    private val source: SyncSource,
    private val progress: MutableMap<String, Int>,
    /** What can open a sealed batch; see [Sealing]. */
    private val sealing: () -> Sealing? = { null }
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
        /** Of those, the ones that are sealed and did not open; see [BatchReader.Outcome]. */
        val linesSealed: Int,
        /** How many lines of each file this pass consumed, for the caller to record afterwards. */
        val advanced: Map<String, Int>
    )

    /**
     * @param known what this device already holds, so the merge can turn it away.
     * @param keepDays how long the local store keeps history; zero or less keeps everything.
     */
    fun read(known: Set<HistoryMerge.Known>, now: Long, keepDays: Int): Outcome {
        // The walking — which files are ours, how far each has been read, what to do with a tail
        // that is half-written — is the same for every kind of file in the folder and lives in
        // [BatchReader]. What is particular to history is the day it stops caring about and the
        // merge it hands the batches to.
        val outcome = BatchReader(ownDeviceId, source, progress, sealing).read(
            inside = DIRECTORY,
            oldestDay = if (keepDays > 0) dayOf(now - keepDays * DAY_MS) else null
        )
        val merge = HistoryMerge.merge(outcome.batches, known, ownDeviceId, now, keepDays)
        return Outcome(
            merge = merge,
            filesRead = outcome.filesRead,
            filesSkipped = outcome.filesSkipped,
            filesUnavailable = outcome.filesUnavailable,
            linesUnreadable = outcome.linesUnreadable,
            linesSealed = outcome.linesSealed,
            advanced = outcome.advanced
        )
    }

    private companion object {

        const val DAY_MS = 24 * 60 * 60 * 1000L

        /** The directory history files live in, inside a device's own. */
        const val DIRECTORY = "history"

        fun dayOf(atMs: Long): String {
            val (year, month, day) = HistoryWriter.utcDate(atMs)
            return "%04d-%02d-%02d".format(year, month, day)
        }
    }
}
