package dev.lelonio.square.ui.components

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
import coil.compose.AsyncImage
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
) {
    // Black over a dark page and white over a light one; see scrimColor.
    val scrim = dev.lelonio.square.ui.theme.scrimColor()
    Box(modifier) {
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
                        Modifier.aspectRatio(imageAspect)
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
                                        *fading(softenFrom, softenTo),
                                    ),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                    },
                ),
        ) {
        Artwork(
            url = artworkUrl,
            title = title,
            modifier = Modifier.fillMaxSize(),
            corner = 0.dp,
            // One picture, sometimes replaced by a better copy of itself — the
            // catalogue's scan arriving after the one the queue carried. A cut
            // between two versions of the same artwork is the most visible
            // change this screen ever makes; a fade makes it a refinement.
            crossfadeMs = SWAP_MS,
            fallback = false,
        )

        // The moving cover over the still one, which stays underneath as what is
        // shown until the first frame arrives.
        if (motionUrl != null) {
            MotionCover(url = motionUrl, modifier = Modifier.fillMaxSize())
        }

        if (artworkUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    // The same picture the sharp copy above draws, which
                    // offline is the file on the disk rather than the url.
                    .data(artSource(artworkUrl))
                    .size(HERO_BLUR_PX)
                    .transformations(HeroBlur)
                    // A hardware bitmap cannot be read back, and the blur reads
                    // every pixel of it.
                    .allowHardware(false)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    // Its own layer, so the mask below erases this copy alone
                    // and not the sharp one underneath it.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
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
private fun fading(from: Float, to: Float): Array<Pair<Float, Color>> {
    val steps = 8
    return Array(steps + 1) { index ->
        val t = index.toFloat() / steps
        val eased = t * t * (3f - 2f * t)
        (from + (to - from) * t) to Color.Black.copy(alpha = 1f - eased)
    }
}
