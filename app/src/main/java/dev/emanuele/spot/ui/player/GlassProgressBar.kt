package dev.emanuele.spot.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The playback position, as a thin capsule.
 *
 * Not [dev.emanuele.spot.ui.glass.LiquidSlider], and not the waveform this
 * replaced. The waveform looked good but invented its shape, and the reference
 * this screen follows uses a plain bar. The library's slider is the wrong tool
 * for a different reason: it reports every intermediate value, and each one here
 * would be a seek — the engine would spend a drag re-buffering instead of
 * playing.
 *
 * So the position is scrubbed locally and committed once, on release. While a
 * drag is in progress the incoming position is ignored, or each update would
 * yank the bar back to where playback still is.
 */
@Composable
fun GlassProgressBar(
    positionMs: State<Long>,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    accentColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    var width by remember { mutableStateOf(1f) }

    val progress = scrubbing ?: progressOf(positionMs.value, durationMs)

    Box(
        modifier
            .fillMaxWidth()
            // The touch target is taller than the bar. A 6dp-high capsule is
            // accurate to look at and almost impossible to grab.
            .height(28.dp)
            .pointerInput(durationMs) {
                width = size.width.toFloat()
                detectTapGestures { offset ->
                    if (durationMs > 0) {
                        onSeek(((offset.x / size.width).coerceIn(0f, 1f) * durationMs).toLong())
                    }
                }
            }
            .pointerInput(durationMs) {
                width = size.width.toFloat()
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        scrubbing = (offset.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        scrubbing?.let { onSeek((it * durationMs).toLong()) }
                        scrubbing = null
                    },
                    onDragCancel = { scrubbing = null },
                ) { change, _ ->
                    change.consume()
                    scrubbing = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            },
    ) {
        Box(
            Modifier
                .padding(vertical = 11.dp)
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(trackColor),
        )
        Box(
            Modifier
                .padding(vertical = 11.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(accentColor)
                .fillMaxHeight()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val w = (constraints.maxWidth * progress).roundToInt()
                    layout(w, placeable.height) { placeable.place(0, 0) }
                },
        )
    }
}
