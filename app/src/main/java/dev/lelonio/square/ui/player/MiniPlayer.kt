package dev.lelonio.square.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.R
import dev.lelonio.square.ui.components.Artwork
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.theme.softShadow
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Broadcast
import com.adamglin.phosphoricons.fill.Pause
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.fill.SkipForward

/** Persistent bar above the bottom edge; tapping it opens the full player. */
/**
 * Marks the artwork as the element shared with the full player.
 *
 * An extension rather than an inline block because it is needed identically at
 * both ends, and the two have to agree on the key or nothing is shared.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedArtwork(
    sharedScope: androidx.compose.animation.SharedTransitionScope?,
    animatedScope: androidx.compose.animation.AnimatedVisibilityScope?,
): Modifier {
    if (sharedScope == null || animatedScope == null) return this
    return with(sharedScope) {
        this@sharedArtwork.sharedElement(
            rememberSharedContentState(key = SHARED_ARTWORK),
            animatedScope,
        )
    }
}

/**
 * Morphs the now-playing bar into the player's title capsule.
 *
 * The artwork alone was not enough: a track with a Canvas has no cover on the
 * player screen, so there was nothing for the thumbnail to become and the whole
 * thing collapsed into a cross-dissolve of two full screens. These two panes,
 * though, always both exist — a rounded glass pill carrying the title and the
 * artist at each end — so the bar has something to grow into on every track.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedPill(
    sharedScope: androidx.compose.animation.SharedTransitionScope?,
    animatedScope: androidx.compose.animation.AnimatedVisibilityScope?,
): Modifier {
    if (sharedScope == null || animatedScope == null) return this
    return with(sharedScope) {
        this@sharedPill.sharedBounds(
            rememberSharedContentState(key = SHARED_PILL),
            animatedScope,
            // The contents differ at the two ends — the bar has transport
            // buttons, the capsule has the queue and like actions — so they
            // cross-fade inside bounds that are morphing.
            enter = fadeIn(tween(EXPAND_MS)),
            exit = fadeOut(tween(EXPAND_MS / 2)),
            // Scaled rather than remeasured: the two panes hold different
            // controls, and re-laying them out mid-flight makes the contents
            // jump around inside bounds that are already moving.
            resizeMode = androidx.compose.animation.SharedTransitionScope
                .ResizeMode.Companion.scaleToBounds(),
        )
    }
}

/** The keys both ends of the expand animation agree on. */
const val SHARED_ARTWORK = "nowPlayingArtwork"
const val SHARED_PILL = "nowPlayingPill"

/** Length of the expand, shared by both halves so they move together. */
const val EXPAND_MS = 400

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
    /**
     * The two halves of the expand animation.
     *
     * The cover here and the cover on the player screen are declared as the same
     * shared element, so opening the player grows this bar into that screen
     * instead of sliding a new one over it. Null when there is no transition to
     * take part in, which keeps this composable usable on its own.
     */
    sharedScope: androidx.compose.animation.SharedTransitionScope? = null,
    animatedScope: androidx.compose.animation.AnimatedVisibilityScope? = null,
    /**
     * The device the music is coming out of, when it is not this one.
     *
     * A name rather than a flag: "playing somewhere else" is not useful on its
     * own, and the bar is the one place the answer is always in view.
     */
    remoteDevice: String? = null,
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
                .sharedPill(sharedScope, animatedScope)
                .softShadow(shape, elevation = 20.dp, spot = 0.26f),
        ) {
        Column(Modifier.clickable(onClick = onExpand)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    // The reference's own numbers for the phone strip: a 36dp
                    // thumbnail with a 9dp corner, 36dp controls, and 10 by 6 of
                    // padding round the lot.
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(
                    state.artworkUrl,
                    state.title,
                    Modifier
                        .size(36.dp)
                        .sharedArtwork(sharedScope, animatedScope),
                    corner = 9.dp,
                )

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
                        .padding(start = 8.dp, end = 8.dp)
                        .weight(1f),
                ) { (title, artist) ->
                    Column {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MiniPlayerInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // The second line answers "where", when that is a
                        // question, and "who" the rest of the time. Not both at
                        // once: on a bar this size the two would elide each
                        // other into nothing.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (remoteDevice != null) {
                                Icon(
                                    PhosphorIcons.Regular.Broadcast,
                                    contentDescription = null,
                                    tint = MiniPlayerAccent,
                                    modifier = Modifier
                                        .size(13.dp)
                                        .padding(end = 1.dp),
                                )
                            }
                            Text(
                                remoteDevice ?: artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (remoteDevice != null) MiniPlayerAccent else MiniPlayerInkDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = if (remoteDevice != null) 4.dp else 0.dp),
                            )
                        }
                    }
                }

                IconButton(onClick = onNext, enabled = state.hasNext) {
                    Icon(
                        PhosphorIcons.Fill.SkipForward,
                        contentDescription = stringResource(R.string.next),
                        tint = MiniPlayerInkDim,
                    )
                }

                // The one control on the bar with a surface of its own, so the
                // tap target that matters most also has weight — and the same
                // press animation every other glass button has.
                LiquidButton(
                    onClick = onTogglePlay,
                    backdrop = backdrop,
                    contentHeight = 36.dp,
                    contentPadding = 0.dp,
                    modifier = Modifier.size(36.dp),
                ) {
                    // Same ring as the player's; see the note there.
                    Box(contentAlignment = Alignment.Center) {
                        if (state.isBuffering) {
                            CircularProgressIndicator(
                                color = MiniPlayerInk.copy(alpha = 0.5f),
                                strokeWidth = 1.5.dp,
                                modifier = Modifier.size(30.dp),
                            )
                        }
                        Crossfade(
                            state.isPlaying,
                            animationSpec = tween(180),
                            label = "playPause",
                        ) { playing ->
                            Icon(
                                imageVector = if (playing) PhosphorIcons.Fill.Pause else PhosphorIcons.Fill.Play,
                                contentDescription = if (playing) stringResource(R.string.pause) else stringResource(R.string.play),
                                tint = MiniPlayerInk,
                                modifier = Modifier.size(20.dp),
                            )
                        }
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
internal val MiniPlayerInk = Color(0xFFF7F8FA)
internal val MiniPlayerInkDim = Color(0xFFF7F8FA).copy(alpha = 0.66f)

/**
 * The green everyone already reads as "this is coming out of something else".
 *
 * Spotify's own, and deliberately so: the mark is theirs, it means one thing,
 * and inventing a colour for it would be asking people to learn a private
 * signal for something they already recognise.
 */
private val MiniPlayerAccent = Color(0xFF1ED760)

/** Guarded against the duration being unknown while a track loads. */
fun progressOf(positionMs: Long, durationMs: Long): Float =
    if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

/** Spacer height so lists can scroll clear of the mini player. */
/**
 * 52dp, measured off the reference: the pill above the tabs is shorter than the
 * tab capsule under it, and a mini player as tall as the bar made the two read
 * as one slab.
 */
val MiniPlayerHeight = 48.dp

@Composable
fun MiniPlayerSpacer() = Box(Modifier.height(MiniPlayerHeight))
