package dev.lelonio.square.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.kyant.backdrop.Backdrop
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.SpotifyLogo
import com.adamglin.phosphoricons.regular.YoutubeLogo
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material3.Icon
import dev.lelonio.square.R
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.SearchItem
import dev.lelonio.square.data.sortedByRecentlyOpened
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.components.AppIcon
import dev.lelonio.square.ui.components.AppLockup
import dev.lelonio.square.ui.components.Artwork
import dev.lelonio.square.ui.components.SquareWordmark
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.glass.pressable
import dev.lelonio.square.ui.player.GlassFilm
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import dev.lelonio.square.ui.theme.softShadow
import java.util.Calendar

/**
 * What the feed is showing.
 *
 * Chips rather than a second tab bar: they sit inside the page, under the name,
 * and "Tutto" is genuinely the whole thing rather than a fourth view. The
 * sections keep their order in every one of them, so switching never rearranges
 * what stays on screen.
 */
private enum class Feed(@StringRes val label: Int) {
    ALL(R.string.feed_all),
    FOR_YOU(R.string.feed_for_you),
    LIBRARY(R.string.library),
    RELEASES(R.string.feed_releases),
    ARTISTS(R.string.artists),
}

/**
 * The home page.
 *
 * Rewritten from scratch rather than adjusted. The previous version had grown
 * from a plain Material list — filled buttons, section headings, a progress
 * spinner in the middle of the page — and adding glass cards on top of it left
 * two design languages sharing a screen. Everything here is drawn on the same
 * material as the bars and the player: no Material containers, no elevation, no
 * accent-filled buttons.
 */
@Composable
fun HomeScreen(
    state: MainViewModel.UiState,
    contentPadding: PaddingValues,
    onLogIn: () -> Unit,
    onRetry: () -> Unit,
    onLogOut: () -> Unit,
    onOpenPlaylist: (CatalogPlaylist) -> Unit,
    /** URIs most recently opened first; see PlaylistOrderStore. */
    playlistOrder: List<String>,
    recent: List<CatalogTrack>,
    onPlayRecent: (List<CatalogTrack>, Int) -> Unit,
    /** Plays a row of the feed, told which row it was so the player can say so. */
    onPlayFeed: (List<CatalogTrack>, Int, String) -> Unit,
    feed: MainViewModel.FeedState,
    onOpenItem: (SearchItem) -> Unit,
    onOpenSettings: () -> Unit,
    /** The layer the glass on this page refracts; see the note in SquareApp. */
    backdrop: Backdrop,
    /**
     * True when the active source is YouTube Music.
     *
     * [state] describes the *Spotify* session, so on YouTube it is permanently
     * `LoggedOut` and this page would offer a login for an account the user
     * chose not to use. There is no account to log into here, so the page shows
     * what it genuinely has instead.
     */
    youtubeMode: Boolean = false,
    youtubeHome: MainViewModel.YouTubeHomeState = MainViewModel.YouTubeHomeState(),
    onPlayTrending: (List<CatalogTrack>, Int) -> Unit = { _, _ -> },
    /**
     * Spotify's own personalised shelves, empty when the gateway said nothing.
     *
     * Shown above the rows this app builds itself. They are what the listener
     * recognises as their home page, and unlike everything below them they
     * cannot be reconstructed from the account's playlists.
     */
    shelves: List<dev.lelonio.square.data.HomeShelf> = emptyList(),
) {
    if (youtubeMode) {
        YouTubeHome(
            contentPadding = contentPadding,
            home = youtubeHome,
            recent = recent,
            accountName = (state as? MainViewModel.UiState.Ready)?.displayName.orEmpty(),
            backdrop = backdrop,
            onPlayRecent = onPlayRecent,
            onPlayTrending = onPlayTrending,
            onOpenPlaylist = onOpenPlaylist,
            onOpenSettings = onOpenSettings,
        )
        return
    }

    when (state) {
        MainViewModel.UiState.LoggedOut -> Centered {
            AppIcon(84.dp)
            SquareWordmark(height = 28.dp)
            Text(
                stringResource(R.string.unofficial_client),
                style = MaterialTheme.typography.bodyMedium,
                color = InkDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
            GlassAction(stringResource(R.string.log_in_with_spotify), backdrop, onLogIn)
        }

        MainViewModel.UiState.Connecting,
        MainViewModel.UiState.Loading,
        -> Centered {
            CircularProgressIndicator(color = Ink, strokeWidth = 2.dp)
        }

        is MainViewModel.UiState.Failed -> Centered {
            Text(
                state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = InkDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
            GlassAction(stringResource(R.string.retry), backdrop, onRetry)
            GlassAction(stringResource(R.string.log_out), backdrop, onLogOut)
        }

        is MainViewModel.UiState.Ready -> {
            var filter by remember { mutableStateOf(Feed.ALL) }
            // Read once here rather than inside the rows: the label travels
            // with the play so the player can say where the song came from,
            // and that is a plain string by the time it leaves this screen.
            val onRepeatLabel = stringResource(R.string.on_repeat)
            val classicsLabel = stringResource(R.string.all_time_favourites)
            // Spotify's rootlist arrives in the order the account added them,
            // which for an old account is close to arbitrary — the playlist
            // opened every day can sit thirtieth.
            val playlists = remember(state.playlists, playlistOrder) {
                state.playlists.sortedByRecentlyOpened(playlistOrder)
            }
            val listState = rememberLazyListState()

            // How far the header has collapsed, 0 to 1.
            //
            // Read from the list rather than driven by a nested-scroll
            // connection: the header is a sibling of the list, not part of it,
            // so it never consumes scroll and the list keeps its own fling
            // untouched. Past the first item the header is simply fully
            // collapsed — asking for the exact offset of something scrolled far
            // off screen means measuring items that no longer exist.
            val collapse by remember {
                derivedStateOf {
                    if (listState.firstVisibleItemIndex > 0) 1f
                    else (listState.firstVisibleItemScrollOffset / COLLAPSE_DISTANCE_PX)
                        .coerceIn(0f, 1f)
                }
            }

            // Back to the top when the view changes. The lists have nothing in
            // common, so keeping the old offset drops the new one in the middle
            // of itself.
            LaunchedEffect(filter) { listState.scrollToItem(0) }

            Column(Modifier.fillMaxSize()) {
                Header(
                    name = state.displayName,
                    avatarUrl = state.avatarUrl,
                    service = R.string.backend_spotify,
                    serviceIcon = PhosphorIcons.Regular.SpotifyLogo,
                    collapse = { collapse },
                    // The chip that is lit is the one that was tapped. It used
                    // to follow whichever section the scroll had reached, which
                    // read as a control changing itself: the chips look like a
                    // filter, so a lit one has to mean "this is what you are
                    // looking at because you asked for it".
                    highlighted = filter,
                    backdrop = backdrop,
                    topPadding = contentPadding.calculateTopPadding(),
                    onFilter = { filter = it },
                    onOpenSettings = onOpenSettings,
                )

                AnimatedContent(
                    targetState = filter,
                    transitionSpec = {
                        // Short and vertical: the sections do not move sideways
                        // when they are filtered, so neither should the change.
                        (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 14 })
                            .togetherWith(fadeOut(tween(120)))
                    },
                    label = "feed",
                ) { current ->
                val showReleases = current == Feed.ALL || current == Feed.RELEASES
                val showArtists = current == Feed.ALL || current == Feed.ARTISTS
                val showLibrary = current == Feed.ALL || current == Feed.LIBRARY
                val showForYou = current == Feed.ALL || current == Feed.FOR_YOU
                val filter = current

                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        // Fades the first rows out under the header instead of
                        // cutting them off square. DstIn needs a layer of its
                        // own, or the mask would erase the page behind the list
                        // as well.
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    FADE_FRACTION to Color.Black,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                    state = listState,
                    // The header already covers the status bar, so only the
                    // bottom inset is left for the list.
                    contentPadding = PaddingValues(
                        bottom = contentPadding.calculateBottomPadding(),
                    ),
                ) {
                // Spotify's own shelves come first, because they are what the
                // listener recognises as their home and the only rows here that
                // this app could not have built itself. What the account has
                // been playing follows: it is worth having, and it is also the
                // same answer for weeks at a time.
                //
                // Every feed section keeps its place while it is still being
                // fetched. The account's playlists and its recent tracks are
                // held on the device and draw at once; everything else is a Web
                // API round trip a second or two behind, and without a
                // placeholder the page arrived in two halves and shifted under
                // whatever was being read.
                if (filter == Feed.ALL) {
                    shelves.forEach { shelf ->
                        item(contentType = "shelf") { Heading(shelf.title) }
                        item(contentType = "shelf") {
                            Carousel(shelf.items, key = { it.uri }) { entry ->
                                PlaylistTile(entry) { onOpenPlaylist(entry) }
                            }
                        }
                    }
                }

                if (showForYou && feed.topTracks.isEmpty() && feed.loading) {
                    item(contentType = "skeleton") {
                        SkeletonSection(tileWidth = 152.dp, tileHeight = 152.dp)
                    }
                }

                if (showForYou && feed.topTracks.isNotEmpty()) {
                    item(contentType = Feed.FOR_YOU.name) { Heading(stringResource(R.string.on_repeat)) }
                    item(contentType = Feed.FOR_YOU.name) {
                        TrackRow(feed.topTracks) { index ->
                            onPlayFeed(feed.topTracks, index, onRepeatLabel)
                        }
                    }
                }

                if (showForYou && feed.jumpBackIn.isEmpty() && feed.loading) {
                    item(contentType = "skeleton") {
                        SkeletonSection(tileWidth = 152.dp, tileHeight = 152.dp)
                    }
                }

                if (showForYou && feed.jumpBackIn.isNotEmpty()) {
                    item(contentType = Feed.FOR_YOU.name) { Heading(stringResource(R.string.jump_back_in)) }
                    item(contentType = Feed.FOR_YOU.name) {
                        Carousel(feed.jumpBackIn, key = { it.uri }) { item ->
                            FeedTile(item) { onOpenItem(item) }
                        }
                    }
                }

                if (showLibrary || filter == Feed.ALL) {
                    item(contentType = Feed.LIBRARY.name) { Heading(stringResource(R.string.your_playlists)) }
                    item(contentType = Feed.LIBRARY.name) {
                        Carousel(
                            playlists.take(if (showLibrary) LIBRARY_SIZE else CAROUSEL_SIZE),
                            key = { it.uri },
                        ) { playlist ->
                            PlaylistTile(playlist) { onOpenPlaylist(playlist) }
                        }
                    }
                }

                if (showReleases && feed.newReleases.isEmpty() && feed.loading) {
                    item(contentType = "skeleton") {
                        SkeletonSection(tileWidth = 0.dp, tileHeight = 200.dp, card = true)
                    }
                }

                if (showReleases && feed.newReleases.isNotEmpty()) {
                    item(contentType = Feed.RELEASES.name) { Heading(stringResource(R.string.feed_releases)) }
                    // Cards rather than another row of thumbnails. A carousel
                    // says "here is a list, pick one"; this is meant to be
                    // looked at, so each release gets the width of the page and
                    // the cover carries it.
                    items(
                        feed.newReleases.take(FEED_SIZE),
                        key = { it.uri },
                        contentType = { Feed.RELEASES.name },
                    ) { item ->
                        FeedCard(item) { onOpenItem(item) }
                    }
                }

                // Novità, but only from artists the account listens to. Sits
                // under the catalogue-wide releases on purpose: it is the
                // narrower of the two and the one worth reaching first.
                if ((showReleases || showForYou) && feed.fromYourArtists.isNotEmpty()) {
                    item(contentType = Feed.FOR_YOU.name) { Heading(stringResource(R.string.new_from_your_artists)) }
                    item(contentType = Feed.FOR_YOU.name) {
                        Carousel(feed.fromYourArtists, key = { it.uri }) { item ->
                            FeedTile(item) { onOpenItem(item) }
                        }
                    }
                }

                if (showArtists && feed.topArtists.isEmpty() && feed.loading) {
                    item(contentType = "skeleton") {
                        SkeletonSection(tileWidth = 120.dp, tileHeight = 120.dp, round = true)
                    }
                }

                if (showArtists && feed.topArtists.isNotEmpty()) {
                    item(contentType = Feed.ARTISTS.name) { Heading(stringResource(R.string.listening_artists)) }
                    item(contentType = Feed.ARTISTS.name) {
                        Carousel(feed.topArtists, key = { it.uri }) { artist ->
                            ArtistTile(artist) { onOpenItem(artist) }
                        }
                    }
                }

                if (recent.isNotEmpty() && filter != Feed.ARTISTS) {
                    item(contentType = Feed.LIBRARY.name) { Heading(stringResource(R.string.play_again)) }
                    item(contentType = Feed.LIBRARY.name) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(top = 14.dp),
                        ) {
                            itemsIndexed(recent, key = { _, track -> track.uri }) { index, track ->
                                TrackTile(track) { onPlayRecent(recent, index) }
                            }
                        }
                    }
                }

                // Last, because it is the least of a surprise: everything above
                // changes month to month, this is the account's own constants.
                if (showForYou && feed.allTimeTracks.isNotEmpty()) {
                    item(contentType = Feed.FOR_YOU.name) { Heading(stringResource(R.string.all_time_favourites)) }
                    item(contentType = Feed.FOR_YOU.name) {
                        TrackRow(feed.allTimeTracks) { index ->
                            onPlayFeed(feed.allTimeTracks, index, classicsLabel)
                        }
                    }
                }

                item(contentType = "tail") { Box(Modifier.height(24.dp)) }
                }
                }
            }
        }
    }
}

/**
 * Name, picture and filters, always on screen.
 *
 * It shrinks rather than sliding away: the filter chips are a control, and a
 * control that has to be scrolled back to before it can be used may as well not
 * be there. What collapses is only the part that is decoration — the greeting
 * line and the size of the name.
 */
@Composable
private fun Header(
    modifier: Modifier = Modifier,
    name: String,
    avatarUrl: String?,
    /** Which service the page is showing: see the note on the line it draws. */
    @StringRes service: Int,
    /** Its mark, so the source is recognisable before the line is read. */
    serviceIcon: androidx.compose.ui.graphics.vector.ImageVector,
    /**
     * How far collapsed, as a lambda rather than a value.
     *
     * Deliberate, and the whole reason this scrolls smoothly: a `Float`
     * parameter is read when the header is composed, so every pixel of scroll
     * invalidated the composable that produced it — the home page, list content
     * lambdas included — several dozen times a second. Read inside `layout` and
     * `graphicsLayer` blocks instead, the same movement costs a measure pass and
     * no recomposition at all.
     */
    collapse: () -> Float,
    /** The chip drawn as active: the one that was tapped. */
    highlighted: Feed,
    backdrop: Backdrop,
    topPadding: Dp,
    onFilter: (Feed) -> Unit,
    onOpenSettings: () -> Unit,
    /**
     * False on a page with no feed to filter.
     *
     * The chips are the one part of this header that is Spotify's: they pick
     * between sections built out of that account's data, and YouTube's page is
     * whatever shelves the service itself sent.
     */
    showFilters: Boolean = true,
) {
    Column(
        modifier
            .fillMaxWidth()
            // Seats the header on the page instead of leaving it floating over
            // whatever the list has scrolled underneath it.
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.45f),
                    1f to Color.Transparent,
                ),
            )
            .padding(top = topPadding),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp)
                // The top padding, as a layout pass: `padding()` takes a Dp,
                // which would have to be computed while composing.
                .layout { measurable, constraints ->
                    val top = lerp(18.dp, 2.dp, collapse()).roundToPx()
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height + top) {
                        placeable.place(0, top)
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                // The account, and which service it is an account for.
                //
                // Small and above rather than large and below, which is where
                // the name used to be: whose account it is changes nothing about
                // the page, while *which service* changes everything on it — the
                // catalogue, the playlists, what the player can do — and that
                // was nowhere on screen. Together they read as one line of
                // provenance over the app's own name.
                //
                // Height goes with the alpha, so the collapsed header is a bar
                // rather than a bar with a blank line in it.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            val height = ((1f - collapse()) * placeable.height).toInt()
                            layout(placeable.width, height) { placeable.place(0, 0) }
                        }
                        .graphicsLayer {
                            clip = true
                            alpha = (1f - collapse() * 1.6f).coerceIn(0f, 1f)
                            transformOrigin = TransformOrigin(0f, 0f)
                        },
                ) {
                    Icon(
                        serviceIcon,
                        contentDescription = stringResource(service),
                        tint = InkDim,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(15.dp),
                    )
                    Text(
                        listOfNotNull(
                            stringResource(service),
                            name.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge,
                        color = InkDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // The app's own name, at the size the account name used to be.
                // It is what the page is, and unlike the account it is worth
                // reading at a glance; it shrinks a little as the header
                // collapses rather than leaving, because a bar with nothing in
                // it says nothing about where you are.
                AppLockup(
                    iconSize = 34.dp,
                    nameHeight = 22.dp,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .graphicsLayer {
                            val scale = 1f - 0.22f * collapse()
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        },
                )
            }

            // Falls back to the generated cover keyed on the name, which is what
            // every other missing image in the app gets rather than a grey
            // circle.
            // The way into the settings. They have no tab of their own — see
            // SettingsScreen — and the account picture is where anyone looks for
            // them anyway.
            Artwork(
                url = avatarUrl,
                title = name,
                modifier = Modifier
                    .padding(start = 14.dp)
                    // Measured from the scroll rather than sized in
                    // composition; see the note on `collapse`.
                    .layout { measurable, _ ->
                        val side = lerp(46.dp, 36.dp, collapse()).roundToPx()
                        val placeable = measurable.measure(Constraints.fixed(side, side))
                        layout(side, side) { placeable.place(0, 0) }
                    }
                    .clip(CircleShape)
                    .pressable(onOpenSettings, pressedScale = 0.90f)
                    .softShadow(CircleShape, elevation = 10.dp),
                corner = 23.dp,
                decodeSize = 46.dp,
            )
        }

        // The chips carry the header's bottom margin with them; without them
        // the list would start against the app's own name.
        if (showFilters) FilterRow(highlighted, backdrop, onFilter) else Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun FilterRow(selected: Feed, backdrop: Backdrop, onSelect: (Feed) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 14.dp, bottom = 10.dp),
    ) {
        items(Feed.entries.toList(), key = { it.name }) { entry ->
            val isSelected = entry == selected
            LiquidButton(
                onClick = { onSelect(entry) },
                backdrop = backdrop,
                contentHeight = 38.dp,
                contentPadding = 18.dp,
                blurRadius = 8.dp,
                // The selected chip is the same glass, filled a little harder.
                // A tinted fill would put the artwork's colour on a control
                // whose whole job is to be legible over any artwork.
                surfaceColor = if (isSelected) SelectedFilm else GlassFilm,
            ) {
                Text(
                    stringResource(entry.label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) Ink else InkDim,
                )
            }
        }
    }
}

/**
 * One release, the full width of the page.
 *
 * The caption sits in a pane of glass over the cover rather than under it, the
 * way the bars over the rest of the app do. Text straight on artwork needs a
 * gradient to survive a light cover, and a gradient large enough to do that
 * ends up dimming the picture it is supposed to be showing.
 */
@Composable
private fun FeedCard(item: SearchItem, onClick: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .fillMaxWidth()
            .softShadow(shape, elevation = 26.dp, spot = 0.55f)
            .clip(shape)
            .pressable(onClick, pressedScale = 0.98f),
    ) {
        // The same cover twice, and that is the point.
        //
        // Spotify serves album art at 640px and no larger, so a cover stretched
        // across a 1080px-wide card is upscaled by nearly two and looks soft —
        // which is exactly the "low quality covers" this replaced. The
        // background is allowed to be soft, so it takes the full width; the copy
        // that has to be sharp is drawn well under its native size.
        //
        // Softened by decoding it tiny and letting the upscale blur it, rather
        // than by `Modifier.blur`. That modifier renders into a layer of its own
        // which is not bound by this box's clip, so it painted a hard black
        // rectangle past the card's rounded corners and up into the header.
        Artwork(
            url = item.artworkUrl,
            title = item.title,
            modifier = Modifier.matchParentSize(),
            corner = 0.dp,
            decodeSize = 24.dp,
        )
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.42f)))

        Column(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Artwork(
                url = item.artworkUrl,
                title = item.title,
                modifier = Modifier
                    .size(COVER_SIZE)
                    .softShadow(RoundedCornerShape(18.dp), elevation = 24.dp, spot = 0.5f),
                corner = 18.dp,
                decodeSize = COVER_SIZE,
            )
            Text(
                item.title,
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 18.dp),
            )
            if (item.subtitle.isNotBlank()) {
                Text(
                    item.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.76f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

/** Round, because that is how every music app has drawn an artist for a decade. */
@Composable
private fun ArtistTile(artist: SearchItem, onClick: () -> Unit) {
    Column(
        Modifier
            .width(110.dp)
            .pressable(onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(
            url = artist.artworkUrl,
            title = artist.title,
            modifier = Modifier
                .size(110.dp)
                .softShadow(CircleShape, elevation = 14.dp),
            corner = 55.dp,
            decodeSize = 110.dp,
        )
        Text(
            artist.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun PlaylistTile(playlist: CatalogPlaylist, onClick: () -> Unit) {
    Column(
        Modifier
            .width(152.dp)
            .pressable(onClick),
    ) {
        Artwork(
            url = playlist.artworkUrl,
            title = playlist.name,
            modifier = Modifier
                .size(152.dp)
                .softShadow(RoundedCornerShape(20.dp), elevation = 18.dp),
            corner = 20.dp,
        )
        Text(
            playlist.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp),
        )
    }
}

@Composable
private fun TrackTile(track: CatalogTrack, onClick: () -> Unit) {
    Column(
        // No clip on the column: it would cut off the artwork's drop shadow,
        // which spreads wider than the tile itself.
        Modifier
            .width(128.dp)
            .pressable(onClick),
    ) {
        Artwork(
            url = track.artworkUrl,
            title = track.name,
            modifier = Modifier
                .size(128.dp)
                .softShadow(RoundedCornerShape(18.dp), elevation = 14.dp),
            corner = 18.dp,
            decodeSize = 128.dp,
        )
        Text(
            track.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp),
        )
        Text(
            track.artist,
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp),
        )
    }
}

/** A row of tracks that play where they are tapped. */
/**
 * A section's shape, before the section has arrived.
 *
 * Deliberately still — no shimmer. A sweep across half the page draws the eye
 * to the part of it that has nothing to look at, and these are gone within a
 * second or two anyway. What they are for is the layout: holding the space
 * means the rows that land later land in the place they already occupied.
 */
@Composable
private fun SkeletonSection(
    tileWidth: Dp,
    tileHeight: Dp,
    round: Boolean = false,
    card: Boolean = false,
) {
    val fill = Ink.copy(alpha = 0.07f)
    Column(Modifier.padding(top = 22.dp)) {
        // Stands in for the heading.
        Box(
            Modifier
                .padding(horizontal = 20.dp)
                .width(140.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(fill),
        )
        if (card) {
            Box(
                Modifier
                    .padding(horizontal = 20.dp, vertical = 14.dp)
                    .fillMaxWidth()
                    .height(tileHeight)
                    .clip(RoundedCornerShape(28.dp))
                    .background(fill),
            )
        } else {
            Row(
                Modifier
                    .padding(start = 20.dp, top = 14.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                repeat(SKELETON_TILES) {
                    Box(
                        Modifier
                            .width(tileWidth)
                            .height(tileHeight)
                            .clip(if (round) CircleShape else RoundedCornerShape(18.dp))
                            .background(fill),
                    )
                }
            }
        }
    }
}

/** Enough to reach the edge of the screen, which is all a placeholder row is. */
private const val SKELETON_TILES = 3

@Composable
private fun TrackRow(tracks: List<CatalogTrack>, onPlay: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(top = 14.dp),
    ) {
        itemsIndexed(tracks, key = { _, track -> track.uri }) { index, track ->
            TrackTile(track) { onPlay(index) }
        }
    }
}

/** An album or a playlist in a carousel: square art, name, who it is by. */
@Composable
private fun FeedTile(item: SearchItem, onClick: () -> Unit) {
    Column(
        Modifier
            .width(152.dp)
            .pressable(onClick),
    ) {
        Artwork(
            url = item.artworkUrl,
            title = item.title,
            modifier = Modifier
                .size(152.dp)
                .softShadow(RoundedCornerShape(20.dp), elevation = 18.dp),
            corner = 20.dp,
        )
        Text(
            item.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp),
        )
        Text(
            item.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp),
        )
    }
}

@Composable
private fun <T> Carousel(
    items: List<T>,
    key: (T) -> Any,
    item: @Composable (T) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(top = 14.dp),
    ) {
        items(items, key = key) { item(it) }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineLarge,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 30.dp),
    )
}

/** A glass pill, for the handful of places that need a button at all. */
@Composable
private fun GlassAction(label: String, backdrop: Backdrop, onClick: () -> Unit) {
    LiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        contentHeight = 50.dp,
        contentPadding = 26.dp,
        blurRadius = 8.dp,
        surfaceColor = GlassFilm,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = Ink)
    }
}

/**
 * The home page when the source is YouTube Music.
 *
 * Deliberately thin, and honestly so. Everything the Spotify home page is made
 * of — the playlists, the top artists, what the account played on other
 * devices — is an account's own data, and this backend is anonymous: there is
 * no account to have any of it. What does exist is what was played here, which
 * is kept on the device and works the same for both sources.
 *
 * So it shows that, and otherwise points at search rather than filling the
 * screen with rows invented to look busy.
 */
@Composable
private fun YouTubeHome(
    contentPadding: PaddingValues,
    home: MainViewModel.YouTubeHomeState,
    recent: List<CatalogTrack>,
    accountName: String,
    backdrop: Backdrop,
    onPlayRecent: (List<CatalogTrack>, Int) -> Unit,
    onPlayTrending: (List<CatalogTrack>, Int) -> Unit,
    onOpenPlaylist: (CatalogPlaylist) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val empty = home.rows.isEmpty() && recent.isEmpty()
    if (empty) {
        Box(Modifier.fillMaxSize()) {
            Centered {
                if (home.loading) {
                    CircularProgressIndicator(color = Ink, strokeWidth = 2.dp)
                } else {
                    AppIcon(84.dp)
                    SquareWordmark(height = 28.dp)
                    Text(
                        stringResource(R.string.youtube_home_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkDim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 40.dp),
                    )
                }
            }
            // Over the empty state rather than above it: the settings are the
            // one thing still worth reaching from a page with nothing on it,
            // and signing in is done from there.
            YouTubeHeader(
                accountName = accountName,
                topPadding = contentPadding.calculateTopPadding(),
                backdrop = backdrop,
                // Nothing scrolls under it here, so it stays as it opens.
                collapse = { 0f },
                onOpenSettings = onOpenSettings,
            )
        }
        return
    }

    val listState = rememberLazyListState()
    // The same collapse the Spotify page has, read the same way; see the note
    // there. The header is a sibling of the list rather than its first item
    // precisely so it can shrink while the list scrolls under it.
    val collapse by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / COLLAPSE_DISTANCE_PX)
                .coerceIn(0f, 1f)
        }
    }

    Column(Modifier.fillMaxSize()) {
        YouTubeHeader(
            accountName = accountName,
            topPadding = contentPadding.calculateTopPadding(),
            backdrop = backdrop,
            collapse = { collapse },
            onOpenSettings = onOpenSettings,
        )

        LazyColumn(
            // The same fade the Spotify list has: the rows dissolve into the
            // header rather than ending against it. See the note there for why
            // the mask needs a layer of its own.
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            FADE_FRACTION to Color.Black,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
            state = listState,
            // The header owns the top of the page; what is left is the room the
            // player bar needs at the bottom.
            contentPadding = PaddingValues(
                bottom = contentPadding.calculateBottomPadding(),
            ),
        ) {
        // Whatever shelves YouTube sent, in its own order and under its own
        // titles. Not reshaped into a fixed set of rows: the page is different
        // signed in and signed out, and it changes on its own besides.
        home.rows.forEach { row ->
            item(key = "head-${row.title}") { Heading(row.title) }

            if (row.tracks.isNotEmpty()) {
                item(key = "tracks-${row.title}") {
                    TrackRow(row.tracks) { index -> onPlayTrending(row.tracks, index) }
                }
            }

            if (row.items.isNotEmpty()) {
                item(key = "items-${row.title}") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(top = 14.dp),
                    ) {
                        items(row.items, key = { it.uri }) { entry ->
                            PlaylistTile(entry) { onOpenPlaylist(entry) }
                        }
                    }
                }
            }
        }

        // Last: the rows above change on their own, this one only changes
        // because the user did something.
        if (recent.isNotEmpty()) {
            item(key = "recent-head") { Heading(stringResource(R.string.play_again)) }
            item(key = "recent") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 14.dp),
                ) {
                    itemsIndexed(recent, key = { _, track -> track.uri }) { index, track ->
                        TrackTile(track) { onPlayRecent(recent, index) }
                    }
                }
            }
        }
        }
    }
}

/**
 * The YouTube page's header.
 *
 * Far plainer than [Header]: that one carries the feed's filter chips and a
 * collapse driven by the scroll, and neither has anything to act on here —
 * there are no feed sections to filter. What it must keep is the way into the
 * settings, because that is the only one there is; the app has no settings tab.
 */
@Composable
private fun YouTubeHeader(
    accountName: String,
    topPadding: Dp,
    backdrop: Backdrop,
    collapse: () -> Float,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Header(
        modifier = modifier,
        name = accountName,
        avatarUrl = null,
        service = R.string.backend_youtube,
        serviceIcon = PhosphorIcons.Regular.YoutubeLogo,
        collapse = collapse,
        highlighted = Feed.entries.first(),
        backdrop = backdrop,
        topPadding = topPadding,
        onFilter = {},
        onOpenSettings = onOpenSettings,
        showFilters = false,
    )
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

@StringRes
private fun greeting(): Int = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..12 -> R.string.good_morning
    in 13..17 -> R.string.good_afternoon
    else -> R.string.good_evening
}

/** A harder film for the chip that is on; see the note at the call site. */
private val SelectedFilm = Color.White.copy(alpha = 0.26f)

/**
 * How far the list scrolls before the header is fully collapsed, in pixels.
 *
 * Pixels rather than dp because it is compared against a scroll offset, which
 * the list reports in pixels; converting per frame to compare two numbers would
 * be work for nothing.
 */
private const val COLLAPSE_DISTANCE_PX = 140f


/** Comfortably under the 640px Spotify serves, so it is never upscaled. */
private val COVER_SIZE = 210.dp

/** How much of the list's height the fade under the header covers. */
private const val FADE_FRACTION = 0.045f

private const val FEED_SIZE = 6
private const val CAROUSEL_SIZE = 8

/** With the library filter on, the carousel is the whole point of the page. */
private const val LIBRARY_SIZE = 30
