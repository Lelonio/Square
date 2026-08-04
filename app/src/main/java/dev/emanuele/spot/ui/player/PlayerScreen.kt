package dev.emanuele.spot.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.emanuele.spot.ui.components.Artwork
import dev.emanuele.spot.ui.glass.LiquidButton
import dev.emanuele.spot.ui.library.formatDuration
import dev.emanuele.spot.ui.theme.softShadow
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Heart
import com.adamglin.phosphoricons.fill.Pause
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.fill.SkipBack
import com.adamglin.phosphoricons.fill.SkipForward
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.Devices
import com.adamglin.phosphoricons.regular.Heart
import com.adamglin.phosphoricons.regular.Queue
import com.adamglin.phosphoricons.regular.Repeat
import com.adamglin.phosphoricons.regular.RepeatOnce
import com.adamglin.phosphoricons.regular.Shuffle

/**
 * The player, as a liquid-glass prototype.
 *
 * This screen deliberately does not follow the monochrome paper look the rest of
 * the app uses — it is here to be judged on a device before deciding whether to
 * take the whole interface this way. The two are opposites: the paper design
 * builds hierarchy out of ink and soft shadow on an opaque page, and glass
 * builds it out of depth over something vivid. Glass over a near-white page has
 * nothing to refract and looks like grey panels, which is why the artwork comes
 * back here as a full-bleed backdrop.
 */
/** What occupies the middle of the player. */
private enum class Stage { COVER, LYRICS, EFFECTS, QUEUE, CANVAS }

@UnstableApi
@Composable
fun PlayerScreen(
    state: PlaybackState,
    /** Read only inside the waveform, to keep position updates off the rest. */
    positionMs: State<Long>,
    onCollapse: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    queue: List<QueueEntry>,
    lyrics: dev.emanuele.spot.data.Lyrics?,
    lyricsLoading: Boolean,
    onPlayQueueItem: (Int) -> Unit,
    reverb: Float,
    onSpeed: (Float) -> Unit,
    onPitch: (Float) -> Unit,
    onReverb: (Float) -> Unit,
    presets: List<dev.emanuele.spot.playback.EffectPreset>,
    onApplyPreset: (dev.emanuele.spot.playback.EffectPreset) -> Unit,
    onSavePreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    /**
     * The app-wide backdrop, shared rather than built here: this screen covers
     * the same blurred artwork the rest of the app already draws, and recording
     * a second copy of it would be two full-screen blurs for one image.
     */
    backdrop: Backdrop,
    /** The track's Canvas clip, or null when it has none. */
    canvas: dev.emanuele.spot.data.CanvasClip?,
    /** See MiniPlayer: the cover is shared with the bar this screen grew out of. */
    sharedScope: androidx.compose.animation.SharedTransitionScope? = null,
    animatedScope: androidx.compose.animation.AnimatedVisibilityScope? = null,
    /** The Spotify Connect device list, and what to do with it. */
    devices: dev.emanuele.spot.ui.MainViewModel.DevicesState,
    onOpenDevices: () -> Unit,
    onCloseDevices: () -> Unit,
    onRefreshDevices: () -> Unit,
    onSelectDevice: (String) -> Unit,
    /** Null while unknown; see MainViewModel.liked. */
    liked: Boolean?,
    onToggleLiked: () -> Unit,
) {
    var panel by remember { mutableStateOf(PlayerPanel.NONE) }

    // The Canvas gets a layer of its own so the glass over it refracts the clip
    // rather than the app's blurred artwork. It is combined with the app
    // backdrop rather than replacing it, because with no Canvas this layer is
    // empty and the panes would have nothing to sample.
    val canvasBackdrop = rememberLayerBackdrop()
    val glassBackdrop = rememberCombinedBackdrop(backdrop, canvasBackdrop)

    // Lyrics take over the middle of the screen rather than opening a panel at
    // the bottom, and the Canvas goes out of focus behind them: a clip is
    // motion, and reading over motion is the one thing that does not work. Blur
    // keeps it present as light and colour without competing for attention.
    // Any panel, not just the lyrics: they all sit in the middle of the screen
    // now, and reading a slider over a moving clip is no easier than reading a
    // lyric over one.
    val panelOpen = panel != PlayerPanel.NONE
    val canvasBlur by animateDpAsState(
        targetValue = if (panelOpen) 26.dp else 0.dp,
        animationSpec = tween(320),
        label = "canvasBlur",
    )

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (canvasBlur > 0.dp) Modifier.blur(canvasBlur) else Modifier)
                .layerBackdrop(canvasBackdrop),
        ) {
            Crossfade(
                targetState = canvas,
                animationSpec = tween(500),
                label = "canvas",
                modifier = Modifier.fillMaxSize(),
            ) { clip ->
                when {
                    clip == null -> Unit

                    clip.isVideo -> CanvasSurface(
                        url = clip.url,
                        isPlaying = state.isPlaying,
                        modifier = Modifier.fillMaxSize(),
                    )

                    // A handful of canvases are stills rather than clips.
                    else -> AsyncImage(
                        model = clip.url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // Only over a Canvas, and deliberately light. Clips are graded for
            // their own sake and some are near-white; this buys the controls
            // enough contrast without turning the video into a mood board. With
            // no Canvas there is nothing to darken — the app's backdrop is
            // already dimmed.
            if (canvas != null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.28f),
                                    Color.Black.copy(alpha = 0.18f),
                                    Color.Black.copy(alpha = 0.52f),
                                ),
                            ),
                        ),
                )
            }
        }

        CompositionLocalProvider(LocalContentColor provides GlassInk) {
            DismissibleScreen(onDismiss = onCollapse, modifier = Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .padding(horizontal = 20.dp),
                ) {
                    TopBar(
                        backdrop = glassBackdrop,
                        onCollapse = onCollapse,
                        onOpenDevices = onOpenDevices,
                    )

                    // Everything sits at the bottom, as in the reference: the
                    // artwork behind is the subject, and the controls are a
                    // stack of panes laid over its lower third rather than a
                    // screen of their own.
                    // Not scrollable: a weighted spacer inside a scrolling
                    // column measures against an infinite height. The panels
                    // below cap their own height and scroll internally, so the
                    // screen never needs to.
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        // With a Canvas the clip *is* the picture and a cover on
                        // top would compete with it. Without one, the cover
                        // fills the space above the controls — the shadow that
                        // settles on pause is the only motion tying this screen
                        // to the transport, and losing it left a still page.
                        //
                        // `weight(1f)`, not `weight(1f, fill = false)`: with the
                        // column arranged from the bottom, "as much space as it
                        // wants" is nothing once the controls have taken theirs.
                        // One slot, cross-faded, rather than two AnimatedVisibility
                        // blocks taking turns.
                        //
                        // Both of those carried a weight, so while one was
                        // leaving and the other arriving the column briefly had
                        // two weighted children and split the space between
                        // them — the cover shrank sideways as it left instead of
                        // simply going. A plain fade is what opening a lyric
                        // sheet should look like, and holding the slot open
                        // keeps everything below it still.
                        Crossfade(
                            targetState = when (panel) {
                                PlayerPanel.LYRICS -> Stage.LYRICS
                                PlayerPanel.EFFECTS -> Stage.EFFECTS
                                PlayerPanel.QUEUE -> Stage.QUEUE
                                PlayerPanel.NONE ->
                                    if (canvas == null) Stage.COVER else Stage.CANVAS
                            },
                            animationSpec = tween(320),
                            label = "stage",
                            modifier = Modifier.weight(1f),
                        ) { stage ->
                            when (stage) {
                                Stage.COVER -> Box(
                                    Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Cover(
                                        state,
                                        panel,
                                        onNext,
                                        onPrevious,
                                        sharedScope,
                                        animatedScope,
                                    )
                                }

                                Stage.LYRICS -> LyricsStage(
                                    lyrics = lyrics,
                                    loading = lyricsLoading,
                                    positionMs = positionMs,
                                    isPlaying = state.isPlaying,
                                    onSeek = onSeek,
                                )

                                // Effects and the queue share the lyrics' space
                                // rather than opening a sheet under the
                                // controls. A panel that pushed the transport
                                // around every time it opened was the reason
                                // this screen never sat still.
                                Stage.EFFECTS -> Box(
                                    Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    EffectsPanel(
                                        speed = state.speed,
                                        pitch = state.pitch,
                                        reverb = reverb,
                                        onSpeed = onSpeed,
                                        onPitch = onPitch,
                                        onReverb = onReverb,
                                        presets = presets,
                                        onApplyPreset = onApplyPreset,
                                        onSavePreset = onSavePreset,
                                        onDeletePreset = onDeletePreset,
                                        backdrop = glassBackdrop,
                                    )
                                }

                                Stage.QUEUE -> Box(Modifier.fillMaxSize()) {
                                    QueueList(queue, onPlayQueueItem)
                                }

                                // The clip is the picture, and it is drawn
                                // behind everything: this slot only has to keep
                                // the space.
                                Stage.CANVAS -> Box(Modifier.fillMaxSize())
                            }
                        }

                        Spacer(Modifier.height(20.dp))

                        // Title and artist on their own capsule, with the two
                        // per-track actions on the right.
                        GlassSurface(
                            backdrop = glassBackdrop,
                            surfaceColor = GlassFilm,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .fillMaxWidth()
                                .sharedPill(sharedScope, animatedScope),
                        ) {
                            Row(
                                Modifier.padding(start = 22.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TitleBlock(state, Modifier.weight(1f))
                                // The queue's one control. It is a sheet that
                                // opens over the player rather than a view of
                                // it, which is why it sits here and not in the
                                // segmented switch below.
                                RoundGlassButton(
                                    backdrop = glassBackdrop,
                                    size = 40.dp,
                                    onClick = {
                                        panel = if (panel == PlayerPanel.QUEUE) {
                                            PlayerPanel.NONE
                                        } else {
                                            PlayerPanel.QUEUE
                                        }
                                    },
                                ) {
                                    Icon(
                                        PhosphorIcons.Regular.Queue,
                                        contentDescription = "Coda",
                                        tint = if (panel == PlayerPanel.QUEUE) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            GlassInk
                                        },
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                Spacer(Modifier.size(8.dp))
                                RoundGlassButton(
                                    backdrop = glassBackdrop,
                                    size = 40.dp,
                                    // Inert until the check has answered:
                                    // guessing wrong here silently removes
                                    // something from a library.
                                    enabled = liked != null,
                                    onClick = onToggleLiked,
                                ) {
                                    Icon(
                                        if (liked == true) {
                                            PhosphorIcons.Fill.Heart
                                        } else {
                                            PhosphorIcons.Regular.Heart
                                        },
                                        contentDescription = if (liked == true) {
                                            "Togli dai brani salvati"
                                        } else {
                                            "Salva"
                                        },
                                        tint = if (liked == true) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            GlassInk
                                        },
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(18.dp))

                        GlassProgressBar(
                            positionMs = positionMs,
                            durationMs = state.durationMs,
                            onSeek = onSeek,
                            accentColor = GlassInk,
                            trackColor = GlassInk.copy(alpha = 0.28f),
                        )
                        TimeRow(positionMs, state.durationMs)

                        Spacer(Modifier.height(14.dp))

                        Controls(
                            state = state,
                            backdrop = glassBackdrop,
                            onTogglePlay = onTogglePlay,
                            onNext = onNext,
                            onPrevious = onPrevious,
                            onToggleShuffle = onToggleShuffle,
                            onCycleRepeat = onCycleRepeat,
                        )

                        Spacer(Modifier.height(14.dp))

                        PlayerPanelSection(
                            panel = panel,
                            onSelect = { panel = it },
                            queue = queue,
                            lyrics = lyrics,
                            lyricsLoading = lyricsLoading,
                            positionMs = positionMs,
                            onPlayQueueItem = onPlayQueueItem,
                            onSeek = onSeek,
                            speed = state.speed,
                            pitch = state.pitch,
                            reverb = reverb,
                            onSpeed = onSpeed,
                            onPitch = onPitch,
                            onReverb = onReverb,
                            presets = presets,
                            onApplyPreset = onApplyPreset,
                            onSavePreset = onSavePreset,
                            onDeletePreset = onDeletePreset,
                            backdrop = glassBackdrop,
                        )

                        Spacer(Modifier.height(20.dp))
                    }
                }
            }
        }

        // Over everything, including the transport: it is a modal choice, and
        // the controls underneath would be operating a device the user is in
        // the middle of changing.
        if (devices.open) {
            DevicePicker(
                state = devices,
                backdrop = glassBackdrop,
                onSelect = onSelectDevice,
                onRefresh = onRefreshDevices,
                onDismiss = onCloseDevices,
            )
        }
    }
}

@Composable
private fun TopBar(
    backdrop: Backdrop,
    onCollapse: () -> Unit,
    onOpenDevices: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassButton(backdrop, onClick = onCollapse) {
            Icon(PhosphorIcons.Regular.CaretDown, contentDescription = "Chiudi")
        }
        Text(
            "In riproduzione",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        // Connect gets the permanent slot rather than hiding behind "more":
        // moving playback to another speaker is the thing you reach for while
        // the player is open, and the queue already has its own tab below.
        GlassButton(backdrop, onClick = onOpenDevices) {
            Icon(
                PhosphorIcons.Regular.Devices,
                contentDescription = "Dispositivi",
                tint = GlassInk,
            )
        }

    }
}

/**
 * The cover, shown only when the track has no Canvas.
 *
 * Pausing pulls it back and lets the shadow settle toward the page; playing
 * pushes it out again. A spring rather than a tween on the scale, because this
 * answers a button press directly and an eased ramp reads as lag — while the
 * shadow follows a plain tween, since a bouncing shadow makes the cover look
 * like it is trembling.
 */
@Composable
private fun Cover(
    state: PlaybackState,
    panel: PlayerPanel,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    sharedScope: androidx.compose.animation.SharedTransitionScope? = null,
    animatedScope: androidx.compose.animation.AnimatedVisibilityScope? = null,
) {
    val coverFraction by animateFloatAsState(
        targetValue = if (panel == PlayerPanel.NONE) 0.82f else 0.44f,
        label = "cover",
    )
    val playingScale by animateFloatAsState(
        targetValue = if (state.isPlaying) 1f else 0.88f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "coverScale",
    )
    val playingLift by animateFloatAsState(
        targetValue = if (state.isPlaying) 1f else 0.35f,
        animationSpec = tween(420),
        label = "coverLift",
    )

    CoverGestures(
        onNext = onNext,
        onPrevious = onPrevious,
        canGoNext = state.hasNext,
        canGoPrevious = state.hasPrevious,
        modifier = Modifier
            .fillMaxWidth(coverFraction)
            .aspectRatio(1f)
            .graphicsLayer {
                scaleX = playingScale
                scaleY = playingScale
            },
    ) {
        Crossfade(
            targetState = state.artworkUrl to state.title,
            animationSpec = tween(320),
            label = "art",
        ) { (url, title) ->
            Artwork(
                url = url,
                title = title,
                modifier = Modifier
                    .fillMaxSize()
                    .sharedArtwork(sharedScope, animatedScope)
                    .softShadow(
                        RoundedCornerShape(26.dp),
                        elevation = (10 + 30 * playingLift).dp,
                        ambient = 0.10f + 0.10f * playingLift,
                        spot = 0.20f + 0.25f * playingLift,
                    ),
                corner = 26.dp,
            )
        }
    }
}

@Composable
private fun TitleBlock(state: PlaybackState, modifier: Modifier = Modifier) {
    // Slid rather than swapped on a track change: this capsule is the only place
    // the track is named now that the big cover is gone, so the change has to be
    // visible without watching for it.
    AnimatedContent(
        targetState = state.title to state.artist,
        transitionSpec = {
            (slideInVertically { it / 2 } + fadeIn(tween(220)))
                .togetherWith(slideOutVertically { -it / 2 } + fadeOut(tween(170)))
        },
        label = "title",
        modifier = modifier,
    ) { (title, artist) ->
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artist,
                style = MaterialTheme.typography.bodyMedium,
                color = GlassInkDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Isolated so the ticking position recomposes only these two labels. */
@Composable
private fun TimeRow(positionMs: State<Long>, durationMs: Long) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            formatDuration(positionMs.value),
            style = MaterialTheme.typography.bodySmall,
            color = GlassInkDim,
        )
        Text(
            formatDuration(durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = GlassInkDim,
        )
    }
}

@Composable
private fun Controls(
    state: PlaybackState,
    backdrop: Backdrop,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ToggleIcon(
            icon = PhosphorIcons.Regular.Shuffle,
            description = "Riproduzione casuale",
            active = state.shuffleEnabled,
            onClick = onToggleShuffle,
        )

        // Three discs of the same material, the middle one larger. The reference
        // gives the transport its own row of circles rather than icons on a bar,
        // and the size difference is the only thing marking the primary action —
        // no fill, no accent.
        RoundGlassButton(
            backdrop = backdrop,
            size = 62.dp,
            enabled = state.hasPrevious,
            onClick = onPrevious,
        ) {
            Icon(
                PhosphorIcons.Fill.SkipBack,
                contentDescription = "Precedente",
                modifier = Modifier.size(30.dp),
            )
        }

        RoundGlassButton(backdrop = backdrop, size = 76.dp, onClick = onTogglePlay) {
            Crossfade(
                state.isPlaying,
                animationSpec = tween(180),
                label = "playPause",
            ) { playing ->
                Icon(
                    imageVector = if (playing) PhosphorIcons.Fill.Pause else PhosphorIcons.Fill.Play,
                    contentDescription = if (playing) "Pausa" else "Riproduci",
                    modifier = Modifier.size(34.dp),
                )
            }
        }

        RoundGlassButton(
            backdrop = backdrop,
            size = 62.dp,
            enabled = state.hasNext,
            onClick = onNext,
        ) {
            Icon(
                PhosphorIcons.Fill.SkipForward,
                contentDescription = "Successivo",
                modifier = Modifier.size(30.dp),
            )
        }

        ToggleIcon(
            icon = if (state.repeatMode == Player.REPEAT_MODE_ONE) {
                PhosphorIcons.Regular.RepeatOnce
            } else {
                PhosphorIcons.Regular.Repeat
            },
            description = "Ripeti",
            active = state.repeatMode != Player.REPEAT_MODE_OFF,
            onClick = onCycleRepeat,
        )
    }
}

/**
 * A disc of glass that answers a press.
 *
 * Built on the catalog's `LiquidButton` rather than on [GlassSurface]: the
 * squash-and-settle when you push it is the same animation the tab indicator
 * uses, and hand-rolling a second version of it would drift from the one the
 * bar has. A capsule with equal sides is a circle, so no separate shape is
 * needed.
 */
@Composable
private fun RoundGlassButton(
    backdrop: Backdrop,
    size: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    LiquidButton(
        onClick = { if (enabled) onClick() },
        backdrop = backdrop,
        modifier = modifier
            .size(size)
            // Dimmed rather than removed when there is nowhere to go: a control
            // that disappears makes the whole row jump.
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        surfaceColor = GlassFilm,
        contentHeight = size,
        contentPadding = 0.dp,
        // Matched to the bottom bar and the mini player. At the upstream 2dp
        // these were the one place in the app where the glass barely frosted
        // what was behind it.
        blurRadius = 8.dp,
    ) {
        content()
    }
}

@Composable
private fun GlassButton(
    backdrop: Backdrop,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    RoundGlassButton(backdrop = backdrop, size = 44.dp, onClick = onClick) { content() }
}

@Composable
private fun ToggleIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (active) GlassInk else GlassInkDim,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Fixed light, not themed.
 *
 * What sits behind every control here is album art under a dark wash, not the
 * app's page colour, so the light/dark scheme says nothing about what is
 * readable.
 */
internal val GlassInk = Color.White
internal val GlassInkDim = Color.White.copy(alpha = 0.68f)

/**
 * The film every glass surface is tinted with.
 *
 * One value, shared, because the surfaces looked like different materials when
 * each picked its own: the tab bar came out noticeably paler than the mini
 * player and the search button beside it.
 */
internal val GlassFilm = Color.White.copy(alpha = 0.12f)

/**
 * The lyrics, centre stage.
 *
 * Its own composable only so the empty and loading cases stay out of the layout
 * above: this sits where the cover would, so "no lyrics" has to occupy the same
 * space rather than collapsing the screen around it.
 */
@Composable
private fun LyricsStage(
    lyrics: dev.emanuele.spot.data.Lyrics?,
    loading: Boolean,
    positionMs: State<Long>,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            loading -> androidx.compose.material3.CircularProgressIndicator(
                color = GlassInkDim,
                strokeWidth = 2.dp,
            )

            lyrics == null -> Text(
                "Nessun testo per questo brano",
                style = MaterialTheme.typography.bodyMedium,
                color = GlassInkDim,
            )

            else -> LyricsView(
                lyrics = lyrics,
                positionMs = positionMs,
                isPlaying = isPlaying,
                onSeek = onSeek,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
