package music.ai.recommend.sync

/**
 * Reads the other devices' playlist and favourite changes out of the sync folder.
 *
 * No day is ever too old to open, and that is the difference from history worth naming. History is
 * a set of things that happened and the store keeps a year of it, so a file named for an older day
 * holds nothing it wants. This log *is* the playlists — the state is what falls out of replaying
 * it — so a change from two years ago is still load-bearing, and a device meeting the folder for
 * the first time has to read all of it.
 *
 * Which also means the caller keeps every change it reads, rather than only the state it computed:
 * an operation that arrives late belongs in the middle of the order, and only the log can put it
 * there. See [PlaylistReplay].
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
class PlaylistReader(
    private val ownDeviceId: String,
    private val source: SyncSource,
    private val progress: Map<String, Int>,
    /** What can open a sealed batch; see [Sealing]. */
    private val sealing: () -> Sealing? = { null }
) {

    data class Outcome(
        /** The changes read this pass, in no particular order; [PlaylistReplay] puts them in one. */
        val ops: List<PlaylistOp>,
        val filesRead: Int,
        val filesSkipped: Int,
        val filesUnavailable: Int,
        /** Lines that would not decode, the half-written tails and the unknown kinds among them. */
        val linesUnreadable: Int,
        /** Of those, the ones that are sealed and did not open; see [BatchReader.Outcome]. */
        val linesSealed: Int,
        /** True when the pass stopped at its limit and more is waiting. */
        val more: Boolean,
        /** How many lines of each file this pass consumed, for the caller to record afterwards. */
        val advanced: Map<String, Int>
    )

    fun read(maxRecords: Int = MAX_RECORDS): Outcome {
        val outcome = BatchReader(ownDeviceId, source, progress, sealing)
            .read(inside = PlaylistWriter.DIRECTORY, maxRecords = maxRecords)

        val ops = ArrayList<PlaylistOp>()
        var unreadable = outcome.linesUnreadable
        for (batch in outcome.batches) {
            // Own changes are skipped: this device's log already has them, with the same counters.
            // A batch is attributed by the name inside it rather than by the directory it sits in,
            // so that a device able to see two others can pass their files along.
            if (batch.deviceId == ownDeviceId) continue
            for (json in batch.records) {
                val op = PlaylistOp.fromJson(json)
                if (op == null) unreadable++ else ops.add(op)
            }
        }

        return Outcome(
            ops = ops,
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
         * How many changes one pass takes.
         *
         * Generous, because unlike vectors these are tiny — a change is a couple of hundred bytes —
         * and unlike history they cannot be partly applied: the state is the replay of the whole
         * log, so a device meeting a folder for the first time wants all of it rather than a tenth
         * of it in each of ten passes.
         */
        const val MAX_RECORDS = 20_000
    }
}
