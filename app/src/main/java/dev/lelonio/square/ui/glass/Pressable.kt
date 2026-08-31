package dev.lelonio.square.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalMotionDurationScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp

/**
 * A tap that answers itself instead of adding a Material ripple to the glass.
 *
 * Phase 4 keeps the existing interaction language but makes two accessibility
 * guarantees explicit: the hit area is never shorter than 48dp, and the press
 * animation follows Compose's motion-duration scale so reduced-motion users do
 * not get a forced squash/spring transition.
 */
@Composable
fun Modifier.pressable(
    onClick: () -> Unit,
    shape: Shape? = null,
    pressedScale: Float = 0.96f,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val scale = remember { Animatable(1f) }
    val motionScale = LocalMotionDurationScale.current

    LaunchedEffect(interaction, enabled, pressedScale, motionScale) {
        interaction.interactions.collect { event ->
            when (event) {
                is PressInteraction.Press -> if (enabled) {
                    if (motionScale.scaleFactor == 0f) {
                        scale.snapTo(pressedScale)
                    } else {
                        scale.animateTo(
                            pressedScale,
                            tween(
                                durationMillis = (90 * motionScale.scaleFactor).toInt().coerceAtLeast(1),
                                easing = FastOutSlowInEasing,
                            ),
                        )
                    }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    if (motionScale.scaleFactor == 0f) {
                        scale.snapTo(1f)
                    } else {
                        scale.animateTo(
                            1f,
                            spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        )
                    }
                }
            }
        }
    }

    val travel = (1f - pressedScale).coerceAtLeast(0.0001f)
    val tint = ((1f - scale.value) / travel).coerceIn(0f, 1f) * 0.10f

    return this
        .heightIn(min = 48.dp)
        .semantics { role = Role.Button }
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .then(
            if (shape == null) Modifier else Modifier.drawWithContent {
                drawContent()
                if (tint > 0f) {
                    drawOutline(
                        outline = shape.createOutline(size, layoutDirection, this),
                        color = Color.White.copy(alpha = tint),
                    )
                }
            },
        )
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}
