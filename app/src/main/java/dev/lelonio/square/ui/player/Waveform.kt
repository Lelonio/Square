package dev.lelonio.square.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * The seek bar, drawn as a waveform.
 *
 * The shape is *not* the track's real audio: the decoded samples live inside
 * librespot and never reach the UI, and asking for them would mean buffering a
 * whole track before it could be drawn. It is generated from the track's URI
 * instead, so a given song always looks the same, different songs look
 * different, and the bar still does the one job a progress bar has — showing
 * where you are and letting you move.
 *
 * Presenting invented data as if it were measured would be wrong if it implied
 * something about the audio; here it reads as decoration on a scrubber, and the
 * played/remaining split it encodes is real.
 */
@Composable
fun Waveform(
    /** Seeds the shape. Same track, same waveform, across restarts. */
    trackKey: String?,
    positionMs: State<Long>,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 46.dp,
    playedColor: Color,
    remainingColor: Color,
) {
    val bars = remember(trackKey) { generateBars(trackKey) }

    // While dragging, the bar follows the finger rather than the player:
    // position updates keep arriving and would otherwise yank it back between
    // drag events.
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    var width by remember { mutableStateOf(1f) }

    fun commit(x: Float) {
        val fraction = (x / width).coerceIn(0f, 1f)
        scrubbing = fraction
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    if (durationMs <= 0) return@detectTapGestures
                    onSeek(((offset.x / size.width).coerceIn(0f, 1f) * durationMs).toLong())
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> commit(offset.x) },
                    onDragEnd = {
                        scrubbing?.let { onSeek((it * durationMs).toLong()) }
                        scrubbing = null
                    },
                    onDragCancel = { scrubbing = null },
                ) { change, _ -> commit(change.position.x) }
            },
    ) {
        width = size.width
        val progress = scrubbing ?: progressOf(positionMs.value, durationMs)

        // Bars are laid out to a fixed count so the density stays the same on
        // every screen width, rather than getting sparser on a tablet.
        val step = size.width / BAR_COUNT
        val barWidth = (step * 0.44f).coerceAtLeast(1f)
        val centre = size.height / 2f
        val playedUntil = size.width * progress

        bars.forEachIndexed { index, amplitude ->
            val x = index * step + step / 2f
            val half = (amplitude * size.height / 2f).coerceAtLeast(barWidth / 2f)
            drawLine(
                color = if (x <= playedUntil) playedColor else remainingColor,
                start = Offset(x, centre - half),
                end = Offset(x, centre + half),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

private const val BAR_COUNT = 68

/**
 * A waveform-shaped envelope rather than plain noise.
 *
 * Pure random heights read as a barcode; real music has loud stretches and quiet
 * ones. Two slow sine waves supply that swell and the noise roughens it, which
 * is what makes the result read as audio at a glance.
 */
private fun generateBars(trackKey: String?): List<Float> {
    val random = Random(trackKey?.hashCode() ?: 0)
    return List(BAR_COUNT) { index ->
        val t = index.toFloat() / BAR_COUNT
        val envelope = 0.45f +
            0.30f * abs(sin(t * Math.PI * 2.4f).toFloat()) +
            0.18f * abs(sin(t * Math.PI * 7.1f + 1.3f).toFloat())
        (envelope * (0.55f + random.nextFloat() * 0.75f)).coerceIn(0.06f, 1f)
    }
}
