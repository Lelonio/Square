package dev.emanuele.spot.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.emanuele.spot.data.CatalogPlaylist
import dev.emanuele.spot.ui.MainViewModel
import dev.emanuele.spot.ui.components.Artwork

@Composable
fun LibraryScreen(
    state: MainViewModel.UiState,
    contentPadding: PaddingValues,
    onLogIn: () -> Unit,
    onRetry: () -> Unit,
    onLogOut: () -> Unit,
    onOpenPlaylist: (CatalogPlaylist) -> Unit,
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

        MainViewModel.UiState.Connecting -> Centered {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
            )
            Text(
                "Connessione…",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        MainViewModel.UiState.Loading -> Centered {
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
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            item(contentType = "header") {
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 28.dp)) {
                    Text(
                        state.displayName.uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "Libreria",
                        style = MaterialTheme.typography.displayLarge,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(
                        "${state.playlists.size} playlist",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            // contentType lets the lazy list reuse row layouts instead of
            // rebuilding one per item as it scrolls.
            items(state.playlists, key = { it.uri }, contentType = { "playlist" }) { playlist ->
                PlaylistRow(playlist) { onOpenPlaylist(playlist) }
            }
        }
    }
}

@Composable
private fun PlaylistRow(playlist: CatalogPlaylist, onClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(
                url = playlist.artworkUrl,
                title = playlist.name,
                modifier = Modifier.size(46.dp),
                corner = 14.dp,
                decodeSize = 46.dp,
            )
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 16.dp)
                    .weight(1f),
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 86.dp, end = 24.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
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
