package music.ai.recommend.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveQueueTest {

    private val queue = listOf("a.mp3", "b.mp3", "c.mp3")

    /** The case the desktop got wrong: the player moving to the next track must not end the wave. */
    @Test
    fun advancingInsideTheQueueKeepsTheWave() {
        assertFalse(choosingOverTheWave("b.mp3", queue, replacingQueue = false))
    }

    /** A skip is how a wave is steered, so it cannot also be how a wave is stopped. */
    @Test
    fun skippingAheadKeepsTheWave() {
        assertFalse(choosingOverTheWave("c.mp3", queue, replacingQueue = false))
    }

    @Test
    fun aTrackFromOutsideTheQueueEndsTheWave() {
        assertTrue(choosingOverTheWave("z.mp3", queue, replacingQueue = false))
    }

    /** An album or a playlist replaces the queue, whether or not the first track happens to be in it. */
    @Test
    fun aListReplacingTheQueueEndsTheWave() {
        assertTrue(choosingOverTheWave("b.mp3", queue, replacingQueue = true))
    }

    @Test
    fun withNothingQueuedAnythingIsAChoice() {
        assertTrue(choosingOverTheWave("a.mp3", emptyList(), replacingQueue = false))
    }

    /** The phone identifies a track by a number; the rule does not care. */
    @Test
    fun theRuleDoesNotCareWhatATrackIs() {
        assertFalse(choosingOverTheWave(7L, listOf(5L, 7L), replacingQueue = false))
        assertTrue(choosingOverTheWave(9L, listOf(5L, 7L), replacingQueue = false))
    }
}
