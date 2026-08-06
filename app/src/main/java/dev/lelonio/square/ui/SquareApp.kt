package dev.lelonio.square.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.lelonio.square.ui.components.BlurTransformation
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.lelonio.square.R
import dev.lelonio.square.data.CanvasClip
import dev.lelonio.square.data.Catalog
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.Lyrics
import dev.lelonio.square.playback.AudioEffects
import dev.lelonio.square.ui.glass.LiquidBottomTab
import dev.lelonio.square.ui.glass.LiquidBottomTabs
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.home.HomeScreen
import dev.lelonio.square.ui.library.LibraryScreen
import dev.lelonio.square.ui.library.PlaylistScreen
import dev.lelonio.square.ui.player.GlassFilm
import dev.lelonio.square.ui.player.AddToPlaylistSheet
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import dev.lelonio.square.ui.components.TrackSheet
import dev.lelonio.square.ui.components.TrackSheetAction
import dev.lelonio.square.ui.library.openLink
import com.adamglin.phosphoricons.regular.LinkSimple
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.Queue
import com.adamglin.phosphoricons.regular.Trash
import dev.lelonio.square.ui.player.MiniPlayer
import dev.lelonio.square.ui.player.MiniPlayerHeight
import dev.lelonio.square.ui.player.NowPlayingSheet
import dev.lelonio.square.ui.player.PlayerScreen
import dev.lelonio.square.ui.player.progressOf
import dev.lelonio.square.ui.player.rememberPlaybackState
import dev.lelonio.square.ui.player.rememberPositionMs
import dev.lelonio.square.ui.player.rememberQueue
import dev.lelonio.square.ui.search.SearchScreen
import dev.lelonio.square.ui.onboarding.OnboardingScreen
import dev.lelonio.square.ui.settings.SettingsScreen
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.SquareTheme
import dev.lelonio.square.ui.theme.rememberArtworkColor
import kotlinx.coroutines.launch
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.House
import com.adamglin.phosphoricons.fill.MagnifyingGlass
import com.adamglin.phosphoricons.fill.MusicNotes
import com.adamglin.phosphoricons.regular.House
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.adamglin.phosphoricons.regular.MusicNotes

/** A track menu waiting to be drawn: what it is about, and where it points. */
private data class TrackMenuRequest(
    val track: dev.lelonio.square.data.CatalogTrack,
    val removable: Boolean,
)

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val PLAYLIST = "playlist"
    const val SETTINGS = "settings"
}

/** How the player settles when it is not being dragged. */
private val expandSpec = spring<Float>(
    dampingRatio = 0.86f,
    stiffness = Spring.StiffnessMediumLow,
)

private val BottomBarHeight = 62.dp

/**
 * Decode size of the page backdrop, in pixels.
 *
 * Large enough that the blur has something to work with, small enough that
 * blurring it costs nothing and it never reads as a photograph.
 */
private const val BACKDROP_DECODE_PX = 128

/** Radius in pixels of the decoded image; see [BlurTransformation]. */
private val BackdropBlur = BlurTransformation(radius = 14, passes = 2)

@UnstableApi
@Composable
fun SquareApp(
    player: Player?,
    /**
     * Plays a queue.
     *
     * The third argument is the context it came from — a playlist or album URI
     * — which is what the account's listening history is filed under; null when
     * the tracks are a selection rather than a place. The fourth says whether
     * the queue *is* that context in its own order, in which case playback is
     * handed to Spotify as the context itself.
     */
    onPlay: (List<CatalogTrack>, Int, String?, Boolean, String) -> Unit,
    /** Appends one track to the end of the queue; see the swipe gesture on rows. */
    onEnqueue: (CatalogTrack) -> Unit,
    /**
     * Incremented when something outside the app asks for the player — the
     * notification, for now. A counter, so a second request while the player is
     * already open is still a request.
     */
    openPlayer: Int = 0,
    viewModel: MainViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val playback by rememberPlaybackState(player)
    // Held as State, not read here: reading the position at this level would
    // recompose the whole app — lists included — several times a second.
    val positionMs = rememberPositionMs(player, playback.isPlaying)
    val queue by rememberQueue(player)
    val accent by rememberArtworkColor(playback.artworkUrl)
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val webApi by viewModel.webApi.collectAsStateWithLifecycle()
    val reverb by AudioEffects.reverb.collectAsStateWithLifecycle()
    val presets by viewModel.effectPresets.collectAsStateWithLifecycle()
    val feed by viewModel.feed.collectAsStateWithLifecycle()
    val playlistOrder by viewModel.playlistOrder.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val addToPlaylist by viewModel.addToPlaylist.collectAsStateWithLifecycle()
    val trackSort by viewModel.trackSort.collectAsStateWithLifecycle()
    val onboarded by viewModel.onboarded.collectAsStateWithLifecycle()

    // Asked for again from the settings, after it has already been finished.
    var showTutorial by remember { mutableStateOf(false) }

    // The open track menu, if any. Held here because the menu is drawn above
    // everything the app puts over its screens.
    var trackMenu by remember { mutableStateOf<TrackMenuRequest?>(null) }

    // Once, on the first composition that has a usable Web API session. The
    // ViewModel keeps what it fetched, so navigating away and back does not
    // spend the quota again.
    LaunchedEffect(webApi.connected) { viewModel.loadFeed() }
    // Two layers, and the split is not optional.
    //
    // `pageBackdrop` records the artwork *and* the screen on top of it, and is
    // what the floating bars refract — that is how the list underneath shows
    // through them. Those bars therefore have to be drawn outside it: a layer
    // that contains something which draws that same layer recurses until the
    // render thread's stack runs out, which is exactly the SIGSEGV this caused.
    //
    // `artBackdrop` records only the blurred artwork, and is what glass *inside*
    // a screen uses — the playlist buttons, the search button — for the same
    // reason: they cannot sample a layer they are part of.
    val artBackdrop = rememberLayerBackdrop()
    val pageBackdrop = rememberLayerBackdrop()

    // Everything the modals cover: the screens, the bars *and* the player. The
    // sheets used to sample `pageBackdrop`, which stops at the navigation host,
    // so opening one over the player blurred the home page behind it instead of
    // the player it was opened from.
    val overlayBackdrop = rememberLayerBackdrop()

    // How far the player is open, 0 to 1. A value rather than a destination:
    // see NowPlayingSheet for why the player stopped being a route.
    val expand = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // Opened from outside — a tap on the media notification.
    //
    // Held as a pending request rather than acted on immediately: the media
    // controller connects a moment after the activity starts, so a launch
    // straight from the notification arrives here with no track yet and the
    // player would have nothing to open onto. This waits for one.
    var pendingOpen by remember { mutableStateOf(false) }
    LaunchedEffect(openPlayer) {
        if (openPlayer > 0) pendingOpen = true
    }
    LaunchedEffect(pendingOpen, playback.hasItem) {
        if (pendingOpen && playback.hasItem) {
            pendingOpen = false
            // Run outside this effect. Clearing the flag changes one of the
            // effect's own keys, so the effect is cancelled immediately — and
            // with it the animation, about two frames in. That is why the player
            // "opened" and was never seen.
            scope.launch {
                expand.animateTo(1f, expandSpec)
            }
        }
    }

    // Measured rather than assumed. The constant this replaced was 62dp while
    // the bar is a 64dp capsule with padding around it and the navigation inset
    // under that, so the sheet sat about twenty over it.
    var barHeight by remember { mutableStateOf(BottomBarHeight) }
    val density = LocalDensity.current

    // Recorded here rather than at the tap: this fires for auto-advance and for
    // controls outside the app too, so the history matches what was actually
    // heard instead of only what was tapped.
    LaunchedEffect(playback.mediaId) {
        val uri = playback.mediaId ?: return@LaunchedEffect
        viewModel.recordPlayed(
            CatalogTrack(
                uri = uri,
                name = playback.title,
                artist = playback.artist,
                durationMs = playback.durationMs,
                artworkUrl = playback.artworkUrl,
            ),
        )
    }

    var canvas by remember { mutableStateOf<CanvasClip?>(null) }

    // Fetched per track, like the lyrics. Most tracks have none, so a null is an
    // ordinary answer and the player falls back to the cover.
    LaunchedEffect(playback.mediaId) {
        val uri = playback.mediaId
        canvas = null
        if (uri != null) canvas = Catalog.canvas(uri)
    }

    var lyrics by remember { mutableStateOf<Lyrics?>(null) }
    var lyricsLoading by remember { mutableStateOf(false) }

    // Fetched per track. Most of the catalogue has none, so a null result is an
    // ordinary answer that shows an empty state rather than an error.
    LaunchedEffect(playback.mediaId) {
        val uri = playback.mediaId
        lyrics = null
        if (uri == null) return@LaunchedEffect
        lyricsLoading = true
        lyrics = runCatching { Catalog.lyrics(uri) }.getOrNull()
        lyricsLoading = false
    }

    // The language the app is read in. Changing it re-creates the activity,
    // which is the only way a Compose tree already built out of one set of
    // resources can be rebuilt out of another.
    val context = LocalContext.current
    val languageStore = remember(context) {
        (context.applicationContext as dev.lelonio.square.SquareApplication).language
    }
    val language by languageStore.tag.collectAsStateWithLifecycle()
    val setLanguage: (String) -> Unit = remember(languageStore, context) {
        { tag ->
            if (tag != languageStore.tag.value) {
                languageStore.set(tag)
                (context as? android.app.Activity)?.recreate()
            }
        }
    }

    // Read once, up here: these travel with a play as plain text, and the rows
    // that start one are not composables of their own.
    val playAgainLabel = stringResource(R.string.play_again)
    val searchLabel = stringResource(R.string.search)

    SquareTheme(seed = accent) {
        // Material's default content colour is black, and it used to arrive from
        // the Surface that wrapped this tree. That Surface is gone — it painted
        // an opaque page over the backdrop — so the colour has to be provided
        // here, or every Text that does not set one explicitly stays black on
        // the darkened artwork.
        CompositionLocalProvider(LocalContentColor provides Ink) {
            Box(Modifier.fillMaxSize()) {
                val navController = rememberNavController()
                val currentEntry by navController.currentBackStackEntryAsState()
                val route = currentEntry?.destination?.route

                val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

                // Settings is not a place you listen from: it is a full page of
                // its own, and the tab bar and the now-playing bar step out of
                // the way for it instead of floating over a form.
                val chromeHidden = route == Routes.SETTINGS
                val chrome by animateFloatAsState(
                    if (chromeHidden) 0f else 1f,
                    tween(220),
                    label = "chrome",
                )

                // Lists end above the bottom bar and the mini player rather than
                // scrolling behind them.
                val listPadding = PaddingValues(
                    top = statusBar,
                    bottom = barHeight + if (playback.hasItem) MiniPlayerHeight else 0.dp,
                )

                // Nothing floats over settings, so nothing has to be left free
                // beneath it either.
                val settingsPadding = PaddingValues(top = statusBar, bottom = navBar + 24.dp)

                // The screens are recorded into the backdrop layer along with
                // the artwork behind them, and the bars that float over them are
                // not.
                //
                // That split is the whole point. Recording only the artwork —
                // which is what this did at first — meant the mini player and
                // the tab bar refracted a blurred cover no matter what was
                // actually beneath them, so they looked like frosted panels
                // rather than like glass: nothing of the list underneath ever
                // showed through.
                // Recorded only while something samples it.
                //
                // A layer backdrop is not free to keep: the subtree draws into
                // an offscreen buffer the size of the window and that buffer is
                // then composited, every frame, whether or not anyone reads it.
                // The sheets that read this one are shut most of the time, so
                // the whole app was paying for a full-screen copy of itself for
                // nothing.
                val sheetsOpen = trackMenu != null || addToPlaylist.open
                // Kept on a little past the close, or the layer would stop
                // being recorded while the sheet is still fading out over it
                // and the panel would go black on the way down.
                var sheetsRecording by remember { mutableStateOf(false) }
                LaunchedEffect(sheetsOpen) {
                    if (sheetsOpen) sheetsRecording = true
                    else {
                        kotlinx.coroutines.delay(400)
                        sheetsRecording = false
                    }
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (sheetsOpen || sheetsRecording) Modifier.layerBackdrop(overlayBackdrop)
                            else Modifier,
                        ),
                ) {
                // Same trade for the page layer: the only things that read it
                // are the tab bar and the mini player, and a fully open player
                // covers both. Derived so it flips once at the end of the
                // animation rather than recomposing on every frame of it.
                val barsVisible by remember {
                    derivedStateOf { expand.value < 0.999f }
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (barsVisible) Modifier.layerBackdrop(pageBackdrop)
                            else Modifier,
                        ),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .layerBackdrop(artBackdrop),
                    ) {
                        AppBackdrop(playback.artworkUrl)
                    }

                    NavHost(navController, startDestination = Routes.HOME) {
                        composable(Routes.HOME) {
                            HomeScreen(
                                state = state,
                                contentPadding = listPadding,
                                onLogIn = viewModel::logIn,
                                onRetry = { viewModel.refresh() },
                                onLogOut = viewModel::logOut,
                                onOpenPlaylist = { navController.openPlaylist(viewModel, it) },
                                playlistOrder = playlistOrder,
                                recent = recent,
                                onPlayRecent = { tracks, index ->
                                    onPlay(tracks, index, null, false, playAgainLabel)
                                },
                                onPlayFeed = { tracks, index, label ->
                                    onPlay(tracks, index, null, false, label)
                                },
                                feed = feed,
                                onOpenItem = { item ->
                                    viewModel.openContext(item.uri, item.title, item.artworkUrl)
                                    navController.navigate(Routes.PLAYLIST)
                                },
                                backdrop = artBackdrop,
                                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                            )
                        }

                        composable(Routes.SEARCH) {
                            SearchScreen(
                                state = search,
                                webApi = webApi,
                                contentPadding = listPadding,
                                nowPlayingUri = playback.mediaId,
                                onQueryChange = viewModel::onSearchQuery,
                                onClientIdChange = viewModel::onWebApiClientIdChange,
                                onConnectWebApi = { viewModel.connectWebApi() },
                                onPlayTrack = { tracks, index ->
                                    onPlay(tracks, index, null, false, searchLabel)
                                },
                                onEnqueue = onEnqueue,
                                onOpenContext = { item ->
                                    viewModel.openContext(item.uri, item.title, item.artworkUrl)
                                    navController.navigate(Routes.PLAYLIST)
                                },
                                backdrop = artBackdrop,
                            )
                        }

                        composable(Routes.LIBRARY) {
                            LibraryScreen(
                                state = state,
                                contentPadding = listPadding,
                                onLogIn = viewModel::logIn,
                                onRetry = { viewModel.refresh() },
                                onLogOut = viewModel::logOut,
                                onOpenPlaylist = { navController.openPlaylist(viewModel, it) },
                                playlistOrder = playlistOrder,
                                backdrop = artBackdrop,
                            )
                        }

                        composable(Routes.SETTINGS) {
                            SettingsScreen(
                                state = state,
                                webApi = webApi,
                                contentPadding = settingsPadding,
                                backdrop = artBackdrop,
                                deviceName = android.os.Build.MODEL ?: "Android",
                                onClientIdChange = viewModel::onWebApiClientIdChange,
                                onConnectWebApi = viewModel::connectWebApi,
                                onDisconnectWebApi = viewModel::disconnectWebApi,
                                onLogOut = viewModel::logOut,
                                onShowTutorial = { showTutorial = true },
                                language = language,
                                onLanguage = setLanguage,
                                onBack = { navController.popBackStack() },
                            )
                        }

                        composable(Routes.PLAYLIST) {
                            // Re-seeded from the open playlist, album or artist.
                            // The rest of the app is themed after whatever is
                            // playing, which is right for the home page and
                            // wrong here: an album page tinted by an unrelated
                            // track reads as belonging to something else.
                            val detailAccent by rememberArtworkColor(playlist.artworkUrl)
                            // Resolved here rather than inside the play
                            // callbacks: those are not composables.
                            val source = playlist.sourceLabel()
                            SquareTheme(seed = detailAccent) {
                                PlaylistScreen(
                                    state = playlist,
                                    contentPadding = listPadding,
                                    nowPlayingUri = playback.mediaId,
                                    onBack = { navController.popBackStack() },
                                    onPlay = { tracks, index, asContext ->
                                        onPlay(
                                            tracks,
                                            index,
                                            playlist.uri,
                                            asContext,
                                            source,
                                        )
                                    },
                                    onEnqueue = onEnqueue,
                                    onShuffle = { tracks ->
                                        // Order does not matter: the player
                                        // treats shuffle as a mode and stamps it
                                        // onto whatever queue arrives next.
                                        player?.shuffleModeEnabled = true
                                        // Shuffled: the order on screen is not
                                        // the one that will play, so this is a
                                        // selection rather than the context.
                                        onPlay(
                                            tracks,
                                            0,
                                            playlist.uri,
                                            false,
                                            source,
                                        )
                                    },
                                    onAddToPlaylist = { track ->
                                        viewModel.openAddToPlaylist(track.uri, track.name)
                                    },
                                    onTrackMenu = { track ->
                                        trackMenu = TrackMenuRequest(
                                            track = track,
                                            // Only a playlist can have
                                            // something taken out of it.
                                            removable = playlist.kind ==
                                                MainViewModel.DetailKind.PLAYLIST,
                                        )
                                    },
                                    storedSort = trackSort,
                                    onSortChange = viewModel::setTrackSort,
                                    onOpenItem = { item ->
                                        viewModel.openContext(
                                            item.uri,
                                            item.title,
                                            item.artworkUrl,
                                        )
                                    },
                                )
                            }
                        }

                    }
                }

                // Outside the recorded layer, and that is structural rather than
                // stylistic: these refract `pageBackdrop`, and a pane drawn
                // inside the layer it samples recurses on the render thread
                // until the process dies.
                // Faded out *and* taken out: a transparent bar still swallows
                // the taps meant for the page underneath it.
                if (chrome > 0.01f) Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        // Hidden as the player takes over, and out of the way
                        // before it covers it: a tab bar under a full-screen
                        // player still swallows the taps meant for the
                        // transport.
                        .graphicsLayer {
                            alpha = (1f - expand.value * 3f).coerceIn(0f, 1f) * chrome
                        }
                        .onSizeChanged { barHeight = with(density) { it.height.toDp() } },
                ) {
                    BottomBar(
                        route = route,
                        bottomInset = navBar,
                        backdrop = pageBackdrop,
                        onSelect = navController::switchTab,
                    )
                }

                if (playback.hasItem && chrome > 0.01f) {
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = chrome }) {
                    NowPlayingSheet(
                        progress = expand,
                        // The measured bar already carries the navigation
                        // inset; the gap is what keeps the two from touching.
                        bottomInset = barHeight + 6.dp,
                        background = { AppBackdrop(playback.artworkUrl) },
                        collapsedContent = {
                            MiniPlayer(
                                state = playback,
                                progress = {
                                    progressOf(positionMs.value, playback.durationMs)
                                },
                                backdrop = pageBackdrop,
                                onExpand = { scope.launch { expand.animateTo(1f, expandSpec) } },
                                onTogglePlay = { player?.togglePlay() },
                                onNext = { player?.seekToNextMediaItem() },
                            )
                        },
                        expandedContent = {
                            PlayerScreen(
                                state = playback,
                                positionMs = positionMs,
                                onCollapse = { scope.launch { expand.animateTo(0f, expandSpec) } },
                                onTogglePlay = { player?.togglePlay() },
                                onNext = { player?.seekToNextMediaItem() },
                                onPrevious = { player?.seekToPreviousMediaItem() },
                                onSeek = { player?.seekTo(it) },
                                onToggleShuffle = {
                                    player?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
                                },
                                onCycleRepeat = { player?.cycleRepeatMode() },
                                queue = queue,
                                lyrics = lyrics,
                                lyricsLoading = lyricsLoading,
                                onPlayQueueItem = { player?.seekTo(it, 0L) },
                                reverb = reverb,
                                // Speed and pitch are set as a pair because
                                // PlaybackParameters carries both; changing one
                                // has to carry the other through unchanged.
                                onSpeed = {
                                    player?.playbackParameters =
                                        PlaybackParameters(it, playback.pitch)
                                },
                                onPitch = {
                                    player?.playbackParameters =
                                        PlaybackParameters(playback.speed, it)
                                },
                                onReverb = AudioEffects::setReverb,
                                presets = presets,
                                onApplyPreset = { preset ->
                                    player?.playbackParameters =
                                        PlaybackParameters(preset.speed, preset.pitch)
                                    AudioEffects.setReverb(preset.reverbAmount)
                                },
                                onSavePreset = {
                                    viewModel.saveEffectPreset(
                                        it,
                                        playback.speed,
                                        playback.pitch,
                                        reverb,
                                    )
                                },
                                onDeletePreset = viewModel::deleteEffectPreset,
                                backdrop = artBackdrop,
                                canvas = canvas,
                                devices = devices,
                                onOpenDevices = viewModel::openDevices,
                                onCloseDevices = viewModel::closeDevices,
                                onRefreshDevices = viewModel::refreshDevices,
                                onSelectDevice = { viewModel.transferPlayback(it) },
                                onAddToPlaylist = {
                                    viewModel.openAddToPlaylist(
                                        playback.mediaId,
                                        playback.title,
                                        asSheet = false,
                                    )
                                },
                                addToPlaylist = addToPlaylist,
                                onPickPlaylist = viewModel::addToPlaylist,
                            )
                        },
                    )
                    }
                }

                }

                // Above the tab bar and the now-playing bar, which is the whole
                // reason it lives here instead of in the screen that asked for
                // it.
                val menu = trackMenu
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                TrackSheet(
                    visible = menu != null,
                    title = menu?.track?.name.orEmpty(),
                    subtitle = menu?.track?.artist.orEmpty(),
                    artworkUrl = menu?.track?.artworkUrl,
                    backdrop = overlayBackdrop,
                    onDismiss = { trackMenu = null },
                ) {
                    if (menu != null) {
                        TrackSheetAction(stringResource(R.string.play), PhosphorIcons.Fill.Play) {
                            trackMenu = null
                            onPlay(listOf(menu.track), 0, null, false, "")
                        }
                        TrackSheetAction(stringResource(R.string.add_to_queue), PhosphorIcons.Regular.Queue) {
                            trackMenu = null
                            onEnqueue(menu.track)
                        }
                        TrackSheetAction(stringResource(R.string.add_to_playlist), PhosphorIcons.Regular.Plus) {
                            trackMenu = null
                            viewModel.openAddToPlaylist(menu.track.uri, menu.track.name)
                        }
                        TrackSheetAction(stringResource(R.string.copy_link), PhosphorIcons.Regular.LinkSimple) {
                            trackMenu = null
                            clipboard.setText(AnnotatedString(menu.track.openLink()))
                        }
                        if (menu.removable) {
                            TrackSheetAction(
                                stringResource(R.string.remove_from_playlist),
                                PhosphorIcons.Regular.Trash,
                                destructive = true,
                            ) {
                                trackMenu = null
                                viewModel.removeFromPlaylist(menu.track)
                            }
                        }
                    }
                }

                // Over everything, including the player: the same sheet is
                // opened from a track row in the library and from the plus in
                // the player, and it is one sheet with one state.
                AddToPlaylistSheet(
                    state = addToPlaylist,
                    backdrop = overlayBackdrop,
                    onSelect = viewModel::addToPlaylist,
                    onDismiss = viewModel::closeAddToPlaylist,
                )

                // Above everything, bars and player included: until it is done
                // there is nothing underneath worth reaching, and on a fresh
                // install most of what is underneath does not work yet.
                if (!onboarded || showTutorial) {
                    OnboardingScreen(
                        state = state,
                        webApi = webApi,
                        backdrop = artBackdrop,
                        onLogIn = viewModel::logIn,
                        onClientIdChange = viewModel::onWebApiClientIdChange,
                        onConnectWebApi = { viewModel.connectWebApi() },
                        language = language,
                        onLanguage = setLanguage,
                        onFinish = {
                            showTutorial = false
                            viewModel.setOnboarded(true)
                        },
                    )
                }
            }
        }
    }
}

/**
 * The blurred artwork every screen sits on.
 *
 * Blurred here rather than by the backdrop library: this is the *page*
 * background, not a glass pane, and it has to be soft even where no glass is
 * drawn over it. The scrim on top is what keeps text legible over a bright
 * cover — the palette is built for a dark page.
 */
@Composable
private fun AppBackdrop(artworkUrl: String?) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0C)),
    ) {
        if (artworkUrl != null) {
            // Blurred at decode time rather than by `Modifier.blur`. That
            // modifier is a RenderEffect over the whole window, re-run whenever
            // the layer changes — every frame while the player expands, which is
            // most of what made it stutter. Stretching a 32px bitmap instead was
            // free and looked it: bilinear upscaling from that size shows square
            // blocks, not a wash. This does the blur once per cover, on a small
            // bitmap, and Coil caches it.
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(artworkUrl)
                    .size(BACKDROP_DECODE_PX)
                    .transformations(BackdropBlur)
                    .allowHardware(false)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.45f),
                        0.5f to Color.Black.copy(alpha = 0.60f),
                        1f to Color.Black.copy(alpha = 0.78f),
                    ),
                ),
        )
    }
}

@Composable
private fun BottomBar(
    route: String?,
    bottomInset: Dp,
    backdrop: Backdrop,
    onSelect: (String) -> Unit,
) {
    // Search sits outside the capsule as its own round button, the way the
    // reference has it: it is a different kind of destination — you go there to
    // do something and come back — and giving it the same weight as Home and
    // Library made all three read as places.
    val routes = remember { listOf(Routes.HOME, Routes.LIBRARY) }

    // The last tab that was actually on screen, held across screens that are not
    // tabs at all — a playlist, the search page.
    //
    // Falling back to Home instead, which is what this did, navigated the user
    // away: the bar reports every change of its selected index through
    // `onTabSelected`, so opening a playlist from Library moved the index 1 -> 0
    // and the bar promptly "selected" Home. That was the playlist opening and
    // then bouncing back.
    var lastTab by remember { mutableStateOf(0) }
    val routeIndex = routes.indexOf(route)
    if (routeIndex >= 0 && routeIndex != lastTab) lastTab = routeIndex
    val selected = if (routeIndex >= 0) routeIndex else lastTab

    // A *stable* lambda, deliberately. LiquidBottomTabs keys its internal state
    // on this reference, so a fresh closure on every recomposition threw that
    // state away and the indicator arrived at the new tab without ever
    // animating — which is exactly the bug where tapping snapped and only
    // dragging moved.
    val selectedState = rememberUpdatedState(selected)
    val selectedTabIndex = remember { { selectedState.value } }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = bottomInset + 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            LiquidBottomTabs(
            selectedTabIndex = selectedTabIndex,
            onTabSelected = { onSelect(routes[it]) },
            backdrop = backdrop,
            tabsCount = routes.size,
            // Both upstream defaults are wrong here: the accent is a system blue
            // that belongs to no part of this palette, and the container is a
            // 40% fill that made the bar a solid slab beside the other glass.
            accentColor = Ink,
            containerColor = GlassFilm,
        ) {
            BottomItem(
                stringResource(R.string.home),
                PhosphorIcons.Fill.House,
                PhosphorIcons.Regular.House,
                selected == 0,
            ) {
                onSelect(Routes.HOME)
            }
            BottomItem(
                stringResource(R.string.library),
                PhosphorIcons.Fill.MusicNotes,
                PhosphorIcons.Regular.MusicNotes,
                selected == 1,
            ) { onSelect(Routes.LIBRARY) }
            }
        }

        Spacer(Modifier.width(10.dp))

        val searching = route == Routes.SEARCH
        LiquidButton(
            onClick = { onSelect(Routes.SEARCH) },
            backdrop = backdrop,
            tint = if (searching) MaterialTheme.colorScheme.primary else Color.Unspecified,
            modifier = Modifier.size(64.dp),
            contentHeight = 64.dp,
            contentPadding = 0.dp,
            blurRadius = 8.dp,
            // The same film the capsule beside it uses, or the round button
            // reads as clearer glass than the bar it sits next to.
            surfaceColor = GlassFilm,
        ) {
            Icon(
                imageVector = if (searching) PhosphorIcons.Fill.MagnifyingGlass else PhosphorIcons.Regular.MagnifyingGlass,
                contentDescription = stringResource(R.string.search),
                tint = Ink,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * Selected tabs switch to the filled icon as well as to ink.
 *
 * Weight carries the selection, not just colour: the difference between a
 * filled and an outlined glyph survives at a glance and for anyone who cannot
 * separate the two tints.
 */
@Composable
private fun RowScope.BottomItem(
    label: String,
    filled: ImageVector,
    outlined: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // Fixed ink rather than the artwork accent. Tinting the label after the
    // playing track put an arbitrary colour on the one control that has to stay
    // readable, and some of those colours have almost no contrast against the
    // glass.
    val tint = if (selected) Ink else Ink.copy(alpha = 0.62f)
    // `LiquidBottomTab` rather than a Column of our own, and this is not
    // cosmetic: it takes an equal share of the row, and the sliding indicator is
    // positioned as `index * (barWidth / tabsCount)`. A tab sized by its own
    // padding leaves the two disagreeing — labels packed to the left with the
    // indicator sitting under empty space.
    LiquidBottomTab(onClick = onClick) {
        Icon(
            imageVector = if (selected) filled else outlined,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(23.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = tint,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** Loads the playlist and shows it, from whichever tab asked. */
private fun NavHostController.openPlaylist(viewModel: MainViewModel, playlist: CatalogPlaylist) {
    viewModel.openPlaylist(playlist)
    navigate(Routes.PLAYLIST)
}

/**
 * Switches tab without stacking destinations.
 *
 * Without popping back to the start, tapping between tabs would grow the back
 * stack forever and the system back button would walk the entire history.
 */
/**
 * What the player says the track is coming from: "Playlist · Estate 2025".
 *
 * The kind first, because the name alone reads as a title and the two are worth
 * telling apart at a glance while a cover is filling the screen.
 */
@Composable
private fun MainViewModel.PlaylistState.sourceLabel(): String {
    val kindName = stringResource(kind.label)
    return if (name.isBlank()) kindName else "$kindName · $name"
}

private fun NavHostController.switchTab(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) {
        // No `saveState`/`restoreState`, and that is the fix rather than a
        // simplification. With them, leaving Home for a playlist saved the pair
        // [home, playlist] under Home's id, and tapping Home restored exactly
        // that — the playlist came straight back and the tab looked dead.
        //
        // Popping to the start destination without saving leaves Home on top
        // and, for Library, one entry over it. What a tab loses is its scroll
        // position, which is the right thing to lose when the tap means "take
        // me back to this page".
        popUpTo(Routes.HOME)
        launchSingleTop = true
    }
}

private fun Player.togglePlay() {
    if (isPlaying) pause() else play()
}

/** off → all → one → off, the order every player uses. */
private fun Player.cycleRepeatMode() {
    repeatMode = when (repeatMode) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    }
}
