package dev.lelonio.square.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

/** How far the mix ring and its glow reach outside the button they go round. */
val MIX_RING_ROOM = 8.dp

/**
 * The lit ring Spotify's Mix puts round its Play button: a sweep of the
 * accent turning slowly round the edge. Nothing at all when [on] is false.
 */
@Composable
fun Modifier.mixRing(
    on: Boolean,
    /**
     * The ring's light: the accent of whatever it is on, which is the
     * playlist's on its page and the song's in the player, rather than
     * Spotify's own green, which belongs to neither.
     */
    color: Color = MaterialTheme.colorScheme.primary,
): Modifier {
    if (!on) return this
    val accent = color
    val turn = rememberInfiniteTransition(label = "mixRing")
    val angle by turn.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4_000, easing = LinearEasing)),
        label = "mixRingAngle",
    )
    return this
        .drawBehind {
            val stroke = 3.dp.toPx()
            val inset = -stroke * 1.4f
            // The light turns, the shape does not: the gradient is rotated
            // through the shader's own matrix, round the button's centre.
            val cx = size.width / 2f
            val cy = size.height / 2f
            val dim = accent.copy(alpha = 0.15f).toArgb()
            val shader = android.graphics.SweepGradient(
                cx, cy, intArrayOf(dim, accent.toArgb(), dim), null,
            ).apply { setLocalMatrix(android.graphics.Matrix().apply { setRotate(angle, cx, cy) }) }
            drawRoundRect(
                brush = androidx.compose.ui.graphics.ShaderBrush(shader),
                topLeft = Offset(inset, inset),
                size = Size(size.width - 2 * inset, size.height - 2 * inset),
                cornerRadius = CornerRadius(size.height),
                style = Stroke(stroke),
            )
        }
        .drawBehind {
            // A soft glow under the ring, so it reads as light and not a line.
            drawRoundRect(
                color = accent.copy(alpha = 0.22f),
                topLeft = Offset(-6.dp.toPx(), -6.dp.toPx()),
                size = Size(size.width + 12.dp.toPx(), size.height + 12.dp.toPx()),
                cornerRadius = CornerRadius(size.height),
            )
        }
}
