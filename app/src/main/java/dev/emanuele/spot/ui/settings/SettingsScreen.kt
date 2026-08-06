package dev.emanuele.spot.ui.settings

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.ArrowUpRight
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.CaretUp
import com.kyant.backdrop.Backdrop
import dev.emanuele.spot.BuildConfig
import dev.emanuele.spot.R
import dev.emanuele.spot.data.AppLanguages
import dev.emanuele.spot.ui.MainViewModel
import dev.emanuele.spot.ui.components.Artwork
import dev.emanuele.spot.ui.glass.LiquidButton
import dev.emanuele.spot.ui.theme.Ink
import dev.emanuele.spot.ui.theme.InkDim
import dev.emanuele.spot.ui.theme.softShadow

/**
 * Everything that is configuration rather than listening.
 *
 * Reached from the avatar in the home header, not from the tab bar. The bar has
 * three places you go to play something and settings is not one of them — it is
 * somewhere you visit twice a year, and giving it a permanent quarter of the
 * navigation would say otherwise.
 *
 * Deliberately short. Most of what a music app usually puts here is decided by
 * the account or by the engine and is not ours to offer: bitrate comes from
 * Premium, the device name from the phone. What is left is the account itself,
 * the Web API application the user has to register for search, and the licences
 * this app owes attribution to.
 */
@Composable
fun SettingsScreen(
    state: MainViewModel.UiState,
    webApi: MainViewModel.WebApiState,
    contentPadding: PaddingValues,
    backdrop: Backdrop,
    deviceName: String,
    onClientIdChange: (String) -> Unit,
    onConnectWebApi: () -> Unit,
    onDisconnectWebApi: () -> Unit,
    onLogOut: () -> Unit,
    onShowTutorial: () -> Unit,
    /** The chosen language tag, empty for the phone's own. */
    language: String,
    onLanguage: (String) -> Unit,
    onBack: () -> Unit,
) {
    val ready = state as? MainViewModel.UiState.Ready

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        item("top") {
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
                    stringResource(R.string.settings),
                    style = MaterialTheme.typography.displayLarge,
                    modifier = Modifier.padding(start = 14.dp),
                )
            }
        }

        item("account") {
            Section(stringResource(R.string.account)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(
                        url = ready?.avatarUrl,
                        title = ready?.displayName.orEmpty(),
                        modifier = Modifier
                            .size(54.dp)
                            .softShadow(CircleShape, elevation = 10.dp),
                        corner = 27.dp,
                        decodeSize = 54.dp,
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = 14.dp),
                    ) {
                        Text(
                            ready?.displayName ?: stringResource(R.string.not_connected),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (ready != null) {
                                stringResource(R.string.playlist_count, ready.playlists.size)
                            } else {
                                stringResource(R.string.log_in_to_resume)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = InkDim,
                        )
                    }
                }

                RowDivider()
                InfoRow(stringResource(R.string.connect_device), deviceName)
            }
        }

        item("webapi") {
            Section(stringResource(R.string.web_api)) {
                if (webApi.connected) {
                    InfoRow(stringResource(R.string.application), stringResource(R.string.connected))
                    RowDivider()
                    InfoRow(
                        stringResource(R.string.client_id),
                        webApi.clientId.take(8) + if (webApi.clientId.length > 8) "…" else "",
                    )
                    RowDivider()
                    ActionRow(stringResource(R.string.disconnect), destructive = true, onClick = onDisconnectWebApi)
                } else {
                    // The full explanation, because with no application
                    // connected search does not work at all and the reason is
                    // not something anyone would guess.
                    WebApiSetup(webApi, backdrop, onClientIdChange, onConnectWebApi)
                }
            }
        }

        item("tutorial") {
            Section(stringResource(R.string.guide)) {
                ActionRow(stringResource(R.string.see_setup_again), destructive = false) {
                    onShowTutorial()
                }
            }
        }

        item("language") {
            Section(stringResource(R.string.language)) {
                AppLanguages.forEachIndexed { index, (tag, name) ->
                    if (index > 0) RowDivider()
                    ChoiceRow(
                        label = name.ifEmpty { stringResource(R.string.system_language) },
                        selected = tag == language,
                    ) { onLanguage(tag) }
                }
            }
        }

        item("permissions") {
            Section(stringResource(R.string.permissions_asked)) {
                Text(
                    stringResource(R.string.permissions_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkDim,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                )
                SCOPES.forEach { (scope, why) ->
                    RowDivider()
                    InfoRow(scope, stringResource(why))
                }
            }
        }

        item("author") {
            Section(stringResource(R.string.developed_by)) {
                val uriHandler = LocalUriHandler.current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { uriHandler.openUri(GITHUB_URL) }
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // GitHub serves the account picture at `<user>.png`, so the
                    // avatar follows whatever it is set to rather than being a
                    // copy checked in here.
                    Artwork(
                        url = "$GITHUB_URL.png",
                        title = GITHUB_USER,
                        modifier = Modifier
                            .size(54.dp)
                            .softShadow(CircleShape, elevation = 10.dp),
                        corner = 27.dp,
                        decodeSize = 54.dp,
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = 14.dp),
                    ) {
                        Text(GITHUB_USER, style = MaterialTheme.typography.titleMedium)
                        Text(
                            GITHUB_URL.removePrefix("https://"),
                            style = MaterialTheme.typography.bodySmall,
                            color = InkDim,
                        )
                    }
                    Icon(
                        PhosphorIcons.Regular.ArrowUpRight,
                        contentDescription = null,
                        tint = InkDim,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        item("about") {
            Section(stringResource(R.string.about)) {
                InfoRow(stringResource(R.string.version), "${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
                RowDivider()
                Licences()
            }
        }

        if (ready != null) {
            item("logout") {
                Section(null) {
                    ActionRow(stringResource(R.string.log_out), destructive = true, onClick = onLogOut)
                }
            }
        }
    }
}

/**
 * The third-party code this app ships, and under what.
 *
 * Not a nicety: Bungee is MPL-2.0 and the Backdrop components are Apache-2.0,
 * and both licences require the notice to travel with the binary. Collapsed by
 * default because it is an obligation to the authors, not a feature.
 */
@Composable
private fun Licences() {
    var open by remember { mutableStateOf(false) }

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.licences), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(
                if (open) PhosphorIcons.Regular.CaretUp else PhosphorIcons.Regular.CaretDown,
                contentDescription = null,
                tint = InkDim,
                modifier = Modifier.size(18.dp),
            )
        }
        if (open) {
            LICENCES.forEach { (what, licence) ->
                Text(
                    "$what — $licence",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkDim,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Section(title: String?, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
        if (title != null) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = InkDim,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Ink.copy(alpha = 0.07f)),
            content = { content() },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = InkDim,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ActionRow(label: String, destructive: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        color = if (destructive) MaterialTheme.colorScheme.error else Ink,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    )
}

/** A row of a list where one is picked, with a tick on the one that is. */
@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) Ink else InkDim,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                PhosphorIcons.Regular.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp)
            .height(1.dp)
            .background(Ink.copy(alpha = 0.08f)),
    )
}

/** Kept next to the request that asks for them; see MainViewModel.connectWebApi. */
private val SCOPES = listOf(
    "user-top-read" to R.string.scope_top_artists,
    "user-read-recently-played" to R.string.scope_history,
    "user-read-playback-state" to R.string.scope_devices,
    "user-modify-playback-state" to R.string.scope_transfer,
    "playlist-modify-private" to R.string.scope_private_playlists,
    "playlist-modify-public" to R.string.scope_public_playlists,
)

private val LICENCES = listOf(
    "librespot" to "MIT",
    "Bungee" to "MPL-2.0",
    "AndroidLiquidGlass" to "Apache-2.0",
    "Phosphor Icons" to "MIT",
    "Coil" to "Apache-2.0",
    "OkHttp / Retrofit" to "Apache-2.0",
)

private const val GITHUB_USER = "Lelonio"
private const val GITHUB_URL = "https://github.com/Lelonio"
