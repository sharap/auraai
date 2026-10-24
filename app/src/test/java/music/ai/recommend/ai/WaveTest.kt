package music.ai.recommend.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

class WaveTest {

    private val dims = 32
    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / norm }
    }

    /**
     * A library of well-separated neighbourhoods, each holding several artists — which is what a
     * real library looks like, and what the artist cooldown assumes: a neighbourhood the wave is
     * heading into must not be blocked wholesale by one artist having just played.
     */
    private fun library(groups: Int, perGroup: Int, seed: Int = 4): Pair<List<MixTrack>, List<FloatArray>> {
        val random = Random(seed)
        val centres = List(groups) { normalize(FloatArray(dims) { random.nextFloat() * 2 - 1 }) }
        val tracks = centres.flatMapIndexed { g, centre ->
            List(perGroup) { i ->
                MixTrack(
                    id = (g * perGroup + i).toLong(),
                    vector = normalize(FloatArray(dims) { centre[it] + (random.nextFloat() - 0.5f) * 0.2f }),
                    group = "g$g-artist${i % ARTISTS_PER_GROUP}"
                )
            }
        }
        return tracks to centres
    }

    private fun groupOf(id: Long, perGroup: Int) = (id / perGroup).toInt()

    private companion object {
        /** Enough that the cooldown never blocks a whole neighbourhood. */
        const val ARTISTS_PER_GROUP = 8
    }

    @Test
    fun theStreamStartsWhereItWasPointed() {
        val (tracks, centres) = library(groups = 6, perGroup = 20)

        val state = Wave.start(centres[3], tracks)
        val (next, _) = Wave.next(state, tracks, count = 5, random = Random(1), now = now)

        assertEquals(5, next.size)
        val fromSeedGroup = next.count { groupOf(it.id, 20) == 3 }
        assertTrue("started elsewhere: ${next.map { it.id }}", fromSeedGroup >= 3)
    }

    /**
     * One skip is one data point, and it used to empty the neighbourhood the listener was in: the
     * avoidance applied at full strength from the first rejection. It should bend the stream, not
     * turn it.
     */
    @Test
    fun oneSkipDoesNotEmptyTheNeighbourhood() {
        val (tracks, centres) = library(groups = 4, perGroup = 30)
        val liked = 0
        val state = Wave.start(centres[liked], tracks)
        val (before, _) = Wave.next(state, tracks, count = 6, random = Random(3), now = now)

        val afterOneSkip = Wave.react(state, tracks.first { groupOf(it.id, 30) == liked }, fraction = 0.02f)
        val (after, _) = Wave.next(afterOneSkip, tracks, count = 6, random = Random(3), now = now)

        val keptBefore = before.count { groupOf(it.id, 30) == liked }
        val keptAfter = after.count { groupOf(it.id, 30) == liked }
        assertTrue("was $keptBefore of six, now $keptAfter", keptAfter >= keptBefore - 2)
        assertTrue("the stream left after a single skip: ${after.map { it.id }}", keptAfter >= 2)
    }

    /** A pattern of skips, on the other hand, does move the stream off that kind of music. */
    @Test
    fun skipsSteerTheStreamAway() {
        val (tracks, centres) = library(groups = 4, perGroup = 30)
        val skipped = 0
        var state = Wave.start(centres[skipped], tracks)

        repeat(14) { step ->
            val (next, queued) = Wave.next(state, tracks, count = 1, random = Random(step), now = now)
            state = queued
            val track = next.single()
            state = Wave.react(state, track, fraction = if (groupOf(track.id, 30) == skipped) 0.02f else 1f)
        }

        val (after, _) = Wave.next(state, tracks, count = 6, random = Random(99), now = now)
        val fromSkipped = after.count { groupOf(it.id, 30) == skipped }
        assertTrue("still serving the skipped neighbourhood: ${after.map { it.id }}", fromSkipped <= 1)
    }

    @Test
    fun aDislikeMovesTheStreamFurtherThanASkip() {
        val (tracks, centres) = library(groups = 3, perGroup = 10)
        val state = Wave.start(centres[0], tracks)
        val track = tracks.first()

        val afterSkip = Wave.react(state, track, fraction = 0.05f)
        val afterDislike = Wave.react(state, track, fraction = 0.05f, disliked = true)

        val skipTurn = Wave.dot(afterSkip.direction, track.vector)
        val dislikeTurn = Wave.dot(afterDislike.direction, track.vector)
        assertTrue("dislike $dislikeTurn should be further than skip $skipTurn", dislikeTurn < skipTurn)
    }

    @Test
    fun aTrackHeardToTheEndPullsTheStreamTowardsIt() {
        val (tracks, centres) = library(groups = 3, perGroup = 10)
        val state = Wave.start(centres[0], tracks)
        val track = tracks.last() // from another group

        val after = Wave.react(state, track, fraction = 1f)

        assertTrue(Wave.dot(after.direction, track.vector) > Wave.dot(state.direction, track.vector))
    }

    @Test
    fun theStreamDoesNotRepeatItselfOrOneArtist() {
        val (tracks, centres) = library(groups = 8, perGroup = 25)
        val state = Wave.start(centres[0], tracks)

        val (next, _) = Wave.next(state, tracks, count = 30, random = Random(7), now = now)

        assertEquals("a track came twice", next.size, next.map { it.id }.toSet().size)
        for (window in next.windowed(5)) {
            assertEquals("one artist inside five tracks: ${window.map { it.group }}",
                5, window.map { it.group }.toSet().size)
        }
    }

    @Test
    fun recentlyPlayedTracksAreHeldBack() {
        val (plain, centres) = library(groups = 5, perGroup = 20)
        val tracks = plain.map { if (it.id < 40) it.copy(lastPlayedAt = now - day) else it }

        val state = Wave.start(centres[0], tracks)
        val (next, _) = Wave.next(state, tracks, count = 10, random = Random(3), now = now)

        assertTrue("served what was just played: ${next.map { it.id }}", next.none { it.id < 40 })
    }

    /** A wave that stops is a broken wave: when every rule cannot be satisfied at once, they are
     * relaxed rather than leaving silence. */
    @Test
    fun theStreamKeepsGoingWhenTheLibraryRunsOut() {
        val (tracks, centres) = library(groups = 2, perGroup = 6)
        val fresh = tracks.map { it.copy(lastPlayedAt = now - 60_000) } // all just played

        val state = Wave.start(centres[0], tracks)
        val (next, _) = Wave.next(state, fresh, count = 12, random = Random(5), now = now)

        assertEquals("the stream ran dry", 12, next.size)
    }

    @Test
    fun dislikedTracksNeverCome() {
        val (tracks, centres) = library(groups = 4, perGroup = 20)
        val banned = tracks.filter { groupOf(it.id, 20) == 0 }.map { it.id }.toSet()

        val state = Wave.start(centres[0], tracks) // pointed straight at the banned group
        val (next, _) = Wave.next(state, tracks, count = 20, excluded = banned, random = Random(2), now = now)

        assertTrue("a rejected track came back", next.none { it.id in banned })
        assertEquals(20, next.size)
    }

    @Test
    fun fractionIsClamped() {
        assertEquals(0.5f, Wave.fractionOf(60_000, 120_000), 0.001f)
        assertEquals(1f, Wave.fractionOf(130_000, 120_000), 0.001f)
        assertEquals(0f, Wave.fractionOf(1_000, 0), 0.001f)
    }
}
