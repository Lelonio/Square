// liquid_glass_widgets' GlassPullDownButton, GlassMenuItem and GlassMenuDivider,
// MIT:
//
//   lib/widgets/interactive/glass_pull_down_button.dart
//   lib/widgets/overlays/glass_menu_item.dart
//   https://github.com/sdegenaar/liquid_glass_widgets (commit 5911897)
//
// See glass/morph/LICENSE-liquid-glass.txt.
//
// Upstream's pull-down button is a GlassButton that a GlassMenu grows out of.
// The menu is this app's port of GlassMenu (GlassChoiceMenu), which a screen
// lays over itself so the glass can read the page under it; the button below
// is its trigger, and the rows are upstream's menu items.

package dev.lelonio.square.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.player.GlassInk
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Check

/**
 * Upstream's GlassPullDownButton: a round GlassButton, 44 dp across, or a
 * pill with the icon and a label beside it, that the screen's GlassChoiceMenu
 * grows out of. [onAnchor] reports where it sits, in root pixels, for the menu
 * to grow from; [trigger] hides it while its glass is out in the menu.
 */
@Composable
fun GlassPullDownButton(
    icon: ImageVector,
    onOpen: () -> Unit,
    onAnchor: (IntRect) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    trigger: GlassMenuTrigger? = null,
    label: String? = null,
    semanticLabel: String? = null,
    ink: Color = GlassInk,
    buttonSize: Dp = 44.dp,
) {
    val sizeModifier = if (label.isNullOrEmpty()) Modifier.size(buttonSize) else Modifier.height(buttonSize)
    val padding = if (label.isNullOrEmpty()) 0.dp else 16.dp
    @Composable
    fun RowScope.Items(modifier: Modifier) {
        Icon(icon, contentDescription = null, tint = ink, modifier = modifier.size(20.dp))
        if (!label.isNullOrEmpty()) {
            // Upstream's 8 px gap is the button's own row spacing here.
            Text(
                label,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = modifier,
            )
        }
    }
    if (trigger != null) {
        androidx.compose.runtime.SideEffect {
            trigger.iconContent = {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = padding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) { Items(Modifier) }
            }
        }
        androidx.compose.runtime.DisposableEffect(trigger) { onDispose { trigger.iconContent = null } }
    }
    Box(modifier.then(sizeModifier)) {
        LiquidButton(
            onClick = onOpen,
            backdrop = backdrop,
            modifier = sizeModifier
                .then(if (trigger != null) Modifier.glassMenuTrigger(trigger) else Modifier)
                .onGloballyPositioned { onAnchor(it.boundsInRoot().toIntRect()) }
                .then(
                    if (semanticLabel != null) {
                        Modifier.semantics { contentDescription = semanticLabel }
                    } else {
                        Modifier
                    },
                ),
            contentHeight = buttonSize,
            contentPadding = padding,
        ) {
            Items(Modifier.alpha(0f))
        }
        Row(
            Modifier
                .matchParentSize()
                .then(if (trigger != null) Modifier.glassMenuTriggerContent(trigger) else Modifier)
                .padding(horizontal = padding),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Items(Modifier)
        }
    }
}

private fun Rect.toIntRect() = IntRect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())

/**
 * Upstream's GlassMenuItem: a row at least 44 dp tall, its title 17 sp at 90 %
 * of the ink, an optional 20 dp icon ahead of it and a trailing mark after.
 * Pressed, the row lights with 15 % white in a 24 dp capsule and gives to 98 %.
 *
 * Upstream draws the chosen option of a pull-down with whatever the caller
 * puts at its end; [selected] puts a check there, as the menus here did.
 */
@Composable
fun GlassMenuItem(
    title: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val morph = LocalChoiceRowMorph.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // AnimatedScale: 150 ms, easeOutCubic.
    val scale by animateFloatAsState(
        if (pressed) 0.98f else 1f,
        tween(150, easing = androidx.compose.animation.core.CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)),
        label = "menuItemScale",
    )
    Box(
        Modifier
            .choiceRowMorph(morph)
            .fillMaxWidth()
            // The menu body's 12 dp either side, which upstream puts round
            // the whole list and this app's menu leaves to its rows.
            .padding(horizontal = 12.dp)
            // Upstream's 2 dp between rows.
            .padding(vertical = 1.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            // Instant on the press, as upstream's AnimatedContainer is.
            .background(if (pressed) Color(0x26FFFFFF) else Color.Transparent, RoundedCornerShape(24.dp))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = GlassInk.copy(alpha = if (enabled) 1f else 0.5f),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Normal,
                    color = GlassInk.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                trailing != null -> trailing()
                selected -> Icon(
                    com.adamglin.PhosphorIcons.Regular.Check,
                    contentDescription = null,
                    tint = GlassInk,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

/** Upstream's GlassMenuDivider: a 0.5 dp line at 45 % of the ink, 12 dp of room, inset 8 dp. */
@Composable
fun GlassMenuDivider() {
    val morph = LocalChoiceRowMorph.current
    Box(
        Modifier
            .choiceRowMorph(morph)
            .fillMaxWidth()
            .height(12.dp)
            // Upstream's 8 dp indent, inside the body's 12.
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(GlassInk.copy(alpha = 0.45f)),
        )
    }
}
