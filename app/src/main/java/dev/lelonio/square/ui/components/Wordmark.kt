package dev.lelonio.square.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lelonio.square.R
import dev.lelonio.square.ui.theme.Ink

/**
 * The name, drawn rather than typeset.
 *
 * The icon is a square wave: one stroke width, right angles only, no curve
 * anywhere. No font shipped with Android says that — a bold sans still has
 * round bowls on S and Q and a diagonal on R — and pulling in a display face
 * for six letters costs more than the six letters do.
 *
 * So the letterforms are the same primitive as the icon: polylines on a 6×10
 * grid, orthogonal segments, square caps and joins. The Q's tail drops below
 * the baseline, which is the one thing that keeps it from reading as an O.
 *
 * Sized by its height: the width follows from the grid, so callers give this a
 * height and let it measure itself.
 */
@Composable
fun SquareWordmark(
    height: Dp,
    modifier: Modifier = Modifier,
    color: Color = Ink,
) {
    Canvas(
        modifier
            .height(height)
            // 6 glyphs on a 9.5-unit advance, over a 12-unit box.
            .width(height * (6f * 9.5f / 12f)),
    ) {
        val unit = size.height / 12f
        // The stroke stays near one unit and the advance well over the glyph
        // width, or the counters close up and the word turns into a dark block.
        val stroke = unit * 1.15f
        val path = Path()
        GLYPHS.forEachIndexed { index, lines ->
            val originX = index * 9.5f * unit + stroke / 2f
            lines.forEach { points ->
                points.forEachIndexed { at, (gx, gy) ->
                    val x = originX + gx * unit
                    val y = gy * unit + stroke / 2f
                    if (at == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
            }
        }
        drawPath(
            path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Square, join = StrokeJoin.Miter),
        )
    }
}

/**
 * The launcher art beside the name.
 *
 * The real icon rather than a drawing of one: this is what the user tapped to
 * get here, and showing the same thing is what says the two are one app.
 */
@Composable
fun AppLockup(
    modifier: Modifier = Modifier,
    iconSize: Dp = 28.dp,
    nameHeight: Dp = 14.dp,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        AppIcon(iconSize)
        Spacer(Modifier.width(iconSize * 0.32f))
        SquareWordmark(nameHeight)
    }
}

@Composable
fun AppIcon(size: Dp, modifier: Modifier = Modifier) {
    Image(
        // The adaptive icon's foreground, which is a plain PNG per density —
        // `ic_launcher` itself is the adaptive XML, which painterResource
        // cannot load.
        painter = painterResource(R.mipmap.ic_launcher_foreground),
        contentDescription = null,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.26f)),
    )
}

/** S Q U A R E, as polylines on the grid described in [SquareWordmark]. */
private val GLYPHS: List<List<List<Pair<Float, Float>>>> = listOf(
    // S
    listOf(listOf(6f to 0f, 0f to 0f, 0f to 5f, 6f to 5f, 6f to 10f, 0f to 10f)),
    // Q: a closed box with the tail dropped out of the bottom right.
    listOf(
        listOf(0f to 0f, 6f to 0f, 6f to 10f, 0f to 10f, 0f to 0f),
        listOf(4f to 8f, 4f to 12f),
    ),
    // U
    listOf(listOf(0f to 0f, 0f to 10f, 6f to 10f, 6f to 0f)),
    // A: the apex is a corner, not a point.
    listOf(
        listOf(0f to 10f, 0f to 0f, 6f to 0f, 6f to 10f),
        listOf(0f to 6f, 6f to 6f),
    ),
    // R: the leg comes straight down instead of splaying.
    listOf(
        listOf(0f to 10f, 0f to 0f, 6f to 0f, 6f to 5f, 0f to 5f),
        listOf(3f to 5f, 3f to 10f),
    ),
    // E
    listOf(
        listOf(6f to 0f, 0f to 0f, 0f to 10f, 6f to 10f),
        listOf(0f to 5f, 4f to 5f),
    ),
)
