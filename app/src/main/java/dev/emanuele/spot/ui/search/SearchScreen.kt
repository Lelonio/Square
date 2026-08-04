package dev.emanuele.spot.ui.search

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.emanuele.spot.data.CatalogTrack
import dev.emanuele.spot.data.SearchItem
import dev.emanuele.spot.ui.MainViewModel
import dev.emanuele.spot.ui.components.Artwork
import com.kyant.backdrop.Backdrop
import dev.emanuele.spot.ui.components.SwipeToQueue
import dev.emanuele.spot.ui.glass.LiquidButton
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.MagnifyingGlass
import com.adamglin.phosphoricons.regular.X

/**
 * Search across tracks, artists, albums and playlists.
 *
 * Tracks come first and get the most room: looking for a song is what this box
 * is mostly used for, and putting artists above them would make the common case
 * the one that needs scrolling.
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
    onOpenContext: (SearchItem) -> Unit,
    backdrop: Backdrop,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(contentType = "field") {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 36.dp, bottom = 10.dp)) {
                Text("Cerca", style = MaterialTheme.typography.displayLarge)
                OutlinedTextField(
                    enabled = !state.needsSetup,
                    value = state.query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("Brani, artisti, album, playlist") },
                    singleLine = true,
                    leadingIcon = { Icon(PhosphorIcons.Fill.MagnifyingGlass, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(PhosphorIcons.Regular.X, contentDescription = "Cancella")
                            }
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                )
            }
        }

        when {
            state.needsSetup -> item(contentType = "setup") {
                WebApiSetup(webApi, backdrop, onClientIdChange, onConnectWebApi)
            }

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

            state.query.isBlank() -> item(contentType = "status") {
                StatusBox {
                    Text(
                        "Cerca qualcosa da ascoltare",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.results.isEmpty -> item(contentType = "status") {
                StatusBox {
                    Text(
                        "Nessun risultato per “${state.query}”",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            else -> {
                val tracks = state.results.tracks
                if (tracks.isNotEmpty()) {
                    item(contentType = "section") { SectionTitle("Brani") }
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
                            )
                        }
                    }
                }

                section("Artisti", state.results.artists, round = true, onOpenContext)
                section("Album", state.results.albums, round = false, onOpenContext)
                section("Playlist", state.results.playlists, round = false, onOpenContext)
            }
        }
    }
}

private fun LazyListScope.section(
    title: String,
    items: List<SearchItem>,
    round: Boolean,
    onOpen: (SearchItem) -> Unit,
) {
    if (items.isEmpty()) return
    item(contentType = "section") { SectionTitle(title) }
    items(
        count = items.size,
        key = { "$title-${items[it].uri}" },
        contentType = { "result" },
    ) { index ->
        val item = items[index]
        ResultRow(
            title = item.title,
            subtitle = item.subtitle,
            artworkUrl = item.artworkUrl,
            highlighted = false,
            round = round,
            onClick = { onOpen(item) },
        )
    }
}

/**
 * One-time setup for the Web API.
 *
 * This screen exists because of a hard constraint rather than a preference:
 * playback must authenticate as librespot's shared client id — no other id is
 * accepted by the access point — and that same id has no Web API quota left,
 * since every librespot-based client on earth spends it. Search therefore needs
 * an application the user owns.
 */
@Composable
private fun WebApiSetup(
    state: MainViewModel.WebApiState,
    backdrop: Backdrop,
    onClientIdChange: (String) -> Unit,
    onConnect: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text("ATTIVA LA RICERCA", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(
            "La ricerca usa le API web di Spotify, che hanno un limite di richieste " +
                "per applicazione. Registra un'app nel dashboard per sviluppatori e " +
                "incolla qui il suo client id: così il limite è solo tuo.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        Text(
            "Nell'app va aggiunto questo Redirect URI, identico:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 18.dp),
        )
        Row(
            Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                state.redirectUri,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { clipboard.setText(AnnotatedString(state.redirectUri)) }) {
                Text("Copia")
            }
        }

        OutlinedTextField(
            value = state.clientId,
            onValueChange = onClientIdChange,
            label = { Text("Client id") },
            singleLine = true,
            enabled = !state.connecting,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        )

        if (state.error != null) {
            Text(
                state.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        LiquidButton(
            onClick = { if (!state.connecting) onConnect() },
            backdrop = backdrop,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 16.dp)
                .height(50.dp),
        ) {
            if (state.connecting) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Collega", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
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
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
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
                color = if (highlighted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
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
