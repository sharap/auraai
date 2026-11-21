package music.ai.recommend.history

/**
 * Turns player callbacks into finished listens.
 *
 * The player reports positions, not listens: a track can be paused, resumed, seeked, skipped or
 * left playing when the app is killed. This keeps the furthest position reached for the current
 * track and emits one event when the track is left, which is what tells a real listen apart from
 * a skip. Pauses and repeats do not add up, so the fraction stays within 0..1.
 *
 * Pure logic and no platform types, which is what let it be shared: the only thing it knew
 * about a track was its identity, and that is now a type parameter.
 */
class PlayTracker<Track>(private val emit: (PlayEvent<Track>) -> Unit) {

    private var track: Track? = null
    private var durationMs: Long = 0
    private var furthestMs: Long = 0

    /** A track became the current one. Any previous one is closed first. */
    fun started(track: Track, durationMs: Long, now: Long) {
        finished(now)
        this.track = track
        this.durationMs = durationMs
        furthestMs = 0
    }

    /** The player reported a position for the current track, or a length it did not know before. */
    fun progress(positionMs: Long, durationMs: Long = this.durationMs) {
        if (track == null) return
        if (durationMs > 0) this.durationMs = durationMs
        if (positionMs > furthestMs) furthestMs = positionMs
    }

    /**
     * The current track was left: skipped, ended, paused for good, or the service is stopping.
     *
     * Every departure is reported, however short. A track dropped after a second is the clearest
     * "not this one" there is, and the wave steers on these events; it is [PlayHistory] that
     * decides what is long enough to be worth keeping as a listen.
     */
    fun finished(now: Long) {
        val left = track ?: return
        track = null
        emit(PlayEvent(left, now, furthestMs, durationMs))
    }
}
