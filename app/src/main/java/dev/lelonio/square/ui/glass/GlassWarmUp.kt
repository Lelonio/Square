package dev.lelonio.square.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.lelonio.square.ui.glass.backdrop.drawBackdrop
import dev.lelonio.square.ui.glass.backdrop.effects.blur
import dev.lelonio.square.ui.glass.backdrop.effects.lens
import dev.lelonio.square.ui.glass.backdrop.effects.vibrancy
import dev.lelonio.square.ui.glass.backdrop.highlight.Highlight
import dev.lelonio.square.ui.glass.backdrop.shadow.Shadow
import dev.lelonio.square.ui.glass.shapes.ContinuousCapsule

/**
 * Compiles the glass shaders before anybody sees them.
 *
 * The first fold of the bar stuttered and every one after it was smooth, which
 * is the signature of a shader compiled on the frame it is first needed: the
 * lens, its chromatic aberration and the blur are RuntimeShaders, and the first
 * draw of each pays for the compile. This draws a pane one pixel across with the
 * same effects, once, while the app is starting — where a few dropped frames are
 * already expected and nobody is watching a control move.
 *
 * And a pane one pixel across is one white pixel, which is exactly what it
 * looked like: a dot on the left edge of the bar, riding up and down with it as
 * it folded. So it is drawn at a hundredth of its opacity, which is enough for
 * the shaders to be compiled for real and not enough for anyone to see, and it
 * stops being composed once the first frames it exists for have gone by.
 */
@Composable
fun GlassWarmUp(backdrop: dev.lelonio.square.ui.glass.backdrop.Backdrop) {
    var needed by remember { mutableStateOf(true) }
    if (!needed) return
    LaunchedEffect(Unit) {
        // Two frames: one to record the draw, one to be sure it reached the GPU.
        withFrameNanos {}
        withFrameNanos {}
        needed = false
    }
    Box(
        Modifier
            .size(1.dp)
            .graphicsLayer { alpha = 0.01f }
            .drawBackdrop(
                backdrop = backdrop,
                shape = { ContinuousCapsule() },
                effects = {
                    vibrancy()
                    blur(8f.dp.toPx())
                    lens(10f.dp.toPx(), 14f.dp.toPx(), chromaticAberration = true)
                },
                highlight = { Highlight.Default },
                shadow = { Shadow() },
            ),
    )
}
