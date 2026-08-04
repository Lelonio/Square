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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.CaretUp
import com.kyant.backdrop.Backdrop
import dev.emanuele.spot.BuildConfig
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
                    Icon(PhosphorIcons.Regular.ArrowLeft, contentDescription = "Indietro")
                }
                Text(
                    "Impostazioni",
                    style = MaterialTheme.typography.displayLarge,
                    modifier = Modifier.padding(start = 14.dp),
                )
            }
        }

        item("account") {
            Section("Account") {
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
                            ready?.displayName ?: "Non connesso",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (ready != null) {
                                "${ready.playlists.size} playlist"
                            } else {
                                "Accedi per riprendere"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = InkDim,
                        )
                    }
                }

                RowDivider()
                InfoRow("Dispositivo Connect", deviceName)
            }
        }

        item("webapi") {
            Section("API web") {
                if (webApi.connected) {
                    InfoRow("Applicazione", "Collegata")
                    RowDivider()
                    InfoRow(
                        "Client id",
                        webApi.clientId.take(8) + if (webApi.clientId.length > 8) "…" else "",
                    )
                    RowDivider()
                    ActionRow("Scollega", destructive = true, onClick = onDisconnectWebApi)
                } else {
                    // The full explanation, because with no application
                    // connected search does not work at all and the reason is
                    // not something anyone would guess.
                    WebApiSetup(webApi, backdrop, onClientIdChange, onConnectWebApi)
                }
            }
        }

        item("permissions") {
            Section("Permessi richiesti") {
                Text(
                    "Un'applicazione collegata prima di un aggiornamento va ricollegata " +
                        "per concedere i permessi aggiunti dopo: le sezioni che li usano " +
                        "restano vuote finché non lo fai.",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkDim,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                )
                SCOPES.forEach { (scope, why) ->
                    RowDivider()
                    InfoRow(scope, why)
                }
            }
        }

        item("about") {
            Section("Informazioni") {
                InfoRow("Versione", "${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
                RowDivider()
                Licences()
            }
        }

        if (ready != null) {
            item("logout") {
                Section(null) {
                    ActionRow("Esci", destructive = true, onClick = onLogOut)
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
            Text("Licenze", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
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
    "user-top-read" to "Artisti che ascolti",
    "user-read-playback-state" to "Elenco dispositivi",
    "user-modify-playback-state" to "Sposta la riproduzione",
    "user-library-read" to "Brani salvati",
    "user-library-modify" to "Cuore nel player",
)

private val LICENCES = listOf(
    "librespot" to "MIT",
    "Bungee" to "MPL-2.0",
    "AndroidLiquidGlass" to "Apache-2.0",
    "Phosphor Icons" to "MIT",
    "Coil" to "Apache-2.0",
    "OkHttp / Retrofit" to "Apache-2.0",
)
