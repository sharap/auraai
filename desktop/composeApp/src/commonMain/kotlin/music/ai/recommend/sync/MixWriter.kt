package music.ai.recommend.sync

/**
 * The outbox for the playlist of the day.
 *
 * One record to a batch, because a mix is one record and the next one for the same day replaces
 * it: there is nothing to be gained by waiting for company, and a file of one complete line is
 * the easiest thing in this folder for the other device to read while it is being written.
 *
 * Not thread-safe: the caller is expected to offer and flush from one place.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
class MixWriter(
    deviceId: String,
    sink: SyncSink,
    /** The batch number to carry on from, so numbering survives a restart. */
    startingSequence: Long = 0L,
    /** What seals a batch; see [Sealing]. */
    sealing: () -> Sealing? = { null }
) {

    private val out = BatchWriter(
        deviceId = deviceId,
        sink = sink,
        directory = DIRECTORY,
        batchRecords = BATCH_RECORDS,
        startingSequence = startingSequence,
        sealing = sealing
    )

    /** The last batch number written. */
    val sequence: Long get() = out.sequence

    val pending: Int get() = out.pending

    fun offer(record: MixRecord) {
        out.offer(record.toJson())
    }

    /** @return how many mixes went out, which is at most one. */
    fun flush(now: Long, force: Boolean = true): Int = out.flush(now, force)

    companion object {

        const val BATCH_RECORDS = 1

        /** The directory one device's mixes live in, relative to its own. */
        const val DIRECTORY = "mix"

        /** Which file a batch written at [atMs] belongs in; see [BatchWriter.dayFile]. */
        fun dayFile(atMs: Long): String = BatchWriter.dayFile(DIRECTORY, atMs)
    }
}
