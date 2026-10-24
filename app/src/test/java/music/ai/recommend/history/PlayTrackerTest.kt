package music.ai.recommend.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayTrackerTest {

    private val now = 1_700_000_000_000L
    private val events = mutableListOf<PlayEvent>()
    private val tracker = PlayTracker { events += it }

    /**
     * The wave steers on these events and tops its queue up from them, so a track dropped after a
     * second has to be reported. While it was not, five quick skips left the queue empty and the
     * stream stopped.
     */
    @Test
    fun aTrackDroppedAfterASecondIsStillReported() {
        tracker.started(songId = 7, durationMs = 200_000, now = now)
        tracker.progress(1_200)
        tracker.finished(now + 1_200)

        assertEquals(1, events.size)
        assertEquals(7L, events.first().songId)
        assertTrue("a second of three minutes is a skip", events.first().fraction < 0.3f)
    }

    @Test
    fun aTrackThatNeverStartedReportsNothing() {
        tracker.finished(now)
        assertTrue(events.isEmpty())
    }

    @Test
    fun startingTheNextTrackClosesTheOneBefore() {
        tracker.started(songId = 1, durationMs = 100_000, now = now)
        tracker.progress(90_000)
        tracker.started(songId = 2, durationMs = 100_000, now = now + 90_000)

        assertEquals(listOf(1L), events.map { it.songId })
        assertEquals(0.9f, events.first().fraction, 0.001f)
    }

    /** Pauses and repeats must not add up: the furthest position reached is what counts. */
    @Test
    fun theFurthestPositionIsWhatCounts() {
        tracker.started(songId = 3, durationMs = 100_000, now = now)
        tracker.progress(50_000)
        tracker.progress(10_000)
        tracker.finished(now + 60_000)

        assertEquals(50_000L, events.first().playedMs)
    }
}
