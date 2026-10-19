package dev.lelonio.square.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Translate
import com.adamglin.phosphoricons.regular.Translate
import dev.lelonio.square.R
import dev.lelonio.square.ui.glass.LocalGlassEffectConfig
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.glass.liquidGlass
import dev.lelonio.square.ui.glass.shapes.ContinuousCapsule

/**
 * Whether to read the song in your own language as well.
 *
 * The same button the karaoke dial is when it is closed — the glass capsule the
 * width of a thumb, the filled glyph for on and the outline for off — because
 * the two sit beside each other over the lyrics and anything else would read as
 * two different kinds of control.
 *
 * On for a song that ships its own translation and for one that does not: the
 * lines the document has no subtitles for are put through a translator when the
 * switch is turned on, which is the wait the dimmed glyph stands for.
 */
@Composable
fun TranslationToggle(
    on: Boolean,
    /** Turns the glyph into a spinner while the lines are away being translated. */
    busy: Boolean = false,
    onChange: (Boolean) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val change by rememberUpdatedState(onChange)
    val state by rememberUpdatedState(on)

    // Gives under the finger, like every other control in the player.
    val grip by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(140),
        label = "translationGrip",
    )
    val lit by animateFloatAsState(
        targetValue = if (pressed) 0.12f else 0f,
        animationSpec = tween(140),
        label = "translationPress",
    )

    Box(
        modifier
            .size(SIZE)
            .graphicsLayer {
                scaleX = grip
                scaleY = grip
            }
            .clip(ContinuousCapsule())
            .liquidGlass(
                config = LocalGlassEffectConfig.current,
                shape = ContinuousCapsule(),
                ownBackdrop = backdrop,
                highlightAlpha = BarHighlightAlpha,
            )
            // Claimed in the first pass, for the reason the dial beside it
            // does: the lyrics behind this scroll, and the player above it
            // closes on a drag, so a touch left unconsumed becomes theirs.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val first = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    first.consume()
                    pressed = true
                    var moved = 0f

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pointer = event.changes.firstOrNull { it.id == first.id } ?: break
                        if (!pointer.pressed) break
                        moved += kotlin.math.abs(pointer.position.y - first.position.y)
                        pointer.consume()
                    }

                    pressed = false
                    // A finger that travelled was going somewhere else.
                    if (moved < viewConfiguration.touchSlop) change(!state)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (lit > 0f) {
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = lit)))
        }

        // A turning ring while the lines are away, in place of the glyph: the
        // wait is a request over the network on a song nobody has asked for
        // before, and a button that only dimmed looked like one that had not
        // registered the press.
        if (busy) {
            androidx.compose.material3.CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Icon(
                if (on) PhosphorIcons.Fill.Translate else PhosphorIcons.Regular.Translate,
                contentDescription = stringResource(R.string.translation),
                // White throughout, as the dial is: the filled glyph is what
                // says it is on, and a coloured one competes with the artwork.
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private val SIZE = 44.dp
