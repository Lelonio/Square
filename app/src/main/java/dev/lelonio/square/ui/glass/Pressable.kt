package dev.lelonio.square.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer

/**
 * A tap that the element answers itself, instead of a ripple over it.
 *
 * Material's ripple expands a circle from the finger and fills the whole hit
 * area with the theme's colour. On this interface that is wrong twice over: the
 * covers are pictures rather than surfaces, so the wash sits on top of the
 * artwork, and the shape the ripple fills is the layout box rather than the
 * rounded tile the user can see.
 *
 * What replaces it is the press itself. The element gives a little under the
 * finger and springs back when it is let go, which is the whole of the
 * feedback: no colour over the artwork, and nothing drawn outside the shape.
 * Rows, which are mostly empty space and where a shrink alone reads as a
 * wobble, can pass a [shape] and get a soft tint inside it as well.
 */
@Composable
fun Modifier.pressable(
    onClick: () -> Unit,
    /** When set, the row is tinted inside this shape while held. */
    shape: Shape? = null,
    /** How far it gives. Small tiles want less than a full-width card. */
    pressedScale: Float = 0.96f,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
): Modifier {
    val interaction = remember { MutableInteractionSource() }

    // Read as a stream of presses and releases rather than as a "held" flag.
    //
    // A flag gives no way to tell a tap from an element that has simply never
    // been touched, and no time to play anything: a tap is over in a few dozen
    // milliseconds, so an animation that turns round the moment the flag drops
    // never gets far enough down to be seen. Only a deliberate hold showed
    // anything, and answering the flag's initial value animated everything on
    // screen at once as it scrolled in.
    //
    // Handled one event at a time, so the dip is always finished before it is
    // undone: the release waits its turn while the element goes down, and
    // whatever the finger did, what is seen is a full press and a release.
    val scale = remember { Animatable(1f) }
    LaunchedEffect(interaction, enabled, pressedScale) {
        interaction.interactions.collect { event ->
            when (event) {
                is PressInteraction.Press -> if (enabled) {
                    scale.animateTo(pressedScale, tween(90, easing = FastOutSlowInEasing))
                }

                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    // Softly damped rather than bouncy on the way back: this
                    // fires dozens of times a session, and an overshoot that
                    // reads as lively on the first tap reads as loose by the
                    // tenth.
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

    // Tied to the dip instead of animated on its own, so the two cannot come
    // apart on a fast tap.
    val travel = (1f - pressedScale).coerceAtLeast(0.0001f)
    val tint = ((1f - scale.value) / travel).coerceIn(0f, 1f) * 0.10f

    return this
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .then(
            if (shape == null) {
                Modifier
            } else {
                Modifier.drawWithContent {
                    drawContent()
                    if (tint > 0f) {
                        drawOutline(
                            outline = shape.createOutline(size, layoutDirection, this),
                            color = Color.White.copy(alpha = tint),
                        )
                    }
                }
            },
        )
        .combinedClickable(
            interactionSource = interaction,
            // The press animation is the indication. Leaving the default here
            // would draw the ripple on top of it.
            indication = null,
            enabled = enabled,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}
