// liquid_glass_widgets' GlassActionSheet, MIT:
//
//   lib/widgets/overlays/glass_action_sheet.dart
//   https://github.com/sdegenaar/liquid_glass_widgets
//
// See glass/morph/LICENSE-liquid-glass.txt.
//
// What showGlassActionSheet puts on screen, as showCupertinoModalPopup shows
// it: a barrier of 54 % black, and from the bottom, 8 in from the edges, a
// card of the page's opposite at 65 % with 14 corners, holding the title and
// the message at 13 and the actions at 17 under hairlines; then, 8 below, the
// cancel button in a card of its own. A press lights a row at 6 % of the ink.
//
// Upstream wraps each card in an AdaptiveLiquidGlassLayer with no glass shape
// inside it, so what it draws is the card's own colour; so does this.

package dev.lelonio.square.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lelonio.square.R
import dev.lelonio.square.ui.glass.LocalGlassEffectConfig
import dev.lelonio.square.ui.glass.liquidGlass

/** Upstream's GlassActionSheetStyle. */
enum class GlassActionSheetStyle { Default, Destructive, Cancel }

/** Upstream's GlassDefaults.barrierColor: about 54 % black. */
val GlassBarrierColor = Color(0x8A000000)

/** CupertinoModalPopupRoute's 335 ms, on its linearToEaseOut and easeInToLinear curves. */
private const val POPUP_MS = 335
private val LinearToEaseOut = CubicBezierEasing(0.35f, 0.91f, 0.33f, 0.97f)
private val EaseInToLinear = CubicBezierEasing(0.67f, 0.03f, 0.65f, 0.09f)

/**
 * A card of the sheet: the iOS 27 glass over the page, with upstream's own
 * card colour on it. Upstream's card has no glass of its own; here, over the
 * app's dark pages, the rows behind read through the colour alone.
 */
@Composable
private fun Modifier.card(
    shape: RoundedCornerShape,
    backdrop: dev.lelonio.square.ui.glass.backdrop.Backdrop?,
    ink: ActionSheetInk,
): Modifier {
    val glass = if (backdrop != null) {
        Modifier.liquidGlass(
            config = LocalGlassEffectConfig.current.copy(sheet = true),
            shape = shape,
            ownBackdrop = backdrop,
        )
    } else {
        Modifier
    }
    return this.then(glass).clip(shape).background(ink.card)
}

/** The colours the sheet's rows take, from whether the page is light. */
private class ActionSheetInk(val light: Boolean) {
    /** CupertinoColors.label. */
    val label = if (light) Color.Black else Color.White
    /** CupertinoColors.secondaryLabel. */
    val secondary = if (light) Color(0x993C3C43) else Color(0x99EBEBF5)
    /** CupertinoColors.destructiveRed. */
    val destructive = if (light) Color(0xFFFF3B30) else Color(0xFFFF453A)
    val card = if (light) Color.White.copy(alpha = 0.65f) else Color.Black.copy(alpha = 0.65f)
    val divider = if (light) Color.Black.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.2f)
}

private val LocalActionSheetInk = staticCompositionLocalOf { ActionSheetInk(light = false) }

@Composable
fun GlassActionSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String? = null,
    message: String? = null,
    cancelLabel: String = stringResource(R.string.cancel),
    showCancelButton: Boolean = true,
    actions: @Composable ColumnScope.() -> Unit,
) {
    val ink = ActionSheetInk(light = dev.lelonio.square.ui.theme.LocalLightTheme.current)
    // Kept so the exit has something to play: `visible` going false is the
    // start of the closing, not the end of it.
    val open = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    open.targetState = visible
    val active = open.currentState || open.targetState
    val dismiss by androidx.compose.runtime.rememberUpdatedState(onDismiss)

    // Over the whole app, as a modal popup route is: above the tab bar and
    // the now-playing bar wherever it is opened from; see GlassSheetHost.
    HostedSheet(active) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val backdrop = LocalGlassSheetHost.current?.backdrop
    val onDismiss = dismiss
    Box(Modifier.fillMaxSize()) {
    AnimatedVisibility(
        visibleState = open,
        enter = fadeIn(tween(POPUP_MS, easing = LinearToEaseOut)),
        exit = fadeOut(tween(POPUP_MS, easing = EaseInToLinear)),
        modifier = Modifier.matchParentSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(GlassBarrierColor)
                .clickable(interactionSource = null, indication = null, onClick = onDismiss),
        )
    }

    AnimatedVisibility(
        visibleState = open,
        enter = slideInVertically(tween(POPUP_MS, easing = LinearToEaseOut)) { it },
        exit = slideOutVertically(tween(POPUP_MS, easing = EaseInToLinear)) { it },
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        androidx.activity.compose.BackHandler(enabled = visible, onBack = onDismiss)
        CompositionLocalProvider(LocalActionSheetInk provides ink) {
            Column(
                Modifier
                    .padding(bottom = bottomInset)
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState())
                    // Swallows the taps that would close the sheet.
                    .clickable(interactionSource = null, indication = null) {},
            ) {
                val shape = RoundedCornerShape(14.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .card(shape, backdrop, ink),
                ) {
                    if (title != null || message != null) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (title != null) {
                                Text(
                                    title,
                                    color = ink.label,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (message != null) {
                                Text(
                                    message,
                                    color = ink.secondary,
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    actions()
                }
                if (showCancelButton) {
                    Spacer(Modifier.height(8.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .card(shape, backdrop, ink),
                    ) {
                        ActionSheetButton(
                            label = cancelLabel,
                            icon = null,
                            style = GlassActionSheetStyle.Cancel,
                            divider = false,
                            onClick = onDismiss,
                        )
                    }
                }
            }
        }
    }
}
    }
    }

/**
 * One of a [GlassActionSheet]'s actions: its label at 17, centred, an icon of
 * 20 before it where it has one, under a hairline. Leaves the sheet's
 * closing to the caller, which knows whether the action closes it.
 */
@Composable
fun GlassActionSheetAction(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    style: GlassActionSheetStyle = GlassActionSheetStyle.Default,
) {
    ActionSheetButton(label = label, icon = icon, style = style, divider = true, onClick = onClick)
}

@Composable
private fun ActionSheetButton(
    label: String,
    icon: ImageVector?,
    style: GlassActionSheetStyle,
    divider: Boolean,
    onClick: () -> Unit,
) {
    val ink = LocalActionSheetInk.current
    val color = if (style == GlassActionSheetStyle.Destructive) ink.destructive else ink.label
    // Lit at once on the press and let go over 150 ms, eased out.
    var pressed by remember { mutableStateOf(false) }
    val highlight by animateFloatAsState(
        if (pressed) 0.06f else 0f,
        if (pressed) snap() else tween(150, easing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)),
        label = "actionPress",
    )
    Column(Modifier.fillMaxWidth()) {
        if (divider) {
            Box(Modifier.fillMaxWidth().height(1.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(ink.divider))
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(ink.label.copy(alpha = highlight))
                .pointerInput(onClick) {
                    awaitEachGesture {
                        awaitFirstDown()
                        pressed = true
                        val up = waitForUpOrCancellation()
                        pressed = false
                        if (up != null) onClick()
                    }
                }
                .semantics {
                    role = Role.Button
                    onClick { onClick(); true }
                }
                .padding(16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(
                label,
                color = color,
                fontSize = 17.sp,
                letterSpacing = (-0.3).sp,
                fontWeight = if (style == GlassActionSheetStyle.Cancel) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
