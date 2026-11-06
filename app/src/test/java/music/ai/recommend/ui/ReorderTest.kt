package music.ai.recommend.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The arithmetic behind dragging a track into a different place.
 *
 * All of it is [Reorder.settle], and it is a pure function because its two mistakes are both ones
 * a finger finds and an eye does not. The first was a row that swapped with its neighbour and
 * swapped straight back, for as long as the finger was held still — on the thread drawing the
 * screen. So the assertions worth reading here are the ones that say *nothing* moved.
 */
class ReorderTest {

    /** Ten rows of a hundred pixels, which is roughly a list of tracks on a phone. */
    private val even: (Int) -> Int? = { index -> if (index in 0..9) 100 else null }

    private fun settle(from: Int, travelled: Float, heights: (Int) -> Int? = even) =
        Reorder.settle(from, travelled, heights)

    @Test
    fun aRowThatHasNotPassedItsNeighbourStaysWhereItIs() {
        val settled = settle(from = 3, travelled = 49f)
        assertEquals(3, settled.index)
        assertNull(settled.moveTo)
        assertEquals(49f, settled.travelled, 0.001f)

        // Half a row down the two rows overlap by half, and their middles have not met yet.
        assertNull(settle(from = 3, travelled = 50f).moveTo)
        assertNull(settle(from = 3, travelled = -50f).moveTo)
        // A whole row down they are exactly on top of each other. Still not past.
        assertNull(settle(from = 3, travelled = 100f).moveTo)
        assertNull(settle(from = 3, travelled = -100f).moveTo)
    }

    @Test
    fun pastTheNeighboursMiddleTheRowTakesItsPlace() {
        val down = settle(from = 3, travelled = 101f)
        assertEquals(4, down.index)
        assertEquals(4, down.moveTo)
        // The place it now has is a row further down, so what is left to travel is a row less.
        // Keeping the remainder rather than dropping it is what makes a slow drag go on smoothly
        // from where it was instead of stalling at every row.
        assertEquals(1f, down.travelled, 0.001f)

        val up = settle(from = 3, travelled = -101f)
        assertEquals(2, up.index)
        assertEquals(2, up.moveTo)
        assertEquals(-1f, up.travelled, 0.001f)
    }

    @Test
    fun aRowDoesNotSwapBackTheMomentItHasSwapped() {
        // The freeze, written down. A short row dragged just past a tall one's middle: by an edge
        // rule it would land far enough above the tall row's edge to swap straight back, and the
        // two tests would then both be true for as long as the finger was still.
        val uneven: (Int) -> Int? = { index -> listOf(100, 1000, 100).getOrNull(index) }
        val down = settle(from = 0, travelled = 551f, heights = uneven)
        assertEquals(1, down.index)
        assertEquals(1, down.moveTo)
        assertEquals(-449f, down.travelled, 0.001f)

        // And from there, with the tall row now above it, nothing moves until the finger says so:
        // the next event is measured against the same middles and asks for no move back.
        val backAgain: (Int) -> Int? = { index -> listOf(1000, 100, 100).getOrNull(index) }
        assertNull(settle(from = 1, travelled = down.travelled, heights = backAgain).moveTo)
        // One step short of a move, and one step over it, to show where the line now is.
        assertNull(settle(from = 1, travelled = -549f, heights = backAgain).moveTo)
        assertEquals(0, settle(from = 1, travelled = -551f, heights = backAgain).moveTo)
    }

    @Test
    fun aFlickMovesOneRowAndTheRestFollowsOnTheNextFrames() {
        // Deliberately one step per event: what the list says about its rows was measured before
        // this move, so a second step would be aimed at rows that have already shifted.
        val settled = settle(from = 1, travelled = 251f)
        assertEquals(2, settled.index)
        assertEquals(2, settled.moveTo)
        // Still a row and a half of travel in hand, which the following frames spend.
        assertEquals(151f, settled.travelled, 0.001f)
        val next = settle(from = settled.index, travelled = settled.travelled)
        assertEquals(3, next.moveTo)
        assertEquals(51f, next.travelled, 0.001f)
        // And there it rests, half a row short of the fourth.
        assertNull(settle(from = next.index, travelled = next.travelled).moveTo)
    }

    @Test
    fun theEndsOfTheListAreTheEnds() {
        // Dragged up from the top and down from the bottom: nowhere to go, and the travel is kept
        // as it is so the row is drawn where the finger has it and no further.
        val top = settle(from = 0, travelled = -400f)
        assertEquals(0, top.index)
        assertNull(top.moveTo)
        assertEquals(-400f, top.travelled, 0.001f)

        val bottom = settle(from = 9, travelled = 400f)
        assertEquals(9, bottom.index)
        assertNull(bottom.moveTo)
    }

    @Test
    fun aRowThatIsNotOnTheScreenStopsTheStep() {
        // Only what the list has laid out can be measured, so a step can only reach as far as the
        // screen does. The rest arrives once scrolling has caught up.
        val onlyTwoVisible: (Int) -> Int? = { index -> if (index in 3..4) 100 else null }
        val settled = settle(from = 3, travelled = 400f, heights = onlyTwoVisible)
        assertEquals(4, settled.index)
        assertEquals(4, settled.moveTo)
        assertEquals(300f, settled.travelled, 0.001f)

        // And a row the list cannot measure at all asks for nothing rather than guessing.
        assertNull(settle(from = 3, travelled = 400f, heights = { null }).moveTo)
    }

    @Test
    fun rowsOfDifferentHeightsAreCrossedByTheirOwnMiddles() {
        // Not a list of tracks, but nothing here may quietly assume one height: the row below is
        // tall, the one below that is short.
        val uneven: (Int) -> Int? = { index -> listOf(100, 300, 40, 100).getOrNull(index) }
        // Into the tall one: half of a hundred and half of three hundred.
        assertNull(settle(from = 0, travelled = 199f, heights = uneven).moveTo)
        val over = settle(from = 0, travelled = 201f, heights = uneven)
        assertEquals(1, over.moveTo)
        assertEquals(-99f, over.travelled, 0.001f)
        // Into the short one from there: seventy is all it takes.
        val afterwards: (Int) -> Int? = { index -> listOf(300, 100, 40, 100).getOrNull(index) }
        assertNull(settle(from = 1, travelled = 69f, heights = afterwards).moveTo)
        assertEquals(2, settle(from = 1, travelled = 71f, heights = afterwards).moveTo)
    }
}
