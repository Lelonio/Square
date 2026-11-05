// The press is liquid_glass_widgets' GlassButton, MIT:
//
//   lib/widgets/interactive/glass_button.dart
//   https://github.com/sdegenaar/liquid_glass_widgets (commit 5911897)
//
// It inflates 17 dp along its longest side and undershoots once on release,
// leans with a tremor towards a dragging finger, brightens while held and
// carries a light under the finger that lights the rim nearest it; see
// GlassInteraction.kt, where those parts are ported. The material is the
// app's own liquidGlass, which is upstream's shader. The tint, wash and flat
// options are this app's, for the chips and the wordmark; the layout
// (a row with a height and padding) is what the screens were built on, from
// the Backdrop catalog this button first came from (Apache-2.0, see
// LICENSE-backdrop.txt).

package dev.lelonio.square.ui.glass

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.glass.shapes.ContinuousCapsule

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiquidButton(
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    /** Upstream's `enabled`: a disabled button is drawn at half opacity and ignores touches. */
    enabled: Boolean = true,
    isInteractive: Boolean = true,
    /** Upstream's `shape`; this app's buttons are capsules. */
    shape: CornerBasedShape = ContinuousCapsule(),
    /** Upstream's `stretch`: how far the button leans with a dragging finger, 0 for none. */
    stretch: Float = 0.5f,
    /** Upstream's `interactionScale`: null for the native 17 dp growth, or a fixed factor. */
    interactionScale: Float? = null,
    /** Upstream's `anchorStretchSettings`: how the lean is shaped. */
    anchor: AnchorStretchSettings = AnchorStretchSettings.NativeTremor,
    /**
     * Upstream's `glowColor`, `glowRadius` and `glowBlurRadius`: the light
     * under the finger. Unspecified for the native sheen.
     */
    glowColor: Color = Color.Unspecified,
    glowRadius: Float = GlassPressDefaults.GlowRadius,
    glowBlurSigma: Float = GlassPressDefaults.GlowBlurSigmaDp,
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
    /**
     * A colour for the glass to hold, over the film rather than under it.
     *
     * Glass over a flat page has nothing to refract and comes out grey. What
     * this gives it is something to bend: a diagonal wash, strongest where the
     * rim catches the light, with a sheen along the top edge. The alpha is the
     * caller's — see GlassWash for what a chip should ask for and why it is
     * less than what the app's own mark asks for.
     *
     * Drawn with the tint below, which is to say inside the material, so the
     * label on top of it stays the colour it was set to.
     */
    wash: Color = Color.Unspecified,
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
    val press = rememberGlassPressState()
    press.enabled = enabled && isInteractive
    press.stretch = stretch
    press.pressGrowthDp = if (interactionScale == null) GlassPressDefaults.NativePressGrowthDp else null
    press.interactionScale = interactionScale ?: 1f
    press.anchor = anchor
    val lightPage = isLightGlassPage()

    // LOCAL CHANGE: the material is the app's, not this file's. Upstream picks
    // its own vibrancy, blur and lens here, which is right for a catalog of one
    // component and wrong in an app where the bottom bar is the reference: next
    // to it these buttons read as a thinner, harder glass.
    val configured = LocalGlassEffectConfig.current
    val config = if (flat) configured.copy(style = GlassStyle.TRANSPARENT) else configured
    // The shared film is drawn first; these are only what this particular
    // control adds to it, then the ambient lift.
    val drawSurface: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = {
        if (tint.isSpecified) {
            drawRect(tint, blendMode = BlendMode.Hue)
            drawRect(tint.copy(alpha = 0.75f))
        }
        if (surfaceColor.isSpecified) {
            drawRect(surfaceColor)
        }
        if (wash.isSpecified) {
            drawRect(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    0f to wash,
                    0.55f to wash.copy(alpha = wash.alpha * 0.34f),
                    1f to Color.Transparent,
                    start = androidx.compose.ui.geometry.Offset.Zero,
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                ),
            )
            // The light along the top, which is what says the
            // surface is curved rather than printed.
            drawRect(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.14f),
                        Color.Transparent,
                    ),
                    endY = size.height * 0.6f,
                ),
            )
        }
        if (isInteractive) with(press) { drawAmbientLift(lightPage) }
    }

    // Upstream's LiquidGlassBlendGroup: inside one, the group draws this
    // button's glass together with its neighbours', so they melt into one
    // another as they meet, and the button draws only what sits on it.
    val group = if (flat) null else LocalGlassBlendGroup.current
    val member = remember(press, isInteractive) { GlassGroupMember(if (isInteractive) press else null) }
    if (group != null) {
        androidx.compose.runtime.DisposableEffect(group, member) {
            group.register(member)
            onDispose { group.unregister(member) }
        }
    }
    val grouped = group != null && group.draws(member)
    val density = androidx.compose.ui.platform.LocalDensity.current

    Row(
        modifier
            .then(if (enabled) Modifier else Modifier.graphicsLayer { alpha = 0.5f })
            // Read outside the stretch, as upstream's Listener and
            // GestureDetector sit outside LiquidStretch.
            .then(if (isInteractive) press.gestureModifier else Modifier)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        interactionSource = null,
                        indication = if (isInteractive) null else LocalIndication.current,
                        enabled = enabled,
                        role = Role.Button,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier.clickable(
                        interactionSource = null,
                        indication = if (isInteractive) null else LocalIndication.current,
                        enabled = enabled,
                        role = Role.Button,
                        onClick = onClick,
                    )
                }
            )
            .then(
                if (grouped) {
                    Modifier
                        .onGloballyPositioned {
                            member.coordinates = it
                            member.cornerRadiusPx = shape.topStart.toPx(
                                androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat()),
                                density,
                            )
                        }
                        .graphicsLayer {
                            if (isInteractive) press.applyTo(this)
                            clip = true
                            this.shape = shape
                        }
                        .drawBehind(drawSurface)
                } else {
                    Modifier.liquidGlass(
                        config = config,
                        shape = shape,
                        blurRadiusDp = (config.blurRadius * blurScale).coerceAtLeast(0f),
                        // LOCAL CHANGE: buttons sample the screen at a fraction of their
                        // own resolution: five filter chips at the top of the home page
                        // alone measured 8ms a frame at full size. Upstream's material
                        // overrides this to full resolution where it runs.
                        backdropScale = 0.4f,
                        // The bar's rim, so a button beside it is cut from the same pane.
                        highlightAlpha = dev.lelonio.square.ui.player.BarHighlightAlpha,
                        ownBackdrop = backdrop,
                        // Upstream's touch specular, on the rim nearest the finger.
                        touch = if (isInteractive) {
                            remember(press) { { GlassTouch(press.touchPosition, press.touchIntensity) } }
                        } else {
                            null
                        },
                        layerBlock = if (isInteractive) {
                            remember(press) { { press.applyTo(this) } }
                        } else {
                            null
                        },
                        onDrawTint = drawSurface,
                    )
                }
            )
            // Over the label, inside the surface's clip, as GlassGlow paints.
            .then(if (isInteractive) press.glowModifier(
                if (glowColor.isSpecified) glowColor else glassGlowColor(lightPage),
                glowRadius,
                glowBlurSigma,
            ) else Modifier)
            .height(contentHeight)
            .padding(horizontal = contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8f.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}
