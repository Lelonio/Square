package dev.emanuele.spot.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.kyant.backdrop.Backdrop
import dev.emanuele.spot.data.CatalogPlaylist
import dev.emanuele.spot.data.CatalogTrack
import dev.emanuele.spot.data.SearchItem
import dev.emanuele.spot.data.sortedByRecentlyOpened
import dev.emanuele.spot.ui.MainViewModel
import dev.emanuele.spot.ui.components.Artwork
import dev.emanuele.spot.ui.glass.LiquidButton
import dev.emanuele.spot.ui.player.GlassFilm
import dev.emanuele.spot.ui.theme.Ink
import dev.emanuele.spot.ui.theme.InkDim
import dev.emanuele.spot.ui.theme.softShadow
import java.util.Calendar

/**
 * What the feed is showing.
 *
 * A filter rather than tabs: the sections keep their order and the chips only
 * decide which of them are on the page, so nothing moves around when you switch
 * and "Tutto" is genuinely the whole thing rather than a fourth view.
 *
 * The chips are in the sections' own order, so scrolling walks along them left
 * to right instead of jumping about.
 */
private enum class Feed(val label: String) {
    ALL("Tutto"),
    LIBRARY("Libreria"),
    RELEASES("Novità"),
    ARTISTS("Artisti"),
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
    feed: MainViewModel.FeedState,
    onOpenItem: (SearchItem) -> Unit,
    /** The layer the glass on this page refracts; see the note in SpotApp. */
    backdrop: Backdrop,
) {
    when (state) {
        MainViewModel.UiState.LoggedOut -> Centered {
            Text("Spot", style = MaterialTheme.typography.displayLarge)
            Text(
                "Client non ufficiale per account Premium",
                style = MaterialTheme.typography.bodyMedium,
                color = InkDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
            GlassAction("Accedi con Spotify", backdrop, onLogIn)
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
            GlassAction("Riprova", backdrop, onRetry)
            GlassAction("Esci", backdrop, onLogOut)
        }

        is MainViewModel.UiState.Ready -> {
            var filter by remember { mutableStateOf(Feed.ALL) }
            // Spotify's rootlist arrives in the order the account added them,
            // which for an old account is close to arbitrary — the playlist
            // opened every day can sit thirtieth.
            val playlists = remember(state.playlists, playlistOrder) {
                state.playlists.sortedByRecentlyOpened(playlistOrder)
            }
            val showReleases = filter == Feed.ALL || filter == Feed.RELEASES
            val showArtists = filter == Feed.ALL || filter == Feed.ARTISTS
            val showLibrary = filter == Feed.ALL || filter == Feed.LIBRARY

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

            // Which section is being looked at, for the chips.
            //
            // Read from the item type rather than from an index: sections
            // appear and disappear with the filter, so any arithmetic over
            // positions would be wrong the moment one of them is empty.
            val visibleSection by remember {
                derivedStateOf {
                    listState.layoutInfo.visibleItemsInfo
                        .firstNotNullOfOrNull { info ->
                            Feed.entries.firstOrNull { it.name == info.contentType }
                        }
                }
            }

            Column(Modifier.fillMaxSize()) {
                Header(
                    name = state.displayName,
                    avatarUrl = state.avatarUrl,
                    collapse = collapse,
                    filter = filter,
                    highlighted = if (filter == Feed.ALL) visibleSection ?: Feed.ALL else filter,
                    backdrop = backdrop,
                    topPadding = contentPadding.calculateTopPadding(),
                    onFilter = { filter = it },
                )

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
                if (showLibrary || filter == Feed.ALL) {
                    item(contentType = Feed.LIBRARY.name) { Heading("Le tue playlist") }
                    item(contentType = Feed.LIBRARY.name) {
                        Carousel(
                            playlists.take(if (showLibrary) LIBRARY_SIZE else CAROUSEL_SIZE),
                            key = { it.uri },
                        ) { playlist ->
                            PlaylistTile(playlist) { onOpenPlaylist(playlist) }
                        }
                    }
                }

                if (showReleases && feed.newReleases.isNotEmpty()) {
                    item(contentType = Feed.RELEASES.name) { Heading("Novità") }
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

                if (showArtists && feed.topArtists.isNotEmpty()) {
                    item(contentType = Feed.ARTISTS.name) { Heading("Artisti che ascolti") }
                    item(contentType = Feed.ARTISTS.name) {
                        Carousel(feed.topArtists, key = { it.uri }) { artist ->
                            ArtistTile(artist) { onOpenItem(artist) }
                        }
                    }
                }

                if (recent.isNotEmpty() && filter != Feed.ARTISTS) {
                    item(contentType = Feed.LIBRARY.name) { Heading("Riascolta") }
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

                item(contentType = "tail") { Box(Modifier.height(24.dp)) }
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
    name: String,
    avatarUrl: String?,
    collapse: Float,
    filter: Feed,
    /** The chip drawn as active, which follows the scroll while nothing is filtered. */
    highlighted: Feed,
    backdrop: Backdrop,
    topPadding: Dp,
    onFilter: (Feed) -> Unit,
) {
    Column(
        Modifier
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
                .padding(
                    start = 24.dp,
                    end = 24.dp,
                    top = lerp(18.dp, 2.dp, collapse),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                // Height goes with the alpha. Fading it alone would leave the
                // header the same size with a blank line in it.
                if (collapse < 1f) {
                    Text(
                        greeting().uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = InkDim,
                        maxLines = 1,
                        modifier = Modifier
                            .height(lerp(18.dp, 0.dp, collapse))
                            .graphicsLayer { alpha = 1f - collapse },
                    )
                }
                Text(
                    name,
                    style = MaterialTheme.typography.displayLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Scaled rather than swapped for a smaller style: a style
                    // change is a jump, and this has to track a finger.
                    modifier = Modifier.graphicsLayer {
                        val scale = 1f - 0.34f * collapse
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    },
                )
            }

            // Falls back to the generated cover keyed on the name, which is what
            // every other missing image in the app gets rather than a grey
            // circle.
            Artwork(
                url = avatarUrl,
                title = name,
                modifier = Modifier
                    .padding(start = 14.dp)
                    .size(lerp(46.dp, 36.dp, collapse))
                    .softShadow(CircleShape, elevation = 10.dp),
                corner = 23.dp,
                decodeSize = 46.dp,
            )
        }

        FilterRow(highlighted, backdrop, onFilter)
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
                    entry.label,
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
            .clickable(onClick = onClick),
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
            .clickable(onClick = onClick),
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
            .clickable(onClick = onClick),
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
            .clickable(onClick = onClick),
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

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..12 -> "Buongiorno"
    in 13..17 -> "Buon pomeriggio"
    else -> "Buonasera"
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
