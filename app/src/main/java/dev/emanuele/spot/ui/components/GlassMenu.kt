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
