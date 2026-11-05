// liquid_glass_widgets' LiquidGlassBlendGroup, MIT:
//
//   lib/src/engine/liquid_glass_blend_group.dart
//   https://github.com/sdegenaar/liquid_glass_widgets (commit 5911897)
//
// See morph/LICENSE-liquid-glass.txt.
//
// Upstream draws every glass shape of a group in one pass of its shader, which
// takes the smooth union of their outlines: two buttons further apart than the
// blend stay two, and as one inflates under a finger or leans towards its
// neighbour the gap between them bridges and the two melt into one drop. Here
// the group is one glass surface behind its members, a little larger than they
// are so a pressed one has room to swell, and each member hands it its outline
// as the press leaves it; the member itself draws only what sits on the glass
// (its tint, the ambient lift, its label and the light under the finger).

package dev.lelonio.square.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.glass.backdrop.isRenderEffectSupported
import dev.lelonio.square.ui.glass.morph.LiquidGlassRenderer
import dev.lelonio.square.ui.glass.morph.LiquidGlassShader
import dev.lelonio.square.ui.glass.morph.LiquidShape
import dev.lelonio.square.ui.glass.morph.LiquidShapeType

/** One frame of a blend group, in its surface's pixels. */
@Immutable
class GlassGroupFrame(
    val shapes: List<LiquidShape>,
    val blendPx: Float,
    val touch: GlassTouch?,
)

/** A member of a blend group: where it sits, and how it is pressed. */
@Stable
class GlassGroupMember internal constructor(
    internal val press: GlassPressState?,
) {
    internal var coordinates: LayoutCoordinates? by mutableStateOf(null, androidx.compose.runtime.neverEqualPolicy())

    /** Its own corner radius in pixels; a capsule's is half its short side. */
    internal var cornerRadiusPx: Float? = null
}

@Stable
class GlassBlendGroupState internal constructor() {
    internal val members = mutableStateListOf<GlassGroupMember>()
    internal var surface: LayoutCoordinates? by mutableStateOf(null)
    internal var blendPx = 0f

    /** Whether [member] is drawn by the group: the shader holds four shapes. */
    fun draws(member: GlassGroupMember): Boolean {
        val index = members.indexOf(member)
        return index in 0 until LiquidGlassShader.MAX_SHAPES
    }

    internal fun register(member: GlassGroupMember) {
        if (member !in members) members += member
    }

    internal fun unregister(member: GlassGroupMember) {
        members -= member
    }

    internal fun frame(): GlassGroupFrame {
        val surface = surface
        val shapes = ArrayList<LiquidShape>(LiquidGlassShader.MAX_SHAPES)
        var touch: GlassTouch? = null
        // Where the members sit at rest, for the blend's ceiling below.
        val resting = ArrayList<Rect>(LiquidGlassShader.MAX_SHAPES)
        if (surface != null && surface.isAttached) {
            for (member in members.take(LiquidGlassShader.MAX_SHAPES)) {
                val coordinates = member.coordinates ?: continue
                if (!coordinates.isAttached) continue
                val origin = surface.localPositionOf(coordinates, Offset.Zero)
                val size = coordinates.size
                val local = member.press?.stretchedBounds()
                    ?: Rect(0f, 0f, size.width.toFloat(), size.height.toFloat())
                val bounds = local.translate(origin)
                resting += Rect(origin, androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()))
                if (bounds.width <= 0f || bounds.height <= 0f) continue
                // The corner grows with the press, as upstream's stretch
                // transforms the shape whole: a circle stays a circle, and
                // the lift drawn inside the member's scaled clip covers it.
                val restingShort = minOf(size.width, size.height).toFloat()
                val growth = if (restingShort > 0f) minOf(bounds.width, bounds.height) / restingShort else 1f
                val radius = ((member.cornerRadiusPx ?: Float.MAX_VALUE) * growth)
                    .coerceAtMost(minOf(bounds.width, bounds.height) / 2f)
                shapes += LiquidShape(LiquidShapeType.RoundedRectangle, bounds, radius)
                val press = member.press
                if (press != null && press.touchIntensity > (touch?.intensity ?: 0f)) {
                    touch = GlassTouch(origin + press.mapToParent(press.touchPosition), press.touchIntensity)
                }
            }
        }
        // The smooth union pulls two outlines towards each other as soon as
        // they are closer than the blend, so a blend wider than the gap
        // between two resting members bends them into each other while
        // nobody touches them. Held to the narrowest gap, they stay whole at
        // rest and melt only as a pressed one swells across it.
        var narrowest = Float.POSITIVE_INFINITY
        for (i in resting.indices) for (j in i + 1 until resting.size) {
            narrowest = minOf(narrowest, gapBetween(resting[i], resting[j]))
        }
        return GlassGroupFrame(shapes, minOf(blendPx, narrowest.coerceAtLeast(0f)), touch)
    }
}

/** The distance between two rectangles' edges, 0 where they touch or overlap. */
private fun gapBetween(a: Rect, b: Rect): Float {
    val dx = maxOf(0f, maxOf(a.left - b.right, b.left - a.right))
    val dy = maxOf(0f, maxOf(a.top - b.bottom, b.top - a.bottom))
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

val LocalGlassBlendGroup = staticCompositionLocalOf<GlassBlendGroupState?> { null }

/** Room round the members for a pressed one to swell and lean into. */
private val GroupHeadroom = 24.dp

/**
 * Upstream's LiquidGlassBlendGroup: the glass buttons inside melt into one
 * another as they come within [blend] of each other.
 *
 * Where upstream's material cannot run (before Android 13, a GPU that
 * rejects the shader, the plain-blur or translucent styles) this is a plain
 * Box and every member draws its own glass, as it would outside a group.
 */
@Composable
fun GlassBlendGroup(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    /** Upstream's default: 20 logical pixels. */
    blend: Dp = 20.dp,
    blurScale: Float = 1f,
    content: @Composable BoxScope.() -> Unit,
) {
    val config = LocalGlassEffectConfig.current
    val active = LiquidGlassRenderer.enabled &&
        config.style == GlassStyle.LIQUID &&
        isRenderEffectSupported() &&
        isGlassAllowed()
    if (!active) {
        Box(modifier, content = content)
        return
    }
    val state = remember { GlassBlendGroupState() }
    val density = LocalDensity.current
    state.blendPx = with(density) { blend.toPx() }
    val frame = remember(state) { { state.frame() } }
    Box(modifier) {
        Box(
            Modifier
                .matchParentSize()
                .layout { measurable, constraints ->
                    val pad = GroupHeadroom.roundToPx()
                    val placeable = measurable.measure(
                        Constraints.fixed(constraints.maxWidth + 2 * pad, constraints.maxHeight + 2 * pad),
                    )
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(-pad, -pad)
                    }
                }
                .onGloballyPositioned { state.surface = it }
                .liquidGlass(
                    config = config,
                    shape = RoundedCornerShape(0.dp),
                    blurRadiusDp = (config.blurRadius * blurScale).coerceAtLeast(0f),
                    highlightAlpha = dev.lelonio.square.ui.player.BarHighlightAlpha,
                    ownBackdrop = backdrop,
                    group = frame,
                ),
        )
        CompositionLocalProvider(LocalGlassBlendGroup provides state) {
            content()
        }
    }
}
