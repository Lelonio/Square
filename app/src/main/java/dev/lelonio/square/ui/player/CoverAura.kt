package dev.lelonio.square.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.sin

/**
 * Light in the cover's own colours, moving, for a track with no Canvas.
 *
 * Most of the catalogue has no clip, and those tracks got a still blurred cover
 * where the rest of the app has something alive. This invents nothing about the
 * song: it is the artwork's own palette drifting behind the glass.
 *
 * Built for the glass rather than for itself. A pane of liquid glass shows what
 * is behind it bent at its edges, and bending an even wash of colour produces an
 * even wash of colour — the first version was three soft blobs and the panels
 * above it barely registered that anything had changed. What a lens has
 * something to do with is an *edge*, so this draws edges: beams with a hard
 * flank sweeping across the screen, and small bright caustics travelling with
 * them. Under glass those read as the refraction moving.
 *
 * Slow, all the same. The periods are not multiples of one another, so nothing
 * on screen ever pulses in time with anything else, and a screen you look at for
 * the length of an album must not be doing anything you can count.
 */
@Composable
fun CoverAura(
    colors: List<Color>,
    /** Stilled with the music: a paused track should not be dancing. */
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    if (colors.isEmpty()) return

    val transition = rememberInfiniteTransition(label = "aura")
    // One phase read at several speeds below, rather than several animations:
    // they would drift apart on a dropped frame and this cannot.
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(CYCLE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    // Held where it was rather than snapped anywhere: the light stops, it does
    // not jump back to a starting position.
    val frozen = remember { mutableFloatStateOf(0f) }
    if (playing) frozen.floatValue = phase
    val turn = frozen.floatValue * TAU

    Canvas(
        modifier
            .fillMaxSize()
            // Blurred as one picture rather than drawn soft.
            //
            // Soft shapes and a blurred hard shape are not the same thing: a
            // gradient fades evenly, while a blurred edge keeps a bright ridge
            // where the edge was, and that ridge is what the glass above has
            // something to bend. So the beams and the caustics are drawn crisp
            // and the whole thing is put out of focus afterwards.
            .blur(BLUR),
    ) {
        // The bed: wide, soft, and the only part meant to be looked at
        // directly. Everything after this is for the glass.
        repeat(3) { index ->
            val color = colors[index % colors.size]
            val angle = turn * DRIFT[index] + index * (TAU / 3f)
            val centre = Offset(
                x = size.width * (0.5f + 0.34f * cos(angle)),
                y = size.height * (0.42f + 0.30f * sin(angle * 2f)),
            )
            val radius = size.maxDimension * 0.62f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.5f), Color.Transparent),
                    center = centre,
                    radius = radius,
                ),
                radius = radius,
                center = centre,
            )
        }

        // The beams. Each is a band with one soft side and one abrupt one, and
        // the abrupt side is the whole point: that is the edge the panes bend.
        repeat(2) { index ->
            val color = colors[(index + 1) % colors.size]
            val angle = turn * BEAM[index] + index * 1.7f
            rotate(degrees = 20f * sin(angle) + index * 40f - 20f) {
                beam(
                    color = color,
                    // Swung rather than wrapped. A band that travels and starts
                    // again jumps at the moment it wraps, and the whole loop was
                    // built so there is no such moment.
                    offset = 0.5f + 0.42f * sin(angle * 0.5f + index),
                )
            }
        }

        // Caustics: the bright knots light makes through moving water. Small
        // and few — under a lens each one throws a highlight far larger than
        // itself, and a dozen would be glitter.
        repeat(3) { index ->
            val color = colors[(index + 2) % colors.size]
            val angle = turn * CAUSTIC[index] + index * 2.1f
            val centre = Offset(
                x = size.width * (0.5f + 0.40f * sin(angle)),
                y = size.height * (0.45f + 0.34f * cos(angle * 2f)),
            )
            val radius = size.minDimension * 0.10f
            drawCircle(
                brush = Brush.radialGradient(
                    // White at the core rather than more of the same colour: a
                    // highlight is where the light is, not where the paint is.
                    colors = listOf(
                        Color.White.copy(alpha = 0.30f),
                        color.copy(alpha = 0.40f),
                        Color.Transparent,
                    ),
                    center = centre,
                    radius = radius,
                ),
                radius = radius,
                center = centre,
            )
        }
    }
}

/**
 * One band of light across the whole canvas, at whatever rotation is in force.
 *
 * Drawn well outside the bounds so that turning it never brings an end of the
 * band into view: a beam has to arrive from off screen or it reads as a shape
 * rather than as light.
 */
private fun DrawScope.beam(color: Color, offset: Float) {
    val span = size.maxDimension * 2f
    val x = -span / 4f + span * offset
    val width = size.minDimension * 0.42f
    drawRect(
        brush = Brush.horizontalGradient(
            // The stops are the shape: a long fade in, full colour, then a
            // cliff. Symmetrical stops gave a soft band with nothing for the
            // glass to catch.
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.62f to color.copy(alpha = 0.26f),
                0.88f to color.copy(alpha = 0.44f),
                0.92f to color.copy(alpha = 0.06f),
                1f to Color.Transparent,
            ),
            startX = x,
            endX = x + width,
        ),
        topLeft = Offset(x, -span / 2f),
        size = Size(width, span * 1.5f),
    )
}

/**
 * How far out of focus. Wide enough that no shape can be named, narrow enough
 * that the beams still pass as beams.
 */
private val BLUR = 48.dp

/** A full turn of the slowest element. Minutes, not seconds. */
private const val CYCLE_MS = 60_000

/**
 * Whole turns per cycle, and whole numbers on purpose.
 *
 * Everything here is a sine or a cosine of the phase times one of these, so at
 * the end of a cycle every element is exactly where it began and the loop has
 * no seam: there is no moment where the animation can be seen restarting.
 * Fractions would have looked freer and shown a jump every forty seconds.
 *
 * They are different from one another, and paired with the doubled vertical
 * terms above, so the paths are lissajous curves rather than circles and the
 * three never trace the same shape.
 */
private val DRIFT = floatArrayOf(1f, 2f, 3f)
private val BEAM = floatArrayOf(2f, 3f)
private val CAUSTIC = floatArrayOf(3f, 2f, 4f)

private const val TAU = (2 * Math.PI).toFloat()
