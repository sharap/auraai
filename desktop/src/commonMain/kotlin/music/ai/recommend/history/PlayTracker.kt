package music.ai.recommend.history

/**
 * Turns player callbacks into finished listens.
 *
 * The player reports positions, not listens: a track can be paused, resumed, seeked, skipped or
 * left playing when the app is killed. This keeps the furthest position reached for the current
 * track and emits one event when the track is left, which is what tells a real listen apart from
 * a skip. Pauses and repeats do not add up, so the fraction stays within 0..1.
 *
 * Pure logic, no Android types, so it is covered by unit tests.
 */
/** One finished listen. [fraction] is how much of the track was reached, 0..1. */
data class PlayEvent(
    val songPath: String,
    val playedAt: Long,
    val playedMs: Long,
    val durationMs: Long
) {
    val fraction: Float
        get() = if (durationMs <= 0) 0f else (playedMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

class PlayTracker(private val emit: (PlayEvent) -> Unit) {

    private var songPath: String? = null
    private var durationMs: Long = 0
    private var furthestMs: Long = 0

    /** A track became the current one. Any previous one is closed first. */
    fun started(songPath: String, durationMs: Long, now: Long) {
        finished(now)
        this.songPath = songPath
        this.durationMs = durationMs
        furthestMs = 0
    }

    /** The player reported a position for the current track, or a length it did not know before. */
    fun progress(positionMs: Long, durationMs: Long = this.durationMs) {
        if (songPath == null) return
        if (durationMs > 0) this.durationMs = durationMs
        if (positionMs > furthestMs) furthestMs = positionMs
    }

    /** The current track was left: skipped, ended, paused for good, or the service is stopping. */
    fun finished(now: Long) {
        val path = songPath ?: return
        songPath = null
        // A track that barely started is usually the player settling, not a listen. A deliberate
        // skip still counts: it is a negative signal, and it is worth knowing about.
        if (furthestMs < MIN_LISTEN_MS) return
        emit(PlayEvent(path, now, furthestMs, durationMs))
    }

    private companion object {
        const val MIN_LISTEN_MS = 3_000L
    }
}
