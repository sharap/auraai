package music.ai.recommend.sync

/**
 * The listening history as sync needs to see it, and nothing else.
 *
 * It exists so that [SyncCoordinator] — the one place where the order of operations *is* the
 * correctness — can be one piece of code on both platforms instead of two that have to be kept
 * agreeing. The two apps store history quite differently: a Room table of rows keyed by MediaStore
 * id here, an appended file keyed by path there. Neither difference reaches the ordering, so
 * neither belongs in it.
 *
 * [Track] is whatever this platform calls a track of its own. The coordinator never looks inside
 * one; it only carries it from the library to the log, which is all it needs to do.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
interface HistoryStore<Track> {

    /**
     * This device's own listens past [afterId], oldest first and at most [limit] of them.
     *
     * Only its own: a listen taken in from elsewhere must never go back out, or a third device
     * would see it twice under two names and nothing would tell them apart again.
     */
    suspend fun own(afterId: Long, limit: Int): List<OwnListen<Track>>

    /**
     * Stores listens heard on [deviceId] and already matched to tracks here.
     *
     * @return how many were stored, which is what the caller reports. Zero for a failure, and the
     *   coordinator then leaves its progress alone so the batch is met again.
     */
    suspend fun takeIn(deviceId: String, listens: List<TakenListen<Track>>): Int

    /**
     * What this device already holds from other devices, as device and instant — what the merge
     * turns away, so that reading the same file twice costs nothing.
     */
    suspend fun heldFromOthers(now: Long): Set<HistoryMerge.Known>

    /**
     * This library, each track described the way another device will hear about it.
     *
     * Both directions need it — the outbox to name what was played, the matcher to recognise what
     * someone else played — so it is asked for once per pass and handed to both.
     */
    suspend fun library(): Map<Track, TrackKey>

    /**
     * How a track of this platform is written into the state file, and read back out of it.
     *
     * The state file keeps the hashes the matcher worked out the hard way, so that work is done
     * once — and a hash is only useful next to a local track, which only the platform can spell: a
     * number here, a path there. [fromText] returns null for text this platform cannot read, which
     * a state file written by some other version may well hold.
     */
    fun asText(track: Track): String

    fun fromText(text: String): Track?
}

/** One of this device's own listens, with the number that says how far the outbox has got. */
data class OwnListen<Track>(
    val rowId: Long,
    val track: Track,
    val playedAt: Long,
    val playedMs: Long,
    val durationMs: Long
)

/** A listen from somewhere else, once it has been matched to a track in this library. */
data class TakenListen<Track>(
    val track: Track,
    val playedAt: Long,
    val playedMs: Long,
    val durationMs: Long
)
