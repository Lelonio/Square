package dev.lelonio.square.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.CaretLeft
import com.adamglin.phosphoricons.regular.CaretRight
import dev.lelonio.square.R
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.ListeningEvent
import dev.lelonio.square.data.ListeningHistory
import dev.lelonio.square.data.MonthlyListening
import dev.lelonio.square.ui.components.Artwork
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.glass.pressable
import dev.lelonio.square.ui.glass.shapes.ContinuousRoundedRectangle
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The month in music, as a page of the library.
 *
 * It takes the library's place rather than sitting over it: it was a Material
 * dialog, then a panel over the dimmed page, and either way a box from
 * somewhere else on top of the library. Here it has the library's own heading,
 * with the same way back the settings pages have, and it leads with the
 * month's song as a picture rather than a row, the way a recap is read: the
 * song first, then how much, then the rest of the ranking.
 */
@Composable
fun MonthlyListeningPage(
    events: List<ListeningEvent>,
    /** Which note goes at the bottom: what Square can and cannot count. */
    fromSpotify: Boolean,
    backdrop: Backdrop,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val current = YearMonth.now()
    var month by remember { mutableStateOf(current) }
    val first = remember(events) {
        events.minOfOrNull { it.spotifyAt ?: it.startedAt }?.let {
            YearMonth.from(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
        }?.coerceAtMost(current) ?: current
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 24.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiquidButton(onClick = onBack, backdrop = backdrop) {
                Icon(PhosphorIcons.Regular.ArrowLeft, contentDescription = stringResource(R.string.back))
            }
            Text(
                stringResource(R.string.recap_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
        Header(
            month = month,
            canGoBack = month > first,
            canGoForward = month < current,
            onBack = { month = month.minusMonths(1) },
            onForward = { month = month.plusMonths(1) },
        )
        AnimatedContent(
            targetState = month,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "recapMonth",
        ) { shown ->
            val summary = remember(events, shown) { ListeningHistory.summarize(events, shown) }
            Column(
                Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Body(summary)
                Text(
                    stringResource(if (fromSpotify) R.string.recap_partial else R.string.recap_partial_youtube),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkDim,
                )
            }
        }
    }
}

@Composable
private fun Header(
    month: YearMonth,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Step(PhosphorIcons.Regular.CaretLeft, stringResource(R.string.recap_previous), canGoBack, onBack)
            Text(
                month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()))
                    .replaceFirstChar { it.titlecase(Locale.getDefault()) },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Ink,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Step(PhosphorIcons.Regular.CaretRight, stringResource(R.string.recap_next), canGoForward, onForward)
        }
    }
}

@Composable
private fun Step(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(ContinuousRoundedRectangle(20.dp))
            .background(if (enabled) FILM else Color.Transparent)
            .pressable(onClick, pressedScale = 0.9f, enabled = enabled),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) Ink else InkDim.copy(alpha = 0.35f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun Body(summary: MonthlyListening) {
    val top = summary.tracks.firstOrNull()?.first
    if (top != null) SongOfTheMonth(top)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Stat(summary.plays.toString(), stringResource(R.string.recap_stat_plays), Modifier.weight(1f))
        Stat(summary.minutes.toString(), stringResource(R.string.recap_stat_minutes), Modifier.weight(1f))
    }

    if (summary.plays == 0) {
        Text(
            stringResource(R.string.recap_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = InkDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )
        return
    }

    Section(stringResource(R.string.recap_tracks))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        summary.tracks.take(RANKED).forEachIndexed { at, (track, count) -> TrackRank(at + 1, track, count) }
    }

    if (summary.artists.isNotEmpty()) {
        Section(stringResource(R.string.recap_artists))
        val most = summary.artists.first().second.coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            summary.artists.take(RANKED).forEachIndexed { at, (artist, count) ->
                ArtistRank(at + 1, artist, count, count.toFloat() / most)
            }
        }
    }
}

/** The month's most played song, as a picture with its name written on it. */
@Composable
private fun SongOfTheMonth(track: CatalogTrack) {
    val shape = ContinuousRoundedRectangle(26.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.25f)
            .clip(shape),
    ) {
        Artwork(track.artworkUrl, track.name, Modifier.fillMaxSize(), corner = 0.dp)
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.78f))),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(18.dp),
        ) {
            Text(
                stringResource(R.string.recap_song_of_month).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.75f),
            )
            Text(
                track.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                track.artist,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(ContinuousRoundedRectangle(22.dp))
            .background(FILM)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Ink,
            maxLines = 1,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = InkDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = Ink,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun Rank(place: Int) {
    Text(
        place.toString(),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = if (place == 1) Ink else InkDim,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(26.dp),
    )
}

@Composable
private fun TrackRank(place: Int, track: CatalogTrack, plays: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Rank(place)
        Artwork(track.artworkUrl, track.name, Modifier.size(46.dp), corner = 10.dp)
        Column(Modifier.weight(1f)) {
            Text(
                track.name,
                style = MaterialTheme.typography.bodyLarge,
                color = Ink,
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
        Text(plays.toString(), style = MaterialTheme.typography.bodyMedium, color = InkDim)
    }
}

/** An artist, with a bar as long as their share of the month's favourite. */
@Composable
private fun ArtistRank(place: Int, artist: String, plays: Int, share: Float) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Rank(place)
        Box(
            Modifier
                .weight(1f)
                .clip(ContinuousRoundedRectangle(14.dp))
                .background(FILM),
        ) {
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier
                        .fillMaxWidth(share.coerceIn(0.08f, 1f))
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    artist,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(plays.toString(), style = MaterialTheme.typography.bodyMedium, color = InkDim)
            }
        }
    }
}

private const val RANKED = 5

/** The ground of the tiles: a shade of the ink, so it reads in both themes. */
private val FILM: Color @Composable get() = Ink.copy(alpha = 0.08f)
