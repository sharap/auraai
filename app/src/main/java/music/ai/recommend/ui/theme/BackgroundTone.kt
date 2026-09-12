package music.ai.recommend.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import kotlin.math.pow
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult

/**
 * What the wallpaper looks like, reduced to the numbers that decide legibility.
 *
 * The average alone is not enough. An image that is half black and half white averages to mid-grey,
 * and text picked for mid-grey disappears into one half of it — so the range matters more than the
 * mean, and [darkest] and [brightest] are what the colour choice is actually held against.
 *
 * @param luminance mean WCAG relative luminance, 0 (black) to 1 (white).
 * @param darkest 5th percentile of per-pixel luminance; outliers are ignored deliberately.
 * @param brightest 95th percentile.
 */
@Immutable
data class BackgroundTone(val luminance: Float, val darkest: Float, val brightest: Float) {
    companion object {
        /** Used until an image has been measured, and whenever there is no wallpaper. */
        val Unknown = BackgroundTone(luminance = -1f, darkest = -1f, brightest = -1f)
    }

    val isKnown: Boolean get() = luminance >= 0f

    /** How far the image swings; a flat wallpaper needs far less covering than a busy one. */
    val range: Float get() = brightest - darkest
}

/**
 * Measures [uri] at thumbnail resolution. Downsampling to 32x32 is deliberate: it is the average
 * brightness that matters, and a tiny bitmap makes this cheap enough to redo whenever the wallpaper
 * changes.
 */
suspend fun measureBackgroundTone(context: Context, uri: String): BackgroundTone? {
    val request = ImageRequest.Builder(context)
        .data(uri)
        .size(THUMBNAIL, THUMBNAIL)
        .allowHardware(false) // pixels have to be readable on the CPU
        .build()

    val bitmap = when (val result = context.imageLoader.execute(request)) {
        is SuccessResult -> (result.drawable as? BitmapDrawable)?.bitmap
        else -> null
    } ?: return null

    return runCatching { bitmap.measureTone() }
        .onFailure { Log.e("BackgroundTone", "Could not measure the wallpaper", it) }
        .getOrNull()
}

private fun Bitmap.measureTone(): BackgroundTone {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)

    val luminances = FloatArray(pixels.size) { ColorUtils.calculateLuminance(pixels[it]).toFloat() }
    val mean = luminances.average().toFloat()
    luminances.sort()
    val low = luminances[(luminances.size * 5 / 100).coerceIn(0, luminances.size - 1)]
    val high = luminances[(luminances.size * 95 / 100).coerceIn(0, luminances.size - 1)]
    return BackgroundTone(mean, low, high)
}

private const val THUMBNAIL = 32

/**
 * The band of luminances actually painted behind content, once the wallpaper has been composited
 * over the theme background at [alpha] and the legibility scrim laid over that.
 *
 * Returns the darkest and brightest the backdrop gets, because text has to read against both.
 */
fun backdropBand(
    themeBackground: Color,
    tone: BackgroundTone,
    alpha: Float,
    scrim: Float
): ClosedFloatingPointRange<Float> {
    val themeLuminance = luminanceOf(themeBackground)
    if (!tone.isKnown || alpha <= 0f) return themeLuminance..themeLuminance

    // Blend the way the GPU does — in sRGB — then read the luminance off the result.
    val themeSrgb = luminanceToSrgb(themeLuminance)
    fun stack(imageLuminance: Float): Float {
        val overTheme = composite(themeSrgb, luminanceToSrgb(imageLuminance), alpha)
        return srgbToLuminance(composite(overTheme, themeSrgb, scrim))
    }
    return stack(tone.darkest)..stack(tone.brightest)
}

/** WCAG contrast ratio between two relative luminances, from 1 (identical) to 21. */
fun contrastRatio(first: Float, second: Float): Float {
    val lighter = maxOf(first, second)
    val darker = minOf(first, second)
    return (lighter + 0.05f) / (darker + 0.05f)
}

fun luminanceOf(color: Color): Float = ColorUtils.calculateLuminance(color.toArgb()).toFloat()

/**
 * The sRGB grey level whose relative luminance is [luminance] — the inverse of the transfer
 * function WCAG applies.
 *
 * Needed because alpha compositing happens in sRGB, not in linear luminance. Modelling the blend in
 * luminance overestimates how much a dark area is lifted by a light scrim by a wide margin: a 20%
 * near-white scrim over black lands at sRGB 0.196, which is a luminance of 0.032, not 0.19.
 */
fun luminanceToSrgb(luminance: Float): Float {
    val l = luminance.coerceIn(0f, 1f)
    return if (l <= 0.0031308f) 12.92f * l else (1.055f * l.pow(1f / 2.4f) - 0.055f)
}

/** Relative luminance of an sRGB grey level. */
fun srgbToLuminance(value: Float): Float {
    val v = value.coerceIn(0f, 1f)
    return if (v <= 0.03928f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
}

/** Composites [over] onto [under] at [alpha], both as sRGB grey levels. */
private fun composite(under: Float, over: Float, alpha: Float): Float = under * (1f - alpha) + over * alpha

private const val BLEND_STEPS = 12

/**
 * A quieter version of [foreground] for secondary lines, muted only as far as the contrast target
 * allows.
 *
 * Dimming with alpha, the obvious approach, silently breaks the guarantee: 75% opacity over a
 * backdrop chosen to give exactly 4.5:1 drops it to about 3.5:1. Blending toward the backdrop and
 * then re-checking keeps the hierarchy where there is room for it and gives it up where there is
 * not.
 */
fun mutedAgainst(
    foreground: Color,
    band: ClosedFloatingPointRange<Float>,
    amount: Float = 0.35f,
    minRatio: Float = BODY_TEXT_RATIO
): Color {
    val mid = luminanceToSrgb((band.start + band.endInclusive) / 2f)
    return ensureContrastInBand(blend(foreground, Color(mid, mid, mid), amount), band, minRatio)
}

private fun blend(from: Color, to: Color, fraction: Float): Color = Color(
    red = from.red + (to.red - from.red) * fraction,
    green = from.green + (to.green - from.green) * fraction,
    blue = from.blue + (to.blue - from.blue) * fraction,
    alpha = from.alpha
)

/**
 * How much of the theme background to lay back over the wallpaper before drawing content.
 *
 * Without this there is no answer for a high-contrast image: one text colour cannot read against
 * both a black and a white half at once. The scrim compresses the backdrop's range until a single
 * colour does work, and no further — a flat or faint wallpaper needs none at all and is left alone.
 */
fun legibilityScrim(themeBackground: Color, tone: BackgroundTone, alpha: Float): Float {
    if (!tone.isKnown || alpha <= 0f) return 0f
    var scrim = 0f
    while (scrim < MAX_LEGIBILITY_SCRIM) {
        val band = backdropBand(themeBackground, tone, alpha, scrim)
        if (bestAchievableContrast(band) >= BODY_TEXT_RATIO) return scrim
        scrim += SCRIM_STEP
    }
    return MAX_LEGIBILITY_SCRIM
}

/** The contrast the better of black or white manages against the whole band. */
fun bestAchievableContrast(band: ClosedFloatingPointRange<Float>): Float {
    val onBlack = minOf(contrastRatio(0f, band.start), contrastRatio(0f, band.endInclusive))
    val onWhite = minOf(contrastRatio(1f, band.start), contrastRatio(1f, band.endInclusive))
    return maxOf(onBlack, onWhite)
}

/**
 * Nudges [foreground] toward black or white until it reads against every luminance in [band].
 *
 * Whichever extreme is chosen is the one that reads better against the worse end of the band, so a
 * backdrop that swings is judged by its hardest part rather than its average.
 */
fun ensureContrastInBand(
    foreground: Color,
    band: ClosedFloatingPointRange<Float>,
    minRatio: Float = BODY_TEXT_RATIO
): Color {
    fun worstContrast(color: Color): Float {
        val luminance = luminanceOf(color)
        return minOf(contrastRatio(luminance, band.start), contrastRatio(luminance, band.endInclusive))
    }
    if (worstContrast(foreground) >= minRatio) return foreground

    // Once the theme's own colour has to be given up, aim well clear of the threshold rather than
    // stopping the moment it is met. Settling for exactly 4.5:1 against black produced a dim grey
    // that passes on paper and looks broken on screen; a share of what the backdrop allows gives
    // near-white there and still falls back to the floor where the backdrop is unforgiving.
    val goal = maxOf(minRatio, bestAchievableContrast(band) * COMFORT_SHARE)

    val target = if (worstContrast(Color.Black) >= worstContrast(Color.White)) Color.Black else Color.White
    var low = 0f
    var high = 1f
    var best = target
    repeat(BLEND_STEPS) {
        val mid = (low + high) / 2f
        val candidate = blend(foreground, target, mid)
        if (worstContrast(candidate) >= goal) {
            best = candidate
            high = mid // less blending still passes, so keep more of the original hue
        } else {
            low = mid
        }
    }
    return best
}

/** Luminance band of a panel painted at [scrimAlpha] over [backdrop]. */
fun panelBand(
    surface: Color,
    backdrop: ClosedFloatingPointRange<Float>,
    scrimAlpha: Float
): ClosedFloatingPointRange<Float> {
    val surfaceSrgb = luminanceToSrgb(luminanceOf(surface))
    fun over(value: Float) = srgbToLuminance(composite(luminanceToSrgb(value), surfaceSrgb, scrimAlpha))
    return over(backdrop.start)..over(backdrop.endInclusive)
}

/**
 * How opaque panels — cards, the navigation bar — have to be for text on them to read.
 *
 * Grows with the wallpaper's opacity, because that is how much of it shows through, and with its
 * range, because a busy image needs more covering than a flat one at the same brightness.
 */
fun surfaceScrimAlpha(tone: BackgroundTone, backgroundAlpha: Float): Float {
    if (!tone.isKnown) return BASE_SCRIM
    val busy = (tone.range * BUSY_WEIGHT).coerceIn(0f, 0.3f)
    return (BASE_SCRIM + backgroundAlpha * 0.35f + busy).coerceIn(BASE_SCRIM, 0.97f)
}

/** WCAG AA for body text. */
const val BODY_TEXT_RATIO = 4.5f

/** How much of the achievable contrast to actually take once the theme colour has to be abandoned. */
private const val COMFORT_SHARE = 0.7f

private const val BASE_SCRIM = 0.5f
private const val BUSY_WEIGHT = 0.35f
private const val SCRIM_STEP = 0.02f

/**
 * Beyond this the wallpaper would be gone entirely, which is not what the user asked for.
 *
 * An image spanning black to white under a dark theme needs about 0.82 before white text clears
 * 4.5:1, so the ceiling has to sit above that; ordinary photographs need none of it, because a
 * single colour already covers their range.
 */
private const val MAX_LEGIBILITY_SCRIM = 0.88f
