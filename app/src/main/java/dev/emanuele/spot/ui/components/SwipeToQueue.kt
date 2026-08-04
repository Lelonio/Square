package dev.emanuele.spot.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Wraps a row so dragging it to the left queues the track.
 *
 * A hand-rolled drag rather than Material's SwipeToDismiss: that component is
 * built around the row *leaving* the list, and here the row stays exactly where
 * it was — the gesture is a command, not a deletion. It also has to share the
 * vertical axis with a scrolling list, so only horizontal drags are consumed and
 * a mostly-vertical movement is left for the list to handle.
 *
 * The row springs back either way; the action fires once, on release, past a
 * threshold. Committing partway through the drag would fire on every
 * accidental brush of the list.
 */
@Composable
fun SwipeToQueue(
    onQueue: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current

    val threshold = with(density) { THRESHOLD.toPx() }
    val maximum = with(density) { MAX_DRAG.toPx() }

    Box(modifier) {
        // Revealed behind the row, and only drawn once the drag is far enough
        // to mean something — a label flickering under every scroll would be
        // noise.
        val progress = (abs(offset.value) / threshold).coerceIn(0f, 1f)
        if (progress > 0f) {
            Row(
                Modifier
                    .matchParentSize()
                    .padding(horizontal = 30.dp)
                    .graphicsLayer { alpha = progress },
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.PlaylistAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    "In coda",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offset.value }
                .background(MaterialTheme.colorScheme.background)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val commit = -offset.value >= threshold
                            if (commit) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onQueue()
                            }
                            scope.launch { offset.animateTo(0f, tween(220)) }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f, tween(220)) } },
                    ) { change, dragAmount ->
                        change.consume()
                        // Left only, and increasingly resistant: the row can be
                        // pulled clearly past the threshold but never far enough
                        // to look like it is being torn out of the list.
                        val next = (offset.value + dragAmount).coerceIn(-maximum, 0f)
                        scope.launch { offset.snapTo(next) }
                    }
                },
        ) {
            content()
        }
    }
}

private val THRESHOLD = 84.dp
private val MAX_DRAG = 132.dp
