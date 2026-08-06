package dev.lelonio.square.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A scroll position indicator that is also a way to move.
 *
 * A thousand-track playlist is the case this exists for: flinging through it
 * takes a dozen throws and tells you nothing about where you are. Dragging the
 * thumb crosses the whole list in one gesture.
 *
 * Position is measured in items rather than in pixels. A lazy list only knows
 * the height of what is currently on screen, so a pixel-accurate bar would need
 * every row measured up front — which is exactly what a lazy list exists to
 * avoid. Rows here are near enough the same height that counting them is
 * indistinguishable from measuring them, and it stays correct with the header
 * and the album strip in the list.
 *
 * Hidden on short lists, where it would be furniture with nothing to do.
 */
@Composable
fun LazyScrollBar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    /** Below this many rows the list is short enough to scroll by hand. */
    minItems: Int = 40,
    /**
     * Content type of the first row the bar should span from.
     *
     * The detail screen opens on a full-bleed cover, and a bar running up the
     * side of the artwork is a scrollbar over a picture. With this set it starts
     * where the tracks start and grows into the full height as the cover scrolls
     * away.
     */
    startAfter: Any? = null,
    thumbHeight: Dp = 52.dp,
    color: Color = Color.White.copy(alpha = 0.55f),
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }

    val enabled by remember { derivedStateOf { state.layoutInfo.totalItemsCount >= minItems } }
    if (!enabled) return

    // Visible while the list is moving and for a moment after, or for as long as
    // it is being dragged. A permanent bar over the artwork is noise; one that
    // appears when you scroll is an answer to the question you just asked.
    val alpha by animateFloatAsState(
        targetValue = if (state.isScrollInProgress || dragging) 1f else 0f,
        animationSpec = tween(if (state.isScrollInProgress || dragging) 120 else 500),
        label = "scrollbarAlpha",
    )

    BoxWithConstraints(
        modifier
            .fillMaxHeight()
            .width(THUMB_WIDTH + TOUCH_MARGIN * 2)
            .pointerInput(state) {
                detectVerticalDragGestures(
                    onDragStart = { down ->
                        dragging = true
                        scope.launch { state.scrollToItem(itemFor(down.y, size.height, state)) }
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    change.consume()
                    scope.launch {
                        state.scrollToItem(itemFor(change.position.y, size.height, state))
                    }
                }
            },
    ) {
        val trackPx = with(density) { maxHeight.toPx() }
        val thumbPx = with(density) { thumbHeight.toPx() }

        Box(
            Modifier
                .padding(horizontal = TOUCH_MARGIN)
                .width(THUMB_WIDTH)
                .height(thumbHeight)
                // Read here rather than in composition: scrolling then moves the
                // thumb without recomposing the list it is drawn over.
                .graphicsLayer {
                    this.alpha = alpha
                    val top = state.topOffsetFor(startAfter)
                    translationY = top + (trackPx - top - thumbPx).coerceAtLeast(0f) *
                        state.progress()
                }
                .clip(RoundedCornerShape(50))
                .background(color),
        )
    }
}

/**
 * Where the run of rows begins on screen, in pixels from the top of the bar.
 *
 * Zero once whatever precedes them has been scrolled off, which is what makes
 * the bar grow to the full height rather than jump.
 */
private fun LazyListState.topOffsetFor(contentType: Any?): Float {
    if (contentType == null) return 0f
    val first = layoutInfo.visibleItemsInfo.firstOrNull { it.contentType == contentType }
        ?: return 0f
    return first.offset.toFloat().coerceAtLeast(0f)
}

/** How far down the list is, 0 to 1, counted in items. */
private fun LazyListState.progress(): Float {
    val info = layoutInfo
    val last = info.totalItemsCount - info.visibleItemsInfo.size
    if (last <= 0) return 0f
    return (firstVisibleItemIndex.toFloat() / last).coerceIn(0f, 1f)
}

/** Which item a touch at [y] within a bar [height] tall is asking for. */
private fun itemFor(y: Float, height: Int, state: LazyListState): Int {
    val info = state.layoutInfo
    val last = (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(0)
    val fraction = (y / height.toFloat()).coerceIn(0f, 1f)
    return (fraction * last).roundToInt().coerceIn(0, last)
}

private val THUMB_WIDTH = 4.dp

/** Widens the target without widening the mark; 4dp of bar is not grabbable. */
private val TOUCH_MARGIN = 10.dp
