package dev.emanuele.spot.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import dev.emanuele.spot.ui.MainViewModel
import dev.emanuele.spot.ui.glass.LiquidButton

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
internal fun WebApiSetup(
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
