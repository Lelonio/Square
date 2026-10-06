package dev.lelonio.square.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
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
 * The page is the record that is playing — a colour taken from the artwork,
 * never a flat sheet — and the system's setting decides which way that colour
 * is taken. Dark takes it down towards the floor and puts light ink and a dark
 * film of glass on it; light takes the same colour up towards paper and puts
 * dark ink and a white film on it. One palette, read in two directions, rather
 * than two palettes.
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
/**
 * Which side the app is on, said plainly.
 *
 * Read off the scheme's own surface at first, and that was wrong in a way that
 * only showed on the other side: every film in this design is a translucent
 * *white*, so `surface.luminance()` is 1.0 in both settings — alpha is not part
 * of luminance. Everything keyed on it would have called the dark theme light.
 */
val LocalLightTheme = staticCompositionLocalOf { false }

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
    val scheme = remember(animatedAccent, darkTheme) {
        // The ink and the film are the two things that change side, and both are
        // read back out of the scheme everywhere else in the app: `Ink` is the
        // scheme's own onSurface, and the glass takes a white film whenever the
        // scheme's surface is a light colour. So this is the only place that
        // knows which way round the app is.
        val ink = if (darkTheme) DarkInk else LightInk
        val film = if (darkTheme) DarkFilm else LightFilm
        val filmStrong = if (darkTheme) DarkFilmStrong else LightFilmStrong
        if (darkTheme) {
            darkColorScheme(
                primary = animatedAccent,
                onPrimary = Color(0xFF0B0D10),
                primaryContainer = film,
                onPrimaryContainer = ink,
                secondary = ink.copy(alpha = 0.66f),
                // See the note above: the backdrop shows through this.
                background = Color.Transparent,
                onBackground = ink,
                // Glass, not a card: a translucent film is what every surface in
                // this design is made of, and the refraction on top of it comes
                // from the backdrop library rather than from the colour.
                surface = film,
                onSurface = ink,
                surfaceVariant = filmStrong,
                onSurfaceVariant = ink.copy(alpha = 0.66f),
                outlineVariant = Color.White.copy(alpha = 0.16f),
            )
        } else {
            lightColorScheme(
                primary = animatedAccent,
                onPrimary = Color(0xFFFFFFFF),
                primaryContainer = film,
                onPrimaryContainer = ink,
                secondary = ink.copy(alpha = 0.66f),
                background = Color.Transparent,
                onBackground = ink,
                surface = film,
                onSurface = ink,
                surfaceVariant = filmStrong,
                onSurfaceVariant = ink.copy(alpha = 0.66f),
                outlineVariant = Color.Black.copy(alpha = 0.14f),
            )
        }
    }

    // System bar icons have to flip with the theme; left alone they are drawn
    // for the system's own theme and disappear against ours.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as android.app.Activity).window
            // Dark icons over a light page and light ones over a dark page:
            // the bars sit over the artwork's own colour, which is taken in
            // whichever direction the system asked for.
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalLightTheme provides !darkTheme) {
        MaterialTheme(colorScheme = scheme, typography = SpotTypography, content = content)
    }
}

private val DarkBase = Color(0xFF0D0E11)
private val LightBase = Color(0xFFF1F2F6)

/**
 * The film every glass surface is tinted with.
 *
 * White over a dark page and white-with-more-of-it over a light one: the
 * material is the same, and what changes is how much of the page is left
 * showing through. The glass code decides its own tint from whether this colour
 * is light or dark, so these are also the switch for that; see GlassEffect.
 */
private val DarkFilm = Color.White.copy(alpha = 0.10f)
private val DarkFilmStrong = Color.White.copy(alpha = 0.16f)
private val LightFilm = Color.White.copy(alpha = 0.55f)
private val LightFilmStrong = Color.White.copy(alpha = 0.72f)

/** Text and icons, on whichever side of the page they have to be read from. */
private val DarkInk = Color(0xFFF7F8FA)
private val LightInk = Color(0xFF15161A)

/**
 * The colour text and icons are drawn in.
 *
 * A composable getter rather than a constant, so the eighty-odd places that
 * name it did not have to learn about the theme: it is the scheme's own
 * onSurface, which is light over a dark page and dark over a light one.
 */
val Ink: Color
    @Composable get() = LocalInkOverride.current ?: MaterialTheme.colorScheme.onSurface

/**
 * The app's own ink, whatever the page around it has done to the theme: for a
 * surface that keeps the app's colours on a page coloured by its cover — a
 * menu, which is a light pane on the light side and a dark one on the dark.
 */
val AppInk: Color
    @Composable get() = if (lightPage()) LightInk else DarkInk

/** The same, for what is said quietly. */
val InkDim: Color
    @Composable get() = Ink.copy(alpha = 0.66f)

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

/**
 * Darken until the colour reads against a near-white background.
 *
 * Taken further than it was. At the old ceiling a mid-tan came out of this
 * barely touched, and the accent is spent on exactly the things that have to be
 * found at a glance — the lit tab, the play button, the row that is playing —
 * so "technically darker than the page" is not enough for it.
 */
private fun Color.deepenFor(background: Color): Color {
    val ceiling = background.luminance() - 0.62f
    if (luminance() <= ceiling) return this
    val amount = ((luminance() - ceiling) * 1.6f).coerceIn(0f, 0.86f)
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
        fontFamily = Inter,
        fontSize = 34.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-1.2).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = Inter,
        fontSize = 27.sp,
        lineHeight = 31.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.8).sp,
    ),
    titleLarge = TextStyle(fontFamily = Inter, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = Inter, fontSize = 15.5.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.1).sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontSize = 13.5.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontFamily = Inter, fontSize = 12.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontFamily = Inter, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
)
