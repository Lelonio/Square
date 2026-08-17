package dev.lelonio.square.ui.player

import androidx.compose.foundation.background
import dev.lelonio.square.ui.glass.backdrop.effects.blur
import dev.lelonio.square.ui.glass.backdrop.drawBackdrop
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Check
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.R
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.components.Artwork

/**
 * Which playlist the playing track should go into.
 *
 * The plus in the player opens this. It stands where a heart used to, and the
 * heart is what it fixes: Liked Songs is one playlist out of the account's
 * several, and a button that could only ever add to that one spent its whole
 * width answering a question nobody asked.
 *
 * The rows do not close the sheet. Adding one track to two playlists is a
 * perfectly ordinary thing to want, and a sheet that dismissed itself on the
 * first tap would make it two round trips through the player.
 */
@Composable
fun AddToPlaylistSheet(
    state: MainViewModel.AddToPlaylistState,
    backdrop: Backdrop,
    onSelect: (CatalogPlaylist) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
    // The page out of focus, like the track sheet: this covers the player as
    // often as it covers a list, and a scrim alone left both perfectly readable
    // underneath.
    AnimatedVisibility(
        visible = state.open,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(180)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RectangleShape },
                    effects = { blur(28f.dp.toPx()) },
                    onDrawSurface = { drawRect(Color.Black.copy(alpha = 0.42f)) },
                )
                .clickable(interactionSource = null, indication = null, onClick = onDismiss),
        )
    }

    AnimatedVisibility(
        visible = state.open,
        // Up from the bottom edge, since that is where it sits.
        enter = slideInVertically(tween(240)) { it / 6 } + fadeIn(tween(180)),
        exit = slideOutVertically(tween(180)) { it / 6 } + fadeOut(tween(150)),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        GlassSurface(
            backdrop = backdrop,
            shape = RoundedCornerShape(28.dp),
            surfaceColor = GlassFilm,
            // Heavier than the page behind it: the panel samples the unblurred
            // layer, so at the usual radius the rows underneath came through
            // sharper inside the sheet than outside it.
            blurScale = 23.0f,
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                // Swallows taps so one inside the sheet does not dismiss it.
                .clickable(interactionSource = null, indication = null) {},
        ) {
            Column(Modifier.padding(vertical = 18.dp)) {
                Text(
                    stringResource(R.string.add_to_playlist),
                    style = MaterialTheme.typography.titleLarge,
                    color = GlassInk,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp),
                )
                Text(
                    // Says what is being added: the sheet can outlive the track
                    // that opened it, since playback carries on underneath.
                    state.trackTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = GlassInkDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 2.dp, bottom = 12.dp),
                )

                if (state.error != null) {
                    Text(
                        state.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                    )
                }

                if (state.done != null) {
                    Text(
                        stringResource(R.string.added_to, state.done.orEmpty()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                    )
                }

                if (state.playlists.isEmpty()) {
                    Text(
                        stringResource(R.string.no_playlists_in_account),
                        style = MaterialTheme.typography.bodyMedium,
                        color = GlassInkDim,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                    )
                } else {
                    Column(
                        Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        state.playlists.forEach { playlist ->
                            PlaylistRow(
                                playlist = playlist,
                                busy = state.busy == playlist.uri,
                                added = state.done == playlist.name,
                                enabled = state.trackUri != null && state.busy == null,
                                onClick = { onSelect(playlist) },
                            )
                        }
                    }
                }
            }
        }
    }
    }
}

/**
 * The picker itself, without a sheet around it.
 *
 * The player puts this in the panel that shows the lyrics and the queue instead
 * of opening a modal over the transport; everywhere else still gets the sheet.
 */
@Composable
fun PlaylistPicker(
    state: MainViewModel.AddToPlaylistState,
    onSelect: (CatalogPlaylist) -> Unit,
) {
            Column(Modifier.padding(vertical = 18.dp)) {
                Text(
                    stringResource(R.string.add_to_playlist),
                    style = MaterialTheme.typography.titleLarge,
                    color = GlassInk,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp),
                )
                Text(
                    // Says what is being added: the sheet can outlive the track
                    // that opened it, since playback carries on underneath.
                    state.trackTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = GlassInkDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 2.dp, bottom = 12.dp),
                )

                if (state.error != null) {
                    Text(
                        state.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                    )
                }

                if (state.done != null) {
                    Text(
                        stringResource(R.string.added_to, state.done.orEmpty()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                    )
                }

                if (state.playlists.isEmpty()) {
                    Text(
                        stringResource(R.string.no_playlists_in_account),
                        style = MaterialTheme.typography.bodyMedium,
                        color = GlassInkDim,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                    )
                } else {
                    Column(
                        Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        state.playlists.forEach { playlist ->
                            PlaylistRow(
                                playlist = playlist,
                                busy = state.busy == playlist.uri,
                                added = state.done == playlist.name,
                                enabled = state.trackUri != null && state.busy == null,
                                onClick = { onSelect(playlist) },
                            )
                        }
                    }
                }
            }
}

@Composable
private fun PlaylistRow(
    playlist: CatalogPlaylist,
    busy: Boolean,
    added: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            url = playlist.artworkUrl,
            title = playlist.name,
            modifier = Modifier.size(44.dp),
            corner = 10.dp,
            decodeSize = 44.dp,
        )
        Text(
            playlist.name,
            style = MaterialTheme.typography.titleMedium,
            color = GlassInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 16.dp)
                .weight(1f),
        )
        when {
            busy -> CircularProgressIndicator(
                color = GlassInkDim,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )

            added -> Icon(
                PhosphorIcons.Regular.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
