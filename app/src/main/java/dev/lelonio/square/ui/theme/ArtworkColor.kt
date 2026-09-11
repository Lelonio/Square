package dev.lelonio.square.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.lelonio.square.ui.components.canonicalArtworkKey

/**
 * Where a page ends up once the artwork's colour has faded out of it.
 *
 * Not pure black: a page that ends on #000 against a phone's own black bezel
 * has no bottom edge, and the last shelf on it looks like it is falling off.
 */
val PageFloor = Color(0xFF0A0A0C)

/**
 * And where it ends up on the other side, when the system is set to light.
 *
 * Not pure white, for the same reason the floor is not pure black: a page that
 * ends on #FFF has no edge against a white status bar or a white sheet over it.
 */
val PageCeiling = Color(0xFFF1F1F5)

/**
 * The same colour, taken up towards paper instead of down towards the floor.
 *
 * Mixing towards a neutral ceiling is what this did first, and it bleaches:
 * a page that measured 45% saturated on the reference came out at 14%, which is
 * a grey with a rumour of pink in it rather than the record's colour. Holding
 * the hue, easing the saturation down by as much as the mix asks for and
 * setting the brightness where paper belongs keeps a light page recognisably
 * the colour of what is playing.
 */
private fun liftedTone(colour: Color, mix: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(colour.toArgb(), hsv)
    return Color(
        android.graphics.Color.HSVToColor(
            floatArrayOf(
                hsv[0],
                (hsv[1] * (1f - mix * 0.55f)).coerceIn(0.08f, 0.46f),
                (0.97f - mix * 0.10f).coerceIn(0.82f, 0.97f),
            ),
        ),
    )
}

/**
 * The ink a surface needs, given the colour it is actually sitting on.
 *
 * Two different things decide ink in this app, and conflating them is what put
 * white letters on a pale pink page. The app's own chrome — bars, sheets, the
 * pages made of shelves — follows the system's setting. A record's page and the
 * player do not: those are the colour of the artwork, chosen by the catalogue
 * or read off the picture, and that colour is as dark as it is whichever way
 * the phone is set. What can be read on it is decided by it, not by the phone.
 */
fun inkOn(ground: Color): Color =
    if (ground.luminance() > 0.42f) Color(0xFF15161A) else Color(0xFFF7F8FA)

/**
 * An ink that overrides the theme's, for the surfaces that own their colour.
 *
 * Provided by a record's page and by the player, read by [Ink] — so every
 * caller that already asks for the app's ink gets the right one on those
 * screens without knowing anything about them.
 */
val LocalInkOverride = androidx.compose.runtime.compositionLocalOf<Color?> { null }

/** Which way this app's colours are being taken; see SquareTheme. */
@Composable
fun lightPage(): Boolean = LocalLightTheme.current

/**
 * What a scrim is made of on this side of the app.
 *
 * A scrim exists to put distance between a picture and the text over it, and
 * which way that distance goes depends on the ink: black under light ink, white
 * under dark. The same gradient with this colour in it works both ways round.
 */
@Composable
fun scrimColor(): Color = if (lightPage()) Color.White else Color.Black

/** The colour every page's own colour is mixed towards. */
@Composable
fun pageGround(): Color = if (lightPage()) PageCeiling else PageFloor

/**
 * The page tone for a cover.
 *
 * The dominant colour arrives at full saturation — it has to, it is picked to
 * identify the artwork — and using it as a background would put text on a
 * fluorescent field. Most of the way to the floor keeps the hue recognisable
 * and nothing else. A null cover falls back to the floor rather than to grey.
 */
@Composable
fun pageColorFor(accent: Color?): Color {
    val ground = pageGround()
    // Less of the ground on the light side. Taken as far towards paper as it is
    // taken towards the floor, a colour arrives as a pastel with no record left
    // in it — and the whole point of these pages is that they are the colour of
    // what is playing.
    if (accent == null) return ground
    return if (lightPage()) {
        liftedTone(accent, 0.62f)
    } else {
        androidx.compose.ui.graphics.lerp(accent, ground, 0.7f)
    }
}

/**
 * The page tone the other catalogue chose for a record, as six hex digits.
 *
 * Kept apart from [pageColorFor] and mixed much lighter, because the two
 * sources are not the same kind of colour. A dominant colour is picked out of a
 * photograph at full strength and has to be taken most of the way down before
 * text can sit on it; this one was chosen by somebody as the ground for a page,
 * so taking it that far down would throw away the choice — and it is what makes
 * a record's page read as coloured rather than as dark grey.
 *
 * Null, or anything unparseable, ends on the floor like everything else.
 */
@Composable
fun pageColorForHex(hex: String?, lift: Boolean = true): Color {
    // A surface that owns its colour mixes towards the floor whichever way the
    // phone is set. Reading the ground from the theme is what put a page the
    // catalogue files as #311a1a on screen at (125,108,109): the mix was right
    // and it was being made towards paper.
    val ground = if (lift) pageGround() else PageFloor
    val picked = hex
        ?.let { runCatching { Color(android.graphics.Color.parseColor("#$it")) }.getOrNull() }
        ?: return ground
    return when {
        lift && lightPage() -> liftedTone(picked, 0.34f)
        // Barely touched where the page owns it: the catalogue chose this
        // colour to be a page and the reference draws it as it is. The little
        // that is taken off is for the ink, which has to sit on it.
        !lift -> androidx.compose.ui.graphics.lerp(picked, ground, 0.12f)
        else -> androidx.compose.ui.graphics.lerp(picked, ground, 0.42f)
    }
}

/**
 * The dominant colour of an artwork URL, for seeding the theme.
 *
 * Results are memoised per URL: the same cover is asked for by the mini player,
 * the full player and the theme at once, and decoding a bitmap three times per
 * track change is wasteful.
 */
@Composable
fun rememberArtworkColor(artworkUrl: String?): State<Color?> {
    val context = LocalContext.current
    val state = remember { mutableStateOf<Color?>(null) }

    LaunchedEffect(artworkUrl) {
        if (artworkUrl == null) {
            state.value = null
            return@LaunchedEffect
        }
        val key = canonicalArtworkKey(artworkUrl)
        cached[key]?.let {
            state.value = it
            return@LaunchedEffect
        }
        state.value = extractDominant(context, artworkUrl)?.also { cached[key] = it }
    }
    return state
}

private val cached = object : LinkedHashMap<String, Color>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, Color>?) = size > 64
}

/**
 * Works out a cover's colour before anything asks for it.
 *
 * The picture and the colour it tints the page with are two different reads of
 * the same file, and only the first of them is prefetched with the song that is
 * coming. Left alone, the artwork arrived out of memory in a single frame while
 * its colour was still being extracted — the cover changed, and the page caught
 * up with it a moment later. This puts the answer in the same place the screen
 * will look for it, at the same time as the picture.
 */
suspend fun warmArtworkColor(context: Context, url: String) {
    val key = canonicalArtworkKey(url)
    if (!cached.containsKey(key)) {
        extractDominant(context, url)?.let { cached[key] = it }
    }
    // And the foot of it, for the same reason and off the same decode: the
    // player's whole background is that colour, so arriving without it is the
    // screen changing twice.
    if (!cachedFeet.containsKey(key)) {
        extractFoot(context, url)?.let { cachedFeet[key] = it }
    }
}

/**
 * A handful of colours from an artwork, for something that has to move.
 *
 * [rememberArtworkColor] answers with one colour because a theme needs one.
 * An animation needs several or it has nothing to travel between, and they
 * have to come from the same picture or the movement stops being the cover's.
 * Empty until the cover has been read, and empty for good if it cannot be.
 */
@Composable
fun rememberArtworkPalette(artworkUrl: String?): State<List<Color>> {
    val context = LocalContext.current
    val state = remember { mutableStateOf<List<Color>>(emptyList()) }

    LaunchedEffect(artworkUrl) {
        if (artworkUrl == null) {
            state.value = emptyList()
            return@LaunchedEffect
        }
        val key = canonicalArtworkKey(artworkUrl)
        cachedPalettes[key]?.let {
            state.value = it
            return@LaunchedEffect
        }
        val swatches = extractPalette(context, artworkUrl)
        if (swatches.isNotEmpty()) cachedPalettes[key] = swatches
        state.value = swatches
    }
    return state
}

private val cachedPalettes = object : LinkedHashMap<String, List<Color>>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, List<Color>>?) = size > 32
}

private suspend fun extractPalette(context: Context, url: String): List<Color> =
    withContext(Dispatchers.IO) {
        runCatching {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(96)
                .allowHardware(false)
                .build()

            val bitmap = (context.imageLoader.execute(request) as? SuccessResult)
                ?.drawable
                ?.let { (it as? android.graphics.drawable.BitmapDrawable)?.bitmap }
                ?: return@runCatching emptyList()

            val palette = Palette.from(bitmap).clearFilters().generate()
            // In this order on purpose: the vivid ones lead, the muted ones fill
            // in behind them, and a cover that is all one colour still answers
            // with something rather than nothing.
            listOfNotNull(
                palette.vibrantSwatch,
                palette.lightVibrantSwatch,
                palette.darkVibrantSwatch,
                palette.mutedSwatch,
                palette.darkMutedSwatch,
                palette.dominantSwatch,
            )
                .map { Color(it.rgb) }
                .distinct()
                .take(4)
        }.getOrDefault(emptyList())
    }

private suspend fun extractDominant(context: Context, url: String): Color? =
    withContext(Dispatchers.IO) {
        runCatching {
            val request = ImageRequest.Builder(context)
                .data(url)
                // Palette only needs colour proportions, so decode small: a
                // full-size cover costs far more memory for the same answer.
                .size(96)
                .allowHardware(false)
                .build()

            val bitmap = (context.imageLoader.execute(request) as? SuccessResult)
                ?.drawable
                ?.let { (it as? android.graphics.drawable.BitmapDrawable)?.bitmap }
                ?: return@runCatching null

            val palette = Palette.from(bitmap).clearFilters().generate()
            val rgb = palette.vibrantSwatch?.rgb
                ?: palette.lightVibrantSwatch?.rgb
                ?: palette.mutedSwatch?.rgb
                ?: palette.dominantSwatch?.rgb
                ?: return@runCatching null

            Color(rgb)
        }.getOrNull()
    }

/**
 * The colour the bottom of a cover actually is.
 *
 * [rememberArtworkColor] answers with the colour that *identifies* a record —
 * the loudest thing in it, wherever it happens to sit. That is right for
 * tinting a page and wrong for continuing a picture: a sleeve whose lower half
 * is a sunset identifies as the bright pink at the top of it, so a screen
 * ending on that pink has the picture and the ground disagreeing across the
 * seam even when the fade itself is perfect.
 *
 * This reads the band the fade actually meets, so the screen below the artwork
 * carries on from where the artwork left off — which is what the reference is
 * doing, and why its fade reads as the cover continuing rather than as the
 * cover ending.
 *
 * Averaged around the hue circle rather than in RGB. A straight mean of a busy
 * band is grey — opposite hues cancel — so the hue is a vector mean weighted by
 * how colourful each pixel is, and saturation and brightness are means of their
 * own, clamped to what a background can be.
 */
@Composable
fun rememberArtworkFootColor(artworkUrl: String?): State<Color?> {
    val context = LocalContext.current
    val state = remember { mutableStateOf<Color?>(null) }

    LaunchedEffect(artworkUrl) {
        if (artworkUrl == null) {
            state.value = null
            return@LaunchedEffect
        }
        val key = canonicalArtworkKey(artworkUrl)
        cachedFeet[key]?.let {
            state.value = it
            return@LaunchedEffect
        }
        state.value = extractFoot(context, artworkUrl)?.also { cachedFeet[key] = it }
    }
    return state
}

private val cachedFeet = object : LinkedHashMap<String, Color>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, Color>?) = size > 64
}

/**
 * The ground colour for a picture that is meant to carry on into it.
 *
 * Mixed down far less than [pageColorFor] does. That one is taming a colour
 * picked for being loud; this one is already the colour of a piece of the
 * picture, and taking it most of the way to the floor would undo the only
 * thing it was read for.
 */
@Composable
fun footToneFor(foot: Color?, mix: Float = 0.34f, lift: Boolean = true): Color {
    val ground = if (lift) pageGround() else PageFloor
    if (foot == null) return ground
    return if (lift && lightPage()) {
        liftedTone(foot, mix)
    } else {
        androidx.compose.ui.graphics.lerp(foot, ground, mix)
    }
}

/** How much of the cover, from the bottom, counts as the band the fade meets. */
private const val FOOT_BAND = 0.18f

private suspend fun extractFoot(context: Context, url: String): Color? =
    withContext(Dispatchers.IO) {
        runCatching {
            val request = ImageRequest.Builder(context)
                // The same request the dominant colour makes, so the two share
                // one decode and one cache entry.
                .data(url)
                .size(96)
                .allowHardware(false)
                .build()

            val bitmap = (context.imageLoader.execute(request) as? SuccessResult)
                ?.drawable
                ?.let { (it as? android.graphics.drawable.BitmapDrawable)?.bitmap }
                ?: return@runCatching null

            val height = bitmap.height
            val width = bitmap.width
            val from = (height * (1f - FOOT_BAND)).toInt().coerceIn(0, height - 1)

            var x = 0.0
            var y = 0.0
            var saturation = 0f
            var value = 0f
            var weight = 0f
            var count = 0
            val hsv = FloatArray(3)

            for (py in from until height) {
                for (px in 0 until width) {
                    android.graphics.Color.colorToHSV(bitmap.getPixel(px, py), hsv)
                    // Grey pixels still count towards the hue, but barely: the
                    // small constant is what keeps an all-grey band from
                    // dividing by nothing.
                    val w = hsv[1] * hsv[2] + 0.05f
                    val radians = Math.toRadians(hsv[0].toDouble())
                    x += kotlin.math.cos(radians) * w
                    y += kotlin.math.sin(radians) * w
                    saturation += hsv[1] * w
                    value += hsv[2]
                    weight += w
                    count++
                }
            }
            if (count == 0 || weight <= 0f) return@runCatching null

            val hue = ((Math.toDegrees(kotlin.math.atan2(y, x)).toFloat()) + 360f) % 360f
            Color(
                android.graphics.Color.HSVToColor(
                    floatArrayOf(
                        hue,
                        // Floors and ceilings, not the raw means: a band that
                        // is nearly white gives a background nothing can be
                        // read on, and one that is nearly black gives back the
                        // flat panel this whole treatment removed.
                        (saturation / weight).coerceIn(0.16f, 0.85f),
                        (value / count).coerceIn(0.18f, 0.72f),
                    ),
                ),
            )
        }.getOrNull()
    }
