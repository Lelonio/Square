package dev.emanuele.spot.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import dev.emanuele.spot.ui.player.GlassSurface
import dev.emanuele.spot.ui.theme.softShadow
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.Capsule
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
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
            Column(
                Modifier
                    .width(MENU_WIDTH)
                    .clip(shape)
                    .background(MenuSurface)
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
    val tint = if (destructive) MaterialTheme.colorScheme.error else Color.White
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
private val MenuSurface = Color(0xFF16161A)

/** The hairline every other surface in the app catches light with. */
private val MenuEdge = Color.White.copy(alpha = 0.14f)

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
        val shape = Capsule()
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
            tint = if (destructive) MaterialTheme.colorScheme.error else Color.White,
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
    anchor: IntOffset,
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
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
        enter = scaleIn(tween(180), initialScale = 0.85f, transformOrigin = TopEnd) +
            fadeIn(tween(120)),
        exit = scaleOut(tween(130), targetScale = 0.9f, transformOrigin = TopEnd) +
            fadeOut(tween(110)),
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset { anchor },
    ) {
        val shape = RoundedCornerShape(20.dp)
        GlassSurface(
            backdrop = backdrop,
            shape = shape,
            surfaceColor = CapsuleFilm,
            blurRadius = 20.dp,
            modifier = Modifier
                .width(CHOICE_MENU_WIDTH)
                .clip(shape)
                .softShadow(shape, elevation = 18.dp, spot = 0.3f),
        ) {
            Column(Modifier.padding(vertical = 4.dp), content = content)
        }
    }
}

@Composable
fun GlassChoiceItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.72f),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        // Only the chosen row carries a mark: a column of icons down the side
        // would make four variants of one setting look like four commands.
        if (selected) {
            Icon(
                CheckIcon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/** Narrow on purpose; the longest label still fits on one line. */
val CHOICE_MENU_WIDTH = 196.dp

private val CheckIcon
    get() = com.adamglin.PhosphorIcons.Regular.Check
