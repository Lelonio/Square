package dev.lelonio.square.ui.player

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.R
import dev.lelonio.square.ui.MainViewModel
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowClockwise
import com.adamglin.phosphoricons.regular.Desktop
import com.adamglin.phosphoricons.regular.DeviceMobile
import com.adamglin.phosphoricons.regular.SpeakerHifi
import com.adamglin.phosphoricons.regular.Television

/**
 * The Spotify Connect device list.
 *
 * The list comes from the Web API rather than from the engine: librespot keeps
 * the cluster — every device the account can see — inside the Connect event
 * loop with no public accessor, so reading it there would mean patching the
 * crate for something an endpoint already answers.
 *
 * Switching device is a real transfer, not a local mute: the audio moves, and
 * this app stops decoding. That is the whole point of Connect, and it is why
 * the active device is marked rather than assumed to be this one.
 */
@Composable
fun DevicePicker(
    state: MainViewModel.DevicesState,
    backdrop: Backdrop,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            // Catches the tap that closes it, and dims what is behind so the
            // sheet reads as being in front rather than as another panel.
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = null, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        GlassSurface(
            backdrop = backdrop,
            shape = RoundedCornerShape(28.dp),
            surfaceColor = GlassFilm,
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                // Swallows taps so one inside the sheet does not dismiss it.
                .clickable(interactionSource = null, indication = null) {},
        ) {
            Column(Modifier.padding(vertical = 18.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 22.dp, end = 14.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.play_on),
                        style = MaterialTheme.typography.titleLarge,
                        color = GlassInk,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onRefresh),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            PhosphorIcons.Regular.ArrowClockwise,
                            contentDescription = stringResource(R.string.refresh),
                            tint = GlassInkDim,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                when {
                    state.loading && state.devices.isEmpty() -> Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 26.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = GlassInkDim, strokeWidth = 2.dp)
                    }

                    state.error != null -> Text(
                        state.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = GlassInkDim,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                    )

                    state.devices.isEmpty() -> Text(
                        stringResource(R.string.no_devices),
                        style = MaterialTheme.typography.bodyMedium,
                        color = GlassInkDim,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                    )

                    else -> Column(
                        Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        state.devices.forEach { device ->
                            DeviceRow(device, switching = state.switchingTo == device.id) {
                                onSelect(device.id)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The list itself, without a sheet around it.
 *
 * The player shows this in the panel it uses for the lyrics and the queue rather
 * than as a modal over the transport; the modal form is still what the rest of
 * the app gets.
 */
@Composable
fun DeviceList(
    state: MainViewModel.DevicesState,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(Modifier.padding(vertical = 18.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 22.dp, end = 14.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.play_on),
                        style = MaterialTheme.typography.titleLarge,
                        color = GlassInk,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onRefresh),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            PhosphorIcons.Regular.ArrowClockwise,
                            contentDescription = stringResource(R.string.refresh),
                            tint = GlassInkDim,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                when {
                    state.loading && state.devices.isEmpty() -> Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 26.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = GlassInkDim, strokeWidth = 2.dp)
                    }

                    state.error != null -> Text(
                        state.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = GlassInkDim,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                    )

                    state.devices.isEmpty() -> Text(
                        stringResource(R.string.no_devices),
                        style = MaterialTheme.typography.bodyMedium,
                        color = GlassInkDim,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                    )

                    else -> Column(
                        Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        state.devices.forEach { device ->
                            DeviceRow(device, switching = state.switchingTo == device.id) {
                                onSelect(device.id)
                            }
                        }
                    }
                }
            }
}

@Composable
private fun DeviceRow(
    device: MainViewModel.SpotifyDevice,
    /** Whether this is the row the playback is moving to right now. */
    switching: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !device.isActive && !switching, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            iconFor(device.type),
            contentDescription = null,
            // The playing device is the accent's one job on this sheet: every
            // other row has to stay legible over whatever is behind the glass.
            tint = if (device.isActive) MaterialTheme.colorScheme.primary else GlassInkDim,
            modifier = Modifier.size(22.dp),
        )
        Column(
            Modifier
                .padding(start = 16.dp)
                .weight(1f),
        ) {
            Text(
                device.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (device.isActive) MaterialTheme.colorScheme.primary else GlassInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    switching -> stringResource(R.string.switching)
                    device.isActive -> stringResource(R.string.now_playing)
                    else -> device.type
                },
                style = MaterialTheme.typography.bodySmall,
                color = GlassInkDim,
                maxLines = 1,
            )
        }

        // The one piece of feedback a handover has. Moving playback takes a
        // moment, and without this the row simply sat there, so it was pressed
        // again and again.
        if (switching) {
            CircularProgressIndicator(
                color = GlassInkDim,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Spotify's device types, as far as they map onto icons worth telling apart. */
private fun iconFor(type: String): ImageVector = when (type.lowercase()) {
    "computer" -> PhosphorIcons.Regular.Desktop
    "smartphone", "tablet" -> PhosphorIcons.Regular.DeviceMobile
    "tv", "castvideo" -> PhosphorIcons.Regular.Television
    else -> PhosphorIcons.Regular.SpeakerHifi
}
