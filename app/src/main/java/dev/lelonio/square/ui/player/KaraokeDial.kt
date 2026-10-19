package dev.lelonio.square.ui.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.MicrophoneStage
import com.adamglin.phosphoricons.regular.MicrophoneStage
import dev.lelonio.square.R
import dev.lelonio.square.ui.glass.LocalGlassEffectConfig
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import dev.lelonio.square.ui.glass.liquidGlass
import dev.lelonio.square.ui.glass.shapes.ContinuousCapsule

/**
 * How much of the singing to take away.
 *
 * Closed it is a button with a microphone on it, the width of a thumb; touched,
 * it grows into a standing capsule filled from the bottom, which is dragged to
 * set the level and closes again when it is let go of. That is the shape Apple
 * gives the same idea, and the reason for it is the same: a lyric sheet is what
 * the panel is for, and a control that is used once a song should not hold a
 * band of it open the rest of the time.
 */
@Composable
fun KaraokeDial(
    amount: Float,
    onChange: (Float) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    /**
     * Bumped from outside to open it, for the times somebody arrives here on
     * purpose — tapping the badge over the cover is asking for this control,
     * and finding it closed would be arriving at the wrong place.
     */
    expandSignal: Int = 0,
) {
    var open by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val change by rememberUpdatedState(onChange)
    // Read inside the gesture rather than captured by it.
    //
    // The handler is built once and lives for the length of the touch, so a
    // plain reference to the parameter is the value as it was when the finger
    // landed: every move then measured itself from the same starting point,
    // which is why dragging moved the level once and then refused to climb.
    val latest = rememberUpdatedState(amount)
    // Read and written from inside the gesture, where composition state cannot
    // be trusted to have caught up yet.
    val openNow = remember { androidx.compose.runtime.mutableStateOf(false) }
    val density = LocalDensity.current

    // Closes itself after a moment's stillness, so nobody has to put it away.
    var lastTouch by remember { mutableStateOf(0L) }

    LaunchedEffect(expandSignal) {
        if (expandSignal > 0) {
            open = true
            openNow.value = true
            lastTouch = System.currentTimeMillis()
        }
    }

    LaunchedEffect(open, lastTouch) {
        if (!open) return@LaunchedEffect
        kotlinx.coroutines.delay(IDLE_MS)
        open = false
    }

    val height by animateDpAsState(
        targetValue = if (open) OPEN_HEIGHT else CLOSED_SIZE,
        animationSpec = tween(220),
        label = "karaokeHeight",
    )
    // Grows under the finger while it is being dragged, and gives a little
    // when it is merely pressed — which is what every other control in the
    // player does, and the only thing that says a tap landed.
    val grip by animateFloatAsState(
        targetValue = when {
            dragging -> 1.08f
            pressed -> 0.94f
            else -> 1f
        },
        animationSpec = tween(140),
        label = "karaokeGrip",
    )
    val lit by animateFloatAsState(
        targetValue = if (pressed || dragging) 0.12f else 0f,
        animationSpec = tween(140),
        label = "karaokePress",
    )
    val fill by animateFloatAsState(
        targetValue = if (open) amount else 0f,
        animationSpec = tween(160),
        label = "karaokeFill",
    )
    // A little longer than the control is tall.
    //
    // Mapped one to one the range was hard to aim; at twice the travel a short
    // slow drag moved the level by a percent or two and read as nothing
    // happening at all. This is between the two, and what keeps the bottom from
    // being a cliff is the step to silence below, not the sensitivity.
    val travelPx = with(density) { OPEN_HEIGHT.toPx() } * 1.25f

    Box(
        modifier
            .width(CLOSED_SIZE)
            .height(height)
            .graphicsLayer {
                scaleX = grip
                scaleY = grip
            }
            .clip(ContinuousCapsule())
            .liquidGlass(
                // The player's own material, not a lighter one: this sits in a
                // row of glass controls and reading as a different substance is
                // exactly what made it look bolted on.
                config = LocalGlassEffectConfig.current,
                shape = ContinuousCapsule(),
                ownBackdrop = backdrop,
                highlightAlpha = BarHighlightAlpha,
            )
            // The touch is claimed the moment it lands, before anything else
            // can decide what it was.
            //
            // A drag detector waits for the finger to travel far enough to be
            // sure it is a drag, and the lyrics behind this are a scrolling
            // list that is just as sure it was a scroll: whoever asks second
            // loses. Taking the pointer at the first touch and consuming every
            // move keeps the list out of it, and a touch that never moves is
            // read as a tap on the way up.
            // Keyed on nothing, deliberately.
            //
            // Keyed on `open`, the handler was rebuilt the instant a drag
            // opened the capsule — and rebuilding it cancels the gesture in
            // progress, so the movement that opened it was the movement that
            // got thrown away. The state is read through a holder instead, and
            // the touch lives from the finger landing to the finger leaving.
            .pointerInput(Unit) {
                awaitEachGesture {
                    // Taken in the first pass, before anybody upstream sees it.
                    //
                    // Two ancestors want vertical drags: the lyrics list, and
                    // the swipe that closes the player. Both are detectors that
                    // wait for the finger to travel, and both sit above this in
                    // the tree — so a drag starting on this button was usually
                    // theirs by the time it counted as a drag here. Claiming the
                    // pointer in the initial pass and consuming every move
                    // leaves them nothing to act on.
                    val first = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial,
                    )
                    first.consume()
                    pressed = true
                    lastTouch = System.currentTimeMillis()
                    var moved = 0f
                    var level = latest.value
                    openNow.value = open

                    while (true) {
                        val event = awaitPointerEvent(
                            androidx.compose.ui.input.pointer.PointerEventPass.Initial,
                        )
                        val pointer = event.changes.firstOrNull { it.id == first.id } ?: break
                        if (!pointer.pressed) break
                        val step = pointer.positionChange().y
                        // Consumed whether or not it moved: a still finger held
                        // on this control is still this control's.
                        pointer.consume()
                        if (step != 0f) {
                            moved += kotlin.math.abs(step)
                            // A finger that starts moving on the closed button
                            // is setting the level, not looking for a tap: it
                            // opens under the finger and carries on from where
                            // the level already is.
                            if (!openNow.value && moved >= viewConfiguration.touchSlop) {
                                openNow.value = true
                                open = true
                                level = latest.value
                            }
                            if (openNow.value) {
                                dragging = true
                                // Up is more, which is how a level reads. Kept
                                // here as it is dragged, so the finger and the
                                // fill move together.
                                level = (level - step / travelPx).coerceIn(0f, 1f)
                                // Off is a place you have to mean: without a
                                // step down to it, easing the level towards
                                // quiet turned the effect off altogether.
                                if (level < OFF_BELOW) level = 0f
                                change(level)
                                lastTouch = System.currentTimeMillis()
                            }
                        }
                    }

                    dragging = false
                    pressed = false
                    // A touch that went nowhere opens it, or puts it away.
                    if (moved < viewConfiguration.touchSlop) {
                        open = !open
                        openNow.value = open
                        lastTouch = System.currentTimeMillis()
                    }
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (lit > 0f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .background(Color.White.copy(alpha = lit)),
            )
        }

        if (fill > 0f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(height * fill)
                    .background(Color.White.copy(alpha = 0.24f)),
            )
        }

        Icon(
            if (amount > 0f) PhosphorIcons.Fill.MicrophoneStage
            else PhosphorIcons.Regular.MicrophoneStage,
            contentDescription = stringResource(R.string.karaoke),
            // White throughout: the filled glyph is what says it is on, and a
            // coloured one competed with the artwork behind it.
            tint = Color.White,
            modifier = Modifier
                .align(if (open) Alignment.BottomCenter else Alignment.Center)
                .padding(bottom = if (open) 11.dp else 0.dp)
                .size(20.dp),
        )
    }
}

private val CLOSED_SIZE = 44.dp
private val OPEN_HEIGHT = 136.dp

/** How long it stays open with nothing happening. */
private const val IDLE_MS = 2_600L

/** Under this there is nothing left to hear, so it reads as off. */
private const val OFF_BELOW = 0.04f
