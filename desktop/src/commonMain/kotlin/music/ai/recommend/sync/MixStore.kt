package music.ai.recommend.sync

/**
 * The playlist of the day as [SyncCoordinator] needs to see it.
 *
 * Three questions, because only the platform can answer them: what day it is where the owner is,
 * what this device has built for that day, and here is one built elsewhere — take it.
 *
 * The Android app carries a copy of this file. The two must match: see docs/SYNC-FORMAT.md.
 */
interface MixStore<Track> {

    /**
     * The day the mix is for, by this device's own calendar.
     *
     * Asked of the platform rather than worked out here, because the mix turns over at the
     * owner's midnight and only the platform knows when that is. Everything else in the folder is
     * named in UTC; this one thing is not, and [MixRecord] says why.
     */
    suspend fun today(): String

    /** What this device holds for [today], or null when it has not built one. */
    suspend fun current(): Offered<Track>?

    /**
     * Takes a mix built on another device as this device's own for that day.
     *
     * The tracks are already this library's: a track the mix names and this device does not have
     * is simply not in the list. Which is also why a mix that arrives mostly unmatched is still
     * worth taking — the alternative is two devices showing two lists called today.
     */
    suspend fun adopt(day: String, attempt: Int, from: String, tracks: List<Track>)

    /**
     * @param from which device built it, or null when this one did. Only a mix built here is
     *   published: a device that re-published what it adopted would be claiming it, and the two
     *   would then take turns winning the day on the strength of their names.
     */
    data class Offered<Track>(
        val day: String,
        val attempt: Int,
        val from: String?,
        val tracks: List<Track>
    )
}
