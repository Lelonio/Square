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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import dev.lelonio.square.R
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.sortedByRecentlyOpened
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.components.Artwork
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.glass.pressable
import dev.lelonio.square.ui.player.GlassFilm
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import dev.lelonio.square.ui.theme.softShadow
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ListBullets
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.SquaresFour

/** How the playlists are arranged. */
private enum class Layout { GRID, LIST }

/** What the library is sorted by. */
private enum class Order(@StringRes val label: Int) {
    RECENT(R.string.recently_opened),
    NAME(R.string.name),
    ADDED(R.string.spotify_order),
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
    /** False when the source cannot be written to; see MusicBackend. */
    canEdit: Boolean = false,
    onCreatePlaylist: () -> Unit = {},
    /** Long press: rename and delete live in a sheet, like a track's actions. */
    onPlaylistMenu: (CatalogPlaylist) -> Unit = {},
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
            var layout by remember { mutableStateOf(Layout.GRID) }
            var order by remember { mutableStateOf(Order.RECENT) }

            val playlists = remember(state.playlists, playlistOrder, order) {
                when (order) {
                    Order.RECENT -> state.playlists.sortedByRecentlyOpened(playlistOrder)
                    Order.NAME -> state.playlists.sortedWith(
                        compareBy(String.CASE_INSENSITIVE_ORDER) { it.name },
                    )
                    // The order the account added them, which is what the
                    // rootlist arrives in.
                    Order.ADDED -> state.playlists
                }
            }

            Column(Modifier.fillMaxSize()) {
                Header(
                    count = playlists.size,
                    layout = layout,
                    order = order,
                    backdrop = backdrop,
                    topPadding = contentPadding.calculateTopPadding(),
                    onLayout = { layout = it },
                    onOrder = { order = it },
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
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(playlists, key = { it.uri }) { playlist ->
                            GridTile(
                                playlist,
                                onClick = { onOpenPlaylist(playlist) },
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
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(playlists, key = { it.uri }) { playlist ->
                            ListRow(
                                playlist,
                                onClick = { onOpenPlaylist(playlist) },
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
        }
    }
}

@Composable
private fun Header(
    count: Int,
    layout: Layout,
    order: Order,
    backdrop: Backdrop,
    topPadding: Dp,
    onLayout: (Layout) -> Unit,
    onOrder: (Order) -> Unit,
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
                Text(
                    stringResource(R.string.playlist_count, count),
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
                    blurRadius = 8.dp,
                    surfaceColor = GlassFilm,
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
                blurRadius = 8.dp,
                surfaceColor = GlassFilm,
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

        // The sort is three chips rather than a menu: there are only three, and
        // a menu hides which one is active behind a tap.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Order.entries.forEach { entry ->
                val selected = entry == order
                LiquidButton(
                    onClick = { onOrder(entry) },
                    backdrop = backdrop,
                    contentHeight = 36.dp,
                    contentPadding = 14.dp,
                    blurRadius = 8.dp,
                    surfaceColor = if (selected) SelectedFilm else GlassFilm,
                ) {
                    Text(
                        stringResource(entry.label),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) Ink else InkDim,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun GridTile(
    playlist: CatalogPlaylist,
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
private fun ListRow(
    playlist: CatalogPlaylist,
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
    }
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

/** A harder film for the chip that is on, matching the home page. */
private val SelectedFilm = Color.White.copy(alpha = 0.26f)
