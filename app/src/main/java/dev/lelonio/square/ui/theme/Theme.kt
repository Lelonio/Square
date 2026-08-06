package dev.lelonio.square.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Glass over the album art.
 *
 * The scheme is deliberately one-sided now. The paper version had a light and a
 * dark half because the page was opaque and had to match the system; here the
 * page *is* the artwork under a dark wash, so there is only ever dark glass with
 * light ink on it. Following the system theme would mean two backdrops and two
 * sets of legibility rules for a surface whose contrast does not depend on
 * either.
 *
 * `background` is transparent on purpose: whatever draws the backdrop sits
 * behind the whole tree, and an opaque page colour would cover it.
 *
 * What follows is the original note, still true of the accent:
 *
 * The neutrals do all the structural work — page, cards, text, borders are a
 * single grey ramp — and the accent seeded from the current cover is spent only
 * where the interface needs to say "this one, right now": the play button, the
 * played part of the waveform, an enabled toggle, the current row. Kept that
 * narrow, colour reads as state rather than as decoration, and the covers stay
 * the most colourful thing on screen.
 *
 * The seed is pushed toward legibility rather than used raw: a cover's dominant
 * colour is as likely to be near-black as near-white, and either one vanishes
 * against the wrong background.
 */
@Composable
fun SquareTheme(
    /** Dominant colour of the current artwork, or null before anything plays. */
    seed: Color? = null,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val base = seed ?: DefaultAccent
    val accent = if (darkTheme) base.liftFor(DarkBase) else base.deepenFor(LightBase)

    // Animated so a track change slides the accent across instead of snapping,
    // which otherwise reads as a glitch when artwork loads a beat late.
    val animatedAccent by animateColorAsState(accent, tween(600), label = "accent")

    // Keyed on the accent: rebuilding a ColorScheme allocates dozens of colours
    // and invalidates every composable that reads MaterialTheme, so doing it on
    // each recomposition drags the whole tree along with the seek bar.
    val scheme = remember(animatedAccent) {
        darkColorScheme(
            primary = animatedAccent,
            onPrimary = Color(0xFF0B0D10),
            primaryContainer = GlassFill,
            onPrimaryContainer = Ink,
            secondary = InkDim,
            // See the note above: the backdrop shows through this.
            background = Color.Transparent,
            onBackground = Ink,
            // Glass, not a card: a translucent white film is what every surface
            // in this design is made of, and the refraction on top of it comes
            // from the backdrop library rather than from the colour.
            surface = GlassFill,
            onSurface = Ink,
            surfaceVariant = GlassFillStrong,
            onSurfaceVariant = InkDim,
            outlineVariant = Color.White.copy(alpha = 0.16f),
        )
    }

    // System bar icons have to flip with the theme; left alone they are drawn
    // for the system's own theme and disappear against ours.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as android.app.Activity).window
            // Always light icons: the bars sit over the darkened artwork, not
            // over the system's idea of a background.
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(colorScheme = scheme, typography = SpotTypography, content = content)
}

private val DarkBase = Color(0xFF0D0E11)
private val LightBase = Color(0xFFF1F2F6)

/** The film every glass surface is tinted with. */
private val GlassFill = Color.White.copy(alpha = 0.10f)
private val GlassFillStrong = Color.White.copy(alpha = 0.16f)

/** Text and icons, fixed light — see the note on [SquareTheme]. */
val Ink = Color(0xFFF7F8FA)
val InkDim = Color(0xFFF7F8FA).copy(alpha = 0.66f)

/**
 * Fallback accent, used until artwork provides one.
 *
 * Muted lilac rather than a green: Spotify's brand colour is 0xFF1DB954, and
 * anything near it makes the app read as a clone no matter how the rest is laid
 * out.
 */
private val DefaultAccent = Color(0xFF7C5CE6)

/** Brighten until the colour reads against a near-black background. */
private fun Color.liftFor(background: Color): Color {
    val floor = background.luminance() + 0.16f
    if (luminance() >= floor) return this
    val amount = ((floor - luminance()) * 2f).coerceIn(0f, 1f)
    return Color(
        red = red + (1f - red) * amount,
        green = green + (1f - green) * amount,
        blue = blue + (1f - blue) * amount,
    )
}

/** Darken until the colour reads against a near-white background. */
private fun Color.deepenFor(background: Color): Color {
    val ceiling = background.luminance() - 0.42f
    if (luminance() <= ceiling) return this
    val amount = ((luminance() - ceiling) * 1.1f).coerceIn(0f, 0.8f)
    return Color(
        red = red * (1f - amount),
        green = green * (1f - amount),
        blue = blue * (1f - amount),
    )
}

/**
 * The wide, soft, low-opacity drop shadow this design leans on.
 *
 * Elevation is the only depth cue left once colour is gone, so it does the work
 * that an accent used to: cards, covers and the mini player read as separate
 * layers rather than as regions of one flat sheet. Kept very diffuse — a tight
 * shadow at this size looks like a border and flattens the effect.
 */
fun Modifier.softShadow(
    shape: Shape,
    elevation: Dp = 18.dp,
    ambient: Float = 0.10f,
    spot: Float = 0.13f,
): Modifier = shadow(
    elevation = elevation,
    shape = shape,
    clip = false,
    ambientColor = Color.Black.copy(alpha = ambient),
    spotColor = Color.Black.copy(alpha = spot),
)

/**
 * Editorial rather than utilitarian: a large, tightly tracked display face
 * against small wide-tracked labels. The contrast between the two is what makes
 * a screen read as designed instead of as a list of controls.
 */
private val SpotTypography = Typography(
    displayLarge = TextStyle(
        fontSize = 34.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-1.2).sp,
    ),
    headlineLarge = TextStyle(
        fontSize = 27.sp,
        lineHeight = 31.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.8).sp,
    ),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontSize = 15.5.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.1).sp),
    bodyMedium = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
)
