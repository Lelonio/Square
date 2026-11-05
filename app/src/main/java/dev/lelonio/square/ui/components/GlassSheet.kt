// liquid_glass_widgets' GlassSheet, MIT:
//
//   lib/widgets/overlays/glass_sheet.dart
//   https://github.com/sdegenaar/liquid_glass_widgets
//
// See glass/morph/LICENSE-liquid-glass.txt.
//
// What GlassSheet.show puts on screen: a barrier of 54 % black, and from the
// bottom, 8 in from the edges, a pane of the theme's glass (here the iOS 27
// material) with 32 corners, sliding up over 350 ms (easeOutCubic in,
// easeInCubic out). On it the drag handle, 36 by 4 with 8 above and below,
// the content, scrolling where it is taller than the screen, and 24 under it.
// Dragged down past a quarter of its height, or flung at 500 px/s, it goes;
// short of that it springs back over 250 ms. A finger on it swells it to 1.01,
// with a selection click.

package dev.lelonio.square.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import dev.lelonio.square.ui.glass.LocalGlassEffectConfig
import dev.lelonio.square.ui.glass.liquidGlass
import dev.lelonio.square.ui.glass.shapes.ContinuousRoundedRectangle
import kotlinx.coroutines.launch

private const val SHEET_MS = 350
private val EaseOutCubic = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)
private val EaseInCubic = CubicBezierEasing(0.55f, 0.055f, 0.675f, 0.19f)

@Composable
fun GlassSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Upstream's `showDragIndicator`. */
    showDragIndicator: Boolean = true,
    /** Upstream's `padding`, round the content. */
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /** Upstream's `isScrollable`: the content scrolls where it is taller than the room. */
    scrollable: Boolean = true,
    /** Upstream's `enableDrag`. */
    enableDrag: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val open = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    open.targetState = visible
    val active = open.currentState || open.targetState
    val dismiss by rememberUpdatedState(onDismiss)

    HostedSheet(active) {
        val host = LocalGlassSheetHost.current
        val onClose = dismiss
        // SafeArea(bottom: true): above the navigation bar, and above the
        // keyboard where a field on the sheet has brought it up.
        val bottomInset = WindowInsets.navigationBars.union(WindowInsets.ime)
            .asPaddingValues().calculateBottomPadding()
        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visibleState = open,
                enter = fadeIn(tween(SHEET_MS, easing = EaseOutCubic)),
                exit = fadeOut(tween(SHEET_MS, easing = EaseInCubic)),
                modifier = Modifier.matchParentSize(),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(GlassBarrierColor)
                        .clickable(interactionSource = null, indication = null, onClick = onClose),
                )
            }
            AnimatedVisibility(
                visibleState = open,
                enter = slideInVertically(tween(SHEET_MS, easing = EaseOutCubic)) { it },
                exit = slideOutVertically(tween(SHEET_MS, easing = EaseInCubic)) { it },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                androidx.activity.compose.BackHandler(enabled = visible, onBack = onClose)
                val density = LocalDensity.current
                val scope = rememberCoroutineScope()
                val drag = remember { Animatable(0f) }
                var height by remember { mutableIntStateOf(0) }
                var pressed by remember { mutableStateOf(false) }
                val haptics = LocalHapticFeedback.current
                // LiquidStretch's interactionScale, while a finger is down.
                val scale by animateFloatAsState(
                    if (pressed) 1.01f else 1f,
                    tween(100, easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)),
                    label = "sheetPress",
                )
                val shape = ContinuousRoundedRectangle(32.dp)
                Column(
                    modifier
                        .padding(bottom = bottomInset)
                        .padding(8.dp)
                        .fillMaxWidth()
                        .onSizeChanged { height = it.height }
                        .graphicsLayer {
                            translationY = drag.value
                            scaleX = scale
                            scaleY = scale
                        }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                pressed = true
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                } while (event.changes.any { it.pressed })
                                pressed = false
                            }
                        }
                        .then(
                            if (enableDrag) {
                                Modifier.draggable(
                                    state = rememberDraggableState { delta ->
                                        scope.launch { drag.snapTo((drag.value + delta).coerceAtLeast(0f)) }
                                    },
                                    orientation = Orientation.Vertical,
                                    onDragStopped = { velocity ->
                                        if (drag.value > height * 0.25f || velocity > 500f) {
                                            onClose()
                                            // Left where it is: the exit slides it the rest of the way.
                                        } else {
                                            drag.animateTo(0f, tween(250, easing = EaseOutCubic))
                                        }
                                    },
                                )
                            } else {
                                Modifier
                            },
                        )
                        .liquidGlass(
                            config = LocalGlassEffectConfig.current.copy(sheet = true),
                            shape = shape,
                            ownBackdrop = host?.backdrop
                                ?: dev.lelonio.square.ui.glass.LocalAppBackdrop.current,
                        )
                        // Swallows the taps that would close the sheet.
                        .clickable(interactionSource = null, indication = null) {},
                ) {
                    if (showDragIndicator) {
                        Spacer(Modifier.height(8.dp))
                        val light = dev.lelonio.square.ui.theme.LocalLightTheme.current
                        Box(
                            Modifier
                                .align(Alignment.CenterHorizontally)
                                .size(width = 36.dp, height = 4.dp)
                                .background(
                                    if (light) Color(0x33000000) else Color(0x59FFFFFF),
                                    RoundedCornerShape(2.dp),
                                ),
                        )
                        Spacer(Modifier.height(8.dp))
                    } else {
                        Spacer(Modifier.height(16.dp))
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                            .padding(contentPadding),
                        content = content,
                    )
                    Spacer(Modifier.height(24.dp))
                }
                // Back to rest for the next opening.
                androidx.compose.runtime.LaunchedEffect(visible) {
                    if (visible) drag.snapTo(0f)
                }
            }
        }
    }
}
