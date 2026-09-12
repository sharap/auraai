package music.ai.recommend.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

/**
 * How opaque cards and bars should be over the current wallpaper. Panels read this instead of
 * hard-coding an alpha, because the right value depends on the wallpaper's opacity and busyness.
 */
val LocalSurfaceScrim = staticCompositionLocalOf { 0.5f }

/**
 * How much of the theme background to lay back over the wallpaper before drawing content. Zero for
 * a flat or faint image; higher only where the image swings too much for one text colour to cover.
 */
val LocalWallpaperScrim = staticCompositionLocalOf { 0f }

/**
 * Secondary text drawn straight on the wallpaper — track counts, hints. Quieter than
 * [ColorScheme.onBackground] but still held to the same contrast target, which dimming with alpha
 * would not be.
 */
val LocalMutedOnBackground = staticCompositionLocalOf { Color.Unspecified }

/** Secondary text on cards and bars — the panel equivalent of [LocalMutedOnBackground]. */
val LocalMutedOnSurface = staticCompositionLocalOf { Color.Unspecified }

/**
 * Applies the Material theme with its content colours adapted to what is actually painted behind
 * them.
 *
 * The wallpaper sits between the theme's background and the content at a user-chosen opacity, so
 * the theme alone cannot say whether text will be readable: a bright photo at full opacity under a
 * dark theme leaves light text on a light backdrop. Here the composited backdrop is measured and
 * every content colour is pushed until it clears WCAG AA against it.
 *
 * Two backdrops matter and they differ: text over the wallpaper itself uses [ColorScheme.onBackground],
 * text on a card uses [ColorScheme.onSurface], and each is checked against its own.
 */
@Composable
fun AiMusicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    backgroundTone: BackgroundTone = BackgroundTone.Unknown,
    backgroundAlpha: Float = 0f,
    content: @Composable () -> Unit
) {
    val base = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val wallpaperScrim = remember(base.background, backgroundTone, backgroundAlpha) {
        legibilityScrim(base.background, backgroundTone, backgroundAlpha)
    }
    val surfaceScrim = remember(backgroundTone, backgroundAlpha) {
        surfaceScrimAlpha(backgroundTone, backgroundAlpha)
    }
    val scheme = remember(base, backgroundTone, backgroundAlpha, wallpaperScrim, surfaceScrim) {
        base.adaptedTo(backgroundTone, backgroundAlpha, wallpaperScrim, surfaceScrim)
    }

    val backdrop = remember(base.background, backgroundTone, backgroundAlpha, wallpaperScrim) {
        backdropBand(base.background, backgroundTone, backgroundAlpha, wallpaperScrim)
    }
    val mutedOnBackground = remember(scheme, backdrop) {
        mutedAgainst(scheme.onBackground, backdrop)
    }
    val mutedOnSurface = remember(scheme, backdrop, surfaceScrim) {
        mutedAgainst(scheme.onSurface, panelBand(base.surface, backdrop, surfaceScrim))
    }

    CompositionLocalProvider(
        LocalSurfaceScrim provides surfaceScrim,
        LocalWallpaperScrim provides wallpaperScrim,
        LocalMutedOnBackground provides mutedOnBackground,
        LocalMutedOnSurface provides mutedOnSurface
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography,
            content = content
        )
    }
}

/**
 * Rewrites the content colours of this scheme so each one clears its contrast target against the
 * surface it is actually drawn on.
 *
 * Only the `on*` roles and the accents move; backgrounds and containers are left alone, so the
 * palette keeps its character and only legibility is enforced.
 */
internal fun ColorScheme.adaptedTo(
    tone: BackgroundTone,
    backgroundAlpha: Float,
    wallpaperScrim: Float,
    surfaceScrim: Float
): ColorScheme {
    val backdrop = backdropBand(background, tone, backgroundAlpha, wallpaperScrim)
    val panel = panelBand(surface, backdrop, surfaceScrim)
    val everywhere = minOf(backdrop.start, panel.start)..maxOf(backdrop.endInclusive, panel.endInclusive)

    return copy(
        // Text and icons sitting directly on the wallpaper.
        onBackground = ensureContrastInBand(onBackground, backdrop),
        // Text on cards, sheets and bars.
        onSurface = ensureContrastInBand(onSurface, panel),
        // onSurfaceVariant is deliberately left alone: it pairs with the opaque surfaceVariant used
        // by album-art placeholders, which the wallpaper never shows through. Secondary text on
        // panels uses LocalMutedOnSurface instead.
        // Accents appear over both, so they are held to the union of the two bands.
        primary = ensureContrastInBand(primary, everywhere, LARGE_TEXT_RATIO),
        secondary = ensureContrastInBand(secondary, everywhere, LARGE_TEXT_RATIO),
        outlineVariant = ensureContrastInBand(outlineVariant, backdrop, DECORATION_RATIO)
    )
}

/** WCAG AA for large text and user-interface components. */
private const val LARGE_TEXT_RATIO = 3f

/** Non-essential separators; enough to be visible without forcing them to shout. */
private const val DECORATION_RATIO = 1.6f
