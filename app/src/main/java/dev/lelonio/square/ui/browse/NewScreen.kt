package dev.lelonio.square.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.bold.CaretRight
import com.adamglin.phosphoricons.bold.DotsThree
import dev.lelonio.square.R
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.SearchItem
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.components.Artwork
import dev.lelonio.square.ui.glass.pressable
import dev.lelonio.square.ui.theme.InkDim

/**
 * What has just come out, as a page rather than as one shelf on the home page.
 *
 * Laid out the way the reference lays this page out: a few records at the top
 * drawn large enough to be an editorial choice rather than a result, then the
 * songs out of them, then the week, then everything else. The order is how
 * specific each part is — the picks are for this listener, the week is for
 * everyone, and by the bottom it is a catalogue.
 *
 * The reference fills the rest of its page with charts, city charts and radio
 * shows, none of which Spotify hands out to an application like this one. What
 * is here is what can be answered honestly.
 */
@Composable
fun NewScreen(
    page: MainViewModel.NewPage,
    followed: MainViewModel.FollowedReleaseState,
    onRefreshFollowed: () -> Unit,
    /**
     * Spotify's own rows, as it lays them out for this account.
     *
     * The rest of this page is built out of the catalogue-wide list of what
     * came out, which is the same for everyone and moves once a week. These are
     * assembled per listener and change daily, and they are the reason the tab
     * has something to say on a Tuesday.
     */
    shelves: List<dev.lelonio.square.data.HomeShelf>,
    /** Whether those rows are still coming; see SkeletonRow. */
    shelvesLoading: Boolean,
    contentPadding: PaddingValues,
    onOpen: (SearchItem) -> Unit,
    onPlaySong: (List<CatalogTrack>, Int) -> Unit,
    /** The same menu every other list of songs in the app opens. */
    onSongMenu: (CatalogTrack) -> Unit,
) {
    // Not held for the followed artists: reading all of them takes seconds,
    // and the whole page stood behind a spinner for the one row that needs it.
    // That row has an outline of its own while it comes.
    if (page.loading && page.hero.isEmpty() && page.songs.isEmpty() && shelves.isEmpty() && followed.albums.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    // The big cards are the newest records by artists this listener follows.
    // The catalogue's picks only fill the places those leave empty: an account
    // that follows few artists, or the first visit, before they have been read.
    // The ones up there leave the row below, so no cover is on screen twice.
    val followedItems = remember(followed.albums) {
        followed.albums.map { album ->
            SearchItem(
                uri = album.uri ?: "spotify:album:${album.id}",
                title = album.name,
                subtitle = album.artists.joinToString(", ") { it.name },
                artworkUrl = album.images.firstOrNull()?.url,
            )
        }
    }
    val heroFollowed = remember(followedItems) { followedItems.take(HERO_CARDS) }
    val heroItems = remember(heroFollowed, page.hero) {
        val taken = heroFollowed.mapTo(mutableSetOf()) { it.uri }
        heroFollowed + page.hero.filter { it.uri !in taken }.take(HERO_CARDS - heroFollowed.size)
    }
    val heroFollowedUris = remember(heroFollowed) { heroFollowed.mapTo(mutableSetOf()) { it.uri } }
    val shelfFollowed = remember(followedItems, heroFollowedUris) {
        followedItems.filter { it.uri !in heroFollowedUris }
    }

    LazyColumn(
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding(),
        ),
    ) {
        item(contentType = "title") {
            Text(
                stringResource(R.string.tab_new),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 14.dp),
            )
        }

        if (heroItems.isNotEmpty()) {
            item(contentType = "hero") { HeroPager(heroItems, heroFollowedUris, onOpen) }
        }

        if (page.songs.isNotEmpty()) {
            item(contentType = "songsHeading") {
                Heading(stringResource(R.string.new_songs), more = true)
            }
            item(contentType = "songs") { SongPages(page.songs, onSongMenu, onPlaySong) }
        }

        if (followed.albums.isNotEmpty() || followed.error != null || followed.loaded || followed.loading) {
            item(key = "followedHeading", contentType = "shelfHeading") {
                Heading(stringResource(R.string.new_from_followed_artists))
            }
            if (shelfFollowed.isNotEmpty()) {
                item(key = "followedShelf", contentType = "shelfRow") {
                    Shelf(shelfFollowed, onOpen)
                }
            } else if (followed.loading && followed.albums.isEmpty()) {
                item(key = "followedSkeleton", contentType = "skeleton") { SkeletonRow() }
            }
            if (followed.error != null || followed.failedArtists > 0 || (followed.loaded && followed.albums.isEmpty())) {
                item(key = "followedStatus", contentType = "status") {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Text(
                            followed.error ?: stringResource(
                                when {
                                    followed.failedArtists > 0 -> R.string.followed_releases_partial
                                    followed.artistCount == 0 -> R.string.followed_releases_no_artists
                                    else -> R.string.followed_releases_empty
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = InkDim,
                        )
                        if (followed.error != null || followed.failedArtists > 0) {
                            Text(
                                stringResource(R.string.retry),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.pressable(onRefreshFollowed).padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }

        // The outline of what is coming, while it is coming.
        if (shelvesLoading && shelves.isEmpty()) {
            items(
                count = SKELETON_ROWS,
                key = { "skeleton $it" },
                contentType = { "skeleton" },
            ) {
                SkeletonRow()
            }
        }

        // Spotify's own rows, under its own headings: they are titled for this
        // listener and renaming them would be this app pretending to have
        // assembled them.
        shelves.forEach { shelf ->
            item(key = "shelf ${shelf.title}", contentType = "shelfHeading") {
                Heading(shelf.title, more = true)
            }
            item(key = "shelfRow ${shelf.title}", contentType = "shelfRow") {
                Shelf(
                    shelf.items.map { entry ->
                        SearchItem(
                            uri = entry.uri,
                            title = entry.name,
                            subtitle = "",
                            artworkUrl = entry.artworkUrl,
                        )
                    },
                    onOpen,
                )
            }
        }

        if (page.thisWeek.isNotEmpty()) {
            item(contentType = "weekHeading") {
                Heading(stringResource(R.string.new_this_week), more = true)
            }
            item(contentType = "week") { Shelf(page.thisWeek, onOpen) }
        }

        // The catalogue-wide list of what came out, and only when Spotify's
        // own picked one is not here.
        //
        // The two are the same shelf twice: theirs is chosen for this listener
        // and ours is what the market got, and on a page that shows both, the
        // reader sees "new releases" and "new releases for you" one under the
        // other with half the same covers. Ours is the fallback for an account
        // the gateway will not answer for.
        if (page.recent.isNotEmpty() && shelves.isEmpty()) {
            item(contentType = "recentHeading") {
                Heading(stringResource(R.string.new_releases_title), more = true)
            }
            item(contentType = "recent") { Shelf(page.recent, onOpen) }
        }
    }
}

/** How many outlines stand in for the rows that are coming. */
private const val SKELETON_ROWS = 3

/** How many big cards the page leads with. */
private const val HERO_CARDS = 9

/**
 * The records the page is leading with, one screen at a time.
 *
 * Laid out the way the reference lays this out, which is not a shelf: the
 * writing sits *above* the picture — a small line saying what kind of thing it
 * is, then its name, then who it is by — and the picture is a wide card almost
 * the width of the screen with the next one showing at the edge. A square in a
 * row of squares reads as a result however large it is drawn; this reads as
 * somebody having chosen it.
 */
@Composable
private fun HeroPager(
    items: List<SearchItem>,
    /** The cards that are there because the listener follows the artist. */
    followed: Set<String>,
    onOpen: (SearchItem) -> Unit,
) {
    BoxWithConstraints {
        // Almost the whole width, with the next card showing at the edge: the
        // peek is what says the row can be moved.
        val cardWidth = maxWidth - 68.dp
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(items, key = { it.uri }) { item ->
                HeroCard(item, cardWidth, fromFollowed = item.uri in followed) { onOpen(item) }
            }
        }
    }
}

@Composable
private fun HeroCard(item: SearchItem, width: Dp, fromFollowed: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.width(width)) {
        Text(
            stringResource(if (fromFollowed) R.string.new_release_followed_tag else R.string.new_release_tag).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = InkDim,
            maxLines = 1,
        )
        Text(
            item.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (item.subtitle.isNotBlank()) {
            Text(
                item.subtitle,
                style = MaterialTheme.typography.titleMedium,
                color = InkDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Artwork(
            url = item.artworkUrl,
            title = item.title,
            modifier = Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                // Wide rather than square, which is the shape an editorial card
                // is drawn in and the shape that leaves room for the next one.
                .aspectRatio(16f / 10f)
                .clip(shape)
                .pressable(onClick, shape = shape, pressedScale = 0.98f),
            corner = 0.dp,
        )
    }
}

/**
 * The new songs, four to a screen, moved sideways rather than down.
 *
 * The reference does not put a list of songs on this page — it puts a block of
 * four and lets the block move, so the songs take one screenful however many
 * there are. Down the page they would push everything under them off it.
 */
@Composable
private fun SongPages(
    songs: List<CatalogTrack>,
    onMenu: (CatalogTrack) -> Unit,
    onPlay: (List<CatalogTrack>, Int) -> Unit,
) {
    val pages = remember(songs) { songs.chunked(SONGS_PER_PAGE) }
    BoxWithConstraints {
        val pageWidth = maxWidth - 56.dp
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(
                items = pages,
                key = { index, _ -> "songPage $index" },
            ) { pageIndex, page ->
                Column(Modifier.width(pageWidth)) {
                    page.forEachIndexed { rowIndex, track ->
                        val at = pageIndex * SONGS_PER_PAGE + rowIndex
                        SongRow(track, onMenu = { onMenu(track) }) { onPlay(songs, at) }
                        // A hairline between the rows and none under the last,
                        // which is what makes four rows read as one block.
                        if (rowIndex < page.lastIndex) {
                            Box(
                                Modifier
                                    .padding(start = 60.dp)
                                    .fillMaxWidth()
                                    .height(0.6.dp)
                                    .background(InkDim.copy(alpha = 0.16f)),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** How many songs stand in one screenful; the reference shows four. */
private const val SONGS_PER_PAGE = 4

/** A row of records, scrolled sideways. */
@Composable
private fun Shelf(items: List<SearchItem>, onOpen: (SearchItem) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(items, key = { it.uri }) { item ->
            Column(
                Modifier
                    .width(146.dp)
                    .pressable({ onOpen(item) }, pressedScale = 0.97f),
            ) {
                Artwork(
                    url = item.artworkUrl,
                    title = item.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    corner = 10.dp,
                    decodeSize = 146.dp,
                )
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (item.subtitle.isNotBlank()) {
                    Text(
                        item.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = InkDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** One new song, in the same shape the track lists everywhere else use. */
@Composable
private fun SongRow(track: CatalogTrack, onMenu: () -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    // No margin of its own: the block it sits in is already inset to the
    // page's own edge, and a second inset had the songs starting further in
    // than every other row on the screen.
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .pressable(onClick, shape = shape, pressedScale = 0.985f, onLongClick = onMenu)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            url = track.artworkUrl,
            title = track.name,
            modifier = Modifier.size(46.dp),
            corner = 8.dp,
            decodeSize = 46.dp,
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 14.dp),
        ) {
            Text(
                track.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = InkDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // What every other list of songs in the app carries, and what the
        // reference puts on each of these rows: the way to the song itself
        // rather than to playing it.
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .pressable(onMenu, shape = CircleShape, pressedScale = 0.9f),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                PhosphorIcons.Bold.DotsThree,
                contentDescription = stringResource(R.string.more),
                tint = InkDim,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * A section's name, with the mark that says there is more of it.
 *
 * The mark is not a control yet — the rows it would open are the same rows the
 * shelf is already showing — but the reference sets its headings this way and
 * without it they read as labels rather than as places.
 */
@Composable
private fun Heading(text: String, more: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (more) {
            Icon(
                PhosphorIcons.Bold.CaretRight,
                contentDescription = null,
                tint = InkDim,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(15.dp),
            )
        }
    }
}
