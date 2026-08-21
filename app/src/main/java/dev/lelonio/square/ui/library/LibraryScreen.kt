package dev.lelonio.square.ui.library

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.R
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.sortedByRecentlyOpened
import dev.lelonio.square.data.withLocalFilesFirst
import dev.lelonio.square.data.withPinnedFirst
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.components.Artwork
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.glass.pressable
import dev.lelonio.square.ui.player.GlassFilm
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import dev.lelonio.square.ui.theme.softShadow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import dev.lelonio.square.ui.components.CHOICE_MENU_WIDTH
import dev.lelonio.square.ui.components.GlassChoiceItem
import dev.lelonio.square.ui.components.GlassMenuRule
import dev.lelonio.square.ui.components.GlassChoiceMenu
import dev.lelonio.square.ui.glass.backdrop.backdrops.layerBackdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.rememberCombinedBackdrop
import dev.lelonio.square.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.PushPin
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowsDownUp
import com.adamglin.phosphoricons.regular.ListBullets
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.SquaresFour

/** How the playlists are arranged. */
private enum class Layout { GRID, LIST }

/** What the library is sorted by. */
private enum class Order(val key: String, @StringRes val label: Int) {
    RECENT("recent", R.string.recently_opened),
    NAME("name", R.string.name),
    ADDED("added", R.string.spotify_order),
}

/**
 * Which kind of thing the library is showing.
 *
 * The chips used to be the sort, which put the three least-used controls in the
 * most prominent place on the screen and left no way at all to say "only
 * albums". The sort is a menu now; the chips answer the question a library is
 * actually asked, which is what am I looking at.
 */
private enum class Filter(@StringRes val label: Int, @StringRes val count: Int) {
    ALL(R.string.library_all, R.string.item_count),
    PLAYLISTS(R.string.playlists, R.string.playlist_count),
    ARTISTS(R.string.artists, R.string.artist_count),
    ALBUMS(R.string.albums, R.string.album_count),
}

/**
 * Every playlist on the account.
 *
 * Rewritten alongside the home page and for the same reason: this was a plain
 * Material list with a divider between every row, which is the one thing on
 * screen that cannot be made of glass. It is now the same material as the rest —
 * and, being the screen you come to when you know what you are looking for, it
 * gets the controls the home page has no room for: a grid, and a sort.
 */
@Composable
fun LibraryScreen(
    state: MainViewModel.UiState,
    contentPadding: PaddingValues,
    onLogIn: () -> Unit,
    onRetry: () -> Unit,
    onLogOut: () -> Unit,
    onOpenPlaylist: (CatalogPlaylist) -> Unit,
    /** URIs most recently opened first; see PlaylistOrderStore. */
    playlistOrder: List<String>,
    /** URIs the listener pinned to the top; see PinnedPlaylistStore. */
    pinned: List<String> = emptyList(),
    /** False when the source cannot be written to; see MusicBackend. */
    canEdit: Boolean = false,
    onCreatePlaylist: () -> Unit = {},
    /** Long press: rename and delete live in a sheet, like a track's actions. */
    onPlaylistMenu: (CatalogPlaylist) -> Unit = {},
    /** The artists the account follows; empty leaves the shelf out entirely. */
    artists: List<dev.lelonio.square.data.SearchItem> = emptyList(),
    onOpenArtist: (dev.lelonio.square.data.SearchItem) -> Unit = {},
    /** The albums the account has saved; see MainViewModel.savedAlbums. */
    albums: List<CatalogPlaylist> = emptyList(),
    backdrop: Backdrop,
) {
    when (state) {
        MainViewModel.UiState.LoggedOut -> Centered {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayLarge)
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
            // Both are the listener's own decision about their library rather
            // than a mode of this screen, so they are read from where they were
            // left and written as they change; see LibraryViewStore.
            val appContext = LocalContext.current.applicationContext
            val view = remember(appContext) {
                (appContext as dev.lelonio.square.SquareApplication).libraryView
            }
            var layout by remember { mutableStateOf(if (view.grid) Layout.GRID else Layout.LIST) }
            var order by remember {
                mutableStateOf(Order.entries.firstOrNull { it.key == view.order } ?: Order.RECENT)
            }
            var filter by remember { mutableStateOf(Filter.ALL) }
            var sortOpen by remember { mutableStateOf(false) }
            // Recorded so the menu has the tiles behind it to blur, rather than
            // the page colour: over a flat fill glass has nothing to bend and
            // comes out looking like a hole. Safe to record — the menu is drawn
            // outside the grid, so nothing in this layer samples it.
            val listBackdrop = rememberLayerBackdrop()
            var descending by remember { mutableStateOf(view.descending) }
            val onOrderChosen: (Order) -> Unit = {
                order = it
                view.order = it.key
            }
            var sortAnchor by remember { mutableStateOf(androidx.compose.ui.unit.IntOffset.Zero) }
            val density = androidx.compose.ui.platform.LocalDensity.current

            val artistItems = remember(artists) {
                artists.map { artist ->
                    CatalogPlaylist(
                        uri = artist.uri,
                        name = artist.title,
                        artworkUrl = artist.artworkUrl,
                    )
                }
            }

            // What the chips are asking for, before any sorting.
            val shown = remember(state.playlists, albums, artistItems, filter) {
                when (filter) {
                    Filter.ALL -> state.playlists + albums
                    Filter.PLAYLISTS -> state.playlists
                    Filter.ALBUMS -> albums
                    Filter.ARTISTS -> artistItems
                }
            }

            val playlists = remember(shown, playlistOrder, pinned, order, descending) {
                when (order) {
                    Order.RECENT -> shown.sortedByRecentlyOpened(playlistOrder)
                    Order.NAME -> shown.sortedWith(
                        compareBy(String.CASE_INSENSITIVE_ORDER) { it.name },
                    )
                    // The order the account added them, which is what the
                    // rootlist arrives in.
                    Order.ADDED -> shown
                }
                    // The direction belongs to the sort rather than beside it:
                    // reversing "recently opened" is "least recently", and a
                    // separate control for that would be a second sort.
                    .let { if (descending) it.reversed() else it }
                    // Pinning outranks the sort: it is the one instruction the
                    // listener gave about this list themselves.
                    .withPinnedFirst(pinned)
                    // And the phone's own music outranks that, because it is
                    // not a playlist competing for a place: it is a fixed shelf
                    // of the library, and one nobody has opened yet would
                    // otherwise sit sixtieth among lists they have.
                    .withLocalFilesFirst()
            }

            Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Header(
                    count = playlists.size,
                    layout = layout,
                    order = order,
                    filter = filter,
                    backdrop = backdrop,
                    topPadding = contentPadding.calculateTopPadding(),
                    onLayout = {
                        layout = it
                        view.grid = it == Layout.GRID
                    },
                    onOrder = { onOrderChosen(it) },
                    onFilter = { filter = it },
                    onSort = { sortOpen = true },
                    onSortAnchor = { sortAnchor = it },
                    canEdit = canEdit,
                    onCreatePlaylist = onCreatePlaylist,
                )

                val listPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 8.dp,
                    bottom = contentPadding.calculateBottomPadding(),
                )

                when (layout) {
                    Layout.GRID -> LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = listPadding,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .layerBackdrop(listBackdrop),
                    ) {
                        if (artists.isNotEmpty() && filter == Filter.ALL) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "artists") {
                                ArtistShelf(artists, onOpenArtist)
                            }
                        }

                        items(playlists, key = { it.uri }) { playlist ->
                            GridTile(
                                playlist,
                                pinned = playlist.uri in pinned,
                                onClick = { open(playlist, artists, onOpenPlaylist, onOpenArtist) },
                                onLongClick = if (canEdit) {
                                    { onPlaylistMenu(playlist) }
                                } else {
                                    null
                                },
                            )
                        }
                    }

                    Layout.LIST -> LazyColumn(
                        contentPadding = listPadding,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .layerBackdrop(listBackdrop),
                    ) {
                        if (artists.isNotEmpty() && filter == Filter.ALL) {
                            item(key = "artists") { ArtistShelf(artists, onOpenArtist) }
                        }

                        items(playlists, key = { it.uri }) { playlist ->
                            ListRow(
                                playlist,
                                pinned = playlist.uri in pinned,
                                onClick = { open(playlist, artists, onOpenPlaylist, onOpenArtist) },
                                onLongClick = if (canEdit) {
                                    { onPlaylistMenu(playlist) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }

            // The app's own menu rather than Material's card: see GlassMenu.
            // The same one the track sort on a playlist page opens, for the
            // same reason — a menu that arrives from another design system is
            // the one surface on screen that says so.
            GlassChoiceMenu(
                visible = sortOpen,
                anchor = sortAnchor.leftOf(density),
                backdrop = rememberCombinedBackdrop(backdrop, listBackdrop),
                onDismiss = { sortOpen = false },
            ) {
                Order.entries.forEach { entry ->
                    GlassChoiceItem(
                        stringResource(entry.label),
                        selected = entry == order,
                    ) {
                        sortOpen = false
                        onOrderChosen(entry)
                    }
                }
                GlassMenuRule()

                // Deliberately leaves the menu open: the direction is the one
                // choice people flip back and forth to compare, and reopening
                // for each flip makes a sort feel heavy.
                GlassChoiceItem(
                    stringResource(R.string.sort_descending),
                    selected = descending,
                ) {
                    descending = !descending
                    view.descending = descending
                }
            }
            }
        }
    }
}

/** Where a menu hanging off the sort button's right edge goes. */
private fun androidx.compose.ui.unit.IntOffset.leftOf(
    density: androidx.compose.ui.unit.Density,
): androidx.compose.ui.unit.IntOffset {
    val width = with(density) {
        CHOICE_MENU_WIDTH.roundToPx()
    }
    val gap = with(density) { 8.dp.roundToPx() }
    return androidx.compose.ui.unit.IntOffset((x - width + gap).coerceAtLeast(gap), y + gap)
}

/**
 * Opens whatever the tile stands for.
 *
 * The grid holds playlists, albums and, under the artists chip, artists. The
 * first two are track lists and open the same way; an artist is a page of its
 * own, and the only thing that knows which is which is the URI.
 */
private fun open(
    item: CatalogPlaylist,
    artists: List<dev.lelonio.square.data.SearchItem>,
    onOpenPlaylist: (CatalogPlaylist) -> Unit,
    onOpenArtist: (dev.lelonio.square.data.SearchItem) -> Unit,
) {
    val artist = artists.firstOrNull { it.uri == item.uri }
    if (artist != null) onOpenArtist(artist) else onOpenPlaylist(item)
}

@Composable
private fun Header(
    count: Int,
    layout: Layout,
    order: Order,
    filter: Filter,
    backdrop: Backdrop,
    topPadding: Dp,
    onLayout: (Layout) -> Unit,
    onOrder: (Order) -> Unit,
    onFilter: (Filter) -> Unit,
    onSort: () -> Unit,
    onSortAnchor: (androidx.compose.ui.unit.IntOffset) -> Unit,
    canEdit: Boolean,
    onCreatePlaylist: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = topPadding),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 20.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.library), style = MaterialTheme.typography.displayLarge)
                // Counting whatever the chips are showing, and saying so: the
                // same number labelled "playlists" under the albums chip is a
                // line that contradicts the screen it sits on.
                Text(
                    stringResource(filter.count, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkDim,
                )
            }

            // Only where a playlist can actually be made: on a source with no
            // account signed in, a plus that always failed would be worse than
            // no plus at all.
            if (canEdit) {
                LiquidButton(
                    onClick = onCreatePlaylist,
                    backdrop = backdrop,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(42.dp),
                    contentHeight = 42.dp,
                    contentPadding = 0.dp,
                ) {
                    Icon(
                        PhosphorIcons.Regular.Plus,
                        contentDescription = stringResource(R.string.new_playlist),
                        tint = Ink,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // One button that swaps between the two arrangements rather than a
            // pair of them: with two options, a toggle showing the *other* one
            // is both smaller and unambiguous.
            LiquidButton(
                onClick = { onLayout(if (layout == Layout.GRID) Layout.LIST else Layout.GRID) },
                backdrop = backdrop,
                modifier = Modifier.size(42.dp),
                contentHeight = 42.dp,
                contentPadding = 0.dp,
            ) {
                Icon(
                    if (layout == Layout.GRID) PhosphorIcons.Regular.ListBullets
                    else PhosphorIcons.Regular.SquaresFour,
                    contentDescription = stringResource(R.string.change_layout),
                    tint = Ink,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // The chips say what is being shown; the sort sits at the end of the
        // same row as a menu, because it is a choice among three that is made
        // once and then left alone.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Filter.entries.forEach { entry ->
                val selected = entry == filter
                LiquidButton(
                    onClick = { onFilter(entry) },
                    backdrop = backdrop,
                    flat = true,
                    contentHeight = 36.dp,
                    contentPadding = 14.dp,
                    surfaceColor = if (selected) SelectedFilm else Color.Unspecified,
                ) {
                    Text(
                        stringResource(entry.label),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) Ink else InkDim,
                        maxLines = 1,
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            LiquidButton(
                onClick = onSort,
                backdrop = backdrop,
                flat = true,
                contentHeight = 36.dp,
                contentPadding = 12.dp,
                modifier = Modifier.onGloballyPositioned {
                    val bounds = it.boundsInRoot()
                    onSortAnchor(
                        androidx.compose.ui.unit.IntOffset(
                            bounds.right.toInt(),
                            bounds.bottom.toInt(),
                        ),
                    )
                },
            ) {
                Icon(
                    PhosphorIcons.Regular.ArrowsDownUp,
                    contentDescription = stringResource(R.string.sort),
                    tint = InkDim,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun GridTile(
    playlist: CatalogPlaylist,
    pinned: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    Column(
        Modifier.pressable(onClick, onLongClick = onLongClick),
    ) {
        Artwork(
            url = playlist.artworkUrl,
            title = playlist.name,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .softShadow(RoundedCornerShape(20.dp), elevation = 18.dp, spot = 0.4f),
            corner = 20.dp,
            decodeSize = 220.dp,
        )
        Row(
            Modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pinned) PinMark(Modifier.padding(end = 6.dp))
            Text(
                playlist.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ListRow(
    playlist: CatalogPlaylist,
    pinned: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .pressable(
                onClick,
                shape = RoundedCornerShape(16.dp),
                pressedScale = 0.98f,
                onLongClick = onLongClick,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            url = playlist.artworkUrl,
            title = playlist.name,
            modifier = Modifier
                .size(52.dp)
                .softShadow(RoundedCornerShape(14.dp), elevation = 10.dp),
            corner = 14.dp,
            decodeSize = 52.dp,
        )
        Text(
            playlist.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 16.dp)
                .weight(1f),
        )
        if (pinned) PinMark()
    }
}

/** The quiet mark on a pinned row. Small: the position is the message. */
@Composable
private fun PinMark(modifier: Modifier = Modifier) {
    Icon(
        PhosphorIcons.Fill.PushPin,
        contentDescription = stringResource(R.string.pinned),
        tint = InkDim,
        modifier = modifier.size(14.dp),
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

/** A harder film for the chip that is on, matching the home page. */
private val SelectedFilm = Color.White.copy(alpha = 0.26f)

/**
 * The artists the account follows, along the top of the library.
 *
 * Round, and in a row that scrolls sideways: it is the shape Spotify made mean
 * "a person" and the one that keeps a long list from pushing the playlists off
 * the screen. Part of the list rather than pinned above it, so it scrolls away
 * with everything else — a shelf that will not leave is a shelf in the way.
 */
@Composable
private fun ArtistShelf(
    artists: List<dev.lelonio.square.data.SearchItem>,
    onOpen: (dev.lelonio.square.data.SearchItem) -> Unit,
) {
    Column(Modifier.padding(bottom = 18.dp)) {
        Text(
            stringResource(R.string.artists_you_follow),
            style = MaterialTheme.typography.titleSmall,
            color = Ink,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(artists, key = { it.uri }) { artist ->
                Column(
                    Modifier
                        .width(76.dp)
                        .pressable({ onOpen(artist) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Artwork(
                        url = artist.artworkUrl,
                        title = artist.title,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .softShadow(CircleShape, elevation = 10.dp),
                        corner = 36.dp,
                        decodeSize = 72.dp,
                    )
                    Text(
                        artist.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = Ink,
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
