package dev.lelonio.square.ui.settings

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.R
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.glass.LiquidButton

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
        Text(stringResource(R.string.turn_on_search), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(R.string.web_api_explainer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        Text(
            stringResource(R.string.redirect_uri_note),
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
                Text(stringResource(R.string.copy))
            }
        }

        WebApiSetupInline(state, backdrop, onClientIdChange, onConnect)
    }
}

/**
 * Just the field and the button.
 *
 * Split out for the welcome tutorial, which has already spent two screens
 * explaining why this is needed and where the id comes from, and would
 * otherwise say all of it twice on the screen that asks for it.
 */
@Composable
internal fun WebApiSetupInline(
    state: MainViewModel.WebApiState,
    backdrop: Backdrop,
    onClientIdChange: (String) -> Unit,
    onConnect: () -> Unit,
) {
    Column {
        OutlinedTextField(
            value = state.clientId,
            onValueChange = onClientIdChange,
            label = { Text(stringResource(R.string.client_id)) },
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
                Text(stringResource(R.string.connect), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
