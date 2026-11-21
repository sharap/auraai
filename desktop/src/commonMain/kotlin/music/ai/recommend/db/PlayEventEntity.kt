package music.ai.recommend.db

/**
 * One finished listen as it is stored: which track, when, how far it got, how long it was.
 *
 * [deviceId] and [id] are what sync needs of it, and both have a reason to be stored rather than
 * worked out. Which device heard a listen has to be kept, or a listen taken in from the phone would
 * be sent back out as this machine's own and the phone would hold it twice under two names. And a
 * number that rises as records are appended is what lets the outbox say where it got to with one
 * value: a batch prepared but never written is simply offered again.
 */
data class PlayEventEntity(
    val path: String,
    val playedAt: Long,
    val playedMs: Long,
    val durationMs: Long,
    /** The device that heard it. Empty means this one, which is what makes it exportable. */
    val deviceId: String = "",
    /** Its place in the log, from one upwards. Zero for a listen not yet stored. */
    val id: Long = 0
)
