// Vendored from the Backdrop catalog app, Apache-2.0.
//
//   https://github.com/Kyant0/AndroidLiquidGlass
//   commit b18eb0ff12c616546a68c72e7d0097f1ab286c87
//
// These are the library author's own example components rather than part of the
// published artifact, so there is nothing to depend on — they have to be copied.
// Kept as close to upstream as possible (package line and a few Material
// swaps aside) so a later upstream fix can be diffed in; see LICENSE-backdrop.txt.

package dev.lelonio.square.ui.glass

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.glass.shapes.ContinuousCapsule
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

@Composable
fun LiquidButton(
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    isInteractive: Boolean = true,
    tint: Color = Color.Unspecified,
    /**
     * A wash of this control's own, over the shared film.
     *
     * No longer the material — that comes from the glass configuration, like
     * everywhere else — but still what a chip is lit with while it is the chosen
     * one. Dropping it entirely made the filter rows in search and the library
     * read as five identical buttons.
     */
    surfaceColor: Color = Color.Unspecified,
    // LOCAL CHANGE: upstream pins the height at 48dp and the horizontal padding
    // at 16dp, which silently overrode any size the caller asked for — a 62dp
    // round button came out 48 tall with its icon squeezed by the padding. Both
    // are parameters now, with the upstream values as defaults.
    contentHeight: Dp = 48f.dp,
    contentPadding: Dp = 16f.dp,
    // LOCAL CHANGE: how thick this button is, as a multiple of the app's own
    // frost rather than a number of dp. Upstream pins it at 2dp and this app
    // pinned various call sites at 8, which is how the player's controls ended
    // up ignoring the frost setting entirely: a button on a fixed 8 beside a bar
    // on a configurable 2 is a different material no matter where the slider is.
    blurScale: Float = 1f,
    /**
     * Draws the material without sampling anything behind it.
     *
     * For the buttons that live in a scrolling page rather than in the chrome.
     * A pane that moves with the content re-photographs the screen on every
     * frame of every scroll, and the five filter chips at the top of the home
     * page cost 8ms a frame between them — for a reflection of a page that is
     * usually flat colour behind them anyway. The film and the rim are the same,
     * so they still read as the same glass.
     */
    flat: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    val animationScope = rememberCoroutineScope()

    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(
            animationScope = animationScope
        )
    }

    // LOCAL CHANGE: the material is the app's, not this file's. Upstream picks
    // its own vibrancy, blur and lens here, which is right for a catalog of one
    // component and wrong in an app where the bottom bar is the reference: next
    // to it these buttons read as a thinner, harder glass. What stays is
    // everything that makes this a button rather than a pane — the press squash
    // below, the pull towards the finger, the tint of an active control.
    val configured = LocalGlassEffectConfig.current
    val config = if (flat) configured.copy(style = GlassStyle.TRANSPARENT) else configured
    Row(
        modifier
            .liquidGlass(
                config = config,
                shape = ContinuousCapsule(),
                blurRadiusDp = (config.blurRadius * blurScale).coerceAtLeast(0f),
                // LOCAL CHANGE: buttons sample the screen at a fraction of their
                // own resolution. There are a lot of them — five filter chips at
                // the top of the home page alone — and each one is a capture plus
                // a shader chain per frame: measured at 8ms a frame together.
                // They are small, filmed and rounded, which is exactly where the
                // upscaling does not show.
                backdropScale = 0.4f,
                // The bar's rim, so a button beside it is cut from the same pane.
                highlightAlpha = dev.lelonio.square.ui.player.BarHighlightAlpha,
                ownBackdrop = backdrop,
                layerBlock = if (isInteractive) {
                    {
                        val width = size.width
                        val height = size.height

                        val progress = interactiveHighlight.pressProgress
                        val scale = lerp(1f, 1f + 4f.dp.toPx() / size.height, progress)

                        val maxOffset = size.minDimension
                        val initialDerivative = 0.05f
                        val offset = interactiveHighlight.offset
                        translationX = maxOffset * tanh(initialDerivative * offset.x / maxOffset)
                        translationY = maxOffset * tanh(initialDerivative * offset.y / maxOffset)

                        val maxDragScale = 4f.dp.toPx() / size.height
                        val offsetAngle = atan2(offset.y, offset.x)
                        scaleX =
                            scale +
                                    maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension) *
                                    (width / height).fastCoerceAtMost(1f)
                        scaleY =
                            scale +
                                    maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension) *
                                    (height / width).fastCoerceAtMost(1f)
                    }
                } else {
                    null
                },
                // The shared film is drawn first; these are only what this
                // particular control adds to it.
                onDrawTint = if (tint.isSpecified || surfaceColor.isSpecified) {
                    {
                        if (tint.isSpecified) {
                            drawRect(tint, blendMode = BlendMode.Hue)
                            drawRect(tint.copy(alpha = 0.75f))
                        }
                        if (surfaceColor.isSpecified) {
                            drawRect(surfaceColor)
                        }
                    }
                } else {
                    null
                },
            )
            .clickable(
                interactionSource = null,
                indication = if (isInteractive) null else LocalIndication.current,
                role = Role.Button,
                onClick = onClick
            )
            .then(
                if (isInteractive) {
                    Modifier
                        .then(interactiveHighlight.modifier)
                        .then(interactiveHighlight.gestureModifier)
                } else {
                    Modifier
                }
            )
            .height(contentHeight)
            .padding(horizontal = contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8f.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}
