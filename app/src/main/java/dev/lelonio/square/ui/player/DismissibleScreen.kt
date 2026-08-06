package dev.lelonio.square.ui.player

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

/**
 * Drag down anywhere on the screen to close it.
 *
 * A gesture, not a transform: the screen does not follow the finger. It used to
 * — it slid, shrank and faded with the drag — but the player is already a sheet
 * that animates itself into the now-playing bar, so a second, different
 * displacement of the same content on the same axis meant a downward drag pulled
 * the whole screen around before anything committed. Once the drag passes the
 * threshold this hands over to that one animation, which is the only thing that
 * should be moving the player.
 *
 * Wired through [NestedScrollConnection] rather than a plain drag detector
 * because the content scrolls. A drag detector on the parent and a scrolling
 * child fight over the same vertical axis; nested scroll gives a defined order —
 * the list gets the drag first, and only what it cannot use reaches this. So a
 * downward drag scrolls the content until it hits the top, and from there the
 * same continuous movement closes the screen. The pointer input below covers the
 * parts that do not scroll at all.
 */
@Composable
fun DismissibleScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current

    val threshold = with(density) { DISMISS_THRESHOLD.toPx() }

    // Plain vars in a remembered holder rather than state: nothing recomposes on
    // them, they only have to survive between drag events.
    val travel = remember { Travel() }

    fun reset() {
        travel.distance = 0f
        travel.fired = false
    }

    fun drag(amount: Float) {
        if (travel.fired) return
        // Upward movement gives the accumulated distance back instead of going
        // negative, so a drag that wanders up and down does not close the player
        // on its total rather than its extent.
        travel.distance = (travel.distance + amount).coerceAtLeast(0f)
        if (travel.distance >= threshold) {
            travel.fired = true
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onDismiss()
        }
    }

    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0) travel.distance = 0f
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // Only what the content could not use: at the top of the scroll
                // this is the whole drag, anywhere else it is nothing.
                if (available.y > 0) {
                    drag(available.y)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                reset()
                return Velocity.Zero
            }
        }
    }

    Box(
        modifier
            .nestedScroll(connection)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { reset() },
                    onDragEnd = { reset() },
                    onDragCancel = { reset() },
                ) { change, amount ->
                    change.consume()
                    drag(amount)
                }
            },
    ) {
        content()
    }
}

private class Travel {
    var distance = 0f
    var fired = false
}

private val DISMISS_THRESHOLD = 110.dp
