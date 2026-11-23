package dev.lelonio.square.ui.player

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

/**
 * Drag down on the cover to close the screen.
 *
 * Only on the cover. It used to be anywhere — the whole screen through the
 * pointer input, and the lyrics and the queue once scrolled to their top — and
 * a finger moving down over the controls, aiming for a button or the progress
 * bar, closed the player instead. The screen provides the gesture as
 * [LocalDismissDrag]; the cover (see [CoverGestures]) is what applies it.
 *
 * A gesture, not a transform: the screen does not follow the finger. It used to
 * — it slid, shrank and faded with the drag — but the player is already a sheet
 * that animates itself into the now-playing bar, so a second, different
 * displacement of the same content on the same axis meant a downward drag pulled
 * the whole screen around before anything committed. Once the drag passes the
 * threshold this hands over to that one animation, which is the only thing that
 * should be moving the player.
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
    val dismiss by androidx.compose.runtime.rememberUpdatedState(onDismiss)

    // Plain vars in a remembered holder rather than state: nothing recomposes on
    // them, they only have to survive between drag events.
    val travel = remember { Travel() }
    val lock = remember { mutableStateOf(false) }

    fun reset() {
        travel.distance = 0f
        travel.fired = false
    }

    fun drag(amount: Float) {
        if (travel.fired || lock.value) return
        // Upward movement gives the accumulated distance back instead of going
        // negative, so a drag that wanders up and down does not close the player
        // on its total rather than its extent.
        travel.distance = (travel.distance + amount).coerceAtLeast(0f)
        if (travel.distance >= threshold) {
            travel.fired = true
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            dismiss()
        }
    }

    val dragToDismiss = remember {
        Modifier.pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragStart = { reset() },
                onDragEnd = { reset() },
                onDragCancel = { reset() },
            ) { change, amount ->
                change.consume()
                drag(amount)
            }
        }
    }

    Box(modifier) {
        CompositionLocalProvider(
            LocalDismissLock provides lock,
            LocalDismissDrag provides dragToDismiss,
        ) { content() }
    }
}

/**
 * The drag that closes the screen, for the one place it belongs: the cover.
 * Nothing outside a [DismissibleScreen].
 */
val LocalDismissDrag = staticCompositionLocalOf<Modifier> { Modifier }

/**
 * Set by whatever inside takes a downward drag for its own, such as a queue row
 * being moved by its handle: while it holds, dragging down does not close.
 */
val LocalDismissLock = staticCompositionLocalOf<MutableState<Boolean>> { mutableStateOf(false) }

private class Travel {
    var distance = 0f
    var fired = false
}

private val DISMISS_THRESHOLD = 110.dp
