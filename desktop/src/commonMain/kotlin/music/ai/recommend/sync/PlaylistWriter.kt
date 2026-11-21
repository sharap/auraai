package music.ai.recommend.sync

/**
 * The outbox for playlist and favourite changes.
 *
 * No timer, unlike history's. A listen is worth most while it is fresh, so that outbox waits half
 * an hour at most; a playlist change is a thing the owner did on purpose and expects to see on the
 * other device, and it goes out with the next pass — playback stopping, the app coming back to the
 * front, the end of an analysis — which is soon enough and costs nothing extra, since those passes
 * were going to touch the folder anyway.
 *
 * Twenty to a batch, which for changes of two hundred bytes is a line of four kilobytes. Organising
 * an evening's playlists is a burst of a few dozen, and the burst goes out as one or two lines.
 *
 * Not thread-safe: the caller is expected to offer and flush from one place.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
class PlaylistWriter(
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

    fun offer(op: PlaylistOp) {
        out.offer(op.toJson())
    }

    /** Whether [flush] would write anything if called now. */
    fun isDue(): Boolean = out.isDue(0L)

    /**
     * @param force writes a part-full batch, which is what every pass asks for: a change the owner
     *   made should not wait for nineteen more to keep it company.
     * @return how many changes went out.
     */
    fun flush(now: Long, force: Boolean = false): Int = out.flush(now, force)

    companion object {

        const val BATCH_RECORDS = 20

        /** The directory one device's playlist changes live in, relative to its own. */
        const val DIRECTORY = "playlists"

        /** Which file a batch written at [atMs] belongs in; see [BatchWriter.dayFile]. */
        fun dayFile(atMs: Long): String = BatchWriter.dayFile(DIRECTORY, atMs)
    }
}
