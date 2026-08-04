package dev.emanuele.spot.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * A pane of glass over [backdrop].
 *
 * Wraps the one call every glass surface in this screen makes, so the numbers
 * that decide what the material *is* — how far it blurs, how hard it bends light
 * at the edge — live in one place instead of being re-picked per component.
 *
 * The three effects are ordered deliberately and are not interchangeable:
 * `vibrancy` lifts the colour of what shows through so it does not go grey under
 * the blur, `blur` is the frosting, and `lens` is the refraction that makes the
 * edge read as a thick slab rather than as a translucent rectangle. Without the
 * lens this is just a blurred panel, which is the thing most "glass" UIs
 * actually are.
 *
 * Everything degrades on its own below Android 13: the library checks for
 * RuntimeShader support and skips the refraction, leaving the blur, and below
 * Android 12 the blur goes too. What is left is a plain translucent surface, so
 * the screen stays usable rather than breaking on older phones.
 */
@Composable
fun GlassSurface(
    backdrop: Backdrop,
    shape: Shape,
    modifier: Modifier = Modifier,
    blurRadius: Dp = 8.dp,
    /** How far in from the edge the refraction reaches. */
    refractionHeight: Dp = 24.dp,
    /** How hard light bends there. */
    refractionAmount: Dp = 24.dp,
    /**
     * A film drawn over the refracted backdrop.
     *
     * Without it a pane is pure refraction and reads darker than the buttons
     * beside it, which do draw one — the surfaces stop looking like the same
     * material. Unspecified leaves the glass clear.
     */
    surfaceColor: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
    Box(
        modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(blurRadius.toPx())
                // Deliberately the same numbers LiquidBottomTabs uses. They were
                // tuned by eye at first and came out a different material — a
                // shallower, harder edge than the bar and the buttons beside
                // it, which is exactly what "the player doesn't look like the
                // rest" was.
                lens(refractionHeight.toPx(), refractionAmount.toPx())
            },
            onDrawSurface = if (surfaceColor.isSpecified) {
                { drawRect(surfaceColor) }
            } else {
                null
            },
        ),
    ) {
        content()
    }
}
