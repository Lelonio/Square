package dev.emanuele.spot.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Drag anywhere on the screen to push it down and close it.
 *
 * The whole screen moves, not just the cover. The first version put this on the
 * cover alongside the track-change swipe, which had two problems: the gesture
 * only worked if you happened to start on the artwork, and it slid the cover
 * out from under a header and a set of controls that stayed put — the screen
 * came apart instead of leaving.
 *
 * Wired through [NestedScrollConnection] rather than a plain drag detector
 * because the content scrolls. A drag detector on the parent and a scrolling
 * child fight over the same vertical axis; nested scroll gives a defined order —
 * the list gets the drag first, and only what it cannot use reaches this. So a
 * downward drag scrolls the content until it hits the top, and from there the
 * same continuous movement starts closing the screen. The pointer input below
 * covers the parts that do not scroll at all.
 */
@Composable
fun DismissibleScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current

    val threshold = with(density) { DISMISS_THRESHOLD.toPx() }

    fun settle() {
        if (offset.value >= threshold) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onDismiss()
            // Not animated away: the navigation transition takes over from here,
            // and running both would play two exits on top of each other.
            scope.launch { offset.snapTo(0f) }
        } else {
            scope.launch { offset.animateTo(0f, spring()) }
        }
    }

    fun drag(amount: Float) {
        // Upward movement is resisted rather than ignored, so overscrolling at
        // the top has some give instead of stopping dead.
        val next = if (offset.value + amount < 0f) offset.value + amount * 0.2f else offset.value + amount
        scope.launch { offset.snapTo(next.coerceAtLeast(0f)) }
    }

    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // While the screen is already pulled down, an upward drag puts
                // it back before the content is allowed to scroll again —
                // otherwise the list starts moving with the screen still
                // displaced.
                if (available.y < 0 && offset.value > 0f) {
                    drag(available.y)
                    return Offset(0f, available.y)
                }
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
                if (offset.value > 0f) {
                    settle()
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    Box(
        modifier
            .graphicsLayer {
                translationY = offset.value
                // Scales and fades with the travel so the screen reads as
                // receding, not merely sliding off the bottom.
                val travel = (offset.value / threshold).coerceIn(0f, 1f)
                val shrink = 1f - travel * 0.08f
                scaleX = shrink
                scaleY = shrink
                alpha = 1f - travel * 0.35f
            }
            .nestedScroll(connection)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { settle() },
                    onDragCancel = { scope.launch { offset.animateTo(0f, spring()) } },
                ) { change, amount ->
                    change.consume()
                    drag(amount)
                }
            },
    ) {
        content()
    }
}

private val DISMISS_THRESHOLD = 140.dp
