package dev.lelonio.square.ui.player

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.lelonio.square.ui.glass.LiquidSlider
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/** The thumb's size while held, which is the size it is drawn at. */
private val THUMB_HELD = 36.dp

/** The height the bar takes in the column, the touch target's. */
private val BAR_ROOM = 28.dp

/** How long the thumb waits at a seek's target for playback to reach it. */
private const val SETTLE_MS = 2_000L

/** Close enough to the target, as a share of the song, to let go of it. */
private const val SETTLE_CLOSE = 0.01f

/**
 * The playback position, as a thin capsule.
 *
 * Not [dev.lelonio.square.ui.glass.LiquidSlider], and not the waveform this
 * replaced. The waveform looked good but invented its shape, and the reference
 * this screen follows uses a plain bar. The library's slider is the wrong tool
 * for a different reason: it reports every intermediate value, and each one here
 * would be a seek — the engine would spend a drag re-buffering instead of
 * playing.
 *
 * So the position is scrubbed locally and committed once, on release. While a
 * drag is in progress the incoming position is ignored, or each update would
 * yank the bar back to where playback still is.
 *
 * Given a [backdrop], it is the liquid slider after all — the same glass thumb
 * as the effects panel — with the seek still made once, when the finger lifts;
 * see LiquidSlider's onValueChangeFinished.
 */
@Composable
fun GlassProgressBar(
    positionMs: State<Long>,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    accentColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    /**
     * Where the finger is, in milliseconds, while it holds the bar; null once
     * the bar is showing playback again. For the time beside it, which would
     * otherwise say where the song is rather than where it is being taken.
     */
    onScrub: (Long?) -> Unit = {},
) {
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    var width by remember { mutableStateOf(1f) }
    val latestOnScrub by rememberUpdatedState(onScrub)
    LaunchedEffect(scrubbing, durationMs) {
        latestOnScrub(scrubbing?.let { (it * durationMs).toLong() })
    }

    if (backdrop != null) {
        // Where the finger left the thumb, held until playback gets there. Let
        // go straight away and the thumb slid back to the old position, then
        // forward again once the seek landed: a bounce on every scrub.
        var settling by remember { mutableStateOf<Float?>(null) }
        LaunchedEffect(settling) {
            val target = settling ?: return@LaunchedEffect
            withTimeoutOrNull(SETTLE_MS) {
                snapshotFlow { progressOf(positionMs.value, durationMs) }
                    .first { abs(it - target) < SETTLE_CLOSE }
            }
            if (settling == target) {
                scrubbing = null
                settling = null
            }
        }
        LiquidSlider(
            value = { scrubbing ?: progressOf(positionMs.value, durationMs) },
            onValueChange = {
                settling = null
                scrubbing = it
            },
            onValueChangeFinished = {
                val target = scrubbing
                if (target != null && durationMs > 0) {
                    onSeek((target * durationMs).toLong())
                    settling = target
                }
            },
            valueRange = 0f..1f,
            // A second of a long song is still a step the thumb should take.
            visibilityThreshold = 0.0005f,
            backdrop = backdrop,
            accentColor = accentColor,
            trackColor = trackColor,
            // Small at rest, and grows under the finger to the size it is
            // drawn at.
            thumbSize = DpSize(THUMB_HELD, THUMB_HELD),
            thumbRestScale = 14f / 36f,
            thumbPressedScale = 1f,
            // As tall as the held thumb, or the part of it past the slider's
            // edge was cut off and it came out flattened underneath; but
            // taking the old bar's 28dp in the column, so nothing around it
            // moves.
            modifier = modifier.layout { measurable, constraints ->
                val tall = THUMB_HELD.roundToPx()
                val placeable = measurable.measure(
                    constraints.copy(minHeight = tall, maxHeight = tall),
                )
                val room = BAR_ROOM.roundToPx()
                layout(placeable.width, room) {
                    placeable.place(0, (room - placeable.height) / 2)
                }
            },
        )
        return
    }

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
