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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
 */
private enum class Feed(val label: String) {
    ALL("Tutto"),
    RELEASES("Novità"),
    ARTISTS("Artisti"),
    LIBRARY("Libreria"),
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

            LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
                item(contentType = "greeting") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 24.dp, top = 34.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                greeting().uppercase(),
                                style = MaterialTheme.typography.labelLarge,
                                color = InkDim,
                            )
                            Text(
                                state.displayName,
                                style = MaterialTheme.typography.displayLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }

                        // Falls back to the generated cover keyed on the name,
                        // which is the same thing every other missing image in
                        // the app gets rather than a grey circle.
                        Artwork(
                            url = state.avatarUrl,
                            title = state.displayName,
                            modifier = Modifier
                                .padding(start = 14.dp)
                                .size(46.dp)
                                .softShadow(CircleShape, elevation = 10.dp),
                            corner = 23.dp,
                            decodeSize = 46.dp,
                        )
                    }
                }

                item(contentType = "filters") {
                    FilterRow(filter, backdrop) { filter = it }
                }

                if (showReleases && feed.newReleases.isNotEmpty()) {
                    item(contentType = "heading") { Heading("Novità") }
                    // Cards rather than another row of thumbnails. A carousel
                    // says "here is a list, pick one"; this is meant to be
                    // looked at, so each release gets the width of the page and
                    // the cover carries it.
                    items(
                        feed.newReleases.take(FEED_SIZE),
                        key = { it.uri },
                        contentType = { "card" },
                    ) { item ->
                        FeedCard(item) { onOpenItem(item) }
                    }
                }

                if (showArtists && feed.topArtists.isNotEmpty()) {
                    item(contentType = "heading") { Heading("Artisti che ascolti") }
                    item(contentType = "artists") {
                        Carousel(feed.topArtists, key = { it.uri }) { artist ->
                            ArtistTile(artist) { onOpenItem(artist) }
                        }
                    }
                }

                if (showLibrary || filter == Feed.ALL) {
                    item(contentType = "heading") { Heading("Le tue playlist") }
                    item(contentType = "playlists") {
                        Carousel(
                            playlists.take(if (showLibrary) LIBRARY_SIZE else CAROUSEL_SIZE),
                            key = { it.uri },
                        ) { playlist ->
                            PlaylistTile(playlist) { onOpenPlaylist(playlist) }
                        }
                    }
                }

                if (recent.isNotEmpty() && filter != Feed.ARTISTS) {
                    item(contentType = "heading") { Heading("Riascolta") }
                    item(contentType = "recent") {
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

@Composable
private fun FilterRow(selected: Feed, backdrop: Backdrop, onSelect: (Feed) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 22.dp),
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
            .padding(horizontal = 20.dp, vertical = 7.dp)
            .fillMaxWidth()
            .height(320.dp)
            .softShadow(shape, elevation = 22.dp, spot = 0.24f)
            .clip(shape)
            .clickable(onClick = onClick),
    ) {
        Artwork(
            url = item.artworkUrl,
            title = item.title,
            modifier = Modifier.fillMaxSize(),
            corner = 0.dp,
        )

        // The caption sits straight on the cover, so this gradient is what makes
        // it readable rather than decoration. Weighted to the bottom third: any
        // higher and it starts dimming the part of the picture the card exists
        // to show.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.55f to Color.Black.copy(alpha = 0.18f),
                        1f to Color.Black.copy(alpha = 0.78f),
                    ),
                ),
        )

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 22.dp, vertical = 20.dp),
        ) {
            Text(
                item.title,
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.subtitle.isNotBlank()) {
                Text(
                    item.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.76f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
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

private const val FEED_SIZE = 6
private const val CAROUSEL_SIZE = 8

/** With the library filter on, the carousel is the whole point of the page. */
private const val LIBRARY_SIZE = 30
