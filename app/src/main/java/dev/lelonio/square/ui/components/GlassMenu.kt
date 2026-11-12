package dev.lelonio.square.ui.components

import dev.lelonio.square.ui.player.GlassInk
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.updateTransition
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.layout
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt
import kotlin.math.pow
import dev.lelonio.square.ui.glass.morph.liquidGlassGroup
import dev.lelonio.square.ui.glass.morph.LiquidGlassRenderer
import dev.lelonio.square.ui.glass.toLiquidGlassSettings
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import dev.lelonio.square.ui.glass.liquidGlass
import dev.lelonio.square.ui.player.GlassSurface
import dev.lelonio.square.ui.theme.softShadow
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.glass.shapes.ContinuousCapsule
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Check
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * A context menu, in a window of its own so nothing can be drawn over it.
 *
 * It went through both other options first, and both are worse. Material's
 * `DropdownMenu` is an opaque elevated card — the one surface in the app that
 * announces it came from a different design system. Drawing it inside the
 * screen instead let it refract the page properly, and put it *under* the mini
 * player and the tab bar, which are drawn above the whole navigation host: a
 * menu with the now-playing bar across it.
 *
 * So: a popup. A popup is its own window and cannot sample a layer belonging to
 * another one, which means no real refraction, which in turn is why this is
 * opaque rather than a pane of glass with nothing behind it. It keeps the shape,
 * the corner radius and the hairline of the rest of the app, and gives up the
 * one property it cannot honestly have.
 *
 * @param anchor where the menu's top-left corner goes, in pixels from the top
 *   left of the window. See [MENU_WIDTH] for placing it by its right edge.
 */
@Composable
fun GlassMenu(
    visible: Boolean,
    anchor: IntOffset,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Kept so the exit animation has something to play. `visible` going false
    // is the *start* of the closing, not the end of it.
    val open = remember { MutableTransitionState(false) }
    open.targetState = visible
    if (!open.currentState && !open.targetState) return

    Popup(
        onDismissRequest = onDismiss,
        offset = anchor,
        properties = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(
            visibleState = open,
            // Grows out of the corner it was opened from, which is the only
            // thing tying it to the button that was tapped.
            enter = scaleIn(tween(170), initialScale = 0.86f, transformOrigin = TopEnd) +
                fadeIn(tween(120)),
            exit = scaleOut(tween(130), targetScale = 0.9f, transformOrigin = TopEnd) +
                fadeOut(tween(110)),
        ) {
            val shape = RoundedCornerShape(20.dp)
            // Opaque, because a popup is its own window and cannot sample a
            // layer belonging to another one — see the note on this function.
            // What it can do is be the same colour as the menu that does have
            // glass, so the two read as one thing rather than two designs: the
            // settings' own tint, over a base dark enough to stand alone.
            Column(
                Modifier
                    .width(MENU_WIDTH)
                    .clip(shape)
                    .background(MenuSurface)
                    .background(menuFilm())
                    .border(1.dp, MenuEdge, shape)
                    .padding(vertical = 6.dp),
                content = content,
            )
        }
    }
}

@Composable
fun GlassMenuItem(
    label: String,
    icon: ImageVector,
    /** Drawn in the error colour: this one takes something away. */
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    // The app's ink, not white: on the light side the menu is pale, and
    // white rows on it could not be read.
    val tint = if (destructive) MaterialTheme.colorScheme.error else GlassInk
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}

/** How wide a menu is, so a caller can place it by its right edge. */
val MENU_WIDTH = 244.dp

private val TopEnd = TransformOrigin(1f, 0f)

/** Dark enough that a list of track titles does not read through it. */
private val MenuSurfaceDark = Color(0xFF16161A)
private val MenuSurfaceLight = Color(0xFFF6F6FA)

/** The base a menu is built on, on whichever side of the app it opens. */
private val MenuSurface: Color
    @Composable get() = if (dev.lelonio.square.ui.theme.lightPage()) {
        MenuSurfaceLight
    } else {
        MenuSurfaceDark
    }

/** The hairline every other surface in the app catches light with. */
private val MenuEdge: Color
    @Composable get() = if (dev.lelonio.square.ui.theme.lightPage()) {
        Color.Black.copy(alpha = 0.12f)
    } else {
        Color.White.copy(alpha = 0.14f)
    }

/**
 * The compact form: one row of icons in a glass capsule.
 *
 * For a menu whose entries are all verbs on the same object — play it, queue it,
 * add it, copy it, remove it. Five words stacked in a list is a dialogue box;
 * five icons in a capsule is a control, and it is small enough to sit beside the
 * row it belongs to rather than over half the screen.
 *
 * Unlike [GlassMenu] this is not a popup, so it can refract [backdrop] properly.
 * That means it has to be drawn late enough not to be covered — at the app
 * level, above the tab bar and the now-playing bar, not inside a screen.
 */
@Composable
fun BoxScope.GlassIconMenu(
    visible: Boolean,
    anchor: IntOffset,
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(120)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = null, indication = null, onClick = onDismiss),
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(tween(180), initialScale = 0.7f, transformOrigin = TopEnd) +
            fadeIn(tween(120)),
        exit = scaleOut(tween(130), targetScale = 0.8f, transformOrigin = TopEnd) +
            fadeOut(tween(110)),
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset { anchor },
    ) {
        val shape = ContinuousCapsule()
        GlassSurface(
            backdrop = backdrop,
            shape = shape,
            surfaceColor = CapsuleFilm,
            modifier = Modifier
                .height(54.dp)
                .clip(shape)
                .softShadow(shape, elevation = 18.dp, spot = 0.3f),
        ) {
            Row(
                Modifier.padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

@Composable
fun GlassIconMenuItem(
    icon: ImageVector,
    description: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (destructive) MaterialTheme.colorScheme.error else GlassInk,
            modifier = Modifier.size(21.dp),
        )
    }
}

/** Light enough to stay glass; the capsule is small and never covers text. */
private val CapsuleFilm = Color.White.copy(alpha = 0.14f)

/**
 * A short list of choices, in glass, drawn inside the screen.
 *
 * The opaque [GlassMenu] above is a popup, and a popup is a window of its own:
 * it cannot sample another window's layer, so it can only fake the material with
 * a dark fill. That is the right trade for a menu that has to sit over a track
 * list near the bottom of the page, where the tab bar would otherwise cross it.
 *
 * This one is for menus that open high up, clear of the bars, and can therefore
 * be drawn in the page and refract it properly. It is also narrower and tighter:
 * these are variants of one setting, not a list of verbs, so each row is a word
 * and a tick rather than a word and an icon.
 */
@Composable
fun BoxScope.GlassChoiceMenu(
    visible: Boolean,
    /** The control this pane grows out of, in root-window pixels. */
    anchor: IntRect,
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    /** The control itself, which the menu hides and hands back; see [glassMenuTrigger]. */
    trigger: GlassMenuTrigger? = null,
    /** Upstream's `menuWidth`: 200 unless the rows need more. */
    width: androidx.compose.ui.unit.Dp = CHOICE_MENU_WIDTH,
    content: @Composable ColumnScope.() -> Unit,
) {
    // The morph uses the large multi-shape AGSL program. If this GPU rejected
    // it, retain the prior glass menu rather than leaving choices unpainted.
    if (!LiquidGlassRenderer.enabled) {
        CompatibleGlassChoiceMenu(visible, anchor, backdrop, onDismiss, content)
        return
    }
    // liquid_glass_widgets' GlassMenu, ported (glass_menu_internal.dart): the
    // control's glass (blob A) and the menu's (blob B) in one blend group; B
    // travels from the control to its place while growing into it, A shrinks
    // away, and the shader fuses the two into the liquid neck between them.
    // On closing B flows back, and at the moment the spring reaches zero the
    // glass hands over to the real control, which takes the bump.
    // The rows' own height, measured where they are laid out below. The
    // spring waits for it: the glass reads the page under the area the menu
    // will cover, and an area that grew once the rows were measured read it
    // a second time, leaving the glass missing for the frames that took.
    var rowsHeight by remember { mutableIntStateOf(0) }
    val capture = dev.lelonio.square.ui.glass.morph.rememberLiquidGlassCapture()
    val morph = dev.lelonio.square.ui.glass.morph.rememberGlassMorphController(
        visible && rowsHeight > 0 && capture.ready,
    )
    val showing by remember { derivedStateOf { morph.isShowing } }
    if (!visible && !showing) {
        if (trigger != null) androidx.compose.runtime.SideEffect { trigger.morph = null }
        return
    }

    val density = LocalDensity.current
    val dpr = density.density
    // In logical pixels, as upstream lays the menu out.
    val tx = anchor.left / dpr
    val ty = anchor.top / dpr
    val tw = anchor.width / dpr
    val th = anchor.height / dpr
    val triggerRadius = minOf(tw, th) / 2f
    val menuWidth = width.value
    val menuHeight = maxOf(rowsHeight / dpr, th)

    // Upstream's _morphAlignment: towards the side of the screen the control
    // is not on, and down unless there is room only above.
    val window = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val screenWidth = window.width / dpr
    val screenHeight = window.height / dpr
    val alignX = if (tx > screenWidth / 2f) 1f else -1f
    val spaceBelow = screenHeight - (ty + th)
    val spaceAbove = ty
    val alignY = if (spaceBelow < menuHeight && spaceAbove > menuHeight) 1f else -1f
    val finalDx = -alignX * (menuWidth - tw) / 2f
    val finalDy = -alignY * (menuHeight - th) / 2f

    if (trigger != null) {
        androidx.compose.runtime.SideEffect {
            trigger.morph = morph
            trigger.finalDx = finalDx * dpr
            trigger.finalDy = finalDy * dpr
            trigger.iconScale = minOf(menuWidth / tw, menuHeight / th).coerceIn(1f, 2.5f)
        }
    }

    val dismissable by remember { derivedStateOf { morph.value.coerceIn(0f, 1f) > 0.3f } }
    if (visible && dismissable) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = null, indication = null, onClick = onDismiss),
        )
    }

    // The area the glass is drawn over: control and menu, with room for the
    // overshoot, the blend and the shadow.
    val margin = MORPH_MARGIN.value
    val menuLeft = if (alignX > 0f) tx + tw - menuWidth else tx
    val menuTop = if (alignY < 0f) ty else ty + th - menuHeight
    val area = androidx.compose.ui.geometry.Rect(
        left = minOf(tx, menuLeft) - margin,
        top = minOf(ty, menuTop) - margin,
        right = maxOf(tx + tw, menuLeft + menuWidth) + margin,
        bottom = maxOf(ty + th, menuTop + menuHeight) + margin,
    )

    val frame: () -> ChoiceMenuFrame = {
        choiceMenuFrame(
            morph = morph,
            tx = tx - area.left,
            ty = ty - area.top,
            tw = tw,
            th = th,
            triggerRadius = triggerRadius,
            menuWidth = menuWidth,
            menuHeight = menuHeight,
            finalDx = finalDx,
            finalDy = finalDy,
        )
    }

    // A fresh reading of the page for each opening, taken before its first
    // frame is drawn.
    val wasVisible = remember { booleanArrayOf(false) }
    androidx.compose.runtime.SideEffect {
        if (visible && !wasVisible[0]) capture.reset()
        wasVisible[0] = visible
    }
    val darkPage = !dev.lelonio.square.ui.theme.lightPage()
    Box(
        Modifier
            .align(Alignment.TopStart)
            .offset { IntOffset((area.left * dpr).roundToInt(), (area.top * dpr).roundToInt()) }
            .size(area.width.dp, area.height.dp)
            .liquidGlassGroup(
                backdrop = backdrop,
                capture = capture,
                // The listener's glass, frosted as menus are (see #132). Under
                // the iOS 27 material, the material itself, as upstream's menu
                // inherits the theme's: this path reads a sharp and a blurred
                // copy of the page and has no frost pass, so the frost's cloud
                // is its blur, at the frost's own sigma.
                settings = dev.lelonio.square.ui.glass.LocalGlassEffectConfig.current.let { config ->
                    if (config.ios27) {
                        dev.lelonio.square.ui.glass.ios27Settings(!darkPage, config).let {
                            if (it.frost > 0f) it.copy(blur = maxOf(it.blur, it.frost), frost = 0f) else it
                        }
                    } else {
                        config.toLiquidGlassSettings(blurScale = MENU_BLUR_SCALE, minBlur = MENU_MIN_BLUR)
                    }
                },
                darkPage = darkPage,
            ) { frame().glass },
    ) {
        // The rows, inside blob B: laid out at the menu's width against its
        // top right, scaled with the container and clipped to it, as
        // upstream's _buildBodyContent and GlassContainer do.
        androidx.compose.runtime.CompositionLocalProvider(
            dev.lelonio.square.ui.theme.LocalInkOverride provides dev.lelonio.square.ui.theme.AppInk,
            LocalChoiceRowMorph provides { frame().rows },
        ) {
            Column(
                Modifier
                    .layout { measurable, _ ->
                        val width = (menuWidth * dpr).roundToInt()
                        val placeable = measurable.measure(
                            androidx.compose.ui.unit.Constraints(minWidth = width, maxWidth = width),
                        )
                        rowsHeight = placeable.height
                        layout(placeable.width, placeable.height) {
                            // Against the body's corner on the menu's side, as
                            // upstream's OverflowBox(alignment: _morphAlignment).
                            fun columnLeft(body: androidx.compose.ui.geometry.Rect) =
                                if (alignX > 0f) body.right - menuWidth else body.left
                            fun columnTop(body: androidx.compose.ui.geometry.Rect) =
                                if (alignY < 0f) body.top else body.bottom - placeable.height / dpr
                            val body = frame().body
                            placeable.placeWithLayer(
                                x = (columnLeft(body) * dpr).roundToInt(),
                                y = (columnTop(body) * dpr).roundToInt(),
                            ) {
                                val current = frame()
                                val left = columnLeft(current.body)
                                val top = columnTop(current.body)
                                alpha = if (current.rowsPresent) 1f else 0f
                                // About the body's centre, in the column's own pixels.
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                                    pivotFractionX = (current.body.center.x - left) / menuWidth,
                                    pivotFractionY = (current.body.center.y - top) * dpr /
                                        placeable.height.coerceAtLeast(1),
                                )
                                scaleX = current.containerScale
                                scaleY = current.containerScale
                                clip = true
                                shape = RoundRectInColumn(
                                    left = (current.body.left - left) * dpr,
                                    top = (current.body.top - top) * dpr,
                                    width = current.body.width * dpr,
                                    height = current.body.height * dpr,
                                    radius = current.radius * dpr,
                                )
                            }
                        }
                    }
                    // Upstream's _kBodyVerticalPadding, above and below the rows.
                    .padding(vertical = 12.dp),
                content = content,
            )
        }
        val iconContent = trigger?.iconContent
        if (trigger != null && iconContent != null) {
            Box(
                Modifier
                    .offset { IntOffset(((tx - area.left) * dpr).roundToInt(), ((ty - area.top) * dpr).roundToInt()) }
                    .size(tw.dp, th.dp)
                    .glassMenuTriggerContent(trigger, inMenu = true),
            ) { iconContent() }
        }
    }
}

/** The pre-morph pane, kept as the safe renderer fallback for constrained GPUs. */
@Composable
private fun BoxScope.CompatibleGlassChoiceMenu(
    visible: Boolean,
    anchor: IntRect,
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(120)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, onClick = onDismiss))
    }
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(tween(180), initialScale = 0.85f, transformOrigin = TopEnd) + fadeIn(tween(120)),
        exit = scaleOut(tween(130), targetScale = 0.9f, transformOrigin = TopEnd) + fadeOut(tween(110)),
        modifier = Modifier.align(Alignment.TopStart).offset {
            val gap = with(density) { 8.dp.roundToPx() }
            IntOffset(
                (anchor.right - with(density) { CHOICE_MENU_WIDTH.roundToPx() } + gap).coerceAtLeast(gap),
                anchor.bottom + gap,
            )
        },
    ) {
        val shape = RoundedCornerShape(20.dp)
        val config = dev.lelonio.square.ui.glass.LocalGlassEffectConfig.current
        Box(
            Modifier
                .width(CHOICE_MENU_WIDTH)
                .softShadow(shape, elevation = 22.dp, spot = 0.35f)
                .liquidGlass(
                    config = config,
                    shape = shape,
                    blurRadiusDp = config.blurRadius * MENU_BLUR_SCALE,
                    highlightAlpha = 0.3f,
                    ownBackdrop = backdrop,
                )
                .background(choiceFilm(), shape)
                .border(1.dp, MenuEdge, shape)
                .clip(shape),
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                dev.lelonio.square.ui.theme.LocalInkOverride provides dev.lelonio.square.ui.theme.AppInk,
            ) {
                Column(Modifier.padding(vertical = 4.dp), content = content)
            }
        }
    }
}

/**
 * The control a [GlassChoiceMenu] grows out of.
 *
 * Upstream hides the real control while its glass is out — fading over the
 * first fifth of the opening — and gives it back the moment the closing
 * spring reaches zero, when it takes the menu's momentum: pushed along the
 * menu's path and squashed, then settling.
 */
@Stable
class GlassMenuTrigger {
    internal var morph by mutableStateOf<dev.lelonio.square.ui.glass.morph.GlassMorphController?>(null)
    internal var finalDx = 0f
    internal var finalDy = 0f
    internal var iconScale = 1f
    internal var iconContent by mutableStateOf<(@Composable () -> Unit)?>(null)
}

@Composable
fun rememberGlassMenuTrigger(): GlassMenuTrigger = remember { GlassMenuTrigger() }

/** Applies [trigger]'s handover to the control it is put on. */
fun Modifier.glassMenuTrigger(trigger: GlassMenuTrigger): Modifier = graphicsLayer {
    val morph = trigger.morph ?: return@graphicsLayer
    val raw = morph.value
    val clamped = raw.coerceIn(0f, 1f)
    val handoff = morph.isClosing && morph.hasHandedOff
    alpha = if (!morph.isShowing || handoff) 1f else 0f
    translationX = if (handoff) trigger.finalDx * raw else 0f
    translationY = if (handoff) trigger.finalDy * raw else 0f
    val impact = if (handoff) (1f + raw * 0.35f).coerceIn(0.88f, 1f) else 1f
    scaleX = impact
    scaleY = impact
}

fun Modifier.glassMenuTriggerContent(trigger: GlassMenuTrigger, inMenu: Boolean = false): Modifier = graphicsLayer {
    val morph = trigger.morph ?: return@graphicsLayer
    val raw = morph.value
    val handoff = morph.isClosing && morph.hasHandedOff
    val withGlass = morph.isShowing && !handoff
    alpha = when {
        !withGlass -> if (inMenu) 0f else 1f
        !inMenu -> 0f
        else -> {
            val growth = morph.computeState(trigger.finalDx, trigger.finalDy).sizeT.coerceIn(0f, 1f)
            1f - smoothFade(growth / 0.7f)
        }
    }
    if (handoff) {
        translationX = trigger.finalDx * raw
        translationY = trigger.finalDy * raw
    } else if (morph.isShowing) {
        val state = morph.computeState(
            finalDx = trigger.finalDx,
            finalDy = trigger.finalDy,
            adaptiveDamping = kotlin.math.abs(trigger.finalDy) / density > 100f,
        )
        if (morph.isClosing) {
            translationX = trigger.finalDx * state.pathT
            translationY = trigger.finalDy * state.pathT
        } else {
            val drift = 8f * density
            translationX = trigger.finalDx * state.sizeT +
                (trigger.finalDx * (state.pathT - state.sizeT)).coerceIn(-drift, drift)
            translationY = trigger.finalDy * state.sizeT +
                (trigger.finalDy * (state.pathT - state.sizeT)).coerceIn(-drift, drift)
        }
    }
    var scale = if (handoff) (1f + raw * 0.35f).coerceIn(0.88f, 1f) else 1f
    if (inMenu && withGlass) {
        val grown = morph.computeState(trigger.finalDx, trigger.finalDy).sizeT.coerceIn(0f, 1f)
        scale = 1f + (trigger.iconScale - 1f) * grown
        val radius = ICON_BLUR_DP * density * grown
        renderEffect = if (radius >= 0.5f) {
            androidx.compose.ui.graphics.BlurEffect(radius, radius, androidx.compose.ui.graphics.TileMode.Decal)
        } else {
            null
        }
    }
    scaleX = scale
    scaleY = scale
}

private const val ICON_BLUR_DP = 5f

private fun smoothFade(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

/** What a row reads while the menu morphs: its opacity and its scale. */
internal class ChoiceRowMorph(val opacity: Float, val scale: Float)

internal val LocalChoiceRowMorph =
    androidx.compose.runtime.compositionLocalOf<() -> ChoiceRowMorph?> { { null } }

/** One frame of the choice menu. All in logical pixels, in the glass area's own space. */
private class ChoiceMenuFrame(
    val glass: dev.lelonio.square.ui.glass.morph.LiquidGlassFrame,
    val body: androidx.compose.ui.geometry.Rect,
    val radius: Float,
    val containerScale: Float,
    val rowsPresent: Boolean,
    val rows: ChoiceRowMorph,
)

/** Upstream's GlassMenu geometry for one frame (_buildMorphingOverlay). */
private fun choiceMenuFrame(
    morph: dev.lelonio.square.ui.glass.morph.GlassMorphController,
    tx: Float,
    ty: Float,
    tw: Float,
    th: Float,
    triggerRadius: Float,
    menuWidth: Float,
    menuHeight: Float,
    finalDx: Float,
    finalDy: Float,
): ChoiceMenuFrame {
    val state = morph.computeState(
        finalDx = finalDx,
        finalDy = finalDy,
        adaptiveDamping = kotlin.math.abs(finalDy) > 100f,
    )
    val closing = morph.isClosing
    val clampedValue = morph.value.coerceIn(0f, 1f)
    // This app's glass is upstream's premium tier.
    val isPremium = true

    val currentHeight = if (state.sizeT < 0f) {
        (th * (1f + state.sizeT * 0.25f)).coerceAtLeast(1f)
    } else {
        lerp(th, menuHeight, state.sizeT).coerceAtLeast(0f)
    }
    val currentWidth = if (state.sizeT < 0f) {
        (tw * (1f + state.sizeT * 0.25f)).coerceAtLeast(1f)
    } else {
        lerp(tw, menuWidth, state.sizeT).coerceAtLeast(0f)
    }

    val maxRadius = minOf(currentWidth, currentHeight) / 2f
    val radiusT = dev.lelonio.square.ui.glass.morph.FlutterCurves.easeInExpo(state.sizeT.coerceIn(0f, 1f))
    val currentRadius = lerp(maxRadius, CHOICE_MENU_RADIUS, radiusT)

    val effectiveDx: Float
    val effectiveDy: Float
    if (closing) {
        effectiveDx = finalDx * state.pathT
        effectiveDy = finalDy * state.pathT
    } else {
        val maxAnchorDrift = 8f
        effectiveDx = finalDx * state.sizeT +
            (finalDx * (state.pathT - state.sizeT)).coerceIn(-maxAnchorDrift, maxAnchorDrift)
        effectiveDy = finalDy * state.sizeT +
            (finalDy * (state.pathT - state.sizeT)).coerceIn(-maxAnchorDrift, maxAnchorDrift)
    }

    val bodyLeft = tx + tw / 2f + effectiveDx - currentWidth / 2f
    val bodyTop = ty + th / 2f + effectiveDy - currentHeight / 2f
    val body = androidx.compose.ui.geometry.Rect(bodyLeft, bodyTop, bodyLeft + currentWidth, bodyTop + currentHeight)

    val shapes = buildList {
        // Blob A, the control's ghost: pushed by the closing bounce, scaled
        // about its centre. Its radius is the control's own, unscaled, as
        // upstream's shape keeps it; the shader clamps it to the size.
        if (!closing || isPremium) {
            val cx = tx + state.pushDx + tw / 2f
            val cy = ty + state.pushDy + th / 2f
            val w = tw * state.anchorScale
            val h = th * state.anchorScale
            add(
                dev.lelonio.square.ui.glass.morph.LiquidShape(
                    type = dev.lelonio.square.ui.glass.morph.LiquidShapeType.RoundedRectangle,
                    bounds = androidx.compose.ui.geometry.Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f),
                    cornerRadius = triggerRadius,
                ),
            )
        }
        // Blob B, the menu body; a sub-pixel body registers nothing.
        if (currentWidth >= 1f && currentHeight >= 1f) {
            add(
                dev.lelonio.square.ui.glass.morph.LiquidShape(
                    type = dev.lelonio.square.ui.glass.morph.LiquidShapeType.RoundedRectangle,
                    bounds = body,
                    cornerRadius = currentRadius,
                ),
            )
        }
    }

    val overlayOpacity = if (closing && morph.hasHandedOff) 0f else 1f
    val rowsPresent = if (closing) clampedValue > 0.72f else clampedValue > 0.5f
    val itemOpacity = if (closing) {
        smoothFade((clampedValue - 0.72f) / 0.25f)
    } else {
        smoothFade((clampedValue - 0.5f) / 0.42f)
    }
    val itemScale = lerp(
        0.92f,
        1f,
        dev.lelonio.square.ui.glass.morph.FlutterCurves.easeOut(((clampedValue - 0.5f) / 0.5f).coerceIn(0f, 1f)),
    )

    return ChoiceMenuFrame(
        glass = dev.lelonio.square.ui.glass.morph.LiquidGlassFrame(
            shapes = shapes,
            blend = state.blend,
            opacity = overlayOpacity,
        ),
        body = body,
        radius = currentRadius,
        containerScale = state.containerScale,
        rowsPresent = rowsPresent && overlayOpacity > 0f,
        rows = ChoiceRowMorph(itemOpacity, itemScale),
    )
}

/** A rounded rectangle at a place inside the column it clips. */
private class RoundRectInColumn(
    private val left: Float,
    private val top: Float,
    private val width: Float,
    private val height: Float,
    private val radius: Float,
) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ): androidx.compose.ui.graphics.Outline = androidx.compose.ui.graphics.Outline.Rounded(
        androidx.compose.ui.geometry.RoundRect(
            left, top, left + width, top + height,
            androidx.compose.ui.geometry.CornerRadius(radius),
        ),
    )
}

/** Upstream's GlassMenu.menuBorderRadius default, in logical pixels. */
private const val CHOICE_MENU_RADIUS = 32f

/**
 * The least a choice menu frosts, in logical pixels: upstream's own menu
 * blur. Turned down further, the page shows through the rows sharp (#132).
 */
private const val MENU_MIN_BLUR = 10f

/** Room around the glass for its overshoot, its blend and its shadow. */
private val MORPH_MARGIN = 40.dp


/**
 * What a menu adds to the material underneath it.
 *
 * The pane itself is the bar's glass, made of the listener's own settings, and
 * for most surfaces that is the whole story. A menu is the one that writes its
 * own words over somebody else's: at a thin setting — 0.13 surface opacity is
 * a real one, taken off this phone — the track titles below go on being
 * perfectly readable through the rows, and two sets of words in one place is a
 * menu nobody can use.
 *
 * So this tops the settings' own tint up to the point where rows stay legible,
 * in the settings' own colour, and gives back every bit of that as the material
 * is thickened: at 0.55 and above it adds nothing at all and the menu is purely
 * what the settings say.
 */
/**
 * The skin a menu wears, for the other things that live in their own window.
 *
 * A dialog is a separate window like a popup is, so it cannot sample the layer
 * behind it and cannot be real glass — see the note on [GlassMenu]. What it can
 * be is the same surface: the same base, the same film taken from the
 * listener's own glass settings, the same hairline. Two windows that cannot
 * refract should at least be one design.
 */
@Composable
fun Modifier.menuSkin(shape: androidx.compose.ui.graphics.Shape): Modifier =
    this
        .clip(shape)
        .background(MenuSurface)
        .background(menuFilm())
        .border(1.dp, MenuEdge, shape)

/**
 * The film of the in-page menu: the app's surface colour, thick enough that
 * the titles of the songs under it do not show through its rows — at 0.72
 * they did, sharp, over a playlist's own coloured page — and still short of
 * opaque, so the glass's rim and the page's tint stay at its edges.
 */
@Composable
private fun choiceFilm(): Color =
    if (dev.lelonio.square.ui.theme.lightPage()) Color(0xFFF6F6FA).copy(alpha = 0.9f)
    else Color(0xFF16161A).copy(alpha = 0.88f)

@Composable
private fun menuFilm(): Color {
    val config = dev.lelonio.square.ui.glass.LocalGlassEffectConfig.current
    val missing = (MENU_FLOOR_OPACITY - config.surfaceOpacity).coerceAtLeast(0f)
    // The listener's own tint where they set one, and the adaptive film
    // otherwise — the same rule the glass itself follows; see GlassEffect.
    val tint = if (config.surfaceTintColor.isSpecified) {
        config.surfaceTintColor
    } else if (dev.lelonio.square.ui.theme.lightPage()) {
        Color(0xFFFAFAFA)
    } else {
        Color(0xFF23232A)
    }
    return tint.copy(alpha = missing)
}

/** The coverage a menu needs before its own rows stop competing with the page. */
private const val MENU_FLOOR_OPACITY = 0.92f

/**
 * The hairline between two groups of choices in a menu.
 *
 * Faint on purpose: it separates the kinds of decision — how a list is shown,
 * what it is sorted by, which way round — without reading as a border drawn
 * across the pane.
 */
@Composable
fun GlassMenuRule() {
    val morph = LocalChoiceRowMorph.current
    Box(
        Modifier
            .choiceRowMorph(morph)
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp)
            .height(1.dp)
            .background(GlassInk.copy(alpha = 0.10f)),
    )
}

@Composable
fun GlassChoiceItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val morph = LocalChoiceRowMorph.current
    Row(
        Modifier
            .choiceRowMorph(morph)
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) GlassInk else GlassInk.copy(alpha = 0.72f),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        // Only the chosen row carries a mark: a column of icons down the side
        // would make four variants of one setting look like four commands.
        if (selected) {
            Icon(
                CheckIcon,
                contentDescription = null,
                tint = GlassInk,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/** Dark enough that a track title behind it never competes with a label. */
/**
 * How much deeper a menu blurs than the pill-sized surfaces.
 *
 * A multiplier rather than a number of pixels, so the setting still decides:
 * turned down, this is a light frost; turned up, the page behind it goes.
 */
private const val MENU_BLUR_SCALE = 2f

/** Upstream's GlassMenu default width: 200 logical pixels. */
val CHOICE_MENU_WIDTH = 200.dp

private val CheckIcon
    get() = com.adamglin.PhosphorIcons.Regular.Check

/** Each row's opacity and scale while its menu morphs, as upstream's _buildListContent. */
internal fun Modifier.choiceRowMorph(morph: () -> ChoiceRowMorph?): Modifier = graphicsLayer {
    val current = morph() ?: return@graphicsLayer
    alpha = current.opacity
    scaleX = current.scale
    scaleY = current.scale
}
