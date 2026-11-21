package music.ai.recommend.sync

/**
 * The outbox for embeddings.
 *
 * A sibling of [HistoryWriter] with one policy difference, and the difference is the whole of why
 * this is a separate class. A listen is worth most when it is fresh, so that outbox waits half an
 * hour at most and flushes the moment a session ends. A vector is worth exactly the same tomorrow:
 * it describes a file, not a moment. So there is no timer here at all — a batch goes out when it is
 * full, and whatever is left goes out when the caller says the run is over.
 *
 * [BATCH_RECORDS] is fifty because a record is about 2.8 KB on the wire, which makes a line of
 * about 137 KB: a little over Syncthing's block size, so an append moves one or two blocks rather
 * than rewriting a file that will reach eleven megabytes for a four thousand track library.
 *
 * Not thread-safe: the caller is expected to offer and flush from one place.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
class VectorWriter(
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

    /**
     * Takes a vector into the outbox, if it is one that may travel at all.
     *
     * The decoder is checked here and not only by the receiver. A vector the other side is certain
     * to refuse is still megabytes of somebody's folder and minutes of somebody's radio, and for a
     * library of four thousand tracks mostly in formats without a shared decoder that is nearly all
     * of it.
     *
     * @return true when it was taken.
     */
    fun offer(record: VectorRecord): Boolean {
        if (record.decoder !in VectorTerms.TRAVELS) return false
        if (record.vector.isEmpty() || record.vector.all { it == 0f }) return false
        out.offer(record.toJson())
        return true
    }

    /** Whether [flush] would write anything if called now. */
    fun isDue(): Boolean = out.isDue(0L)

    /**
     * Writes what is waiting, if there is enough of it.
     *
     * @param force writes a part-full batch, which is what the end of a scan and the app going away
     *   ask for.
     * @return how many records went out.
     */
    fun flush(now: Long, force: Boolean = false): Int = out.flush(now, force)

    companion object {

        /** Records in one batch; see the note on line size above. */
        const val BATCH_RECORDS = 50

        /** The directory one device's vector files live in, relative to its own. */
        const val DIRECTORY = "vectors"

        /** Which file a batch written at [atMs] belongs in; see [BatchWriter.dayFile]. */
        fun dayFile(atMs: Long): String = BatchWriter.dayFile(DIRECTORY, atMs)
    }
}
