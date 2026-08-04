package dev.emanuele.spot.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The now-playing bar and the player, as one surface.
 *
 * The previous version made the player a navigation destination and tried to
 * animate between the two. That can only ever cross-fade: they are separate
 * screens, both on stage at once, and no amount of shared-element work hides
 * that the bar and the player are two different things pretending to be one.
 *
 * Here they *are* one. A single container sits at the bottom of the window and
 * its height runs from the bar's to the whole window's; the player is inside it
 * the entire time, laid out at full height and revealed as the container grows,
 * and the bar's row fades out over the first quarter of the travel. Nothing is
 * re-measured while it moves — the player keeps its final layout throughout —
 * so the motion is a single edge sliding up, not two screens dissolving.
 *
 * The same value is what a drag writes to, which is why dragging down works at
 * any point and can be released anywhere: expansion is a position, not an event.
 *
 * @param progress 0 while collapsed, 1 while expanded. Hoisted because the tab
 *   bar has to fade out against it.
 */
@Composable
fun NowPlayingSheet(
    progress: Animatable<Float, *>,
    /** Space the collapsed bar leaves for the tab bar beneath it. */
    bottomInset: Dp,
    collapsedContent: @Composable () -> Unit,
    expandedContent: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // Clamped, because the settle spring is under-damped on purpose: it passes
    // 1 on the way in, and an interpolated padding past that end is negative,
    // which Compose rejects outright.
    val fraction = progress.value.coerceIn(0f, 1f)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fullHeight = maxHeight
        val collapsedHeight = MiniPlayerHeight
        val travelPx = with(density) { (fullHeight - collapsedHeight).toPx() }

        fun settle(target: Float) {
            scope.launch {
                progress.animateTo(
                    target,
                    // Slightly under-damped: it arrives with the smallest
                    // settle rather than stopping dead, which is what makes a
                    // panel this size feel like it has weight.
                    spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
                )
            }
        }

        BackHandler(enabled = fraction > 0.5f) { settle(0f) }

        val draggable = rememberDraggableState { delta ->
            scope.launch {
                // Dragging up is negative, and up means expanding.
                progress.snapTo((progress.value - delta / travelPx).coerceIn(0f, 1f))
            }
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = lerp(16.dp, 0.dp, fraction))
                // The bar floats above the tab bar; the player owns the whole
                // window.
                .padding(bottom = lerp(bottomInset, 0.dp, fraction))
                .height(lerp(collapsedHeight, fullHeight, fraction))
                .clip(RoundedCornerShape(lerp(22.dp, 0.dp, fraction)))
                .draggable(
                    state = draggable,
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity ->
                        // Velocity decides when the gesture was a flick;
                        // otherwise the halfway point does. Without the
                        // velocity test a fast short flick would snap back,
                        // which reads as the gesture having been ignored.
                        val target = when {
                            abs(velocity) > FLING_VELOCITY -> if (velocity < 0) 1f else 0f
                            fraction > 0.5f -> 1f
                            else -> 0f
                        }
                        settle(target)
                    },
                ),
        ) {
            // Laid out at its final size from the start and simply uncovered.
            // Re-measuring a screen this complex on every frame of the travel is
            // what would make it stutter.
            if (fraction > 0.001f) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .height(fullHeight)
                        .fillMaxWidth()
                        .graphicsLayer { alpha = expandedAlpha(fraction) },
                ) {
                    expandedContent()
                }
            }

            if (fraction < 0.999f) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .height(collapsedHeight)
                        .fillMaxWidth()
                        .graphicsLayer { alpha = collapsedAlpha(fraction) },
                ) {
                    collapsedContent()
                }
            }
        }
    }
}

/**
 * The bar is gone by a fifth of the way up, the player is there by two thirds.
 *
 * They deliberately do not cross at 0.5: with both half-visible in the middle
 * of the travel you see two sets of controls at once, which is precisely what
 * the cross-fade version looked like.
 */
private fun collapsedAlpha(fraction: Float): Float =
    (1f - fraction / 0.2f).coerceIn(0f, 1f)

private fun expandedAlpha(fraction: Float): Float =
    ((fraction - 0.15f) / 0.5f).coerceIn(0f, 1f)

/** Past this, a drag is a flick and its direction decides where it lands. */
private const val FLING_VELOCITY = 900f
