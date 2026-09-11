package dev.lelonio.square.ui.library

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.RowScope
import dev.lelonio.square.ui.glass.liquidGlass
import dev.lelonio.square.ui.player.GlassFilm
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.lelonio.square.ui.components.BlurTransformation
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.mutableFloatStateOf
import dev.lelonio.square.ui.player.GlassSurface
import dev.lelonio.square.ui.components.MENU_WIDTH
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.BoxScope
import dev.lelonio.square.R
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.layerBackdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.rememberCombinedBackdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.rememberBackdropFreeze
import dev.lelonio.square.ui.components.Artwork
import dev.lelonio.square.ui.components.CHOICE_MENU_WIDTH
import dev.lelonio.square.ui.components.GlassChoiceItem
import dev.lelonio.square.ui.components.GlassChoiceMenu
import dev.lelonio.square.ui.components.LazyScrollBar
import dev.lelonio.square.ui.components.SwipeToQueue
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.glass.shapes.ContinuousCapsule
import dev.lelonio.square.ui.glass.pressable
import dev.lelonio.square.ui.theme.InkDim
import dev.lelonio.square.ui.theme.footToneFor
import dev.lelonio.square.ui.theme.rememberArtworkColor
import dev.lelonio.square.ui.theme.rememberArtworkFootColor
import dev.lelonio.square.ui.theme.pageColorFor
import dev.lelonio.square.ui.theme.pageColorForHex
import dev.lelonio.square.ui.theme.softShadow
import kotlin.math.roundToInt
import java.util.Locale
import java.util.concurrent.TimeUnit
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.fill.ArrowCircleDown
import com.adamglin.phosphoricons.fill.Check
import com.adamglin.phosphoricons.fill.MagnifyingGlass
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.bold.Shuffle
import com.adamglin.phosphoricons.bold.Info
import com.adamglin.phosphoricons.bold.ArrowDown
import com.adamglin.phosphoricons.bold.CaretLeft
import com.adamglin.phosphoricons.bold.MagnifyingGlass
import com.adamglin.phosphoricons.bold.X
import com.adamglin.phosphoricons.bold.DotsThree
import com.adamglin.phosphoricons.bold.Plus
import com.adamglin.phosphoricons.bold.Check
import com.adamglin.phosphoricons.bold.Star
import com.adamglin.phosphoricons.bold.ArrowsDownUp
import com.adamglin.phosphoricons.bold.ArrowCircleDown
import com.adamglin.phosphoricons.fill.Shuffle
import com.adamglin.phosphoricons.fill.Info
import com.adamglin.phosphoricons.fill.ArrowDown
import com.adamglin.phosphoricons.fill.CaretLeft
import com.adamglin.phosphoricons.fill.Export
import com.adamglin.phosphoricons.fill.DotsThree
import com.adamglin.phosphoricons.fill.Plus
import com.adamglin.phosphoricons.fill.ArrowsDownUp
import com.adamglin.phosphoricons.fill.Star
import com.adamglin.phosphoricons.regular.Star
import com.adamglin.phosphoricons.regular.Info
import com.adamglin.phosphoricons.fill.Waveform
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.ArrowsDownUp
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.DotsThree
import com.adamglin.phosphoricons.regular.Download
import com.adamglin.phosphoricons.regular.Export
import com.adamglin.phosphoricons.regular.LinkSimple
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.Queue
import com.adamglin.phosphoricons.regular.Shuffle
import com.adamglin.phosphoricons.regular.Trash
import com.adamglin.phosphoricons.regular.X

/** How the track list is ordered. */
enum class TrackSort(@StringRes val label: Int) {
    ORIGINAL(R.string.playlist_order),
    TITLE(R.string.title),
    ARTIST(R.string.artist),
    ADDED(R.string.recently_added),
    DURATION(R.string.duration),
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistScreen(
    state: MainViewModel.PlaylistState,
    contentPadding: PaddingValues,
    nowPlayingUri: String?,
    onBack: () -> Unit,
    /**
     * Plays the list. The flag says whether what is being played is the context
     * in its own order — false once the rows are sorted or filtered, when the
     * queue is a selection out of the playlist rather than the playlist.
     */
    onPlay: (List<CatalogTrack>, Int, Boolean) -> Unit,
    onEnqueue: (CatalogTrack) -> Unit,
    onShuffle: (List<CatalogTrack>) -> Unit,
    /** Opens the app-wide "add to playlist" sheet for one track. */
    onAddToPlaylist: (CatalogTrack) -> Unit,
    /**
     * Opens the track menu, which the app draws rather than this screen.
     *
     * It has to be above the tab bar and the now-playing bar, and those are
     * drawn over the whole navigation host — nothing inside a screen can reach
     * that far up.
     */
    onTrackMenu: (CatalogTrack) -> Unit,
    onOpenItem: (dev.lelonio.square.data.SearchItem) -> Unit = {},
    /** Follows or unfollows the artist this page is about. */
    onToggleFollow: () -> Unit = {},
    /** Keeps the record on the artist page's top card, or lets it go. */
    onToggleLatestSaved: () -> Unit = {},
    /** Whether this page is kept on the phone, and how far along that is. */
    downloadState: dev.lelonio.square.data.OwnerState = dev.lelonio.square.data.OwnerState.None,
    onToggleDownload: () -> Unit = {},
    /** False where a download makes no sense: the local files shelf, YouTube. */
    canDownload: Boolean = false,
    /**
     * No connection, so only what is on the phone can be played.
     *
     * Rows for tracks that are not downloaded stay visible and go quiet: a
     * playlist that silently lost half its songs would be a worse answer than
     * one that shows what it has and what it is missing.
     */
    offline: Boolean = false,
    /**
     * What each row should say about itself.
     *
     * A lambda rather than a map, because the map changes several times a
     * second while a playlist downloads and threading a new one through the
     * screen would recompose the whole list for one row's ring.
     */
    trackDownload: (CatalogTrack) -> dev.lelonio.square.data.DownloadState = {
        dev.lelonio.square.data.DownloadState.None
    },
    /** Hands the page's own link to whoever wants it. */
    /**
     * Asks for the permission to read the music on the phone.
     *
     * Only ever called from the local files shelf; see
     * MainViewModel.openLocalFiles.
     */
    onAskLocalPermission: () -> Unit = {},
    /** Opens the sheet with everything else this page can do. */
    onMenu: () -> Unit = {},
    /** Keeps the page in the library, or lets it go. */
    onToggleSaved: () -> Unit = {},
    /** The remembered track order, and where a change to it is stored. */
    storedSort: String? = null,
    onSortChange: (String) -> Unit = {},
    /** And which way round it runs; see the same menu. */
    storedSortDescending: Boolean = false,
    onSortDescendingChange: (Boolean) -> Unit = {},
) {
    var query by remember(state.uri) { mutableStateOf("") }
    var searching by remember(state.uri) { mutableStateOf(false) }
    // Opening and closing the field, as one number.
    //
    // The field used to appear and disappear on the frame the button was
    // pressed, which is the one thing on this page that happened without
    // travelling: the capsule vanished and a grey box stood where it had been.
    // Now the capsule fades and the field grows out from under the way out,
    // and every part of that reads this — inside a `layout` or a
    // `graphicsLayer`, never in composition, so a frame of the opening costs a
    // measure and a draw and nothing recomposes at all. Same discipline as the
    // header's own collapse and the bottom bar's fold.
    val searchOpen = remember { Animatable(0f) }
    val readSearch = remember { { searchOpen.value } }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(searching) {
        // Let the keyboard go before the field does, or it stays up over a page
        // with nothing left to type into.
        if (!searching && searchOpen.value > 0f) focusManager.clearFocus()
        searchOpen.animateTo(
            if (searching) 1f else 0f,
            // Soft rather than snappy: this is a pane of glass stretching, not
            // a switch. The same spring the bar settles on.
            spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
        )
    }
    // Composed for the whole of the travel, not only while the field is wanted:
    // closing has to be watched too. Derived so this flips twice a search
    // rather than once a frame.
    val searchPresent by remember {
        derivedStateOf { searching || searchOpen.value > 0.001f }
    }
    // An artist is not a list with a cover on top of it. It is a photograph of
    // a person with their name written across it, and the songs come after —
    // which is a different page, not a differently-worded one, so the header,
    // the height it opens at and what the rows say are all chosen from here.
    val isArtist = state.kind == MainViewModel.DetailKind.ARTIST
    // The follower count and the genres, behind the button that asks for them.
    // On the reference they are a screen of their own reached from an "i"; here
    // they unfold in place, which is the same answer without the round trip.
    var infoOpen by remember(state.uri) { mutableStateOf(false) }
    // Asked before a kept page is given back to the network. The button that
    // says "this is on your phone" is also how it comes off again, so the
    // question is what stops a mis-tap from deleting a hundred songs.
    var confirmingRemoval by remember { mutableStateOf(false) }
    // Seeded from the stored choice and written back on change: picking an
    // order and finding it gone on the next playlist is the kind of thing that
    // makes a setting feel like it did not take.
    // A station is the one list whose order is the whole of it: Spotify built
    // this sequence for this listener a minute ago, and sorting it by title
    // throws away the only thing that made it a radio rather than a bag of
    // songs. So the stored preference does not reach it.
    val isStation = state.uri?.startsWith("spotify:station:") == true
    var sort by remember(storedSort, isStation) {
        mutableStateOf(
            if (isStation) {
                TrackSort.ORIGINAL
            } else {
                TrackSort.entries.firstOrNull { it.name == storedSort } ?: TrackSort.ORIGINAL
            },
        )
    }
    var descending by remember(storedSortDescending) { mutableStateOf(storedSortDescending) }
    var sortOpen by remember { mutableStateOf(false) }

    // Whether the rows on screen are the playlist itself, in its order. Sorting
    // or searching makes them a selection out of it, and playing that as the
    // context would have Spotify play the playlist's own order instead of the
    // one on screen.
    val asContext = sort == TrackSort.ORIGINAL && !descending && query.isBlank()

    // Whether the top songs are shown in full.
    //
    // An artist page is several sections and the songs are one of them, so it
    // opens on the handful the reference shows and the heading is the way to
    // the rest. Kept per artist: opening a second artist starts closed again.


    // Derived, not stored: keeping a second list in state would leave the two
    // able to disagree after a reload.
    val visible = remember(state.tracks, query, sort, descending) {
        state.tracks
            .filter { it.matches(query) }
            .sortedWith(sort.comparator())
            // Reversed after sorting rather than by a second comparator: the
            // playlist's own order has no comparator to invert, and turning it
            // upside down is exactly what "descending" means there too.
            .let { if (descending) it.reversed() else it }
    }

    // Taken from *this* screen's cover, not from whatever is playing. The two
    // are usually different things, and colouring an album page after an
    // unrelated track makes the page look like it belongs to something else.
    // Whose record this is. An album says its artist; a playlist says nothing
    // here, because the name under a playlist's title on the reference is the
    // curator, and Spotify's owner field is an account id as often as a person.
    val byline = remember(state.kind, state.tracks, state.byline) {
        if (state.kind == MainViewModel.DetailKind.ALBUM) {
            state.tracks.firstOrNull()?.artist.orEmpty()
        } else {
            state.byline
        }
    }

    // The third line: the year for a record, and for a playlist how long ago
    // somebody last put something in it — which is the one fact about a list
    // that changes, and the one the reference prints here.
    val updated = remember(state.kind, state.tracks) {
        if (state.kind == MainViewModel.DetailKind.PLAYLIST) {
            state.tracks.mapNotNull { it.addedAt }.maxOrNull()
        } else {
            null
        }
    }

    // Taken from the picture the page is actually showing, which since Apple's
    // artwork arrived is not always Spotify's square: colouring a page after an
    // image it is not displaying is what leaves the header and the page below it
    // visibly two different browns.
    val accent by rememberArtworkColor(state.heroUrl ?: state.coverUrl ?: state.artworkUrl)

    // The colour of the picture's own bottom edge, which is what the page below
    // it carries on in; see rememberArtworkFootColor.
    val heroFoot by rememberArtworkFootColor(
        (state.heroUrl ?: state.coverUrl ?: state.artworkUrl)
            .takeIf { state.heroUrl != null || !state.heroPending },
    )
    // The catalogue's own colour for the page where there is one, and a colour
    // worked out from the cover where there is not. Theirs is chosen for the
    // record; ours is the most common colour in a photograph, which on a
    // portrait is usually somebody's face.
    // White, with the page's own colour in it.
    //
    // The reference never draws pure white on a coloured page: on a green page
    // its icons are a green-tinted white, and that is most of why the controls
    // look like part of the picture rather than pasted onto it. A sixth of the
    // page colour is enough to see and not enough to read as grey.
    // Not remembered: the tint it is built from now depends on which way the
    // app is being read, and that changes under the composition rather than
    // with the record.
    val inkSource = remember(accent, state.tintHex) {
        state.tintHex
            ?.let { hex -> runCatching { Color(android.graphics.Color.parseColor("#$hex")) }.getOrNull() }
            ?: accent
    }

    // Not remembered: both halves of this read the theme now, so the answer
    // changes when the system does. It is two lerps.
    // The record's own colour, as dark or as light as it is.
    //
    // Not taken up towards paper on the light side, which is what had a page
    // the reference draws in dark brown coming out pink: the catalogue picked
    // that colour to be a page, and the reference uses it the same way whether
    // the phone is set to light or dark. The app's own chrome still follows the
    // system; this is the record's, and it owns its ground.
    val pageColor = if (state.tintHex != null) {
        pageColorForHex(state.tintHex, lift = false)
    } else {
        footToneFor(heroFoot ?: accent, mix = 0.5f, lift = false)
    }

    // And the ink this page needs, which its own ground decides — not the
    // system's setting. A brown page wants light letters in both settings, and
    // that is exactly what the reference does; see inkOn.
    // The catalogue's own ink for this page where it gave one — which is what
    // makes the letters the same letters as the reference's, rather than a
    // near-white we worked out ourselves and got slightly pink.
    val catalogueInk = remember(state.inkHex) {
        state.inkHex?.let { hex ->
            runCatching { Color(android.graphics.Color.parseColor("#$hex")) }.getOrNull()
        }
    }
    val baseInk = dev.lelonio.square.ui.theme.inkOn(pageColor)
    // What the glass carries, which is not what the page carries.
    //
    // The letters on this page belong to the record: a dark brown page keeps
    // white text in both settings, because the catalogue chose that pairing.
    // The floating controls do not belong to the record — they are the app's
    // own chrome, made of the same film as the bars, and that film follows the
    // phone: white in the light setting, dark in the dark one. Tinting their
    // icons by the record put white glyphs on a white pane whenever a dark
    // record was opened in the light setting.
    val chromeInk = androidx.compose.material3.MaterialTheme.colorScheme.onSurface

    val pageInk = catalogueInk
        ?: inkSource?.let { inkTintedBy(it, baseInk) }
        ?: baseInk

    // What the glass on this screen refracts.
    //
    // Not the app's backdrop: that is the blurred cover of whatever is playing,
    // so the buttons here — back, play, shuffle, search — picked up the colour
    // of an unrelated track while the page around them was tinted from this
    // cover.
    val pageBackdrop = rememberLayerBackdrop()

    /** The rows, for the one piece of glass that opens over them. */
    val listBackdrop = rememberLayerBackdrop()
    val listBackdropFreeze = rememberBackdropFreeze()

    var sortAnchor by remember { mutableStateOf(IntOffset.Zero) }

    val density = LocalDensity.current
    val listState = rememberLazyListState()

    val topPadding = contentPadding.calculateTopPadding()
    val collapsedHeight = topPadding + COLLAPSED_BAR_HEIGHT
    val heroHeight = HERO_HEIGHT

    // The header is not in the list. It sits above it and shrinks as the list is
    // dragged upwards, which is the difference between a hero that collapses and
    // a hero that scrolls away with a bar appearing over it.
    //
    // Nested scroll is what makes the two feel like one surface: the drag
    // reaches the header first and only what the header cannot use scrolls the
    // list, so a single gesture closes the cover and then carries on into the
    // tracks.
    val collapseRange = with(density) { (heroHeight - collapsedHeight).toPx() }
    var collapsed by remember(state.uri) { mutableFloatStateOf(0f) }
    LaunchedEffect(state.uri) {
        listState.scrollToItem(0)
    }
    val collapseFraction = { (collapsed / collapseRange).coerceIn(0f, 1f) }

    // How much of a drag the header itself uses up, closing on the way up and
    // opening on the way down. Written once and used twice: by the list's
    // nested scroll, and by a drag that starts on the picture.
    val takeForHeader: (Float) -> Float = { delta ->
        if (delta < 0f) {
            val take = (-delta).coerceAtMost(collapseRange - collapsed)
            collapsed += take
            -take
        } else {
            val give = delta.coerceAtMost(collapsed)
            collapsed -= give
            give
        }
    }

    val headerScroll = remember(collapseRange) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f) return Offset.Zero
                return Offset(0f, takeForHeader(available.y))
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y <= 0f) return Offset.Zero
                return Offset(0f, takeForHeader(available.y))
            }
        }
    }

    // The picture scrolls the page too.
    //
    // The header is not in the list — it is pinned above it — so a drag that
    // began on the cover reached nothing at all, and the page only moved if you
    // happened to start on a song. This hands those drags the same treatment
    // the list's own get: the header closes first, and whatever is left of the
    // gesture goes into the rows.
    val scope = rememberCoroutineScope()
    val headerDrag = rememberScrollableState { delta ->
        val used = takeForHeader(delta)
        val rest = delta - used
        if (rest != 0f) used + listState.dispatchRawDelta(-rest).let { -it } else used
    }

    val heroPx = with(density) { heroHeight.roundToPx() }
    val collapsedPx = with(density) { collapsedHeight.roundToPx() }

    // Where the page colour starts giving way, as a fraction of the screen: just
    // past the bottom of the header, whatever height this page's header is.
    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    val floorStart = ((heroHeight + 40.dp) / screenHeight).coerceIn(0.35f, 0.95f)

    // How tall the picture itself is, which is not the same as how tall the
    // header is: the catalogue files some artists as a tall portrait and some
    // as a square, and each is drawn at the shape it was made in. What is left
    // of the header below it is the picture carrying on, blurred.
    val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
    val artHeight = state.heroAspect
        ?.let { (screenWidth / it).coerceIn(200.dp, heroHeight) }
        ?: heroHeight

    /** Where the picture's own bottom edge falls, as a fraction of the screen. */
    val heroFraction = (artHeight / screenHeight).coerceIn(0.3f, 0.95f)


    // Everything on this page reads the app's ink, and on this page the app's
    // ink is the page's own: see LocalInkOverride. It saves every row, label
    // and icon here from having to know that a record's page does not follow
    // the system's light and dark.
    // Everything here reads either the app's ink or Material's own colours, and
    // on this page both have to come from the record rather than from the
    // phone: a dark brown page in the system's light setting was drawing its
    // track titles in the light theme's near-black.
    androidx.compose.runtime.CompositionLocalProvider(
        dev.lelonio.square.ui.theme.LocalInkOverride provides pageInk,
        androidx.compose.material3.LocalContentColor provides pageInk,
    ) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            onSurface = pageInk,
            onSurfaceVariant = pageInk.copy(alpha = 0.66f),
            onBackground = pageInk,
        ),
        typography = MaterialTheme.typography,
    ) {
    Box(Modifier.fillMaxSize()) {
        // The page, without the things that sample it.
        //
        // The layer must hold nothing that draws from it: recording the whole
        // screen put the glass buttons inside the layer those same buttons read,
        // and the render tree recursed until it overflowed the stack.
        //
        // It cannot hold *only* the page colour either, which is what it did
        // until now. A flat fill refracts to a flat fill, so the buttons came
        // out as plain translucent discs — the glass had nothing to bend. The
        // cover behind them is the thing worth bending, so it lives here, below
        // the layer boundary, and the header above draws only the text and the
        // controls over it.
        Box(
            Modifier
                .fillMaxSize()
                // One colour, held all the way down, and the picture ends on it.
                //
                // The player is the screen made of the record — there the cover
                // is carried past its own edge and nothing covers it. A page is
                // not that: it is a list with a picture at the top, and the
                // reference gives it a single flat ground, the colour the
                // catalogue filed for this record, with the photograph
                // dissolving into that colour rather than into a blurred
                // continuation of itself.
                .background(pageColor)
                .layerBackdrop(pageBackdrop),
        ) {
            // Every page is its own picture, filling the top of the screen:
            // the editorial photograph where the catalogue has one, and the
            // cover itself where it does not. A square sleeve floating in the
            // middle of a coloured field is the older design, and the reference
            // only still uses it for the records nobody made a picture for.
            HeroArt(
                // Nothing at all while the other catalogue is still being
                // asked: this page is about to have a different photograph, and
                // opening on Spotify's only to replace it a second later is the
                // swap the reader sees. The wait is capped in the view model.
                artworkUrl = (state.heroUrl ?: state.coverUrl ?: state.artworkUrl)
                    .takeIf { state.heroUrl != null || !state.heroPending },
                // Played over the still where the record has one; see
                // MotionCover. Offline it is left alone: a header that streams
                // video is the last thing a page with no network should try.
                motionUrl = state.motionUrl.takeIf { !offline },
                name = state.name,
                pageColor = pageColor,
                heroPx = heroPx,
                collapsedPx = collapsedPx,
                collapse = collapseFraction,
                imageAspect = state.heroAspect,
                pending = state.heroPending,
            )
        }

        Column(Modifier.fillMaxSize()) {
        Box(
            // A drag that starts on the cover scrolls the page; see headerDrag.
            // Not reversed: the lambda already speaks the gesture's own
            // language — a finger going down is a positive delta, and it opens
            // the header and scrolls the rows back up.
            Modifier.scrollable(
                state = headerDrag,
                orientation = Orientation.Vertical,
            ),
        ) {
        if (isArtist) {
            ArtistHeader(
                ink = pageInk,
                name = state.name,
                logoUrl = state.logoUrl,
                avatarUrl = state.heroUrl ?: state.coverUrl ?: state.artworkUrl,
                verified = state.verified,
                monthlyListeners = state.monthlyListeners,
                following = state.following,
                onToggleFollow = onToggleFollow,
                onMenu = onMenu,
                infoOpen = infoOpen,
                onToggleInfo = { infoOpen = !infoOpen },
                pageColor = pageColor,
                backdrop = pageBackdrop,
                collapse = collapseFraction,
                heroHeight = heroHeight,
                collapsedHeight = collapsedHeight,
                topPadding = topPadding,
                searching = searching,
                onBack = onBack,
                onPlay = { visible.takeIf { it.isNotEmpty() }?.let { onPlay(it, 0, asContext) } },
                onShuffle = { visible.takeIf { it.isNotEmpty() }?.let(onShuffle) },
                onToggleSearch = {
                    searching = !searching
                    if (!searching) query = ""
                },
            )
        } else {
        DetailHeader(
            ink = pageInk,
            name = state.name,
            kind = state.kind,
            description = state.description,
            pageColor = pageColor,
            saved = state.saved,
            onToggleSaved = onToggleSaved,
            following = state.following.takeIf {
                state.kind == MainViewModel.DetailKind.ARTIST
            },
            onToggleFollow = onToggleFollow,
            download = downloadState,
            onToggleDownload = {
                val kept = downloadState !is dev.lelonio.square.data.OwnerState.None
                if (kept) confirmingRemoval = true else onToggleDownload()
            },
            // Only where the reference puts one: a record or a list. An artist
            // page keeps it in the menu, beside the rest of what a page can do.
            canDownload = canDownload && !isArtist,
            byline = byline,
            year = state.tracks.firstOrNull()?.year.orEmpty()
                .takeIf { state.kind == MainViewModel.DetailKind.ALBUM }
                .orEmpty(),
            updatedAt = updated,
            backdrop = pageBackdrop,
            collapse = collapseFraction,
            collapsedHeight = collapsedHeight,
            topPadding = topPadding,
            onBack = onBack,
            onPlay = {
                visible.takeIf { it.isNotEmpty() }?.let { onPlay(it, 0, asContext) }
            },
            onShuffle = { visible.takeIf { it.isNotEmpty() }?.let(onShuffle) },
            onToggleSearch = {
                searching = !searching
                if (!searching) query = ""
            },
            searching = searching,
            heroHeight = heroHeight,
        )
        }
        }

        LazyColumn(
            state = listState,
            // Recorded so the sort menu has something to blur. It opens over
            // the rows, and the page layer holds only the cover and the page
            // colour — over a flat fill, glass has nothing to bend and comes
            // out as a grey card. Safe to record: the menu is drawn outside the
            // list, so nothing in this layer samples it.
            // Frozen during scroll so the layer is not re-recorded every frame,
            // taking frame time down from 47ms to 1.9ms.
            modifier = Modifier
                .nestedScroll(listBackdropFreeze.connection)
                .layerBackdrop(listBackdrop, frozen = { listBackdropFreeze.frozen() || listState.isScrollInProgress })
                .nestedScroll(headerScroll),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
        ) {
            // Behind the "i". On the reference this is a screen of its own;
            // unfolding it under the photograph says the same thing without
            // taking the reader off the page they came for.
            //
            // Inside the list, not above it. Above it, in the column that holds
            // the header, it took its height out of the list's — and a bio long
            // enough to fill the rest of the screen left the list with none, so
            // opening the "i" stopped the page scrolling entirely. The button
            // that opens it is still outside the list, which is what the old
            // note here was actually about.
            if (isArtist) {
                item(contentType = "about") {
                    AnimatedVisibility(visible = infoOpen) {
                        ArtistAbout(
                            followers = state.followers,
                            genres = state.genres,
                            origin = state.origin,
                            bio = state.notes,
                        )
                    }
                }
            }

            if (!isArtist) {
                state.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                    item(contentType = "notes") { EditorialNotes(notes) }
                }
            }

            if (state.tracks.isNotEmpty()) {
                item(contentType = "sectionTitle") {
                    if (isArtist) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Populares",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    } else {
                        SectionHeader(
                            title = stringResource(R.string.tracks),
                            large = false,
                            sort = sort,
                            onSortOpen = { sortOpen = it },
                            onAnchor = { sortAnchor = it },
                            expandable = false,
                            expanded = false,
                        )
                    }
                }
            }

            when {
                state.loading -> item(contentType = "status") {
                    StatusBox { CircularProgressIndicator(strokeWidth = 2.dp) }
                }

                state.needsPermission -> item(contentType = "status") {
                    StatusBox {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                stringResource(R.string.local_files_needs_permission),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(14.dp))
                            LiquidButton(
                                onClick = onAskLocalPermission,
                                backdrop = pageBackdrop,
                                contentPadding = 18.dp,
                            ) {
                                Text(
                                    stringResource(R.string.local_files_allow),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                }

                state.error != null -> item(contentType = "status") {
                    StatusBox {
                        Text(
                            state.error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                visible.isEmpty() -> if (!isArtist) item(contentType = "status") {
                    StatusBox {
                        Text(
                            if (query.isBlank()) stringResource(R.string.no_tracks)
                            else stringResource(R.string.no_results_for, query),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (state.albums.isEmpty() && state.singles.isEmpty() && !state.loading) {
                    item(contentType = "status") {
                        StatusBox {
                            Text(
                                stringResource(R.string.no_tracks),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                else -> itemsIndexed(
                    items = if (isArtist && query.isBlank()) {
                        visible.take(5)
                    } else {
                        visible
                    },
                    key = { index, track -> "$index ${track.uri}" },
                    contentType = { _, _ -> "track" },
                ) { index, track ->
                    SwipeToQueue(onQueue = { onEnqueue(track) }) {
                        TrackRow(
                            track = track,
                            position = index + 1,
                            rank = if (isArtist) index + 1 else null,
                            subtitle = when {
                                isArtist -> track.releaseLine()
                                state.kind == MainViewModel.DetailKind.ALBUM ->
                                    formatDuration(track.durationMs)
                                else -> null
                            },
                            numbered = state.kind == MainViewModel.DetailKind.ALBUM,
                            isCurrent = track.uri == nowPlayingUri,
                            onClick = { onPlay(visible, index, asContext) },
                            onMenu = { onTrackMenu(track) },
                            download = trackDownload(track),
                            unavailable = offline &&
                                !track.uri.startsWith("local:") &&
                                trackDownload(track) !=
                                dev.lelonio.square.data.DownloadState.Done,
                        )
                    }
                }
            }

            if (state.albums.isNotEmpty() && query.isBlank()) {
                item(contentType = "albums") {
                    AlbumStrip(
                        albums = state.albums,
                        title = stringResource(R.string.albums),
                        onOpen = onOpenItem,
                    )
                }
            }

            if (state.singles.isNotEmpty() && query.isBlank()) {
                item(contentType = "singles") {
                    AlbumStrip(
                        albums = state.singles,
                        title = stringResource(R.string.singles_and_eps),
                        onOpen = onOpenItem,
                    )
                }
            }

            if (!isArtist && state.appearsOn.isNotEmpty()) {
                item(contentType = "appearsOn") {
                    AlbumStrip(
                        albums = state.appearsOn,
                        title = stringResource(R.string.appears_on),
                        onOpen = onOpenItem,
                    )
                }
            }

            if (!isArtist && state.artistPlaylists.isNotEmpty()) {
                item(contentType = "artistPlaylists") {
                    AlbumStrip(
                        albums = state.artistPlaylists,
                        title = stringResource(R.string.artist_playlists),
                        onOpen = onOpenItem,
                    )
                }
            }

            // A long playlist arrives a batch at a time and this is the tail of
            // it still coming. Deliberately quiet: the list above is already
            // readable and playable, and a spinner in the middle of the screen
            // would say otherwise.
            if (state.loadingMore) {
                item(contentType = "more") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.more_tracks_coming),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
            }
        }
        }

        dev.lelonio.square.ui.components.ConfirmSheet(
            visible = confirmingRemoval,
            title = stringResource(R.string.remove_download),
            message = stringResource(R.string.remove_download_confirm, state.name),
            confirmLabel = stringResource(R.string.remove_download_confirm_action),
            cancelLabel = stringResource(R.string.cancel),
            backdrop = pageBackdrop,
            destructive = true,
            onConfirm = {
                confirmingRemoval = false
                onToggleDownload()
            },
            onDismiss = { confirmingRemoval = false },
        )

        LazyScrollBar(
            state = listState,
            startAfter = "track",
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(
                    top = contentPadding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding(),
                ),
        )

        // The chrome over the picture, outside the page's own ink.
        //
        // This page tells everything inside it to write in the record's ink,
        // because the record owns the page — see LocalInkOverride. These
        // buttons are not the page: they are the app's own controls, made of
        // the film the bars are made of, and the glass reads that same override
        // to pick its film. On a near-black record in the light setting that
        // came out as a dark pane with dark glyphs on it, while the bar at the
        // foot of the same screen was white. Cleared here, they follow the
        // phone like the rest of the chrome: a white pane with dark icons.
        androidx.compose.runtime.CompositionLocalProvider(
            dev.lelonio.square.ui.theme.LocalInkOverride provides null,
        ) {

        // Share and everything else, in one capsule opposite the back button.
        // Two round buttons side by side would have read as two destinations;
        // one pane with a divide down it reads as what it is, a place where the
        // page's own actions live.
        // One pane of glass with a line down it, not two buttons side by side.
        // Two would have read as two destinations; one capsule reads as what it
        // is, the place the page's own actions live.
        // The field takes the capsule's place while it is open, running from the
        // back button to the edge — the same move the bottom bar makes when its
        // search opens. It used to be a row near the top of the list, which is
        // why opening it from halfway down the page appeared to do nothing.
        if (searchPresent) {
            dev.lelonio.square.ui.components.ListSearchField(
                query = query,
                onQuery = { query = it },
                // The same glass as the capsule it replaces, refracting the
                // same cover, so what happens on the press is one control
                // turning into another rather than a box arriving.
                backdrop = pageBackdrop,
                height = 42.dp,
                ink = chromeInk,
                hint = chromeInk.copy(alpha = 0.55f),
                autoFocus = true,
                // The writing arrives after the pane has somewhere to put it.
                contentAlpha = { ((readSearch() - 0.35f) / 0.65f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(
                        top = contentPadding.calculateTopPadding() + 8.dp,
                        start = 68.dp,
                        // Room for the way out, which the capsule was carrying
                        // until the field took its place.
                        end = 62.dp,
                    )
                    // A pane cannot appear at full width out of nothing, and it
                    // cannot fade in either — glass that fades reads as a
                    // photograph of glass. It arrives the width of the button
                    // beside it and stretches to the back arrow.
                    .growingFromEnd(readSearch, from = 42.dp),
            )

            LiquidButton(
                onClick = {
                    searching = false
                    query = ""
                },
                backdrop = pageBackdrop,
                modifier = Modifier
                    .padding(top = contentPadding.calculateTopPadding() + 8.dp, end = 14.dp)
                    .align(Alignment.TopEnd)
                    // Takes the capsule's place as the capsule gives it up.
                    .graphicsLayer { alpha = (readSearch() / 0.45f).coerceIn(0f, 1f) }
                    .size(42.dp),
                contentHeight = 42.dp,
                contentPadding = 0.dp,
            ) {
                Icon(
                    PhosphorIcons.Bold.X,
                    contentDescription = stringResource(R.string.clear),
                    tint = chromeInk,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (!isArtist) {
        GlassCapsule(
            backdrop = pageBackdrop,
            modifier = Modifier
                .padding(top = contentPadding.calculateTopPadding() + 8.dp, end = 14.dp)
                .align(Alignment.TopEnd)
                // Gone by the time the field is a third open, and faded before
                // that. Out of the layout rather than merely invisible: an
                // invisible capsule still takes every touch aimed at the field.
                .graphicsLayer { alpha = (1f - readSearch() / 0.3f).coerceIn(0f, 1f) }
                .layout { measurable, constraints ->
                    if (readSearch() >= 0.3f) {
                        layout(0, 0) {}
                    } else {
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                },
        ) {
            // Keeping the page, first in the row — where the reference puts the
            // one action that changes what your library holds, apart from the
            // controls that only start the music. Absent while the answer is
            // unknown and on the pages the question does not apply to.
            if (state.saved != null) {
                CollapsingSlot(collapseFraction) {
                CapsuleAction(
                    tint = chromeInk,
                    icon = if (state.saved) {
                        PhosphorIcons.Bold.Check
                    } else {
                        PhosphorIcons.Bold.Plus
                    },
                    description = stringResource(
                        if (state.saved) R.string.remove_from_library else R.string.add_to_library,
                    ),
                    onClick = onToggleSaved,
                )
                }
                CollapsingSlot(collapseFraction) { CapsuleDivider() }
            }
            // Filtering the list, where the link used to be.
            //
            // A link is something you do once and from a menu; narrowing a
            // hundred songs down to the one you meant is something you do while
            // the page is open, and the reference gives that the place next to
            // the page's other actions. Sharing moved under the dots, which is
            // where the rest of a page's occasional business already lives.
            CapsuleAction(
                icon = if (searching) {
                    PhosphorIcons.Bold.X
                } else {
                    PhosphorIcons.Bold.MagnifyingGlass
                },
                description = stringResource(R.string.search_in_tracks),
                onClick = {
                    searching = !searching
                    if (!searching) query = ""
                },
                tint = chromeInk,
            )
            CollapsingSlot(collapseFraction) { CapsuleDivider() }
            CollapsingSlot(collapseFraction) {
                CapsuleAction(
                    icon = PhosphorIcons.Bold.DotsThree,
                    description = stringResource(R.string.more),
                    onClick = onMenu,
                    tint = chromeInk,
                )
            }
        }
        }

        // Floating rather than a top bar: the list scrolls under it, so the
        // picture stays uninterrupted. It gives way to the collapsed bar, which
        // carries a back button of its own.
        LiquidButton(
            onClick = onBack,
            backdrop = pageBackdrop,
            modifier = Modifier
                .padding(top = contentPadding.calculateTopPadding() + 8.dp, start = 14.dp)
                .align(Alignment.TopStart)
                .size(42.dp),
            contentHeight = 42.dp,
            contentPadding = 0.dp,
        ) {
            Icon(
                PhosphorIcons.Bold.CaretLeft,
                contentDescription = stringResource(R.string.back),
                tint = chromeInk,
                modifier = Modifier.size(20.dp),
            )
        }

        }

        // In the page rather than in a popup of its own: the sort button is up
        // by the header, well clear of the bars that would otherwise be drawn
        // over this, so it can be real glass instead of a dark card.
        GlassChoiceMenu(
            visible = sortOpen,
            anchor = sortAnchor.leftOf(density),
            backdrop = listBackdrop,
            onDismiss = { sortOpen = false },
        ) {
            TrackSort.entries.forEach { option ->
                GlassChoiceItem(stringResource(option.label), selected = option == sort) {
                    sort = option
                    onSortChange(option.name)
                    sortOpen = false
                }
            }
            dev.lelonio.square.ui.components.GlassMenuRule()

            // Deliberately leaves the menu open: the direction is the one choice
            // people flip back and forth to compare, and reopening for each flip
            // makes a sort feel heavy.
            GlassChoiceItem(
                stringResource(R.string.sort_descending),
                selected = descending,
            ) {
                descending = !descending
                onSortDescendingChange(descending)
            }
        }

    }
    }
    }
}

/**
 * Turns a button's position into where a menu hanging off its right edge goes.
 *
 * Menus open leftwards from the control that owns them, because every one of
 * those controls is at the right-hand edge of the screen.
 */
private fun IntOffset.leftOf(density: androidx.compose.ui.unit.Density): IntOffset {
    val width = with(density) { CHOICE_MENU_WIDTH.roundToPx() }
    val gap = with(density) { 8.dp.roundToPx() }
    return IntOffset((x - width + gap).coerceAtLeast(gap), y + gap)
}

/**
 * The picture-first header: cover full bleed, everything else on top of it.
 *
 * The old header put a 118dp thumbnail beside the title. This is the layout the
 * reference uses, and the reason to prefer it is not decoration — the cover is
 * the fastest thing to recognise, and at thumbnail size it is doing none of that
 * work. The gradient is what makes it usable: without it the title and the
 * controls sit on whatever happens to be in the bottom of the image, which for
 * a light cover means white on white.
 */
@Composable
private fun DetailHeader(
    /** White with the page's colour in it; see where it is worked out. */
    ink: Color,
    name: String,
    kind: MainViewModel.DetailKind,
    description: String,
    /** The page's own colour; the solid Play button prints its label in it. */
    pageColor: Color,
    /** Whether the page is kept in the library; null hides the button. */
    saved: Boolean?,
    onToggleSaved: () -> Unit,
    /**
     * Whether the artist is followed; null on every page that is not one.
     *
     * Here rather than under the name, where it used to sit: following an
     * artist is the same kind of act as saving an album, and both belong beside
     * the button that starts the music. Under the follower count it read as
     * part of the caption.
     */
    following: Boolean?,
    onToggleFollow: () -> Unit,
    /** Whose record it is: the artist, or the account that made the list. */
    byline: String,
    /** When it came out, where the source says. */
    year: String,
    /** When a playlist last changed, ISO-8601; null on everything else. */
    updatedAt: String?,
    /** Kept on the phone, being kept, or not; see the button beside play. */
    download: dev.lelonio.square.data.OwnerState,
    onToggleDownload: () -> Unit,
    canDownload: Boolean,
    backdrop: Backdrop,
    /** 0 fully open, 1 collapsed into the bar. A lambda, so reading it costs a
     * re-layout rather than a recomposition of the header on every frame. */
    collapse: () -> Float,
    collapsedHeight: Dp,
    topPadding: Dp,
    searching: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onToggleSearch: () -> Unit,
    /** What the header opens at, which the artist page makes taller. */
    heroHeight: Dp = HERO_HEIGHT,
) {
    val density = LocalDensity.current
    val heroPx = with(density) { heroHeight.roundToPx() }
    val collapsedPx = with(density) { collapsedHeight.roundToPx() }

    Box(
        Modifier
            .fillMaxWidth()
            .heroCollapse(heroPx, collapsedPx, collapse),
    ) {
        // No cover here: it is drawn below, inside the layer these controls
        // refract. See the note where that layer is recorded.

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 20.dp)
                // Gone by the time the header is half closed: below that there
                // is no room for a cover and a two-line title, and watching them
                // be squeezed is worse than watching them leave.
                .graphicsLayer { alpha = (1f - collapse() * 2f).coerceIn(0f, 1f) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The record itself, as an object on the page. The reference draws
            // it this way and it is not decoration: a square with an edge and a
            // shadow is a thing you are looking at, where a picture bled to the
            // screen's edges is a background the words happen to sit on.
            Text(
                text = name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = ink,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 16.dp),
            )

            // Whose record it is, in the page's own accent — the one line the
            // reference colours, because it is the one that is also a link.
            if (byline.isNotEmpty()) {
                Text(
                    text = byline,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = ink.copy(alpha = 0.92f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            Text(
                text = when {
                    updatedAt != null -> stringResource(R.string.updated_ago, agoOf(updatedAt))
                    year.isNotEmpty() -> stringResource(kind.label) + " · " + year
                    else -> stringResource(kind.label)
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = ink.copy(alpha = 0.66f),
                modifier = Modifier.padding(top = 6.dp),
            )

            // Shuffle, play, keep — in that order and in those three shapes,
            // which is exactly how the reference lays out a list: a round
            // control either side of one wide button that says what it does.
            Row(
                Modifier.padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleAction(
                    icon = PhosphorIcons.Bold.Shuffle,
                    description = stringResource(R.string.shuffle),
                    size = 46.dp,
                    backdrop = backdrop,
                    onClick = onShuffle,
                    tint = ink,
                )

                // The one solid control on the page, and the only one that says
                // what it does in words.
                Row(
                    Modifier
                        .softShadow(ContinuousCapsule, elevation = 20.dp, spot = 0.32f)
                        .clip(ContinuousCapsule)
                        .background(Color.White)
                        .pressable(onPlay, shape = ContinuousCapsule, pressedScale = 0.96f)
                        .padding(horizontal = 30.dp, vertical = 15.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        PhosphorIcons.Fill.Play,
                        contentDescription = null,
                        tint = pageColor,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        stringResource(R.string.play),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = pageColor,
                    )
                }

                // Keeping the music, where the reference keeps it: opposite
                // shuffle, on the other side of the button that starts it.
                if (canDownload) {
                    DownloadAction(
                        state = download,
                        backdrop = backdrop,
                        onClick = onToggleDownload,
                        tint = ink,
                    )
                }

                if (following != null) {
                    FollowPill(following = following, onClick = onToggleFollow)
                }

            }

            // What the list is, in its own words. Only the sources that carry
            // one have it, and a page without it simply has one line less.
            if (description.isNotEmpty()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.62f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 14.dp, start = 8.dp, end = 8.dp),
                )
            }
        }

        CollapsedBar(
            ink = ink,
            name = name,
            collapse = collapse,
            collapsedHeight = collapsedHeight,
            topPadding = topPadding,
            searching = searching,
            onBack = onBack,
            onToggleSearch = onToggleSearch,
            onShuffle = onShuffle,
            onPlay = onPlay,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

/**
 * Grows a control out of its own right-hand edge.
 *
 * How the search field opens: it keeps the place the way out already occupies
 * and takes the rest of the row from it, so the motion starts where the finger
 * was rather than in the middle of the header. The width is a real measurement
 * and not a scale — a scaled pane stretches its corners into ovals and drags
 * the writing with them.
 *
 * [progress] is read in `layout`, so opening costs a measure and nothing
 * recomposes.
 */
private fun Modifier.growingFromEnd(progress: () -> Float, from: Dp): Modifier =
    layout { measurable, constraints ->
        val full = constraints.maxWidth
        val start = from.roundToPx().coerceAtMost(full)
        val fraction = progress().coerceIn(0f, 1f)
        val width = (start + (full - start) * fraction).roundToInt().coerceAtLeast(1)
        val placeable = measurable.measure(
            constraints.copy(minWidth = width, maxWidth = width),
        )
        layout(full, placeable.height) { placeable.place(full - width, 0) }
    }

/**
 * A part of the header's capsule that leaves once the page is scrolled.
 *
 * Back and search stay for the whole of the page — one is how you leave and the
 * other is how you find a song in a list too long to read. Keeping the record
 * and the rest of its business are things you do while looking at the top of
 * the page, so they go with the top of the page.
 *
 * Faded *and* taken out of the layout. Alpha alone leaves an invisible control
 * still taking every touch that lands on it, and leaves the capsule the width
 * of three buttons with one button in it.
 *
 * The width goes continuously, which is the whole of the motion. It used to be
 * a switch — full width while the icons faded, then nothing on the frame the
 * travel finished — so the capsule spent the scroll as wide as three buttons
 * with one visible in it and then jumped to a circle. The pane is a piece of
 * glass, and a piece of glass that changes size in one frame reads as two
 * different controls.
 *
 * Both ends over the first [SLOT_TRAVEL] of the header's own travel, the way
 * the bottom bar folds: the part that stops being readable first is the part
 * that goes first, and the capsule has finished closing well before the header
 * has. What is left of the box is cropped from both sides, so the icon is
 * squeezed out of the middle rather than sliding into its neighbour.
 */
@Composable
private fun CollapsingSlot(collapse: () -> Float, content: @Composable () -> Unit) {
    val keep = { (1f - collapse() / SLOT_TRAVEL).coerceIn(0f, 1f) }
    Box(
        Modifier
            .graphicsLayer { alpha = keep() }
            .clipToBounds()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val width = (placeable.width * keep()).roundToInt()
                if (width <= 0) {
                    layout(0, 0) {}
                } else {
                    layout(width, placeable.height) {
                        placeable.place((width - placeable.width) / 2, 0)
                    }
                }
            },
    ) {
        content()
    }
}

/** How much of the header's travel a departing capsule slot uses up. */
private const val SLOT_TRAVEL = 0.45f

/**
 * What every header becomes once it is scrolled shut.
 *
 * The same title and the same controls at the size a bar can carry them,
 * arriving in the second half of the travel as the full-size version finishes
 * leaving. Shared by both headers: a page whose collapsed bar behaved
 * differently from the page next to it would read as a different app.
 */
@Composable
private fun CollapsedBar(
    /** White with the page's colour in it; see where it is worked out. */
    ink: Color,
    name: String,
    collapse: () -> Float,
    collapsedHeight: Dp,
    topPadding: Dp,
    searching: Boolean,
    onBack: () -> Unit,
    onToggleSearch: () -> Unit,
    onShuffle: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(collapsedHeight)
            .padding(top = topPadding)
            // Wide enough on both sides to clear the glass controls, which stay
            // where they are for the whole of the page rather than handing over
            // to a bar of their own; see below.
            .padding(horizontal = 68.dp)
            .graphicsLayer { alpha = (collapse() * 2f - 1f).coerceIn(0f, 1f) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The name, and nothing else.
        //
        // This bar used to grow its own back button, search, shuffle and play
        // as the header shut, and the floating glass ones faded out under them.
        // Two sets of the same controls trading places is a lot of machinery
        // for no gain — and it was the source of a real bug, since a faded
        // control still takes touches. The glass stays; this says where you are.
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The artist's photograph, and the way it stops being a photograph.
 *
 * Two copies of the same image: the sharp one, and over its lower half a blurred
 * one that fades in. That is what makes the picture dissolve into the page
 * instead of ending — a straight fade to a flat colour leaves the face crisp
 * right up to the point where it vanishes, which reads as a photo behind a
 * curtain. Softening it first is the whole trick.
 *
 * Blurred at decode time rather than with `Modifier.blur`, for the reason
 * written over the app backdrop: the modifier is a render effect over the whole
 * layer, re-run on every frame the header collapses.
 */
@Composable
private fun HeroArt(
    artworkUrl: String?,
    motionUrl: String?,
    name: String,
    pageColor: Color,
    heroPx: Int,
    collapsedPx: Int,
    collapse: () -> Float,
    /** The picture's own proportions, where the catalogue gave them. */
    imageAspect: Float? = null,
    pending: Boolean = false,
) {
    // The picture and its fade are the same everywhere the app shows a cover
    // large — here and in the player; see HeroBackdrop. What belongs to this
    // screen alone is the collapse, so that is all that is left here.
    dev.lelonio.square.ui.components.HeroBackdrop(
        artworkUrl = artworkUrl,
        title = name,
        pageColor = pageColor,
        motionUrl = motionUrl,
        imageAspect = imageAspect,
        pending = pending,
        // Measured against the reference rather than chosen.
        //
        // Ours was flat page colour by 38% of the way down the screen; theirs
        // still has the curtain behind the name at 44% and does not finish
        // until past halfway. A picture that ends above the title leaves the
        // title sitting on a panel, which is the difference you can see between
        // the two screens even when the colour underneath is identical.
        // Late, now that the picture is drawn at its own shape rather than
        // cropped into the header's.
        //
        // These are fractions of the picture, not of the screen. While it was
        // stretched over the whole header, starting at 44% put the fade
        // somewhere near the bottom of the frame; at its own height that same
        // number has the photograph half gone by its middle. What is wanted is
        // a picture that reads as a picture and gives way in its last stretch —
        // which is what the player does, and the reference with it.
        softenFrom = if (imageAspect != null) 0.58f else 0.44f,
        // Finished before the edge, not on it: a picture that is one percent
        // still there where it stops has a border, and that border is the line.
        // A long tail. The reference still has the curtain faintly behind its
        // name and buttons at 48% of the screen and only settles past 60%; ours
        // was flat page colour by 42%, which is the picture ending rather than
        // giving way. Past the picture's own foot the fade has nothing left to
        // thin, so the length has to come from starting it earlier and taking
        // it all the way to the edge.
        softenTo = if (imageAspect != null) 1f else 0.96f,
        modifier = Modifier
            .fillMaxWidth()
            .heroCollapse(heroPx, collapsedPx, collapse),
    )
}

/**
 * The artist's name across their own photograph, and the three things there are
 * to do with them.
 *
 * Deliberately not the header the other pages use. A playlist is a list with a
 * cover, so its header says what the list is and how long it runs; an artist is
 * a person, and the page is their picture with their name written on it. Under
 * the name: what they are about, play, and follow — one word each, in the order
 * a listener wants them.
 */
@Composable
private fun ArtistHeader(
    /** White with the page's colour in it; see where it is worked out. */
    ink: Color,
    name: String,
    /** Their name as Apple draws it; null falls back to setting it in type. */
    logoUrl: String?,
    avatarUrl: String?,
    verified: Boolean = true,
    monthlyListeners: String? = null,
    /** Null until Spotify has answered; the star waits rather than guessing. */
    following: Boolean?,
    onToggleFollow: () -> Unit,
    onMenu: () -> Unit = {},
    infoOpen: Boolean,
    onToggleInfo: () -> Unit,
    pageColor: Color,
    backdrop: Backdrop,
    collapse: () -> Float,
    heroHeight: Dp,
    collapsedHeight: Dp,
    topPadding: Dp,
    searching: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onToggleSearch: () -> Unit,
) {
    val density = LocalDensity.current
    val heroPx = with(density) { heroHeight.roundToPx() }
    val collapsedPx = with(density) { collapsedHeight.roundToPx() }

    Box(
        Modifier
            .fillMaxWidth()
            .heroCollapse(heroPx, collapsedPx, collapse),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .graphicsLayer { alpha = (1f - collapse() * 2f).coerceIn(0f, 1f) },
            horizontalAlignment = Alignment.Start,
        ) {
            if (logoUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(logoUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth(0.82f)
                        .heightIn(max = LOGO_HEIGHT),
                )
            } else {
                Text(
                    text = name,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontSize = nameSize(name),
                        lineHeight = nameSize(name) * 1.05f,
                        shadow = Shadow(
                            if (ink.luminance() > 0.5f) {
                                Color.Black.copy(alpha = 0.5f)
                            } else {
                                Color.White.copy(alpha = 0.75f)
                            },
                            Offset(0f, 2f),
                            18f,
                        ),
                    ),
                    fontWeight = FontWeight.Black,
                    color = ink,
                    textAlign = TextAlign.Start,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (verified) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    Icon(
                        PhosphorIcons.Fill.Check,
                        contentDescription = null,
                        tint = Color(0xFF1ED760),
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.15f))
                            .padding(2.dp),
                    )
                    Text(
                        text = "Verificado por Spotify",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = ink.copy(alpha = 0.9f),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }

            if (!monthlyListeners.isNullOrBlank()) {
                Text(
                    text = monthlyListeners,
                    style = MaterialTheme.typography.bodySmall,
                    color = ink.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (avatarUrl != null) {
                    Artwork(
                        url = avatarUrl,
                        title = name,
                        modifier = Modifier.size(36.dp),
                        corner = 6.dp,
                        decodeSize = 36.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                }

                FollowPill(
                    following = following == true,
                    onClick = onToggleFollow,
                )

                Spacer(Modifier.width(8.dp))

                IconButton(onClick = onMenu, modifier = Modifier.size(36.dp)) {
                    Icon(
                        PhosphorIcons.Bold.DotsThree,
                        contentDescription = stringResource(R.string.more),
                        tint = ink.copy(alpha = 0.85f),
                        modifier = Modifier.size(22.dp),
                    )
                }

                Spacer(Modifier.weight(1f))

                IconButton(onClick = onShuffle, modifier = Modifier.size(40.dp)) {
                    Icon(
                        PhosphorIcons.Fill.Shuffle,
                        contentDescription = stringResource(R.string.shuffle),
                        tint = Color(0xFF1ED760),
                        modifier = Modifier.size(24.dp),
                    )
                }

                Spacer(Modifier.width(8.dp))

                Box(
                    Modifier
                        .size(54.dp)
                        .softShadow(CircleShape, elevation = 12.dp, spot = 0.35f)
                        .clip(CircleShape)
                        .background(Color(0xFF1ED760))
                        .pressable(onPlay, shape = CircleShape, pressedScale = 0.94f),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        PhosphorIcons.Fill.Play,
                        contentDescription = stringResource(R.string.play),
                        tint = Color.Black,
                        modifier = Modifier
                            .size(28.dp)
                            .padding(start = 2.dp),
                    )
                }
            }
        }

        CollapsedBar(
            ink = ink,
            name = name,
            collapse = collapse,
            collapsedHeight = collapsedHeight,
            topPadding = topPadding,
            searching = searching,
            onBack = onBack,
            onToggleSearch = onToggleSearch,
            onShuffle = onShuffle,
            onPlay = onPlay,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

/**
 * How big a name can be drawn without breaking the frame.
 *
 * Stepped rather than measured: text that is auto-fitted to the pixel changes
 * size as the page loads and as the header collapses, and a title that resizes
 * while being read is worse than one that is a point too small.
 */
private fun nameSize(name: String): TextUnit = when (name.length) {
    in 0..9 -> 46.sp
    in 10..14 -> 38.sp
    in 15..20 -> 31.sp
    in 21..28 -> 26.sp
    else -> 22.sp
}

/**
 * The record they put out last, above everything else on the page.
 *
 * One card rather than a shelf, and it is the first thing under the photo,
 * because "what is new from them" is the question an artist page is most often
 * opened with — the discography further down answers the other one.
 */
@Composable
private fun LatestReleaseCard(
    release: MainViewModel.ArtistRelease,
    /** Whether it is already in the library; null while nobody knows yet. */
    saved: Boolean?,
    onOpen: () -> Unit,
    onToggleSaved: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.08f))
            .pressable(onOpen, shape = shape, pressedScale = 0.99f)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            url = release.artworkUrl,
            title = release.title,
            modifier = Modifier.size(58.dp),
            corner = 8.dp,
            decodeSize = 58.dp,
        )

        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 14.dp),
        ) {
            Text(
                releaseDateLabel(release.releaseDate),
                style = MaterialTheme.typography.bodySmall,
                color = InkDim,
                maxLines = 1,
            )
            Text(
                release.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                pluralStringResource(
                    R.plurals.song_count,
                    release.trackCount,
                    release.trackCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = InkDim,
                maxLines = 1,
            )
        }

        // Absent until Spotify has said whether the record is already kept —
        // the same rule as the star above.
        if (saved != null) {
            IconButton(onClick = onToggleSaved, modifier = Modifier.size(38.dp)) {
                Icon(
                    if (saved) PhosphorIcons.Fill.Check else PhosphorIcons.Bold.Plus,
                    contentDescription = stringResource(
                        if (saved) R.string.remove_from_library else R.string.add_to_library,
                    ),
                    tint = if (saved) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * A release date as a person would write it, from however much Spotify knows.
 *
 * The field is a full date for most records, a month for some and a year for
 * the oldest, and printing "2019-01-01" for a record that only says 2019 is
 * inventing a day it did not come out on.
 */
@Composable
private fun releaseDateLabel(date: String): String {
    if (date.isBlank()) return ""
    return runCatching {
        when (date.length) {
            4 -> date
            7 -> java.time.YearMonth.parse(date).format(
                java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()),
            )
            else -> java.time.LocalDate.parse(date).format(
                java.time.format.DateTimeFormatter.ofLocalizedDate(
                    java.time.format.FormatStyle.MEDIUM,
                ).withLocale(Locale.getDefault()),
            )
        }
    }.getOrDefault(date)
}

/**
 * The header genuinely shrinks: the content keeps its full size and is clipped
 * by the box around it, which is what makes the cover look like it is being
 * rolled up rather than moved off.
 */
private fun Modifier.heroCollapse(
    heroPx: Int,
    collapsedPx: Int,
    collapse: () -> Float,
): Modifier = layout { measurable, constraints ->
    val height = androidx.compose.ui.util.lerp(heroPx, collapsedPx, collapse())
    val placeable = measurable.measure(
        constraints.copy(minHeight = heroPx, maxHeight = heroPx),
    )
    layout(constraints.maxWidth, height) {
        // Anchored to the bottom of the picture, so what stays visible as it
        // closes is the part the title sits on.
        placeable.place(0, height - heroPx)
    }
}.clipToBounds()

/**
 * A pane of glass shaped like a capsule, for actions that belong together.
 *
 * Refraction alone is invisible here. The page under these controls is a flat
 * colour taken from the cover, and bending a flat colour gives the same flat
 * colour back: the buttons came out as plain discs, which is the note in the
 * layer above about glass having nothing to bend. What makes glass read against
 * a flat field is its edge, so this adds the two things an edge has — a film so
 * the pane is a shade lighter than what it lies on, and a bright rim where the
 * light would catch it.
 */
/** The hairline between two actions in the capsule above. */
@Composable
private fun CapsuleDivider() {
    Box(
        Modifier
            .height(20.dp)
            .width(1.dp)
            .background(Color.White.copy(alpha = 0.22f)),
    )
}

@Composable
private fun GlassCapsule(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier
            .height(42.dp)
            .clip(shape)
            .liquidGlass(
                config = dev.lelonio.square.ui.glass.LocalGlassEffectConfig.current,
                shape = shape,
                blurRadiusDp = dev.lelonio.square.ui.glass.LocalGlassEffectConfig.current.blurRadius,
                ownBackdrop = backdrop,
            )
            // The rim comes with the material now; this is the drawn edge that
            // separates the two halves' pane from the artwork behind it.
            .border(0.6.dp, Color.White.copy(alpha = 0.30f), shape),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** One half of [GlassCapsule], sized so the two halves are the same target. */
@Composable
private fun CapsuleAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color = Color.White,
) {
    Box(
        Modifier
            .size(width = 46.dp, height = 42.dp)
            .pressable(onClick, pressedScale = 0.92f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Keeping the page on the phone, in one control.
 *
 * Three answers rather than a toggle, because a download has a middle: not
 * kept, being kept, kept. The middle one draws its own progress around the
 * icon — the ring is the only thing on the page that says the fetching is
 * still happening, since it runs in a service with the screen off.
 *
 * Partial is drawn as done deliberately. It means every track that could be
 * fetched was, and the rest are not available to this account at all; a page
 * that sat forever at nine tenths with no way to finish would be a fault the
 * listener cannot act on.
 */
@Composable
private fun DownloadAction(
    state: dev.lelonio.square.data.OwnerState,
    backdrop: Backdrop,
    onClick: () -> Unit,
    tint: Color = Color.White,
) {
    val kept = state is dev.lelonio.square.data.OwnerState.Complete ||
        state is dev.lelonio.square.data.OwnerState.Partial
    val running = state as? dev.lelonio.square.data.OwnerState.Running

    Box(contentAlignment = Alignment.Center) {
        CircleAction(
            // A heavy arrow pointing down, which is what the reference puts
            // here — and once it is on the phone, the same arrow inside a ring
            // to say the journey is over. A tick would be about the act rather
            // than about the music.
            icon = if (kept) {
                PhosphorIcons.Bold.ArrowCircleDown
            } else {
                PhosphorIcons.Bold.ArrowDown
            },
            description = stringResource(
                if (kept) R.string.remove_download else R.string.download,
            ),
            size = 46.dp,
            backdrop = backdrop,
            onClick = onClick,
            tint = tint,
        )
        // Around the button rather than inside it: the icon still has to be
        // legible while this turns.
        running?.let {
            CircularProgressIndicator(
                progress = { it.progress },
                color = tint,
                trackColor = Color.White.copy(alpha = 0.22f),
                strokeWidth = 2.dp,
                gapSize = 0.dp,
                modifier = Modifier.size(46.dp),
            )
        }
    }
}

@Composable
private fun CircleAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    size: androidx.compose.ui.unit.Dp,
    backdrop: Backdrop,
    onClick: () -> Unit,
    tint: Color = Color.White,
) {
    LiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        // See GlassCapsule: over the page's flat colour the refraction has
        // nothing to bend, and only the film and the rim say this is glass.
        modifier = Modifier
            .size(size)
            // A film of its own over the glass. The reference's round controls
            // read as pale discs against the photograph rather than as holes
            // cut in it, and refraction alone cannot do that over a picture as
            // dark as most artist photographs are.
            .background(Color.White.copy(alpha = 0.17f), CircleShape)
            .border(0.6.dp, Color.White.copy(alpha = 0.26f), CircleShape),
        contentHeight = size,
        contentPadding = 0.dp,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(size * 0.44f),
        )
    }
}

@Composable
private fun AlbumStrip(
    albums: List<dev.lelonio.square.data.SearchItem>,
    title: String,
    onOpen: (dev.lelonio.square.data.SearchItem) -> Unit,
) {
    Column(Modifier.padding(top = 18.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 24.dp, bottom = 10.dp),
        )
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(items = albums, key = { it.uri }) { album ->
                Column(
                    Modifier
                        .width(132.dp)
                        .pressable({ onOpen(album) }),
                ) {
                    Artwork(
                        url = album.artworkUrl,
                        title = album.title,
                        modifier = Modifier
                            .size(132.dp)
                            .softShadow(RoundedCornerShape(14.dp), elevation = 14.dp),
                        corner = 14.dp,
                        decodeSize = 132.dp,
                    )
                    Text(
                        album.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (album.subtitle.isNotBlank()) {
                        Text(
                            album.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    sort: TrackSort,
    /** Drawn as a page heading rather than as a label over a list. */
    large: Boolean = false,
    onSortOpen: (Boolean) -> Unit,
    /** Where the sort button is, for the menu drawn above the list. */
    onAnchor: (IntOffset) -> Unit,
    /** Whether there is more of this section than the page is showing. */
    expandable: Boolean = false,
    expanded: Boolean = false,
    onToggle: () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 14.dp, top = 22.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The heading itself is the way in, which is how the reference does it:
        // the title and the mark after it are one target, and there is no
        // separate "see all" anywhere on the page.
        Row(
            Modifier
                .weight(1f)
                .then(
                    if (expandable) {
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .pressable(onToggle, pressedScale = 0.97f)
                    } else {
                        Modifier
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        Text(
            title,
            style = if (large) {
                MaterialTheme.typography.titleLarge
            } else {
                MaterialTheme.typography.titleMedium
            },
            fontWeight = FontWeight.Bold,
        )

        if (expandable) {
            // Pointing on, and turning down once the rest is out: the same mark
            // saying both "there is more" and "this is all of it".
            val turn by animateFloatAsState(
                targetValue = if (expanded) 90f else 0f,
                animationSpec = tween(220),
                label = "sectionCaret",
            )
            Icon(
                PhosphorIcons.Regular.CaretRight,
                contentDescription = stringResource(
                    if (expanded) R.string.show_less else R.string.show_all,
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(16.dp)
                    .graphicsLayer { rotationZ = turn },
            )
        }
        }

        IconButton(
            onClick = { onSortOpen(true) },
            modifier = Modifier.onGloballyPositioned {
                val root = it.boundsInRoot()
                onAnchor(IntOffset(root.left.toInt(), root.bottom.toInt()))
            },
        ) {
            run {
                Icon(
                    PhosphorIcons.Bold.ArrowsDownUp,
                    contentDescription = stringResource(R.string.sort),
                    tint = if (sort == TrackSort.ORIGINAL) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** How many songs an artist page opens on; the heading holds the rest. */
private const val TOP_SONGS = 5

/** What the hero shrinks to: a bar, under the status bar's own inset. */
private val COLLAPSED_BAR_HEIGHT = 56.dp

/**
 * Tall enough for a record and everything written under it.
 *
 * Measured rather than chosen: the cover, the title, the artist, the two
 * buttons and the row of small ones stack to just under this, and a header
 * shorter than its contents squeezes them — which is what a fixed-height header
 * does when it is too small, since the column inside it keeps its own size.
 */
private val HERO_HEIGHT = 512.dp

/** As tall as a drawn name gets before it starts crowding the controls. */
private val LOGO_HEIGHT = 96.dp

/** Decode size for the soft copy of the artist's photo, in pixels. */
private const val HERO_BLUR_PX = 240

/** Soft enough to be a wash, sharp enough to keep the picture's shapes. */
private val HeroBlur = BlurTransformation(radius = 10, passes = 2)

/**
 * White, carrying a colour's hue and none of its darkness.
 *
 * Mixing white with the page colour itself is what this did first, and on a page
 * whose colour is a deep purple that gives grey — the darkness comes along with
 * the hue. Taking the hue and the saturation, setting the brightness where a
 * tint belongs, and only then mixing gives a purple-white on a purple page and a
 * green-white on a green one.
 */
private fun inkTintedBy(color: Color, ink: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    if (hsv[1] < 0.06f) return ink
    hsv[1] = hsv[1].coerceIn(0.5f, 1f)
    // Bright where the ink is light and deep where it is dark: the tint is the
    // page's either way, and only the direction changes.
    hsv[2] = if (ink.luminance() > 0.5f) 0.92f else 0.36f
    return lerp(ink, Color(android.graphics.Color.HSVToColor(hsv)), 0.26f)
}


/**
 * The little mark in a row that says this song is here.
 *
 * Nothing at all until a download exists for the track. That is the whole rule:
 * an ordinary playlist reads exactly as it did before this feature, and the
 * mark appearing is itself the news. There is no "not downloaded" state to draw
 * because a row full of empty placeholders would be worse than no marks.
 */
@Composable
private fun DownloadMark(state: dev.lelonio.square.data.DownloadState) {
    val running = state as? dev.lelonio.square.data.DownloadState.Running
    // Queued deliberately draws nothing. A playlist of four hundred songs would
    // otherwise sprout four hundred marks the instant the button is pressed,
    // which says "all downloaded" a good ten minutes before it is true.
    if (state !is dev.lelonio.square.data.DownloadState.Done && running == null) return

    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .padding(end = 6.dp)
            .size(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (state is dev.lelonio.square.data.DownloadState.Done) {
            Icon(
                PhosphorIcons.Fill.ArrowCircleDown,
                contentDescription = stringResource(R.string.downloaded),
                tint = accent,
                modifier = Modifier.size(14.dp),
            )
        } else {
            // A thin ring rather than the app's spinner: at fourteen density
            // pixels inside a scrolling list, anything with a label or a fill
            // is a smudge.
            val progress by animateFloatAsState(
                targetValue = running?.progress ?: 0f,
                animationSpec = tween(320),
                label = "row download",
            )
            androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
                val stroke = 1.6.dp.toPx()
                val inset = androidx.compose.ui.geometry.Offset(stroke / 2f, stroke / 2f)
                val ring = androidx.compose.ui.geometry.Size(
                    this.size.width - stroke,
                    this.size.height - stroke,
                )
                drawArc(
                    color = accent.copy(alpha = 0.28f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = inset,
                    size = ring,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                )
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    // A stub of ring even at zero, so a track that has only
                    // just started still reads as busy.
                    sweepAngle = (360f * progress).coerceAtLeast(24f),
                    useCenter = false,
                    topLeft = inset,
                    size = ring,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = stroke,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    ),
                )
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: CatalogTrack,
    position: Int,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    /** What the second line says; null for the artist and the length. */
    subtitle: String? = null,
    /** Its place on the record, drawn where the cover would be. */
    numbered: Boolean = false,
    /** Rank number shown to the left of artwork (e.g. for popular artist tracks). */
    rank: Int? = null,
    /** Nothing is drawn until a download for this track exists; see below. */
    download: dev.lelonio.square.data.DownloadState =
        dev.lelonio.square.data.DownloadState.None,
    /**
     * Offline, and this one is not on the phone.
     *
     * Dimmed and inert rather than hidden: a playlist that quietly loses two
     * songs in three is a playlist the listener no longer recognises, and the
     * missing ones are exactly what they might want to download later.
     */
    unavailable: Boolean = false,
) {
    val shape = RoundedCornerShape(14.dp)
    // Everything in the row fades together, artwork included, so it reads as
    // one thing being unavailable rather than a list of greyed-out words.
    val dim = if (unavailable) 0.38f else 1f
    // Eased rather than snapped: rows change state on every track advance, and
    // a hard cut in the middle of a list draws the eye more than the change
    // deserves.
    val highlight by animateFloatAsState(
        targetValue = if (isCurrent) 1f else 0f,
        animationSpec = tween(260),
        label = "row",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 2.dp)
            // The playing row lifts onto its own card. With covers gone from the
            // list, a tint alone was too quiet to find while scrolling.
            .clip(shape)
            // `surface` is already a 10%-alpha white film. Passing the animation
            // value to `copy(alpha = …)` *replaced* that alpha instead of
            // scaling it, so the playing row finished the animation as solid
            // white — a paper card in the middle of a glass UI.
            .background(
                MaterialTheme.colorScheme.surface.copy(
                    alpha = MaterialTheme.colorScheme.surface.alpha * highlight,
                ),
            )
            .then(
                if (unavailable) {
                    Modifier
                } else {
                    Modifier.pressable(onClick, shape = shape, pressedScale = 0.985f)
                },
            )
            .graphicsLayer { alpha = dim }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rank != null) {
            Text(
                text = rank.toString(),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = if (isCurrent) Color(0xFF1ED760) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .width(26.dp)
                    .padding(end = 4.dp),
                textAlign = TextAlign.Start,
            )
        }

        // The record, not the row number. A list of covers is scanned by
        // recognition rather than by reading, and the position of a song in a
        // playlist is the least interesting thing about it. The playing row
        // still says so, with the meter drawn over its own cover.
        Box(contentAlignment = Alignment.Center) {
            if (numbered) {
                Box(
                    Modifier.size(width = 30.dp, height = 46.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isCurrent) {
                        Icon(
                            PhosphorIcons.Fill.Waveform,
                            contentDescription = stringResource(R.string.now_playing),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Text(
                            position.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
            Artwork(
                url = track.artworkUrl,
                title = track.name,
                modifier = Modifier.size(46.dp),
                corner = 8.dp,
                decodeSize = 46.dp,
            )
            if (isCurrent) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        PhosphorIcons.Fill.Waveform,
                        contentDescription = stringResource(R.string.now_playing),
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            }
        }

        Column(
            Modifier
                .weight(1f)
                .padding(start = 14.dp, end = 8.dp),
        ) {
            Text(
                text = track.name,
                style = MaterialTheme.typography.titleMedium,
                // A notch heavier than the app's own default: on the reference
                // the title of a row is the one thing set in a weight you can
                // pick out while scrolling.
                color = if (isCurrent) {
                    Color(0xFF1ED760)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle
                    ?: "${track.artist} · ${formatDuration(track.durationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        DownloadMark(download)

        IconButton(onClick = onMenu, modifier = Modifier.size(32.dp)) {
            Icon(
                PhosphorIcons.Bold.DotsThree,
                contentDescription = stringResource(R.string.more),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * What can be done with one track, without leaving the list.
 *
 * Only actions this app can actually carry out: the queue and the playlists,
 * plus the link, since sharing a track is the one thing people leave a music
 * app to do. No "go to album" — the track model carries the album's name for
 * display and not its URI, so the entry would be there and not work.
 */
/**
 * The web address for a track URI, which is what a share expects.
 *
 * Whichever service the track came from: sharing a YouTube track as a Spotify
 * link would point at something that is not the same recording, or at nothing.
 */
fun CatalogTrack.openLink(): String {
    val id = uri.substringAfterLast(':')
    return if (uri.startsWith("ytmusic:")) {
        "https://music.youtube.com/watch?v=$id"
    } else {
        "https://open.spotify.com/track/$id"
    }
}

/**
 * The web address for a page: a playlist, an album, an artist.
 *
 * The same shape as a track's, and told apart the same way — a `ytmusic:` uri
 * belongs to one service and a `spotify:` one to the other, and a link handed to
 * the wrong service points at nothing.
 */
fun openLinkOf(uri: String): String {
    val id = uri.substringAfterLast(':')
    if (uri.startsWith("ytmusic:")) {
        return "https://music.youtube.com/playlist?list=$id"
    }
    val kind = uri.split(':').getOrNull(1) ?: "playlist"
    return "https://open.spotify.com/$kind/$id"
}

/**
 * The record a song is on and the year it came out, in one line.
 *
 * Whichever half is known: an album with no year is still worth naming, and a
 * year with no album name is never the case. Empty when there is neither, and
 * the row then falls back to the artist and the length.
 */
private fun CatalogTrack.releaseLine(): String = listOf(album, year)
    .filter { it.isNotBlank() }
    .joinToString(" · ")
    .ifBlank { artist }

/**
 * The paragraph a record's page carries about itself.
 *
 * Folded to three lines with a word to open it, the way the reference folds it:
 * some of these run to six paragraphs, and a page that begins with an essay
 * buries the thing it is a page for.
 */
@Composable
private fun EditorialNotes(notes: String) {
    var open by remember(notes) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .pressable({ open = !open }, pressedScale = 0.995f),
    ) {
        Text(
            // The notes arrive as HTML often enough to matter, and a stray
            // <p> in the middle of a paragraph reads as a bug in the app.
            notes.replace(Regex("<[^>]+>"), "").trim(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (open) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (!open) {
            Text(
                stringResource(R.string.more_notes),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * How long ago something happened, in the coarsest unit that still says it.
 *
 * "2 g" rather than "2 giorni, 4 ore": this is the line under a playlist's
 * title, and what it is for is telling a live list from one nobody has touched
 * in a year.
 */
@Composable
private fun agoOf(iso: String): String {
    val then = runCatching { java.time.Instant.parse(iso) }.getOrNull()
        ?: return ""
    val days = java.time.Duration.between(then, java.time.Instant.now()).toDays()
    return when {
        days <= 0 -> stringResource(R.string.ago_today)
        days < 7 -> stringResource(R.string.ago_days, days)
        days < 31 -> stringResource(R.string.ago_weeks, days / 7)
        days < 365 -> stringResource(R.string.ago_months, days / 30)
        else -> stringResource(R.string.ago_years, days / 365)
    }
}

@Composable
private fun StatusBox(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Case- and accent-insensitive enough for a phone search box. */
private fun CatalogTrack.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim().lowercase(Locale.getDefault())
    return name.lowercase(Locale.getDefault()).contains(needle) ||
        artist.lowercase(Locale.getDefault()).contains(needle) ||
        album.lowercase(Locale.getDefault()).contains(needle)
}

private fun TrackSort.comparator(): Comparator<CatalogTrack> = when (this) {
    // Stable no-op: the list is already in playlist order.
    TrackSort.ORIGINAL -> Comparator { _, _ -> 0 }
    TrackSort.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    TrackSort.ARTIST -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist }
    // Most recent first, and anything without a date last rather than first:
    // a null here means the list came from the access point, which does not
    // carry the field, and those belong at the end instead of on top.
    TrackSort.ADDED -> compareByDescending(nullsFirst()) { it.addedAt }
    TrackSort.DURATION -> compareBy { it.durationMs }
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return "%d:%02d".format(minutes, seconds)
}

/** Total playlist length, in hours and minutes rather than a running clock. */
@Composable
private fun formatTotal(ms: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    return if (hours > 0) stringResource(R.string.hours_minutes, hours, minutes)
    else stringResource(R.string.minutes_only, minutes)
}

/**
 * Who the artist is, in the two facts Spotify itself leads with, and the one
 * thing there is to do about them.
 *
 * The follower count is the only number on the page that says how big somebody
 * is, and the genres are the only words: without them an artist page is a list
 * of records that could belong to anyone. Both are quiet — this is a caption
 * under a name, not a dashboard.
 */
@Composable
private fun ArtistAbout(
    followers: Int,
    genres: List<String>,
    /** Where they are from, from Apple's catalogue; absent for most acts. */
    origin: String?,
    /** And what its editors wrote about them. */
    bio: String?,
) {
    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 6.dp)) {
        // The facts first and the prose under them, because the facts are what
        // somebody who pressed "i" on an artist they do not know wants first.
        val line = listOfNotNull(
            origin?.takeIf { it.isNotBlank() },
            followers.takeIf { it > 0 }?.let { stringResource(R.string.followers_count, compact(it)) },
            genres.take(2).joinToString(" · ") { it.replaceFirstChar(Char::uppercase) }
                .takeIf { it.isNotEmpty() },
        ).joinToString(" · ")

        if (line.isNotEmpty()) {
            Text(
                line,
                style = MaterialTheme.typography.labelMedium,
                color = InkDim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (!bio.isNullOrBlank()) {
            var open by remember(bio) { mutableStateOf(false) }
            Text(
                bio.replace(Regex("<[^>]+>"), "").trim(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (open) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .pressable({ open = !open }, pressedScale = 0.995f),
            )
            if (!open) {
                Text(
                    stringResource(R.string.more_notes),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Follow, and the same button saying it is already done.
 *
 * Outlined once followed rather than filled: it is then a state to read and
 * occasionally undo, not something to press, and two solid buttons side by side
 * both ask to be the one you tap.
 */
@Composable
private fun FollowPill(following: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        Modifier
            .clip(shape)
            .border(1.dp, Color.White.copy(alpha = if (following) 0.35f else 0.8f), shape)
            .background(if (following) Color.White.copy(alpha = 0.12f) else Color.Transparent, shape)
            .pressable(onClick, pressedScale = 0.94f)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(if (following) R.string.following else R.string.follow),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
        )
    }
}

@Composable
private fun ArtistTabs(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    ink: Color,
    modifier: Modifier = Modifier,
) {
    val tabs = listOf("Música", "Clips")
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, title ->
            val selected = selectedTab == index
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .pressable({ onSelectTab(index) }, pressedScale = 0.96f)
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 17.sp,
                    ),
                    color = if (selected) ink else ink.copy(alpha = 0.5f),
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .width(if (selected) 40.dp else 0.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(if (selected) Color(0xFF1ED760) else Color.Transparent),
                )
            }
        }
    }
}

@Composable
private fun PopularReleaseRow(
    release: dev.lelonio.square.data.SearchItem,
    isLatest: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(shape)
            .pressable(onClick, shape = shape, pressedScale = 0.98f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            url = release.artworkUrl,
            title = release.title,
            modifier = Modifier.size(68.dp),
            corner = 6.dp,
            decodeSize = 68.dp,
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 14.dp),
        ) {
            if (isLatest) {
                Text(
                    text = "Último lanzamiento",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Text(
                text = release.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (release.subtitle.isNotBlank()) {
                Text(
                    text = release.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ConArtistStrip(
    artistName: String,
    items: List<dev.lelonio.square.data.SearchItem>,
    onOpen: (dev.lelonio.square.data.SearchItem) -> Unit,
) {
    Column(Modifier.padding(top = 24.dp)) {
        Text(
            text = "Con $artistName",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(start = 20.dp, bottom = 12.dp),
        )
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(items = items, key = { it.uri }) { item ->
                Column(
                    Modifier
                        .width(140.dp)
                        .pressable({ onOpen(item) }),
                ) {
                    Artwork(
                        url = item.artworkUrl,
                        title = item.title,
                        modifier = Modifier
                            .size(140.dp)
                            .softShadow(RoundedCornerShape(8.dp), elevation = 8.dp),
                        corner = 8.dp,
                        decodeSize = 140.dp,
                    )
                    Text(
                        item.title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (item.subtitle.isNotBlank()) {
                        Text(
                            item.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RelatedArtistsStrip(
    artists: List<dev.lelonio.square.data.SearchItem>,
    onOpen: (dev.lelonio.square.data.SearchItem) -> Unit,
) {
    Column(Modifier.padding(top = 24.dp, bottom = 20.dp)) {
        Text(
            text = "A los fans también les gusta",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(start = 20.dp, bottom = 14.dp),
        )
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items = artists, key = { it.uri }) { artist ->
                Column(
                    Modifier
                        .width(110.dp)
                        .pressable({ onOpen(artist) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Artwork(
                        url = artist.artworkUrl,
                        title = artist.title,
                        modifier = Modifier
                            .size(110.dp)
                            .clip(CircleShape),
                        corner = 55.dp,
                        decodeSize = 110.dp,
                    )
                    Text(
                        artist.title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * A follower count as somebody would say it out loud: 1,2 Mln, 340 mila.
 *
 * Spotify shows the exact number and nobody reads it; what the digits are for
 * is the order of magnitude, and seven of them take a line to say what two do.
 */
private fun compact(count: Int): String = when {
    count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000f)
    count >= 1_000 -> "${count / 1_000}K"
    else -> count.toString()
}
