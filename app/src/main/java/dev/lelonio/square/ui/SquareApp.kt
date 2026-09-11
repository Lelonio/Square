package dev.lelonio.square.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.luminance
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.lelonio.square.ui.components.FriendsPanel
import dev.lelonio.square.ui.components.SharedTrackCard
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import com.adamglin.phosphoricons.fill.XCircle
import dev.lelonio.square.ui.glass.floatingtabbar.FloatingTabBar
import dev.lelonio.square.ui.glass.floatingtabbar.FloatingTabBarDefaults
import dev.lelonio.square.ui.glass.floatingtabbar.rememberFloatingTabBarScrollConnection
import androidx.compose.ui.unit.sp
import dev.lelonio.square.ui.glass.liquidGlass
import dev.lelonio.square.ui.glass.pressable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.layerBackdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.rememberCombinedBackdrop
import dev.lelonio.square.ui.glass.backdrop.drawBackdrop
import dev.lelonio.square.R
import dev.lelonio.square.data.CanvasClip
import dev.lelonio.square.data.Catalog
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.Lyrics
import dev.lelonio.square.backend.youtube.YouTubeVideoMode
import dev.lelonio.square.playback.AudioEffects
import dev.lelonio.square.ui.glass.LiquidBottomTab
import dev.lelonio.square.ui.browse.NewScreen
import dev.lelonio.square.ui.browse.RadioScreen
import dev.lelonio.square.ui.glass.LiquidBottomTabs
import dev.lelonio.square.ui.glass.LiquidBottomTab
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
import dev.lelonio.square.ui.components.UpdateSheet
import dev.lelonio.square.update.Updater
import dev.lelonio.square.ui.library.openLink
import com.adamglin.phosphoricons.regular.Download
import com.adamglin.phosphoricons.regular.LinkSimple
import com.adamglin.phosphoricons.regular.PencilSimple
import com.adamglin.phosphoricons.regular.PushPin
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.Queue
import com.adamglin.phosphoricons.regular.SpotifyLogo
import com.adamglin.phosphoricons.regular.YoutubeLogo
import com.adamglin.phosphoricons.regular.Export
import com.adamglin.phosphoricons.regular.Trash
import com.adamglin.phosphoricons.regular.User
import dev.lelonio.square.data.RemoteConnect
import dev.lelonio.square.ui.player.asPlaybackState
import dev.lelonio.square.ui.player.rememberRemotePositionMs
import kotlinx.coroutines.Dispatchers
import dev.lelonio.square.ui.player.FloatingMiniPlayer
import dev.lelonio.square.ui.player.MiniPlayer
import dev.lelonio.square.ui.player.PlaybackState
import dev.lelonio.square.ui.player.MiniPlayerHeight
import dev.lelonio.square.ui.player.NowPlayingSheet
import dev.lelonio.square.ui.player.PlayerScreen
import dev.lelonio.square.ui.player.progressOf
import dev.lelonio.square.ui.player.rememberPlaybackState
import dev.lelonio.square.ui.player.rememberPositionMs
import dev.lelonio.square.ui.player.rememberQueue
import dev.lelonio.square.ui.search.SearchScreen
import dev.lelonio.square.ui.onboarding.BackendChoiceScreen
import dev.lelonio.square.ui.onboarding.OnboardingScreen
import dev.lelonio.square.ui.settings.SettingsScreen
import dev.lelonio.square.ui.theme.footToneFor
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.pageColorFor
import dev.lelonio.square.ui.theme.pageColorForHex
import dev.lelonio.square.ui.theme.pageGround
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.togetherWith
import dev.lelonio.square.ui.theme.SquareTheme
import dev.lelonio.square.ui.theme.rememberArtworkColor
import dev.lelonio.square.ui.theme.rememberArtworkFootColor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.fill.ArrowCircleDown
import com.adamglin.phosphoricons.fill.Check
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.House
import com.adamglin.phosphoricons.fill.MagnifyingGlass
import com.adamglin.phosphoricons.fill.MusicNotes
import com.adamglin.phosphoricons.bold.House
import com.adamglin.phosphoricons.bold.MusicNotes
import com.adamglin.phosphoricons.bold.SquaresFour
import com.adamglin.phosphoricons.bold.Broadcast
import com.adamglin.phosphoricons.bold.MagnifyingGlass
import com.adamglin.phosphoricons.fill.SquaresFour
import com.adamglin.phosphoricons.fill.Broadcast
import com.adamglin.phosphoricons.regular.SquaresFour
import com.adamglin.phosphoricons.regular.Broadcast
import com.adamglin.phosphoricons.regular.House
import com.adamglin.phosphoricons.regular.MagnifyingGlass
import com.adamglin.phosphoricons.regular.MusicNotes

/** A name being asked for: null playlist means one that does not exist yet. */
private data class NamingRequest(val playlist: CatalogPlaylist?)

/** A track menu waiting to be drawn: what it is about, and where it points. */
private data class TrackMenuRequest(
    val track: dev.lelonio.square.data.CatalogTrack,
    val removable: Boolean,
)

object Routes {
    const val HOME = "home"
    const val NEW = "new"
    const val RADIO = "radio"
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
 * White, carrying a colour's hue.
 *
 * Mixing plain white with the colour itself is what a first version did, and on
 * a page whose colour is a deep purple that gives grey: the darkness comes along
 * with the hue. This takes the hue and the saturation, sets the brightness where
 * a tint belongs, and only then mixes — so a purple page gives a purple-white and
 * a green one a green-white, whatever the page's own colour was worth.
 */
@Composable
private fun inkTintedBy(color: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    if (hsv[1] < 0.06f) return Ink
    hsv[1] = hsv[1].coerceIn(0.5f, 1f)
    // Bright over a dark page and deep over a light one. Fixed bright was the
    // whole of this before there was a light side, and there it gave a pale
    // icon on a white film — the tint was right and the direction was not.
    hsv[2] = if (dev.lelonio.square.ui.theme.lightPage()) 0.38f else 0.9f
    return androidx.compose.ui.graphics.lerp(Ink, Color(android.graphics.Color.HSVToColor(hsv)), 0.3f)
}

/** Roughly what the search circle takes: the row's height, which is its own. */
private val SearchCircle = 58.dp

/**
 * The bar's own inset, and the size of a glyph in it.
 *
 * Both measured off the reference: at its scale the bar holds its slots 3.9px
 * clear of its ends and draws a 21px glyph in a 50px-tall bar, which is 5dp and
 * 25dp at this one.
 */
private val BarInset = 6.dp
private val TabIcon = 26.dp

/**
 * One place in the bar's tab row.
 *
 * A thin wrapper over the catalog's own tab so the four of them are not four
 * copies of the same eight lines. The colours are the app's: the place you are
 * takes the accent the cover gave the theme, and the rest are the bar's white
 * with the page's colour in it.
 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.BarTab(
    @StringRes label: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    accent: Color,
    ink: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    LiquidBottomTab(onClick = onClick, modifier = modifier) {
        Icon(
            icon,
            contentDescription = stringResource(label),
            tint = if (selected) accent else ink,
            modifier = Modifier.size(TabIcon),
        )
        Text(
            stringResource(label),
            fontSize = 9.sp,
            lineHeight = 11.sp,
            style = LocalTextStyle.current.copy(
                platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                    includeFontPadding = false,
                ),
                lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                    alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                    trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
                ),
            ),
            fontWeight = FontWeight.Bold,
            color = if (selected) accent else ink,
        )
    }
}

/**
 * The folded bar's search button.
 *
 * A key of its own rather than the search route's: the route belongs to the tab
 * now, and a standalone sharing that key would be reported as the selected tab
 * and light up beside itself.
 */
private const val SEARCH_CIRCLE = "search-circle"

/** How tall the open bar stands; measured off the reference. See barMargin. */
private val BarHeight = 61.dp

/** The pages that are somewhere you are, rather than somewhere you went. */
private val TAB_ROUTES =
    setOf(Routes.HOME, Routes.NEW, Routes.RADIO, Routes.LIBRARY, Routes.SEARCH)

/** How many stations the radio tab offers. Enough to scroll, few enough to read. */
private const val RADIO_SEEDS = 36

/**
 * How far the radio page's order moves each day.
 *
 * A prime, so a list of any length works its way through all of itself rather
 * than landing on the same few starting points.
 */
private const val DAILY_TURN = 7

/**
 * Decode size of the page backdrop, in pixels.
 *
 * Large enough that the blur has something to work with, small enough that
 * blurring it costs nothing and it never reads as a photograph.
 */
/**
 * The permission that lets the app see the phone's own music.
 *
 * Returns what to call to ask for it. Already-granted is answered without a
 * dialog: the system shows nothing for a permission it has, and the caller
 * still needs to hear that the answer is yes so the shelf can fill itself in.
 */
@Composable
private fun rememberLocalAudioPermission(onAnswered: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { onAnswered() }
    return {
        if (dev.lelonio.square.data.LocalLibrary.granted(context)) onAnswered()
        else launcher.launch(dev.lelonio.square.data.LocalLibrary.permission)
    }
}

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
    onPlay: (List<CatalogTrack>, Int, String?, Boolean, String, Long) -> Unit,
    /** Appends one track to the end of the queue; see the swipe gesture on rows. */
    onEnqueue: (CatalogTrack) -> Unit,
    /** Appends multiple tracks to the end of the queue; used by Autoplay. */
    onEnqueueAll: (List<CatalogTrack>) -> Unit = {},
    /**
     * Incremented when something outside the app asks for the player — the
     * notification, for now. A counter, so a second request while the player is
     * already open is still a request.
     */
    openPlayer: Int = 0,
    /** A Spotify link the app was opened with; see [LinkRequest]. */
    link: LinkRequest? = null,
    viewModel: MainViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The session as it was left, so the player has something to draw before
    // the media controller connects. Read once, off the saved queue.
    val seed = remember(context) { savedPlaybackSeed(context) }
    val localState = rememberPlaybackState(player, seed)
    val local by localState

    /**
     * Playback on another of the account's devices, when there is any.
     *
     * When there is, it is what the player shows and what the buttons drive:
     * the phone is not playing, so a player drawn from the phone would be an
     * empty screen next to music the user can hear. This is what the official
     * client does with a speaker in another room.
     */
    val elsewhere by dev.lelonio.square.data.RemoteConnect.playback.collectAsStateWithLifecycle()

    /**
     * Whether the screen is attached to another device.
     *
     * The engine's answer, not a guess. This started as a rule about who was
     * making sound, because that was all this side could see, and every version
     * of it was wrong somewhere: attached to a device that was only paused, or
     * detached the moment the listener paused it, or pointed at the local player
     * while the music was elsewhere, which left the play button reaching for
     * nothing and a spinner turning for ever.
     */
    val elsewhereActive by dev.lelonio.square.data.RemoteConnect.elsewhereActive
        .collectAsStateWithLifecycle()
    val inPlaylists by viewModel.inPlaylists.collectAsStateWithLifecycle()
    val homeShelves by viewModel.homeShelves.collectAsStateWithLifecycle()
    val remote = elsewhere?.takeIf { elsewhereActive }
    val remoteLabel = stringResource(R.string.playing_on, remote?.deviceName.orEmpty())
    val playback = remote?.asPlaybackState(remoteLabel) ?: local

    // Commands to another device are HTTP requests, so none of them may run on
    // the main thread; the screen redraws when the cluster update comes back.
    val remoteScope = rememberCoroutineScope()
    val onRemote: ((String) -> Unit) -> Unit = { action ->
        remote?.deviceId?.let { id -> remoteScope.launch(Dispatchers.IO) { action(id) } }
    }

    // Playback coming back from another device: the queue it was playing is
    // opened here and then wound forward to where it had got to. The seek is a
    // second step because starting a queue is always a start, and the position
    // belongs to the track rather than to the act of playing it.
    LaunchedEffect(Unit) {
        viewModel.resumeHere.collect { resume ->
            onPlay(
                resume.tracks,
                resume.index,
                resume.contextUri,
                resume.contextUri != null,
                "",
                // Straight into the load: the track starts where it was left.
                resume.positionMs,
            )
        }
    }

    // Held as State, not read here: reading the position at this level would
    // recompose the whole app — lists included — several times a second.
    val localPosition = rememberPositionMs(player, local.isPlaying)
    val remotePosition = rememberRemotePositionMs(remote)
    val positionMs = if (remote != null) remotePosition else localPosition
    val queueSource = rememberQueue(player)
    val videoOn by YouTubeVideoMode.enabled.collectAsStateWithLifecycle()

    // Video keeps playing when the app leaves the foreground, surface or no
    // surface. Switching back to the audio-only stream would be tidier, but the
    // stream has to reopen to do it and that break is audible — which, for
    // someone who put the phone down to keep listening, is the one thing
    // leaving the app must not do.

    // Speed and pitch, written to the player only once the slider settles.
    //
    // A drag produces a value every frame, and each one reconfigures the audio
    // sink: on the ExoPlayer the YouTube source uses, that means a flush every
    // 8ms, which starves the output — the sound stops and the position falls
    // back to where it stalled. The wait is short enough not to be felt after
    // letting go, and the slider draws its own value meanwhile.
    val effectsScope = rememberCoroutineScope()
    var effectsJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val setPlaybackParameters: (PlaybackParameters) -> Unit = { params ->
        effectsJob?.cancel()
        effectsJob = effectsScope.launch {
            kotlinx.coroutines.delay(120)
            player?.playbackParameters = params
        }
    }
    // The playing record's own artwork, asked for once per track and cached.
    val nowPlayingArt by viewModel.nowPlayingArt.collectAsStateWithLifecycle()
    val nowPlayingArtPending by viewModel.nowPlayingArtPending.collectAsStateWithLifecycle()

    // The app's one accent, taken from the same picture the pages are tinted
    // by.
    //
    // It used to be read off Spotify's square print while the background was
    // taken from the other catalogue's — two colours from two photographs of
    // the same record, and on a cover where they disagree the bar came out
    // brown over a grey page. The catalogue's own colour for the record wins
    // where there is one: it was chosen for this page rather than measured off
    // a photograph, which is the same reason a record's own page uses it.
    val playingArt = nowPlayingArt?.heroUrl
        ?: nowPlayingArt?.coverUrl
        ?: playback.artworkUrl
    val playingAccent by rememberArtworkColor(playingArt)
    val accent = nowPlayingArt?.bgColor
        ?.let { hex ->
            runCatching { Color(android.graphics.Color.parseColor("#$hex")) }.getOrNull()
        }
        ?: playingAccent

    // How bright the page behind the glass is, from the artwork rather than
    // from the screen.
    //
    // The library's own adaptive-luminance demo photographs its backdrop every
    // frame and reads the pixels back to average them, which on a bar that is
    // always on screen is a capture and a GPU-to-CPU copy for ever. The colours
    // of the cover playing are already extracted for the aura and the accent,
    // and they answer the same question: is what is behind this light or dark.
    val palette by dev.lelonio.square.ui.theme.rememberArtworkPalette(playback.artworkUrl)
    val backdropLuminance by animateFloatAsState(
        targetValue = remember(palette) {
            if (palette.isEmpty()) 0f else palette.map { it.luminance() }.average().toFloat()
        },
        animationSpec = tween(600),
        label = "backdropLuminance",
    )
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
    val searchTrail by viewModel.searchTrail.collectAsStateWithLifecycle()
    val webApi by viewModel.webApi.collectAsStateWithLifecycle()
    val reverb by AudioEffects.reverb.collectAsStateWithLifecycle()
    val presets by viewModel.effectPresets.collectAsStateWithLifecycle()
    val feed by viewModel.feed.collectAsStateWithLifecycle()

    /**
     * What the radio tab builds its stations from.
     *
     * One song per artist: the account's most played tracks name the same
     * handful of people over and over, and four cards saying "Radio of X" is
     * not a page of stations. Spotify tracks only — a station is a context on
     * Spotify's access point and means nothing on any other backend.
     */
    val newPage by viewModel.newPage.collectAsStateWithLifecycle()

    LaunchedEffect(playback.mediaId, playback.title, playback.album, playback.artist) {
        viewModel.loadNowPlayingArt(
            uri = playback.mediaId,
            title = playback.title,
            album = playback.album,
            artist = playback.artist,
        )

        // And the one after it, while there is time to spare. Read off the
        // player's own timeline rather than the queue list: the media item
        // carries the record's name, which is what the other catalogue is
        // searched by, and the list does not. See prefetchArt.
        val next = runCatching {
            val at = player?.nextMediaItemIndex ?: androidx.media3.common.C.INDEX_UNSET
            if (at == androidx.media3.common.C.INDEX_UNSET) null
            else player?.getMediaItemAt(at)?.mediaMetadata
        }.getOrNull()
        if (next != null) {
            viewModel.prefetchArt(
                title = next.title?.toString().orEmpty(),
                album = next.albumTitle?.toString().orEmpty(),
                artist = next.artist?.toString().orEmpty(),
            )
        }
    }

    // Everything the account has actually touched, one station per artist.
    //
    // It used to be the two lists of top tracks and nothing else, and those are
    // computed monthly at best: the page was the same fourteen names for weeks
    // at a time. These sources move at different speeds — what was played this
    // hour, what was searched for, what came out this week, what is on repeat
    // this month, what has been on repeat for years — so between them there is
    // always something the page did not show yesterday.
    // Spotify's own browse pages, one tab each; see loadBrowse. Filtering the
    // home page's rows into these two tabs was the first attempt and it only
    // moved the same rows around the app.
    val newShelves by viewModel.newBrowse.collectAsStateWithLifecycle()
    val browseLoading by viewModel.browseLoading.collectAsStateWithLifecycle()
    val radioShelves by viewModel.radioBrowse.collectAsStateWithLifecycle()

    val radioSeeds = remember(recent, searchHistory, newPage.songs, feed.topTracks, feed.allTimeTracks) {
        (recent + feed.topTracks + searchHistory + newPage.songs + feed.allTimeTracks)
            .filter { it.uri.startsWith("spotify:track:") && it.artist.isNotBlank() }
            // By address as well as by artist: the same track can arrive from
            // two of these lists at once, and a lazy list crashes on a repeated
            // key rather than drawing it twice. (From HectorZL's #19.)
            .distinctBy { it.artist.lowercase().trim() }
            .distinctBy { it.uri }
            // Turned over daily rather than shuffled: a page that rearranges
            // itself every time it is opened is not richer, it is unreadable.
            // The same list, started at a different name each day.
            .let { seeds ->
                if (seeds.isEmpty()) seeds
                else {
                    val day = java.time.LocalDate.now().toEpochDay().toInt()
                    val from = Math.floorMod(day * DAILY_TURN, seeds.size)
                    seeds.drop(from) + seeds.take(from)
                }
            }
            .take(RADIO_SEEDS)
    }
    val playlistOrder by viewModel.playlistOrder.collectAsStateWithLifecycle()
    val pinnedPlaylists by viewModel.pinnedPlaylists.collectAsStateWithLifecycle()
    val likedTracks by viewModel.likedTracks.collectAsStateWithLifecycle()
    val friends by viewModel.friends.collectAsStateWithLifecycle()
    val addFriendState by viewModel.addFriendState.collectAsStateWithLifecycle()
    val followedArtists by viewModel.followedArtists.collectAsStateWithLifecycle()
    val savedAlbums by viewModel.savedAlbums.collectAsStateWithLifecycle()
    val videoFileId by viewModel.videoFileId.collectAsStateWithLifecycle()
    val spotifyVideoOn by dev.lelonio.square.backend.spotify.SpotifyVideoMode.enabled
        .collectAsStateWithLifecycle()
    val spotifyVideoGeneration by dev.lelonio.square.backend.spotify.SpotifyVideoMode.generation
        .collectAsStateWithLifecycle()

    val liveQueue by queueSource

    // What was queued before the video took over.
    //
    // A video is one item: the player that shows it has a timeline of one, and
    // the queue would read as empty for as long as it plays. The songs waiting
    // are still waiting — the engine holding them is parked, not stopped — so
    // the list keeps showing them rather than lying about it.
    var queuedBeforeVideo by remember {
        mutableStateOf<List<dev.lelonio.square.ui.player.QueueEntry>>(emptyList())
    }
    val queue = if (spotifyVideoOn) queuedBeforeVideo else liveQueue
    LaunchedEffect(spotifyVideoOn, liveQueue) {
        if (!spotifyVideoOn) queuedBeforeVideo = liveQueue
    }

    // And whether there is anywhere to skip to, which is the same question.
    //
    // The video's own player has one item and answers no to both, so the
    // buttons went grey over a queue that was still there. Skipping leaves the
    // video anyway — see SpotifyVideoMode.skip — so what they should show is
    // what the queue behind it can do.
    var skipsBeforeVideo by remember { mutableStateOf(false to false) }
    LaunchedEffect(spotifyVideoOn, playback.hasNext, playback.hasPrevious) {
        if (!spotifyVideoOn) skipsBeforeVideo = playback.hasNext to playback.hasPrevious
    }
    val playerState = if (spotifyVideoOn) {
        playback.copy(
            hasNext = skipsBeforeVideo.first,
            hasPrevious = skipsBeforeVideo.second,
        )
    } else {
        playback
    }

    // Asked once per track: almost no song has a video, and the answer is two
    // catalogue calls rather than something the account volunteers.
    LaunchedEffect(playback.mediaId) {
        awaitAudible(localState)
        viewModel.lookUpVideo(playback.mediaId)
    }

    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val addToPlaylist by viewModel.addToPlaylist.collectAsStateWithLifecycle()
    val trackSort by viewModel.trackSort.collectAsStateWithLifecycle()
    val trackSortDescending by viewModel.trackSortDescending.collectAsStateWithLifecycle()
    val onboarded by viewModel.onboarded.collectAsStateWithLifecycle()

    // Asked for again from the settings, after it has already been finished.
    var showTutorial by remember { mutableStateOf(false) }

    /** The Google sign-in web view, opened from the settings. */
    var showYouTubeLogin by remember { mutableStateOf(false) }

    // The open track menu, if any. Held here because the menu is drawn above
    // everything the app puts over its screens.
    var trackMenu by remember { mutableStateOf<TrackMenuRequest?>(null) }
    // The playlist a long press opened the actions for.
    var playlistMenu by remember { mutableStateOf<CatalogPlaylist?>(null) }
    // What the menu may offer for the playlist it is open on. Defaults say yes,
    // because the library's own rows are the account's own library: only the
    // detail page, which can be a playlist belonging to anyone, ever says no.
    /** The page whose downloads are about to be given back; see the menu. */
    var unkeeping by remember { mutableStateOf<MainViewModel.PlaylistState?>(null) }
    var playlistMenuMine by remember { mutableStateOf(true) }
    var playlistMenuSaved by remember { mutableStateOf(true) }
    var deleting by remember { mutableStateOf<CatalogPlaylist?>(null) }
    // A song somebody sent, waiting to be looked at; see SharedTrackCard.
    var sharedTrack by remember { mutableStateOf<CatalogTrack?>(null) }
    // Whether the friend list is open; the faces in the header open it.
    var friendsOpen by remember { mutableStateOf(false) }
    // Open when a name is being asked for; the playlist is null when the name is
    // for one that does not exist yet.
    var naming by remember { mutableStateOf<NamingRequest?>(null) }

    // Once, on the first composition that has a usable Web API session. The
    // ViewModel keeps what it fetched, so navigating away and back does not
    // spend the quota again.
    LaunchedEffect(webApi.connected) {
        if (webApi.connected) {
            viewModel.loadFeed()
            viewModel.loadProfile()
        }
    }
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

    // The foot of the Canvas as it plays, which the player's field is made of
    // while there is one; see AmbientArtworkBackground.
    var clipColumns by remember { mutableStateOf<List<Color>>(emptyList()) }

    // What the player's field is made of, worked out once.
    //
    // Both the background and the player itself need it: one paints it, and the
    // other has to know whether what it is writing on is light or dark. It was
    // computed inside the background's own lambda, where the player could not
    // see it — and the player was choosing its ink from the phone's setting
    // instead, which is how a dark Canvas ended up with a near-black progress
    // bar drawn on it in the light setting.
    val ambientArt = nowPlayingArt?.heroUrl
        ?: nowPlayingArt?.coverUrl
        ?: playback.artworkUrl
    val ambientFoot by rememberArtworkFootColor(ambientArt)
    val ambientAccent by rememberArtworkColor(
        ambientArt.takeIf { nowPlayingArt?.bgColor == null },
    )
    val playerTone = when {
        nowPlayingArt?.bgColor != null ->
            pageColorForHex(nowPlayingArt?.bgColor, lift = false)
        ambientFoot != null -> footToneFor(ambientFoot, lift = false)
        else -> pageColorFor(ambientAccent)
    }

    // And the ink that field asks for.
    //
    // Damped a little first: the lower half of the player carries a black wash
    // for legibility, so the colour the transport actually sits on is darker
    // than the field's own — deciding on the undamped colour left dark ink on
    // a field that had since been taken down towards black.
    val playerInk = dev.lelonio.square.ui.theme.inkOn(
        androidx.compose.ui.graphics.lerp(
            if (clipColumns.isNotEmpty()) clipColumns.mean() else playerTone,
            Color.Black,
            0.22f,
        ),
    )

    // Emptied by the track change itself, not by the answer about the new
    // track's Canvas.
    //
    // A song with no clip left the last one's colours on the screen: the reset
    // waited for the player to report that this track has no Canvas, and the
    // clip that was leaving went on sampling itself for the length of its own
    // crossfade — so whichever arrived last won, and it was usually the clip.
    // The track changing is the one moment that is certainly true.
    LaunchedEffect(playback.mediaId) { clipColumns = emptyList() }


    // What the player's own glass refracts: the ambient field made from the
    // record, and nothing of the page underneath.
    //
    // The panes in the player used to sample `artBackdrop`, which is the page's
    // flat tone — drawn behind a screen that entirely covers it. So the pill
    // under the title and the transport bent a colour that was not on screen,
    // and sat on the ambient without ever picking anything up from it.
    val playerBackdrop = rememberLayerBackdrop()

    // Everything the modals cover: the screens, the bars *and* the player. The
    // sheets used to sample `pageBackdrop`, which stops at the navigation host,
    // so opening one over the player blurred the home page behind it instead of
    // the player it was opened from.
    val overlayBackdrop = rememberLayerBackdrop()

    // Where the player was left, read before anything is drawn.
    //
    // The activity is destroyed while the app sits in the background and the
    // service keeps playing, so coming back from the launcher rebuilds this
    // composition from nothing. Starting the animation at rest in the open
    // position makes the first frame the player itself; waiting for the media
    // controller to connect and *then* animating is what showed the home page
    // for a moment first.
    val preferences = remember(context) {
        (context.applicationContext as dev.lelonio.square.SquareApplication).preferences
    }

    /** False only until the source has been picked once; see BackendChoiceScreen. */
    val backendChosen by preferences.backendChosen.collectAsStateWithLifecycle()
    val backend by preferences.backend.collectAsStateWithLifecycle()
    val youtubeHome by viewModel.youtubeHome.collectAsStateWithLifecycle()
    // What is kept on the phone. Read here rather than inside the pages that
    // draw it, because the same answer is wanted by the playlist button, the
    // track rows and the track menu, and one collection is one recomposition.
    val downloadOwners by viewModel.downloadOwners.collectAsStateWithLifecycle()
    val downloadedFiles by viewModel.downloadedFiles.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val downloadSingles by viewModel.downloadSingles.collectAsStateWithLifecycle()
    // Whether only what is on the phone can be played. Read once here: half the
    // screens ask, and every one of them asks the same question.
    val offlineNow by dev.lelonio.square.playback.OfflineMode.active
        .collectAsStateWithLifecycle()

    // Once the source is YouTube Music. Keyed on the backend so switching to it
    // at runtime fills the page rather than leaving yesterday's empty one.
    //
    // And the library with it, which is read from whichever backend is active:
    // switching source used to leave the other service's playlists on screen
    // until the app was restarted.
    var loadedBackend by remember { mutableStateOf(backend) }
    LaunchedEffect(backend) {
        viewModel.loadYouTubeHome()
        if (backend != loadedBackend) {
            loadedBackend = backend
            viewModel.refresh()
        }
    }

    // How far the player is open, 0 to 1. A value rather than a destination:
    // see NowPlayingSheet for why the player stopped being a route.
    val expand = remember { Animatable(if (preferences.playerWasOpen()) 1f else 0f) }

    // Open and standing still. Derived rather than read straight, so what
    // depends on it recomposes twice a journey instead of on every frame of
    // one; see the player's ambient background.
    val playerSettled by remember {
        derivedStateOf { expand.value > 0.999f && !expand.isRunning }
    }
    val scope = rememberCoroutineScope()

    // Remembered for the next launch. Written when the animation settles rather
    // than on every frame of the drag.
    LaunchedEffect(Unit) {
        snapshotFlow { expand.isRunning to expand.value }
            .collect { (running, value) ->
                if (!running) preferences.setPlayerOpen(value > 0.5f)
            }
    }

    // Nothing to be open onto: the queue was cleared, or the saved session did
    // not survive. Closed without animating, so it is never seen.
    LaunchedEffect(player, playback.hasItem) {
        if (player != null && !playback.hasItem && expand.value > 0f) {
            expand.snapTo(0f)
        }
    }

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
    // Keyed on the metadata as well as the address, and refusing a nameless
    // track.
    //
    // A session arrives in pieces: the uri first, the title and the cover a
    // moment later. Recorded on the uri alone, what landed in the history was a
    // row with no name and no picture, which is the ghost you could not tap
    // away. Waiting for a title costs nothing, because the same effect runs
    // again when it arrives. (From HectorZL's #19.)
    LaunchedEffect(playback.mediaId, playback.title, playback.artworkUrl) {
        val uri = playback.mediaId ?: return@LaunchedEffect
        if (playback.title.isBlank()) return@LaunchedEffect
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
    // Canvas is Spotify's own, served by its access point: on another source
    // there is nobody to ask.
    // Turned off, no clip is ever asked for — which is the point of the switch:
    // it saves the video as well as hiding it.
    val canvasEnabled by preferences.canvasEnabled.collectAsStateWithLifecycle()

    LaunchedEffect(playback.mediaId, backend, canvasEnabled, offlineNow) {
        val uri = playback.mediaId
        canvas = null
        // Offline means offline, including for the things that are only
        // decoration: nothing here is fetched with the mode on — whether the
        // listener set it or the signal went — and what shows then is the copy
        // that came down with the download.
        if (uri != null &&
            canvasEnabled &&
            backend == dev.lelonio.square.backend.BackendId.SPOTIFY
        ) {
            // After the song, not beside it: a Canvas is a video, and fetching
            // one while the track is still arriving takes the connection the
            // track needs. See awaitAudible. Only wait when playing locally.
            if (remote == null) {
                awaitAudible(localState)
            }
            // The copy kept beside a download first, and not only offline: it
            // is the same clip, it is already here, and playing it costs
            // nothing. Offline it is the only one there is — the answer names a
            // url on a CDN that cannot be reached, so without this a downloaded
            // Canvas was a video sitting unplayed on the phone.
            canvas = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                dev.lelonio.square.download.DownloadExtras.localCanvas(uri)
            } ?: if (offlineNow) null else Catalog.canvas(uri)
        }
    }

    val credits by viewModel.credits.collectAsStateWithLifecycle()
    val creditsLoading by viewModel.creditsLoading.collectAsStateWithLifecycle()

    var lyrics by remember { mutableStateOf<Lyrics?>(null) }

    /**
     * The track [lyrics] is the answer for.
     *
     * A flag set at the top of the fetch is not enough to say whether an answer
     * is in yet: between the track changing and the effect that fetches for it
     * starting, the flag still reads as it did for the track before — long
     * enough for the panel to say a song has no words before anybody has asked.
     * Naming the track the answer belongs to leaves no such moment.
     */
    var lyricsFor by remember { mutableStateOf<String?>(null) }

    // One small question per track: is this one saved? The player draws a heart
    // from the answer, and there is no other way to know without reading the
    // whole of Liked Songs.
    LaunchedEffect(playback.mediaId) { viewModel.checkLiked(playback.mediaId) }

    // Fetched per track. Most of the catalogue has none, so a null result is an
    // ordinary answer that shows an empty state rather than an error.
    LaunchedEffect(playback.mediaId) {
        val uri = playback.mediaId
        lyrics = null
        lyricsFor = null
        if (uri == null) {
            lyricsFor = null
            return@LaunchedEffect
        }
        val isRemote = remote != null
        if (!isRemote) {
            // Behind the song as well; see awaitAudible. The panel shows its own
            // spinner meanwhile, so the wait is visible rather than blank.
            awaitAudible(localState)
        }

        // Read metadata from active playback state (supporting both Spotify Connect
        // remote playback and local player playback).
        val currentTitle = if (isRemote) playback.title else localState.value.title.ifBlank { playback.title }
        val currentArtist = if (isRemote) playback.artist else localState.value.artist.ifBlank { playback.artist }
        val currentDuration = if (isRemote && playback.durationMs > 0) {
            playback.durationMs
        } else {
            localState.value.durationMs.takeIf { it > 0 } ?: playback.durationMs
        }

        if (playback.mediaId != uri && localState.value.mediaId != uri) return@LaunchedEffect

        lyrics = runCatching {
            (context.applicationContext as dev.lelonio.square.SquareApplication)
                .activeBackend
                .lyrics(
                    uri = uri,
                    title = currentTitle,
                    artist = currentArtist,
                    durationMs = currentDuration,
                )
        }.getOrNull()

        // Only if this is still the song being played. Dismiss the spinner by assigning lyricsFor.
        if (playback.mediaId == uri || localState.value.mediaId == uri) {
            lyricsFor = uri
        } else {
            lyrics = null
        }
    }

    // The language the app is read in. Changing it re-creates the activity,
    // which is the only way a Compose tree already built out of one set of
    // resources can be rebuilt out of another.
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
    val trendingLabel = stringResource(R.string.trending_now)
    val newLabel = stringResource(R.string.tab_new)
    val searchLabel = stringResource(R.string.search)
    val radioLabel = stringResource(R.string.radio)
    // Resolved here rather than at the tap: a composable's resources are not
    // reachable from inside a coroutine started by a click.
    val radioOfTemplate = stringResource(R.string.radio_of)
    val radioOf: (String) -> String = { title ->
        if (title.isBlank()) radioLabel else radioOfTemplate.format(title)
    }

    val inPip by YouTubeVideoMode.pictureInPicture.collectAsStateWithLifecycle()

    // In a floating window the app is the picture and nothing else.
    //
    // Drawing the whole interface into a window that size would put a home page,
    // a tab bar and a player's worth of controls into a thumbnail, none of it
    // legible and none of it reachable — the system owns the gestures there.
    if (inPip) {
        SquareTheme(seed = accent) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                // The key matters for a Spotify video: its player is built
                // fresh when the mode is entered, and a surface attached to the
                // one before it shows nothing at all.
                player?.let {
                    dev.lelonio.square.ui.player.VideoSurface(
                        it,
                        attachKey = if (spotifyVideoOn) spotifyVideoGeneration else null,
                    )
                }
            }
        }
        return
    }

    // Turned on its side while a video plays, the phone is a screen.
    //
    // Nobody rotates a music player to read a queue sideways; they rotate it
    // because they are watching something. So landscape gives the picture the
    // whole display and nothing else, the way every video app does — and
    // turning back brings the player exactly as it was, since none of this
    // touches what is playing.
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    if (landscape && (videoOn || spotifyVideoOn) && player != null) {
        SquareTheme(seed = accent) {
            dev.lelonio.square.ui.player.FullScreenVideo(
                player = player,
                attachKey = spotifyVideoGeneration,
                state = playerState,
                positionMs = positionMs,
                onTogglePlay = { player.togglePlay() },
                onNext = {
                    if (spotifyVideoOn) {
                        dev.lelonio.square.backend.spotify.SpotifyVideoMode.skip(forward = true)
                    } else {
                        player.seekToNextMediaItem()
                    }
                },
                onPrevious = {
                    if (spotifyVideoOn) {
                        dev.lelonio.square.backend.spotify.SpotifyVideoMode.skip(forward = false)
                    } else {
                        player.seekToPreviousMediaItem()
                    }
                },
                onSeek = { player.seekTo(it) },
                onToggleShuffle = { player.shuffleModeEnabled = !player.shuffleModeEnabled },
                onCycleRepeat = {
                    player.repeatMode = when (player.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                },
            )
        }
        return
    }

    androidx.compose.runtime.CompositionLocalProvider(
        dev.lelonio.square.ui.glass.LocalBackdropLuminance provides backdropLuminance,
    ) {
    SquareTheme(seed = accent) {
        // Material's default content colour is black, and it used to arrive from
        // the Surface that wrapped this tree. That Surface is gone — it painted
        // an opaque page over the backdrop — so the colour has to be provided
        // here, or every Text that does not set one explicitly stays black on
        // the darkened artwork.
        // What glass is, once, for everything made of it.
        //
        // This used to be provided around the bottom bar alone, because the bar
        // was the only thing asking. Every other surface then quietly used the
        // library's built-in defaults, which is how the settings below could
        // move a slider and change nothing outside the bar.
        val glassStore = remember(context) {
            (context.applicationContext as dev.lelonio.square.SquareApplication).glass
        }
        val glassConfig by glassStore.config.collectAsStateWithLifecycle()

        // Asking to read the music on the phone.
        //
        // Held at this level rather than in the shelf that needs it: a launcher
        // belongs to the activity, and the answer has to reach the view model
        // whether or not the screen that asked is still on top when it arrives.
        val askLocalPermission = rememberLocalAudioPermission(
            onAnswered = viewModel::onLocalPermissionAnswered,
        )

        // What folds the bar, and what tells the glass the page is moving. Built
        // here rather than beside the bar because both of those are read at the
        // top of the app: the glass configuration is provided from this scope.
        // The bar never folds itself: what folds it is the connection below,
        // which knows something the library's own does not — whether the page
        // has any more room above it.
        val tabBarScroll = rememberFloatingTabBarScrollConnection(
            inlineBehavior = dev.lelonio.square.ui.glass.floatingtabbar
                .FloatingTabBarInlineBehavior.Never,
        )
        val foldThresholdPx = with(LocalDensity.current) { 50.dp.toPx() }
        // Beside the bar's own rather than inside it; see ScrollActivity.
        val scrollActivity = remember(tabBarScroll, foldThresholdPx) {
            dev.lelonio.square.ui.glass.ScrollActivity(
                onFold = { tabBarScroll.inline() },
                // Only at the top. Scrolling up in the middle of a list leaves
                // the bar where it is: it went away because the reader is
                // reading, and it comes back when they have finished.
                onTop = { tabBarScroll.expand() },
                foldThresholdPx = foldThresholdPx,
            )
        }
        // Held as a lambda so a scroll starting or stopping costs no
        // recomposition of the app: the surfaces ask during draw.
        // And still again while a screen is arriving or leaving. A push is not a
        // scroll, so the freeze above never covered it, and every navigation
        // re-recorded the whole page for each frame of its own animation. Keyed
        // on the route and on the open page, because the detail screen swaps its
        // contents without the route changing at all.
        val navFreeze = remember { dev.lelonio.square.ui.glass.NavTransitionFreeze() }
        val pageMoving = remember(scrollActivity, navFreeze) {
            { scrollActivity.scrolling || navFreeze.frozen() }
        }

        CompositionLocalProvider(
            LocalContentColor provides Ink,
            dev.lelonio.square.ui.glass.LocalGlassEffectConfig provides glassConfig,
            dev.lelonio.square.ui.glass.LocalGlassFrozen provides pageMoving,
        ) {
            Box(Modifier.fillMaxSize()) {
                val navController = rememberNavController()
                val currentEntry by navController.currentBackStackEntryAsState()
                val route = currentEntry?.destination?.route

                // Every arrival starts the freeze window above. Keyed on the
                // page as well as the route: the detail screen changes what it
                // is showing without the route changing at all.
                LaunchedEffect(route, playlist.uri) { navFreeze.mark() }

                // Which tab the bar shows as the current one.
                //
                // Not the route: a playlist, an album and an artist are pages
                // pushed over a tab rather than tabs of their own, and asking the
                // bar to select a key it has no tab for left it with nothing
                // selected — you opened an album from the library and the library
                // stopped being where you were. A page opened from a tab belongs
                // to it until you choose another one, which is also what makes
                // the back arrow's destination legible before you press it.
                // Only the two that have one: search is a circle beside the tabs
                // and grows into the field rather than being somewhere you are,
                // so it is the bar's search mode that shows it, not a selection.
                // Naming it here instead left a page opened from a search result
                // with the search key selected and no tab to put it on — nothing
                // lit again, by a different route.
                var activeTab by rememberSaveable { mutableStateOf(Routes.HOME) }
                /**
                 * Whether the bar is currently the search field.
                 *
                 * Not the same question as "is the search page on screen": the
                 * page stays while the field goes, which is what tapping the
                 * search tab a second time does — the results are still there to
                 * read, the keyboard and the field are out of the way, and the
                 * bar is a bar again. Reset on the way out of the page, so
                 * coming back to search opens typing-ready as it did the first
                 * time.
                 */
                var searchOpen by rememberSaveable { mutableStateOf(false) }

                LaunchedEffect(route) {
                    if (route in TAB_ROUTES) activeTab = route.orEmpty()
                    if (route != Routes.SEARCH) searchOpen = false
                }

                val focus = androidx.compose.ui.platform.LocalFocusManager.current
                // Clearing the focus is usually enough to put the keyboard away,
                // but not on every phone: this asks for it directly.
                val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

                // Read here as well as at the bar: what the page does about the
                // keyboard depends on it, and the bar is built much further down.
                val searching = route == Routes.SEARCH

                // A link from outside.
                //
                // A song is offered rather than started. Whoever sent it may
                // have meant "listen to this now" or "keep this", and a link
                // that begins playing over whatever was already on takes the
                // choice away: the card shows what the link is, and both
                // answers are one tap.
                //
                // Everything else is a page, and the page opens in front of
                // whatever was on screen.
                LaunchedEffect(link) {
                    val uri = link?.uri ?: return@LaunchedEffect
                    if (uri.startsWith("spotify:track:")) {
                        sharedTrack = viewModel.resolveTrack(uri)
                    } else {
                        viewModel.openLink(uri)
                        if (navController.currentDestination?.route != Routes.PLAYLIST) {
                            navController.navigate(Routes.PLAYLIST) { launchSingleTop = true }
                        }
                    }
                }

                // Back to the home page when the source changes: an open
                // playlist, a search or a detail page all belong to the
                // catalogue that was just swapped out.
                var lastBackend by remember { mutableStateOf(backend) }
                // Held up over the switch, so the page changing catalogue
                // underneath is something that happens behind a curtain rather
                // than a screen half of one source and half of the other.
                var switching by remember { mutableStateOf(false) }
                LaunchedEffect(backend) {
                    if (backend != lastBackend) {
                        lastBackend = backend
                        switching = true
                        navController.popBackStack(Routes.HOME, inclusive = false)
                        // Held until the new source has actually answered, so
                        // what appears behind it is a page rather than a page
                        // being built — with a floor, so the splash is never a
                        // flash, and a ceiling, because a cold session can take
                        // longer than anyone will stare at a logo.
                        kotlinx.coroutines.withTimeoutOrNull(3_000) {
                            kotlinx.coroutines.delay(600)
                            snapshotFlow { state }
                                .first { it !is MainViewModel.UiState.Loading }
                        }
                        switching = false
                    }
                }

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
                // The playlist sheet belongs in here too. Left out, the layer
                // it refracts was not being recorded while it was the only
                // thing open, so the glass had nothing behind it and the sheet
                // came up as a transparent panel with no depth at all.
                // The update sheet is glass like the rest, so it has to be
                // counted here too: the layer below is only recorded while
                // something is reading it, and a panel over an unrecorded
                // backdrop has nothing to refract — it came out flat.
                var updateOpen by remember { mutableStateOf(false) }
                val sheetsOpen = trackMenu != null || addToPlaylist.open || playlistMenu != null ||
                    sharedTrack != null || friendsOpen || updateOpen
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
                        )
                        ,
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .layerBackdrop(artBackdrop),
                    ) {
                        // The playing record as the other catalogue draws it:
                        // its own page colour where there is one, and its own
                        // picture to take a colour from where there is not. The
                        // same two sources a record's page uses, so home and
                        // library are tinted exactly as the page you reach from
                        // them — instead of a duller colour off Spotify's
                        // thumbnail, which is what made them look like a
                        // different app's screens.
                        AppBackdrop()
                    }

                    Box(
                        Modifier
                            .nestedScroll(tabBarScroll)
                            .nestedScroll(scrollActivity)
                            // Touching the page puts the keyboard away.
                            //
                            // The field is in the bar at the bottom of the screen
                            // and the keyboard is under it, so the results are the
                            // only thing left to touch — and touching a result
                            // while the keyboard stays up is the moment the
                            // listener has finished typing. Nothing is consumed
                            // here: the tap goes on to whatever it landed on, and
                            // this only takes the focus with it.
                            .pointerInput(searching) {
                                if (!searching) return@pointerInput
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    focus.clearFocus()
                                }
                            },
                    ) {
                    NavHost(
                        navController,
                        startDestination = Routes.HOME,
                        // Two different movements, because there are two
                        // different kinds of navigation here. Opening a page is
                        // a push: it comes in from the edge and the page under
                        // it stays put and dims, so the new one reads as having
                        // arrived over the old. Changing tab is not a push —
                        // neither place is on top of the other — so those
                        // crossfade, which is what the reference does too.
                        enterTransition = {
                            if (targetState.destination.route in TAB_ROUTES ||
                                targetState.destination.route == Routes.SEARCH
                            ) {
                                fadeIn(tween(180))
                            } else {
                                slideInHorizontally(tween(280)) { it / 5 } + fadeIn(tween(220))
                            }
                        },
                        exitTransition = { fadeOut(tween(180)) },
                        popEnterTransition = { fadeIn(tween(200)) },
                        popExitTransition = {
                            slideOutHorizontally(tween(260)) { it / 5 } + fadeOut(tween(200))
                        },
                    ) {
                        composable(Routes.HOME) {
                            LaunchedEffect(Unit) {
                                viewModel.loadBrowse()
                            }
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
                                    onPlay(tracks, index, null, false, playAgainLabel, 0L)
                                },
                                onPlayFeed = { tracks, index, label ->
                                    onPlay(tracks, index, null, false, label, 0L)
                                },
                                feed = feed,
                                onOpenItem = { item ->
                                    viewModel.openContext(item.uri, item.title, item.artworkUrl)
                                    if (navController.currentDestination?.route != Routes.PLAYLIST) {
                                        navController.navigate(Routes.PLAYLIST) { launchSingleTop = true }
                                    }
                                },
                                backdrop = artBackdrop,
                                youtubeMode =
                                    backend == dev.lelonio.square.backend.BackendId.YOUTUBE_MUSIC,
                                onRetryOnline = viewModel::retryOnline,
                                youtubeHome = youtubeHome,
                                shelves = homeShelves,
                                mixShelves = radioShelves,
                                onPlayTrending = { tracks, index ->
                                    onPlay(tracks, index, null, false, trendingLabel, 0L)
                                },
                                onPickYouTubeChip = viewModel::selectYouTubeChip,
                                onLoadMoreYouTube = viewModel::loadMoreYouTubeHome,
                                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                                friends = friends,
                                onOpenFriends = {
                                    friendsOpen = true
                                    // Read again as it opens: what somebody is
                                    // playing is only worth showing if it is
                                    // what they are playing now.
                                    viewModel.loadFriends()
                                },
                            )
                        }

                        composable(Routes.NEW) {
                            LaunchedEffect(Unit) {
                                viewModel.loadNewPage()
                                viewModel.loadBrowse()
                            }
                            NewScreen(
                                page = newPage,
                                shelves = newShelves,
                                shelvesLoading = browseLoading,
                                contentPadding = listPadding,
                                onPlaySong = { tracks, index ->
                                    onPlay(tracks, index, null, false, newLabel, 0L)
                                },
                                onSongMenu = { track ->
                                    trackMenu = TrackMenuRequest(track = track, removable = false)
                                },
                                onOpen = { item ->
                                    viewModel.openContext(
                                        item.uri,
                                        item.title,
                                        item.artworkUrl,
                                    )
                                    if (navController.currentDestination?.route != Routes.PLAYLIST) {
                                        navController.navigate(Routes.PLAYLIST) { launchSingleTop = true }
                                    }
                                },
                            )
                        }

                        composable(Routes.RADIO) {
                            LaunchedEffect(Unit) { viewModel.loadBrowse() }
                            RadioScreen(
                                seeds = radioSeeds,
                                shelves = radioShelves,
                                shelvesLoading = browseLoading,
                                loading = feed.loading,
                                contentPadding = listPadding,
                                onOpenMix = { mix ->
                                    viewModel.openContext(mix.uri, mix.name, mix.artworkUrl)
                                    if (navController.currentDestination?.route != Routes.PLAYLIST) {
                                        navController.navigate(Routes.PLAYLIST) { launchSingleTop = true }
                                    }
                                },
                                onOpen = { seed ->
                                    scope.launch {
                                        // Held, because everything in here can
                                        // fail: the station is fetched from a
                                        // private gateway and the page is
                                        // opened from a coroutine, and a throw
                                        // in either took the app down with it.
                                        // (From HectorZL's #19.)
                                        runCatching {
                                        val tracks = viewModel.radioFor(seed.uri)
                                        if (tracks.isEmpty()) return@launch
                                        val station = "spotify:station:track:" +
                                            seed.uri.substringAfterLast(':')
                                        val name = radioOfTemplate.format(seed.artist)
                                        // The station as a page and then as a
                                        // queue, in that order: what the tab
                                        // promises is a station to look at, not
                                        // a shuffle that starts under a page
                                        // the listener never sees.
                                        viewModel.showStation(
                                            uri = station,
                                            name = name,
                                            artworkUrl = seed.artworkUrl,
                                            tracks = tracks,
                                        )
                                        if (navController.currentDestination?.route != Routes.PLAYLIST) {
                                            navController.navigate(Routes.PLAYLIST) { launchSingleTop = true }
                                        }
                                        onPlay(tracks, 0, station, true, name, 0L)
                                        }.onFailure {
                                            android.util.Log.w(
                                                "SquareRadio",
                                                "station for ${seed.uri} failed: ${it.message}",
                                                it,
                                            )
                                        }
                                    }
                                },
                            )
                        }

                        composable(Routes.SEARCH) {
                            SearchScreen(
                                state = search,
                                onQuery = viewModel::onSearchQuery,
                                webApi = webApi,
                                contentPadding = listPadding,
                                nowPlayingUri = playback.mediaId,
                                onClientIdChange = viewModel::onWebApiClientIdChange,
                                onConnectWebApi = { viewModel.connectWebApi() },
                                onPlayTrack = { tracks, index ->
                                    tracks.getOrNull(index)?.let(viewModel::recordSearchPlay)
                                    onPlay(tracks, index, null, false, searchLabel, 0L)
                                },
                                history = searchTrail,
                                onClearHistory = viewModel::clearSearchHistory,
                                offline = offlineNow,
                                onEnqueue = onEnqueue,
                                onTrackMenu = { track ->
                                    trackMenu = TrackMenuRequest(
                                        track = track,
                                        // Nothing to take a search result out
                                        // of: it belongs to no playlist here.
                                        removable = false,
                                    )
                                },
                                onOpenContext = { item ->
                                    // Kept, like a song played from a result:
                                    // an artist looked up on Tuesday is worth
                                    // having back as much as a song is.
                                    viewModel.recordSearchOpen(item)
                                    viewModel.openContext(item.uri, item.title, item.artworkUrl)
                                    if (navController.currentDestination?.route != Routes.PLAYLIST) {
                                        navController.navigate(Routes.PLAYLIST) { launchSingleTop = true }
                                    }
                                },
                                onLoadMore = viewModel::loadMoreSearch,
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
                                pinned = pinnedPlaylists,
                                canEdit = viewModel.canEditPlaylists,
                                onCreatePlaylist = { naming = NamingRequest(null) },
                                onPlaylistMenu = { playlistMenu = it },
                                artists = followedArtists,
                                albums = savedAlbums,
                                onRetryOnline = viewModel::retryOnline,
                                onOpenArtist = { artist ->
                                    viewModel.openContext(
                                        artist.uri,
                                        artist.title,
                                        artist.artworkUrl,
                                    )
                                    if (navController.currentDestination?.route != Routes.PLAYLIST) {
                                        navController.navigate(Routes.PLAYLIST) { launchSingleTop = true }
                                    }
                                },
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
                                onYouTubeSignIn = { showYouTubeLogin = true },
                                onYouTubeChannelChange = {
                                    // A different channel is a different
                                    // library and a different home: both were
                                    // read for the one before it.
                                    viewModel.refresh()
                                    viewModel.loadYouTubeHome(force = true)
                                },
                            )
                        }

                        composable(Routes.PLAYLIST) {
                            // While this screen is up, a page opened from it is
                            // opened *over* it; once it is gone, whatever it was
                            // showing is not "the page you were on" any more.
                            DisposableEffect(Unit) {
                                viewModel.detailShown()
                                onDispose { viewModel.detailClosed() }
                            }

                            // The system gesture, answered the same way as the
                            // arrow: the page behind this one is usually inside
                            // this screen rather than under it.
                            val hasPreviousPage by viewModel.hasPreviousPage
                                .collectAsStateWithLifecycle()
                            androidx.activity.compose.BackHandler(
                                enabled = hasPreviousPage,
                            ) { viewModel.popPage() }

                            // One destination holds every detail page, so a
                            // page opening over another is not a navigation the
                            // library can animate — it is the same screen with
                            // different contents. This is that missing movement:
                            // the page being left slides out the way it came,
                            // the new one slides in from the other side, and the
                            // depth says which side that is.
                            val pageDepth by viewModel.pageDepth
                                .collectAsStateWithLifecycle()
                            androidx.compose.animation.AnimatedContent(
                                targetState = playlist to pageDepth,
                                contentKey = { (page, depth) -> page.uri to depth },
                                transitionSpec = {
                                    val deeper = targetState.second > initialState.second
                                    val from = { width: Int ->
                                        if (deeper) width / 4 else -width / 4
                                    }
                                    (
                                        slideInHorizontally(tween(280)) { from(it) } +
                                            fadeIn(tween(200))
                                        ).togetherWith(
                                        slideOutHorizontally(tween(280)) { -from(it) } +
                                            fadeOut(tween(180)),
                                    )
                                },
                                label = "detailPage",
                            ) { (page, _) ->
                            // Re-seeded from the open playlist, album or artist.
                            // The rest of the app is themed after whatever is
                            // playing, which is right for the home page and
                            // wrong here: an album page tinted by an unrelated
                            // track reads as belonging to something else.
                            val detailAccent by rememberArtworkColor(page.artworkUrl)
                            // Resolved here rather than inside the play
                            // callbacks: those are not composables.
                            val source = page.sourceLabel()
                            SquareTheme(seed = detailAccent) {
                                PlaylistScreen(
                                    state = page,
                                    offline = offlineNow,
                                    // Read per row rather than handed over as a
                                    // map: the progress map changes several
                                    // times a second while a playlist is being
                                    // fetched, and a new map is a new list.
                                    trackDownload = { track ->
                                        when {
                                            track.uri in downloadedFiles ->
                                                dev.lelonio.square.data.DownloadState.Done
                                            downloadProgress[track.uri] != null ->
                                                dev.lelonio.square.data.DownloadState.Running(
                                                    downloadProgress.getValue(track.uri),
                                                )
                                            else -> dev.lelonio.square.data.DownloadState.None
                                        }
                                    },
                                    contentPadding = listPadding,
                                    nowPlayingUri = playback.mediaId,
                                    // One route holds every detail page, so
                                    // back is the screen's own history first
                                    // and the navigation stack only once that
                                    // is empty; see MainViewModel.popPage.
                                    onBack = {
                                        if (!viewModel.popPage()) navController.popBackStack()
                                    },
                                    onAskLocalPermission = { askLocalPermission() },
                                    onPlay = { tracks, index, asContext ->
                                        onPlay(
                                            tracks,
                                            index,
                                            page.uri,
                                            asContext,
                                            source,
                                            0L,
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
                                            page.uri,
                                            false,
                                            source,
                                            0L,
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
                                            // Taking a track out goes through
                                            // the Spotify Web API, so only a
                                            // Spotify playlist can offer it.
                                            removable = page.kind ==
                                                MainViewModel.DetailKind.PLAYLIST &&
                                                backend ==
                                                dev.lelonio.square.backend.BackendId.SPOTIFY,
                                        )
                                    },
                                    storedSort = trackSort,
                                    onSortChange = viewModel::setTrackSort,
                                    storedSortDescending = trackSortDescending,
                                    onSortDescendingChange = viewModel::setTrackSortDescending,
                                    onOpenItem = { item ->
                                        viewModel.openContext(
                                            item.uri,
                                            item.title,
                                            item.artworkUrl,
                                        )
                                    },
                                    downloadState = page.uri
                                        ?.let { downloadOwners[it] }
                                        ?: dev.lelonio.square.data.OwnerState.None,
                                    onToggleDownload = { viewModel.toggleDownload(page) },
                                    // The store belongs to the librespot engine,
                                    // so only Spotify pages can be kept — and
                                    // with no network the button would only ever
                                    // queue work that cannot start.
                                    canDownload = backend ==
                                        dev.lelonio.square.backend.BackendId.SPOTIFY &&
                                        page.uri?.startsWith("spotify:") == true &&
                                        !offlineNow,
                                    onToggleFollow = viewModel::toggleFollowArtist,
                                    onToggleLatestSaved = viewModel::toggleLatestSaved,
                                    onToggleSaved = viewModel::toggleSaved,
                                    onMenu = {
                                        val uri = page.uri ?: return@PlaylistScreen
                                        // What the menu is allowed to offer, from
                                        // the page that knows: whose list this is,
                                        // and whether it is in the library at all.
                                        playlistMenuMine = page.mine != false
                                        playlistMenuSaved = page.saved != false
                                        playlistMenu = CatalogPlaylist(
                                            uri = uri,
                                            name = page.name,
                                            artworkUrl = playlist.artworkUrl,
                                        )
                                    },
                                )
                            }
                            }
                        }

                    }
                }

                }

                // Out here for the same reason as the bars below: it refracts
                // `pageBackdrop`, so it cannot be drawn inside it.
                SessionExpiredNotice(
                    visible = webApi.expired && !webApi.connecting,
                    backdrop = pageBackdrop,
                    onReconnect = viewModel::connectWebApi,
                    onDismiss = viewModel::dismissWebApiExpiry,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = statusBar + 8.dp),
                )

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
                        // The bar is where you type now, so it has to be above
                        // the keyboard: the search field lived on the page until
                        // a moment ago, where the keyboard covering the bottom of
                        // the screen cost nothing.
                        .imePadding()
                        // Hidden as the player takes over, and out of the way
                        // before it covers it: a tab bar under a full-screen
                        // player still swallows the taps meant for the
                        // transport.
                        .graphicsLayer {
                            alpha = (1f - expand.value * 3f).coerceIn(0f, 1f) * chrome
                        }
                        .onSizeChanged { barHeight = with(density) { it.height.toDp() } },
                ) {
                    // The material comes from the settings now, provided once at
                    // the top of the app; what is still local here is which
                    // backdrop the bar's surfaces sample when they are not handed
                    // one, and that has to stay local — a pane drawn inside the
                    // layer it samples recurses on the render thread until the
                    // process dies.
                    androidx.compose.runtime.CompositionLocalProvider(
                        dev.lelonio.square.ui.glass.LocalAppBackdrop provides pageBackdrop,
                    ) {
                    // One pane of glass, given to both the bar and the pill
                    // above it — which is how the reference does it: the tab bar
                    // component paints no background of its own, the caller
                    // hands it the material.
                    // Where the glass is allowed to be, from the settings. Off
                    // is not "no surface" — a transparent bar swallows the taps
                    // meant for the page under it and reads as nothing at all —
                    // but the film, which is the same thing a phone without the
                    // hardware blur gets.
                    val flat = dev.lelonio.square.ui.glass.GlassStyle.TRANSPARENT
                    val barConfig = if (glassConfig.navBarEnabled) {
                        glassConfig
                    } else {
                        glassConfig.copy(style = flat)
                    }
                    val pillConfig = if (glassConfig.miniPlayerEnabled) {
                        glassConfig
                    } else {
                        glassConfig.copy(style = flat)
                    }
                    // Nothing special while the bar changes shape.
                    //
                    // Two things were tried there and both were worse than what
                    // they fixed. Taking the glass away for the length of the
                    // fold was the cheapest and you could see it go. Holding the
                    // last capture instead was invisible while it lasted, but
                    // the bar's two states are two different pieces of
                    // composition, so the fresh capture arrives on a node that
                    // has just been created and the correction is a cut nothing
                    // can fade. The glass simply keeps working.
                    val barShape = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50)

                    val barGlass = Modifier.liquidGlass(
                        config = barConfig,
                        shape = barShape,
                        highlightAlpha = 0.3f,
                    )
                    // The same pane, on the pill above it, unless the settings
                    // have singled that one out.
                    val pillGlass = if (glassConfig.miniPlayerEnabled == glassConfig.navBarEnabled) {
                        barGlass
                    } else {
                        Modifier.liquidGlass(
                            config = pillConfig,
                            shape = barShape,
                            highlightAlpha = 0.3f,
                        )
                    }

                    // How far the player has to travel, for the pull that opens
                    // it. Read once here rather than per frame of the drag.
                    val playerTravelPx = with(density) {
                        (LocalConfiguration.current.screenHeightDp.dp - MiniPlayerHeight).toPx()
                    }
                    val accessory: (@Composable androidx.compose.animation.SharedTransitionScope.(
                        Modifier,
                        androidx.compose.animation.AnimatedVisibilityScope,
                    ) -> Unit)? = if (playback.hasItem) {
                        { accessoryModifier, _ ->
                            FloatingMiniPlayer(
                                state = playback,
                                positionMs = positionMs,
                                remoteLabel = remote?.let { remoteLabel },
                                modifier = accessoryModifier
                                    .fillMaxWidth()
                                    .then(pillGlass)
                                    // Up to open the player, as it always was.
                                    // The pill's own drag detector locks to the
                                    // horizontal after the touch slop, so a
                                    // vertical pull never reached it and the
                                    // gesture landed on nothing — the sheet's
                                    // collapsed half, which used to carry this,
                                    // is empty now that what is playing lives in
                                    // the bar.
                                    .draggable(
                                        state = rememberDraggableState { delta ->
                                            scope.launch {
                                                val travel = playerTravelPx
                                                if (travel > 0f) {
                                                    expand.snapTo(
                                                        (expand.value - delta / travel)
                                                            .coerceIn(0f, 1f),
                                                    )
                                                }
                                            }
                                        },
                                        orientation = androidx.compose.foundation.gestures.Orientation.Vertical,
                                        onDragStopped = { velocity ->
                                            scope.launch {
                                                val target = when {
                                                    velocity < -800f -> 1f
                                                    velocity > 800f -> 0f
                                                    expand.value > 0.4f -> 1f
                                                    else -> 0f
                                                }
                                                expand.animateTo(target, expandSpec)
                                            }
                                        },
                                    ),
                                inline = tabBarScroll.isInline,
                                onClick = {
                                    scope.launch { expand.animateTo(1f, expandSpec) }
                                },
                                onTogglePlay = {
                                    if (remote != null) {
                                        val playing = remote?.playing == true
                                        onRemote { id ->
                                            if (playing) RemoteConnect.pause(id)
                                            else RemoteConnect.play(id)
                                        }
                                    } else {
                                        player?.togglePlay()
                                    }
                                },
                                onNext = {
                                    if (remote != null) onRemote(RemoteConnect::next)
                                    else player?.seekToNextMediaItem()
                                },
                                onPrevious = {
                                    if (remote != null) onRemote(RemoteConnect::previous)
                                    else player?.seekToPreviousMediaItem()
                                },
                                onSeek = { positionMillis -> player?.seekTo(positionMillis) },
                            )
                        }
                    } else {
                        null
                    }

                    // Before anything is drawn for real; see GlassWarmUp.
                    dev.lelonio.square.ui.glass.GlassWarmUp(pageBackdrop)
                    // Two tabs and a circle beside them: the capsule takes what
                    // is left of the row once the circle has its 80dp, and never
                    // more than the 88dp a tab is designed for. Without this the
                    // bar stretched to the screen and the tabs floated in it.
                    // Measured off the reference, at its own density: a tab is
                    // 88dp wide, the row is inset 16 while folded and 26 while
                    // open, and everything is spaced by 8. Two tabs make a
                    // narrower capsule than its three do, and the row centres
                    // itself — which is the point of measuring the parts rather
                    // than the whole.
                    // Measured off the reference frame (314 px wide, bar 299 px
                    // of it): the bar leaves about the same margin either side
                    // as a tab is tall is wide — 17dp here once its own width is
                    // taken off the screen. Folded it pulls in slightly, which
                    // is what the reference does too.
                    // Measured off the recording of the reference running on
                    // this phone: 27 video pixels of a 720-wide capture, which
                    // is 16dp here, and the same on both sides in both states.
                    val barMargin = 16.dp

                    // What one of the four gets, once the row's own margins and
                    // the search circle beside it have been taken off. Measured
                    // from the screen rather than fixed: four labels and a circle
                    // on a narrow phone is exactly where a constant starts
                    // pushing the last tab off the end.
                    // The colour the current tab is drawn in: the artwork's
                    // own, straight from the palette rather than through the
                    // theme.
                    //
                    // The theme's primary is that colour after the material
                    // scheme has had it — pulled toward its own tonal palette,
                    // which is what made the lit tab a near-relative of the
                    // cover rather than the cover's colour. This is the same
                    // value the pages are tinted from, at full strength, which
                    // is how the reference draws the place you are.
                    // The app's accent, which is the cover's own: the theme is
                    // seeded from it through ArtworkColor and every switch,
                    // slider and progress bar in the app is drawn in it.
                    //
                    // This used to read the palette a second time here, and off
                    // the *other* catalogue's picture — so the bar could be
                    // violet while the rest of the app was orange, on the same
                    // song. One accent, taken from where the app already keeps
                    // it.
                    val tabAccent = MaterialTheme.colorScheme.primary

                    // And the white the rest of the bar is drawn in: never a
                    // pure one. The reference tints its icons with the colour of
                    // what is *behind* them — on a green page they are a green
                    // white — which is most of why the bar reads as part of the
                    // page rather than as a strip laid over it.
                    //
                    // Behind, and not what is playing. Those are two different
                    // colours as soon as you open a record while something else
                    // is on: a purple page under a pink bar is exactly the seam
                    // this is meant to remove. So on a detail page the tint is
                    // that page's own — the catalogue's colour for it where
                    // there is one, and the colour of the picture where there is
                    // not — and everywhere else it falls back to the playing
                    // track, which is what those pages are tinted by.
                    val onDetail = route == Routes.PLAYLIST
                    val detailArt = playlist.heroUrl ?: playlist.coverUrl ?: playlist.artworkUrl
                    val detailAccent by rememberArtworkColor(detailArt.takeIf { onDetail })
                    val surfaceTint = when {
                        // Off a detail page the bar is tinted by what is
                        // playing, which is what those pages are tinted by too.
                        !onDetail -> tabAccent
                        playlist.tintHex != null -> runCatching {
                            Color(android.graphics.Color.parseColor("#${playlist.tintHex}"))
                        }.getOrDefault(tabAccent)
                        else -> detailAccent ?: tabAccent
                    }
                    val barInk = inkTintedBy(surfaceTint)

                    // What the lit slot is filled with. The listener's own glass
                    // settings still own it — the puck was a surface of this
                    // app's making before the library grew one — so an opacity of
                    // zero leaves the row unmarked rather than drawing a shape
                    // nobody asked for.
                    // Darker than the bar, not lighter.
                    //
                    // The reference sinks its lit slot into the bar rather than
                    // lifting it off: measured off it, the puck reads at about
                    // three fifths of the brightness of the glass around it. A
                    // white sticker was the older idea and it made the bar look
                    // like it had a highlight stuck on it.
                    //
                    // The listener's own slider still sets how far it goes; only
                    // the direction is fixed here.
                    // Far lighter on the light side. Sinking the lit slot works
                    // over a dark bar, where the glass around it is already
                    // dark; over a white film the same wash is a solid blob
                    // with the page's colour in it, and what should be a mark
                    // reads as a button someone pressed and left down.
                    val puckWash = if (dev.lelonio.square.ui.theme.lightPage()) {
                        Color.Black.copy(
                            alpha = (glassConfig.puckOpacity.coerceIn(0f, 1f) * 0.13f)
                                .coerceAtMost(0.11f),
                        )
                    } else {
                        Color.Black.copy(
                            alpha = (glassConfig.puckOpacity.coerceIn(0f, 1f) * 1.3f)
                                .coerceAtMost(0.5f),
                        )
                    }

                    // Searching opens the bar and keeps it open.
                    //
                    // The field is in the bar, so arriving at search with the bar
                    // already folded left the search page with nowhere to type
                    // and no sign that unfolding the bar was what you had to do.
                    // And once open it stays: it used to fold on the way down the
                    // results and come back on the way up, taking the field with
                    // it each time and bringing the keyboard back up on every
                    // scroll.
                    // And the same lock serves the setting: a bar told not to
                    // fold is a bar whose scrolling is not allowed to change it.
                    val barFolds by glassStore.barFolds.collectAsStateWithLifecycle()
                    LaunchedEffect(searching, barFolds) {
                        if (searching || !barFolds) tabBarScroll.expand()
                    }

                    FloatingTabBar(
                        // The page's own tab, search included — it is one of
                        // them now. The bar keeps this tab when it folds and
                        // finds it by matching this key.
                        selectedTabKey = activeTab,
                        scrollConnection = tabBarScroll,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = barMargin)
                            .padding(bottom = navBar + 8.dp),
                        // Where the glass goes in. The library applies this after
                        // its own background and before its padding, which is
                        // exactly the seam a pane of glass belongs in: the
                        // surfaces below draw nothing of their own — see the
                        // transparent colours — and this is what they are made
                        // of.
                        tabBarContentModifier = barGlass,
                        colors = FloatingTabBarDefaults.colors(
                            // A film over the glass, not nothing.
                            //
                            // The bar was left transparent so the glass alone
                            // made it, and over a dark page that is very nearly
                            // black — which leaves a dark puck with nothing to be
                            // darker than. The reference's bar is a lighter
                            // surface for exactly this reason: it is what its lit
                            // slot is cut out of.
                            backgroundColor = GlassFilm,
                            accessoryBackgroundColor = GlassFilm,
                            selectedTabBackgroundColor = puckWash,
                            selectedStandaloneTabBackgroundColor = puckWash,
                        ),
                        sizes = FloatingTabBarDefaults.sizes(
                            // Measured off the reference running on this phone;
                            // see the notes on barMargin.
                            // What separates the lit slot from the bar's own
                            // edge. Measured off the reference: its puck stands
                            // four fifths of the bar's height, which is six
                            // points of glass above and below it.
                            tabBarContentPadding = PaddingValues(
                                vertical = 6.dp,
                                horizontal = 2.dp,
                            ),
                            // Measured off Music OS 26 on this phone, which is
                            // the bar this one is trying to be: its row stands
                            // 61dp, its glyphs are 26, and the circle beside the
                            // pill is as tall as the pill is. The library sizes
                            // that circle off the row for us, so the only
                            // numbers here are the paddings that make the row
                            // that tall.
                            tabExpandedContentPadding = PaddingValues(
                                vertical = 8.dp,
                                horizontal = 14.dp,
                            ),
                            tabInlineContentPadding = PaddingValues(14.dp),
                            componentSpacing = 14.dp,
                        ),
                        // The tab group is the catalog's own component, as it
                        // ships it: the pill, the four tabs and the indicator
                        // that can be dragged between them. The bar around it —
                        // the fold, the mini player, the search circle — is
                        // still the library's.
                        expandedTabs = { tabsModifier, tabsVisibility ->
                            // The bar's own configuration, not the app's.
                            //
                            // The component reads the glass settings from
                            // composition, and the setting that governs this
                            // surface is the one for the navigation bar — so a
                            // listener who turns the glass off there gets a flat
                            // bar, exactly as they do for the mini player and the
                            // player's own panes.
                            val tabRoutes = remember {
                                listOf(
                                    Routes.HOME,
                                    Routes.NEW,
                                    Routes.RADIO,
                                    Routes.LIBRARY,
                                    Routes.SEARCH,
                                )
                            }
                            val here = tabRoutes.indexOf(activeTab).coerceAtLeast(0)
                            // Stable, or the component throws away the state it
                            // keys on this and the indicator stops animating.
                            val hereState = rememberUpdatedState(here)
                            val selectedTabIndex = remember { { hereState.value } }
                            // The catalog's component as this app carries it:
                            // same code, with its shell made of the app's own
                            // glass so the bar answers the listener's settings
                            // like every other pane. See LiquidBottomTabs.
                            CompositionLocalProvider(
                                dev.lelonio.square.ui.glass.LocalGlassEffectConfig provides barConfig,
                            ) {
                            LiquidBottomTabs(
                                selectedTabIndex = selectedTabIndex,
                                onTabSelected = { index ->
                                    viewModel.clearPageHistory()
                                    navController.switchTab(tabRoutes[index])
                                },
                                backdrop = pageBackdrop,
                                tabsCount = tabRoutes.size,
                                accentColor = tabAccent,
                                containerColor = GlassFilm,
                                // Darker than the bar, not lighter: the mark is
                                // cut into the glass rather than stuck on it.
                                // Still the listener's own slider — at zero the
                                // row goes unmarked.
                                indicatorColor = puckWash,
                                height = BarHeight,
                                // Under half the height, or the refraction from
                                // the two long edges meets in the middle and
                                // draws a seam across the capsule.
                                lensDepth = 20.dp,
                                // Nothing is lit while the page on screen is not
                                // one of these four; search is a place of its
                                // own.
                                indicatorVisible = activeTab in TAB_ROUTES,
                                modifier = tabsModifier,
                            ) {
                                BarTab(
                                    R.string.home,
                                    PhosphorIcons.Fill.House,
                                    activeTab == Routes.HOME,
                                    tabAccent,
                                    barInk,
                                ) {
                                    viewModel.clearPageHistory()
                                    navController.switchTab(Routes.HOME)
                                }
                                BarTab(
                                    R.string.tab_new,
                                    PhosphorIcons.Fill.SquaresFour,
                                    activeTab == Routes.NEW,
                                    tabAccent,
                                    barInk,
                                ) {
                                    viewModel.clearPageHistory()
                                    navController.switchTab(Routes.NEW)
                                }
                                BarTab(
                                    R.string.tab_radio,
                                    PhosphorIcons.Fill.Broadcast,
                                    activeTab == Routes.RADIO,
                                    tabAccent,
                                    barInk,
                                ) {
                                    viewModel.clearPageHistory()
                                    navController.switchTab(Routes.RADIO)
                                }
                                BarTab(
                                    R.string.library,
                                    PhosphorIcons.Fill.MusicNotes,
                                    activeTab == Routes.LIBRARY,
                                    tabAccent,
                                    barInk,
                                ) {
                                    viewModel.clearPageHistory()
                                    navController.switchTab(Routes.LIBRARY)
                                }
                                BarTab(
                                    R.string.search,
                                    // Heavy rather than solid: a filled
                                    // magnifier reads as a blob at this size,
                                    // and it is the one glyph here whose shape
                                    // is the whole of its meaning.
                                    PhosphorIcons.Bold.MagnifyingGlass,
                                    activeTab == Routes.SEARCH,
                                    tabAccent,
                                    barInk,
                                    // This slot is the folded bar's search
                                    // circle standing in the row. Sharing its
                                    // bounds is what keeps it at the right edge
                                    // while the pill collapses to the left,
                                    // instead of shrinking away with the pill
                                    // and a circle fading in where it was — which
                                    // is what the reference does: the first and
                                    // last of the row survive the fold, the
                                    // three in between go.
                                    modifier = Modifier.sharedElement(
                                        sharedContentState = rememberSharedContentState("standaloneTab"),
                                        animatedVisibilityScope = tabsVisibility,
                                        zIndexInOverlay = 2f,
                                    ),
                                ) {
                                    viewModel.clearPageHistory()
                                    searchOpen = true
                                    navController.switchTab(Routes.SEARCH)
                                }
                            }
                            }
                        },
                        // What the tabs are built out of, so the builder runs
                        // again when it changes.
                        //
                        // The library remembers the scope and runs the content
                        // lambda once — so the icons registered in it captured
                        // the colours of the first composition and kept them for
                        // ever. That is why the folded bar's circle stayed the
                        // accent of whatever was playing at startup while the
                        // open bar followed the song, and why the search icon
                        // drifted from the rest.
                        contentKey = listOf(activeTab, searching, searchOpen, tabAccent, barInk),
                        expandedTabsHeight = BarHeight,
                        // Search is one of the places now, so the circle beside
                        // the pill would be the same control twice. It stays for
                        // the folded bar, where there is no pill to hold it.
                        standaloneInExpanded = false,
                        // Tapping the search tab grows it into the field, and
                        // the tabs fold away behind it. Tapping it again puts
                        // the field away and leaves the results; see searchOpen.
                        searchMode = searching && searchOpen,
                        searchBarContent = if (searching && searchOpen) {
                            { fieldModifier ->
                                BarSearchField(
                                    query = search.query,
                                    onQuery = viewModel::onSearchQuery,
                                    modifier = fieldModifier,
                                    ink = barInk,
                                )
                            }
                        } else {
                            null
                        },
                        tabsFillWidth = true,
                        inlineAccessory = accessory,
                        expandedAccessory = accessory,
                    ) {
                        tab(
                            key = Routes.HOME,
                            title = {
                                Text(
                                    stringResource(R.string.home),
                                    fontSize = 9.sp,
                                    lineHeight = 11.sp,
                                    style = LocalTextStyle.current.copy(
                                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                                            includeFontPadding = false,
                                        ),
                                        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                                            alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                                            trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
                                        ),
                                    ),
                                    fontWeight = FontWeight.Bold,
                                    color = if (activeTab == Routes.HOME) tabAccent else barInk,
                                )
                            },
                            icon = {
                                Icon(
                                    PhosphorIcons.Fill.House,
                                    contentDescription = stringResource(R.string.home),
                                    tint = if (activeTab == Routes.HOME) tabAccent else barInk,
                                    modifier = Modifier.size(TabIcon),
                                )
                            },
                            onClick = {
                                viewModel.clearPageHistory()
                                navController.switchTab(Routes.HOME)
                            },
                        )
                        tab(
                            key = Routes.NEW,
                            title = {
                                Text(
                                    stringResource(R.string.tab_new),
                                    fontSize = 9.sp,
                                    lineHeight = 11.sp,
                                    style = LocalTextStyle.current.copy(
                                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                                            includeFontPadding = false,
                                        ),
                                        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                                            alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                                            trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
                                        ),
                                    ),
                                    fontWeight = FontWeight.Bold,
                                    color = if (activeTab == Routes.NEW) tabAccent else barInk,
                                )
                            },
                            icon = {
                                Icon(
                                    PhosphorIcons.Fill.SquaresFour,
                                    contentDescription = stringResource(R.string.tab_new),
                                    tint = if (activeTab == Routes.NEW) tabAccent else barInk,
                                    modifier = Modifier.size(TabIcon),
                                )
                            },
                            onClick = {
                                viewModel.clearPageHistory()
                                navController.switchTab(Routes.NEW)
                            },
                        )
                        tab(
                            key = Routes.RADIO,
                            title = {
                                Text(
                                    stringResource(R.string.tab_radio),
                                    fontSize = 9.sp,
                                    lineHeight = 11.sp,
                                    style = LocalTextStyle.current.copy(
                                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                                            includeFontPadding = false,
                                        ),
                                        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                                            alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                                            trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
                                        ),
                                    ),
                                    fontWeight = FontWeight.Bold,
                                    color = if (activeTab == Routes.RADIO) tabAccent else barInk,
                                )
                            },
                            icon = {
                                Icon(
                                    PhosphorIcons.Fill.Broadcast,
                                    contentDescription = stringResource(R.string.tab_radio),
                                    tint = if (activeTab == Routes.RADIO) tabAccent else barInk,
                                    modifier = Modifier.size(TabIcon),
                                )
                            },
                            onClick = {
                                viewModel.clearPageHistory()
                                navController.switchTab(Routes.RADIO)
                            },
                        )
                        tab(
                            key = Routes.LIBRARY,
                            title = {
                                Text(
                                    stringResource(R.string.library),
                                    fontSize = 9.sp,
                                    lineHeight = 11.sp,
                                    style = LocalTextStyle.current.copy(
                                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                                            includeFontPadding = false,
                                        ),
                                        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                                            alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                                            trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
                                        ),
                                    ),
                                    fontWeight = FontWeight.Bold,
                                    color = if (activeTab == Routes.LIBRARY) tabAccent else barInk,
                                )
                            },
                            icon = {
                                Icon(
                                    PhosphorIcons.Fill.MusicNotes,
                                    contentDescription = stringResource(R.string.library),
                                    tint = if (activeTab == Routes.LIBRARY) tabAccent else barInk,
                                    modifier = Modifier.size(TabIcon),
                                )
                            },
                            onClick = {
                                viewModel.clearPageHistory()
                                navController.switchTab(Routes.LIBRARY)
                            },
                        )
                        // Search is one of the places, so it is registered like
                        // one: this is the tab the folded bar shows when it is
                        // where you are, and the key the search field grows out
                        // of.
                        tab(
                            key = Routes.SEARCH,
                            title = {
                                Text(
                                    stringResource(R.string.search),
                                    fontSize = 9.sp,
                                    lineHeight = 11.sp,
                                    style = LocalTextStyle.current.copy(
                                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                                            includeFontPadding = false,
                                        ),
                                        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                                            alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                                            trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
                                        ),
                                    ),
                                    fontWeight = FontWeight.Bold,
                                    color = if (activeTab == Routes.SEARCH) tabAccent else barInk,
                                )
                            },
                            icon = {
                                Icon(
                                    // Heavy rather than solid: a filled magnifier
                                    // reads as a blob at this size, and it is the
                                    // one glyph here whose shape is the whole of
                                    // its meaning.
                                    PhosphorIcons.Bold.MagnifyingGlass,
                                    contentDescription = stringResource(R.string.search),
                                    tint = if (activeTab == Routes.SEARCH) tabAccent else barInk,
                                    modifier = Modifier.size(TabIcon),
                                )
                            },
                            onClick = {
                                if (searching && searchOpen) {
                                    // Already typing: this is the way out of it.
                                    // The field goes and the keyboard with it,
                                    // the results stay where they are.
                                    searchOpen = false
                                    focus.clearFocus()
                                    keyboard?.hide()
                                } else {
                                    viewModel.clearPageHistory()
                                    searchOpen = true
                                    navController.switchTab(Routes.SEARCH)
                                }
                            },
                        )
                        // And still a circle beside the folded bar, so a reader
                        // halfway down a list can search without opening it
                        // first. Gone while search is where you are: the folded
                        // tab is already this control.
                        if (!searching) {
                            standaloneTab(
                                key = SEARCH_CIRCLE,
                                icon = {
                                    Icon(
                                        PhosphorIcons.Bold.MagnifyingGlass,
                                        contentDescription = stringResource(R.string.search),
                                        tint = barInk,
                                        modifier = Modifier.size(TabIcon),
                                    )
                                },
                                onClick = {
                                    viewModel.clearPageHistory()
                                    searchOpen = true
                                    navController.switchTab(Routes.SEARCH)
                                },
                            )
                        }
                    }
                    }
                }

                if (playback.hasItem && chrome > 0.01f) {
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = chrome }) {
                    NowPlayingSheet(
                        progress = expand,
                        background = {
                          Box(Modifier.fillMaxSize().layerBackdrop(playerBackdrop)) {
                            // The player's own room, out of the record it is
                            // playing: see AmbientArtworkBackground. The tone
                            // is the catalogue's where it gave one and the
                            // picture's own where it did not, which is the same
                            // rule every page in the app follows.
                            dev.lelonio.square.ui.components.AmbientArtworkBackground(
                                artworkModel = ambientArt,
                                // The shape the cover above is drawn at, so
                                // this lies exactly under it; see PlayerScreen.
                                coverAspect = 3f / 4f,
                                // The catalogue's colour first, and used the
                                // way it was filed rather than taken towards
                                // the theme's paper: it was picked for this
                                // record, and it is what the reference puts
                                // behind the same cover. The colour read off
                                // the picture is the fallback for the records
                                // it has never heard of.
                                tone = playerTone,
                                // Barely any colour over the picture.
                                //
                                // The reference does not put a tone behind its
                                // player at all: what fills the screen above and
                                // below the cover is the cover, blurred and
                                // carried past its edges — dark grey at the top
                                // because the sleeve's top is dark grey, red at
                                // the foot because the sleeve ends in a red
                                // coat. A wash of a chosen colour over that is
                                // the thing that made ours stop looking like the
                                // record. What is left is enough to keep the
                                // controls legible.
                                toneFloor = 0.08f,
                                scrimFloor = 0.22f,
                                // Still while the sheet is travelling: under a
                                // moving, scaling, fading layer the drift
                                // cannot be seen, and it is the one moment in
                                // the app where an always-invalidating
                                // background would be competing for the frame.
                                motion = playerSettled,
                                columns = clipColumns,
                            )
                          }
                        },
                        expandedContent = {
                          // The player is the largest glass surface in the app by
                          // a wide margin, so the settings' switch for it is the
                          // one that buys the most. Off leaves the film every
                          // pane falls back to, not a hole.
                          androidx.compose.runtime.CompositionLocalProvider(
                              dev.lelonio.square.ui.player.LocalGlassEnabled provides
                                  (glassConfig.playerEnabled &&
                                      dev.lelonio.square.ui.player.LocalGlassEnabled.current),
                              // Everything on this screen writes on the field,
                              // and the field is the record — not the phone's
                              // setting. The glass reads this too and takes the
                              // opposite film; see GlassEffect.
                              dev.lelonio.square.ui.theme.LocalInkOverride provides playerInk,
                              androidx.compose.material3.LocalContentColor provides playerInk,
                          ) {
                            PlayerScreen(
                                state = playerState,
                                // The title is the way onto the record. The
                                // address travels with the queue where the
                                // source gave one; where it did not — a list
                                // resolved before the app kept it — it is
                                // looked up on the tap rather than left inert.
                                onOpenAlbum = playback.mediaId
                                    ?.takeIf { it.startsWith("spotify:track:") }
                                    ?.let { track ->
                                        {
                                            scope.launch {
                                                val album = playback.albumUri?.let { uri ->
                                                    CatalogPlaylist(
                                                        uri = uri,
                                                        name = playback.album,
                                                        artworkUrl = playback.artworkUrl,
                                                    )
                                                } ?: viewModel.albumOf(track)
                                                if (album != null) {
                                                    expand.animateTo(0f, expandSpec)
                                                    navController.openPlaylist(viewModel, album)
                                                }
                                            }
                                            Unit
                                        }
                                    },
                                positionMs = positionMs,
                                videoFileId = videoFileId,
                                videoMode = spotifyVideoOn,
                                onToggleVideo = {
                                    // Handed to the service, which swaps what
                                    // the session is playing: the video carries
                                    // the song's own audio and answers the
                                    // transport controls. See SpotifyVideoMode.
                                    val at = player?.currentPosition ?: 0L
                                    val id = videoFileId
                                    if (spotifyVideoOn) {
                                        dev.lelonio.square.backend.spotify
                                            .SpotifyVideoMode.listen(at)
                                    } else if (id != null) {
                                        dev.lelonio.square.backend.spotify
                                            .SpotifyVideoMode.watch(id, at)
                                    }
                                },
                                onCollapse = { scope.launch { expand.animateTo(0f, expandSpec) } },
                                onTogglePlay = {
                                    if (remote != null) {
                                        val playing = remote?.playing == true
                                        onRemote { id ->
                                            if (playing) RemoteConnect.pause(id)
                                            else RemoteConnect.play(id)
                                        }
                                    } else {
                                        player?.togglePlay()
                                    }
                                },
                                onNext = {
                                    if (remote != null) onRemote(RemoteConnect::next)
                                    // Skipping is about the song: a video has
                                    // nowhere of its own to go, and the queue
                                    // is held by the engine parked behind it.
                                    else if (spotifyVideoOn) {
                                        dev.lelonio.square.backend.spotify
                                            .SpotifyVideoMode.skip(forward = true)
                                    } else {
                                        player?.seekToNextMediaItem()
                                    }
                                },
                                onPrevious = {
                                    if (remote != null) onRemote(RemoteConnect::previous)
                                    else if (spotifyVideoOn) {
                                        dev.lelonio.square.backend.spotify
                                            .SpotifyVideoMode.skip(forward = false)
                                    } else {
                                        player?.seekToPreviousMediaItem()
                                    }
                                },
                                onSeek = { target ->
                                    if (remote != null) {
                                        onRemote { id -> RemoteConnect.seek(id, target) }
                                    } else {
                                        player?.seekTo(target)
                                    }
                                },
                                onToggleShuffle = {
                                    if (remote != null) {
                                        val wanted = remote?.shuffle != true
                                        onRemote { id -> RemoteConnect.setShuffle(id, wanted) }
                                    } else {
                                        player?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
                                    }
                                },
                                onCycleRepeat = {
                                    if (remote != null) {
                                        val current = remote
                                        // Off, all, one, off: the same cycle the
                                        // local button walks.
                                        val context = current?.repeatContext != true &&
                                            current?.repeatTrack != true
                                        val track = current?.repeatContext == true
                                        onRemote { id -> RemoteConnect.setRepeat(id, context, track) }
                                    } else {
                                        player?.cycleRepeatMode()
                                    }
                                },
                                queue = queue,
                                lyrics = lyrics,
                                // Loading until the answer in hand is this
                                // track's, which covers the wait before the
                                // fetch has even begun.
                                lyricsLoading = playback.mediaId != null &&
                                    lyricsFor != playback.mediaId,
                                credits = credits,
                                creditsLoading = creditsLoading,
                                onWantCredits = viewModel::loadCredits,
                                onPlayQueueItem = { player?.seekTo(it, 0L) },
                                onRemoveQueueItem = { player?.removeMediaItem(it) },
                                reverb = reverb,
                                // Speed and pitch are set as a pair because
                                // PlaybackParameters carries both; changing one
                                // has to carry the other through unchanged.
                                onSpeed = {
                                    setPlaybackParameters(
                                        PlaybackParameters(it, playback.pitch),
                                    )
                                },
                                onPitch = {
                                    setPlaybackParameters(
                                        PlaybackParameters(playback.speed, it),
                                    )
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
                                backdrop = playerBackdrop,
                                canvas = canvas,
                                // The record's own artwork from the other
                                // catalogue, where it has any: the tall picture
                                // and the moving cover a song inherits from its
                                // album. Null leaves the player with the square
                                // cover it always had.
                                // The colours filed with this artwork, for the
                                // light that moves behind it; see CoverAura.
                                catalogPalette = nowPlayingArt?.inkPalette.orEmpty(),
                                onClipColumns = { clipColumns = it },
                                coverHeroUrl = nowPlayingArt?.heroUrl,
                                coverMotionUrl = nowPlayingArt?.motionUrl,
                                coverSquareUrl = nowPlayingArt?.coverUrl,
                                // Spotify's cover is the fallback, not the
                                // first draft: while the catalogue is still
                                // being asked, the player shows colour rather
                                // than a picture it is about to replace.
                                coverPending = nowPlayingArtPending,
                                devices = devices,
                                onAnotherDevice = remote != null,
                                alreadySaved = playback.mediaId != null &&
                                    playback.mediaId in inPlaylists,
                                inLikedSongs = playback.mediaId != null &&
                                    playback.mediaId in likedTracks,
                                // Spotify's own radio, and only where Spotify
                                // can answer: the station is a context on its
                                // access point and means nothing anywhere else.
                                onRadio = playback.mediaId
                                    ?.takeIf {
                                        it.startsWith("spotify:track:") &&
                                            backend == dev.lelonio.square.backend.BackendId.SPOTIFY
                                    }
                                    ?.let { uri ->
                                        {
                                            scope.launch {
                                                runCatching {
                                                    val tracks = viewModel.radioFor(uri)
                                                    if (tracks.isEmpty()) return@launch
                                                    val station =
                                                        "spotify:station:track:" +
                                                            uri.substringAfterLast(':')
                                                    val name = radioOf(playback.title)
                                                    viewModel.showStation(
                                                        uri = station,
                                                        name = name,
                                                        artworkUrl = playback.artworkUrl,
                                                        tracks = tracks,
                                                    )
                                                    expand.animateTo(0f, expandSpec)
                                                    if (navController.currentDestination?.route != Routes.PLAYLIST) {
                                                        navController.navigate(Routes.PLAYLIST) { launchSingleTop = true }
                                                    }
                                                    onPlay(
                                                        tracks,
                                                        0,
                                                        station,
                                                        true,
                                                        name,
                                                        0L,
                                                    )
                                                }.onFailure {
                                                    android.util.Log.e("SquareApp", "Failed to start radio: ${it.message}")
                                                }
                                            }
                                            Unit
                                        }
                                    },
                                onOpenDevices = viewModel::openDevices,
                                connectAvailable =
                                    backend == dev.lelonio.square.backend.BackendId.SPOTIFY &&
                                        // Offline there is no device to hand
                                        // playback to, and no list to draw: the
                                        // engine came up without a Connect
                                        // device at all.
                                        !offlineNow,
                                onCloseDevices = viewModel::closeDevices,
                                onRefreshDevices = viewModel::refreshDevices,
                                // The position goes with the request: a
                                // handover resumes where the listener was, and
                                // this is the only side that knows to the
                                // second.
                                onSelectDevice = {
                                    viewModel.transferPlayback(it, positionMs.value)
                                },
                                onOpenUri = { uri, name ->
                                    viewModel.openContext(uri, name)
                                    if (navController.currentDestination?.route != Routes.PLAYLIST) {
                                        navController.navigate(Routes.PLAYLIST) { launchSingleTop = true }
                                    }
                                },
                                onAddToPlaylist = {
                                    viewModel.openAddToPlaylist(
                                        playback.mediaId,
                                        playback.title,
                                        asSheet = false,
                                    )
                                },
                                onToggleLike = playback.mediaId?.let { uri ->
                                    {
                                        viewModel.toggleLike(
                                            uri,
                                            playback.title,
                                            playback.artist,
                                            playback.artworkUrl,
                                        )
                                    }
                                },
                                addToPlaylist = addToPlaylist,
                                onPickPlaylist = viewModel::addToPlaylist,
                                playlistEditAvailable =
                                    backend == dev.lelonio.square.backend.BackendId.SPOTIFY,
                                onWatchVideo = player
                                    ?.takeIf {
                                        // A video is streamed, so there is
                                        // nothing to offer offline.
                                        !offlineNow &&
                                            playback.mediaId
                                                ?.startsWith("ytmusic:track:") == true
                                    }
                                    ?.let { { YouTubeVideoMode.toggle(it) } },
                                videoOn = videoOn || spotifyVideoOn,
                                videoPlayer = player,
                                videoAttachKey = spotifyVideoGeneration,
                                onMore = playback.mediaId?.let { uri ->
                                    {
                                        trackMenu = TrackMenuRequest(
                                            track = CatalogTrack(
                                                uri = uri,
                                                name = playback.title,
                                                artist = playback.artist,
                                                artworkUrl = playback.artworkUrl,
                                            ),
                                            removable = false,
                                        )
                                    }
                                },
                            )
                          }
                        },
                    )
                    }
                }

                }

                // Above the tab bar and the now-playing bar, which is the whole
                // reason it lives here instead of in the screen that asked for
                // it.
                var lastPlaylistMenu by remember { mutableStateOf<CatalogPlaylist?>(null) }
                LaunchedEffect(playlistMenu) { playlistMenu?.let { lastPlaylistMenu = it } }
                val shownPlaylist = playlistMenu ?: lastPlaylistMenu
                TrackSheet(
                    visible = playlistMenu != null,
                    title = shownPlaylist?.name.orEmpty(),
                    subtitle = stringResource(R.string.playlist),
                    artworkUrl = shownPlaylist?.artworkUrl,
                    backdrop = overlayBackdrop,
                    onDismiss = { playlistMenu = null },
                ) {
                    if (shownPlaylist != null) {
                        // First action: this is the one that costs nothing and
                        // can be undone by pressing it again.
                        val isPinned = shownPlaylist.uri in pinnedPlaylists
                        // Pinning is about where a row sits in the library, so
                        // it is offered for what is in the library. A playlist
                        // you are only looking at has no row to move.
                        if (playlistMenuSaved) TrackSheetAction(
                            stringResource(if (isPinned) R.string.unpin else R.string.pin),
                            PhosphorIcons.Regular.PushPin,
                        ) {
                            viewModel.togglePinned(shownPlaylist.uri)
                            playlistMenu = null
                        }
                        // Liked Songs is Spotify's own list: it can be pinned
                        // like any other row, but there is no name to change
                        // and no way to delete it. Nor is anything editable on
                        // somebody else's playlist — Spotify has no request for
                        // "delete the list an editor made", and the app offering
                        // one meant offering a button whose only outcome was an
                        // error.
                        val editable = !shownPlaylist.uri.endsWith(":collection") &&
                            playlistMenuMine
                        if (editable) TrackSheetAction(
                            stringResource(R.string.rename),
                            PhosphorIcons.Regular.PencilSimple,
                        ) {
                            naming = NamingRequest(shownPlaylist)
                            playlistMenu = null
                        }
                        // The whole playlist in one link. yt-dlp and the apps
                        // built on it expand a playlist URL themselves, so this
                        // is the entire "download an album" feature.
                        if (backend == dev.lelonio.square.backend.BackendId.YOUTUBE_MUSIC) {
                            val label = if (downloaderInstalled(context)) {
                                stringResource(R.string.download_with)
                            } else {
                                stringResource(R.string.download_get_app)
                            }
                            TrackSheetAction(label, PhosphorIcons.Regular.Download) {
                                playlistMenu = null
                                sendForDownload(context, shownPlaylist.openLink())
                            }
                        }
                        // Keeping the page on the phone lives here rather
                        // than under the cover: the reference has no button for
                        // it beside play, and the three that are there are the
                        // three you press while listening. This is a decision
                        // about storage, which is what a menu is for.
                        //
                        // The store belongs to the librespot engine, so only
                        // Spotify pages can be kept — and with no network the
                        // entry would only ever queue work that cannot start.
                        //
                        // Offered only for the page that is open, because
                        // keeping one means keeping its songs and the track list
                        // is what this reads: the same sheet opens over a
                        // library row, where there is a name and nothing to
                        // fetch yet.
                        val keepable = backend == dev.lelonio.square.backend.BackendId.SPOTIFY &&
                            shownPlaylist.uri.startsWith("spotify:") &&
                            playlist.uri == shownPlaylist.uri &&
                            playlist.tracks.isNotEmpty() &&
                            // An artist page has no keep button of its own: its
                            // three controls are the ones the reference gives an
                            // artist, so for that one page this is the way in.
                            playlist.kind == MainViewModel.DetailKind.ARTIST &&
                            !offlineNow
                        if (keepable) {
                            val kept = downloadOwners[shownPlaylist.uri]
                            val isKept = kept != null && kept !is dev.lelonio.square.data.OwnerState.None
                            TrackSheetAction(
                                stringResource(
                                    if (isKept) R.string.remove_download else R.string.download,
                                ),
                                if (isKept) {
                                    PhosphorIcons.Regular.Trash
                                } else {
                                    PhosphorIcons.Regular.Download
                                },
                            ) {
                                playlistMenu = null
                                // Asked rather than done when it takes songs
                                // away: a mis-tap should not delete a hundred
                                // of them.
                                if (isKept) unkeeping = playlist
                                else viewModel.toggleDownload(playlist)
                            }
                        }
                        // The link to the page, which used to be a button of
                        // its own beside the cover. Sharing is occasional and
                        // it is done once; the place next to the page's title
                        // now filters its songs, which is what gets used while
                        // the page is open.
                        TrackSheetAction(
                            stringResource(R.string.copy_link),
                            PhosphorIcons.Regular.Export,
                        ) {
                            playlistMenu = null
                            context.startActivity(
                                android.content.Intent.createChooser(
                                    android.content.Intent(android.content.Intent.ACTION_SEND)
                                        .setType("text/plain")
                                        .putExtra(
                                            android.content.Intent.EXTRA_TEXT,
                                            dev.lelonio.square.ui.library.openLinkOf(
                                                shownPlaylist.uri,
                                            ),
                                        ),
                                    null,
                                ),
                            )
                        }
                        if (editable) TrackSheetAction(
                            stringResource(R.string.delete),
                            PhosphorIcons.Regular.Trash,
                            destructive = true,
                        ) {
                            // Asked rather than done: see ConfirmDialog.
                            deleting = shownPlaylist
                            playlistMenu = null
                        }
                    }
                }

                unkeeping?.let { target ->
                    dev.lelonio.square.ui.components.ConfirmDialog(
                        title = stringResource(R.string.remove_download),
                        message = stringResource(R.string.remove_download_confirm, target.name),
                        confirmLabel = stringResource(R.string.remove_download_confirm_action),
                        onConfirm = {
                            viewModel.toggleDownload(target)
                            unkeeping = null
                        },
                        onDismiss = { unkeeping = null },
                    )
                }

                deleting?.let { target ->
                    dev.lelonio.square.ui.components.ConfirmDialog(
                        title = stringResource(R.string.delete_playlist_title),
                        message = stringResource(R.string.delete_playlist_message, target.name),
                        confirmLabel = stringResource(R.string.delete),
                        onConfirm = {
                            viewModel.deletePlaylist(target.uri)
                            deleting = null
                        },
                        onDismiss = { deleting = null },
                    )
                }

                naming?.let { request ->
                    dev.lelonio.square.ui.components.NameDialog(
                        title = stringResource(
                            if (request.playlist == null) R.string.new_playlist else R.string.rename,
                        ),
                        initial = request.playlist?.name.orEmpty(),
                        confirmLabel = stringResource(
                            if (request.playlist == null) R.string.create else R.string.save,
                        ),
                        onConfirm = { name ->
                            val playlist = request.playlist
                            if (playlist == null) {
                                viewModel.createPlaylist(name)
                            } else {
                                viewModel.renamePlaylist(playlist.uri, name)
                            }
                            naming = null
                        },
                        onDismiss = { naming = null },
                    )
                }

                // Held one beat longer than the state that opened it: the
                // sheet slides out over a couple of hundred milliseconds, and
                // reading the track straight off `trackMenu` emptied the title,
                // the artist and every action the instant it was dismissed —
                // the sheet was seen leaving with nothing in it.
                var lastMenu by remember { mutableStateOf<TrackMenuRequest?>(null) }
                LaunchedEffect(trackMenu) { trackMenu?.let { lastMenu = it } }
                val menu = trackMenu ?: lastMenu
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                TrackSheet(
                    visible = trackMenu != null,
                    title = menu?.track?.name.orEmpty(),
                    subtitle = menu?.track?.artist.orEmpty(),
                    artworkUrl = menu?.track?.artworkUrl,
                    backdrop = overlayBackdrop,
                    onDismiss = { trackMenu = null },
                ) {
                    if (menu != null) {
                        TrackSheetAction(stringResource(R.string.play), PhosphorIcons.Fill.Play) {
                            trackMenu = null
                            onPlay(listOf(menu.track), 0, null, false, "", 0L)
                        }
                        TrackSheetAction(stringResource(R.string.add_to_queue), PhosphorIcons.Regular.Queue) {
                            trackMenu = null
                            onEnqueue(menu.track)
                        }
                        // Writing to a playlist is the Spotify Web API's; on
                        // another source, or with no network, the entry would
                        // only ever fail.
                        if (backend == dev.lelonio.square.backend.BackendId.SPOTIFY && !offlineNow) {
                            TrackSheetAction(stringResource(R.string.add_to_playlist), PhosphorIcons.Regular.Plus) {
                                trackMenu = null
                                viewModel.openAddToPlaylist(menu.track.uri, menu.track.name)
                            }
                        }
                        TrackSheetAction(stringResource(R.string.go_to_artist), PhosphorIcons.Regular.User) {
                            val track = menu.track
                            trackMenu = null
                            scope.launch {
                                if (expand.value > 0f) {
                                    expand.animateTo(0f, expandSpec)
                                }
                                val artist = viewModel.artistOf(track)
                                if (artist != null) {
                                    viewModel.openContext(artist.uri, artist.title, artist.artworkUrl)
                                    if (navController.currentDestination?.route != Routes.PLAYLIST) {
                                        navController.navigate(Routes.PLAYLIST) { launchSingleTop = true }
                                    }
                                }
                            }
                        }
                        // Keeping one song, as opposed to keeping the list it
                        // came from. Spotify only: this is the engine's own
                        // store, and the other backend streams from elsewhere.
                        // Removing is offered only for a song kept on its
                        // own. One that is here because a playlist wants it
                        // cannot be let go of from this menu — the file is
                        // still owed to that playlist — and a row that looks
                        // like it removes something and removes nothing is
                        // worse than no row.
                        val onItsOwn = menu.track.uri in downloadSingles
                        val here = menu.track.uri in downloadedFiles
                        if (backend == dev.lelonio.square.backend.BackendId.SPOTIFY &&
                            (onItsOwn || (!here && !offlineNow))
                        ) {
                            val kept = onItsOwn
                            TrackSheetAction(
                                stringResource(
                                    if (kept) R.string.remove_download else R.string.download,
                                ),
                                if (kept) {
                                    PhosphorIcons.Fill.ArrowCircleDown
                                } else {
                                    PhosphorIcons.Regular.Download
                                },
                            ) {
                                trackMenu = null
                                viewModel.toggleTrackDownload(menu.track)
                            }
                        }
                        TrackSheetAction(stringResource(R.string.copy_link), PhosphorIcons.Regular.LinkSimple) {
                            trackMenu = null
                            clipboard.setText(AnnotatedString(menu.track.openLink()))
                        }
                        // YouTube only. A Spotify link handed to a downloader
                        // is a link it cannot do anything with, so offering the
                        // action there would be offering a failure.
                        if (backend == dev.lelonio.square.backend.BackendId.YOUTUBE_MUSIC) {
                            // Says which app it will open, and says the other
                            // thing when there is no app to open: a row that
                            // promised a download and delivered a web page
                            // would be the entry lying about what it does.
                            val label = if (downloaderInstalled(context)) {
                                stringResource(R.string.download_with)
                            } else {
                                stringResource(R.string.download_get_app)
                            }
                            TrackSheetAction(label, PhosphorIcons.Regular.Download) {
                                trackMenu = null
                                sendForDownload(context, menu.track.openLink())
                            }
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

                FriendsPanel(
                    visible = friendsOpen,
                    friends = friends,
                    backdrop = overlayBackdrop,
                    onOpenTrack = { friend ->
                        friendsOpen = false
                        sharedTrack = dev.lelonio.square.data.CatalogTrack(
                            uri = friend.trackUri,
                            name = friend.trackName,
                            artist = friend.artist,
                            artworkUrl = friend.artworkUrl,
                        )
                    },
                    onAddFriend = viewModel::addFriend,
                    addState = addFriendState,
                    onDismiss = {
                        friendsOpen = false
                        viewModel.clearAddFriend()
                    },
                )

                // A song that arrived by link. Held one beat past the dismissal
                // for the same reason the track sheet is: the card is still on
                // its way out, and reading the track off the state that closed
                // it would empty the cover and the title as it goes.
                var lastShared by remember { mutableStateOf<CatalogTrack?>(null) }
                LaunchedEffect(sharedTrack) { sharedTrack?.let { lastShared = it } }
                SharedTrackCard(
                    visible = sharedTrack != null,
                    track = sharedTrack ?: lastShared,
                    backdrop = overlayBackdrop,
                    onPlay = {
                        val track = sharedTrack ?: return@SharedTrackCard
                        sharedTrack = null
                        onPlay(listOf(track), 0, null, false, "", 0L)
                    },
                    onAddToPlaylist = {
                        val track = sharedTrack ?: return@SharedTrackCard
                        sharedTrack = null
                        viewModel.openAddToPlaylist(track.uri, track.name)
                    },
                    onDismiss = { sharedTrack = null },
                )

                // Over everything, including the player: the same sheet is
                // opened from a track row in the library and from the plus in
                // the player, and it is one sheet with one state.
                // Here rather than at the top of the app: a sheet made of
                // glass has to be drawn where there is a layer to refract, and
                // this is the layer the other sheets use.
                UpdatePrompt(overlayBackdrop, onOpenChange = { updateOpen = it })

                AddToPlaylistSheet(
                    state = addToPlaylist,
                    backdrop = overlayBackdrop,
                    onSelect = viewModel::addToPlaylist,
                    onDismiss = viewModel::closeAddToPlaylist,
                )

                // The curtain. Over the whole app, under nothing.
                androidx.compose.animation.AnimatedVisibility(
                    visible = switching,
                    enter = androidx.compose.animation.fadeIn(tween(180)),
                    exit = androidx.compose.animation.fadeOut(tween(420)),
                    modifier = Modifier.zIndex(20f),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            // A colour of its own: the theme's background is
                            // transparent, because every page in this app is
                            // drawn over the blurred artwork. A curtain has to
                            // be opaque or it is not a curtain.
                            .background(Color(0xFF0A0A0A)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            dev.lelonio.square.ui.components.AppIcon(84.dp)
                            dev.lelonio.square.ui.components.SquareWordmark(height = 28.dp)
                            // Which source is being opened, named and marked:
                            // the whole point of the wait is that the app is
                            // becoming a different one, and a bare spinner says
                            // nothing about that.
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    when (backend) {
                                        dev.lelonio.square.backend.BackendId.SPOTIFY ->
                                            PhosphorIcons.Regular.SpotifyLogo
                                        dev.lelonio.square.backend.BackendId.YOUTUBE_MUSIC ->
                                            PhosphorIcons.Regular.YoutubeLogo
                                    },
                                    contentDescription = null,
                                    tint = Ink,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    stringResource(
                                        when (backend) {
                                            dev.lelonio.square.backend.BackendId.SPOTIFY ->
                                                R.string.backend_spotify
                                            dev.lelonio.square.backend.BackendId.YOUTUBE_MUSIC ->
                                                R.string.backend_youtube
                                        },
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Ink,
                                )
                            }
                        }
                    }
                }

                // Above everything, bars and player included: until it is done
                // there is nothing underneath worth reaching, and on a fresh
                // install most of what is underneath does not work yet.
                //
                // Above the tutorial too, and asked first: everything the
                // tutorial explains is Spotify's setup, so the source has to be
                // settled before any of it is worth walking through.
                // Over everything, like the tutorial: it is Google's own sign-in
                // page and half-covering it would be the wrong thing to do with
                // a page someone is typing a password into.
                if (showYouTubeLogin) {
                    val account = remember(context) {
                        (context.applicationContext as dev.lelonio.square.SquareApplication)
                            .youtubeAccount
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(androidx.compose.ui.graphics.Color(0xFF101012)),
                    ) {
                        dev.lelonio.square.backend.youtube.YouTubeLoginScreen(
                            account = account,
                            onDone = {
                                showYouTubeLogin = false
                                // The library is the whole point of signing in,
                                // and it was read as the signed-out account.
                                viewModel.refresh()
                            },
                        )
                    }
                }

                if (!backendChosen) {
                    BackendChoiceScreen(onChoose = preferences::setBackend)
                } else if (
                    // Every step of it is Spotify's setup — the Premium
                    // account, the login, the registered application — so on
                    // YouTube Music it would be five screens of instructions
                    // for something the user just chose not to use. Still
                    // reachable from the settings, which is where someone who
                    // means to switch to Spotify would go.
                    showTutorial ||
                    (!onboarded && backend == dev.lelonio.square.backend.BackendId.SPOTIFY)
                ) {
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
}

/**
 * The colour every screen sits on, taken from what is playing.
 *
 * The player is the exception and has its own: there the record is the subject
 * and the screen is made of the picture itself — see AmbientArtworkBackground,
 * which is where the blurred copy of the artwork went.
 */
@Composable
private fun AppBackdrop() {
    // Neutral, and the same on every page the app assembles.
    //
    // These screens used to take the colour of whatever was playing. On a page
    // that is *about* one record — its own page, or the player — that is right,
    // and it stays. On a screen made of other people's covers it is a wash
    // under a hundred artworks that have nothing to do with it: on the New tab
    // a salmon ground had every sleeve fighting it, and the reference keeps
    // exactly these pages black or white for that reason.
    //
    // The record's colour did not go anywhere. It is on the page of the record
    // itself, on the player, and on the controls that mean "this one, now" —
    // the play button, the lit tab, the row that is playing — which is where a
    // colour says something.
    Box(Modifier.fillMaxSize().background(pageGround()))
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
    // do something and come back — and giving it the same weight as the places
    // made all of them read as places.
    val routes = remember {
        listOf(Routes.HOME, Routes.NEW, Routes.RADIO, Routes.LIBRARY)
    }

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
            indicatorVisible = routeIndex >= 0,
        ) {
            BottomItem(
                stringResource(R.string.home),
                PhosphorIcons.Fill.House,
                PhosphorIcons.Bold.House,
                // Nothing is lit while the page on screen is not one of these
                // two: search is a destination of its own, and leaving Home
                // filled behind it said the app was somewhere it was not.
                routeIndex >= 0 && selected == 0,
            ) {
                onSelect(Routes.HOME)
            }
            BottomItem(
                stringResource(R.string.tab_new),
                PhosphorIcons.Fill.SquaresFour,
                PhosphorIcons.Bold.SquaresFour,
                routeIndex >= 0 && selected == 1,
            ) { onSelect(Routes.NEW) }
            BottomItem(
                stringResource(R.string.tab_radio),
                PhosphorIcons.Fill.Broadcast,
                PhosphorIcons.Bold.Broadcast,
                routeIndex >= 0 && selected == 2,
            ) { onSelect(Routes.RADIO) }
            BottomItem(
                stringResource(R.string.library),
                PhosphorIcons.Fill.MusicNotes,
                PhosphorIcons.Bold.MusicNotes,
                routeIndex >= 0 && selected == 3,
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
            // The same film the capsule beside it uses, or the round button
            // reads as clearer glass than the bar it sits next to.
        ) {
            Icon(
                imageVector = if (searching) PhosphorIcons.Fill.MagnifyingGlass else PhosphorIcons.Bold.MagnifyingGlass,
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
    if (currentDestination?.route != Routes.PLAYLIST) {
        navigate(Routes.PLAYLIST) { launchSingleTop = true }
    }
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
    // playWhenReady, not isPlaying: a track that is still being fetched is not
    // playing, so asking isPlaying made every tap during a load another play —
    // the listener could tap five times and never stop it. This alternates from
    // the first tap, and the last one wins.
    if (playWhenReady) pause() else play()
}

/** off → all → one → off, the order every player uses. */
private fun Player.cycleRepeatMode() {
    repeatMode = when (repeatMode) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    }
}

/**
 * The playing track as the last session left it, or null when there was none.
 *
 * The saved index points into the *current* order while the list is kept in the
 * pre-shuffle one, so the permutation has to be applied to find the track that
 * was actually playing.
 */
/**
 * Asks GitHub for a newer release when the app opens, and says so if there is one.
 *
 * This used to be a button in the settings and nothing else, on the grounds
 * that the check is a request made for the app's benefit rather than the
 * user's. The trouble with that is who it leaves behind: an app no store can
 * reach, whose owner never opens the settings, stays on the version it was
 * installed with forever, security fixes and all. So it runs on its own now,
 * with the cost kept small: at most one request every six hours, and one
 * dialog per release, ever.
 *
 * Everything that can go wrong is silence. The check is not something the user
 * asked for, so a failure is not something to tell them about.
 */
@Composable
private fun UpdatePrompt(
    backdrop: dev.lelonio.square.ui.glass.backdrop.Backdrop,
    onOpenChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val updater = remember(context) {
        (context.applicationContext as dev.lelonio.square.SquareApplication).updater
    }
    val prefs = remember(context) { dev.lelonio.square.data.PreferencesStore(context) }
    val scope = rememberCoroutineScope()
    val state by updater.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis()
        if (now - prefs.lastUpdateCheck() < UPDATE_CHECK_INTERVAL_MS) return@LaunchedEffect
        // Recorded before the call rather than after it: a phone with no
        // network would otherwise ask again at every launch, which is the one
        // shape of this that would be a nuisance.
        prefs.setLastUpdateCheck(now)
        runCatching { updater.check() }
    }

    // Held while the download runs: the state moves on from Available the
    // moment the fetch starts, and the sheet has to stay to show it happening.
    var offered by remember { mutableStateOf<Updater.State.Available?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    (state as? Updater.State.Available)?.let { found ->
        // Already offered, and turned down. The version is remembered rather
        // than a flag, so the next release is news again.
        if (found.version != prefs.skippedUpdate()) offered = found
    }
    val available = offered ?: return

    // Held across the trip to the system settings, so granting the permission
    // continues the install instead of dropping it.
    var pending by remember { mutableStateOf<Updater.State.Available?>(null) }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val update = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (updater.canInstall()) updater.install(update)
    }

    val downloading = state is Updater.State.Downloading
    val installing = state is Updater.State.Installing

    // What the layer below is recorded for; see [sheetsOpen].
    val showing = !dismissed && !installing
    LaunchedEffect(showing) { onOpenChange(showing) }
    DisposableEffect(Unit) { onDispose { onOpenChange(false) } }

    UpdateSheet(
        version = available.version,
        size = available.bytes.takeIf { it > 0 }?.let { "%.1f MB".format(it / 1_000_000.0) },
        notes = available.notes,
        progress = (state as? Updater.State.Downloading)?.progress,
        downloading = downloading,
        onInstall = {
            if (updater.canInstall()) {
                updater.install(available)
            } else {
                pending = available
                permission.launch(updater.permissionIntent())
            }
        },
        onDismiss = {
            prefs.setSkippedUpdate(available.version)
            dismissed = true
        },
        backdrop = backdrop,
        // Gone once the system installer has it: what happens next is Android's
        // dialog, and two things asking about the same install is one too many.
        visible = showing,
    )
}

/** At most one check every six hours, however often the app is opened. */
private const val UPDATE_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

private fun savedPlaybackSeed(context: android.content.Context): PlaybackState? {
    val saved = dev.lelonio.square.data.PlaybackStore(context).load() ?: return null
    val position = saved.shuffleOrder?.getOrNull(saved.index) ?: saved.index
    val track = saved.tracks.getOrNull(position)?.takeIf { it.name.isNotBlank() } ?: return null
    return PlaybackState(
        hasItem = true,
        mediaId = track.uri,
        title = track.name,
        artist = track.artist,
        // The record too, and not only for the caption under the title: it is
        // what the other catalogue is searched by. Left out, the first lookup of
        // every cold start went to the search-by-song fallback and came back
        // with whichever release ranked first — a single, or a compilation —
        // which is why a song could open under a different cover each time the
        // app was started.
        album = track.album,
        albumUri = track.albumUri,
        artworkUrl = track.artworkUrl,
        durationMs = track.durationMs,
        // Not "paused": what it is doing is unknown until the controller
        // answers, and a play button that flips to pause a moment later reads
        // as the app having ignored a tap.
        isBuffering = true,
    )
}

/**
 * The search field, living in the bar.
 *
 * The circle the listener tapped grows into this, so it is focused as it
 * arrives: whoever pressed the magnifier wants to type. The cross clears the
 * field and appears only once there is something to clear.
 */
@Composable
private fun BarSearchField(
    query: String,
    onQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** The bar's own white, carrying the colour of the page behind it. */
    ink: Color = Ink,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Row(
        modifier.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            PhosphorIcons.Bold.MagnifyingGlass,
            contentDescription = null,
            tint = ink.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp),
        )
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(ink),
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
                .focusRequester(focus),
            decorationBox = { field ->
                if (query.isEmpty()) {
                    Text(
                        stringResource(R.string.search_placeholder),
                        style = MaterialTheme.typography.bodyLarge,
                        color = ink.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                field()
            },
        )
        if (query.isNotEmpty()) {
            Icon(
                PhosphorIcons.Fill.XCircle,
                contentDescription = stringResource(R.string.clear),
                tint = Ink.copy(alpha = 0.55f),
                modifier = Modifier
                    .size(22.dp)
                    .pressable(onClick = { onQuery("") }, pressedScale = 0.9f),
            )
        }
    }
}

/**
 * Waits for the song to be playing before fetching what surrounds it.
 *
 * Everything the player shows around a track — the Canvas clip above all, which
 * is a video, plus the lyrics and the question of whether there is a music
 * video — is worth having and worth nothing at all until the song is audible.
 * Asked for the moment the track changes, they compete with the audio for the
 * same connection, and on a slow one that is the difference between a song
 * starting now and starting in three seconds.
 *
 * Bounded, because a track that never gets going must not hold them for ever:
 * after this, they are fetched anyway.
 */
private suspend fun awaitAudible(
    state: androidx.compose.runtime.State<dev.lelonio.square.ui.player.PlaybackState>,
) {
    kotlinx.coroutines.withTimeoutOrNull(AUDIBLE_TIMEOUT_MS) {
        snapshotFlow { state.value.isBuffering }.first { !it }
    }
}

/** How long the extras wait for the song; see [awaitAudible]. */
private const val AUDIBLE_TIMEOUT_MS = 5_000L

/** The one colour a row of sampled columns amounts to. */
private fun List<Color>.mean(): Color {
    if (isEmpty()) return Color.Black
    return Color(
        red = sumOf { it.red.toDouble() }.toFloat() / size,
        green = sumOf { it.green.toDouble() }.toFloat() / size,
        blue = sumOf { it.blue.toDouble() }.toFloat() / size,
    )
}
