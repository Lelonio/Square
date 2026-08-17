/*
 * Adapted from Convx (GPL-3.0), ui/player/FloatingMiniPlayer.kt.
 * https://github.com/cosmictaserdev-creator/Convx
 *
 * Their accessory strip, with their sizes, their press response, their glow and
 * their swipe-to-skip, driven by this app's own playback state instead of
 * Convx's PlayerConnection. What is gone is what belonged to their app and not
 * to the shape of the thing: listen-together, the tablet pill that scales with
 * its height, the queue and lyrics buttons, and the preference keys behind the
 * gesture. The rest is theirs.
 */

package dev.lelonio.square.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.Pause
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.fill.SkipForward
import dev.lelonio.square.R
import dev.lelonio.square.ui.glass.InteractiveHighlight
import dev.lelonio.square.ui.glass.ScrollingWaveformSeekBar
import dev.lelonio.square.ui.components.Artwork
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Their numbers: a 40dp thumbnail, 40dp controls, 12 by 8 of padding. */
private val ArtSize = 40.dp
private val ControlSize = 40.dp

/**
 * What is playing, as the bar's accessory.
 *
 * The pill above the tabs when the bar is open and one of the three things on
 * the row when it has folded — the bar decides which, this only draws it.
 */
@Composable
fun FloatingMiniPlayer(
    state: PlaybackState,
    positionMs: State<Long>,
    modifier: Modifier = Modifier,
    contentColor: androidx.compose.ui.graphics.Color = MiniPlayerInk,
    /** Folded: the strip has room for the song and one button, nothing else. */
    inline: Boolean = false,
    onClick: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val layoutDirection = LocalLayoutDirection.current

    // iOS 26 style press response: the whole glass pill grows slightly while
    // touched.
    val pressInteractionSource = remember { MutableInteractionSource() }
    val isPressed by pressInteractionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 1.04f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "accessoryPressScale",
    )

    // The same finger-tracking glow the tab bar's puck has. Its gesture tracking
    // never consumes pointer events, so it is safe stacked alongside the swipe
    // detector and the click below.
    val glow = remember(scope) { InteractiveHighlight(animationScope = scope) }

    val offsetX = remember { Animatable(0f) }
    var dragStart by remember { mutableStateOf(0L) }
    var dragged by remember { mutableStateOf(0f) }
    val settle = remember { spring<Float>(stiffness = Spring.StiffnessMediumLow) }

    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .then(modifier)
            .then(glow.modifier)
            .clipToBounds()
            .then(glow.gestureModifier)
            .pointerInput(state.hasNext, state.hasPrevious) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragStart = System.currentTimeMillis()
                        dragged = 0f
                    },
                    onDragCancel = { scope.launch { offsetX.animateTo(0f, settle) } },
                    onHorizontalDrag = { _, amount ->
                        val delta = if (layoutDirection == LayoutDirection.Rtl) -amount else amount
                        val goingBack = delta > 0
                        val allowed =
                            (goingBack && state.hasPrevious) || (!goingBack && state.hasNext)
                        // Or on the way home: a pill dragged where it cannot go
                        // still has to be able to come back.
                        val returning = (goingBack && offsetX.value < 0) || (!goingBack && offsetX.value > 0)
                        if (allowed || returning) {
                            dragged += abs(delta)
                            scope.launch { offsetX.snapTo(offsetX.value + delta) }
                        }
                    },
                    onDragEnd = {
                        val ms = System.currentTimeMillis() - dragStart
                        // Mean velocity over the gesture, px/ms: a short fast
                        // throw should not have to cross the whole distance.
                        val velocity = if (ms > 0) dragged / ms else 0f
                        val threshold = size.width * 0.28f
                        val far = abs(offsetX.value)
                        if (far > threshold || (velocity > 0.55f && far > threshold * 0.25f)) {
                            if (offsetX.value > 0 && state.hasPrevious) onPrevious()
                            else if (offsetX.value <= 0 && state.hasNext) onNext()
                        }
                        scope.launch { offsetX.animateTo(0f, settle) }
                    },
                )
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .clickable(
                    interactionSource = pressInteractionSource,
                    indication = null,
                    onClick = onClick,
                )
                .padding(
                    horizontal = if (inline) 10.dp else 12.dp,
                    vertical = if (inline) 6.dp else 8.dp,
                ),
        ) {
            Artwork(
                url = state.artworkUrl,
                title = state.title,
                modifier = Modifier.size(if (inline) 36.dp else ArtSize),
                corner = 9.dp,
                decodeSize = ArtSize,
            )

            Spacer(Modifier.width(if (inline) 8.dp else 10.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = state.title,
                    style = if (inline) {
                        MaterialTheme.typography.bodySmall
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.artist,
                    style = if (inline) {
                        MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp)
                    } else {
                        MaterialTheme.typography.bodySmall
                    },
                    color = contentColor.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Skip first and play second, which is the order asked for: the
            // button that starts the music is the one nearest the thumb.
            if (!inline) {
                IconButton(onClick = onNext, modifier = Modifier.size(ControlSize)) {
                    Icon(
                        PhosphorIcons.Fill.SkipForward,
                        contentDescription = stringResource(R.string.next),
                        tint = contentColor,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            IconButton(
                onClick = onTogglePlay,
                modifier = Modifier.size(if (inline) 36.dp else ControlSize),
            ) {
                Icon(
                    if (state.isPlaying) PhosphorIcons.Fill.Pause else PhosphorIcons.Fill.Play,
                    contentDescription = stringResource(
                        if (state.isPlaying) R.string.pause else R.string.play,
                    ),
                    tint = contentColor,
                    modifier = Modifier.size(if (inline) 20.dp else 24.dp),
                )
            }
        }

        // The old bar's own hairline, back where the wave was: a coloured line
        // along the bottom of the pill. It says where the track is without
        // asking to be read, which on a strip this size is the whole job.
        androidx.compose.material3.LinearProgressIndicator(
            progress = {
                val duration = state.durationMs
                if (duration > 0) (positionMs.value.toFloat() / duration).coerceIn(0f, 1f) else 0f
            },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .height(2.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = contentColor.copy(alpha = 0.18f),
            drawStopIndicator = {},
        )
    }
}
