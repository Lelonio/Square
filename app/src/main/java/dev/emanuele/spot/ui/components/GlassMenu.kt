package dev.emanuele.spot.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import dev.emanuele.spot.ui.player.GlassSurface

/**
 * A context menu made of the same glass as everything else.
 *
 * Material's `DropdownMenu` draws an opaque elevated card — the one surface in
 * the app that announces it came from a different design system.
 *
 * Drawn inside the screen rather than in a `Popup`, which is what the first
 * version did and is worth explaining because a popup is the obvious choice. A
 * popup is its own window: it cannot refract the list it is covering, because
 * that list is in another window and in no layer the popup can sample. What
 * came out was a pane of glass with nothing behind it — transparent, with the
 * rows legible straight through its own text. As a sibling of the content,
 * [backdrop] can be the layer the page is recorded into and the material
 * behaves like every other pane in the app.
 *
 * @param anchor where the menu's top-left corner goes, in pixels from the top
 *   left of the box this is placed in. See [MENU_WIDTH] for placing it by its
 *   right edge instead.
 */
@Composable
fun BoxScope.GlassMenu(
    visible: Boolean,
    anchor: IntOffset,
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Catches the tap that closes the menu, and stops what is behind it from
    // competing with it.
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(140)),
        exit = fadeOut(tween(140)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(interactionSource = null, indication = null, onClick = onDismiss),
        )
    }

    AnimatedVisibility(
        visible = visible,
        // Grows out of the corner it was opened from, which is the only thing
        // tying it to the button that was tapped.
        enter = scaleIn(tween(170), initialScale = 0.86f, transformOrigin = TopEnd) +
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
            surfaceColor = MenuFilm,
            modifier = Modifier
                .width(MENU_WIDTH)
                .clip(shape),
        ) {
            Column(Modifier.padding(vertical = 6.dp), content = content)
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

/**
 * The film over the refracted page.
 *
 * Heavier than the app's other panes deliberately: those sit over a backdrop
 * nobody is reading, and this one sits over a list of track titles. At the usual
 * twelve per cent the rows underneath were legible through the menu's own text.
 */
private val MenuFilm = Color.Black.copy(alpha = 0.62f)
