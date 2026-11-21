package music.ai.recommend.sync

import com.google.gson.JsonObject

/**
 * The outbox: records waiting to be told to the other devices, and the rule for when it is worth
 * touching the folder.
 *
 * Everything the three kinds of record share lives here. Chiefly one rule that is easy to get
 * wrong and expensive to get wrong: the batch number is spent only once the write has landed. A
 * number spent on a write that failed leaves a hole in the file, and a hole is how the other side
 * tells a lost batch from a reboot — so it would report a loss that never happened, for ever.
 *
 * What differs between the three is policy, and policy is what the constructor takes. History
 * waits [intervalMs] because a listen is worth most while it is fresh and a phone pays for every
 * change to the folder with a radio wake-up. Vectors and playlist changes have no timer: a vector
 * is worth the same tomorrow, and a playlist change goes out with the next pass anyway.
 *
 * Not thread-safe: the caller is expected to offer and flush from one place.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
class BatchWriter(
    private val deviceId: String,
    private val sink: SyncSink,
    /** `history`, `vectors` or `playlists`. */
    private val directory: String,
    /** Records that make a batch worth writing on their own. */
    private val batchRecords: Int,
    /** How long a part-full batch waits, or zero for no timer. */
    private val intervalMs: Long = 0L,
    /**
     * What seals a batch, asked for each one: the owner can set or change the phrase while the
     * app is running, and the next batch should be written under whatever is true then.
     */
    private val sealing: () -> Sealing? = { null },
    /** The batch number to carry on from, so numbering survives a restart. */
    startingSequence: Long = 0L
) {

    private val waiting = ArrayList<JsonObject>()
    private var lastFlushAt = 0L

    /** The last batch number written. */
    var sequence: Long = startingSequence
        private set

    val pending: Int get() = waiting.size

    fun offer(record: JsonObject) {
        waiting.add(record)
    }

    /**
     * Whether [flush] would write anything if called now.
     *
     * With a timer, the first flush of a run is due as soon as anything is waiting: [lastFlushAt]
     * starts at zero and the interval has therefore always passed. That is deliberate — a device
     * coming back after being away should say so promptly rather than hold the first batch.
     */
    fun isDue(now: Long): Boolean = when {
        waiting.isEmpty() -> false
        waiting.size >= batchRecords -> true
        else -> intervalMs > 0 && now - lastFlushAt >= intervalMs
    }

    /**
     * Writes what is waiting, if it is time.
     *
     * @param force writes a part-full batch, which is what playback stopping, the app going away
     *   and the end of an analysis ask for.
     * @return how many records went out. Zero means nothing was waiting, or it was not yet time,
     *   or the folder would not take them — in the last case they stay and are offered again, so
     *   an unreachable folder costs a delay and not a record.
     */
    fun flush(now: Long, force: Boolean = false): Int {
        if (waiting.isEmpty()) return 0
        if (!force && !isDue(now)) return 0

        val taking = if (force) waiting.size else minOf(waiting.size, batchRecords)
        val seal = sealing()
        val batch = SyncBatch(
            deviceId = deviceId,
            sequence = sequence + 1,
            sealing = seal?.name ?: SyncBatch.SEALING_NONE,
            records = waiting.subList(0, taking).toList()
        )
        // A batch that could not be sealed is not written in the clear: the records stay and are
        // offered again, exactly as when the folder itself will not take them.
        val line = batch.encode(seal) ?: return 0
        if (!sink.append(dayFile(directory, now), listOf(line))) return 0

        sequence = batch.sequence
        lastFlushAt = now
        waiting.subList(0, taking).clear()
        return taking
    }

    companion object {

        /**
         * Which file a batch written at [atMs] belongs in.
         *
         * Named for the day of the *writing*, not of whatever is inside, so that a file is appended
         * to and then finished: yesterday's is never touched again, Syncthing stops carrying it,
         * and a reader can tell from the name alone that it has read all of it. A listen flushed
         * just after midnight therefore sits in the new day's file, which no reader minds — the
         * name is a bound on when records arrived, not a claim about what is inside.
         *
         * In UTC, because a name has to be stable: local time moves with travel and with daylight
         * saving, and a folder whose file names go backwards is a folder that needs explaining.
         */
        fun dayFile(directory: String, atMs: Long): String {
            val (year, month, day) = utcDate(atMs)
            return "$directory/%04d-%02d-%02d.jsonl".format(year, month, day)
        }

        /**
         * The civil date, in UTC, of an instant in epoch milliseconds.
         *
         * Done by hand rather than with `java.time`, which needs API 26 against this app's 23, and
         * rather than with `Calendar`, which would drag a time zone and a locale into a file name.
         * This is Howard Hinnant's `civil_from_days`, which is exact for every date either app
         * will ever see.
         */
        fun utcDate(atMs: Long): Triple<Int, Int, Int> {
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
