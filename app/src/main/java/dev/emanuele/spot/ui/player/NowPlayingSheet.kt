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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
    /**
     * Drawn under the player, fading in with it.
     *
     * The player used to be a destination stacked over the app's own backdrop
     * and so had no background of its own. As a sheet it sits *above* the page,
     * so without this the home screen showed through every track that has no
     * Canvas to cover it.
     */
    background: @Composable () -> Unit,
    collapsedContent: @Composable () -> Unit,
    expandedContent: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Nothing here reads `progress.value` during composition, and that is the
    // whole performance story of this file.
    //
    // Reading it in the composable body recomposed the sheet — and with it the
    // entire player and the bar — on every frame of the travel, which measured
    // at a 97ms median frame and a 250ms worst case. Read from inside a layout
    // or a graphicsLayer block instead, the same value only re-measures or
    // re-draws, which is two phases cheaper.
    //
    // These two are derived rather than read directly so a recomposition
    // happens when the answer *changes*, not when the number does.
    val expandedVisible by remember { derivedStateOf { progress.value > 0.001f } }

    // True only at the two ends of the travel; see LocalGlassEnabled.
    val settled by remember {
        derivedStateOf { progress.value <= 0.001f || progress.value >= 0.999f }
    }
    val collapsedVisible by remember { derivedStateOf { progress.value < 0.999f } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fullHeight = maxHeight
        val collapsedHeight = MiniPlayerHeight
        val travelPx = with(density) { (fullHeight - collapsedHeight).toPx() }

        // One spec for both directions, and the same one the tap uses: closing
        // is meant to be the opening played backwards, so nothing about the
        // motion may depend on which way it is going.
        fun settle(target: Float, initialVelocity: Float = 0f) {
            scope.launch {
                progress.animateTo(
                    target,
                    // Slightly under-damped: it arrives with the smallest
                    // settle rather than stopping dead, which is what makes a
                    // panel this size feel like it has weight.
                    spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
                    initialVelocity = initialVelocity,
                )
            }
        }

        BackHandler(enabled = expandedVisible) { settle(0f) }

        val draggable = rememberDraggableState { delta ->
            scope.launch {
                // Dragging up is negative, and up means expanding.
                progress.snapTo((progress.value - delta / travelPx).coerceIn(0f, 1f))
            }
        }

        val marginPx = with(density) { SIDE_MARGIN.roundToPx() }
        val insetPx = with(density) { bottomInset.roundToPx() }
        val collapsedPx = with(density) { collapsedHeight.roundToPx() }
        val fullPx = with(density) { fullHeight.roundToPx() }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // Size, margins and inset in one measure pass, all read here
                // rather than in composition.
                .layout { measurable, constraints ->
                    val f = progress.value.coerceIn(0f, 1f)
                    val margin = ((1f - f) * marginPx).toInt()
                    val inset = ((1f - f) * insetPx).toInt()
                    val height = collapsedPx + ((fullPx - collapsedPx) * f).toInt()

                    val width = (constraints.maxWidth - margin * 2).coerceAtLeast(0)
                    val placeable = measurable.measure(
                        constraints.copy(
                            minWidth = width,
                            maxWidth = width,
                            minHeight = height,
                            maxHeight = height,
                        ),
                    )
                    layout(constraints.maxWidth, height + inset) {
                        placeable.place(margin, 0)
                    }
                }
                .graphicsLayer {
                    val f = progress.value.coerceIn(0f, 1f)
                    shape = RoundedCornerShape(SHEET_CORNER * (1f - f))
                    clip = true
                }
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
                            progress.value > 0.5f -> 1f
                            else -> 0f
                        }
                        // Handed to the spring rather than dropped. Releasing
                        // mid-drag otherwise restarted the motion from a dead
                        // stop, which is the one thing a tap-to-open never does
                        // and is why closing felt unlike opening reversed.
                        settle(target, initialVelocity = -velocity / travelPx)
                    },
                ),
        ) {
            if (expandedVisible) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .height(fullHeight)
                        .fillMaxWidth()
                        // Tracks the travel one to one, so what is behind the
                        // player — the page it was opened from — is visible
                        // through it while it is being dragged away. It used to
                        // reach full opacity in the first quarter, which made
                        // the content slide down over a background that never
                        // moved and never let anything through.
                        .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) },
                ) {
                    background()
                }
            }

            // Laid out at its final size from the start and simply uncovered.
            // Re-measuring a screen this complex on every frame of the travel is
            // what would make it stutter.
            if (expandedVisible) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .height(fullHeight)
                        .fillMaxWidth()
                        .graphicsLayer {
                            val f = progress.value.coerceIn(0f, 1f)
                            alpha = expandedAlpha(f)
                            // Contracts towards the bottom edge as it closes.
                            //
                            // Without this the player was a full-size screen
                            // being slid down behind a shrinking window: every
                            // element kept its size right up to the moment it
                            // was gone, which reads as a screen leaving rather
                            // than as a panel collapsing into the bar. The
                            // origin is the bottom because that is where the bar
                            // it is becoming actually is.
                            val scale = COLLAPSE_SCALE + (1f - COLLAPSE_SCALE) * f
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0.5f, 1f)
                            // Modulated rather than composited: the default
                            // draws the whole subtree into an offscreen buffer
                            // the size of the window before applying alpha,
                            // every frame. Modulating is per-draw-call and looks
                            // identical here because nothing in the player
                            // overlaps itself.
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        },
                ) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalGlassEnabled provides settled,
                    ) {
                        expandedContent()
                    }
                }
            }

            if (collapsedVisible) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .height(collapsedHeight)
                        .fillMaxWidth()
                        .graphicsLayer { alpha = collapsedAlpha(progress.value) },
                ) {
                    collapsedContent()
                }
            }
        }
    }
}

/**
 * The bar is gone by a third of the way up, and the player starts there.
 *
 * They hand over rather than overlap: with both half-visible you see two sets
 * of controls at once, which is what the cross-dissolve version looked like.
 * They also hand over at exactly the same point in both directions — the two
 * used to leave a sliver of travel where the bar had gone and the player had
 * not arrived, and running that backwards is not the same as running it
 * forwards.
 */
private fun collapsedAlpha(fraction: Float): Float =
    (1f - fraction / HANDOVER).coerceIn(0f, 1f)

private fun expandedAlpha(fraction: Float): Float =
    ((fraction - HANDOVER) / (1f - HANDOVER) * 2f).coerceIn(0f, 1f)

/** Where the bar has finished leaving and the player begins to arrive. */
private const val HANDOVER = 0.3f

/**
 * How far the player is shrunk when fully collapsed.
 *
 * Enough to read as a contraction; much below this and the text inside starts
 * to look like it is being squashed rather than moving away.
 */
private const val COLLAPSE_SCALE = 0.86f

/** Margin the collapsed bar keeps from the edges of the window. */
private val SIDE_MARGIN = 16.dp

private val SHEET_CORNER = 22.dp

/** Past this, a drag is a flick and its direction decides where it lands. */
private const val FLING_VELOCITY = 900f
