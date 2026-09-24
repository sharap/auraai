package music.ai.recommend.ai

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/**
 * What the mix knows about one track: where it sits in embedding space, who it is by, and when it
 * was last heard. [group] keeps one artist or one smart album from filling the playlist.
 */
data class MixTrack(
    val id: Long,
    val vector: FloatArray,
    val group: String,
    val lastPlayedAt: Long? = null,
    /**
     * Identifies the recording rather than the file. Libraries collect the same song twice, in two
     * folders, and two rips of one song are not always close enough in embedding space to be caught
     * by similarity alone — artist and title catch them.
     */
    val duplicateKey: String = ""
) {
    override fun equals(other: Any?) = other is MixTrack && other.id == id
    override fun hashCode() = id.hashCode()
}

/** A listen, as the mix sees it: [fraction] of the track reached, at [playedAt]. */
data class MixListen(val songId: Long, val playedAt: Long, val fraction: Float)

/**
 * Builds the playlist of the day from the library's own embeddings and the listening history.
 *
 * The shape follows what streaming services do, with one difference that matters here: there is no
 * one else's listening to fall back on, so everything is derived from this library alone.
 *
 * - **Taste** is a weight per track: finishing it counts for, skipping it counts against, a
 *   favourite counts for a lot, and all of it fades with time, so last week outweighs last spring.
 * - **Anchors** are a handful of liked tracks, drawn at random with a weight-biased draw. They are
 *   what makes two days differ; picking the top-weighted tracks every time would produce the same
 *   playlist forever.
 * - **Selection** balances closeness to the anchors against being unlike what is already picked, so
 *   the result is a mix and not forty minutes of one artist.
 * - **Freshness** reserves part of the playlist for tracks not heard in a long time or never.
 * - **Order** walks from each track to its nearest remaining neighbour, so the playlist drifts
 *   rather than jumping between extremes.
 *
 * Pure Kotlin, no Android dependencies, so the whole thing runs under unit tests and offline
 * experiments on an exported library.
 */
object DailyMix {

    const val DEFAULT_SIZE = 28

    /** A listen past this much of the track is a real listen. */
    private const val FINISHED = 0.8f

    /** Below this it is a skip, and counts against the track. */
    private const val SKIPPED = 0.3f

    private const val FINISHED_WEIGHT = 1f
    private const val PARTIAL_WEIGHT = 0.3f
    private const val SKIP_WEIGHT = -0.7f
    private const val FAVOURITE_WEIGHT = 2f

    /** Weight halves every this many days, so the profile follows what is being listened to now. */
    private const val HALF_LIFE_DAYS = 21.0

    private const val ANCHORS = 4

    /** Share of the playlist reserved for tracks that are new or long unheard. */
    private const val FRESH_SHARE = 0.4

    /** A track counts as fresh if it has not been played for this long. */
    private const val FRESH_AFTER_DAYS = 30

    /** Kept out of the mix entirely: played within this window, or offered on a recent day. */
    private const val REPEAT_BLOCK_DAYS = 3

    /** Taste weight at or below which a track is not offered at all: it was skipped, repeatedly. */
    private const val REJECTED = -0.5f

    /** How much the selection cares about closeness to taste versus being unlike the rest. */
    private const val RELEVANCE = 0.75f

    /** At most this many tracks from one artist or smart album, when the library allows it. */
    private const val PER_GROUP = 2

    /**
     * Above this cosine two tracks are the same recording: libraries collect the same song twice,
     * in two folders, and a playlist that offers both looks broken.
     */
    private const val DUPLICATE = 0.99f

    /** Only the best candidates go through the full selection, which is quadratic in its input. */
    private const val SHORTLIST = 300

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * @param tracks every analysed track in the library.
     * @param listens the listening history; may be empty, in which case the mix falls back to
     *   favourites and, failing those, to the library's own variety.
     * @param favourites song ids the user marked.
     * @param recentlyOffered song ids from the last few daily playlists, kept out to avoid repeats.
     * @param seed derived from the date, so the same day always yields the same playlist.
     * @param now epoch milliseconds, for ageing the history.
     */
    fun build(
        tracks: List<MixTrack>,
        listens: List<MixListen>,
        favourites: Set<Long>,
        recentlyOffered: Set<Long> = emptySet(),
        seed: Long,
        now: Long,
        size: Int = DEFAULT_SIZE
    ): List<Long> {
        if (tracks.size <= size) return tracks.map { it.id }

        val random = Random(seed)
        val weights = tasteWeights(listens, favourites, now)
        val byId = tracks.associateBy { it.id }

        val available = tracks.filter { track ->
            track.id !in recentlyOffered &&
                (track.lastPlayedAt == null || now - track.lastPlayedAt > REPEAT_BLOCK_DAYS * DAY_MS) &&
                // Skipping something is an answer. Scoring it down is not enough — a skipped track
                // that happens to sit close to an anchor would still come back tomorrow.
                ((weights[track.id] ?: 0f) > REJECTED || track.id in favourites)
        }.ifEmpty { tracks }

        val anchors = pickAnchors(available, weights, random)
        val shortlist = shortlist(available, anchors, weights, now)
        val picked = select(shortlist, size, now, random)
        return order(picked.map { byId.getValue(it) })
    }

    // ------------------------------------------------------------------ taste

    /**
     * How much each track counts for or against, with older listens fading out.
     *
     * A skip is deliberately weaker than a finished listen: skipping happens for reasons that have
     * nothing to do with the track, while sitting through four minutes rarely does.
     */
    internal fun tasteWeights(listens: List<MixListen>, favourites: Set<Long>, now: Long): Map<Long, Float> {
        val weights = HashMap<Long, Float>()
        for (listen in listens) {
            val ageDays = (now - listen.playedAt).coerceAtLeast(0L).toDouble() / DAY_MS
            val decay = exp(-ln(2.0) * ageDays / HALF_LIFE_DAYS).toFloat()
            val base = when {
                listen.fraction >= FINISHED -> FINISHED_WEIGHT
                listen.fraction >= SKIPPED -> PARTIAL_WEIGHT
                else -> SKIP_WEIGHT
            }
            weights[listen.songId] = (weights[listen.songId] ?: 0f) + base * decay
        }
        for (id in favourites) weights[id] = (weights[id] ?: 0f) + FAVOURITE_WEIGHT
        return weights
    }

    /**
     * The tracks the day is built around. Drawn at random in proportion to taste, which is what
     * makes one day differ from the next; with no history at all the draw is uniform, and the
     * playlist is then simply a varied tour of the library.
     */
    private fun pickAnchors(tracks: List<MixTrack>, weights: Map<Long, Float>, random: Random): List<MixTrack> {
        val liked = tracks.filter { (weights[it.id] ?: 0f) > 0f }
        val pool = liked.ifEmpty { tracks }
        val anchors = ArrayList<MixTrack>(ANCHORS)
        val remaining = pool.toMutableList()
        repeat(minOf(ANCHORS, pool.size)) {
            val total = remaining.sumOf { (weights[it.id] ?: 0f).toDouble().coerceAtLeast(0.01) }
            var cut = random.nextDouble() * total
            var chosen = remaining.lastIndex
            for ((i, track) in remaining.withIndex()) {
                cut -= (weights[track.id] ?: 0f).toDouble().coerceAtLeast(0.01)
                if (cut <= 0) {
                    chosen = i
                    break
                }
            }
            anchors += remaining.removeAt(chosen)
        }
        return anchors
    }

    // ------------------------------------------------------------------ candidates

    private class Candidate(val track: MixTrack, val score: Float, val fresh: Boolean)

    /**
     * Scores every track by how close it is to the day's anchors, then keeps the best few hundred.
     * Tracks the user skips are pushed down, tracks they finish are pulled up, and the anchors
     * themselves stay in: the playlist is meant to contain some of what it was built from.
     */
    private fun shortlist(
        tracks: List<MixTrack>,
        anchors: List<MixTrack>,
        weights: Map<Long, Float>,
        now: Long
    ): List<Candidate> {
        val candidates = tracks.map { track ->
            val closeness = anchors.maxOfOrNull { dot(track.vector, it.vector) } ?: 0f
            val taste = (weights[track.id] ?: 0f).coerceIn(-1f, 1f)
            Candidate(
                track = track,
                score = closeness + TASTE_PULL * taste,
                fresh = track.lastPlayedAt == null || now - track.lastPlayedAt > FRESH_AFTER_DAYS * DAY_MS
            )
        }
        return candidates.sortedByDescending { it.score }.take(SHORTLIST)
    }

    private const val TASTE_PULL = 0.15f

    /**
     * Picks the playlist out of the shortlist, trading closeness to taste against variety: at each
     * step the best remaining candidate is the one that scores well and is least like what is
     * already in. Group limits and the freshness quota are applied as hard constraints, because
     * they are the difference between a playlist and a pile of near-duplicates.
     */
    private fun select(shortlist: List<Candidate>, size: Int, now: Long, random: Random): List<Long> {
        val picked = ArrayList<Candidate>(size)
        val groupCounts = HashMap<String, Int>()
        val usedKeys = HashSet<String>()
        val freshNeeded = (size * FRESH_SHARE).toInt()
        val remaining = shortlist.toMutableList()
        // Two per group is the aim, but a library of a few large groups cannot fill a playlist at
        // that rate; there the limit is whatever the groups on offer can supply.
        val groups = shortlist.distinctBy { it.track.group }.size.coerceAtLeast(1)
        var perGroup = maxOf(PER_GROUP, (size + groups - 1) / groups)

        while (picked.size < size && remaining.isNotEmpty()) {
            val left = size - picked.size
            val freshPicked = picked.count { it.fresh }
            // Once only as many slots are left as the quota still needs, take fresh tracks only.
            val freshOnly = freshNeeded - freshPicked >= left

            var bestIndex = -1
            var bestValue = Float.NEGATIVE_INFINITY
            for ((i, candidate) in remaining.withIndex()) {
                if (freshOnly && !candidate.fresh) continue
                if ((groupCounts[candidate.track.group] ?: 0) >= perGroup) continue
                if (candidate.track.duplicateKey.isNotEmpty() && candidate.track.duplicateKey in usedKeys) continue
                val similarity = picked.maxOfOrNull { dot(candidate.track.vector, it.track.vector) } ?: 0f
                if (similarity > DUPLICATE) continue
                val value = RELEVANCE * candidate.score - (1 - RELEVANCE) * similarity
                if (value > bestValue) {
                    bestValue = value
                    bestIndex = i
                }
            }
            if (bestIndex < 0) {
                // Nothing satisfies the constraints any more: let one more track per group
                // through rather than hand back a short playlist.
                if (remaining.none { !freshOnly || it.fresh }) break
                perGroup++
                continue
            }
            val chosen = remaining.removeAt(bestIndex)
            groupCounts[chosen.track.group] = (groupCounts[chosen.track.group] ?: 0) + 1
            if (chosen.track.duplicateKey.isNotEmpty()) usedKeys += chosen.track.duplicateKey
            picked += chosen
        }
        // A tiny shuffle of equals would break the ordering pass below, so ordering is left to it.
        return picked.map { it.track.id }
    }

    /**
     * Orders the chosen tracks as a walk: from the one furthest from the rest, each time to its
     * nearest remaining neighbour. A playlist ordered by score instead would put everything alike
     * at the front and all the outliers at the end.
     */
    private fun order(tracks: List<MixTrack>): List<Long> {
        if (tracks.size <= 2) return tracks.map { it.id }
        val remaining = tracks.toMutableList()
        var current = remaining.minByOrNull { track ->
            tracks.sumOf { dot(track.vector, it.vector).toDouble() }
        } ?: remaining.first()
        remaining.remove(current)
        val ordered = ArrayList<Long>(tracks.size)
        ordered += current.id
        while (remaining.isNotEmpty()) {
            val next = remaining.maxByOrNull { dot(current.vector, it.vector) } ?: break
            remaining.remove(next)
            ordered += next.id
            current = next
        }
        return ordered
    }

    // ------------------------------------------------------------------ helpers

    /** Vectors are stored unit-length, so the dot product is the cosine. */
    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in 0 until minOf(a.size, b.size)) sum += a[i] * b[i]
        return sum
    }

    /**
     * The seed for a given day: the same on every launch that day, different the next. Derived
     * from the date alone, so no clock skew or time zone change can make it drift mid-day.
     */
    fun seedFor(day: String, library: Int): Long = abs(day.hashCode().toLong() * 1_000_003L + library)
}
