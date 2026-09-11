package dev.lelonio.square.ui.search

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.R
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.SearchItem
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.components.Artwork
import dev.lelonio.square.ui.components.SwipeToQueue
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.glass.pressable
import dev.lelonio.square.ui.player.GlassFilm
import dev.lelonio.square.ui.settings.WebApiSetup
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import androidx.compose.foundation.clickable
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.bold.MagnifyingGlass
import com.adamglin.phosphoricons.bold.X
import com.adamglin.phosphoricons.regular.DotsThree

/** Which kind of result the page is showing. */
private enum class Kind(@StringRes val label: Int) {
    ALL(R.string.feed_all),
    TRACKS(R.string.tracks),
    ARTISTS(R.string.artists),
    ALBUMS(R.string.albums),
    PLAYLISTS(R.string.playlists),
}

/**
 * Search across tracks, artists, albums and playlists.
 *
 * The page opens on the best of each kind rather than on every track the
 * service returned: a search is answered by the first few rows or not at all,
 * and the twenty below them only ever made the artist you were actually after
 * something to scroll past. The chips are how you ask for the rest, and they
 * are the same control the home page uses for the same purpose.
 */
@Composable
fun SearchScreen(
    state: MainViewModel.SearchState,
    webApi: MainViewModel.WebApiState,
    contentPadding: PaddingValues,
    nowPlayingUri: String?,
    onClientIdChange: (String) -> Unit,
    onConnectWebApi: () -> Unit,
    onPlayTrack: (List<CatalogTrack>, Int) -> Unit,
    /**
     * Songs found by searching and played before, newest first.
     *
     * Shown in place of the prompt on an empty box, which is where a list of
     * things somebody already wanted belongs.
     */
    history: List<dev.lelonio.square.data.SearchHistoryEntry>,
    onClearHistory: () -> Unit,
    /** No connection: the catalogue cannot be searched at all. */
    offline: Boolean = false,
    onEnqueue: (CatalogTrack) -> Unit,
    /** Opens the same track sheet the library rows open. */
    onTrackMenu: (CatalogTrack) -> Unit,
    onOpenContext: (SearchItem) -> Unit,
    /**
     * What is typed goes here.
     *
     * The field used to live in the bottom bar, growing out of the search
     * circle. That was a local change to the bar, and the bar is the library's
     * again — so the box you type in is back on the page that answers it, which
     * is also where every other client puts it.
     */
    onQuery: (String) -> Unit,
    /** Asked for as the list nears its end; see MainViewModel.loadMoreSearch. */
    onLoadMore: () -> Unit = {},
    backdrop: Backdrop,
) {
    var kind by remember { mutableStateOf(Kind.ALL) }
    // A new search answers a new question, so the page goes back to showing all
    // of the answer rather than staying filtered to what the last one was about.
    val query = state.query
    remember(query) { kind = Kind.ALL }

    val listState = rememberLazyListState()

    // Another page as the end comes into view, not on a button.
    //
    // Twenty of each kind is the page every client asks for and a fine first
    // answer, but a search for a name a hundred records share stopped dead at
    // twenty. Watching the last few rows rather than the very last means the
    // next page is usually there before the reader arrives at it.
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_AHEAD
        }
    }
    LaunchedEffect(nearEnd, state.query, state.results.count) {
        if (nearEnd && !state.loading && !state.loadingMore && !state.exhausted) onLoadMore()
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = contentPadding,
    ) {
        // No field here: it is in the bar, where the search button grows into
        // it. A page that answers a query by drawing a second box asks which of
        // the two is listening.

        if (!state.results.isEmpty && !state.loading) {
            item(contentType = "chips") {
                KindRow(kind, backdrop) { kind = it }
            }
        }

        when {
            // Nothing here can be answered without a connection: the catalogue
            // is Spotify's and none of it is on the phone. Said plainly rather
            // than left to a query that returns an error a moment later.
            offline -> item(contentType = "status") {
                StatusBox { Message(stringResource(R.string.search_offline)) }
            }

            state.needsSetup -> item(contentType = "setup") {
                WebApiSetup(webApi, backdrop, onClientIdChange, onConnectWebApi)
            }

            state.loading -> item(contentType = "status") {
                StatusBox { CircularProgressIndicator(color = Ink, strokeWidth = 2.dp) }
            }

            state.error != null -> item(contentType = "status") {
                StatusBox { Message(state.error) }
            }

            state.query.isBlank() && history.isEmpty() -> item(contentType = "status") {
                StatusBox { Message(stringResource(R.string.search_prompt)) }
            }

            state.query.isBlank() -> {
                item(contentType = "history-title") {
                    HistoryTitle(onClear = onClearHistory)
                }
                items(
                    count = history.size,
                    key = { "history-${history[it].uri}" },
                    contentType = { "track" },
                ) { index ->
                    val entry = history[index]
                    val track = entry.track
                    if (track != null) {
                        // The songs among them are the queue, as a playlist
                        // would be: playing the third leaves the two after it to
                        // follow. The rows that are not songs are skipped in
                        // that count rather than played.
                        val songs = remember(history) { history.mapNotNull { it.track } }
                        val at = remember(history, entry.uri) {
                            songs.indexOfFirst { it.uri == entry.uri }.coerceAtLeast(0)
                        }
                        SwipeToQueue(onQueue = { onEnqueue(track) }) {
                            ResultRow(
                                title = track.name,
                                subtitle = track.artist,
                                artworkUrl = track.artworkUrl,
                                highlighted = track.uri == nowPlayingUri,
                                round = false,
                                onClick = { onPlayTrack(songs, at) },
                                onMenu = { onTrackMenu(track) },
                            )
                        }
                    } else {
                        // An artist, a record or a list: the row opens its page,
                        // which is what it did when it was found.
                        ResultRow(
                            title = entry.title,
                            subtitle = entry.subtitle,
                            artworkUrl = entry.artworkUrl,
                            highlighted = false,
                            // Only an artist is drawn round, and an artist is the
                            // one kind whose address says so.
                            round = entry.uri.contains(":artist:"),
                            onClick = {
                                onOpenContext(
                                    SearchItem(
                                        uri = entry.uri,
                                        title = entry.title,
                                        subtitle = entry.subtitle,
                                        artworkUrl = entry.artworkUrl,
                                    ),
                                )
                            },
                            onMenu = null,
                        )
                    }
                }
                // The page on its way, said in the one place the reader is
                // looking: at the bottom, where they ran out of rows.
                if (state.loadingMore) {
                    item(contentType = "more") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 18.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }

                item(contentType = "tail") { Box(Modifier.height(24.dp)) }
            }

            state.results.isEmpty -> item(contentType = "status") {
                StatusBox { Message(stringResource(R.string.no_results_for, state.query)) }
            }

            else -> {
                val all = kind == Kind.ALL
                val tracks = state.results.tracks
                    .let { if (all) it.take(TOP_RESULTS) else it }

                if ((all || kind == Kind.TRACKS) && tracks.isNotEmpty()) {
                    item(contentType = "section") { SectionTitle(stringResource(R.string.tracks)) }
                    items(
                        count = tracks.size,
                        key = { "track-${tracks[it].uri}-$it" },
                        contentType = { "track" },
                    ) { index ->
                        val track = tracks[index]
                        SwipeToQueue(onQueue = { onEnqueue(track) }) {
                            ResultRow(
                                title = track.name,
                                subtitle = track.artist,
                                artworkUrl = track.artworkUrl,
                                highlighted = track.uri == nowPlayingUri,
                                round = false,
                                onClick = { onPlayTrack(tracks, index) },
                                // Only tracks have one: an artist row opens a
                                // page, and there is nothing to queue, add or
                                // share about it that the page does not do
                                // better.
                                onMenu = { onTrackMenu(track) },
                                badge = stringResource(R.string.lyrics_match)
                                    .takeIf { track.uri in state.results.lyricMatches },
                            )
                        }
                    }
                }

                if (all || kind == Kind.ARTISTS) {
                    section(R.string.artists, state.results.artists, all, round = true, onOpenContext)
                }
                if (all || kind == Kind.ALBUMS) {
                    section(R.string.albums, state.results.albums, all, round = false, onOpenContext)
                }
                if (all || kind == Kind.PLAYLISTS) {
                    section(R.string.playlists, state.results.playlists, all, round = false, onOpenContext)
                }

                // The page on its way, said in the one place the reader is
                // looking: at the bottom, where they ran out of rows.
                if (state.loadingMore) {
                    item(contentType = "more") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 18.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }

                item(contentType = "tail") { Box(Modifier.height(24.dp)) }
            }
        }
    }
}

/** The same chips the home page filters with; see the note there. */
@Composable
private fun KindRow(selected: Kind, backdrop: Backdrop, onSelect: (Kind) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    ) {
        items(Kind.entries.toList(), key = { it.name }) { entry ->
            dev.lelonio.square.ui.components.FilterChip(
                label = stringResource(entry.label),
                selected = entry == selected,
                onClick = { onSelect(entry) },
            )
        }
    }
}

private fun LazyListScope.section(
    @StringRes title: Int,
    items: List<SearchItem>,
    /** True on the combined page, where each kind shows only its best few. */
    trimmed: Boolean,
    round: Boolean,
    onOpen: (SearchItem) -> Unit,
) {
    if (items.isEmpty()) return
    val shown = if (trimmed) items.take(TOP_RESULTS) else items
    item(contentType = "section") { SectionTitle(stringResource(title)) }
    items(
        count = shown.size,
        key = { "$title-${shown[it].uri}" },
        contentType = { "result" },
    ) { index ->
        val item = shown[index]
        ResultRow(
            title = item.title,
            subtitle = item.subtitle,
            artworkUrl = item.artworkUrl,
            highlighted = false,
            round = round,
            onClick = { onOpen(item) },
            onMenu = null,
        )
    }
}

/** The history's own heading, with the way to empty it beside the name. */
@Composable
private fun HistoryTitle(onClear: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp, top = 22.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.search_history).uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = InkDim,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(R.string.clear),
            style = MaterialTheme.typography.labelLarge,
            color = InkDim,
            modifier = Modifier
                .pressable(onClear, shape = RoundedCornerShape(12.dp), pressedScale = 0.96f)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = InkDim,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 6.dp),
    )
}

@Composable
private fun ResultRow(
    title: String,
    subtitle: String,
    artworkUrl: String?,
    highlighted: Boolean,
    round: Boolean,
    onClick: () -> Unit,
    onMenu: (() -> Unit)?,
    /**
     * Why this row is here, when the title does not say it.
     *
     * Shown before the artist rather than under it: a row is two lines tall
     * everywhere in the app, and a third line for some rows and not others
     * makes a list that was even into a list that jumps.
     */
    badge: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick, shape = RoundedCornerShape(16.dp), pressedScale = 0.98f)
            .padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            url = artworkUrl,
            title = title,
            // Artists read as circles everywhere; keeping that convention makes
            // the row type obvious without a label.
            modifier = Modifier.size(48.dp),
            corner = if (round) 24.dp else 8.dp,
            decodeSize = 48.dp,
        )
        Column(
            Modifier
                .padding(start = 14.dp)
                .weight(1f),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (highlighted) MaterialTheme.colorScheme.primary else Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (badge != null) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = Ink,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(BadgeFilm)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onMenu != null) {
            Icon(
                PhosphorIcons.Regular.DotsThree,
                contentDescription = stringResource(R.string.more),
                tint = InkDim,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .pressable(onMenu, pressedScale = 0.88f)
                    .padding(10.dp)
                    .size(20.dp),
            )
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = InkDim,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun StatusBox(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The chip that is lit, filled a little harder than the rest; see the home page. */
private val SelectedFilm = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.26f)

/**
 * The pill behind "lyrics match": lighter than a lit chip.
 *
 * It labels a row rather than offering something to press, and at chip
 * strength a whole page of them reads as a page of buttons.
 */
private val BadgeFilm = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.16f)



/**
 * How many of each kind the combined page shows before the chips take over.
 *
 * Four was a taste of each kind and nothing to read: a search for an artist
 * answered with four songs, four albums and four playlists on a screen that
 * holds far more. The filters above are still where a long list of one kind
 * belongs, but the page they sit on should be worth scrolling first.
 */
private const val TOP_RESULTS = 8

/** How many rows from the end the next page is asked for. */
private const val LOAD_AHEAD = 6
