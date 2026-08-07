package dev.lelonio.square.ui.search

import androidx.annotation.StringRes
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import dev.lelonio.square.R
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.SearchItem
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.components.Artwork
import dev.lelonio.square.ui.components.SwipeToQueue
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.player.GlassFilm
import dev.lelonio.square.ui.settings.WebApiSetup
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.MagnifyingGlass
import com.adamglin.phosphoricons.regular.DotsThree
import com.adamglin.phosphoricons.regular.X

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
    onQueryChange: (String) -> Unit,
    onClientIdChange: (String) -> Unit,
    onConnectWebApi: () -> Unit,
    onPlayTrack: (List<CatalogTrack>, Int) -> Unit,
    onEnqueue: (CatalogTrack) -> Unit,
    /** Opens the same track sheet the library rows open. */
    onTrackMenu: (CatalogTrack) -> Unit,
    onOpenContext: (SearchItem) -> Unit,
    backdrop: Backdrop,
) {
    var kind by remember { mutableStateOf(Kind.ALL) }
    // A new search answers a new question, so the page goes back to showing all
    // of the answer rather than staying filtered to what the last one was about.
    val query = state.query
    remember(query) { kind = Kind.ALL }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(contentType = "field") {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 36.dp)) {
                Text(stringResource(R.string.search), style = MaterialTheme.typography.displayLarge)
                SearchField(
                    query = state.query,
                    enabled = !state.needsSetup,
                    backdrop = backdrop,
                    onQueryChange = onQueryChange,
                )
            }
        }

        if (!state.results.isEmpty && !state.loading) {
            item(contentType = "chips") {
                KindRow(kind, backdrop) { kind = it }
            }
        }

        when {
            state.needsSetup -> item(contentType = "setup") {
                WebApiSetup(webApi, backdrop, onClientIdChange, onConnectWebApi)
            }

            state.loading -> item(contentType = "status") {
                StatusBox { CircularProgressIndicator(color = Ink, strokeWidth = 2.dp) }
            }

            state.error != null -> item(contentType = "status") {
                StatusBox { Message(state.error) }
            }

            state.query.isBlank() -> item(contentType = "status") {
                StatusBox { Message(stringResource(R.string.search_prompt)) }
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

                item(contentType = "tail") { Box(Modifier.height(24.dp)) }
            }
        }
    }
}

/**
 * The query box, in the app's own glass rather than Material's outline.
 *
 * Built from [BasicTextField] because an `OutlinedTextField` carries a label, a
 * container colour and a border that belong to a design this app does not use —
 * every other control on the page is a pane of glass over the artwork.
 */
@Composable
private fun SearchField(
    query: String,
    enabled: Boolean,
    backdrop: Backdrop,
    onQueryChange: (String) -> Unit,
) {
    LiquidButton(
        onClick = {},
        backdrop = backdrop,
        surfaceColor = GlassFilm,
        contentHeight = 52.dp,
        contentPadding = 18.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    ) {
        Icon(
            PhosphorIcons.Fill.MagnifyingGlass,
            contentDescription = null,
            tint = InkDim,
            modifier = Modifier.size(18.dp),
        )
        Box(
            Modifier
                .padding(start = 12.dp)
                .weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (query.isEmpty()) {
                Text(
                    stringResource(R.string.search_placeholder),
                    style = MaterialTheme.typography.bodyLarge,
                    color = InkDim,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
                cursorBrush = SolidColor(Ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                PhosphorIcons.Regular.X,
                contentDescription = stringResource(R.string.clear),
                tint = InkDim,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onQueryChange("") }
                    .size(20.dp),
            )
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
            val isSelected = entry == selected
            LiquidButton(
                onClick = { onSelect(entry) },
                backdrop = backdrop,
                contentHeight = 38.dp,
                contentPadding = 18.dp,
                blurRadius = 8.dp,
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
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = InkDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onMenu != null) {
            Icon(
                PhosphorIcons.Regular.DotsThree,
                contentDescription = stringResource(R.string.more),
                tint = InkDim,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onMenu)
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

/** How many of each kind the combined page shows before the chips take over. */
private const val TOP_RESULTS = 4
