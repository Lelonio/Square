package dev.lelonio.square.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.cos
import kotlin.math.sin

/**
 * The colours the Mix's light is made of: those of what lies under the
 * button (the cover's, in the player; the playlist's, on its page). Empty
 * for Square's own green, violet and teal.
 */
val LocalMixColors = androidx.compose.runtime.compositionLocalOf<List<Color>> { emptyList() }

/**
 * Light moving inside the play button while a playlist plays as a Mix.
 *
 * Three soft lights, in the colours under the button, turn slowly around the middle of
 * the glass, each at its own pace, and swell with how loud the music is: the
 * button is the mix going on, not a badge saying so. Drawn under the symbol,
 * inside the disc, so the glass around it stays the same material as every
 * other control.
 */
@Composable
fun MixAurora(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = CircleShape,
) {
    val given = LocalMixColors.current
    val lights = remember(given) { lightsOf(given) }
    val time = remember { mutableFloatStateOf(0f) }
    val level = remember { mutableFloatStateOf(0f) }
    // The band's angle, added up a frame at a time: worked out as the time
    // so far times a speed that follows the music, it jumped whenever the
    // music got louder or quieter.
    val turn = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                time.floatValue += dt
                // Eased towards the music, never stepped: rising over about a
                // quarter of a second, settling over nearly one.
                val heard = dev.lelonio.square.playback.AudioLevel.now()
                val was = level.floatValue
                val pace = if (heard > was) 4f else 1.1f
                level.floatValue = was + (heard - was) * (1f - kotlin.math.exp(-pace * dt))
                turn.floatValue = (turn.floatValue + dt * (0.07f + level.floatValue * 0.05f) * 360f) % 360f
            }
        }
    }
    Canvas(
        modifier
            .clip(shape)
            // Its own layer, for the fade towards the middle; see drawAurora.
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen },
    ) {
        drawAurora(lights, time.floatValue, turn.floatValue, level.floatValue)
    }
}

/**
 * Three lights in the colours given, made bright enough to show through the
 * glass: a dark sleeve's colours as they are would be lost in it.
 */
private fun lightsOf(colors: List<Color>): List<Light> {
    if (colors.isEmpty()) return Lights
    val picked = colors.sortedByDescending { saturation(it) }.take(3)
    // Fewer than three: the others are neighbours of the first in hue, so a
    // page of one colour still has light moving in it.
    return Lights.mapIndexed { i, light ->
        val base = brighten(picked.getOrNull(i) ?: picked[0])
        light.copy(color = if (i < picked.size) base else shiftHue(base, if (i == 1) 28f else -28f))
    }
}

private fun saturation(c: Color): Float {
    val max = maxOf(c.red, c.green, c.blue)
    val min = minOf(c.red, c.green, c.blue)
    return if (max == 0f) 0f else (max - min) / max
}

/** The colour in its own hue, lifted to a clear light. */
private fun brighten(c: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt(), hsv)
    hsv[1] = hsv[1].coerceIn(0.45f, 0.9f)
    hsv[2] = hsv[2].coerceAtLeast(0.85f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private fun shiftHue(c: Color, degrees: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt(), hsv)
    hsv[0] = (hsv[0] + degrees + 360f) % 360f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private val Lights = listOf(
    // colour, turns per second, distance from the middle, phase
    Light(Color(0xFF5FE3A1), 0.07f, 0.34f, 0f),
    Light(Color(0xFF8F7CF0), -0.05f, 0.38f, 2.1f),
    Light(Color(0xFF3EC6D8), 0.035f, 0.30f, 4.2f),
)

private data class Light(val color: Color, val turns: Float, val distance: Float, val phase: Float)

private fun DrawScope.drawAurora(lights: List<Light>, t: Float, turn: Float, level: Float) {
    val r = size.minDimension / 2f
    val reach = maxOf(size.width, size.height) / 2f
    val strength = (0.5f + level * 0.45f).coerceAtMost(0.95f)

    // A band of light going round the glass: the three colours in a turning
    // ring, quicker a little with the music (see [turn]).
    val ring = lights.map { it.color.copy(alpha = strength) }
    rotate(turn) {
        drawRect(
            brush = Brush.sweepGradient(
                listOf(ring[0], ring[1], ring[2], ring[0].copy(alpha = strength * 0.4f), ring[0]),
                center = center,
            ),
            topLeft = Offset(center.x - reach * 1.5f, center.y - reach * 1.5f),
            size = androidx.compose.ui.geometry.Size(reach * 3f, reach * 3f),
        )
    }

    // Two soft glows that travel along the edge, each at its own pace, so the
    // band never looks like a wheel.
    lights.take(2).forEachIndexed { i, light ->
        val angle = light.phase + t * light.turns * 1.6f * 2f * Math.PI.toFloat()
        val d = r * 0.72f * (1f + 0.08f * sin(t * 0.5f + light.phase))
        val at = center + Offset(cos(angle) * d * size.width / size.minDimension, sin(angle) * d)
        val radius = r * (0.6f + level * 0.25f)
        drawCircle(
            brush = Brush.radialGradient(
                0f to light.color.copy(alpha = strength * 0.9f),
                1f to Color.Transparent,
                center = at,
                radius = radius,
            ),
            radius = radius,
            center = at,
            blendMode = if (i == 0) BlendMode.Screen else BlendMode.SrcOver,
        )
    }

    // Light at the rim, quiet in the middle where the symbol is: the
    // whole thus far is kept only as far as this lets it.
    drawRect(
        brush = Brush.radialGradient(
            0f to Color.Black.copy(alpha = 0.12f),
            0.45f to Color.Black.copy(alpha = 0.35f),
            1f to Color.Black,
            center = center,
            radius = reach,
        ),
        blendMode = BlendMode.DstIn,
    )
}
