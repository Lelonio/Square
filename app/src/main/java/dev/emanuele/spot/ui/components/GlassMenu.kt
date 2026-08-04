package dev.emanuele.spot.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.kyant.backdrop.Backdrop
import dev.emanuele.spot.ui.player.GlassSurface

/**
 * A context menu made of the same glass as everything else.
 *
 * Material's `DropdownMenu` draws an opaque elevated card. It works, and it is
 * the one surface in the app that announces it came from a different design
 * system — a paper rectangle over a page built entirely out of refraction.
 *
 * The anchor is whatever composable this is placed inside, so it belongs in the
 * `Box` around the button that opens it.
 */
@Composable
fun GlassMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    /** Where the menu sits relative to its anchor. */
    alignment: Alignment = Alignment.TopEnd,
    offset: IntOffset = IntOffset(0, 0),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return

    Popup(
        alignment = alignment,
        offset = offset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        val shape = RoundedCornerShape(20.dp)
        val scale by animateFloatAsState(
            targetValue = 1f,
            animationSpec = tween(140),
            label = "menuScale",
        )

        GlassSurface(
            backdrop = backdrop,
            shape = shape,
            surfaceColor = MenuFilm,
            modifier = modifier
                .width(236.dp)
                // Grows out of the corner it is anchored to rather than
                // appearing at full size, which is the only thing that ties it
                // to the button that was tapped.
                .graphicsLayer {
                    scaleX = 0.92f + 0.08f * scale
                    scaleY = 0.92f + 0.08f * scale
                    this.alpha = scale
                    transformOrigin = TransformOrigin(1f, 0f)
                }
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

/** Enough film to read text over anything the page puts behind it. */
private val MenuFilm = Color.White.copy(alpha = 0.14f)
