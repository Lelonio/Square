package dev.lelonio.square.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.bold.DotsThree
import com.adamglin.phosphoricons.fill.CheckCircle
import com.adamglin.phosphoricons.fill.Pause
import com.adamglin.phosphoricons.fill.Play
import dev.lelonio.square.R
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.ui.components.Artwork
import dev.lelonio.square.ui.glass.pressable

/**
 * One episode, as a card rather than a song's row.
 *
 * An episode is chosen differently from a song: by what it is about, by when
 * it came out, and by whether it was already heard. So the card carries what a
 * song's row has no room for — a couple of lines of what the show says about
 * it, how far in the listener got, and a mark on the ones heard to the end —
 * and the play button is a target of its own, the way Spotify lays these out.
 */
@Composable
internal fun EpisodeRow(
    episode: CatalogTrack,
    isCurrent: Boolean,
    /** Whether the one that is current is playing right now. */
    playing: Boolean,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    /** Off on a show's own page, where every card is the same show. */
    showName: Boolean = false,
    unavailable: Boolean = false,
) {
    val shape = RoundedCornerShape(16.dp)
    val ink = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(shape)
            .pressable(onClick, shape = shape, pressedScale = 0.985f, onLongClick = onMenu)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .alpha(if (unavailable) 0.38f else if (episode.played && !isCurrent) 0.72f else 1f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(
                url = episode.artworkUrl,
                title = episode.name,
                modifier = Modifier.size(60.dp),
                corner = 10.dp,
                decodeSize = 60.dp,
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 14.dp),
            ) {
                Text(
                    episode.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                    color = ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showName && episode.artist.isNotBlank()) {
                    Text(
                        episode.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        if (episode.summary.isNotBlank()) {
            Text(
                episode.summary,
                style = MaterialTheme.typography.bodySmall,
                color = dim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (episode.played) {
                Icon(
                    PhosphorIcons.Fill.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                episodeLine(episode, showName = false),
                style = MaterialTheme.typography.labelMedium,
                color = dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val started = !episode.played && episode.resumeMs > 0 && episode.durationMs > 0
            if (started) {
                Box(
                    Modifier
                        .width(56.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(dim.copy(alpha = 0.25f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth((episode.resumeMs.toFloat() / episode.durationMs).coerceIn(0.03f, 1f))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .pressable(onMenu, shape = CircleShape, pressedScale = 0.9f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    PhosphorIcons.Bold.DotsThree,
                    contentDescription = stringResource(R.string.more),
                    tint = dim,
                    modifier = Modifier.size(18.dp),
                )
            }
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(ink)
                    .pressable(onClick, shape = CircleShape, pressedScale = 0.9f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isCurrent && playing) PhosphorIcons.Fill.Pause else PhosphorIcons.Fill.Play,
                    contentDescription = stringResource(R.string.play),
                    // Whatever reads on the ink it sits on, the page's own
                    // colour being anything the cover made it.
                    tint = if (ink.luminance() > 0.5f) Color.Black else Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
