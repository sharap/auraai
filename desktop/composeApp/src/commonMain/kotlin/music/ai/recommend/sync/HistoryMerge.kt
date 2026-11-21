package music.ai.recommend.sync

/**
 * Folding other devices' listening history into this one's.
 *
 * History is a set that only grows, so there is nothing to resolve and no order to agree on: the
 * merge is a union, and the whole of the difficulty is in deciding what not to let in. Three things
 * are turned away.
 *
 * A record already held, because the same file will be read again every time it grows. A record so
 * old that the local store would prune it on the next pass, which there is no point inserting. And
 * a record from the future, which is the one case that could do lasting harm: history never
 * forgets, the wave holds back whatever was played recently, and a device whose clock says 2031
 * would put its listens out of reach for years. A day of slack covers a clock that is merely
 * wrong, rather than wrong about the year.
 *
 * What this does not do is decide which local track a record is about. That needs the library, and
 * sometimes the vectors, so it belongs to the matcher; here a record keeps the [TrackKey] it
 * arrived with.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
object HistoryMerge {

    /** How far ahead of this device's clock another device's may be before it is disbelieved. */
    const val FUTURE_SLACK_MS = 24 * 60 * 60 * 1000L

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** A listen this device already holds. The pair is enough: see [Result]. */
    data class Known(val deviceId: String, val playedAt: Long)

    /** A listen to be taken in, and who heard it. */
    data class Incoming(val deviceId: String, val sequence: Long, val record: PlayRecord)

    /**
     * @param gaps sequence numbers missing between the lowest and highest batch seen from a
     *   device. A gap means a batch was lost or has not arrived; records missing from the *end*
     *   cannot be seen at all, and are indistinguishable from ones not yet synced.
     */
    data class Result(
        val accepted: List<Incoming>,
        val alreadyHeld: Int,
        val tooOld: Int,
        val fromTheFuture: Int,
        val unreadable: Int,
        val gaps: Map<String, List<Long>>
    )

    /**
     * @param ownDeviceId this device, whose own batches are skipped: it wrote them from the store
     *   that is being merged into.
     * @param keepDays how long the local store keeps history; zero or less keeps everything.
     */
    fun merge(
        batches: List<SyncBatch>,
        known: Set<Known>,
        ownDeviceId: String,
        now: Long,
        keepDays: Int
    ): Result {
        val accepted = ArrayList<Incoming>()
        val seen = HashSet(known)
        var alreadyHeld = 0
        var tooOld = 0
        var fromTheFuture = 0
        var unreadable = 0
        val oldest = if (keepDays > 0) now - keepDays * DAY_MS else Long.MIN_VALUE
        val latest = now + FUTURE_SLACK_MS

        // Oldest batch first, so that when the same listen somehow appears twice the earlier
        // telling of it wins and the result does not depend on the order files were read in.
        for (batch in batches.sortedWith(compareBy({ it.deviceId }, { it.sequence }))) {
            if (batch.deviceId == ownDeviceId) continue
            for (json in batch.records) {
                val record = PlayRecord.fromJson(json)
                if (record == null) {
                    unreadable++
                    continue
                }
                when {
                    record.playedAt <= 0L || record.playedAt > latest -> fromTheFuture++
                    record.playedAt < oldest -> tooOld++
                    !seen.add(Known(batch.deviceId, record.playedAt)) -> alreadyHeld++
                    else -> accepted.add(Incoming(batch.deviceId, batch.sequence, record))
                }
            }
        }
        return Result(accepted, alreadyHeld, tooOld, fromTheFuture, unreadable, gaps(batches, ownDeviceId))
    }

    private fun gaps(batches: List<SyncBatch>, ownDeviceId: String): Map<String, List<Long>> {
        val byDevice = batches.filter { it.deviceId != ownDeviceId }.groupBy { it.deviceId }
        val result = LinkedHashMap<String, List<Long>>()
        for ((deviceId, theirs) in byDevice) {
            val numbers = theirs.map { it.sequence }.toSortedSet()
            val missing = (numbers.first()..numbers.last()).filterNot { it in numbers }
            if (missing.isNotEmpty()) result[deviceId] = missing
        }
        return result
    }
}
