package music.ai.recommend.ui.theme

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Checks that the wallpaper is actually measured, not just that the contrast maths is sound. */
@RunWith(AndroidJUnit4::class)
class BackgroundToneTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun writeImage(name: String, draw: (Bitmap) -> Unit): String {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        draw(bitmap)
        val file = File(context.cacheDir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file.toURI().toString()
    }

    @Test
    fun measuresAWhiteWallpaperAsBright() {
        val uri = writeImage("tone-white.png") { it.eraseColor(AndroidColor.WHITE) }
        val tone = runBlocking { measureBackgroundTone(context, uri) }
        assertNotNull(tone)
        assertEquals(1.0f, tone!!.luminance, 0.02f)
        assertEquals("a flat image has no range", 0f, tone.range, 0.02f)
    }

    @Test
    fun measuresABlackWallpaperAsDark() {
        val uri = writeImage("tone-black.png") { it.eraseColor(AndroidColor.BLACK) }
        val tone = runBlocking { measureBackgroundTone(context, uri) }
        assertNotNull(tone)
        assertEquals(0.0f, tone!!.luminance, 0.02f)
    }

    /** A half-black, half-white image averages to mid-grey but must report high variation. */
    @Test
    fun reportsVariationForABusyWallpaper() {
        val uri = writeImage("tone-split.png") { bitmap ->
            for (x in 0 until bitmap.width) {
                for (y in 0 until bitmap.height) {
                    bitmap.setPixel(x, y, if (x < bitmap.width / 2) AndroidColor.BLACK else AndroidColor.WHITE)
                }
            }
        }
        val tone = runBlocking { measureBackgroundTone(context, uri) }
        assertNotNull(tone)
        assertEquals(0.5f, tone!!.luminance, 0.1f)
        assertTrue("range ${tone.range}", tone.range > 0.9f)
        assertTrue(
            "a split image must be covered more than a flat one",
            surfaceScrimAlpha(tone, 0.5f) > surfaceScrimAlpha(BackgroundTone(0.5f, 0.5f, 0.5f), 0.5f)
        )
    }

    /** A bright wallpaper under a dark theme is exactly the case that used to be unreadable. */
    @Test
    fun forcesDarkTextOverABrightWallpaper() {
        val scheme = androidx.compose.material3.darkColorScheme()
        val tone = BackgroundTone(luminance = 0.95f, darkest = 0.93f, brightest = 0.97f)
        val adapted = scheme.adaptedTo(
            tone,
            backgroundAlpha = 1f,
            wallpaperScrim = legibilityScrim(scheme.background, tone, 1f),
            surfaceScrim = surfaceScrimAlpha(tone, 1f)
        )
        assertTrue(
            "text should have gone dark, luminance ${luminanceOf(adapted.onBackground)}",
            luminanceOf(adapted.onBackground) < 0.2f
        )
    }
}
