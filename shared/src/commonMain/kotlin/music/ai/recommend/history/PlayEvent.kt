package music.ai.recommend.history

/**
 * One finished listen: a track was left, and this is what the player had reached.
 *
 * [track] is whatever the port identifies tracks by — a MediaStore id on the phone, a path on the
 * desktop. A type parameter because both are right and because the alternative was tried: this
 * class existed twice, identical but for that one field, and so did [PlayTracker], which produces
 * it. The same answer as the sync layer's, down to the name.
 *
 * Not the storage type. Each port has its own `PlayEventEntity` for that; this is the listen as the
 * app thinks of it, which is why it can be shared at all.
 */
data class PlayEvent<Track>(
    val track: Track,
    val playedAt: Long,
    val playedMs: Long,
    val durationMs: Long
) {
    /** How much of the track was reached, 0..1. Pauses and repeats do not add up. */
    val fraction: Float
        get() = if (durationMs <= 0) 0f else (playedMs.toFloat() / durationMs).coerceIn(0f, 1f)
}
