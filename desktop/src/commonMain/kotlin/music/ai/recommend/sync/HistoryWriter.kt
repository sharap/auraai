package music.ai.recommend.sync

/**
 * The outbox: listens waiting to be told to the other device.
 *
 * It is an outbox and not a journal, which is the whole reason it can afford to be lazy. Every
 * record offered here is already in this device's own database, so a phone that dies with twenty
 * records pending has lost its outbox, not its history — they go out on the next flush. Write
 * frequency therefore buys freshness on the other device and nothing else, and half an hour behind
 * costs nothing that anyone can hear.
 *
 * What it buys in return is power. Syncthing watches the folder and starts an exchange within about
 * ten seconds of a change, and on a phone that is a radio wake-up, which costs disproportionately:
 * the modem stays in a high-power state for seconds after each transmission. So batches, not a
 * stream — [BATCH_RECORDS] records or [BATCH_INTERVAL_MS], whichever comes first. With tracks of a
 * few minutes it is always the interval that gets there first, so that is the real knob: half an
 * hour of listening per write, which is a couple of writes an hour and a dozen on a heavy day. The
 * record count is there for the bursts, where a run of skips fills a batch in a minute.
 *
 * Not thread-safe: the caller is expected to offer and flush from one place.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
class HistoryWriter(
    deviceId: String,
    sink: SyncSink,
    /** The batch number to carry on from, so numbering survives a restart and a gap means one. */
    startingSequence: Long = 0L,
    /** What seals a batch; see [Sealing]. */
    sealing: () -> Sealing? = { null }
) {

    private val out = BatchWriter(
        deviceId = deviceId,
        sink = sink,
        directory = DIRECTORY,
        batchRecords = BATCH_RECORDS,
        intervalMs = BATCH_INTERVAL_MS,
        startingSequence = startingSequence,
        sealing = sealing
    )

    /** The last batch number written. */
    val sequence: Long get() = out.sequence

    val pending: Int get() = out.pending

    /** Takes a finished listen into the outbox. It goes out on the next flush that is due. */
    fun offer(record: PlayRecord) {
        out.offer(record.toJson())
    }

    fun isDue(now: Long): Boolean = out.isDue(now)

    /**
     * Writes what is waiting, if it is time.
     *
     * @param force writes regardless, which is what playback stopping and the app going to the
     *   background ask for: those are the moments when the next flush might be a long way off.
     * @return how many records went out.
     */
    fun flush(now: Long, force: Boolean = false): Int = out.flush(now, force)

    companion object {

        /** The directory history files live in, inside a device's own. */
        const val DIRECTORY = "history"

        /** Records that make a batch worth writing on their own. */
        const val BATCH_RECORDS = 20

        /**
         * How long a part-full batch waits.
         *
         * Half an hour, which is the staleness the other device was judged able to live with: the
         * wave only reads history for how many days ago a track was played. And it is rarely waited
         * out in full anyway, because playback stopping and the app going to the background both
         * flush at once — the end of a listening session is reported when it ends.
         */
        const val BATCH_INTERVAL_MS = 30 * 60 * 1000L

        /** Which file a batch written at [atMs] belongs in; see [BatchWriter.dayFile]. */
        fun dayFile(atMs: Long): String = BatchWriter.dayFile(DIRECTORY, atMs)

        /** The civil date, in UTC; see [BatchWriter.utcDate]. */
        internal fun utcDate(atMs: Long): Triple<Int, Int, Int> = BatchWriter.utcDate(atMs)
    }
}
