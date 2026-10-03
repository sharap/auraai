package music.ai.recommend.ai

import kotlin.math.exp
import kotlin.random.Random

/**
 * Where a wave is steering, and what it has already done this session.
 *
 * Immutable: every reaction produces a new state, so the stream can be reasoned about and tested
 * step by step instead of through a mutable object that playback keeps poking at.
 */
data class WaveState(
    /** Unit vector the stream is heading towards. */
    val direction: FloatArray,
    /**
     * What has been rejected, as a unit vector, or null while nothing has been.
     *
     * Rotating the direction away from a skip is not enough on its own: a track sitting exactly
     * where the stream is pointing is mostly parallel to the direction, so subtracting it shortens
     * the vector without turning it, and normalising undoes even that. Holding the rejected
     * direction separately and scoring against it is what actually gets the stream out of a
     * neighbourhood the listener keeps skipping.
     */
    val avoid: FloatArray? = null,
    /** Played in this session, so the stream does not circle back. */
    val played: Set<Long> = emptySet(),
    /** Artists of the last few tracks, to keep one act from taking over. */
    val recentGroups: List<String> = emptyList(),
    val steps: Int = 0
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/**
 * An endless stream of music that steers itself from what the listener does.
 *
 * The difference from [DailyMix] is the loop: a daily playlist is chosen once, while a wave decides
 * after every track. A track heard to the end pulls the direction towards itself, a skipped one
 * pushes it away, and the next choice is made from the new direction — so the stream drifts towards
 * whatever is working right now instead of averaging a whole history.
 *
 * This is relevance feedback, the old Rocchio idea: no training, no model to fit, and the reaction
 * is immediate, which is the whole point of a wave.
 *
 * Pure Kotlin, no Android dependencies.
 */
object Wave {

    /** How far a finished track pulls the direction towards itself. */
    private const val FINISHED_PULL = 0.15f

    /** A skip pushes away more gently than a finish pulls: skipping is ambiguous — "not now" is
     * not "not ever" — while sitting through a whole track rarely is. */
    private const val SKIPPED_PUSH = 0.10f

    /** An explicit "don't like" is unambiguous, so it moves the direction harder. */
    private const val DISLIKE_PUSH = 0.30f

    /** How much being close to what was rejected counts against a candidate. */
    private const val AVOID_WEIGHT = 1.1f

    /** Fraction of a track below which it counts as skipped, and above which as heard. */
    private const val SKIPPED_BELOW = 0.3f
    private const val FINISHED_ABOVE = 0.8f

    /** How much the next track is chosen to follow the current one rather than the direction. A
     * stream that only followed the direction would jump from a quiet guitar straight into big
     * room, which is jarring even when both fit the taste. */
    private const val CONTINUITY = 0.25f

    /** Candidates considered for each pick; the choice is sampled among them, not maximised over
     * them — always taking the best track locks the stream into one corner of the library and it
     * starts repeating within the hour. */
    private const val SHORTLIST = 40

    /** Spread of the sampling. Higher means more surprising, lower means more predictable. */
    private const val TEMPERATURE = 0.04f

    /** An artist cannot come back within this many tracks. */
    private const val GROUP_COOLDOWN = 5

    /** Played more recently than this, a track is held back unless the stream runs dry. */
    private const val RECENT_DAYS = 14

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Starts a stream heading in [seed]'s direction. */
    fun start(seed: FloatArray): WaveState = WaveState(direction = normalize(seed.copyOf()))

    /**
     * Steers the stream after a track was left.
     *
     * @param fraction how much of the track was heard, 0..1.
     * @param disliked the listener pressed "don't like" rather than simply skipping.
     */
    fun react(state: WaveState, track: MixTrack, fraction: Float, disliked: Boolean = false): WaveState {
        val step = when {
            disliked -> -DISLIKE_PUSH
            fraction >= FINISHED_ABOVE -> FINISHED_PULL
            fraction < SKIPPED_BELOW -> -SKIPPED_PUSH
            // Heard most of the way through: neither a verdict for nor against.
            else -> 0f
        }
        val direction = if (step == 0f) state.direction else {
            normalize(FloatArray(state.direction.size) { i ->
                state.direction[i] + step * track.vector.getOrElse(i) { 0f }
            })
        }
        val avoid = if (step >= 0f) state.avoid else {
            val weight = -step
            val previous = state.avoid
            normalize(FloatArray(track.vector.size) { i ->
                (previous?.getOrElse(i) { 0f } ?: 0f) + weight * track.vector[i]
            })
        }
        return state.copy(
            direction = direction,
            avoid = avoid,
            played = state.played + track.id,
            recentGroups = (state.recentGroups + track.group).takeLast(GROUP_COOLDOWN),
            steps = state.steps + 1
        )
    }

    /**
     * The next [count] tracks of the stream.
     *
     * @param current the track playing now, if any: the stream is also chosen to flow on from it.
     * @param excluded tracks never to play here, such as the ones marked "don't like".
     * @return the chosen tracks in order, and the state as if they had been queued — so a caller
     *   that queues several at once does not get the same track twice.
     */
    fun next(
        state: WaveState,
        tracks: List<MixTrack>,
        count: Int,
        current: MixTrack? = null,
        excluded: Set<Long> = emptySet(),
        random: Random = Random.Default,
        now: Long = System.currentTimeMillis()
    ): Pair<List<MixTrack>, WaveState> {
        val chosen = ArrayList<MixTrack>(count)
        var walking = state
        var previous = current
        repeat(count) {
            val pick = pickOne(walking, tracks, previous, excluded, random, now) ?: return@repeat
            chosen += pick
            // Queued tracks count as played straight away: the wave looks several tracks ahead,
            // and without this the same track would be picked for every slot of the lookahead.
            walking = walking.copy(
                played = walking.played + pick.id,
                recentGroups = (walking.recentGroups + pick.group).takeLast(GROUP_COOLDOWN)
            )
            previous = pick
        }
        return chosen to walking
    }

    private fun pickOne(
        state: WaveState,
        tracks: List<MixTrack>,
        current: MixTrack?,
        excluded: Set<Long>,
        random: Random,
        now: Long
    ): MixTrack? {
        // Relaxed in turn when nothing is left: a wave that stops is a broken wave, and a repeat
        // after an hour is a smaller fault than silence.
        for (relaxation in 0..3) {
            val candidates = tracks.filter { track ->
                if (track.id in excluded) return@filter false
                if (track.id in state.played) return@filter false
                if (relaxation < 1 && track.group in state.recentGroups) return@filter false
                if (relaxation < 2 && track.lastPlayedAt != null &&
                    now - track.lastPlayedAt < RECENT_DAYS * DAY_MS
                ) return@filter false
                true
            }.ifEmpty {
                if (relaxation < 3) return@ifEmpty emptyList()
                // Last resort: everything but what the listener rejected outright.
                tracks.filter { it.id !in excluded }
            }
            if (candidates.isEmpty()) continue

            val scored = candidates.map { it to score(it, state, current) }
                .sortedByDescending { it.second }
                .take(SHORTLIST)
            return sample(scored, random)
        }
        return null
    }

    private fun score(track: MixTrack, state: WaveState, current: MixTrack?): Float {
        val towardsDirection = dot(state.direction, track.vector)
        val followsOn = current?.let { dot(it.vector, track.vector) } ?: towardsDirection
        val rejected = state.avoid?.let { dot(it, track.vector) } ?: 0f
        return (1 - CONTINUITY) * towardsDirection + CONTINUITY * followsOn - AVOID_WEIGHT * rejected
    }

    /** Draws one candidate with a probability that grows with its score. */
    private fun sample(scored: List<Pair<MixTrack, Float>>, random: Random): MixTrack {
        val best = scored.first().second
        val weights = scored.map { exp(((it.second - best) / TEMPERATURE).toDouble()) }
        var cut = random.nextDouble() * weights.sum()
        for ((i, weight) in weights.withIndex()) {
            cut -= weight
            if (cut <= 0) return scored[i].first
        }
        return scored.last().first
    }

    /** Turns a listen into the fraction the wave reacts to. */
    fun fractionOf(playedMs: Long, durationMs: Long): Float =
        if (durationMs <= 0) 0f else (playedMs.toFloat() / durationMs).coerceIn(0f, 1f)

    internal fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in 0 until minOf(a.size, b.size)) sum += a[i] * b[i]
        return sum
    }

    private fun normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = kotlin.math.sqrt(norm)
        if (norm > 0f) for (i in v.indices) v[i] /= norm
        return v
    }
}
