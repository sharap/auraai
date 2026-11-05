package music.ai.recommend.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The excerpt rule has to give the same answer on two platforms that disagree about how long a
 * track is, so the cases that matter here are the disagreements rather than the arithmetic.
 */
class ExcerptTest {

    @Test
    fun theExcerptComesFromTheMiddle() {
        // Five minutes: the middle ten seconds start at 145 s.
        assertEquals(145_000L, Excerpt.startMs(300_000))
    }

    @Test
    fun aTrackNoLongerThanTheExcerptStartsAtTheBeginning() {
        assertEquals(0L, Excerpt.startMs(10_000))
        assertEquals(0L, Excerpt.startMs(9_000))
        assertEquals(0L, Excerpt.startMs(1))
        assertEquals(0L, Excerpt.startMs(0))
        assertEquals(0L, Excerpt.startMs(-1))
    }

    @Test
    fun aTrackBarelyLongerThanTheExcerptDoesNotStartBeforeZero() {
        assertEquals(0L, Excerpt.startMs(10_001))
        assertEquals(0L, Excerpt.startMs(11_999))
        assertEquals(1_000L, Excerpt.startMs(12_000))
        assertEquals(4_000L, Excerpt.startMs(19_999))
        assertEquals(5_000L, Excerpt.startMs(20_000))
    }

    @Test
    fun twoPlatformsReadingTheSameTrackAgree() {
        // What Android reports for one mp3, down to the microsecond, against what the desktop's tag
        // reader reports for the same file: whole seconds. Unquantised, the two middles are 439 ms
        // apart, which costs a cosine of about 0.95 — the whole reason the rule rounds.
        val fromAndroid = Excerpt.startMs(297_878)
        val fromTags = Excerpt.startMs(297_000)
        assertEquals(fromAndroid, fromTags)
        assertEquals(143_000L, fromAndroid)
    }

    @Test
    fun aDisagreementAcrossAnEvenSecondIsNotHidden() {
        // The rule cannot absorb everything: these two durations fall either side of a boundary
        // and give windows a second apart. A synced embedding travels with the start it was made
        // from precisely so that this case is detected rather than trusted.
        assertEquals(144_000L, Excerpt.startMs(298_000))
        assertEquals(143_000L, Excerpt.startMs(297_999))
    }

    @Test
    fun theStartIsAlwaysAWholeNumberOfSeconds() {
        for (durationMs in 10_001L..400_000L step 997L) {
            assertEquals(0L, Excerpt.startMs(durationMs) % 1000)
        }
    }

    @Test
    fun theExcerptNeverRunsPastTheEndOfTheTrack() {
        for (durationMs in 1L..600_000L step 491L) {
            val end = Excerpt.startMs(durationMs) + Excerpt.LENGTH_MS
            if (durationMs >= Excerpt.LENGTH_MS) {
                assert(end <= durationMs) { "$durationMs: отрывок кончается на $end" }
            }
        }
    }

    @Test
    fun enoughSourceSamplesAreAskedForToFillTheExcerpt() {
        // At the target rate the count is the excerpt itself; below it, enough that resampling has
        // a sample to interpolate towards for the very last one.
        assertEquals(Excerpt.SAMPLES, Excerpt.sourceSamplesNeeded(Excerpt.SAMPLE_RATE))
        assertEquals(441_002, Excerpt.sourceSamplesNeeded(44_100))
        assertEquals(220_502, Excerpt.sourceSamplesNeeded(22_050))
        assertEquals(960_002, Excerpt.sourceSamplesNeeded(96_000))
        assertEquals(Excerpt.SAMPLES, Excerpt.sourceSamplesNeeded(0))
    }

    @Test
    fun theExcerptIsTenSecondsAtTheTargetRate() {
        assertEquals(480_000, Excerpt.SAMPLES)
        assertEquals(Excerpt.SAMPLES.toLong(), Excerpt.LENGTH_MS * Excerpt.SAMPLE_RATE / 1000)
    }
}
