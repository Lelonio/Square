package dev.lelonio.square.ui.player

import dev.lelonio.square.ui.components.copyFrame
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import kotlin.math.roundToInt
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.withContext
import dev.lelonio.square.backend.spotify.panelAt
import androidx.core.graphics.scale
import dev.lelonio.square.ui.glass.pressable
import dev.lelonio.square.ui.glass.LocalGlassEffectConfig
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.glass.liquidGlass
import dev.lelonio.square.ui.glass.backdrop.backdrops.layerBackdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.rememberCombinedBackdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import dev.lelonio.square.R
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.components.Artwork
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.library.formatDuration
import dev.lelonio.square.ui.theme.softShadow
import com.adamglin.phosphoricons.regular.MonitorPlay
import com.adamglin.phosphoricons.regular.MusicNotes
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.regular.DeviceRotate
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Heart
import com.adamglin.phosphoricons.fill.Pause
import com.adamglin.phosphoricons.fill.MicrophoneStage
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.fill.SkipBack
import com.adamglin.phosphoricons.fill.SkipForward
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.Devices
import com.adamglin.phosphoricons.regular.Heart
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.Broadcast
import com.adamglin.phosphoricons.regular.YoutubeLogo
import com.adamglin.phosphoricons.regular.Repeat
import com.adamglin.phosphoricons.regular.RepeatOnce
import com.adamglin.phosphoricons.regular.Sparkle
import com.adamglin.phosphoricons.regular.Shuffle
import com.adamglin.phosphoricons.regular.TextAlignLeft
import kotlin.math.abs

/**
 * How much of the swipe the clip takes, when it stands in for the cover.
 *
 * Damped: the Canvas is the backdrop every piece of glass above it samples, and
 * moving it one to one drags the whole screen's refraction along with it.
 */
private const val CANVAS_SWIPE_FOLLOW = 0.35f

/**
 * How far a Canvas is darkened while playback is paused.
 *
 * Enough to be unmistakable, not so much that the clip stops being visible —
 * this is a paused picture, not a closed one.
 */
private const val PAUSED_DIM = 0.55f

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
/**
 * How long the cover and a Canvas take to change places.
 *
 * Matched to the clip's own fade below: the two are one movement, and a
 * handover where each half runs at its own speed is visible as a dip.
 */
/** How far the picture behind an open panel goes down. */
private const val PANEL_DIM = 0.26f

private const val CANVAS_HANDOVER = 500

/** The shape the catalogue files an extended cover in: three by four. */
private const val DETAIL_ASPECT = 3f / 4f

/**
 * Where a Canvas stops being a picture, in its own slot's terms.
 *
 * The same shape as the cover's fade — see HeroBackdrop's `softening` — written
 * out here because a clip is drawn by a different path and there is nothing to
 * share but the numbers. Eased rather than straight: a linear ramp has a corner
 * at each end, and a corner across a moving picture is a band.
 */
private val CLIP_FADE: Array<Pair<Float, Color>> = clipFade(0.82f, 0.97f)

private fun clipFade(from: Float, to: Float): Array<Pair<Float, Color>> {
    val steps = 8
    return Array(steps + 1) { index ->
        val t = index.toFloat() / steps
        val eased = t * t * (3f - 2f * t)
        (from + (to - from) * t) to Color.Black.copy(alpha = 1f - eased)
    }
}

/**
 * The row the "watch the video" button sits in, kept whether there is one or
 * not: its height is the button's own plus the gap to the title under it.
 */
private val VIDEO_ROW_HEIGHT = 54.dp

/** How long each half of a change between the cover and a panel takes. */
private const val STAGE_FADE_MS = 180

/** And the whole of a change between the cover and a Canvas; see the use of it. */
private const val CLIP_FADE_MS = 460

/**
 * The song, on one line across the top while its lyrics are read alone: the
 * cover, the title and the artist, and a tap to go back to the cover.
 */
@Composable
private fun NowReadingLine(
    state: PlaybackState,
    backdrop: Backdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** On its side: no wider than a phone held upright, in the middle. */
    sideways: Boolean = false,
) {
    GlassSurface(
        backdrop = backdrop,
        surfaceColor = LocalPlayerFilm.current,
        shape = RoundedCornerShape(50),
        modifier = modifier
            .padding(vertical = 6.dp)
            .then(if (sideways) Modifier.widthIn(max = 560.dp) else Modifier)
            .fillMaxWidth()
            .pressable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            dev.lelonio.square.ui.components.Artwork(
                url = state.artworkUrl,
                title = state.title,
                corner = 22.dp,
                decodeSize = 44.dp,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    state.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = GlassInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = GlassInkDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** How long a song plays over its lyrics, untouched, before they are left alone. */
private const val LYRICS_ALONE_AFTER_MS = 4_000L

/** Slow on the way out, as a thing noticed late; quick back, as an answer to a touch. */
private const val LYRICS_ALONE_LEAVE_MS = 700
private const val LYRICS_ALONE_RETURN_MS = 300

/**
 * Shown to the extent of [shown], 0 to 1, in height as well as opacity, so
 * what is below or above closes the gap as it goes. Kept in the composition
 * rather than removed: the title pill is the one the mini player grows out
 * of, and a pill that was not there could not be shared.
 */
private fun Modifier.retreat(shown: Float): Modifier = this
    .graphicsLayer {
        alpha = shown * shown
        // Cut to what is left of it, for touches as much as for the eye: a
        // pane folded away but still laid out over its neighbour took their
        // presses. Not at rest, where the glass's shadow reaches past it.
        clip = shown < 1f
    }
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val height = (placeable.height * shown).roundToInt()
        layout(placeable.width, height) { placeable.place(0, height - placeable.height) }
    }

private enum class Stage {
    COVER, LYRICS, EFFECTS, INFO, QUEUE, DEVICES, ADD_TO_PLAYLIST, CANVAS, VIDEO
}

@UnstableApi
@Composable
fun PlayerScreen(
    state: PlaybackState,
    /**
     * Spotify's DJ is talking: its lights take the place of the cover, since
     * the song they would show has not started. See DjVoice.
     */
    djSpeaking: Boolean = false,
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
    lyrics: dev.lelonio.square.data.Lyrics?,
    lyricsLoading: Boolean,
    /** The song the lyrics are for, so the panel can ask every source about it. */
    lyricsQuery: dev.lelonio.square.backend.lyrics.LyricsQuery? = null,
    /** Words picked from another source in the panel. */
    onLyricsChosen: (dev.lelonio.square.data.Lyrics) -> Unit = {},
    /** Who made the track, for the credits panel; null until it is asked for. */
    credits: dev.lelonio.square.backend.spotify.SpotifyCredits.Credits?,
    creditsLoading: Boolean,
    /** Asked for when the credits panel is opened, and not before. */
    onWantCredits: (String?) -> Unit,
    onPlayQueueItem: (Int) -> Unit,
    /** Reordering, removing and the rest of what the queue sheet does. */
    queueActions: QueueActions,
    /** The music video for this track, when the catalogue has one. */
    videoFileId: String? = null,
    /** Whether the listener has asked to watch rather than listen. */
    videoMode: Boolean = false,
    onToggleVideo: () -> Unit = {},
    reverb: Float,
    onSpeed: (Float) -> Unit,
    onPitch: (Float) -> Unit,
    onReverb: (Float) -> Unit,
    presets: List<dev.lelonio.square.playback.EffectPreset>,
    onApplyPreset: (dev.lelonio.square.playback.EffectPreset) -> Unit,
    onSavePreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    /**
     * The app-wide backdrop, shared rather than built here: this screen covers
     * the same blurred artwork the rest of the app already draws, and recording
     * a second copy of it would be two full-screen blurs for one image.
     */
    backdrop: Backdrop,
    /** The track's Canvas clip, or null when it has none. */
    canvas: dev.lelonio.square.data.CanvasClip?,
    /**
     * The tall picture the record carries, where the catalogue has one.
     *
     * A song has one square cover and nothing else; the extended artwork
     * belongs to its album, which is where the reference's own player takes it
     * from as well. Null falls back to the square cover.
     */
    /** The colours the catalogue filed with this artwork; see CoverAura. */
    catalogPalette: List<String> = emptyList(),
    /**
     * The foot of the Canvas, across its width, as it plays.
     *
     * The field this player sits on is built from the cover, and a Canvas is
     * very often nothing like its sleeve: the clip faded into a colour that was
     * not in it. Empty whenever no clip is showing, which puts the cover's own
     * colours back.
     */
    onClipColumns: (List<Color>) -> Unit = {},
    coverHeroUrl: String? = null,
    /** And the moving version of it, for the records that have one. */
    coverMotionUrl: String? = null,
    /**
     * The catalogue's own square scan, for the records with no tall picture.
     *
     * Better than falling straight back to the cover the queue carries: that
     * one is sized for a list row, and this screen enlarges whatever it is
     * given to fill a phone.
     */
    coverSquareUrl: String? = null,
    /**
     * The other catalogue has not answered yet.
     *
     * Holds the queue's own cover back for that moment: it is the fallback, and
     * drawing it first only to replace it with a sharper copy of the same
     * picture is the swap the listener sees.
     */
    coverPending: Boolean = false,
    /** See MiniPlayer: the cover is shared with the bar this screen grew out of. */
    sharedScope: androidx.compose.animation.SharedTransitionScope? = null,
    animatedScope: androidx.compose.animation.AnimatedVisibilityScope? = null,
    /** The Spotify Connect device list, and what to do with it. */
    devices: dev.lelonio.square.ui.MainViewModel.DevicesState,
    onOpenDevices: () -> Unit,
    onCloseDevices: () -> Unit,
    onRefreshDevices: () -> Unit,
    onSelectDevice: (String) -> Unit,
    /**
     * Opens the "add to playlist" sheet.
     *
     * The sheet itself is drawn by the app rather than by this screen: the track
     * rows in the library open the same one, and two copies would be two states
     * of one thing.
     */
    onAddToPlaylist: () -> Unit,
    /**
     * Opens an artist, a playlist or an album by uri, with a name to show while
     * it loads. The player closes itself first; see the two callers.
     */
    onOpenUri: (String, String) -> Unit = { _, _ -> },
    /** Opens the record the playing track is on; null when there is none. */
    onOpenAlbum: (() -> Unit)? = null,
    /**
     * Whether this track is already in one of the account's playlists.
     *
     * Only ever true when it is known to be; see MainViewModel.inPlaylists. A
     * button that says "add" over a track that is already there is a small lie,
     * and the answer is cheap for anything the app has already read.
     */
    alreadySaved: Boolean = false,
    /**
     * Whether it is in Liked Songs, which is a different answer and a different
     * mark: a heart, the way every Spotify client says it.
     */
    inLikedSongs: Boolean = false,
    /** Direct toggle for Liked Songs ("Tus me gusta"). */
    onToggleLike: (() -> Unit)? = null,
    /** Starts a station from the current track; null where there is none. */
    onRadio: (() -> Unit)? = null,
    /** The playlist picker's state, shown in the panel rather than as a sheet. */
    addToPlaylist: MainViewModel.AddToPlaylistState,
    onPickPlaylist: (dev.lelonio.square.data.CatalogPlaylist) -> Unit,
    /**
     * False when the source has no writable playlists.
     *
     * Adding a track to a playlist goes through the Spotify Web API; on another
     * source the button could only open a picker that fails on every choice.
     */
    playlistEditAvailable: Boolean = true,
    /**
     * Turns the current track's video on or off, or null when it has none.
     *
     * The same track either way — the source reopens on the muxed stream, which
     * carries the picture along with the sound — so the queue, the position and
     * everything else stay as they were.
     */
    onWatchVideo: (() -> Unit)? = null,
    /** Showing the picture right now. */
    videoOn: Boolean = false,
    /** Why the picture is not the song's official video, when it is not. */
    videoNotice: String? = null,
    /** The player to hang the video surface off; null when there is no video. */
    videoPlayer: Player? = null,
    /** Changes when the session starts playing a new video; see VideoStage. */
    videoAttachKey: Any? = null,
    /**
     * False when the source is not Spotify.
     *
     * Connect is Spotify's own protocol for handing playback to another
     * speaker; on any other source the button would open a list that can only
     * ever be empty, so it is not drawn at all.
     */
    connectAvailable: Boolean = true,
    /** Whether the sound is coming out of another of the account's devices. */
    onAnotherDevice: Boolean = false,
) {
    var panel by remember { mutableStateOf(PlayerPanel.NONE) }
    // The official SDK mixes its own audio: no effects or karaoke reach it.
    val sdkPlayback by dev.lelonio.square.playback.websdk.WebSdkRecovery.inUse.collectAsStateWithLifecycle()
    LaunchedEffect(sdkPlayback) {
        if (sdkPlayback && panel == PlayerPanel.EFFECTS) panel = PlayerPanel.NONE
    }

    /** Bumped to open the karaoke control; see KaraokeDial. */
    var karaokeExpand by remember { androidx.compose.runtime.mutableIntStateOf(0) }



    // The one panel whose contents are fetched rather than already here, and
    // the one nobody opens for most songs. Asking on open keeps a request per
    // track from being made for a page most listeners never see.
    LaunchedEffect(panel, state.mediaId) {
        if (panel == PlayerPanel.INFO) onWantCredits(state.mediaId)
    }

    // How far the track-change swipe has been dragged, when the clip stands in
    // for the cover. Written from the gesture and read only inside a
    // graphicsLayer, so following the finger costs a redraw of one layer rather
    // than a recomposition of the player.
    // No Canvas behind a video.
    //
    // The Canvas is the loop that stands in for a picture when there is none;
    // with the video playing there is one, and two moving images arguing over
    // the same screen is what the official client does not do either.
    @Suppress("NAME_SHADOWING")
    val canvas = canvas?.takeIf { !videoOn }

    val canvasShift = remember { mutableFloatStateOf(0f) }

    // Whether the clip has put a frame on screen yet.
    //
    // A Canvas is known about — the URL arrives with the track — well before it
    // has decoded anything, and in between the surface is empty. Opening the
    // player onto that gap showed a blank middle where the cover belongs, so
    // the cover stays until this turns true and the clip fades in over it.
    // Reset per track: the next one starts from nothing again.
    var canvasReady by remember(canvas?.url) { mutableStateOf(false) }

    // The Canvas gets a layer of its own so the glass over it refracts the clip
    // rather than the app's blurred artwork. It is combined with the app
    // backdrop rather than replacing it, because with no Canvas this layer is
    // empty and the panes would have nothing to sample.
    val canvasBackdrop = rememberLayerBackdrop()

    // The video's light on the walls, in a layer of its own under the clip's.
    // It is what the screen is coloured by while a video plays, and the panes
    // that skip the clip's layer (below it, or over a moving cover) still have
    // to see it: reading the app's backdrop alone, they refracted the sleeve's
    // colours from under the light, and the glass came out the record's
    // brown over a screen lit blue. This layer changes only as the light
    // fades, not with every frame of the video.
    val lightBackdrop = rememberLayerBackdrop()
    val groundBackdrop = rememberCombinedBackdrop(backdrop, lightBackdrop)
    val glassBackdrop = rememberCombinedBackdrop(backdrop, lightBackdrop, canvasBackdrop)

    // The panes below the clip only refract it when they are actually over it.
    //
    // The clip's layer covers the whole screen and is redrawn with every frame
    // of the video, so every pane reading it redid its blur thirty times a
    // second, including the title, the controls and the tabs, which on most
    // phones sit below the clip, where the layer is empty. Measured rather
    // than assumed: on a short screen the title does reach into the clip, and
    // there it keeps refracting it.
    var clipBottom by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    var lowerTop by remember { mutableFloatStateOf(0f) }
    val lowerBackdropAlone = if (lowerTop >= clipBottom) groundBackdrop else glassBackdrop

    // The panes over a moving Apple cover read the page's own blurred ground
    // instead of the cover. The cover is in the layer they refract, and every
    // frame of it had that whole layer recorded again and every pane over it
    // blurred again; behind this much glass the motion is only a shimmer, and
    // it was costing the player half its frames.
    val coverMoving = coverMotionUrl != null && canvas == null
    val upperBackdrop = when {
        panel != PlayerPanel.NONE -> groundBackdrop
        coverMoving -> groundBackdrop
        else -> glassBackdrop
    }
    // The panes below it too. The cover's picture carries on under it to the
    // foot of the screen, moving with it, so the layer changed under the title,
    // the transport and the tabs with every frame as well, and each of them
    // redid its blur and its lens: a trace of the player over a moving cover
    // counted close to a hundred render passes a frame.
    val lowerBackdropMoving = if (coverMoving) groundBackdrop else lowerBackdropAlone
    // Behind a panel every pane reads the same layer: the ground, the cover
    // blurred over the whole screen, which is near enough one colour from top
    // to bottom. The round buttons beside the lyrics read the cover's own
    // layer while the panes under them read the ground, and the two looked
    // like different glass (#85); moved to the cover's layer, the tabs at the
    // foot took its dark end and came out grey under brown panes.
    val panelBackdrop = groundBackdrop
    val lowerBackdrop = if (panel != PlayerPanel.NONE) panelBackdrop else lowerBackdropMoving

    // Lyrics take over the middle of the screen rather than opening a panel at
    // the bottom, and the Canvas goes out of focus behind them: a clip is
    // motion, and reading over motion is the one thing that does not work. Blur
    // keeps it present as light and colour without competing for attention.
    // Any panel, not just the lyrics: they all sit in the middle of the screen
    // now, and reading a slider over a moving clip is no easier than reading a
    // lyric over one.
    val panelOpen = panel != PlayerPanel.NONE

    // On its side: the stage and the controls side by side; see stageBlock.
    val sideways = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    // On its side the player is two halves: the song on a card to the left,
    // the views — lyrics, queue, effects, credits — to the right; see SongCard.
    val readingSideways = sideways
    // The cover is the card's on its side, so the views start at the lyrics.
    LaunchedEffect(sideways, panel) {
        if (sideways && panel == PlayerPanel.NONE) panel = PlayerPanel.LYRICS
    }

    // The words on their own, as Apple Music does it: while a song plays over
    // its lyrics and nobody touches the screen, everything but the transport
    // steps aside and the lyrics take the room, under a line with the song on
    // it. A touch anywhere else brings it all back, and so does a pause; a
    // touch on the lyrics does not, since scrolling through them is reading
    // them. See retreat.
    var lastTouch by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    // Set by the stage as a press arrives, read by the column after it.
    var pressOnLyrics by remember { mutableStateOf(false) }
    var lyricsAlone by remember { mutableStateOf(false) }
    val lyricsReading = (panel == PlayerPanel.LYRICS || (sideways && panel == PlayerPanel.NONE)) &&
        lyrics != null && !lyricsLoading
    // On wantsPlay, not isPlaying: a tap on a line seeks, the player stops for
    // the moment it takes to load the new place, and on isPlaying that moment
    // brought everything back and folded it away again four seconds later.
    LaunchedEffect(lyricsReading, state.wantsPlay, lastTouch) {
        lyricsAlone = false
        if (!lyricsReading || !state.wantsPlay) return@LaunchedEffect
        kotlinx.coroutines.delay(LYRICS_ALONE_AFTER_MS)
        lyricsAlone = true
    }
    // The screen stays on while the words are being sung along to. Left to
    // the phone's own timeout it dimmed and locked halfway through a verse,
    // which on a page meant to be read from across the room is the page going
    // away. Only while it plays: paused, the phone sleeps as it would anyway.
    val hostView = androidx.compose.ui.platform.LocalView.current
    val keepAwake = lyricsReading && state.wantsPlay
    DisposableEffect(hostView, keepAwake) {
        if (keepAwake) hostView.keepScreenOn = true
        onDispose { if (keepAwake) hostView.keepScreenOn = false }
    }
    val chrome by animateFloatAsState(
        targetValue = if (lyricsAlone) 0f else 1f,
        animationSpec = tween(if (lyricsAlone) LYRICS_ALONE_LEAVE_MS else LYRICS_ALONE_RETURN_MS),
        label = "chrome",
    )

    val canvasBlur by animateDpAsState(
        targetValue = if (panelOpen) 26.dp else 0.dp,
        animationSpec = tween(320),
        label = "canvasBlur",
    )

    // And a little darker with it. Blur takes the detail out of what is behind
    // a panel but not the light: on a bright sleeve the words of a lyric sat on
    // a white glow. A clip has a dimmer of its own below, for the same reason
    // and on the same signal.
    val panelDim by animateFloatAsState(
        targetValue = if (panelOpen) PANEL_DIM else 0f,
        animationSpec = tween(320),
        label = "panelDim",
    )

    // The video's own light, sampled by the stage below and spread over the
    // whole screen from here — the way an ambient television lights the wall
    // behind it rather than just its own frame. Null whenever no video is
    // showing, which is what puts the ordinary backdrop back.
    var ambient by remember { mutableStateOf<AmbientEdges?>(null) }
    LaunchedEffect(videoOn) { if (!videoOn) ambient = null }

    // Nothing of the last clip left behind on a track with no Canvas.
    val clipReport = androidx.compose.runtime.rememberUpdatedState(onClipColumns)
    LaunchedEffect(canvas?.url, canvas?.isVideo) {
        if (canvas?.isVideo != true) clipReport.value(emptyList())
    }

    val readPalette by dev.lelonio.square.ui.theme.rememberArtworkPalette(
        state.artworkUrl.takeIf { canvas == null },
    )

    // The catalogue's own colours for this artwork where it has them.
    //
    // Ours are swatches pulled out of the bitmap by whatever reads as most
    // vivid, and on a crimson sleeve with a black rose on it that came back
    // blue — a light behind the cover in a colour the cover does not contain.
    // Theirs were chosen to sit on this picture, and they are the four the
    // reference itself uses.
    val catalogAura = remember(catalogPalette) {
        catalogPalette.mapNotNull { hex ->
            runCatching { Color(android.graphics.Color.parseColor("#$hex")) }.getOrNull()
        }
    }
    val auraColors = if (canvas == null && catalogAura.isNotEmpty()) catalogAura else readPalette

    // The tone the cover fades into, worked out the same way the pages do it.
    val coverAccent by dev.lelonio.square.ui.theme.rememberArtworkColor(state.artworkUrl)
    val coverTone = dev.lelonio.square.ui.theme.pageColorFor(coverAccent)

    Box(
        Modifier
            .fillMaxSize()
            ,
    ) {
        // Recorded for the glass above, so light drawn here is light the
        // buttons and the panels refract; see lightBackdrop.
        Box(Modifier.fillMaxSize().layerBackdrop(lightBackdrop)) {
            // On its side, the room is the sleeve itself, blurred and dimmed:
            // the picture behind the player upright is often another one — the
            // record's extended artwork — and beside the sleeve it read as two
            // different covers at once.
            if (sideways) {
                dev.lelonio.square.ui.components.Artwork(
                    url = state.artworkUrl,
                    title = state.title,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(70.dp),
                    corner = 0.dp,
                )
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            }
            AmbientLight(ambient)
            // In this layer so the glass refracts the DJ, not the cover under it.
            val djShown by animateFloatAsState(
                targetValue = if (djSpeaking) 1f else 0f,
                animationSpec = tween(if (djSpeaking) 450 else 700),
                label = "djShown",
            )
            if (djShown > 0f) {
                DjVoice(Modifier.fillMaxSize().graphicsLayer { alpha = djShown })
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .then(if (canvasBlur > 0.dp) Modifier.blur(canvasBlur) else Modifier)
                // The clip follows a track-change swipe, since with a Canvas
                // playing it is the only thing on screen the gesture could be
                // about. Damped, because the clip is the backdrop of everything
                // above it and moving it one to one drags the whole screen's
                // refraction with it.
                .graphicsLayer {
                    val shift = canvasShift.floatValue
                    if (shift == 0f) return@graphicsLayer
                    translationX = shift * CANVAS_SWIPE_FOLLOW
                    val travel = (abs(shift) / size.width).coerceIn(0f, 1f)
                    val shrink = 1f - travel * 0.12f
                    scaleX = shrink
                    scaleY = shrink
                    alpha = 1f - travel * 0.5f
                }
                .layerBackdrop(canvasBackdrop),
        ) {
            // The stand-in for a Canvas, for the tracks that have none, and
            // only when it has been asked for. Inside the recorded layer, so
            // the glass above refracts it rather than sitting on top of it.
            // The stand-in is for a screen with nothing moving on it. With a
            // video playing there is something, and the glow above is taken
            // from its own frames: drawing the cover's aura over that would
            // paint the song's colours on top of the video's.
            // The picture leaves on the same clock the clip arrives on.
            //
            // It used to be a condition: the moment a Canvas existed, the cover
            // was dropped from the composition and the clip faded up over
            // whatever was behind it — a cut on one side of a crossfade, which
            // reads as the screen blinking. Held as an opacity instead, so the
            // two overlap for the length of the change, and it comes back the
            // same way on a track that has no clip.
            val coverFade by animateFloatAsState(
                // Never on its side, where the room is the sleeve, blurred: the
                // stand-in is the record's extended artwork, often another
                // picture, and it was what showed behind the sleeve.
                targetValue = if (!sideways && !videoOn && (canvas == null || !canvasReady)) 1f else 0f,
                animationSpec = tween(CANVAS_HANDOVER),
                label = "coverFade",
            )

            if (coverFade > 0f) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = coverFade }) {
                // The cover, shown the way every other screen in the app shows
                // a picture it is built around: filling the top, softening on
                // the way down, ending on the colour taken from it. A track
                // with no clip used to get moving light in those same colours
                // and nothing of the picture itself — which was a stand-in for
                // an image the app had all along.
                dev.lelonio.square.ui.components.HeroBackdrop(
                    artworkUrl = coverHeroUrl
                        ?: coverSquareUrl
                        ?: state.artworkUrl.takeIf { !coverPending },
                    // The moving cover only while the player is standing
                    // still. Otherwise its TextureView goes black for the
                    // length of the closing animation and is the last thing
                    // left on screen — see LocalPlayerSettled. The still
                    // picture underneath is what the travel shows instead, and
                    // at that size and speed the swap is invisible.
                    motionUrl = coverMotionUrl.takeIf { LocalPlayerSettled.current },
                    // And still behind a panel, as a Canvas is, where it stands
                    // rather than taken away; see MotionCover's playing.
                    motionPlaying = !panelOpen,
                    title = state.title,
                    pageColor = coverTone,
                    pending = coverPending,
                    // Where the picture stops being a picture. It runs longer
                    // when it is the only one on the screen — there is no sleeve
                    // over it to look at, so it has to hold the top of the
                    // player on its own — and ends earlier when it is only the
                    // ground under a square cover.
                    // Left alone to its own bottom edge, and softened only in
                    // the last stretch before it.
                    //
                    // Measured on the screen: the picture is three by four from
                    // the top, so it ends where the "watch the video" button
                    // begins, and the fade runs in the last stretch above that
                    // line rather than starting a third of the way up.
                    //
                    // It has to *finish* before that line, though, and not on
                    // it. Softening right up to the edge leaves the picture
                    // still faintly there where it stops, and a picture that is
                    // faintly there has a border — which is the seam the fade is
                    // for. This lands the colour a few percent early, so what
                    // reaches the bottom of the frame is colour and nothing
                    // else.
                    // A longer run than the numbers suggest they are: the
                    // ramp between them is eased, so the first fifth of it is
                    // barely there and the picture reads as sharp well past the
                    // point it starts giving way. See softening.
                    softenFrom = 0.82f,
                    softenTo = 0.97f,
                    // And, once the controls are laid out, where they begin
                    // instead: the three by four only lands on the button on
                    // some phones, and the blur ahead of the fade put the
                    // picture's visible end well above it even there.
                    // And carried on below that, in its own colours, rather
                    // than giving way to the app's darkened field.
                    extendPicture = true,
                    // Three by four, whichever picture it is — the shape the
                    // extended covers are drawn in, and the shape that reaches
                    // down to the title with nothing empty in between.
                    //
                    // A square sleeve is trimmed at the sides to fit it, not
                    // enlarged: the catalogue's scan is 1600 across and the
                    // screen is 1080, so what is cropped is real picture and
                    // what is drawn is still smaller than the source.
                    imageAspect = DETAIL_ASPECT,
                    // The picture ends on itself here, not on the page's tone:
                    // behind this whole screen is the same artwork, blurred and
                    // filling it, so the sharp copy has its own blur to dissolve
                    // into. See fadeToPage.
                    fadeToPage = false,
                    // The tall picture comes with a blur of its own well above
                    // where this one fades; enlarged until the two coincide.
                    // A square scan has none and is shown as it is.
                    zoom = if (coverHeroUrl != null) {
                        dev.lelonio.square.ui.components.TALL_ART_ZOOM
                    } else {
                        1f
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                // And the light still moves, in the cover's own colours: it is
                // what the glass above has to bend. Only *under* the picture,
                // though — beams sweeping across a photograph wash it out, and
                // the part of this screen that needs something alive in it is
                // the colour field below, where the controls sit.
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            // Where the picture ends, in this box's own terms:
                            // it is as tall as the screen is wide, times four
                            // thirds. Measured rather than guessed, since the
                            // slot is whatever the phone leaves it.
                            val ends = (size.width / DETAIL_ASPECT / size.height)
                                .coerceIn(0f, 1f)
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    (ends * 0.8f) to Color.Transparent,
                                    ends to Color.Black,
                                    1f to Color.Black,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                ) {
                    // Only where there is no picture to expand.
                    //
                    // The reference puts no invented light on this screen at
                    // all: what fills it above and below the cover is the cover
                    // itself, blurred and carried past its own edges. Coloured
                    // beams over that are a second, competing source — and being
                    // colours *chosen* rather than colours present, they can be
                    // ones the sleeve does not contain. A crimson cover was
                    // reading blue at the foot of the screen for exactly that
                    // reason. So this is now the stand-in for a screen with no
                    // artwork behind it, and nothing else.
                    if (coverHeroUrl == null && coverSquareUrl == null &&
                        state.artworkUrl == null
                    ) {
                        CoverAura(colors = auraColors, playing = state.isPlaying)
                    }
                }
                }
            }


            // Held at zero until the first frame, then faded up. Without this
            // the clip's own surface appears the instant it decodes, which next
            // to a cover crossfading out reads as two separate events.
            val clipAlpha by animateFloatAsState(
                targetValue = if (canvasReady) 1f else 0f,
                animationSpec = tween(420),
                label = "clipAlpha",
            )

            // The clip takes the cover's place rather than the screen's.
            //
            // Full-bleed, a Canvas ran under the title, the transport and the
            // tab bar — every one of them a pane of glass, and glass over a
            // bright, moving, ungraded picture is not readable at any tint. The
            // page already knows how to end a picture: the cover sits in this
            // slot and dissolves into a blurred field made of itself. A clip is
            // a cover that moves, so it goes in the same slot and gets the same
            // ending, and the controls sit on the field the way they do for
            // every other track.
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(DETAIL_ASPECT)
                    .align(Alignment.TopCenter)
                    .onGloballyPositioned { clipBottom = it.boundsInWindow().bottom }
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(*CLIP_FADE),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            ) {
            Crossfade(
                targetState = canvas,
                animationSpec = tween(500),
                label = "canvas",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (canvas?.isVideo == true) clipAlpha else 1f },
            ) { clip ->
                when {
                    clip == null -> Unit

                    // The video only while the player is at rest.
                    //
                    // A TextureView inside a layer that is being scaled and
                    // faded — which is exactly what the closing animation does —
                    // goes black for the length of the travel: its surface is
                    // detached and re-attached around the layer change, and
                    // there is nothing to draw in between. Standing in the
                    // artwork for those few hundred milliseconds is invisible;
                    // a black rectangle sliding down the screen is not.
                    clip.isVideo && LocalGlassEnabled.current -> CanvasSurface(
                        url = clip.url,
                        // Still behind a panel. Out of focus and dimmed, the
                        // motion is barely there, and it was the most costly
                        // thing on the screen: every new frame of the clip had
                        // the whole screen blurred again and every pane of
                        // glass redrawn over it, which is what made the lyrics
                        // stutter. A still frame is blurred once.
                        isPlaying = state.isPlaying && !panelOpen,
                        onFirstFrame = { canvasReady = true },
                        // Only while this clip is still the one playing.
                        //
                        // A Crossfade keeps the outgoing content alive for the
                        // length of the fade, and a Canvas that is leaving goes
                        // on reading itself for those few hundred milliseconds.
                        // Its last report landed *after* the track change had
                        // emptied the field, so a song with no clip of its own
                        // kept the previous one's colours — there was nothing
                        // left to clear them again.
                        onTone = { columns ->
                            if (clip.url == canvas?.url) onClipColumns(columns)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Nothing at all, rather than the cover: what shows through
                    // is the app's own blurred artwork, which is the same
                    // picture out of focus and reads as the clip softening for a
                    // moment. Dropping a sharp cover in for the length of the
                    // animation was worse than the black rectangle it fixed.
                    clip.isVideo -> Unit

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
            //
            // It deepens on pause. A stilled clip is a frozen picture that looks
            // no different from a playing one held on a slow shot, so pausing
            // read as the video having stalled rather than as playback having
            // stopped. Dimming it says the same thing the picture cannot.
            if (canvas != null) {
                // Darkened for a paused track and for an open panel alike. Both
                // are the same statement — the clip is not what you are looking
                // at right now — and the blur alone left a bright moving picture
                // behind a column of text.
                val dim by animateFloatAsState(
                    targetValue = if (state.isPlaying && !panelOpen) 0f else 1f,
                    animationSpec = tween(420),
                    label = "canvasDim",
                )
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
                        )
                        .drawWithContent {
                            drawContent()
                            drawRect(Color.Black, alpha = dim * PAUSED_DIM)
                        },
                )
            }
            }

            // The panel's own shade over the picture, inside the recorded layer
            // so the glass above refracts what the reader sees rather than the
            // bright original. Not over a Canvas: that one is darkened by the
            // gradient above, which already answers an open panel.
            if (panelDim > 0f && canvas == null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = panelDim)),
                )
            }

            // The DJ over the cover it replaces while it talks: the cover is drawn
            // in this layer, above the light one, and hid it there.
            val djOver by animateFloatAsState(
                targetValue = if (djSpeaking) 1f else 0f,
                animationSpec = tween(if (djSpeaking) 450 else 700),
                label = "djOver",
            )
            if (djOver > 0f) {
                DjVoice(Modifier.fillMaxSize().graphicsLayer { alpha = djOver })
            }
        }

        CompositionLocalProvider(LocalContentColor provides GlassInk) {
            DismissibleScreen(onDismiss = onCollapse, modifier = Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        // Watched, never consumed: the touch still does
                        // whatever it was for, and the controls come back
                        // while it does. On the way back up, so the stage has
                        // said whether the press was on the lyrics.
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Final)
                                    if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
                                        if (!(lyricsAlone && pressOnLyrics)) {
                                            lastTouch = android.os.SystemClock.uptimeMillis()
                                        }
                                        pressOnLyrics = false
                                    }
                                }
                            }
                        }
                        .systemBarsPadding()
                        .padding(horizontal = 20.dp),
                ) {
                    // Across the top upright; at the top of the song's
                    // half on its side, where the video, devices and the way
                    // down would otherwise be missing.
                    val topBar: @Composable (Modifier) -> Unit = { barModifier ->
                    TopBar(
                        backdrop = upperBackdrop,
                        panel = panel,
                        source = state.source,
                        onOpenSource = state.contextUri?.let { uri ->
                            {
                                onCollapse()
                                onOpenUri(uri, state.source.substringAfterLast("· ").trim())
                            }
                        },
                        onCollapse = onCollapse,
                        onOpenDevices = {
                            panel = if (panel == PlayerPanel.DEVICES) {
                                PlayerPanel.NONE
                            } else {
                                onOpenDevices()
                                PlayerPanel.DEVICES
                            }
                        },
                        connectAvailable = connectAvailable,
                        onAnotherDevice = onAnotherDevice,
                        onWatchVideo = onWatchVideo,
                        videoOn = videoOn,
                        modifier = barModifier,
                        dj = isDjQueue(state.contextUri),
                    )
                    }
                    if (!sideways) topBar(Modifier.retreat(chrome))

                    // In its place while the lyrics are read alone: the song,
                    // and the way back to its cover.
                    NowReadingLine(
                        state = state,
                        sideways = sideways,
                        // The ground, as the title pill reads it: over the
                        // lyrics the clip is blurred on screen and not in its
                        // own layer, and a pane refracting that layer showed
                        // the moving cover sharp through frosted glass.
                        backdrop = groundBackdrop,
                        onClick = { panel = PlayerPanel.NONE },
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .retreat(if (readingSideways) 0f else 1f - chrome),
                    )

                    // Everything sits at the bottom, as in the reference: the
                    // artwork behind is the subject, and the controls are a
                    // stack of panes laid over its lower third rather than a
                    // screen of their own.
                    // Not scrollable: a weighted spacer inside a scrolling
                    // column measures against an infinite height. The panels
                    // below cap their own height and scroll internally, so the
                    // screen never needs to.
                    // The stage and the controls, as two pieces: one above the other on a
                    // phone held upright, side by side on its side, where one above the
                    // other left the controls stretched across the screen and the tabs
                    // pushed off the bottom of it.
                    val tabsBlock: @Composable () -> Unit = {
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
                            backdrop = lowerBackdrop,
                            modifier = Modifier.retreat(chrome),
                            showCover = !sideways,
                        )
                    }
                    val stageBlock: @Composable ColumnScope.() -> Unit = {
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
                        // The cover is drawn here rather than as one of the
                        // states below.
                        //
                        // As a state it could not be made to dissolve: whichever
                        // container held it — Crossfade first, then
                        // AnimatedContent — dropped it from the composition in
                        // the same frame the panel arrived, so there was nothing
                        // left to fade. The panels go on swapping between
                        // themselves; the cover is simply the floor of this
                        // slot, and opening a panel takes its opacity away.
                        // No square cover on a record that has a picture of its
                        // own. The extended artwork behind this slot is the
                        // cover, at the size the record was given one for, and
                        // putting the sleeve back on top of it is the same image
                        // twice — which is what the reference stopped doing.
                        // Never on its side, where the cover is the stage's own
                        // to draw: see Stage.COVER.
                        val coverShowing = !sideways && panel == PlayerPanel.NONE &&
                            !(videoOn && videoPlayer != null && LocalGlassEnabled.current) &&
                            (canvas == null || !canvasReady)
                        // One at a time, from one clock.
                        //
                        // Faded together, the cover and the panel are both half
                        // visible through the middle of the change: lyrics
                        // printed over a record sleeve, which reads as a mistake
                        // rather than as a transition. Two animations with the
                        // same duration and opposite delays were still two
                        // clocks, and they overlapped by however far apart they
                        // started. This is a single number: the first half of it
                        // takes the cover away, the second half brings the panel
                        // in, and neither half can begin before the other ends.
                        // Slower when the cover is handing over to a clip.
                        //
                        // With a panel it is a change the listener asked for and
                        // wants over with. A Canvas arriving is not: the clip
                        // fades up behind the glass over four hundred
                        // milliseconds, and a cover that left in a hundred and
                        // eighty made the two look like separate events instead
                        // of one dissolve.
                        val toClip = panel == PlayerPanel.NONE
                        val phase by animateFloatAsState(
                            targetValue = if (coverShowing) 0f else 1f,
                            animationSpec = tween(
                                if (toClip) CLIP_FADE_MS else STAGE_FADE_MS * 2,
                            ),
                            label = "stageFade",
                        )
                        val coverAlpha = (1f - phase * 2f).coerceIn(0f, 1f)
                        val panelAlpha = ((phase - 0.5f) * 2f).coerceIn(0f, 1f)

                        Box(
                            Modifier
                                .weight(1f)
                                .pointerInput(panel) {
                                    if (panel != PlayerPanel.LYRICS) return@pointerInput
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                            if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
                                                pressOnLyrics = true
                                            }
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            // Says so in words, over whatever the slot is
                            // showing.
                            //
                            // The lit microphone says it to whoever is looking
                            // at the control; this says it to whoever comes
                            // back to a song that sounds wrong and does not
                            // remember why. It rides above the cover rather
                            // than beside the title, where the transport
                            // already has all the room it needs.
                            val karaokeAmount by dev.lelonio.square.playback.AudioEffects.karaoke
                                .collectAsStateWithLifecycle()
                            // Not over the lyrics, where the karaoke's own
                            // control is on screen and the source's credit
                            // takes this same line: the two were drawn on top
                            // of each other (#79).
                            androidx.compose.animation.AnimatedVisibility(
                                visible = karaokeAmount > 0f && !sdkPlayback && panel != PlayerPanel.LYRICS,
                                enter = fadeIn(tween(220)),
                                exit = fadeOut(tween(180)),
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .then(androidx.compose.ui.Modifier)

                            ) {
                                KaraokeBadge(karaokeAmount, upperBackdrop) {
                                    panel = PlayerPanel.LYRICS
                                    karaokeExpand++
                                }
                            }

                            // No sleeve on this screen any more.
                            //
                            // What used to sit here was the square cover, drawn
                            // over a blurred copy of itself — the same image
                            // twice, the small one hiding the middle of the
                            // large one. The picture behind this slot *is* the
                            // cover now: the record's own extended artwork
                            // where the catalogue has one, and the sleeve itself
                            // laid across the top where it does not, both of
                            // them ending in the colour the page is made of.

                        AnimatedContent(
                            targetState = when (panel) {
                                PlayerPanel.LYRICS -> Stage.LYRICS
                                PlayerPanel.EFFECTS -> Stage.EFFECTS
                                PlayerPanel.INFO -> Stage.INFO
                                PlayerPanel.QUEUE -> Stage.QUEUE
                                PlayerPanel.DEVICES -> Stage.DEVICES
                                PlayerPanel.ADD_TO_PLAYLIST -> Stage.ADD_TO_PLAYLIST
                                // On its side the cover is on the card to the
                                // left, so the right half opens on the words, or
                                // on what is coming up for a song without any.
                                PlayerPanel.NONE -> if (sideways) {
                                    if (lyrics != null || lyricsLoading) Stage.LYRICS else Stage.QUEUE
                                } else when {
                                    // Not while the player is travelling: a
                                    // TextureView inside a layer being scaled
                                    // and faded loses its surface for the length
                                    // of the animation and leaves a black
                                    // rectangle sliding down the screen. The
                                    // cover stands in for those few hundred
                                    // milliseconds — the same trick the Canvas
                                    // above uses, and for the same reason.
                                    videoOn && videoPlayer != null &&
                                        LocalGlassEnabled.current -> Stage.VIDEO
                                    canvas == null || !canvasReady -> Stage.COVER
                                    else -> Stage.CANVAS
                                }
                            },
                            // Spelled out rather than left to Crossfade.
                            //
                            // Crossfade dropped the cover the instant a panel
                            // opened — the log had it leaving the composition in
                            // the same millisecond the lyrics arrived, while the
                            // panels dissolved into each other perfectly. Said
                            // this way, both halves of the change are named and
                            // both are given the same time.
                            // Between one panel and another, where no cover
                            // is involved, this is the whole animation. Going to
                            // or from the cover it is held at nothing until its
                            // turn; see panelAlpha.
                            transitionSpec = {
                                fadeIn(tween(STAGE_FADE_MS)) togetherWith
                                    fadeOut(tween(STAGE_FADE_MS)) using null
                            },
                            label = "stage",
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = panelAlpha },
                        ) { stage ->
                            when (stage) {
                                // Empty: the cover above this AnimatedContent
                                // is what fills the slot when no panel is open.
                                // Empty: the cover above this AnimatedContent
                                // is what fills the slot when no panel is open.
                                Stage.COVER -> Box(Modifier.fillMaxSize())

                                Stage.VIDEO -> Column(
                                    Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    // Nullable even in this stage: the crossfade
                                    // keeps drawing the one it is leaving, and
                                    // the controller it holds is gone the moment
                                    // the activity stops.
                                    // Gives up height to the button under it rather
                                    // than taking the whole stage and squashing it.
                                    Box(
                                        Modifier.weight(1f, fill = false),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        videoPlayer?.let {
                                            VideoStage(
                                                it,
                                                videoAttachKey,
                                                protectedContent = videoMode,
                                            ) { ambient = it }
                                        }
                                    }
                                    // Under the picture, at its edge, the way into
                                    // the whole screen without turning the phone.
                                    val context = androidx.compose.ui.platform.LocalContext.current
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(top = 10.dp, start = 4.dp, end = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            videoNotice.orEmpty(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White.copy(alpha = 0.8f),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Box(
                                            Modifier
                                                .requiredSize(36.dp)
                                                .clip(CircleShape)
                                                .background(Color.Black.copy(alpha = 0.35f))
                                                .pressable(onClick = {
                                                    dev.lelonio.square.ui.player.lockVideoOrientation(context, landscape = true)
                                                }),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                com.adamglin.PhosphorIcons.Regular.DeviceRotate,
                                                contentDescription = stringResource(R.string.rotate_video),
                                                tint = Color.White,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }

                                Stage.LYRICS -> LyricsStage(
                                    lyrics = lyrics,
                                    loading = lyricsLoading,
                                    query = lyricsQuery,
                                    onChosen = onLyricsChosen,
                                    positionMs = positionMs,
                                    isPlaying = state.isPlaying,
                                    onSeek = onSeek,
                                    backdrop = panelBackdrop,
                                    expandSignal = karaokeExpand,
                                    alone = lyricsAlone,
                                )

                                // Effects and the queue share the lyrics' space
                                // rather than opening a sheet under the
                                // controls. A panel that pushed the transport
                                // around every time it opened was the reason
                                // this screen never sat still.
                                Stage.INFO -> CreditsView(
                                    credits = credits,
                                    loading = creditsLoading,
                                    modifier = Modifier.fillMaxSize(),
                                )

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
                                        backdrop = panelBackdrop,
                                    )
                                }

                                Stage.QUEUE -> Box(Modifier.fillMaxSize()) {
                                    QueueList(queue, queueActions)
                                }

                                Stage.DEVICES -> Box(
                                    Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    DeviceList(
                                        state = devices,
                                        onSelect = onSelectDevice,
                                        onRefresh = onRefreshDevices,
                                    )
                                }

                                Stage.ADD_TO_PLAYLIST -> Box(
                                    Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    PlaylistPicker(
                                        state = addToPlaylist,
                                        onSelect = onPickPlaylist,
                                    )
                                }

                                // The clip is the picture, and it is drawn
                                // behind everything: this slot only has to keep
                                // the space.
                                // With a clip playing there is no cover to
                                // swipe, and the track-change gesture went with
                                // it. The slot keeps the same drag handler over
                                // the clip — nothing moves, because the clip is
                                // drawn behind this and is not ours to shift,
                                // but the swipe still changes track.
                                Stage.CANVAS -> CoverGestures(
                                    onNext = onNext,
                                    onPrevious = onPrevious,
                                    canGoNext = state.hasNext,
                                    canGoPrevious = state.hasPrevious,
                                    followFinger = false,
                                    onDrag = { canvasShift.floatValue = it },
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    Box(Modifier.fillMaxSize())
                                }
                            }
                        }
                        }

                    }
                    val controlsBlock: @Composable ColumnScope.() -> Unit = {
                        // With a panel open the room below the stage goes to
                        // the panel: lyrics, queue and effects ran out a video
                        // row and a gap short of the title, over empty glass.
                        // What the stage gains the row gives up, so the title
                        // and the controls stay where they are.
                        val panelOpen = panel != PlayerPanel.NONE
                        val gapBelowStage by androidx.compose.animation.core.animateDpAsState(
                            if (panelOpen) 12.dp else 20.dp,
                            tween(STAGE_FADE_MS * 2),
                            label = "gapBelowStage",
                        )
                        val videoRowHeight by androidx.compose.animation.core.animateDpAsState(
                            // On its side the room is the controls': the
                            // video is one tap away at the top right.
                            if (panelOpen || sideways) 0.dp else VIDEO_ROW_HEIGHT,
                            tween(STAGE_FADE_MS * 2),
                            label = "videoRowHeight",
                        )
                        Spacer(Modifier.height(gapBelowStage))

                        // Only for the few tracks that have a video, and above
                        // the title because that is where the official client
                        // puts it: it is a choice about this song, not about
                        // the app.
                        // Faded and grown in, not dropped in.
                        //
                        // Whether a track has a video is answered a moment
                        // after it starts, so this button arrives while the
                        // listener is already looking at the screen. Appearing
                        // in one frame reads as a glitch; arriving reads as an
                        // answer.
                        // Its place is kept whether the song has a video or
                        // not. The button used to push everything below it
                        // down as it arrived, which moved the controls, and
                        // with them the line the picture above ends on: two
                        // songs in a row, one with a video and one without,
                        // ended their covers at two different heights. Now the
                        // row is there either way and only the button inside
                        // it comes and goes.
                        Box(
                            Modifier
                                .align(Alignment.CenterHorizontally)
                                .height(videoRowHeight),
                            contentAlignment = Alignment.Center,
                        ) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = videoFileId != null && !panelOpen && !sideways,
                            enter = fadeIn(tween(260)) + scaleIn(tween(260), initialScale = 0.9f),
                            exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.92f),
                        ) {
                            GlassSurface(
                                backdrop = upperBackdrop,
                                surfaceColor = LocalPlayerFilm.current,
                                shape = RoundedCornerShape(50),
                                // The gap to the title lives here rather than
                                // in a spacer beside it: what a visibility
                                // block holds is stacked in a box, so a spacer
                                // next to the button sat *on* it and the button
                                // ended up against the title.
                                modifier = Modifier.pressable(onClick = onToggleVideo),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // The icon says what the button leads to,
                                    // like the words beside it: a screen while
                                    // listening, a note while watching.
                                    Icon(
                                        if (videoMode) PhosphorIcons.Regular.MusicNotes
                                        else PhosphorIcons.Regular.MonitorPlay,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .padding(end = 8.dp)
                                            .size(18.dp),
                                    )
                                    Text(
                                        stringResource(
                                            if (videoMode) R.string.switch_to_audio
                                            else R.string.switch_to_video,
                                        ),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }
                        }
                        }

                        // On its side there is room above the title, and the
                        // line being sung is the best thing to put in it.
                        if (sideways && panel == PlayerPanel.NONE && lyrics?.synced == true) {
                            SungLine(
                                lyrics = lyrics,
                                positionMs = positionMs,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                            )
                        }

                        // Title and artist on their own capsule, with the two
                        // per-track actions on the right.
                        GlassSurface(
                            backdrop = lowerBackdrop,
                            surfaceColor = LocalPlayerFilm.current,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .retreat(chrome)
                                .fillMaxWidth()
                                // Measured at rest only. Folding away moves
                                // the pill, and the transport, which reads
                                // this to choose its backdrop, changed colour
                                // as the lyrics opened out.
                                .onGloballyPositioned { if (chrome == 1f) lowerTop = it.boundsInWindow().top }
                                .sharedPill(sharedScope, animatedScope),
                        ) {
                            Row(
                                Modifier.padding(start = 22.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TitleBlock(
                                    state,
                                    Modifier.weight(1f),
                                    onOpenArtist = { uri, name ->
                                        onCollapse()
                                        onOpenUri(uri, name)
                                    },
                                    onOpenAlbum = onOpenAlbum,
                                )
                                // A station built from this track, the way the
                                // official client's own radio button does it.
                                if (onRadio != null) {
                                    RoundGlassButton(
                                        backdrop = lowerBackdrop,
                                        size = 40.dp,
                                        onClick = onRadio,
                                    ) {
                                        Icon(
                                            PhosphorIcons.Regular.Broadcast,
                                            contentDescription = stringResource(R.string.radio),
                                            tint = panelTint(false),
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                                if (playlistEditAvailable) {
                                Spacer(Modifier.size(8.dp))
                                RoundGlassButton(
                                    backdrop = lowerBackdrop,
                                    size = 40.dp,
                                    onClick = {
                                        // A tick opens the lists, where it can
                                        // be taken back; a heart, filled or
                                        // not, is the like itself.
                                        if (onToggleLike != null && (inLikedSongs || !alreadySaved)) {
                                            onToggleLike()
                                        } else {
                                            panel = if (panel == PlayerPanel.ADD_TO_PLAYLIST) {
                                                PlayerPanel.NONE
                                            } else {
                                                onAddToPlaylist()
                                                PlayerPanel.ADD_TO_PLAYLIST
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        panel = if (panel == PlayerPanel.ADD_TO_PLAYLIST) {
                                            PlayerPanel.NONE
                                        } else {
                                            onAddToPlaylist()
                                            PlayerPanel.ADD_TO_PLAYLIST
                                        }
                                    },
                                ) {
                                    // A heart where a tap likes the song, a plus
                                    // where the tap opens the lists instead: the
                                    // picture says what pressing it does. And a
                                    // tick where the song is in one of the
                                    // listener's playlists and not liked: the
                                    // heart had taken its place, which left no
                                    // way to see that, or to take the song back
                                    // out of the list it was playing from.
                                    val hearts = onToggleLike != null
                                    Icon(
                                        when {
                                            hearts && inLikedSongs -> PhosphorIcons.Fill.Heart
                                            alreadySaved -> PhosphorIcons.Regular.Check
                                            !hearts -> PhosphorIcons.Regular.Plus
                                            else -> PhosphorIcons.Regular.Heart
                                        },
                                        contentDescription = stringResource(
                                            when {
                                                hearts && inLikedSongs -> R.string.remove_from_liked
                                                alreadySaved -> R.string.in_a_playlist
                                                !hearts -> R.string.add_to_playlist
                                                else -> R.string.liked_songs
                                            },
                                        ),
                                        tint = when {
                                            panel == PlayerPanel.ADD_TO_PLAYLIST ->
                                                panelTint(true)
                                            hearts && inLikedSongs -> SavedInk
                                            alreadySaved -> SavedInk
                                            else -> panelTint(false)
                                        },
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                }
                            }
                        }

                        Column(Modifier.retreat(chrome)) {
                            // Tighter on its side, where the column is short.
                            Spacer(Modifier.height(if (sideways) 10.dp else 18.dp))

                            GlassProgressBar(
                                positionMs = positionMs,
                                durationMs = state.durationMs,
                                onSeek = onSeek,
                                accentColor = GlassInk,
                                trackColor = GlassInk.copy(alpha = 0.28f),
                            )
                            TimeRow(positionMs, state.durationMs)

                            Spacer(Modifier.height(if (sideways) 6.dp else 14.dp))
                        }

                        Controls(
                            state = state,
                            backdrop = lowerBackdrop,
                            onTogglePlay = onTogglePlay,
                            onNext = onNext,
                            onPrevious = onPrevious,
                            onToggleShuffle = onToggleShuffle,
                            onCycleRepeat = onCycleRepeat,
                            toggles = chrome,
                        )

                        // Under the transport upright; under the sleeve on
                        // its side, where there is no height left for it here.
                        if (!sideways) {
                            Spacer(Modifier.height(14.dp))
                            tabsBlock()
                        }

                        Spacer(Modifier.height(if (sideways) 12.dp else 20.dp))
                    }
                    if (sideways) {
                        Row(Modifier.fillMaxSize()) {
                            Column(
                                Modifier
                                    .weight(0.85f)
                                    .fillMaxHeight(),
                            ) {
                                topBar(Modifier)
                                SongCard(
                                    state = state,
                                    positionMs = positionMs,
                                    onSeek = onSeek,
                                    onTogglePlay = onTogglePlay,
                                    onNext = onNext,
                                    onPrevious = onPrevious,
                                    onCover = { panel = PlayerPanel.LYRICS },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                )
                            }
                            Spacer(Modifier.width(24.dp))
                            Column(
                                Modifier
                                    .weight(1.4f)
                                    .fillMaxHeight(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                stageBlock()
                                Spacer(Modifier.height(8.dp))
                                tabsBlock()
                                Spacer(Modifier.height(12.dp))
                            }
                        }
                    } else {
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            stageBlock()
                            controlsBlock()
                        }
                    }
                }
            }
        }

    }
}

/**
 * The video, on a surface the player draws straight into.
 *
 * A `SurfaceView` rather than a `TextureView`: the player writes to it without
 * the frame ever passing through the view hierarchy, which is what keeps a
 * 720p stream from costing anything on the UI thread. The surface is handed
 * back on the way out, or the player would go on rendering into a dead one.
 */
@Composable
private fun VideoStage(
    player: Player,
    attachKey: Any?,
    /**
     * True for content the device decrypts in a protected buffer.
     *
     * Which decides the surface, and takes the ambient glow away with it: a
     * texture is the only surface whose pixels can be read back, and pixels
     * that can be read back are exactly what protected playback does not
     * allow. Drawn into one, a Widevine video shows nothing at all — the
     * decoder refuses the surface rather than the licence. So this gets the
     * plain surface the floating window uses, and the glow falls back to the
     * cover's colours, which is the honest best available.
     */
    protectedContent: Boolean,
    onAmbient: (AmbientEdges) -> Unit,
) {
    // The video's own shape, fitted inside the stage. A fixed 16:9 box drew
    // every other shape stretched to it: a wide film pulled tall, a square
    // clip pulled wide.
    val ratio = rememberVideoRatio(player, attachKey)

    if (protectedContent) {
        VideoSurface(
            player,
            attachKey,
            Modifier
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(18.dp)),
        )
        PanelAmbient(player, onAmbient)
        return
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    // A TextureView rather than the SurfaceView the floating window uses: only
    // a texture can be read back, and reading the picture back is the whole
    // ambient effect — the glow is the video's own colour, not a guess made
    // from the cover.
    val texture = remember(context) { android.view.TextureView(context) }
    // `attachKey` as well as the player: the session can change what it is
    // playing on without changing the handle this screen holds, and the
    // picture goes to whoever the surface was last given to.
    DisposableEffect(player, texture, attachKey) {
        player.setVideoTextureView(texture)
        onDispose { player.clearVideoTextureView(texture) }
    }

    val report by rememberUpdatedState(onAmbient)
    LaunchedEffect(texture, player) {
        while (true) {
            // Slowly, off a thumbnail. Reading the whole picture back at frame
            // rate would cost far more than it shows, and a glow that tracked
            // every cut would strobe: this is meant to be light in the room, and
            // light in a room does not flicker.
            kotlinx.coroutines.delay(AMBIENT_INTERVAL_MS)
            if (!texture.isAvailable) continue
            // Off the main thread, copy and sums alike; see copyFrame.
            val frame = texture.copyFrame(AMBIENT_SAMPLE, AMBIENT_SAMPLE) ?: continue
            val edges = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                frame.edges().also { frame.recycle() }
            }
            report(edges)
        }
    }

    androidx.compose.ui.viewinterop.AndroidView(
        factory = { texture },
        modifier = Modifier
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(18.dp)),
    )
}

/**
 * The sampled colour, over the whole screen.
 *
 * Each corner eased into its new value over seconds rather than frames: the
 * point is the colour of the scene, and a scene lasts. Drawn under everything,
 * so the glass above it refracts the light the same way it refracts artwork.
 */
@Composable
private fun AmbientLight(edges: AmbientEdges?) {
    val topLeft by animateColorAsState(
        edges?.topLeft ?: Color.Transparent, tween(AMBIENT_FADE_MS), label = "tl",
    )
    val topRight by animateColorAsState(
        edges?.topRight ?: Color.Transparent, tween(AMBIENT_FADE_MS), label = "tr",
    )
    val bottomLeft by animateColorAsState(
        edges?.bottomLeft ?: Color.Transparent, tween(AMBIENT_FADE_MS), label = "bl",
    )
    val bottomRight by animateColorAsState(
        edges?.bottomRight ?: Color.Transparent, tween(AMBIENT_FADE_MS), label = "br",
    )

    Canvas(Modifier.fillMaxSize()) {
        // One soft glow per corner rather than two bands across the screen.
        //
        // Bands meet along a line, and a line is exactly what light does not
        // have: the first version left a visible seam across the middle of the
        // screen. Radial gradients reaching past each other have no edge to
        // show, which is also what the lamps behind an ambient television
        // actually do.
        val radius = size.maxDimension * 0.9f
        listOf(
            topLeft to androidx.compose.ui.geometry.Offset(0f, 0f),
            topRight to androidx.compose.ui.geometry.Offset(size.width, 0f),
            bottomLeft to androidx.compose.ui.geometry.Offset(0f, size.height),
            bottomRight to androidx.compose.ui.geometry.Offset(size.width, size.height),
        ).forEach { (color, center) ->
            drawRect(
                Brush.radialGradient(
                    listOf(color, color.copy(alpha = 0f)),
                    center = center,
                    radius = radius,
                ),
            )
        }
    }
}

/** The colour of each corner of a frame, which is what the glow is made of. */
private data class AmbientEdges(
    val topLeft: Color,
    val topRight: Color,
    val bottomLeft: Color,
    val bottomRight: Color,
)

/**
 * Averages each quadrant of the thumbnail.
 *
 * Quadrants rather than single pixels: one pixel of a dark scene with a bright
 * speck in it would swing the whole glow, and the average is what a wall
 * actually reflects.
 */
private fun android.graphics.Bitmap.edges(): AmbientEdges {
    val half = AMBIENT_SAMPLE / 2
    fun quadrant(x0: Int, y0: Int): Color {
        var r = 0L
        var g = 0L
        var b = 0L
        for (x in x0 until x0 + half) {
            for (y in y0 until y0 + half) {
                val pixel = getPixel(x, y)
                r += android.graphics.Color.red(pixel)
                g += android.graphics.Color.green(pixel)
                b += android.graphics.Color.blue(pixel)
            }
        }
        val count = (half * half).toFloat()
        return Color(r / count / 255f, g / count / 255f, b / count / 255f)
    }
    return AmbientEdges(
        topLeft = quadrant(0, 0),
        topRight = quadrant(half, 0),
        bottomLeft = quadrant(0, half),
        bottomRight = quadrant(half, half),
    )
}

/** How often the picture is read back, in milliseconds. */
private const val AMBIENT_INTERVAL_MS = 900L

/** How long a colour takes to become the next one. */
private const val AMBIENT_FADE_MS = 2500

/** Side of the thumbnail each sample is taken from. */
private const val AMBIENT_SAMPLE = 16

/**
 * The glow for a video whose own pixels cannot be read.
 *
 * Sampled from the thumbnail sheets the scrubber uses — ordinary JPEGs, one
 * frame a second — because protected playback decrypts into a buffer nothing
 * can read back. Same effect, same pictures, arrived at from the outside.
 *
 * One small fetch every couple of seconds, and the sheet holds thirty-six
 * frames, so most of those are already in the cache the loader keeps.
 */
@Composable
private fun PanelAmbient(player: Player, onAmbient: (AmbientEdges) -> Unit) {
    val manifest by dev.lelonio.square.backend.spotify.SpotifyVideoMode.manifest
        .collectAsStateWithLifecycle()
    val report by rememberUpdatedState(onAmbient)
    val http = remember { okhttp3.OkHttpClient() }

    LaunchedEffect(manifest) {
        val current = manifest ?: return@LaunchedEffect
        var lastUrl: String? = null
        var sheet: android.graphics.Bitmap? = null
        while (true) {
            val at = withContext(kotlinx.coroutines.Dispatchers.Main) { player.currentPosition }
            val panel = current.panelAt(at)
            if (panel == null) {
                kotlinx.coroutines.delay(AMBIENT_INTERVAL_MS)
                continue
            }
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    if (panel.url != lastUrl) {
                        val request = okhttp3.Request.Builder().url(panel.url).build()
                        val bytes = http.newCall(request).execute().use { it.body?.bytes() }
                        sheet?.recycle()
                        sheet = bytes?.let {
                            android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)
                        }
                        lastUrl = panel.url
                    }
                    val whole = sheet ?: return@runCatching
                    // The one frame out of the sheet, small: the glow is four
                    // averaged edges, and averaging is cheaper on few pixels.
                    val frame = android.graphics.Bitmap.createBitmap(
                        whole,
                        panel.left.coerceIn(0, (whole.width - 1).coerceAtLeast(0)),
                        panel.top.coerceIn(0, (whole.height - 1).coerceAtLeast(0)),
                        panel.width.coerceAtMost(whole.width - panel.left),
                        panel.height.coerceAtMost(whole.height - panel.top),
                    )
                    val small = frame.scale(AMBIENT_SAMPLE, AMBIENT_SAMPLE)
                    frame.recycle()
                    val edges = small.edges()
                    small.recycle()
                    withContext(kotlinx.coroutines.Dispatchers.Main) { report(edges) }
                }
            }
            kotlinx.coroutines.delay(AMBIENT_INTERVAL_MS)
        }
    }
}

/**
 * The video's own shape, width over height, as the player reports it.
 *
 * Read off the player on the way in as well as listened for. The size is
 * announced once, when the picture starts, so a screen opened on a video that
 * was already playing (turning the phone on its side, which is the usual way
 * in) never heard it and kept its guess. 16:9 until anything is known, which
 * is what most of them are.
 *
 * Give it to `aspectRatio` with no fill in front: inside a box that leaves the
 * size open, that fits the picture to whichever side runs out first. Asked to
 * fill the width as well, a picture narrower than the screen could not be both,
 * and the surface stretched it over the whole display instead.
 */
@Composable
internal fun rememberVideoRatio(player: Player, attachKey: Any? = null): Float {
    var ratio by remember(player, attachKey) {
        mutableFloatStateOf(player.videoSize.ratio() ?: DEFAULT_VIDEO_RATIO)
    }
    DisposableEffect(player, attachKey) {
        player.videoSize.ratio()?.let { ratio = it }
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoSize.ratio()?.let { ratio = it }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return ratio
}

private fun VideoSize.ratio(): Float? =
    if (width > 0 && height > 0) width * pixelWidthHeightRatio / height else null

private const val DEFAULT_VIDEO_RATIO = 16f / 9f

/** The surface itself, shared with the floating window; see [VideoStage]. */
@Composable
fun VideoSurface(player: Player, attachKey: Any? = null, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val surface = remember(context) { android.view.SurfaceView(context) }
    DisposableEffect(player, surface, attachKey) {
        player.setVideoSurfaceView(surface)
        onDispose { player.clearVideoSurfaceView(surface) }
    }
    androidx.compose.ui.viewinterop.AndroidView(factory = { surface }, modifier = modifier)
}

@Composable
private fun TopBar(
    backdrop: Backdrop,
    panel: PlayerPanel,
    /** Where the queue came from: "Estate 2025", "Ricerca". */
    source: String,
    /** Opens that place, when it is one; null leaves the line as a caption. */
    onOpenSource: (() -> Unit)?,
    onCollapse: () -> Unit,
    onOpenDevices: () -> Unit,
    connectAvailable: Boolean,
    onAnotherDevice: Boolean,
    /** Null when the track has no video to watch. */
    onWatchVideo: (() -> Unit)?,
    videoOn: Boolean,
    modifier: Modifier = Modifier,
    /** The DJ's queue: the source is marked as the DJ's; see DjMark. */
    dj: Boolean = false,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassButton(backdrop, onClick = onCollapse) {
            Icon(PhosphorIcons.Regular.CaretDown, contentDescription = stringResource(R.string.close))
        }
        // Names whatever the middle of the screen is currently showing, so the
        // switch below has a label without carrying one.
        Crossfade(
            targetState = when (panel) {
                PlayerPanel.LYRICS -> stringResource(R.string.lyrics)
                PlayerPanel.EFFECTS -> stringResource(R.string.effects)
                PlayerPanel.INFO -> stringResource(R.string.credits)
                PlayerPanel.QUEUE -> stringResource(R.string.queued)
                PlayerPanel.DEVICES -> stringResource(R.string.play_on)
                PlayerPanel.ADD_TO_PLAYLIST -> stringResource(R.string.add_to_playlist)
                // The source in place of the words "in riproduzione", which
                // said nothing the screen was not already saying. What is worth
                // knowing here is where the track came from — the playlist you
                // opened, the album, the search — and this is the one line of
                // the player not already spoken for.
                PlayerPanel.NONE -> source.ifBlank { stringResource(R.string.now_playing) }
            },
            animationSpec = tween(220),
            label = "topBarTitle",
            modifier = Modifier.weight(1f),
        ) { title ->
            // Only the source line is a way back, and only while it is the one
            // being shown: the panel names above it are labels for what is on
            // screen already.
            val open = onOpenSource.takeIf { panel == PlayerPanel.NONE && source.isNotBlank() }
            // Light, with a shadow, whichever way the phone is set.
            //
            // This line is the only text on the player with nothing behind it
            // but the picture — the title and the transport sit on the field
            // below the fade, and the buttons beside it have their own glass.
            // Following the theme put black letters straight onto a Canvas in
            // the light setting, and pausing made it worse, since the veil that
            // dims a stopped clip was then darkening the very thing those
            // letters needed to be lighter than. A photograph has no side to
            // be on: what works over one is light type with a shadow under it.
            if (dj && panel == PlayerPanel.NONE) {
                // The DJ's queue: the mark alone says where the songs come
                // from, and who is talking between them.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    DjMark(color = Color.White, fontSize = 13.sp)
                }
            } else Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.55f),
                        offset = Offset(0f, 1f),
                        blurRadius = 14f,
                    ),
                ),
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (open != null) Modifier.plainClickable(open) else Modifier,
                    ),
            )
        }
        // Connect gets the permanent slot rather than hiding behind "more":
        // moving playback to another speaker is the thing you reach for while
        // the player is open, and the queue already has its own tab below.
        // Every YouTube Music track is a video underneath, and switching to it
        // is the thing this app cannot do itself — so it hands the track over
        // at the position it had reached rather than pretending otherwise.
        if (onWatchVideo != null) {
            GlassButton(backdrop, onClick = onWatchVideo) {
                Icon(
                    PhosphorIcons.Regular.YoutubeLogo,
                    contentDescription = stringResource(R.string.watch_video),
                    // Lit while the video is on, like every other button here
                    // that opens something: it is a switch, not a one-way trip.
                    tint = panelTint(videoOn),
                )
            }
        }
        if (connectAvailable) {
            GlassButton(backdrop, onClick = onOpenDevices) {
                Icon(
                    PhosphorIcons.Regular.Devices,
                    contentDescription = stringResource(R.string.devices),
                    // Lit for as long as the music is coming out of another
                    // device, not only while its list is open: on this screen it
                    // is the one thing that says the buttons are reaching
                    // somewhere else, and it has to still say it once the panel
                    // is closed.
                    tint = when {
                        onAnotherDevice -> ConnectedInk
                        else -> panelTint(panel == PlayerPanel.DEVICES)
                    },
                )
            }
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
private fun TitleBlock(
    state: PlaybackState,
    modifier: Modifier = Modifier,
    onOpenArtist: (uri: String, name: String) -> Unit = { _, _ -> },
    /**
     * Opens the record this track is on. Null where there is none to open.
     *
     * A plain callback rather than a URI: the queue does not always carry the
     * album's address — a list resolved before the app started keeping it has
     * only the name — and whoever hands this in is the one that can look it up.
     */
    onOpenAlbum: (() -> Unit)? = null,
) {
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
            // The title is the way to the record it is on — the same idea as
            // the artist's name below it being the way to them. Only where
            // there is somewhere to go: a queue built from search results, or a
            // track another device is playing, names a record this app has no
            // address for, and a title that looks like a link and does nothing
            // is worse than a caption.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.smartRecommended) {
                    Icon(
                        PhosphorIcons.Regular.Sparkle,
                        contentDescription = stringResource(R.string.smart_shuffle),
                        tint = GlassInk,
                        modifier = Modifier.padding(end = 8.dp).size(20.dp),
                    )
                }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (onOpenAlbum != null) {
                    Modifier.pressable(onOpenAlbum, pressedScale = 0.98f)
                } else {
                    Modifier
                },
            )
            }
            // The rating beside the names rather than on its own line: it is
            // about the song, and a line of its own would read as a warning.
            if (state.explicit) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    dev.lelonio.square.ui.components.ExplicitMark(color = GlassInk)
                    Spacer(Modifier.width(6.dp))
                    ArtistLine(state, artist, onOpenArtist)
                }
            } else {
                ArtistLine(state, artist, onOpenArtist)
            }
        }
    }
}

/**
 * The line under the title: who made this, and a way to each of them.
 *
 * "Pyrex, Sfera Ebbasta" is two artists and two pages, so the whole line
 * cannot lead to one of them. Where the finger landed is turned into a
 * character offset and matched against the span each name occupies, which is
 * what makes the second name reach the second artist.
 *
 * Plain text when there is nowhere to go: a queue built from search results, or
 * a track another device is playing, names an artist this app has no page for,
 * and a line that looks like a link and does nothing is worse than a caption.
 */
@Composable
private fun ArtistLine(
    state: PlaybackState,
    artist: String,
    onOpenArtist: (uri: String, name: String) -> Unit,
) {
    val credits = state.artists
    // The names as written, so the spans below line up with what is drawn.
    val label = if (credits.isEmpty()) artist else credits.joinToString(", ") { it.name }

    var layout by remember(label) { mutableStateOf<TextLayoutResult?>(null) }
    val openable = credits.any { it.uri != null } || state.artistUri != null

    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = GlassInkDim,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout = it },
        modifier = if (!openable) {
            Modifier
        } else {
            Modifier.pointerInput(credits, label) {
                detectTapGestures { position ->
                    val offset = layout?.getOffsetForPosition(position)
                    val hit = offset?.let { at ->
                        var start = 0
                        credits.firstOrNull { credit ->
                            val end = start + credit.name.length
                            val contains = at in start..end
                            start = end + 2
                            contains
                        }
                    }
                    // Anywhere else on the line, and any track that named only
                    // one artist, goes to the first.
                    val uri = hit?.uri ?: state.artistUri ?: return@detectTapGestures
                    onOpenArtist(uri, hit?.name ?: artist)
                }
            }
        },
    )
}

/**
 * A tap with no ripple behind it.
 *
 * These two are lines of text rather than controls, and the ripple draws a
 * rectangle around a word: the highlight ends up wider and squarer than the
 * thing it is meant to be acknowledging.
 */
@Composable
private fun Modifier.plainClickable(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/** Isolated so the ticking position recomposes only these two labels. */
@Composable
internal fun TimeRow(positionMs: State<Long>, durationMs: Long) {
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
internal fun Controls(
    state: PlaybackState,
    backdrop: Backdrop,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    /**
     * How much of shuffle and repeat is showing, 0 to 1: they step aside with
     * the rest while the lyrics are read alone, and the three discs stay put.
     */
    toggles: Float = 1f,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        // Their place is kept, so the discs do not slide; a press on the empty
        // place does nothing but bring them back.
        val togglesOn = toggles > 0.5f
        Box(Modifier.graphicsLayer { alpha = toggles }) {
            ToggleIcon(
                icon = PhosphorIcons.Regular.Shuffle,
                description = stringResource(if (state.smartShuffle) R.string.smart_shuffle else R.string.shuffle_play),
                active = state.shuffleEnabled,
                smart = state.smartShuffle,
                onClick = { if (togglesOn) onToggleShuffle() },
            )
        }

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
                contentDescription = stringResource(R.string.previous),
                modifier = Modifier.size(30.dp),
            )
        }

        RoundGlassButton(backdrop = backdrop, size = 76.dp, onClick = onTogglePlay) {
            // A ring around the icon while the track is still being fetched.
            //
            // Worth its place here and nowhere else: a YouTube track has to have
            // its stream resolved before a single byte can be read, which takes
            // long enough that a tap on play looks like it did nothing at all.
            // Around the button rather than in place of it, so the control stays
            // where it is and stays pressable.
            // A Box, because the button lays its content out in a row and the
            // ring belongs *around* the icon rather than beside it.
            Box(contentAlignment = Alignment.Center) {
                if (state.isBuffering) {
                    CircularProgressIndicator(
                        color = LocalContentColor.current.copy(alpha = 0.5f),
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(52.dp),
                    )
                }
                Crossfade(
                    state.wantsPlay,
                    animationSpec = tween(180),
                    label = "playPause",
                ) { playing ->
                    Icon(
                        imageVector = if (playing) PhosphorIcons.Fill.Pause else PhosphorIcons.Fill.Play,
                        contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
                        modifier = Modifier.size(34.dp),
                    )
                }
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
                contentDescription = stringResource(R.string.next),
                modifier = Modifier.size(30.dp),
            )
        }

        Box(Modifier.graphicsLayer { alpha = toggles }) {
            ToggleIcon(
                icon = if (state.repeatMode == Player.REPEAT_MODE_ONE) {
                    PhosphorIcons.Regular.RepeatOnce
                } else {
                    PhosphorIcons.Regular.Repeat
                },
                description = stringResource(R.string.repeat),
                active = state.repeatMode != Player.REPEAT_MODE_OFF,
                onClick = { if (togglesOn) onCycleRepeat() },
            )
        }
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
/**
 * A word for the setting that is quietly changing the record.
 *
 * Small, unclickable and out of the way: it is a note to self, not a control.
 * The control is the microphone at the other end of the panel.
 */
@Composable
private fun KaraokeBadge(amount: Float, backdrop: Backdrop, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(top = 4.dp)
            .clip(dev.lelonio.square.ui.glass.shapes.ContinuousCapsule())
            // A way to the thing it is talking about: the lyrics, with the
            // control open. A label about a setting that cannot be reached from
            // where it is read is half a message.
            .pressable(onClick, pressedScale = 0.94f)
            // The app's material, not a grey plate: it sits among glass and a
            // painted rectangle is the one thing that reads as pasted on.
            .liquidGlass(
                config = LocalGlassEffectConfig.current,
                shape = dev.lelonio.square.ui.glass.shapes.ContinuousCapsule(),
                ownBackdrop = backdrop,
                highlightAlpha = BarHighlightAlpha,
            )
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            PhosphorIcons.Fill.MicrophoneStage,
            contentDescription = null,
            // White, like the control it refers to: an accent pulled from the
            // cover lands anywhere, including on the cover itself.
            tint = GlassInk,
            modifier = Modifier.size(13.dp),
        )
        Text(
            stringResource(R.string.karaoke_on, (amount * 100).toInt()),
            style = MaterialTheme.typography.labelMedium,
            color = GlassInk,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun RoundGlassButton(
    backdrop: Backdrop,
    size: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    LiquidButton(
        onClick = { if (enabled) onClick() },
        onLongClick = if (enabled) onLongClick else null,
        backdrop = backdrop,
        modifier = modifier
            .size(size)
            // Dimmed rather than removed when there is nowhere to go: a control
            // that disappears makes the whole row jump.
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        contentHeight = size,
        contentPadding = 0.dp,
        // Matched to the bottom bar and the mini player. At the upstream 2dp
        // these were the one place in the app where the glass barely frosted
        // what was behind it.
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

/** A control that opened the panel currently showing is coloured, not just lit. */
@Composable
private fun panelTint(active: Boolean) =
    if (active) MaterialTheme.colorScheme.primary else GlassInk

/** Spotify's own green, which already means "playing over there". */
private val ConnectedInk = androidx.compose.ui.graphics.Color(0xFF1ED760)

/** The same green for "this one is already in a playlist of yours". */
private val SavedInk = ConnectedInk

@Composable
private fun ToggleIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    active: Boolean,
    smart: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Box(Modifier.size(if (smart) 28.dp else 22.dp)) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (active) GlassInk else GlassInkDim,
            modifier = Modifier.size(22.dp).align(Alignment.BottomStart),
        )
        if (smart) Icon(
            PhosphorIcons.Regular.Sparkle,
            contentDescription = null,
            tint = if (active) GlassInk else GlassInkDim,
            modifier = Modifier.size(10.dp).align(Alignment.TopEnd),
        )
        }
    }
}

/**
 * The ink on every glass surface in the app.
 *
 * Fixed white was the reasoning here for a long time, and it held while the
 * only thing behind a control was album art under a *dark* wash. With the
 * system's light setting that wash goes the other way — the artwork is taken up
 * towards paper, and the film over it is white — so white ink on it is nothing
 * at all. It follows the app's own ink now, which is what decides that
 * direction; see SquareTheme.
 */
internal val GlassInk: Color
    @Composable get() = dev.lelonio.square.ui.theme.Ink
internal val GlassInkDim: Color
    @Composable get() = dev.lelonio.square.ui.theme.Ink.copy(alpha = 0.68f)

/**
 * The film every glass surface is tinted with.
 *
 * One value, shared, because the surfaces looked like different materials when
 * each picked its own: the tab bar came out noticeably paler than the mini
 * player and the search button beside it.
 */
internal val GlassFilm: Color
    @Composable get() = if (dev.lelonio.square.ui.theme.lightPage()) {
        // More of it on the light side: a twelfth of white over a pale page is
        // not a surface, and the controls have to sit on something.
        Color.White.copy(alpha = 0.45f)
    } else {
        Color.White.copy(alpha = 0.12f)
    }

/**
 * The dark film, for a record with no colour of its own yet.
 *
 * The same tint the glass recipe puts under light ink, at the same strength,
 * so the panes that draw their film themselves match the ones that get it from
 * [GlassEffect]. Everywhere else the film follows the phone's setting; the
 * player does not, see playerInk in SquareApp.
 */
internal val PlayerFilm = Color(0xFF23232A).copy(alpha = 0.5f)

/**
 * The film the player's glass is made of while a record plays.
 *
 * Provided once, over the whole player, so the panes that draw their own film
 * stay the material of the ones that get it from [GlassEffect]; SquareApp
 * hands the same colour to both.
 */
internal val LocalPlayerFilm = androidx.compose.runtime.compositionLocalOf { PlayerFilm }

/**
 * The record's colour at the depth of the dark film.
 *
 * Its hue, with the saturation held back so a loud sleeve gives tinted glass
 * rather than a coloured slab, and the darkness the light ink was chosen
 * against, so the writing reads on it as it did on the grey. A grey record
 * gives very nearly the film it always had.
 */
internal fun playerFilmTint(accent: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(accent.toArgb(), hsv)
    hsv[1] = hsv[1].coerceAtMost(FILM_SATURATION)
    hsv[2] = FILM_VALUE
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private const val FILM_SATURATION = 0.55f
private const val FILM_VALUE = 0.24f

/**
 * The lyrics, centre stage.
 *
 * Its own composable only so the empty and loading cases stay out of the layout
 * above: this sits where the cover would, so "no lyrics" has to occupy the same
 * space rather than collapsing the screen around it.
 */
@Composable
private fun LyricsStage(
    lyrics: dev.lelonio.square.data.Lyrics?,
    loading: Boolean,
    query: dev.lelonio.square.backend.lyrics.LyricsQuery?,
    onChosen: (dev.lelonio.square.data.Lyrics) -> Unit,
    positionMs: State<Long>,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    backdrop: Backdrop,
    /** Bumped when somebody arrives here asking for the karaoke control. */
    expandSignal: Int,
    /**
     * Read alone, with the rest of the player folded away: every line sharp,
     * so the ones around the sung line can be read as well. The blur is a
     * depth effect for a lyric glanced at between the controls.
     */
    alone: Boolean = false,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember(context) {
        (context.applicationContext as dev.lelonio.square.SquareApplication).preferences
    }
    val style by preferences.lyricsStyle.collectAsStateWithLifecycle()
    var optionsOpen by remember(query?.uri) { androidx.compose.runtime.mutableStateOf(false) }

    // Off when a song starts: turning it on is asking to read this one.
    var translated by androidx.compose.runtime.saveable.rememberSaveable(lyrics) {
        androidx.compose.runtime.mutableStateOf(false)
    }

    // The language the app itself is read in, which is the one to translate
    // into — not the phone's, when the two have been made to differ on purpose.
    val target = remember(context) {
        (context.applicationContext as dev.lelonio.square.SquareApplication)
            .language
            .language()
    }

    // What the translator answered for this song, once it has been asked.
    // Kept per song, so turning the toggle off and on again costs nothing.
    var machine by remember(lyrics) {
        androidx.compose.runtime.mutableStateOf<List<String>?>(null)
    }
    var translating by remember(lyrics) { androidx.compose.runtime.mutableStateOf(false) }

    // A document that ships its own translation is already answered; anything
    // else is put through the translator the first time it is asked for.
    val hasOwn = lyrics?.lines.orEmpty().any { !it.translation.isNullOrBlank() }

    androidx.compose.runtime.LaunchedEffect(lyrics, translated) {
        if (!translated || lyrics == null || hasOwn || machine != null) return@LaunchedEffect
        translating = true
        machine = runCatching {
            dev.lelonio.square.backend.lyrics.Translate.lines(
                lyrics.lines.map { it.text },
                target,
            )
        }.getOrNull()
        translating = false
    }

    val shown = remember(lyrics, machine) {
        val answers = machine
        if (lyrics == null || answers == null) {
            lyrics
        } else {
            lyrics.copy(
                lines = lyrics.lines.mapIndexed { index, line ->
                    line.copy(translation = answers.getOrNull(index)?.takeIf { it.isNotBlank() })
                },
            )
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            optionsOpen && query != null -> LyricsOptionsPanel(
                query = query,
                current = lyrics?.source,
                style = style,
                onStyle = preferences::setLyricsStyle,
                onChoose = onChosen,
                onDismiss = { optionsOpen = false },
                backdrop = backdrop,
            )

            loading -> androidx.compose.material3.CircularProgressIndicator(
                color = GlassInkDim,
                strokeWidth = 2.dp,
            )

            lyrics == null -> Text(
                stringResource(R.string.no_lyrics),
                style = MaterialTheme.typography.bodyMedium,
                color = GlassInkDim,
            )

            style == dev.lelonio.square.data.PreferencesStore.LyricsStyle.CLASSIC -> ClassicLyricsView(
                lyrics = shown ?: lyrics,
                positionMs = positionMs,
                showTranslation = translated,
                onSeek = onSeek,
                modifier = Modifier.fillMaxSize(),
            )

            else -> CompositionLocalProvider(
                LocalLyricVisualSettings provides LocalLyricVisualSettings.current.let {
                    if (alone) it.copy(inactiveBlur = 0f) else it
                },
            ) {
                LyricsView(
                    lyrics = shown ?: lyrics,
                    positionMs = positionMs,
                    isPlaying = isPlaying,
                    showTranslation = translated,
                    onSeek = onSeek,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Where the words came from, and the way to the other sources and
        // to the other style: low on the left, across from the panel's other
        // controls. Shown with no words as well, since another source may
        // have what the usual order stopped short of.
        // The credit a source asks for, on screen with its words: Spicy Lyrics
        // makes it a condition of using it. Across the top, clear of the
        // controls below.
        val credit = lyrics?.credit
        if (credit != null && !optionsOpen && !loading) {
            LyricsCreditLine(
                credit = credit,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }

        if (query != null && !loading && !optionsOpen) {
            LyricsSourceChip(
                source = lyrics?.source,
                backdrop = backdrop,
                onClick = { optionsOpen = true },
                // At the top of the column the translation and the karaoke
                // stand in, above them. With no words there is no
                // translation to sit above, so it takes that place instead.
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 6.dp, bottom = if (lyrics != null) 106.dp else 56.dp),
            )
        }

        // Beside the words rather than under them: a band across the panel took
        // the room the lyrics need, for a control touched once a song.
        val karaoke by dev.lelonio.square.playback.AudioEffects.karaoke
            .collectAsStateWithLifecycle()
        // Offered on every song with words. Where the document carries no
        // translation of its own the lines are put through a translator, so
        // there is always something behind the switch.
        if (lyrics != null && !optionsOpen) {
            TranslationToggle(
                on = translated,
                busy = translating,
                onChange = { translated = it },
                backdrop = backdrop,
                modifier = Modifier
                    // Above the karaoke dial, in the same column: the two are
                    // the panel's controls, and a second one loose on the other
                    // side would read as belonging to something else.
                    .align(Alignment.BottomEnd)
                    .padding(end = 6.dp, bottom = 56.dp),
            )
        }

        // Not over the official SDK, whose audio never passes through the effect.
        val sdkPlayback by dev.lelonio.square.playback.websdk.WebSdkRecovery.inUse
            .collectAsStateWithLifecycle()
        if (!sdkPlayback && !optionsOpen) KaraokeDial(
            amount = karaoke,
            onChange = dev.lelonio.square.playback.AudioEffects::setKaraoke,
            backdrop = backdrop,
            expandSignal = expandSignal,
            modifier = Modifier
                // Low on the right, just above the transport — where Apple puts
                // it, and out of the way of the line being sung, which sits in
                // the middle of the panel.
                .align(Alignment.BottomEnd)
                .padding(end = 6.dp, bottom = 6.dp),
        )
    }
}

/**
 * The line of [lyrics] being sung at [positionMs], changing with a dissolve;
 * nothing between lines, where nobody is singing. For the room above the
 * title on a phone held sideways.
 */
@Composable
private fun SungLine(
    lyrics: dev.lelonio.square.data.Lyrics,
    positionMs: State<Long>,
    modifier: Modifier = Modifier,
) {
    val line by remember(lyrics) {
        androidx.compose.runtime.derivedStateOf {
            val now = positionMs.value
            lyrics.lines.lastOrNull { (it.startTimeMs ?: Long.MAX_VALUE) <= now }
                ?.text?.trim().orEmpty()
        }
    }
    // Two lines' height whatever is being sung: a line that grew from one row
    // to two pushed the title, the progress and the transport down with it.
    AnimatedContent(
        targetState = line,
        transitionSpec = {
            (fadeIn(tween(320)) + androidx.compose.animation.slideInVertically(tween(320)) { it / 3 })
                .togetherWith(fadeOut(tween(220)))
        },
        label = "sungLine",
        contentAlignment = Alignment.BottomCenter,
        modifier = modifier.height(56.dp),
    ) { text ->
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = GlassInk,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The song while its lyrics fill the rest of a sideways screen: the sleeve
 * with the transport over its foot, the progress under it, then the title and
 * the artist. A tap on the sleeve goes back to it.
 */
@Composable
private fun SongCard(
    state: PlaybackState,
    positionMs: State<Long>,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onCover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .weight(1f, fill = false)
                .aspectRatio(1f)
                .softShadow(RoundedCornerShape(18.dp), elevation = 20.dp)
                .clip(RoundedCornerShape(18.dp))
                .clickable(onClick = onCover),
        ) {
            dev.lelonio.square.ui.components.Artwork(
                url = state.artworkUrl,
                title = state.title,
                modifier = Modifier.fillMaxSize(),
                corner = 18.dp,
            )
            // A shade at the foot, so the transport reads over any sleeve.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.42f)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))),
            )
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CardButton(PhosphorIcons.Fill.SkipBack, stringResource(R.string.previous), 40.dp, state.hasPrevious, onPrevious)
                CardButton(
                    if (state.wantsPlay) PhosphorIcons.Fill.Pause else PhosphorIcons.Fill.Play,
                    stringResource(if (state.wantsPlay) R.string.pause else R.string.play),
                    52.dp,
                    true,
                    onTogglePlay,
                )
                CardButton(PhosphorIcons.Fill.SkipForward, stringResource(R.string.next), 40.dp, state.hasNext, onNext)
            }
        }
        Column(Modifier.widthIn(max = 420.dp).padding(top = 12.dp)) {
            GlassProgressBar(
                positionMs = positionMs,
                durationMs = state.durationMs,
                onSeek = onSeek,
                accentColor = GlassInk,
                trackColor = GlassInk.copy(alpha = 0.28f),
            )
            TimeRow(positionMs, state.durationMs)
        }
        Text(
            state.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = GlassInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            state.artist,
            style = MaterialTheme.typography.bodyMedium,
            color = GlassInkDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** A round control over the sleeve: a dark disc, a white glyph. */
@Composable
private fun CardButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    size: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.size(size * 0.45f),
        )
    }
}
