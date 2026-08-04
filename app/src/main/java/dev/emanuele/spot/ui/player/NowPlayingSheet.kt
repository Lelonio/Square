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
 * They still are, in the sense that matters: one `progress` value owns both, so
 * the bar and the player are two views of a single state rather than two screens
 * taking turns.
 *
 * How that value is spent went through a growing container — the bar's height
 * stretched up to the window's, with the player already laid out full size
 * inside it. It looked right and it was never quite smooth: resizing that
 * container re-ran a layout pass over a screen holding a video surface and
 * several pieces of live glass, sixty times a second, and deferring every state
 * read only moved the cost around.
 *
 * So the travel is now a transform and nothing else. The player is full size
 * from the first frame, rises a little way into place and fades in as the bar
 * fades out under it. One layer, scaled and faded on the render thread, with no
 * measure or layout anywhere in the frame.
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

        // The player, full size from the first frame to the last. Nothing about
        // the travel touches measure or layout any more: the growing container
        // that used to drive it re-ran a layout pass on the whole player every
        // frame, and no amount of deferring state reads makes that free on a
        // screen with a video surface and a stack of glass in it. What is left
        // is one layer being scaled and faded, which the render thread does
        // without waking the UI thread at all.
        if (expandedVisible) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val f = progress.value.coerceIn(0f, 1f)
                        alpha = f
                        // Rises into place from just below, slightly smaller.
                        // Small numbers on purpose — this is the player arriving
                        // where it already is, not flying in from off-screen.
                        val scale = ENTER_SCALE + (1f - ENTER_SCALE) * f
                        scaleX = scale
                        scaleY = scale
                        translationY = (1f - f) * size.height * ENTER_TRAVEL
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        // Rounded while it is on its way in and square once it
                        // has arrived, so it reads as a sheet rather than as a
                        // screen appearing.
                        shape = RoundedCornerShape(SHEET_CORNER * (1f - f))
                        clip = true
                        // Modulated rather than composited: the default draws
                        // the whole subtree into an offscreen buffer the size of
                        // the window before applying alpha, every frame.
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
            ) {
                background()
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalGlassEnabled provides settled,
                ) {
                    expandedContent()
                }
            }
        }

        // The bar stays where it is and fades under the arriving player. It used
        // to be the same surface, stretched — which is the effect this is
        // giving up, and with it the stutter that came from stretching it.
        if (collapsedVisible) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = SIDE_MARGIN)
                    .padding(bottom = bottomInset)
                    .height(collapsedHeight)
                    .fillMaxWidth()
                    .graphicsLayer { alpha = collapsedAlpha(progress.value) }
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
                            // Handed to the spring rather than dropped.
                            settle(target, initialVelocity = -velocity / travelPx)
                        },
                    ),
            ) {
                collapsedContent()
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

/** Where the bar has finished leaving and the player begins to arrive. */
private const val HANDOVER = 0.3f

/** How small the player starts, growing to its own size as it arrives. */
private const val ENTER_SCALE = 0.92f

/** How far below its place it starts, as a fraction of its height. */
private const val ENTER_TRAVEL = 0.06f

/** Margin the collapsed bar keeps from the edges of the window. */
private val SIDE_MARGIN = 16.dp

private val SHEET_CORNER = 22.dp

/** Past this, a drag is a flick and its direction decides where it lands. */
private const val FLING_VELOCITY = 900f
