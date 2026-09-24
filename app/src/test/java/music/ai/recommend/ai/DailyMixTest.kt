package music.ai.recommend.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

class DailyMixTest {

    private val dims = 32
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_700_000_000_000L

    private fun normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / norm }
    }

    /** A library of [groups] clusters, ids 0..n-1, group "g{cluster}". */
    private fun library(groups: Int, perGroup: Int, seed: Int = 3): List<MixTrack> {
        val random = Random(seed)
        val centres = List(groups) { normalize(FloatArray(dims) { random.nextFloat() * 2 - 1 }) }
        return centres.flatMapIndexed { g, centre ->
            List(perGroup) { i ->
                MixTrack(
                    id = (g * perGroup + i).toLong(),
                    vector = normalize(FloatArray(dims) { centre[it] + (random.nextFloat() - 0.5f) * 0.3f }),
                    group = "g$g"
                )
            }
        }
    }

    @Test
    fun theSameDayGivesTheSamePlaylistAndAnotherDayDoesNot() {
        val tracks = library(groups = 8, perGroup = 20)
        val listens = List(20) { MixListen(it.toLong(), now - day, 1f) }

        val monday = DailyMix.build(tracks, listens, favourites = emptySet(), seed = 1, now = now)
        val mondayAgain = DailyMix.build(tracks, listens, favourites = emptySet(), seed = 1, now = now)
        val tuesday = DailyMix.build(tracks, listens, favourites = emptySet(), seed = 2, now = now)

        assertEquals(monday, mondayAgain)
        assertNotEquals(monday, tuesday)
        assertEquals(DailyMix.DEFAULT_SIZE, monday.size)
    }

    /** The point of the group limit: no single artist or album may take over the playlist. */
    @Test
    fun noGroupTakesOverThePlaylist() {
        val tracks = library(groups = 20, perGroup = 30)
        val listens = List(30) { MixListen(it.toLong(), now - day, 1f) } // all from group 0

        val mix = DailyMix.build(tracks, listens, favourites = emptySet(), seed = 7, now = now)

        val byGroup = mix.map { id -> tracks.first { it.id == id }.group }.groupingBy { it }.eachCount()
        assertTrue("one group took over: $byGroup", byGroup.values.all { it <= 2 })
        assertTrue("too few groups: $byGroup", byGroup.size >= 14)
    }

    @Test
    fun finishedListensCountForATrackAndSkipsAgainstIt() {
        val weights = DailyMix.tasteWeights(
            listens = listOf(
                MixListen(1, now, 1f),
                MixListen(2, now, 0.05f),
                MixListen(3, now, 0.5f)
            ),
            favourites = setOf(4),
            now = now
        )

        assertTrue(weights.getValue(1) > 0.9f)
        assertTrue(weights.getValue(2) < 0f)
        assertTrue(weights.getValue(3) in 0.2f..0.4f)
        assertTrue("a favourite outweighs a single listen", weights.getValue(4) > weights.getValue(1))
    }

    @Test
    fun oldListensFadeOut() {
        val listens = listOf(MixListen(1, now - 21 * day, 1f), MixListen(2, now, 1f))

        val weights = DailyMix.tasteWeights(listens, favourites = emptySet(), now = now)

        // 21 days is the half-life.
        assertEquals(0.5f, weights.getValue(1) / weights.getValue(2), 0.02f)
    }

    /** Skipped tracks must not come back the next day as recommendations. */
    @Test
    fun skippedTracksAreNotPushed() {
        val tracks = library(groups = 6, perGroup = 25)
        val skipped = (0L..24L).toList() // the whole of group 0
        val listens = skipped.map { MixListen(it, now - day, 0.02f) } +
            (25L..49L).map { MixListen(it, now - day, 1f) } // group 1 finished

        val mix = DailyMix.build(tracks, listens, favourites = emptySet(), seed = 5, now = now)

        assertTrue("skipped tracks dominate: $mix", mix.count { it in skipped } <= 2)
    }

    @Test
    fun partOfThePlaylistIsUnheardOrLongUnplayed() {
        val recent = library(groups = 5, perGroup = 20).map { it.copy(lastPlayedAt = now - 5 * day) }
        val fresh = library(groups = 5, perGroup = 20, seed = 9)
            .map { it.copy(id = it.id + 1000) }
        val listens = recent.take(20).map { MixListen(it.id, now - 2 * day, 1f) }

        val mix = DailyMix.build(recent + fresh, listens, favourites = emptySet(), seed = 11, now = now)

        val freshIds = fresh.map { it.id }.toSet()
        assertTrue("too little fresh: $mix", mix.count { it in freshIds } >= (mix.size * 0.4).toInt())
    }

    @Test
    fun tracksPlayedTodayAndYesterdaysPlaylistAreLeftOut() {
        val tracks = library(groups = 8, perGroup = 20)
            .map { if (it.id < 20) it.copy(lastPlayedAt = now - 3600_000) else it }
        val yesterday = (20L..39L).toSet()

        val mix = DailyMix.build(
            tracks,
            listens = emptyList(),
            favourites = emptySet(),
            recentlyOffered = yesterday,
            seed = 13,
            now = now
        )

        assertTrue("just played: $mix", mix.none { it < 20 })
        assertTrue("offered yesterday: $mix", mix.none { it in yesterday })
    }

    /** With no history and no favourites there is still a playlist, and it spans the library. */
    @Test
    fun coldStartStillProducesAVariedPlaylist() {
        val tracks = library(groups = 12, perGroup = 20)

        val mix = DailyMix.build(tracks, listens = emptyList(), favourites = emptySet(), seed = 17, now = now)

        assertEquals(DailyMix.DEFAULT_SIZE, mix.size)
        val groups = mix.map { id -> tracks.first { it.id == id }.group }.toSet()
        assertTrue("only $groups", groups.size >= 8)
    }

    /** The same song filed twice must not appear twice in one playlist. */
    @Test
    fun duplicateRecordingsAreOfferedOnce() {
        val base = library(groups = 8, perGroup = 20)
        // Copies far enough apart in embedding space that only the key catches them.
        val copies = base.take(40).map {
            it.copy(id = it.id + 5000, vector = normalize(FloatArray(dims) { i -> it.vector[i] + 0.15f }))
        }

        val keyed = (base + copies).map { it.copy(duplicateKey = "song${it.id % 5000}") }

        val mix = DailyMix.build(keyed, emptyList(), emptySet(), seed = 23, now = now)

        val originals = mix.map { if (it >= 5000) it - 5000 else it }
        assertEquals("a duplicate slipped in: $mix", originals.size, originals.toSet().size)
    }

    @Test
    fun aLibrarySmallerThanThePlaylistIsReturnedWhole() {
        val tracks = library(groups = 2, perGroup = 5)

        val mix = DailyMix.build(tracks, emptyList(), emptySet(), seed = 1, now = now)

        assertEquals(tracks.size, mix.size)
    }
}
