package dev.emanuele.spot.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import dev.emanuele.spot.ui.components.BlurTransformation
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.emanuele.spot.data.CanvasClip
import dev.emanuele.spot.data.Catalog
import dev.emanuele.spot.data.CatalogPlaylist
import dev.emanuele.spot.data.CatalogTrack
import dev.emanuele.spot.data.Lyrics
import dev.emanuele.spot.playback.AudioEffects
import dev.emanuele.spot.ui.glass.LiquidBottomTab
import dev.emanuele.spot.ui.glass.LiquidBottomTabs
import dev.emanuele.spot.ui.glass.LiquidButton
import dev.emanuele.spot.ui.home.HomeScreen
import dev.emanuele.spot.ui.library.LibraryScreen
import dev.emanuele.spot.ui.library.PlaylistScreen
import dev.emanuele.spot.ui.player.GlassFilm
import dev.emanuele.spot.ui.player.MiniPlayer
import dev.emanuele.spot.ui.player.MiniPlayerHeight
import dev.emanuele.spot.ui.player.NowPlayingSheet
import dev.emanuele.spot.ui.player.PlayerScreen
import dev.emanuele.spot.ui.player.progressOf
import dev.emanuele.spot.ui.player.rememberPlaybackState
import dev.emanuele.spot.ui.player.rememberPositionMs
import dev.emanuele.spot.ui.player.rememberQueue
import dev.emanuele.spot.ui.search.SearchScreen
import dev.emanuele.spot.ui.theme.Ink
import dev.emanuele.spot.ui.theme.SpotTheme
import dev.emanuele.spot.ui.theme.rememberArtworkColor
import kotlinx.coroutines.launch
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.House
import com.adamglin.phosphoricons.fill.MagnifyingGlass
import com.adamglin.phosphoricons.fill.MusicNotes
import com.adamglin.phosphoricons.regular.House
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.adamglin.phosphoricons.regular.MusicNotes

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val PLAYLIST = "playlist"
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
fun SpotApp(
    player: Player?,
    onPlay: (List<CatalogTrack>, Int) -> Unit,
    /** Appends one track to the end of the queue; see the swipe gesture on rows. */
    onEnqueue: (CatalogTrack) -> Unit,
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
    val liked by viewModel.liked.collectAsStateWithLifecycle()

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

    // How far the player is open, 0 to 1. A value rather than a destination:
    // see NowPlayingSheet for why the player stopped being a route.
    val expand = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

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

    LaunchedEffect(playback.mediaId) { viewModel.checkLiked(playback.mediaId) }

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

    SpotTheme(seed = accent) {
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

                // Lists end above the bottom bar and the mini player rather than
                // scrolling behind them.
                val listPadding = PaddingValues(
                    top = statusBar,
                    bottom = barHeight + if (playback.hasItem) MiniPlayerHeight else 0.dp,
                )

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
                Box(
                    Modifier
                        .fillMaxSize()
                        .layerBackdrop(pageBackdrop),
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
                                onPlayRecent = onPlay,
                                feed = feed,
                                onOpenItem = { item ->
                                    viewModel.openContext(item.uri, item.title, item.artworkUrl)
                                    navController.navigate(Routes.PLAYLIST)
                                },
                                backdrop = artBackdrop,
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
                                onPlayTrack = onPlay,
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

                        composable(Routes.PLAYLIST) {
                            // Re-seeded from the open playlist, album or artist.
                            // The rest of the app is themed after whatever is
                            // playing, which is right for the home page and
                            // wrong here: an album page tinted by an unrelated
                            // track reads as belonging to something else.
                            val detailAccent by rememberArtworkColor(playlist.artworkUrl)
                            SpotTheme(seed = detailAccent) {
                                PlaylistScreen(
                                    state = playlist,
                                    contentPadding = listPadding,
                                    nowPlayingUri = playback.mediaId,
                                    onBack = { navController.popBackStack() },
                                    onPlay = onPlay,
                                    onEnqueue = onEnqueue,
                                    onShuffle = { tracks ->
                                        // Order does not matter: the player
                                        // treats shuffle as a mode and stamps it
                                        // onto whatever queue arrives next.
                                        player?.shuffleModeEnabled = true
                                        onPlay(tracks, 0)
                                    },
                                    backdrop = artBackdrop,
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
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        // Hidden as the player takes over, and out of the way
                        // before it covers it: a tab bar under a full-screen
                        // player still swallows the taps meant for the
                        // transport.
                        .graphicsLayer { alpha = (1f - expand.value * 3f).coerceIn(0f, 1f) }
                        .onSizeChanged { barHeight = with(density) { it.height.toDp() } },
                ) {
                    BottomBar(
                        route = route,
                        bottomInset = navBar,
                        backdrop = pageBackdrop,
                        onSelect = navController::switchTab,
                    )
                }

                if (playback.hasItem) {
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
                                liked = liked,
                                onToggleLiked = viewModel::toggleLiked,
                            )
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
            // While the search screen is open, taps on the tabs have to be
            // caught here rather than by the bar.
            //
            // The bar draws its sliding indicator *over* the selected tab, and
            // that indicator carries the drag gesture, so it swallows taps on
            // whatever it is sitting on. Search is not one of the tabs, so the
            // selection falls back to Home — and tapping Home then hit the
            // indicator, matched the index the bar already held, and produced no
            // event at all. That was the "search does not close" bug.
            if (route == Routes.SEARCH) {
                Row(Modifier.matchParentSize().zIndex(1f)) {
                    routes.forEach { tab ->
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable(
                                    interactionSource = null,
                                    indication = null,
                                ) { onSelect(tab) },
                        )
                    }
                }
            }

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
            BottomItem("Home", PhosphorIcons.Fill.House, PhosphorIcons.Regular.House, selected == 0) {
                onSelect(Routes.HOME)
            }
            BottomItem(
                "Libreria",
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
                contentDescription = "Cerca",
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
private fun NavHostController.switchTab(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
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
