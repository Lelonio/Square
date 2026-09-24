package dev.lelonio.square.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.SuccessResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.request.ImageRequest

/**
 * A picture that stops being a picture on its way down the screen.
 *
 * Two copies of the same image: the sharp one, and over its lower half a
 * blurred one that fades in, and then the page's own colour arriving under
 * that. It is what makes a photograph dissolve into a page instead of ending —
 * a straight fade to a flat colour leaves the image crisp right up to the point
 * where it vanishes, which reads as a photo behind a curtain.
 *
 * Shared by the pages that are made of one picture — an artist, a record, a
 * list — and by the player when the track has nothing moving to show. One
 * treatment, so the cover looks the same wherever the app puts it up large.
 *
 * Blurred at decode time rather than with `Modifier.blur`: that modifier is a
 * render effect over the whole layer, re-run on every frame the layer changes,
 * and this sits under everything.
 */
@Composable
fun HeroBackdrop(
    artworkUrl: String?,
    /** Named for the description a reader hears; the picture is decorative. */
    title: String,
    /** The tone the picture ends on, which is the page's own. */
    pageColor: Color,
    modifier: Modifier = Modifier,
    /** The cover as a moving picture, where there is one; see [MotionCover]. */
    motionUrl: String? = null,
    /**
     * Whether the picture is still being looked up.
     *
     * While it is, nothing stands in for it: see [Artwork]'s `fallback`. The
     * page colour is already drawn under this, so the wait reads as a header
     * that has not finished arriving rather than as a song with no cover.
     */
    pending: Boolean = false,
    /** Where the blurred copy starts and where it is complete, top to bottom. */
    softenFrom: Float = 0.42f,
    softenTo: Float = 0.72f,
    /**
     * Whether the picture ends on the page's colour, or on itself.
     *
     * A page is a picture with a page under it: the artwork gives way to the
     * tone taken from it, and everything below is flat. A player is not — the
     * reference fills the whole screen with the same picture, blurred and
     * enlarged, and lets the sharp copy dissolve into it. Nothing is covered,
     * so there is no line where the covering starts, and that is the whole of
     * why it reads as one image rather than as an image and a panel.
     *
     * With this false the blurred copy is left unmasked, and the colour stays a
     * scrim for legibility instead of taking over.
     */
    fadeToPage: Boolean = true,
    /**
     * The picture's own proportions, when it is to be shown at them.
     *
     * Null crops it to fill whatever it is given, which is right for a header
     * measured in advance. A number lays it across the top at that ratio
     * instead, untouched: a picture drawn for a record is a composition, and
     * cropping it to a phone's shape enlarges it and cuts the sides off the
     * thing somebody framed.
     */
    imageAspect: Float? = null,
    /**
     * Whether the picture carries on down the screen past where it fades.
     *
     * Where it ends on itself, what showed below it was the blurred, darkened
     * copy of the whole cover that the app keeps behind every screen: its
     * colours are the average of the picture, not the bottom of it, so under
     * the fade the cover turned into a flat field of some other colour. With
     * this on, the picture's last visible row is drawn on down to the bottom
     * of the screen, soft across and deepening towards the controls, and the
     * fade dissolves into its own continuation. See [PictureExtension].
     */
    extendPicture: Boolean = false,
    /**
     * How much the picture is enlarged in its slot, from the top and centred
     * across: 1 shows it whole.
     *
     * For the tall pictures the other catalogue draws for its own player,
     * which come already dissolving into a blur about seven tenths of the way
     * down, where that player's controls go. Shown whole, that blur arrived
     * well before this screen's own fade and read as the cover cut off with a
     * dead band under it; enlarged by [TALL_ART_ZOOM], the two fades are the
     * same one.
     */
    zoom: Float = 1f,
) {
    // Black over a dark page and white over a light one; see scrimColor.
    val scrim = dev.lelonio.square.ui.theme.scrimColor()
    // What the moving cover is showing now, for the blur and the extension;
    // empty, and the still used, until it has shown something.
    val motion = remember(motionUrl) { MotionFrames() }
    // Every copy of the picture this draws, loaded together and swapped
    // together. Each used to load on its own, and the small ones arrived
    // first: on a change of song the extension and the blur were already the
    // next cover's colours under the previous cover, which had not finished
    // loading at full size.
    val context = LocalContext.current
    val extended = extendPicture && !fadeToPage
    val art by produceState<ArtSet?>(null, artworkUrl, zoom, extended) {
        if (artworkUrl == null) {
            value = null
            return@produceState
        }
        value = coroutineScope {
            val cover = async { loadArt(context, artworkUrl, COVER_PX) }
            val edge = async { if (extended) loadArt(context, artworkUrl, EXTENSION_PX) else null }
            val blur = async { loadArt(context, artworkUrl, HERO_BLUR_PX, blurred = true) }
            cover.await()?.let { ArtSet(it, edge.await(), blur.await(), zoom) }
        }
    }
    Box(modifier) {
        if (extended && artworkUrl != null) {
            PictureExtension(
                art = art,
                motion = motion,
                imageAspect = imageAspect,
                softenFrom = softenFrom,
                softenTo = softenTo,
            )
        }
        if (imageAspect != null && fadeToPage) {
            // The colour first, because the picture no longer covers the slot.
            //
            // Only where the picture is meant to end on it. On the player it is
            // not: an opaque panel here is precisely what the blend is against,
            // and it was hiding the blurred copy of the artwork that fills that
            // screen — which is the thing the sharp copy is supposed to
            // dissolve into.
            Box(Modifier.fillMaxSize().background(pageColor))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .then(
                    if (imageAspect != null) {
                        Modifier.pictureSlot(imageAspect)
                    } else {
                        Modifier.fillMaxSize()
                    },
                )
                .align(Alignment.TopCenter)
                .then(
                    // Where the picture ends on itself, the whole slot is what
                    // fades — not just the sharp copy under the blurred one.
                    //
                    // Both copies used to stop dead at the slot's bottom edge,
                    // and the screen-wide blur behind them is a different
                    // enlargement of the same artwork, so the two met in a line
                    // across the player. Erasing the slot before it ends hands
                    // the picture over to that blur instead of butting against
                    // it.
                    if (fadeToPage) {
                        Modifier
                    } else {
                        Modifier
                            .graphicsLayer {
                                compositingStrategy = CompositingStrategy.Offscreen
                            }
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        *if (extendPicture) {
                                            fading(1f - SLOT_FADE, 1f)
                                        } else {
                                            fading(softenFrom, softenTo)
                                        },
                                    ),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                    },
                ),
        ) {
        CoverFill(art = art, modifier = Modifier.fillMaxSize())

        // The moving cover over the still one, which stays underneath as what is
        // shown until the first frame arrives.
        if (motionUrl != null) {
            MotionCover(
                url = motionUrl,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    },
                onFrame = motion::take,
            )
        }

        if (artworkUrl != null) {
            Crossfade(
                targetState = art,
                animationSpec = tween(SWAP_MS),
                label = "coverBlur",
                modifier = Modifier.fillMaxSize(),
            ) { shown ->
            val blur = shown?.blur ?: return@Crossfade
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Its own layer, so the mask below erases this copy alone
                    // and not the sharp one underneath it.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        // The moving cover's own frame when it is playing, so
                        // the blur is of what is on screen rather than of the
                        // still it started from.
                        val frame = motion.latest.value
                        drawCropped(frame?.soft ?: blur, shown.zoom)
                        drawRect(
                            // Eased rather than straight, and started well
                            // before the colour does.
                            //
                            // A linear ramp between two stops is a band: the eye
                            // finds both ends of it, and what should read as a
                            // picture dissolving reads as a picture with a strip
                            // over it. These are the same two ends with the
                            // middle bent — slow to leave, quick through the
                            // middle, slow to arrive — which is the shape a fade
                            // has to have before it stops looking like a shape.
                            brush = Brush.verticalGradient(
                                // Ahead of the slot's own fade where the
                                // picture ends on itself: the copy that
                                // dissolves has to be blurred *before* it
                                // starts going, or what dissolves is a sharp
                                // photograph and the eye follows it down.
                                *if (fadeToPage) {
                                    softening(softenFrom, softenTo, Color.Black)
                                } else if (extendPicture) {
                                    // Just behind the fade rather than a third
                                    // of the way up: the picture stays sharp
                                    // down to where it gives way.
                                    softening(
                                        1f - SLOT_FADE - SLOT_BLUR_LEAD,
                                        1f - SLOT_FADE + SLOT_BLUR_TAIL,
                                        Color.Black,
                                    )
                                } else {
                                    softening(
                                        (softenFrom - 0.16f).coerceAtLeast(0f),
                                        softenFrom + 0.03f,
                                        Color.Black,
                                    )
                                },
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            )
            }
        }

        // And the colour, arriving under the blur. It ends on the page's own
        // tone rather than on black: the last row of the picture and the first
        // row of the background are then the same colour, which is what leaves
        // no seam to find.
        if (fadeToPage) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        // Tied to where the picture is being softened rather
                        // than to fixed fractions. With them, a picture asked to
                        // stay sharp most of the way down was still being washed
                        // out from halfway — the colour arrived on its own
                        // schedule and paled the very part that was meant to be
                        // legible.
                        0f to scrim.copy(alpha = 0.22f),
                        (softenFrom * 0.45f) to Color.Transparent,
                        // The colour comes up behind the blur rather than with
                        // it, and on the same eased curve. The picture going
                        // soft first is what makes the two read as one surface
                        // blending into the page instead of an image being
                        // covered by a panel.
                        *softening(
                            from = softenFrom + (softenTo - softenFrom) * COLOUR_LAG,
                            to = softenTo,
                            colour = pageColor,
                        ),
                        // Opaque at the end of the softening, not nearly
                        // opaque: the last few percent of the picture were
                        // showing through, and a picture that is still faintly
                        // there has an edge — which is the seam this whole
                        // gradient exists to remove.
                        1f to pageColor,
                    ),
                ),
        )
        }
        }

        // Where the picture ends on itself, the scrim spans the *screen* and
        // not the picture.
        //
        // Kept inside the picture's own slot, it stopped at the slot's bottom
        // edge — and a shade that stops is a line, which is the one thing this
        // whole treatment exists to avoid. Measured on the frame it is drawn
        // in, so the picture's foot is wherever the phone actually put it.
        if (!fadeToPage) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        val ends = imageAspect
                            ?.let { (size.width / it / size.height).coerceIn(0f, 1f) }
                            ?: 1f
                        // The last two stops collapse onto each other where the
                        // picture reaches the bottom of its own box, which is a
                        // hard edge rather than a gradient.
                        val stops = buildList {
                            add(0f to scrim.copy(alpha = 0.20f))
                            add((ends * 0.45f) to Color.Transparent)
                            add((ends * 0.92f) to scrim.copy(alpha = 0.05f))
                            if (ends < 0.999f) {
                                add(ends to scrim.copy(alpha = 0.12f))
                                add(1f to scrim.copy(alpha = 0.30f))
                            } else {
                                add(1f to scrim.copy(alpha = 0.12f))
                            }
                        }
                        drawRect(brush = Brush.verticalGradient(*stops.toTypedArray()))
                    },
            )
        }
    }
}

/**
 * A fade drawn as a curve rather than as a line.
 *
 * Compose's gradients interpolate straight between the stops they are given, so
 * a ramp from nothing to everything in one step has a corner at each end and a
 * flat middle — visible as a band across the picture. These are the same two
 * ends sampled along a smoothstep, which starts and ends flat and does its work
 * in the middle: the picture leaves gradually, gives way quickly where nobody is
 * reading it, and settles without an edge.
 */
private fun softening(
    from: Float,
    to: Float,
    colour: Color,
): Array<Pair<Float, Color>> {
    val steps = 8
    return Array(steps + 1) { index ->
        val t = index.toFloat() / steps
        val at = from + (to - from) * t
        // smoothstep: 3t² − 2t³
        val eased = t * t * (3f - 2f * t)
        at to colour.copy(alpha = colour.alpha * eased)
    }
}

/**
 * How far into the softening the colour starts, as a fraction of it.
 *
 * The picture blurs first and the page's colour arrives behind it. Together
 * they read as one surface changing; started at the same moment they read as
 * two things happening at once, which is what a hard fade looks like.
 */
private const val COLOUR_LAG = 0.22f

/** How long a better copy of the same picture takes to arrive. */
private const val SWAP_MS = 450

/** Decode size for the soft copy, in pixels. */
private const val HERO_BLUR_PX = 240

/** Soft enough to be a wash, sharp enough to keep the picture's shapes. */
private val HeroBlur = BlurTransformation(radius = 10, passes = 2)

/**
 * The same curve as [softening], run the other way.
 *
 * What leaves rather than what arrives: opaque above [from], gone by [to], with
 * the middle bent so neither end is a line.
 */
/**
 * The cover, filling the slot whatever shape it arrived in.
 *
 * Drawn here rather than handed to the image library with "crop to fill":
 * that is what it was, and a square sleeve still came out at its own height
 * with a band of nothing under it, so a square cover faded a third of the way
 * up the screen and a tall one faded at the controls. The bitmap is loaded and
 * put on the canvas at the size of the slot, taking the middle of whatever
 * does not fit, which cannot end anywhere but the slot's own foot.
 *
 * Cross-faded on a change of song, so a new cover arrives the way it did.
 */
@Composable
private fun CoverFill(art: ArtSet?, modifier: Modifier = Modifier) {
    Crossfade(
        targetState = art,
        animationSpec = tween(SWAP_MS),
        label = "cover",
        modifier = modifier,
    ) { shown ->
        if (shown == null) return@Crossfade
        Box(Modifier.fillMaxSize().drawBehind { drawCropped(shown.cover, shown.zoom) })
    }
}

/**
 * One picture as every copy of it is drawn, with the enlargement it was loaded
 * for: the one fading out keeps its own while the next arrives, since a tall
 * picture and a square scan are not enlarged alike.
 */
private class ArtSet(
    val cover: ImageBitmap,
    /** A tiny copy for the extension, where there is one. */
    val edge: ImageBitmap?,
    val blur: ImageBitmap?,
    val zoom: Float,
)

private suspend fun loadArt(
    context: android.content.Context,
    url: String,
    px: Int,
    blurred: Boolean = false,
): ImageBitmap? {
    val request = ImageRequest.Builder(context)
        // Offline, the file on the disk rather than the url.
        .data(artSource(url))
        .size(px)
        // Read back pixel by pixel: by the blur, and by the extension when
        // drawn stretched.
        .allowHardware(false)
        .apply { if (blurred) transformations(HeroBlur) }
        .build()
    val result = runCatching { context.imageLoader.execute(request) }.getOrNull()
    return (result as? SuccessResult)?.drawable?.toBitmap()?.asImageBitmap()
}

/**
 * How large the cover is decoded, in pixels on its longest side.
 *
 * Wider than any phone is, since it is drawn across the screen and then some:
 * the slot is a third taller than a square sleeve, so the middle of it is
 * enlarged by that much before it is seen.
 */
private const val COVER_PX = 1600


/**
 * The bottom of the picture, carried on to the bottom of the screen.
 *
 * A small copy of the cover, cropped the way the slot crops it, and the row of
 * it where the fade begins drawn on down the rest of the screen: each column
 * keeps its own colour, which is what makes it read as the picture going on
 * rather than as a tint. Blurred across, or every edge in that row became a
 * stripe the height of the screen; a little just under the picture, where it
 * has to match it, and more and more further down, where it only has to be
 * the picture's colours. It deepens towards the bottom, where the controls
 * need the contrast; see [ExtensionPicture].
 *
 * Cross-faded with the cover on a change of song, on the cover's own clock.
 */
@Composable
private fun PictureExtension(
    art: ArtSet?,
    motion: MotionFrames,
    imageAspect: Float?,
    softenFrom: Float,
    softenTo: Float,
) {
    Crossfade(
        targetState = art,
        animationSpec = tween(SWAP_MS),
        label = "pictureExtension",
        modifier = Modifier.fillMaxSize(),
    ) { shown ->
        val image = shown?.edge ?: return@Crossfade
        val imageZoom = shown.zoom
        val extension = remember { ExtensionPicture() }
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    // The slot, in this box's terms: across the top, as wide as
                    // the screen, at the picture's proportions.
                    val slotHeight =
                        if (imageAspect != null) size.width / imageAspect else size.height
                    val fadeFrom = if (imageAspect != null) 1f - SLOT_FADE else softenFrom
                    val fadeTo = if (imageAspect != null) 1f else softenTo
                    // Exactly where the fade begins: what the picture is at the
                    // point it starts to go is what it has to carry on as.
                    //
                    // It used to be read a little into the fade, and a cover's
                    // last stretch is very often something else — a border, a
                    // strip of text, a floor under the subject — so the
                    // screen below the cover took the colour of that band
                    // rather than of the picture the eye was following down.
                    // Its own last row where the fade is below it, since there
                    // is no picture down there to read.
                    val rowFraction = fadeFrom.coerceAtMost(0.995f)
                    // From the fade, or from the picture's bottom edge where
                    // the fade is further down than that: the stretch between
                    // the two is the picture carrying on, and it has to be
                    // drawn or the screen's own blurred background shows
                    // through the gap.
                    val top = (minOf(fadeFrom, 1f) * slotHeight).coerceIn(0f, size.height)
                    if (top >= size.height) return@drawBehind
                    // How much of what is drawn is that carrying on: held at
                    // the picture's own colours, so the softening begins where
                    // the controls do and not where the picture stopped.
                    val hold = if (size.height > top) {
                        ((fadeFrom * slotHeight - top) / (size.height - top)).coerceIn(0f, 0.9f)
                    } else {
                        0f
                    }

                    // The slot crops the picture to fill it, centred; so does this.
                    // The moving cover's frame while it plays; see MotionFrames.
                    val source = motion.latest.value?.sharp ?: image
                    val w = source.width
                    val h = source.height
                    val slotRatio = if (slotHeight > 0f) size.width / slotHeight else 1f
                    val shown = croppedTo(w, h, slotRatio, imageZoom)
                    val row = (shown.top + rowFraction * shown.height).roundToInt()
                        .coerceIn(shown.top, shown.bottom - 1)

                    val picture =
                        extension.at(source, row, shown.left, shown.width, shown.top, shown.bottom, hold)
                    drawImage(
                        image = picture,
                        dstOffset = IntOffset(0, top.roundToInt()),
                        dstSize = IntSize(size.width.roundToInt(), (size.height - top).roundToInt()),
                        filterQuality = FilterQuality.Low,
                    )
                },
        )
    }
}

/**
 * The extension as a picture of its own, a few dozen pixels in each direction,
 * drawn stretched to the screen, bilinear, which smooths what is left between
 * the samples.
 *
 * Its top row is the cover's row, barely softened, since that is where the two
 * meet. On the way down it is blurred across more and more, and drawn towards
 * the row's dominant colour: the one the eye takes the picture to be, weighted
 * to the vivid pixels, so a pink sky beside a white ramp comes out pink rather
 * than the grey their average would be. And it darkens on the way down by
 * taking its own colours down, a little more saturated as they go, rather
 * than by laying black over them, which turned everything under the controls
 * the same grey.
 *
 * Built once for each row the ending asks for, which changes only when the
 * controls move.
 */
private class ExtensionPicture {
    private var key = -1L
    private var from: ImageBitmap? = null
    private var built: ImageBitmap? = null

    fun at(
        source: ImageBitmap,
        row: Int,
        left: Int,
        width: Int,
        top: Int,
        bottom: Int,
        /**
         * The share of the picture, from its top, that is the cover carrying
         * on rather than the fade: held at the row's own colours, sharp
         * across and undarkened, so that what the eye finds is the controls
         * and not the edge of the cover.
         */
        hold: Float,
    ): ImageBitmap {
        val held = (hold * 100f).roundToInt().toLong()
        val wanted =
            (held shl 48) or (row.toLong() shl 32) or (left.toLong() shl 16) or width.toLong()
        built?.takeIf { key == wanted && from === source }?.let { return it }

        // A few rows averaged, all of them at or above the one asked for: a
        // single row can be a stroke that is nowhere else, but a row below it
        // is already the part of the picture that is fading, and at the
        // bottom of a cover that is so often a band of another colour.
        val bitmap = source.asAndroidBitmap()
        val sampled = FloatArray(width * 3)
        var rows = 0
        for (r in (row - EXTENSION_ROWS_ABOVE)..row) {
            if (r < top || r >= bottom) continue
            val line = IntArray(width)
            bitmap.getPixels(line, 0, width, left, r, width, 1)
            for (x in 0 until width) {
                val c = line[x]
                sampled[x * 3] += ((c shr 16) and 0xFF).toFloat()
                sampled[x * 3 + 1] += ((c shr 8) and 0xFF).toFloat()
                sampled[x * 3 + 2] += (c and 0xFF).toFloat()
            }
            rows++
        }
        for (i in sampled.indices) sampled[i] /= rows.coerceAtLeast(1)
        val dominant = dominantOf(sampled, width)

        val blurred = FloatArray(width * 3)
        val hsv = FloatArray(3)
        val out = IntArray(width * EXTENSION_ROWS)
        for (r in 0 until EXTENSION_ROWS) {
            // Counted from the end of the held stretch: above it the picture
            // is still itself, below it the fade runs its whole course as it
            // would have if it had started at the picture's edge.
            val down = r.toFloat() / (EXTENSION_ROWS - 1)
            val t = if (hold < 1f) ((down - hold) / (1f - hold)).coerceIn(0f, 1f) else 0f
            val sigma = (EXTENSION_NEAR + (EXTENSION_FAR - EXTENSION_NEAR) * t) * width
            blurAcross(sampled, width, sigma, blurred)
            // Into the dominant colour only once the extension is well clear
            // of the picture, and the darkening on the same eased curve.
            val toward = EXTENSION_CONVERGE * eased(((t - 0.2f) / 0.8f).coerceIn(0f, 1f))
            val deepen = EXTENSION_DEEPEN * eased(t)
            for (x in 0 until width) {
                val red = blurred[x * 3] + (dominant[0] - blurred[x * 3]) * toward
                val green = blurred[x * 3 + 1] + (dominant[1] - blurred[x * 3 + 1]) * toward
                val blue = blurred[x * 3 + 2] + (dominant[2] - blurred[x * 3 + 2]) * toward
                android.graphics.Color.RGBToHSV(
                    red.roundToInt().coerceIn(0, 255),
                    green.roundToInt().coerceIn(0, 255),
                    blue.roundToInt().coerceIn(0, 255),
                    hsv,
                )
                hsv[1] = (hsv[1] * (1f + EXTENSION_RICHER * deepen / EXTENSION_DEEPEN)).coerceAtMost(1f)
                hsv[2] = hsv[2] * (1f - deepen)
                out[r * width + x] = android.graphics.Color.HSVToColor(hsv)
            }
        }
        val picture = android.graphics.Bitmap
            .createBitmap(out, width, EXTENSION_ROWS, android.graphics.Bitmap.Config.ARGB_8888)
            .asImageBitmap()
        key = wanted
        from = source
        built = picture
        return picture
    }

    /**
     * The colour a row reads as: its pixels averaged, each counted by how
     * vivid it is, so a few greys and whites do not wash out the hue that is
     * actually there. A row with no colour in it at all comes out as its plain
     * average, which is what it is.
     */
    private fun dominantOf(rgb: FloatArray, width: Int): FloatArray {
        val hsv = FloatArray(3)
        var red = 0f
        var green = 0f
        var blue = 0f
        var total = 0f
        for (x in 0 until width) {
            android.graphics.Color.RGBToHSV(
                rgb[x * 3].roundToInt(),
                rgb[x * 3 + 1].roundToInt(),
                rgb[x * 3 + 2].roundToInt(),
                hsv,
            )
            val vivid = hsv[1] * hsv[2]
            val weight = 0.05f + vivid * vivid
            red += rgb[x * 3] * weight
            green += rgb[x * 3 + 1] * weight
            blue += rgb[x * 3 + 2] * weight
            total += weight
        }
        return floatArrayOf(red / total, green / total, blue / total)
    }

    /** A Gaussian across one row, the edges held rather than wrapped. */
    private fun blurAcross(rgb: FloatArray, width: Int, sigma: Float, out: FloatArray) {
        val radius = (sigma * 2.5f).roundToInt().coerceAtLeast(1)
        val weights = FloatArray(radius * 2 + 1) { i ->
            val d = (i - radius).toFloat()
            kotlin.math.exp(-(d * d) / (2f * sigma * sigma))
        }
        for (x in 0 until width) {
            var red = 0f
            var green = 0f
            var blue = 0f
            var total = 0f
            for (i in weights.indices) {
                val sx = (x + i - radius).coerceIn(0, width - 1)
                val w = weights[i]
                red += rgb[sx * 3] * w
                green += rgb[sx * 3 + 1] * w
                blue += rgb[sx * 3 + 2] * w
                total += w
            }
            out[x * 3] = red / total
            out[x * 3 + 1] = green / total
            out[x * 3 + 2] = blue / total
        }
    }

    /** smoothstep: 3t² − 2t³, the same ease as the fades above. */
    private fun eased(t: Float): Float = t * t * (3f - 2f * t)
}

/**
 * The moving cover's latest frame, as the blur and the extension want it.
 *
 * Both were made from the still the cover starts on, so while it moved they
 * showed the first frame's colours under whatever it had moved on to: a dark
 * blot of scenery under a sky. The cover hands over a small copy of each frame
 * it reads; this keeps it as it is, for the extension, and softened, for the
 * blurred copy the picture dissolves through.
 *
 * Not the frames as read, though, but a running blend of them. Taken one after
 * another they changed the colour under the cover fifteen times a second, and
 * wherever something vivid came into the picture or the loop started over the
 * whole field flashed. Each copy moves the blend a fifth of the way towards
 * itself, so the colours follow the cover a third of a second behind it, too
 * little to see as lag and enough to turn a cut into a change.
 *
 * Read only while drawing, so a new frame is a redraw and nothing more.
 */
private class MotionFrames {
    class Frame(val sharp: ImageBitmap, val soft: ImageBitmap)

    val latest = mutableStateOf<Frame?>(null)

    private var blend: FloatArray? = null
    private var blendWidth = 0
    private var blendHeight = 0

    fun take(bitmap: android.graphics.Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val kept = blend?.takeIf { blendWidth == width && blendHeight == height }
        val running = kept ?: FloatArray(pixels.size * 3).also {
            blend = it
            blendWidth = width
            blendHeight = height
        }
        // The first copy is taken as it is: there is nothing to blend it with.
        val follow = if (kept == null) 1f else FRAME_FOLLOW
        for (i in pixels.indices) {
            val c = pixels[i]
            running[i * 3] += (((c shr 16) and 0xFF) - running[i * 3]) * follow
            running[i * 3 + 1] += (((c shr 8) and 0xFF) - running[i * 3 + 1]) * follow
            running[i * 3 + 2] += ((c and 0xFF) - running[i * 3 + 2]) * follow
            pixels[i] = (0xFF shl 24) or
                (running[i * 3].roundToInt().coerceIn(0, 255) shl 16) or
                (running[i * 3 + 1].roundToInt().coerceIn(0, 255) shl 8) or
                running[i * 3 + 2].roundToInt().coerceIn(0, 255)
        }
        val smooth = android.graphics.Bitmap
            .createBitmap(pixels, width, height, android.graphics.Bitmap.Config.ARGB_8888)
        latest.value = Frame(smooth.asImageBitmap(), softened(smooth).asImageBitmap())
    }

    /**
     * About as soft as the still's blurred copy: that one is blurred by ten
     * pixels across two hundred and forty, and this is two passes of two
     * across forty-eight.
     */
    private fun softened(bitmap: android.graphics.Bitmap): android.graphics.Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val scratch = IntArray(pixels.size)
        repeat(2) {
            boxBlur(pixels, scratch, width, height, horizontal = true)
            boxBlur(scratch, pixels, width, height, horizontal = false)
        }
        return android.graphics.Bitmap.createBitmap(pixels, width, height, android.graphics.Bitmap.Config.ARGB_8888)
    }

    private fun boxBlur(from: IntArray, to: IntArray, width: Int, height: Int, horizontal: Boolean) {
        val radius = FRAME_SOFTEN
        for (y in 0 until height) {
            for (x in 0 until width) {
                var red = 0
                var green = 0
                var blue = 0
                var count = 0
                for (d in -radius..radius) {
                    val sx = if (horizontal) (x + d).coerceIn(0, width - 1) else x
                    val sy = if (horizontal) y else (y + d).coerceIn(0, height - 1)
                    val c = from[sy * width + sx]
                    red += (c shr 16) and 0xFF
                    green += (c shr 8) and 0xFF
                    blue += c and 0xFF
                    count++
                }
                to[y * width + x] = (0xFF shl 24) or
                    ((red / count) shl 16) or ((green / count) shl 8) or (blue / count)
            }
        }
    }
}

/** The radius of each pass of [MotionFrames]' blur, in the copy's pixels. */
private const val FRAME_SOFTEN = 2

/** How far each copy of a frame moves [MotionFrames]' blend towards itself. */
private const val FRAME_FOLLOW = 0.2f

/**
 * Draws [image] filling this area, cropped at the sides or the top and bottom
 * to its shape and centred, the way ContentScale.Crop lays out the still.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCropped(
    image: ImageBitmap,
    zoom: Float = 1f,
) {
    val shown = croppedTo(image.width, image.height, size.width / size.height, zoom)
    drawImage(
        image = image,
        srcOffset = shown.topLeft,
        srcSize = shown.size,
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        filterQuality = FilterQuality.Low,
    )
}

/**
 * How wide the copy the extension is read from is decoded.
 *
 * Large enough that the row where the fade begins is a row of its own: at 32
 * the last tenth of a cover was three rows, and the one read was whichever of
 * them the rounding landed on.
 */
private const val EXTENSION_PX = 128

/** Rows above the fade that are averaged into the one read; see ExtensionPicture.at. */
private const val EXTENSION_ROWS_ABOVE = 3

/** Rows in the extension's own picture, top to bottom of the screen. */
private const val EXTENSION_ROWS = 24

/**
 * How far the extension is blurred across, as a share of its width, just under
 * the picture and at the bottom of the screen.
 */
private const val EXTENSION_NEAR = 0.05f
private const val EXTENSION_FAR = 0.40f

/** How much of the way to the dominant colour the bottom of the screen goes. */
private const val EXTENSION_CONVERGE = 0.85f

/** How far the extension's colours are taken down by the bottom of the screen. */
private const val EXTENSION_DEEPEN = 0.40f

/** And how much richer they get as they darken, at the bottom. */
private const val EXTENSION_RICHER = 0.25f

/**
 * The slot the cover is drawn in: as tall as the picture's own proportions
 * make it, and never shorter than the ending the player measured.
 *
 * A picture taller than it is wide, which is what Apple's portrait covers are,
 * reaches past the controls on its own and is cut at the ending. A square one
 * runs out well above them, and the fade then happened where the picture
 * stopped rather than where the controls begin, which is not the same place
 * twice. The slot is stretched down to the ending instead and the cover fills
 * it, cropped at the sides, centred: a small enlargement, since the two are
 * close together, and the fade lands where it does for every other cover.
 */
/**
 * The part of a [w] by [h] picture that fills an area of shape [ratio]: cropped
 * to that shape and centred, then enlarged by [zoom] from the top and centred
 * across — see HeroBackdrop's `zoom`.
 */
private fun croppedTo(w: Int, h: Int, ratio: Float, zoom: Float): IntRect {
    val fit = if (w.toFloat() / h > ratio) {
        val visible = (h * ratio).roundToInt().coerceIn(1, w)
        IntRect(IntOffset((w - visible) / 2, 0), IntSize(visible, h))
    } else {
        val visible = (w / ratio).roundToInt().coerceIn(1, h)
        IntRect(IntOffset(0, (h - visible) / 2), IntSize(w, visible))
    }
    if (zoom <= 1f) return fit
    val width = (fit.width / zoom).roundToInt().coerceAtLeast(1)
    val height = (fit.height / zoom).roundToInt().coerceAtLeast(1)
    return IntRect(IntOffset(fit.left + (fit.width - width) / 2, fit.top), IntSize(width, height))
}

private fun Modifier.pictureSlot(aspect: Float): Modifier =
    layout { measurable, constraints ->
        val width = constraints.maxWidth
        val height = (width / aspect).roundToInt().coerceIn(0, constraints.maxHeight)
        val placeable = measurable.measure(Constraints.fixed(width, height))
        layout(width, height) { placeable.place(0, 0) }
    }

/**
 * Where a picture drawn in the slot gives way, as a fraction of the slot, and
 * how far the blur runs ahead of that and into it.
 *
 * Fractions of the slot rather than of the picture, and the slot is the same
 * shape on every song, so every cover fades at the same height whatever shape
 * it arrived in.
 */
private const val SLOT_FADE = 0.10f
private const val SLOT_BLUR_LEAD = 0.03f
private const val SLOT_BLUR_TAIL = 0.05f

/**
 * How far down the other catalogue's tall pictures stop being sharp, as a
 * fraction of their height — measured on them, where the blur they carry for
 * their own player's controls begins.
 */
private const val TALL_ART_SOFT_FROM = 0.70f

/**
 * The enlargement that puts that blur where this slot's own begins: see
 * HeroBackdrop's `zoom`.
 */
const val TALL_ART_ZOOM = (1f - SLOT_FADE - SLOT_BLUR_LEAD) / TALL_ART_SOFT_FROM


private fun fading(from: Float, to: Float): Array<Pair<Float, Color>> {
    val steps = 8
    return Array(steps + 1) { index ->
        val t = index.toFloat() / steps
        val eased = t * t * (3f - 2f * t)
        (from + (to - from) * t) to Color.Black.copy(alpha = 1f - eased)
    }
}
