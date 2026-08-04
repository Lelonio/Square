package dev.emanuele.spot.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.mutableFloatStateOf
import dev.emanuele.spot.ui.player.GlassSurface
import dev.emanuele.spot.ui.components.MENU_WIDTH
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.layout.BoxScope
import dev.emanuele.spot.data.CatalogTrack
import dev.emanuele.spot.ui.MainViewModel
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.emanuele.spot.ui.components.Artwork
import dev.emanuele.spot.ui.components.GlassMenu
import dev.emanuele.spot.ui.components.GlassMenuItem
import dev.emanuele.spot.ui.components.LazyScrollBar
import dev.emanuele.spot.ui.components.SwipeToQueue
import dev.emanuele.spot.ui.glass.LiquidButton
import dev.emanuele.spot.ui.theme.rememberArtworkColor
import dev.emanuele.spot.ui.theme.softShadow
import java.util.Locale
import java.util.concurrent.TimeUnit
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.MagnifyingGlass
import com.adamglin.phosphoricons.fill.Play
import com.adamglin.phosphoricons.fill.Waveform
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.ArrowsDownUp
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.DotsThree
import com.adamglin.phosphoricons.regular.LinkSimple
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.Queue
import com.adamglin.phosphoricons.regular.Shuffle
import com.adamglin.phosphoricons.regular.Trash
import com.adamglin.phosphoricons.regular.X

/** How the track list is ordered. */
enum class TrackSort(val label: String) {
    ORIGINAL("Ordine della playlist"),
    TITLE("Titolo"),
    ARTIST("Artista"),
    ADDED("Aggiunti di recente"),
    DURATION("Durata"),
}

@Composable
fun PlaylistScreen(
    state: MainViewModel.PlaylistState,
    contentPadding: PaddingValues,
    nowPlayingUri: String?,
    onBack: () -> Unit,
    onPlay: (List<CatalogTrack>, Int) -> Unit,
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
    onTrackMenu: (CatalogTrack, IntOffset) -> Unit,
    onOpenItem: (dev.emanuele.spot.data.SearchItem) -> Unit = {},
    /** The remembered track order, and where a change to it is stored. */
    storedSort: String? = null,
    onSortChange: (String) -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    // Seeded from the stored choice and written back on change: picking an
    // order and finding it gone on the next playlist is the kind of thing that
    // makes a setting feel like it did not take.
    var sort by remember(storedSort) {
        mutableStateOf(
            TrackSort.entries.firstOrNull { it.name == storedSort } ?: TrackSort.ORIGINAL,
        )
    }
    var sortOpen by remember { mutableStateOf(false) }

    // Derived, not stored: keeping a second list in state would leave the two
    // able to disagree after a reload.
    val visible = remember(state.tracks, query, sort) {
        state.tracks
            .filter { it.matches(query) }
            .sortedWith(sort.comparator())
    }

    // Taken from *this* screen's cover, not from whatever is playing. The two
    // are usually different things, and colouring an album page after an
    // unrelated track makes the page look like it belongs to something else.
    val accent by rememberArtworkColor(state.artworkUrl)
    val pageColor = remember(accent) { pageColorFor(accent) }

    // What the glass on this screen refracts.
    //
    // Not the app's backdrop: that is the blurred cover of whatever is playing,
    // so the buttons here — back, play, shuffle, search — picked up the colour
    // of an unrelated track while the page around them was tinted from this
    // cover.
    val pageBackdrop = rememberLayerBackdrop()

    var sortAnchor by remember { mutableStateOf(IntOffset.Zero) }

    val density = LocalDensity.current
    val listState = rememberLazyListState()

    val topPadding = contentPadding.calculateTopPadding()
    val collapsedHeight = topPadding + COLLAPSED_BAR_HEIGHT

    // The header is not in the list. It sits above it and shrinks as the list is
    // dragged upwards, which is the difference between a hero that collapses and
    // a hero that scrolls away with a bar appearing over it.
    //
    // Nested scroll is what makes the two feel like one surface: the drag
    // reaches the header first and only what the header cannot use scrolls the
    // list, so a single gesture closes the cover and then carries on into the
    // tracks.
    val collapseRange = with(density) { (HERO_HEIGHT - collapsedHeight).toPx() }
    var collapsed by remember { mutableFloatStateOf(0f) }
    val collapseFraction = { (collapsed / collapseRange).coerceIn(0f, 1f) }

    val headerScroll = remember(collapseRange) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f) return Offset.Zero
                val take = (-available.y).coerceAtMost(collapseRange - collapsed)
                collapsed += take
                return Offset(0f, -take)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y <= 0f) return Offset.Zero
                val give = available.y.coerceAtMost(collapsed)
                collapsed -= give
                return Offset(0f, give)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // The page colour, and only the page colour.
        //
        // The layer has to hold nothing that samples it. Recording the whole
        // screen — content included — put the glass buttons inside the layer
        // those same buttons draw from, and the render tree recursed until it
        // overflowed the stack. Every one of them lives above this box.
        Box(
            Modifier
                .fillMaxSize()
                // Opaque, so the app-wide blurred artwork of the playing track
                // does not show through and re-tint the page.
                .background(
                    Brush.verticalGradient(
                        // Held flat over the top half rather than falling away
                        // immediately: the header has to end on the same colour
                        // the page starts with, and the list scrolls, so the
                        // meeting point moves. A slow gradient makes that seam
                        // impossible to catch.
                        0f to pageColor,
                        0.45f to pageColor,
                        1f to PageFloor,
                    ),
                )
                .layerBackdrop(pageBackdrop),
        )

        Column(Modifier.fillMaxSize()) {
        DetailHeader(
            name = state.name,
            artworkUrl = state.artworkUrl,
            kind = state.kind,
            trackCount = state.tracks.size,
            totalMs = state.tracks.sumOf { it.durationMs },
            pageColor = pageColor,
            backdrop = pageBackdrop,
            collapse = collapseFraction,
            collapsedHeight = collapsedHeight,
            topPadding = topPadding,
            onBack = onBack,
            onPlay = { visible.takeIf { it.isNotEmpty() }?.let { onPlay(it, 0) } },
            onShuffle = { visible.takeIf { it.isNotEmpty() }?.let(onShuffle) },
            onToggleSearch = {
                searching = !searching
                if (!searching) query = ""
            },
            searching = searching,
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.nestedScroll(headerScroll),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
        ) {
            if (state.albums.isNotEmpty()) {
                item(contentType = "albums") {
                    AlbumStrip(albums = state.albums, onOpen = onOpenItem)
                }
            }

            if (state.tracks.isNotEmpty()) {
                item(contentType = "sectionTitle") {
                    SectionHeader(
                        title = if (state.kind == MainViewModel.DetailKind.ARTIST) {
                            "Brani principali"
                        } else {
                            "Brani"
                        },
                        sort = sort,
                        onSortOpen = { sortOpen = it },
                        onAnchor = { sortAnchor = it },
                    )
                }

                item(contentType = "search") {
                    AnimatedVisibility(visible = searching) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Cerca fra i brani") },
                            singleLine = true,
                            leadingIcon = { Icon(PhosphorIcons.Fill.MagnifyingGlass, contentDescription = null) },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(PhosphorIcons.Regular.X, contentDescription = "Cancella")
                                    }
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            when {
                state.loading -> item(contentType = "status") {
                    StatusBox { CircularProgressIndicator(strokeWidth = 2.dp) }
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

                visible.isEmpty() -> item(contentType = "status") {
                    StatusBox {
                        Text(
                            if (query.isBlank()) "Nessun brano" else "Nessun risultato per “$query”",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> itemsIndexed(
                    items = visible,
                    // Index in the key as well as the URI: a playlist may hold
                    // the same track twice, and duplicate keys crash the list.
                    key = { index, track -> "$index ${track.uri}" },
                    contentType = { _, _ -> "track" },
                ) { index, track ->
                    SwipeToQueue(onQueue = { onEnqueue(track) }) {
                        TrackRow(
                            track = track,
                            position = index + 1,
                            isCurrent = track.uri == nowPlayingUri,
                            onClick = { onPlay(visible, index) },
                            onMenu = { anchor -> onTrackMenu(track, anchor) },
                        )
                    }
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
                            "Altri brani in arrivo",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
            }
        }
        }

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

        // Floating rather than a top bar: the list scrolls under it, so the
        // picture stays uninterrupted. It gives way to the collapsed bar, which
        // carries a back button of its own.
        LiquidButton(
            onClick = onBack,
            backdrop = pageBackdrop,
            modifier = Modifier
                .padding(top = contentPadding.calculateTopPadding() + 8.dp, start = 14.dp)
                .align(Alignment.TopStart)
                .size(42.dp)
                .graphicsLayer { alpha = 1f - collapseFraction() },
            contentHeight = 42.dp,
            contentPadding = 0.dp,
            blurRadius = 8.dp,
        ) {
            Icon(
                PhosphorIcons.Regular.ArrowLeft,
                contentDescription = "Indietro",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }

        GlassMenu(
            visible = sortOpen,
            anchor = sortAnchor.leftOf(density),
            onDismiss = { sortOpen = false },
        ) {
            TrackSort.entries.forEach { option ->
                GlassMenuItem(
                    option.label,
                    if (option == sort) {
                        PhosphorIcons.Regular.Check
                    } else {
                        PhosphorIcons.Regular.ArrowsDownUp
                    },
                ) {
                    sort = option
                    onSortChange(option.name)
                    sortOpen = false
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
    val width = with(density) { MENU_WIDTH.roundToPx() }
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
    name: String,
    artworkUrl: String?,
    kind: MainViewModel.DetailKind,
    trackCount: Int,
    totalMs: Long,
    pageColor: Color,
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
) {
    val density = LocalDensity.current
    val heroPx = with(density) { HERO_HEIGHT.roundToPx() }
    val collapsedPx = with(density) { collapsedHeight.roundToPx() }

    Box(
        Modifier
            .fillMaxWidth()
            // The header genuinely shrinks. Everything inside keeps its full
            // size and is clipped by it, which is what makes the cover look like
            // it is being rolled up rather than moved off.
            .layout { measurable, constraints ->
                val height = androidx.compose.ui.util.lerp(heroPx, collapsedPx, collapse())
                val placeable = measurable.measure(
                    constraints.copy(minHeight = heroPx, maxHeight = heroPx),
                )
                layout(constraints.maxWidth, height) {
                    // Anchored to the bottom of the picture, so what stays
                    // visible as it closes is the part the title sits on.
                    placeable.place(0, height - heroPx)
                }
            }
            .clipToBounds(),
    ) {
        Artwork(
            url = artworkUrl,
            title = name,
            modifier = Modifier.fillMaxSize(),
            corner = 0.dp,
        )

        // The bottom stops fade to the page colour itself, not to black. That is
        // what makes the cover run into the page instead of ending on an edge:
        // the last row of the image and the first row of the background are the
        // same colour. Fading to black over a page that is not black just moves
        // the seam and adds a dark band.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.35f),
                        0.28f to Color.Transparent,
                        0.58f to pageColor.copy(alpha = 0.55f),
                        0.82f to pageColor.copy(alpha = 0.94f),
                        1f to pageColor,
                    ),
                ),
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 18.dp)
                // Gone by the time the header is half closed: below that there
                // is no room for a 68dp play button and a two-line title, and
                // watching them be squeezed is worse than watching them leave.
                .graphicsLayer { alpha = (1f - collapse() * 2f).coerceIn(0f, 1f) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = buildString {
                    append(kind.label)
                    if (trackCount > 0) append(" · $trackCount brani · ${formatTotal(totalMs)}")
                },
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.72f),
            )

            Text(
                text = name,
                style = MaterialTheme.typography.displayLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )

            Row(
                Modifier.padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleAction(
                    icon = PhosphorIcons.Regular.Shuffle,
                    description = "Casuale",
                    size = 46.dp,
                    backdrop = backdrop,
                    onClick = onShuffle,
                )
                // The one solid control on the screen. Everything else here is
                // glass, so weight has to come from the fill rather than from
                // size alone.
                Box(
                    Modifier
                        .size(68.dp)
                        .softShadow(CircleShape, elevation = 18.dp, spot = 0.3f)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(onClick = onPlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        PhosphorIcons.Fill.Play,
                        contentDescription = "Riproduci",
                        tint = Color.Black,
                        modifier = Modifier.size(34.dp),
                    )
                }
                CircleAction(
                    icon = if (searching) PhosphorIcons.Regular.X else PhosphorIcons.Fill.MagnifyingGlass,
                    description = "Cerca fra i brani",
                    size = 46.dp,
                    backdrop = backdrop,
                    onClick = onToggleSearch,
                )
            }
        }

        // What the header becomes: the same title and the same three controls,
        // at the size a bar can carry them. It arrives in the second half of the
        // travel, as the full-size version finishes leaving.
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(collapsedHeight)
                .padding(top = topPadding)
                .padding(horizontal = 6.dp)
                .graphicsLayer { alpha = (collapse() * 2f - 1f).coerceIn(0f, 1f) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    PhosphorIcons.Regular.ArrowLeft,
                    contentDescription = "Indietro",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
            )
            IconButton(onClick = onToggleSearch) {
                Icon(
                    if (searching) PhosphorIcons.Regular.X else PhosphorIcons.Fill.MagnifyingGlass,
                    contentDescription = "Cerca fra i brani",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onShuffle) {
                Icon(
                    PhosphorIcons.Regular.Shuffle,
                    contentDescription = "Casuale",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onPlay) {
                Icon(
                    PhosphorIcons.Fill.Play,
                    contentDescription = "Riproduci",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
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
) {
    LiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = Modifier.size(size),
        contentHeight = size,
        contentPadding = 0.dp,
        blurRadius = 8.dp,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.size(size * 0.44f),
        )
    }
}

@Composable
private fun AlbumStrip(
    albums: List<dev.emanuele.spot.data.SearchItem>,
    onOpen: (dev.emanuele.spot.data.SearchItem) -> Unit,
) {
    Column(Modifier.padding(top = 18.dp)) {
        Text(
            "Album",
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
                        .clickable { onOpen(album) },
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
    onSortOpen: (Boolean) -> Unit,
    /** Where the sort button is, for the menu drawn above the list. */
    onAnchor: (IntOffset) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 14.dp, top = 22.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))

        IconButton(
            onClick = { onSortOpen(true) },
            modifier = Modifier.onGloballyPositioned {
                val root = it.boundsInRoot()
                onAnchor(IntOffset(root.left.toInt(), root.bottom.toInt()))
            },
        ) {
            run {
                Icon(
                    PhosphorIcons.Regular.ArrowsDownUp,
                    contentDescription = "Ordina",
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

/** What the hero shrinks to: a bar, under the status bar's own inset. */
private val COLLAPSED_BAR_HEIGHT = 56.dp

/** Tall enough to be the top of the screen rather than a banner above a list. */
private val HERO_HEIGHT = 420.dp

/** Where the page ends up once the cover's colour has faded out of it. */
private val PageFloor = Color(0xFF0A0A0C)

/**
 * The page tone for a cover.
 *
 * The dominant colour arrives at full saturation — it has to, it is picked to
 * identify the artwork — and using it as a background would put text on a
 * fluorescent field. Most of the way to black keeps the hue recognisable and
 * nothing else. A null cover falls back to the floor rather than to grey.
 */
private fun pageColorFor(accent: Color?): Color =
    accent?.let { lerp(it, PageFloor, 0.78f) } ?: PageFloor

@Composable
private fun TrackRow(
    track: CatalogTrack,
    position: Int,
    isCurrent: Boolean,
    onClick: () -> Unit,
    /** Where the menu button is on screen, for the menu drawn above the list. */
    onMenu: (IntOffset) -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
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
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The number is replaced by a level meter on the playing row, so the
        // state survives without relying on the accent being distinguishable.
        Box(Modifier.width(30.dp), contentAlignment = Alignment.CenterStart) {
            if (isCurrent) {
                Icon(
                    PhosphorIcons.Fill.Waveform,
                    contentDescription = "In riproduzione",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Text(
                    text = "%02d".format(position),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = track.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // Artist and length on one line, as the reference has it: two
                // stacked grey lines under every title turn the list into a wall
                // of secondary text.
                text = "${track.artist} · ${formatDuration(track.durationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        var buttonPosition by remember { mutableStateOf(IntOffset.Zero) }
        IconButton(
            onClick = { onMenu(buttonPosition) },
            modifier = Modifier
                .size(32.dp)
                .onGloballyPositioned {
                    val root = it.boundsInRoot()
                    buttonPosition = IntOffset(root.left.toInt(), root.bottom.toInt())
                },
        ) {
            Icon(
                PhosphorIcons.Regular.DotsThree,
                contentDescription = "Altro",
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
/** The web address for a track URI, which is what a share expects. */
fun CatalogTrack.openLink(): String =
    "https://open.spotify.com/track/" + uri.substringAfterLast(':')

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
private fun formatTotal(ms: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    return if (hours > 0) "${hours}h ${minutes}min" else "${minutes}min"
}
