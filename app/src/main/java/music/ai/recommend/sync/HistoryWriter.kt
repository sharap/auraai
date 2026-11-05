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
 * The desktop port carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
class HistoryWriter(
    private val deviceId: String,
    private val sink: SyncSink,
    /** The batch number to carry on from, so numbering survives a restart and a gap means one. */
    startingSequence: Long = 0L
) {

    private val waiting = ArrayList<PlayRecord>()
    private var lastFlushAt = 0L

    /** The last batch number written. */
    var sequence: Long = startingSequence
        private set

    val pending: Int get() = waiting.size

    /** Takes a finished listen into the outbox. It goes out on the next flush that is due. */
    fun offer(record: PlayRecord) {
        waiting.add(record)
    }

    /**
     * Whether [flush] would write anything if called now.
     *
     * The first flush of a run is due as soon as anything is waiting: [lastFlushAt] starts at zero
     * and the interval has therefore always passed. That is deliberate — a device coming back after
     * being away should say so promptly rather than hold the first batch for a quarter of an hour.
     */
    fun isDue(now: Long): Boolean =
        waiting.isNotEmpty() && (waiting.size >= BATCH_RECORDS || now - lastFlushAt >= BATCH_INTERVAL_MS)

    /**
     * Writes what is waiting, if it is time.
     *
     * @param force writes regardless, which is what playback stopping and the app going to the
     *   background ask for: those are the moments when the next flush might be a long way off.
     * @return how many records went out. Zero means either nothing was waiting, or it was not yet
     *   time, or the folder would not take them — in the last case they stay in the outbox and are
     *   offered again, so an unreachable folder costs a delay and not a record.
     */
    fun flush(now: Long, force: Boolean = false): Int {
        if (waiting.isEmpty()) return 0
        if (!force && !isDue(now)) return 0

        val batch = SyncBatch(
            deviceId = deviceId,
            sequence = sequence + 1,
            sealing = SyncBatch.SEALING_NONE,
            records = waiting.map { it.toJson() }
        )
        if (!sink.append(dayFile(now), listOf(batch.encode()))) return 0

        sequence = batch.sequence
        lastFlushAt = now
        val written = waiting.size
        waiting.clear()
        return written
    }

    companion object {

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

        /**
         * Which file a batch written at [atMs] belongs in.
         *
         * Named for the day of the *writing*, not of the listening, so that a file is appended to
         * and then finished: yesterday's is never touched again, Syncthing stops carrying it, and a
         * reader can tell from the name alone that it has read all of it. A batch flushed just
         * after midnight therefore holds a few records from the day before, which no reader minds
         * — the name is a bound on when records arrived, not a claim about what is inside.
         *
         * In UTC, because a name has to be stable: local time moves with travel and with daylight
         * saving, and a folder whose file names go backwards is a folder that needs explaining.
         */
        fun dayFile(atMs: Long): String {
            val (year, month, day) = utcDate(atMs)
            return "history/%04d-%02d-%02d.jsonl".format(year, month, day)
        }

        /**
         * The civil date, in UTC, of an instant in epoch milliseconds.
         *
         * Done by hand rather than with `java.time`, which needs API 26 against this app's 23, and
         * rather than with `Calendar`, which would drag a time zone and a locale into a file name.
         * This is Howard Hinnant's `civil_from_days`, which is exact for every date either app
         * will ever see.
         */
        internal fun utcDate(atMs: Long): Triple<Int, Int, Int> {
            val days = Math.floorDiv(atMs, 86_400_000L)
            val shifted = days + 719_468L
            val era = Math.floorDiv(shifted, 146_097L)
            val dayOfEra = shifted - era * 146_097L
            val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
            val year = yearOfEra + era * 400
            val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
            val monthPrime = (5 * dayOfYear + 2) / 153
            val day = dayOfYear - (153 * monthPrime + 2) / 5 + 1
            val month = if (monthPrime < 10) monthPrime + 3 else monthPrime - 9
            return Triple((if (month <= 2) year + 1 else year).toInt(), month.toInt(), day.toInt())
        }
    }
}
