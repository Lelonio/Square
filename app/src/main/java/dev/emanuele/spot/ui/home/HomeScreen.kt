package dev.emanuele.spot.ui.home

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.emanuele.spot.data.CatalogPlaylist
import dev.emanuele.spot.data.CatalogTrack
import dev.emanuele.spot.ui.MainViewModel
import dev.emanuele.spot.ui.components.Artwork
import dev.emanuele.spot.ui.player.PlaybackState
import dev.emanuele.spot.ui.theme.softShadow
import java.util.Calendar

@Composable
fun HomeScreen(
    state: MainViewModel.UiState,
    playback: PlaybackState,
    contentPadding: PaddingValues,
    onLogIn: () -> Unit,
    onRetry: () -> Unit,
    onLogOut: () -> Unit,
    onOpenPlaylist: (CatalogPlaylist) -> Unit,
    onOpenPlayer: () -> Unit,
    recent: List<CatalogTrack>,
    onPlayRecent: (List<CatalogTrack>, Int) -> Unit,
) {
    when (state) {
        MainViewModel.UiState.LoggedOut -> Centered {
            Text("Spot", style = MaterialTheme.typography.displayLarge)
            Text(
                "Client non ufficiale per account Premium",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onLogIn, modifier = Modifier.padding(top = 14.dp)) {
                Text("Accedi con Spotify")
            }
        }

        MainViewModel.UiState.Connecting,
        MainViewModel.UiState.Loading,
        -> Centered {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
            )
        }

        is MainViewModel.UiState.Failed -> Centered {
            Text(
                state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
            Button(onClick = onRetry) { Text("Riprova") }
            TextButton(onClick = onLogOut) { Text("Esci") }
        }

        is MainViewModel.UiState.Ready -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            item(contentType = "greeting") {
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 40.dp)) {
                    Text(
                        greeting().uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        state.displayName,
                        style = MaterialTheme.typography.displayLarge,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            if (playback.hasItem) {
                item(contentType = "resume") {
                    ResumeCard(playback, onOpenPlayer)
                }
            }

            item(contentType = "section") {
                SectionTitle("Le tue playlist", Modifier.padding(top = 30.dp))
            }

            item(contentType = "carousel") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.padding(top = 14.dp),
                ) {
                    items(state.playlists.take(CAROUSEL_SIZE), key = { it.uri }) { playlist ->
                        PlaylistTile(playlist) { onOpenPlaylist(playlist) }
                    }
                }
            }

            if (recent.isNotEmpty()) {
                item(contentType = "section") {
                    SectionTitle("Riascolta", Modifier.padding(top = 34.dp))
                }
                item(contentType = "recent") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.padding(top = 14.dp),
                    ) {
                        itemsIndexed(recent, key = { _, track -> track.uri }) { index, track ->
                            TrackTile(track) { onPlayRecent(recent, index) }
                        }
                    }
                }
            }

            item(contentType = "section") {
                SectionTitle("Il resto della libreria", Modifier.padding(top = 34.dp))
            }

            // A deliberately short list rather than all 62: the home page is for
            // getting somewhere quickly, and the library tab already holds the
            // full set.
            items(
                state.playlists.drop(CAROUSEL_SIZE).take(REST_SIZE),
                key = { it.uri },
                contentType = { "row" },
            ) { playlist ->
                CompactRow(playlist) { onOpenPlaylist(playlist) }
            }
        }
    }
}

@Composable
private fun ResumeCard(playback: PlaybackState, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(start = 24.dp, end = 24.dp, top = 26.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            url = playback.artworkUrl,
            title = playback.title,
            modifier = Modifier.size(74.dp),
            corner = 16.dp,
            decodeSize = 74.dp,
        )
        Column(
            Modifier
                .padding(start = 16.dp)
                .weight(1f),
        ) {
            Text(
                if (playback.isPlaying) "IN RIPRODUZIONE" else "IN PAUSA",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                playback.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                playback.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TrackTile(track: CatalogTrack, onClick: () -> Unit) {
    Column(
        // No clip on the column: it would cut off the artwork's drop shadow,
        // which spreads wider than the tile itself.
        Modifier
            .width(132.dp)
            .clickable(onClick = onClick),
    ) {
        Artwork(
            url = track.artworkUrl,
            title = track.name,
            modifier = Modifier
                .size(132.dp)
                .softShadow(RoundedCornerShape(16.dp), elevation = 14.dp),
            corner = 16.dp,
            decodeSize = 132.dp,
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp),
        )
    }
}

@Composable
private fun PlaylistTile(playlist: CatalogPlaylist, onClick: () -> Unit) {
    Column(
        Modifier
            .width(150.dp)
            .clickable(onClick = onClick),
    ) {
        Artwork(
            url = playlist.artworkUrl,
            title = playlist.name,
            modifier = Modifier
                .size(150.dp)
                .softShadow(RoundedCornerShape(18.dp), elevation = 16.dp),
            corner = 18.dp,
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
private fun CompactRow(playlist: CatalogPlaylist, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            url = playlist.artworkUrl,
            title = playlist.name,
            modifier = Modifier.size(46.dp),
            corner = 12.dp,
            decodeSize = 46.dp,
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

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.headlineLarge,
        modifier = modifier.padding(horizontal = 24.dp),
    )
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

private const val CAROUSEL_SIZE = 8
private const val REST_SIZE = 6
