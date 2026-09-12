package music.ai.recommend.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The wallpaper is user-supplied and its opacity user-set, so no fixed palette can be known to read.
 * This sweeps the space — both themes, every brightness, every opacity, flat through to
 * black-and-white-halves — and insists that what ends up on screen clears WCAG AA at both ends of
 * whatever the backdrop does.
 */
@RunWith(AndroidJUnit4::class)
class AdaptiveContrastTest {

    /** Mean plus a range around it, clamped to the 0..1 the measurement would produce. */
    private fun tone(mean: Float, range: Float) = BackgroundTone(
        luminance = mean,
        darkest = (mean - range / 2f).coerceIn(0f, 1f),
        brightest = (mean + range / 2f).coerceIn(0f, 1f)
    )

    @Test
    fun contentReadsOverAnyWallpaperAndOpacity() {
        val failures = StringBuilder()

        for (dark in listOf(true, false)) {
            val base = if (dark) darkColorScheme() else lightColorScheme()
            for (brightness in 0..10) {
                for (opacity in 0..10) {
                    // 0.0 flat, 0.4 a normal photo, 1.0 the pathological half-black half-white case.
                    for (range in listOf(0f, 0.4f, 1f)) {
                        val tone = tone(brightness / 10f, range)
                        val alpha = opacity / 10f
                        val wallpaperScrim = legibilityScrim(base.background, tone, alpha)
                        val surfaceScrim = surfaceScrimAlpha(tone, alpha)
                        val scheme = base.adaptedTo(tone, alpha, wallpaperScrim, surfaceScrim)

                        val backdrop = backdropBand(base.background, tone, alpha, wallpaperScrim)
                        val panel = panelBand(base.surface, backdrop, surfaceScrim)

                        check(failures, "onBackground", scheme.onBackground, backdrop, 4.5f, dark, tone, alpha)
                        check(failures, "onSurface", scheme.onSurface, panel, 4.5f, dark, tone, alpha)
                        // onSurfaceVariant is theme-internal and left alone; secondary text over
                        // each backdrop goes through the muted colours instead.
                        check(failures, "mutedOnBackground", mutedAgainst(scheme.onBackground, backdrop), backdrop, 4.5f, dark, tone, alpha)
                        check(failures, "mutedOnSurface", mutedAgainst(scheme.onSurface, panel), panel, 4.5f, dark, tone, alpha)
                    }
                }
            }
        }
        assertTrue(failures.toString(), failures.isEmpty())
    }

    private fun check(
        failures: StringBuilder,
        role: String,
        color: Color,
        band: ClosedFloatingPointRange<Float>,
        required: Float,
        dark: Boolean,
        tone: BackgroundTone,
        alpha: Float
    ) {
        val luminance = luminanceOf(color)
        val worst = minOf(contrastRatio(luminance, band.start), contrastRatio(luminance, band.endInclusive))
        if (worst < required - 0.01f) {
            failures.append(
                "%s %.2f:1 (needs %.1f) dark=%b wallpaper=%.1f..%.1f alpha=%.1f\n"
                    .format(role, worst, required, dark, tone.darkest, tone.brightest, alpha)
            )
        }
    }

    /** A flat wallpaper is readable as it is, so it must not be covered. */
    @Test
    fun leavesFlatWallpapersUncovered() {
        for (base in listOf(darkColorScheme(), lightColorScheme())) {
            assertEquals(0f, legibilityScrim(base.background, tone(0.9f, 0f), 1f), 0.001f)
            assertEquals(0f, legibilityScrim(base.background, tone(0.05f, 0f), 1f), 0.001f)
            assertEquals("no wallpaper, no scrim", 0f, legibilityScrim(base.background, BackgroundTone.Unknown, 1f), 0.001f)
        }
    }

    /** The case a screenshot caught: half black, half white, fully opaque. */
    @Test
    fun coversAWallpaperThatSwingsFromBlackToWhite()  {
        val base = darkColorScheme()
        val extreme = BackgroundTone(luminance = 0.5f, darkest = 0f, brightest = 1f)
        val scrim = legibilityScrim(base.background, extreme, 1f)
        assertTrue("expected a real scrim, got $scrim", scrim > 0.3f)

        val band = backdropBand(base.background, extreme, 1f, scrim)
        assertTrue(
            "band ${band.start}..${band.endInclusive} still has no readable colour",
            bestAchievableContrast(band) >= 4.5f
        )
    }

    /** A brighter or busier wallpaper has to be covered more, never less. */
    @Test
    fun scrimGrowsWithOpacityAndRange() {
        val flat = tone(0.5f, 0f)
        val busy = tone(0.5f, 0.6f)
        assertTrue(surfaceScrimAlpha(flat, 1f) > surfaceScrimAlpha(flat, 0f))
        assertTrue(surfaceScrimAlpha(busy, 0.5f) > surfaceScrimAlpha(flat, 0.5f))
        assertTrue(surfaceScrimAlpha(busy, 1f) <= 1f)
    }
}
