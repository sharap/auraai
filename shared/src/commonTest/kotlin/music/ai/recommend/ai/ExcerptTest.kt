package music.ai.recommend.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun threeWindowsSpreadAroundTheMiddle() {
        // Five minutes: the middle ten seconds start at 145 s, and the other two windows sit three
        // fifths of that distance either side — 19% and 77% of the way through, which is the
        // spread the measurements picked.
        assertArrayEquals(longArrayOf(58_000L, 145_000L, 232_000L), Excerpt.startsMs(300_000))
    }

    @Test
    fun theMiddleWindowIsTheOneAVectorTravelsWith() {
        // What makes a synced vector trustworthy: agreeing on startMs means agreeing on all three,
        // because the outer two are derived from it and from nothing else.
        for (durationMs in longArrayOf(30_000, 61_000, 180_499, 300_000, 3_600_000)) {
            assertEquals(Excerpt.startMs(durationMs), Excerpt.startsMs(durationMs)[1])
        }
    }

    @Test
    fun aShortTrackGetsOneWindowRatherThanThreeOverlappingCopies() {
        assertArrayEquals(longArrayOf(0L), Excerpt.startsMs(10_000))
        assertArrayEquals(longArrayOf(1_000L), Excerpt.startsMs(12_000))
        assertArrayEquals(longArrayOf(9_000L), Excerpt.startsMs(29_999))
        // Thirty seconds is where three windows start to hear different music.
        assertEquals(3, Excerpt.startsMs(30_000).size)
    }

    @Test
    fun everyWindowFitsInsideTheTrack() {
        var durationMs = 30_000L
        while (durationMs <= 2 * 60 * 60 * 1000L) {
            for (start in Excerpt.startsMs(durationMs)) {
                assertTrue(
                    "window at $start does not fit in $durationMs ms",
                    start >= 0 && start + Excerpt.LENGTH_MS <= durationMs
                )
            }
            durationMs += 997L
        }
    }

    @Test
    fun averagingWindowsGivesAUnitVector() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        val averaged = Excerpt.averaged(listOf(a, b))
        val half = (1.0 / Math.sqrt(2.0)).toFloat()
        assertEquals(half, averaged[0], 1e-6f)
        assertEquals(half, averaged[1], 1e-6f)
        assertEquals(0f, averaged[2], 1e-6f)
    }

    @Test
    fun averagingOneWindowChangesNothing() {
        val only = floatArrayOf(0.6f, 0.8f, 0f)
        val averaged = Excerpt.averaged(listOf(only))
        assertEquals(0.6f, averaged[0], 1e-6f)
        assertEquals(0.8f, averaged[1], 1e-6f)
    }

    /**
     * The decoder names the windows by the middle it counted itself, and that has to give exactly
     * what the duration-based rule gives — otherwise the sync record would name one window set and
     * the vector be made from another.
     */
    @Test
    fun namingTheWindowsByTheMiddleAgreesWithNamingThemByTheDuration() {
        var durationMs = 1_000L
        while (durationMs <= 2 * 60 * 60 * 1000L) {
            assertArrayEquals(
                "windows for $durationMs ms",
                Excerpt.startsMs(durationMs),
                Excerpt.startsAroundMs(Excerpt.startMs(durationMs))
            )
            durationMs += 613L
        }
    }
}
