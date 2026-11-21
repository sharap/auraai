package music.ai.recommend.ai

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * What the scan is asked to look at, which is the difference between a broken file being decoded
 * once and being decoded on every scan for the rest of the library's life.
 */
class AnalysisBacklogTest {

    private val asked = ArrayList<Long>()

    private fun pending(
        tracks: List<Long>,
        analysed: Set<Long> = emptySet(),
        refusals: Map<Long, String> = emptyMap(),
        stamps: Map<Long, String> = emptyMap()
    ): List<Long> = AnalysisBacklog.pending(tracks, analysed, refusals) { id ->
        asked.add(id)
        stamps[id].orEmpty()
    }

    @Test
    fun `a track with no vector is pending`() {
        assertEquals(listOf(1L, 2L), pending(listOf(1L, 2L)))
    }

    @Test
    fun `an analysed track is not`() {
        assertEquals(listOf(2L), pending(listOf(1L, 2L), analysed = setOf(1L)))
    }

    @Test
    fun `a refusal for the file as it still is holds`() {
        val pending = pending(
            tracks = listOf(1L),
            refusals = mapOf(1L to "2048:1700000000000"),
            stamps = mapOf(1L to "2048:1700000000000")
        )
        assertTrue(pending.isEmpty())
    }

    @Test
    fun `a refusal expires when the file grows`() {
        val pending = pending(
            tracks = listOf(1L),
            refusals = mapOf(1L to AnalysisBacklog.stamp(2048, 1_700_000_000_000)),
            stamps = mapOf(1L to AnalysisBacklog.stamp(7_344_210, 1_700_000_000_000))
        )
        assertEquals(listOf(1L), pending)
    }

    @Test
    fun `a refusal expires when the file is written again at the same length`() {
        val pending = pending(
            tracks = listOf(1L),
            refusals = mapOf(1L to AnalysisBacklog.stamp(2048, 1_700_000_000_000)),
            stamps = mapOf(1L to AnalysisBacklog.stamp(2048, 1_700_000_900_000))
        )
        assertEquals(listOf(1L), pending)
    }

    @Test
    fun `a refusal with no stamp gets one more chance`() {
        // What the migration leaves behind: rows that were zero vectors before a refusal had a
        // record of its own. They have to be reconsidered exactly once.
        val now = AnalysisBacklog.stamp(2048, 1_700_000_000_000)
        assertEquals(listOf(1L), pending(listOf(1L), refusals = mapOf(1L to ""), stamps = mapOf(1L to now)))
        assertTrue(pending(listOf(1L), refusals = mapOf(1L to now), stamps = mapOf(1L to now)).isEmpty())
    }

    @Test
    fun `a refused file that cannot be read at all is left alone`() {
        // Deleted, or on a card that is not mounted. Retrying costs a decode attempt per scan and
        // can only fail the same way.
        assertTrue(pending(listOf(1L), refusals = mapOf(1L to "2048:17")).isEmpty())
        assertTrue(pending(listOf(1L), refusals = mapOf(1L to "")).isEmpty())
    }

    @Test
    fun `a refusal outranks being counted as analysed`() {
        // Both maps can name the same track while a stale cache is in play; the refusal is the
        // more recent fact and must decide, or a broken file would never be looked at again.
        val pending = pending(
            tracks = listOf(1L),
            analysed = setOf(1L),
            refusals = mapOf(1L to ""),
            stamps = mapOf(1L to "2048:17")
        )
        assertEquals(listOf(1L), pending)
    }

    @Test
    fun `only refused tracks cost a look at the file`() {
        pending(
            tracks = listOf(1L, 2L, 3L, 4L),
            analysed = setOf(2L, 3L),
            refusals = mapOf(3L to ""),
            stamps = mapOf(3L to "1:1")
        )
        assertEquals(listOf(3L), asked)
    }

    @Test
    fun `the library's order is kept`() {
        assertEquals(listOf(9L, 4L, 7L), pending(listOf(9L, 4L, 7L)))
    }

    @Test
    fun `a file of no length has no stamp`() {
        assertEquals("", AnalysisBacklog.stamp(0, 1_700_000_000_000))
        assertEquals("", AnalysisBacklog.stamp(-1, 1_700_000_000_000))
        assertFalse(AnalysisBacklog.stamp(1, 2).isEmpty())
    }
}
