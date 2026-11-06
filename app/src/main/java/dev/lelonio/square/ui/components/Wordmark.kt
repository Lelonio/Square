package dev.lelonio.square.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    /**
     * What to put the mark on, when it should sit on glass.
     *
     * Null leaves it bare, which is what everywhere but the home header wants:
     * a plate is a control, and a mark in the middle of a login page is not one.
     */
    plate: dev.lelonio.square.ui.glass.backdrop.Backdrop? = null,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (plate == null) {
            AppGlyph(iconSize)
        } else {
            AppPlate(iconSize, plate)
        }
        Spacer(Modifier.width(iconSize * 0.32f))
        SquareWordmark(nameHeight)
    }
}

/**
 * The mark on a round pane of the app's own glass.
 *
 * The same material and the same rim as the round buttons — see CircleAction —
 * so it belongs to the same row of controls even though there is nothing to
 * press. What it gives the mark is a place to stand.
 *
 * With something behind it to bend. Glass over the page's own flat colour has
 * nothing to refract and comes out as a grey disc, so the pane is given a wash
 * of the accent the app takes from the artwork that is playing: off-centre, so
 * the film and the rim have a gradient to sit on, and alive, because that
 * colour slides to a new one every time the music changes.
 *
 * Flat, deliberately. This sits in a header that moves with the scroll, and a
 * pane that samples the screen re-photographs it every frame — for a
 * reflection of a colour this already knows.
 */
@Composable
fun AppPlate(
    size: Dp,
    backdrop: dev.lelonio.square.ui.glass.backdrop.Backdrop,
    modifier: Modifier = Modifier,
) {
    val accent = androidx.compose.material3.MaterialTheme.colorScheme.primary
    // The iOS 27 glass, its own edge and light, over the artwork behind the
    // page: that layer stands still while the page scrolls, so the pane is
    // not taken again on every frame of it.
    dev.lelonio.square.ui.glass.LiquidButton(
        onClick = {},
        backdrop = backdrop,
        isInteractive = false,
        modifier = modifier.size(size),
        contentHeight = size,
        contentPadding = 0.dp,
    ) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            // A veil of the accent the app takes from the artwork playing,
            // light enough that the glass is what reads: it slides to a new
            // colour every time the music changes.
            androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                drawCircle(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        0f to accent.copy(alpha = 0.34f),
                        1f to accent.copy(alpha = 0f),
                        start = androidx.compose.ui.geometry.Offset.Zero,
                        end = androidx.compose.ui.geometry.Offset(this.size.width, this.size.height),
                    ),
                )
            }
            AppGlyph(size * 0.58f)
        }
    }
}

/**
 * The app's mark with no plate under it.
 *
 * The launcher icon is a tile: artwork on a coloured square, because that is
 * what a launcher draws. On a page of the app itself there is nothing for a
 * tile to sit on — it reads as a sticker of the icon rather than as the app's
 * own mark — so what is used is the wave alone, in the page's ink.
 *
 * Drawn from the same file the launcher art came from — see
 * `app/icon-src/wave-square.svg` — rather than from the stroked approximation
 * the themed launcher icon carries, which was a redrawing of it by hand and
 * showed at this size.
 */
@Composable
fun AppGlyph(size: Dp, modifier: Modifier = Modifier, tint: Color = Ink) {
    Icon(
        painter = painterResource(R.drawable.ic_app_mark),
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size),
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
