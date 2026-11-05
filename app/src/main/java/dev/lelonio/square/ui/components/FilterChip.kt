package dev.lelonio.square.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lelonio.square.ui.glass.AnchorStretchSettings
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.theme.lightPage

/**
 * One of a row of filters: liquid_glass_widgets' GlassChip, MIT.
 *
 *   lib/widgets/interactive/glass_chip.dart
 *   https://github.com/sdegenaar/liquid_glass_widgets (commit 5911897)
 *
 * A capsule of glass pressed as a GlassButton, but gently: a fixed 3 %
 * growth, a third of the lean, and a light under the finger that covers 0.8
 * of its short side. The label is 14 sp at medium weight in 90 % of the
 * page's ink, and the chosen chip is filled with that ink at 10 % (30 % on a
 * dark page); the light is tinted the same way.
 */
@Composable
fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    /** Upstream's leading `icon`, 16 across and 6 from the label. */
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    /** Upstream's `labelColor` and `iconColor`: the page's ink unless given. */
    ink: Color = Color.Unspecified,
) {
    val light = lightPage()
    val base = if (light) Color.Black else Color.White
    val contentColor = if (ink != Color.Unspecified) ink else base.copy(alpha = 0.9f)
    val selectedColor = base.copy(alpha = if (light) 0.1f else 0.3f)
    val glowUnselected = base.copy(alpha = if (light) 0.05f else 0.2f)

    LiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier,
        interactionScale = 1.03f,
        stretch = 0.3f,
        anchor = AnchorStretchSettings(),
        glowColor = if (selected) selectedColor else glowUnselected,
        glowRadius = 0.8f,
        // The theme's glow blur, which an explicit radius falls back to.
        glowBlurSigma = 4f,
        surfaceColor = if (selected) selectedColor else Color.Unspecified,
        contentHeight = CHIP_HEIGHT,
        contentPadding = 12.dp,
    ) {
        // One child, so the button's own 8 between children does not add to
        // upstream's 6 between the icon and the label.
        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) {
                androidx.compose.material3.Icon(
                    icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = androidx.compose.ui.Modifier.size(16.dp),
                )
            }
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Upstream's 8 dp of padding above and below a 14 sp line. */
private val CHIP_HEIGHT = 33.dp
