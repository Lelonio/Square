package dev.emanuele.spot.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import dev.emanuele.spot.ui.components.Artwork
import dev.emanuele.spot.ui.glass.LiquidButton
import dev.emanuele.spot.ui.theme.softShadow

/** Persistent bar above the bottom edge; tapping it opens the full player. */
@Composable
fun MiniPlayer(
    state: PlaybackState,
    /**
     * Read lazily, in the draw phase: taking the position as a value would
     * recompose the whole bar four times a second.
     */
    progress: () -> Float,
    /** The layer this bar refracts. */
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.hasItem,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier,
    ) {
        val shape = RoundedCornerShape(22.dp)
        GlassSurface(
            backdrop = backdrop,
            shape = shape,
            surfaceColor = GlassFilm,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .softShadow(shape, elevation = 20.dp, spot = 0.26f),
        ) {
        Column(Modifier.clickable(onClick = onExpand)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(state.artworkUrl, state.title, Modifier.size(42.dp), corner = 12.dp)

                // Slides up on a track change, so an auto-advance is visible
                // without having to be reading the bar at that moment.
                AnimatedContent(
                    targetState = state.title to state.artist,
                    transitionSpec = {
                        (slideInVertically { it / 2 } + fadeIn(tween(200)))
                            .togetherWith(slideOutVertically { -it / 2 } + fadeOut(tween(160)))
                    },
                    label = "nowPlaying",
                    modifier = Modifier
                        .padding(horizontal = 14.dp)
                        .weight(1f),
                ) { (title, artist) ->
                    Column {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MiniPlayerInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MiniPlayerInkDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                IconButton(onClick = onNext, enabled = state.hasNext) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = "Successivo",
                        tint = MiniPlayerInkDim,
                    )
                }

                // The one control on the bar with a surface of its own, so the
                // tap target that matters most also has weight — and the same
                // press animation every other glass button has.
                LiquidButton(
                    onClick = onTogglePlay,
                    backdrop = backdrop,
                    surfaceColor = GlassFilm,
                    contentHeight = 42.dp,
                    contentPadding = 0.dp,
                    modifier = Modifier.size(42.dp),
                ) {
                    Crossfade(
                        state.isPlaying,
                        animationSpec = tween(180),
                        label = "playPause",
                    ) { playing ->
                        Icon(
                            imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "Pausa" else "Riproduci",
                            tint = MiniPlayerInk,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // A hairline of progress rather than a full seek bar: enough to see
            // where the track is without inviting a drag in a 42dp-tall row.
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MiniPlayerInk.copy(alpha = 0.18f),
                drawStopIndicator = {},
            )
            }
        }
    }
}

/**
 * Fixed light, like everything else on the glass: what sits behind this bar is
 * the darkened artwork, not a page colour.
 */
private val MiniPlayerInk = Color(0xFFF7F8FA)
private val MiniPlayerInkDim = Color(0xFFF7F8FA).copy(alpha = 0.66f)

/** Guarded against the duration being unknown while a track loads. */
fun progressOf(positionMs: Long, durationMs: Long): Float =
    if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

/** Spacer height so lists can scroll clear of the mini player. */
val MiniPlayerHeight = 74.dp

@Composable
fun MiniPlayerSpacer() = Box(Modifier.height(MiniPlayerHeight))
