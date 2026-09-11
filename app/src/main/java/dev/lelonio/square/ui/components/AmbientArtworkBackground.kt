package dev.lelonio.square.ui.components

import android.content.Context
import android.os.PowerManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import dev.lelonio.square.ui.theme.warmArtworkColor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The room the player is in, made out of the record that is playing.
 *
 * Not a blurred cover behind a cover. The same picture is drawn much larger
 * than the screen, blurred past the point where anything in it can be made
 * out, and left as light and colour: what reaches the eye is the record's
 * palette filling the window, with the sharp copy above dissolving into it —
 * see [HeroBackdrop]'s `fadeToPage`, which is the other half of this.
 *
 * Three things make it read as a room rather than as a wallpaper:
 *
 * - two copies at different blurs, the softer one filling the frame and the
 *   sharper one over it at low opacity, which gives the field a near and a far
 *   and stops it looking like flat paper;
 * - a drift so slow it is never caught moving, which is what keeps a still
 *   screen from looking switched off;
 * - a crossfade on a track change that is slower than the cover's own, so the
 *   room changes around the picture instead of with it.
 *
 * ## What this deliberately does not do
 *
 * There is no `Modifier.blur` here and no `RenderEffect`. Both are render
 * effects over a layer, re-run every frame that layer changes — and this layer
 * changes on every frame of the drift, of the crossfade, and of the player
 * opening. The blur is done once per cover, at decode, on an image small enough
 * that the work is trivial, and Coil keeps the result: see [BlurTransformation].
 * That also settles the compatibility question, since it is ordinary bitmap
 * work on every version the app runs on.
 *
 * Everything that animates is read inside a `graphicsLayer` block, which the
 * compose runtime defers to the draw phase: the drift and the crossfade never
 * recompose anything and never re-decode anything. They move a texture the GPU
 * already has.
 */
@Composable
fun AmbientArtworkBackground(
    /** Usually a url; anything Coil can load. */
    artworkModel: Any?,
    /**
     * The colour taken from this same picture, which the field settles onto at
     * the foot of the screen and which is what is showing before it loads.
     */
    tone: Color,
    modifier: Modifier = Modifier,
    /** Off leaves the tone alone, which is the whole of the fallback. */
    enabled: Boolean = true,
    /**
     * The proportions the sharp cover above is drawn at, width over height.
     *
     * Given, this copy is laid over that cover exactly and its foot carried on
     * down the rest of the screen. Null fills the window with the picture
     * instead, which is right where there is no cover over this to match.
     */
    coverAspect: Float? = null,
    /**
     * The height of the picture above, where the page fixes it rather than
     * letting it take its own proportions. Wins over [coverAspect].
     */
    coverHeight: Dp? = null,
    /**
     * How the copy is framed, which has to be how the sharp picture above is
     * framed.
     *
     * The player draws its cover at its own proportions and fills the window,
     * so stretching to the bounds keeps every row under the row above it. A
     * page lays the picture across a short wide header and crops it, and a copy
     * stretched instead of cropped is a different framing of the same photo —
     * which arrives as a band of blurred something-else under the header.
     */
    coverScale: ContentScale = ContentScale.FillBounds,
    /**
     * Where the field starts settling — usually the foot of the picture.
     *
     * A page carries a list over this and needs to be darker than a player
     * does, and it needs to be darker from where its header ends rather than
     * from halfway down a screen it does not control.
     */
    settleFrom: Float = 0.55f,
    /**
     * Where the settling is complete, after which the field is one flat colour
     * and stays it.
     *
     * A page holds a single tone under its lists rather than sliding towards
     * black: the slide is what made every page look like it was running out of
     * light towards the bottom.
     */
    settleTo: Float = 1f,
    /** How much of the tone, and of black, has arrived by [settleTo]. */
    toneFloor: Float = 0.26f,
    scrimFloor: Float = 0.34f,
    /**
     * Whether the field drifts.
     *
     * Worth switching off while the player is arriving or leaving: the drift is
     * cheap, but it is also invisible under a travelling sheet, and a
     * continuously invalidating background during that travel is the one moment
     * this could cost a frame.
     */
    motion: Boolean = true,
    /**
     * The foot of a moving picture, across its width — see CanvasSurface.
     *
     * When a Canvas is playing, the picture the screen is made of is the clip,
     * not the sleeve, and the field under it has to be the clip's. These are
     * column averages of the clip's own bottom band, painted across the width
     * the way the still copy's last row is carried down: a red shape at the
     * foot of the frame goes on being red under it, and a dark corner stays
     * dark. Empty puts the cover's own copies back.
     */
    columns: List<Color> = emptyList(),
) {
    val context = LocalContext.current
    val powerManager = remember(context) {
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }
    val isPowerSave = powerManager?.isPowerSaveMode == true
    val effectiveMotion = motion && !isPowerSave

    // Which picture is showing, and which one is on its way out.
    //
    // Held here rather than handed to Crossfade or AnimatedContent: both of
    // those keep whole subtrees alive and recompose them across the change, and
    // what is crossfading is two images and nothing else. This is the same
    // effect as two alphas.
    var shown by remember { mutableStateOf(artworkModel) }
    var leaving by remember { mutableStateOf<Any?>(null) }
    val swap = remember { Animatable(1f) }

    // The colour the field is actually on, which is not always the colour it
    // has been given.
    //
    // A track change reaches this composable as several separate arrivals: the
    // address of the new cover first, then its colour once a bitmap has been
    // read, then the picture itself once it has been decoded and blurred. Taken
    // as they come, they are three visible events in a row — and the middle one
    // is the worst of them, a whole screen of colour changing under a picture
    // that has not changed yet. That is the flash. This holds the colour where
    // it is until the picture is ready, and then moves both together.
    var held by remember { mutableStateOf(tone) }
    var holding by remember { mutableStateOf(false) }
    if (!holding && held != tone) held = tone

    // Eased, and slower than everything else here: the tone is the one thing on
    // screen with no picture in it, so a cut in it reads as the lights being
    // switched rather than as the record changing.
    val settledTone by animateColorAsState(
        targetValue = held,
        animationSpec = tween(durationMillis = TONE_MS),
        label = "ambientTone",
    )

    LaunchedEffect(artworkModel) {
        if (artworkModel == shown) return@LaunchedEffect
        // Nothing to fade from on the first picture of all: fading up from the
        // tone is what the colour animation above is already doing.
        if (shown == null || artworkModel == null) {
            shown = artworkModel
            leaving = null
            swap.snapTo(1f)
            return@LaunchedEffect
        }

        // Wait for the new picture before starting to leave the old one.
        //
        // Without this the fade ran on the clock and the image ran on the
        // network: the old copy was already half gone when the new one had
        // nothing to draw, so what showed through the middle of the change was
        // the bare tone — a flash of flat colour between two covers. Waiting
        // costs nothing visible, because what is on screen in the meantime is
        // the picture that was already there.
        //
        // Timed out rather than awaited for ever: a cover that never arrives
        // must not leave the player on the wrong record's colours.
        holding = true
        withTimeoutOrNull(READY_MS.toLong()) {
            coroutineScope {
                // Exactly the requests the copies below will make, so what this
                // waits for is what they will find already in the cache.
                launch { warm(context, artworkModel, FAR_PX, FarBlur) }
                launch { warm(context, artworkModel, NEAR_PX, NearBlur) }
                // And the colour read off the same picture, so it cannot arrive
                // on its own afterwards.
                launch { (artworkModel as? String)?.let { warmArtworkColor(context, it) } }
            }
        }
        holding = false

        leaving = shown
        shown = artworkModel
        swap.snapTo(0f)
        swap.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = SWAP_MS, easing = FastOutSlowInEasing),
        )
        leaving = null
    }

    // One clock for the drift, shared by both copies so they cannot beat
    // against each other.
    val drift: State<Float> = if (effectiveMotion && enabled) {
        rememberInfiniteTransition(label = "ambientDrift").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                // Linear, and reversed rather than restarted: an eased drift has
                // a moment where it visibly slows, and a restarted one has a
                // jump. Neither survives being watched for a whole song.
                animation = tween(durationMillis = DRIFT_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "ambientDriftValue",
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    BoxWithConstraints(modifier.fillMaxSize().background(settledTone)) {
        if (columns.isNotEmpty()) {
            // Eased one column at a time, and slowly: a field that tracked
            // every cut would strobe, and this is meant to read as the light
            // the clip is throwing rather than as the clip itself.
            // Smoothed across before anything is drawn.
            //
            // The columns are averages of neighbouring strips of one frame, so
            // where the clip has an edge the two strips either side of it are
            // genuinely different colours — and a gradient between them puts
            // that edge back on the screen as a seam. Averaged with their
            // neighbours a few times over, what is left is where the colour is
            // rather than where the shape was: it reads as light, which is the
            // whole point of it.
            val spread = remember(columns) { columns.spread(SPREAD_PASSES, SPREAD_RADIUS) }
            val eased = spread.map { target ->
                animateColorAsState(
                    targetValue = target,
                    animationSpec = tween(durationMillis = CLIP_TONE_MS),
                    label = "clipColumn",
                ).value
            }
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(eased)))

            // The shapes merge into one colour as they go down.
            //
            // A gradient repeated unchanged down the screen keeps the clip's
            // red flood as a hard-edged stripe running the whole height, which
            // is not what carrying a picture's foot downwards looks like — the
            // still copy's own stretch is blurred, so its shapes dissolve. This
            // is that dissolve: the columns are what meets the fade, and a few
            // hundred points lower they have become their own average.
            val mean = remember(eased) {
                if (eased.isEmpty()) {
                    Color.Transparent
                } else {
                    Color(
                        red = eased.sumOf { it.red.toDouble() }.toFloat() / eased.size,
                        green = eased.sumOf { it.green.toDouble() }.toFloat() / eased.size,
                        blue = eased.sumOf { it.blue.toDouble() }.toFloat() / eased.size,
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            settleFrom to Color.Transparent,
                            (settleFrom + (1f - settleFrom) * 0.42f) to mean.copy(alpha = 0.72f),
                            1f to mean,
                        ),
                    ),
            )

            // And damped on the way down.
            //
            // The columns are the clip's own foot, and a clip's foot is often
            // the brightest thing in it: painted flat down the screen it left
            // the transport sitting on a field louder than the picture it came
            // from. This keeps the colour where the fade meets it and takes the
            // light out of it further down, which is what the still copy's own
            // stretch does by simply running out of picture.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            settleFrom to Color.Transparent,
                            (settleFrom + (1f - settleFrom) * 0.45f)
                                to Color.Black.copy(alpha = 0.20f),
                            1f to Color.Black.copy(alpha = 0.46f),
                        ),
                    ),
            )
        }

        if (enabled && columns.isEmpty()) {
            // The far copy, laid over the sharp one exactly.
            //
            // Same width, same top, same proportions, so every row of it is the
            // blur of the row of cover above it. That is what the fade needs:
            // it is not crossing from one picture to another, it is taking the
            // sharpness out of the one picture.
            val slot = coverHeight ?: coverAspect?.let { maxWidth / it }
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (slot != null) Modifier.height(slot) else Modifier.fillMaxSize(),
                    )
                    .align(Alignment.TopCenter),
            ) {
                AmbientCopy(
                    leaving = leaving,
                    shown = shown,
                    progress = { swap.value },
                    drift = { drift.value },
                    decodePx = FAR_PX,
                    blur = FarBlur,
                    baseScale = 1f,
                    travel = 0f,
                    opacity = 1f,
                    scale = coverScale,
                )
            }

            // And below the cover, the cover's own bottom edge carried down.
            //
            // There is nothing under the last row of a picture, so a "picture
            // continuing" past its own end can only be that row going on. The
            // copy here is stretched vertically by a large factor about its
            // foot, which leaves the bottom fiftieth of the artwork spread over
            // the rest of the screen: the seam matches because the row that
            // meets it is the row that ended the cover, and what fills the
            // screen still varies across the width the way the sleeve does —
            // warm at one side, dark at the other — instead of being one tone.
            if (slot != null && slot < maxHeight) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(maxHeight - slot)
                        .align(Alignment.BottomCenter)
                        .clipToBounds(),
                ) {
                    // A copy the size of the picture's own slot, so it is
                    // framed exactly as the picture is, sunk to the foot of
                    // this box and stretched about that foot. What is left in
                    // view is the sleeve's last rows and nothing else.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(slot)
                            .align(Alignment.BottomCenter),
                    ) {
                        AmbientCopy(
                            leaving = leaving,
                            shown = shown,
                            progress = { swap.value },
                            drift = { drift.value },
                            decodePx = FAR_PX,
                            blur = FarBlur,
                            baseScale = 1f,
                            travel = 0f,
                            opacity = 1f,
                            stretch = SMEAR,
                            scale = coverScale,
                        )
                    }
                }
            }

            // And the near one over it, sharper and barely there. It is what
            // gives the field somewhere to be: a single blur at a single scale
            // is flat no matter how well it is made, and two of them at
            // different sizes moving against each other are a depth.
            AmbientCopy(
                leaving = leaving,
                shown = shown,
                progress = { swap.value },
                drift = { drift.value },
                decodePx = NEAR_PX,
                blur = NearBlur,
                baseScale = NEAR_SCALE,
                // The only thing here that moves. The far copy is in register
                // with the sharp cover above and has to stay there; drifting
                // this one is what keeps the field alive without ever moving
                // the picture out from under the fade.
                travel = -NEAR_TRAVEL,
                opacity = NEAR_ALPHA,
            )
        }

        // A trace of the cover's own foot colour on the way down, and a scrim
        // under that for the controls.
        //
        // Both kept light on purpose, and the tone lighter than the scrim. What
        // fills the bottom of this screen is meant to be the picture carrying
        // on; a tone heavy enough to be seen is a panel over it, however well it
        // was chosen, and it was that — at more than half — which had every
        // record ending on the same lilac. The darkening that legibility needs
        // is black, which takes the light out without taking the hue with it.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        *tonalSettle(settleFrom, settleTo, settledTone, toneFloor),
                    ),
                ),
        )
        val scrim = dev.lelonio.square.ui.theme.scrimColor()
        if (scrimFloor > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to scrim.copy(alpha = 0.16f),
                            (settleFrom * 0.64f) to Color.Transparent,
                            (settleFrom + (1f - settleFrom) * 0.5f)
                                to scrim.copy(alpha = scrimFloor * 0.4f),
                            1f to scrim.copy(alpha = scrimFloor),
                        ),
                    ),
            )
        }
    }
}

/**
 * One blurred copy of the artwork, and the one leaving fading out under it.
 *
 * [progress] and [drift] are functions rather than values: read as parameters
 * they would recompose this on every frame of both animations, and read inside
 * the `graphicsLayer` block they are draw-phase reads, which is a re-record of
 * one layer and nothing else.
 */
@Composable
private fun AmbientCopy(
    leaving: Any?,
    shown: Any?,
    progress: () -> Float,
    drift: () -> Float,
    decodePx: Int,
    blur: BlurTransformation,
    baseScale: Float,
    travel: Float,
    opacity: Float,
    /** Vertical stretch about the foot; see SMEAR. */
    stretch: Float = 1f,
    /** How the picture is framed in its box; see coverScale. */
    scale: ContentScale = ContentScale.FillBounds,
) {
    if (leaving != null) {
        AmbientImage(
            model = leaving,
            decodePx = decodePx,
            blur = blur,
            scale = scale,
            layer = {
                val p = progress()
                alpha = (1f - p) * opacity
                // The picture leaving grows very slightly as it goes, which
                // reads as it receding rather than as it being switched off.
                val scale = baseScale * (1f + LEAVE_SWELL * p)
                scaleX = scale
                scaleY = scale * stretch
                transformOrigin = if (stretch > 1f) SmearAnchor else FootAnchor
                translationY = size.height * travel * drift()
            },
        )
    }
    if (shown != null) {
        AmbientImage(
            model = shown,
            decodePx = decodePx,
            blur = blur,
            scale = scale,
            layer = {
                val p = progress()
                alpha = p * opacity
                // And the one arriving settles inward to its own size.
                val scale = baseScale * (1f + ARRIVE_SWELL * (1f - p))
                scaleX = scale
                scaleY = scale * stretch
                transformOrigin = if (stretch > 1f) SmearAnchor else FootAnchor
                translationY = size.height * travel * drift()
            },
        )
    }
}

/**
 * The tone coming up under the picture, eased, and then holding.
 *
 * Smoothstepped for the same reason the picture's own fade is: a straight ramp
 * between two stops has a corner at each end, and a corner in a field this
 * large is a band across the page. Past [to] the colour is simply itself, which
 * is what makes the lower half of a page one colour rather than a slide into
 * the dark.
 */
private fun tonalSettle(
    from: Float,
    to: Float,
    colour: Color,
    ceiling: Float,
): Array<Pair<Float, Color>> {
    val steps = 8
    val end = to.coerceIn(from + 0.01f, 1f)
    // The flat run past the end of the ramp, unless the ramp already ends at
    // the bottom of the screen: two stops at the same place is a hard edge.
    val hold = if (end < 0.999f) 1 else 0
    return Array(steps + 1 + hold) { index ->
        if (index > steps) return@Array 1f to colour.copy(alpha = ceiling)
        val t = index.toFloat() / steps
        val eased = t * t * (3f - 2f * t)
        (from + (end - from) * t) to colour.copy(alpha = ceiling * eased)
    }
}

/**
 * Puts a copy in Coil's cache, at the size and blur a copy below will ask for.
 *
 * The result is thrown away on purpose: what is wanted is the cache entry, so
 * that the image the crossfade is about to draw is there on the first frame of
 * it rather than somewhere in the middle.
 */
private suspend fun warm(
    context: android.content.Context,
    model: Any,
    decodePx: Int,
    blur: BlurTransformation,
) {
    runCatching {
        context.imageLoader.execute(
            ImageRequest.Builder(context)
                .data(if (model is String) artSource(model) else model)
                .size(decodePx)
                .transformations(blur)
                .allowHardware(false)
                .build(),
        )
    }
}

@Composable
private fun AmbientImage(
    model: Any,
    decodePx: Int,
    blur: BlurTransformation,
    scale: ContentScale,
    layer: GraphicsLayerScope.() -> Unit,
) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            // Offline this is the file on the disk rather than the address it
            // came from; the same resolution the sharp copy uses.
            .data(if (model is String) artSource(model) else model)
            .size(decodePx)
            .transformations(blur)
            // A hardware bitmap cannot be read back and the blur reads every
            // pixel of it.
            .allowHardware(false)
            // Off deliberately: the fade between two covers is the one above,
            // which knows about both of them. Coil's own also skips itself on a
            // cache hit, so with it on a prefetched cover arrives as a cut.
            .crossfade(false)
            .build(),
        contentDescription = null,
        // Stretched to the window rather than cropped to it, which is the
        // whole of why this reads as the cover carrying on.
        //
        // The cover is drawn across the top at its own proportions, and the
        // screen is much taller than that. Cropping to fill leaves the bottom
        // of the screen showing the middle of the sleeve, so the picture and
        // the field below it are two different parts of the same artwork
        // meeting at the fade — which is a seam however well the fade is made.
        // Filling the bounds keeps every row of this copy under the same row of
        // the sharp one and carries the last of it down to the foot of the
        // screen. The vertical stretch is real and, under this much blur,
        // unfindable.
        contentScale = scale,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(layer),
    )
}

/**
 * Enlargement from the top, where the sharp copy starts.
 *
 * Anything anchored at the centre slides the picture out of register with the
 * cover above it, and out of register is the whole of what a seam is.
 */
private val FootAnchor = TransformOrigin(0.5f, 0f)

/** And for the smear, which grows upward out of the bottom of its own box. */
private val SmearAnchor = TransformOrigin(0.5f, 1f)

/**
 * How far the copy below the cover is stretched.
 *
 * Very large on purpose. The band left in view is the last 1/[SMEAR] of the
 * picture, and it is drawn bottom-row-last — so the row that meets the seam is
 * not quite the row that ended the picture, and the difference between the two
 * is a line across the screen. At fifty that gap was two percent of the image
 * and findable; at four hundred it is a quarter of a percent, which is under
 * the blur. What is drawn is still the sleeve's own foot, varying across the
 * width the way the sleeve does.
 */
private const val SMEAR = 400f

/** How far past its own size the near copy is drawn. */
private const val NEAR_SCALE = 1.06f

/** How much of the near copy shows. */
private const val NEAR_ALPHA = 0.30f

/** How far the near copy drifts, as a fraction of the screen's height. */
private const val NEAR_TRAVEL = 0.014f

/** How long the drift takes to cross, one way. */
private const val DRIFT_MS = 12_000

/** How long the room takes to become a different room. */
private const val SWAP_MS = 720

/**
 * How long the change will wait for the new picture before going without it.
 *
 * Long enough for a cover to come off the network on a poor connection, short
 * enough that a cover which is not coming at all does not hold the screen on
 * the last record for longer than the song took to change.
 */
private const val READY_MS = 1_800

/** And the colour under it, which is slower still. */
private const val TONE_MS = 900

/** How long the field takes to become the clip's next colours. */
private const val CLIP_TONE_MS = 1_800

/** How far each colour is averaged into its neighbours, and how many times. */
private const val SPREAD_RADIUS = 4
private const val SPREAD_PASSES = 3

/**
 * A list of colours with the edges taken out of it.
 *
 * A box blur along the list, repeated: three passes of a running mean is close
 * enough to a gaussian that nothing in it can be found, and on two dozen
 * values it costs nothing. The ends are held rather than wrapped — the left of
 * the screen is not next to the right.
 */
private fun List<Color>.spread(passes: Int, radius: Int): List<Color> {
    if (size < 3 || radius < 1) return this
    var current = this
    repeat(passes) {
        current = current.mapIndexed { index, _ ->
            var r = 0f
            var g = 0f
            var b = 0f
            var count = 0
            for (offset in -radius..radius) {
                val at = (index + offset).coerceIn(0, current.lastIndex)
                val colour = current[at]
                r += colour.red
                g += colour.green
                b += colour.blue
                count++
            }
            Color(r / count, g / count, b / count)
        }
    }
    return current
}

/** How much the picture leaving grows, and the one arriving shrinks. */
private const val LEAVE_SWELL = 0.05f
private const val ARRIVE_SWELL = 0.06f

/** Blurred at decode, once per cover, and kept by Coil. */
private val FarBlur = BlurTransformation(radius = 16, passes = 3)
private val NearBlur = BlurTransformation(radius = 10, passes = 2)

/** How small each copy is decoded before it is blurred and stretched. */
private const val FAR_PX = 96
private const val NEAR_PX = 192
