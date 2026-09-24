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
class PlayTracker(private val emit: (PlayEvent) -> Unit) {

    private var songId: Long? = null
    private var durationMs: Long = 0
    private var furthestMs: Long = 0

    /** A track became the current one. Any previous one is closed first. */
    fun started(songId: Long, durationMs: Long, now: Long) {
        finished(now)
        this.songId = songId
        this.durationMs = durationMs
        furthestMs = 0
    }

    /** The player reported a position for the current track, or a length it did not know before. */
    fun progress(positionMs: Long, durationMs: Long = this.durationMs) {
        if (songId == null) return
        if (durationMs > 0) this.durationMs = durationMs
        if (positionMs > furthestMs) furthestMs = positionMs
    }

    /** The current track was left: skipped, ended, paused for good, or the service is stopping. */
    fun finished(now: Long) {
        val id = songId ?: return
        songId = null
        // A track that barely started is usually the player settling, not a listen. A deliberate
        // skip still counts: it is a negative signal, and it is worth knowing about.
        if (furthestMs < MIN_LISTEN_MS) return
        emit(PlayEvent(id, now, furthestMs, durationMs))
    }

    private companion object {
        const val MIN_LISTEN_MS = 3_000L
    }
}
