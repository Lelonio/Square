package dev.emanuele.spot.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Horizontal drag on the cover: left for the next track, right for the previous.
 *
 * Only this axis. Closing the screen used to live here too, which meant the
 * gesture worked on the cover alone and dragged the cover out from under the
 * rest of the screen — see [DismissibleScreen], which now owns the vertical axis
 * for the whole player.
 *
 * The cover follows the finger and fades as it goes, so the gesture is
 * discoverable: a swipe that does nothing visible until it completes gives no
 * hint that it exists.
 */
@Composable
fun CoverGestures(
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    canGoNext: Boolean,
    canGoPrevious: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current

    val screenWidth = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val threshold = screenWidth * 0.22f

    Box(
        modifier
            .graphicsLayer {
                translationX = offsetX.value
                // Dimmest exactly where releasing would act.
                val travel = abs(offsetX.value) / threshold
                alpha = (1f - travel * 0.45f).coerceIn(0.4f, 1f)
                val shrink = (1f - travel * 0.06f).coerceIn(0.9f, 1f)
                scaleX = shrink
                scaleY = shrink
            }
            .pointerInput(canGoNext, canGoPrevious) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val committed = when {
                            offsetX.value <= -threshold && canGoNext -> {
                                onNext(); true
                            }

                            offsetX.value >= threshold && canGoPrevious -> {
                                onPrevious(); true
                            }

                            else -> false
                        }
                        if (committed) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        // Always springs back, including on a commit: the cover
                        // is about to be replaced by the next track's, and
                        // leaving it parked off-screen would show the new art
                        // already displaced.
                        scope.launch { offsetX.animateTo(0f, spring()) }
                    },
                    onDragCancel = { scope.launch { offsetX.animateTo(0f, tween(200)) } },
                ) { change, drag ->
                    change.consume()
                    // Resists in the direction that has nowhere to go rather
                    // than refusing to move: a dead gesture is indistinguishable
                    // from a broken one.
                    val blocked = (drag < 0 && !canGoNext) || (drag > 0 && !canGoPrevious)
                    val step = if (blocked) drag * 0.25f else drag
                    scope.launch { offsetX.snapTo(offsetX.value + step) }
                }
            },
    ) {
        content()
    }
}
