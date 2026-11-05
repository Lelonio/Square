package dev.lelonio.square.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import dev.lelonio.square.ui.theme.Ink

/**
 * A title in the wordmark's letters: polylines on the same 6×10 grid, the
 * same stroke, square caps and joins, the same advance, as SquareWordmark
 * draws S, Q, U, A, R and E. Capitals only, as the wordmark is.
 *
 * [height] is the capitals' height, the wordmark's own measure. A title with
 * a character the grid has no letter for (a script other than Latin) is set
 * in the app's type at that size instead, rather than half drawn.
 */
@Composable
fun SquareTitle(
    text: String,
    height: Dp,
    modifier: Modifier = Modifier,
    color: Color = Ink,
) {
    val letters = text.uppercase()
    if (letters.any { it != ' ' && it !in TITLE_GLYPHS }) {
        val size = with(LocalDensity.current) { (height * 1.35f).toSp() }
        Text(letters, modifier = modifier, color = color, fontSize = size, fontWeight = FontWeight.Bold)
        return
    }
    // The wordmark's box is 12 units over 10-unit capitals; this one keeps
    // 3 units over them for the accents, and the Q's tail below.
    val unit = height / 10f
    val width = unit * (letters.length * ADVANCE - (ADVANCE - 6f) + STROKE)
    Canvas(
        modifier
            .semantics {
                heading()
                contentDescription = text
            }
            .height(unit * (ABOVE + 12f))
            .width(width),
    ) {
        val u = size.height / (ABOVE + 12f)
        val stroke = u * STROKE
        val path = Path()
        letters.forEachIndexed { index, char ->
            val glyph = TITLE_GLYPHS[char] ?: return@forEachIndexed
            val originX = index * ADVANCE * u + stroke / 2f
            glyph.forEach { points ->
                points.forEachIndexed { at, (gx, gy) ->
                    val x = originX + gx * u
                    val y = (gy + ABOVE) * u
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

/** The wordmark's advance and stroke, in grid units. */
private const val ADVANCE = 9.5f
private const val STROKE = 1.15f

/** Room over the capitals for an accent. */
private const val ABOVE = 3f

private typealias Glyph = List<List<Pair<Float, Float>>>

private fun g(vararg lines: List<Pair<Float, Float>>): Glyph = lines.toList()
private fun l(vararg points: Pair<Float, Float>): List<Pair<Float, Float>> = points.toList()

/** A bar over a capital, for its accent, whichever way the accent leans. */
private val ACCENT = l(2f to -2.5f, 4f to -2.5f)

private val BASE: Map<Char, Glyph> = mapOf(
    // The wordmark's six, as SquareWordmark draws them.
    'S' to g(l(6f to 0f, 0f to 0f, 0f to 5f, 6f to 5f, 6f to 10f, 0f to 10f)),
    'Q' to g(l(0f to 0f, 6f to 0f, 6f to 10f, 0f to 10f, 0f to 0f), l(4f to 8f, 4f to 12f)),
    'U' to g(l(0f to 0f, 0f to 10f, 6f to 10f, 6f to 0f)),
    'A' to g(l(0f to 10f, 0f to 0f, 6f to 0f, 6f to 10f), l(0f to 6f, 6f to 6f)),
    'R' to g(l(0f to 10f, 0f to 0f, 6f to 0f, 6f to 5f, 0f to 5f), l(3f to 5f, 3f to 10f)),
    'E' to g(l(6f to 0f, 0f to 0f, 0f to 10f, 6f to 10f), l(0f to 5f, 4f to 5f)),
    // The rest of the alphabet in the same terms: right angles, one stroke.
    'B' to g(l(0f to 10f, 0f to 0f, 5f to 0f, 5f to 5f, 0f to 5f), l(0f to 5f, 6f to 5f, 6f to 10f, 0f to 10f)),
    'C' to g(l(6f to 0f, 0f to 0f, 0f to 10f, 6f to 10f)),
    'D' to g(l(0f to 0f, 4f to 0f, 4f to 2f, 6f to 2f, 6f to 10f, 0f to 10f, 0f to 0f)),
    'F' to g(l(6f to 0f, 0f to 0f, 0f to 10f), l(0f to 5f, 4f to 5f)),
    'G' to g(l(6f to 0f, 0f to 0f, 0f to 10f, 6f to 10f, 6f to 5f, 3f to 5f)),
    'H' to g(l(0f to 0f, 0f to 10f), l(6f to 0f, 6f to 10f), l(0f to 5f, 6f to 5f)),
    'I' to g(l(0f to 0f, 6f to 0f), l(3f to 0f, 3f to 10f), l(0f to 10f, 6f to 10f)),
    'J' to g(l(6f to 0f, 6f to 10f, 0f to 10f, 0f to 7f)),
    'K' to g(l(0f to 0f, 0f to 10f), l(6f to 0f, 6f to 3f, 3f to 3f, 3f to 7f, 6f to 7f, 6f to 10f), l(0f to 5f, 3f to 5f)),
    'L' to g(l(0f to 0f, 0f to 10f, 6f to 10f)),
    'M' to g(l(0f to 10f, 0f to 0f, 6f to 0f, 6f to 10f), l(3f to 0f, 3f to 6f)),
    'N' to g(l(0f to 10f, 0f to 0f, 6f to 0f, 6f to 10f)),
    'O' to g(l(0f to 0f, 6f to 0f, 6f to 10f, 0f to 10f, 0f to 0f)),
    'P' to g(l(0f to 10f, 0f to 0f, 6f to 0f, 6f to 5f, 0f to 5f)),
    'T' to g(l(0f to 0f, 6f to 0f), l(3f to 0f, 3f to 10f)),
    'V' to g(l(0f to 0f, 0f to 7f, 2f to 7f, 2f to 10f, 4f to 10f, 4f to 7f, 6f to 7f, 6f to 0f)),
    'W' to g(l(0f to 0f, 0f to 10f, 6f to 10f, 6f to 0f), l(3f to 10f, 3f to 4f)),
    'X' to g(l(0f to 0f, 0f to 4f, 6f to 4f, 6f to 0f), l(0f to 10f, 0f to 6f, 6f to 6f, 6f to 10f), l(3f to 4f, 3f to 6f)),
    'Y' to g(l(0f to 0f, 0f to 5f, 6f to 5f), l(6f to 0f, 6f to 10f, 0f to 10f)),
    'Z' to g(l(0f to 0f, 6f to 0f, 6f to 5f, 0f to 5f, 0f to 10f, 6f to 10f)),
    '0' to g(l(0f to 0f, 6f to 0f, 6f to 10f, 0f to 10f, 0f to 0f)),
    '1' to g(l(1f to 2f, 3f to 2f, 3f to 0f, 3f to 10f), l(0f to 10f, 6f to 10f)),
    '2' to g(l(0f to 0f, 6f to 0f, 6f to 5f, 0f to 5f, 0f to 10f, 6f to 10f)),
    '3' to g(l(0f to 0f, 6f to 0f, 6f to 10f, 0f to 10f), l(2f to 5f, 6f to 5f)),
    '4' to g(l(0f to 0f, 0f to 6f, 6f to 6f), l(5f to 0f, 5f to 10f)),
    '5' to g(l(6f to 0f, 0f to 0f, 0f to 5f, 6f to 5f, 6f to 10f, 0f to 10f)),
    '6' to g(l(6f to 0f, 0f to 0f, 0f to 10f, 6f to 10f, 6f to 5f, 0f to 5f)),
    '7' to g(l(0f to 0f, 6f to 0f, 6f to 10f)),
    '8' to g(l(0f to 0f, 6f to 0f, 6f to 10f, 0f to 10f, 0f to 0f), l(0f to 5f, 6f to 5f)),
    '9' to g(l(6f to 5f, 0f to 5f, 0f to 0f, 6f to 0f, 6f to 10f, 0f to 10f)),
    '-' to g(l(1f to 5f, 5f to 5f)),
    '.' to g(l(3f to 10f, 3f to 10f)),
)

/** Every letter, and the accented capitals the app's languages use. */
private val TITLE_GLYPHS: Map<Char, Glyph> = BASE + buildMap {
    val accented = mapOf(
        'A' to "ÀÁÂÄÃÅ", 'E' to "ÈÉÊË", 'I' to "ÌÍÎÏ", 'O' to "ÒÓÔÖÕ", 'U' to "ÙÚÛÜ",
        'N' to "Ñ", 'C' to "Ç",
    )
    accented.forEach { (base, variants) ->
        variants.forEach { put(it, BASE.getValue(base) + listOf(ACCENT)) }
    }
}
