package dev.lelonio.square.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lelonio.square.data.sources.AddonSourceClient
import dev.lelonio.square.data.sources.AddonSourceConfig
import dev.lelonio.square.data.sources.AddonSourceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Addon management UI. Hosts should embed this in the Playback/Source settings
 * page. Addons are regular HTTPS endpoints; this UI never installs plugin code.
 */
@Composable
fun AddonSourcesSection() {
    val context = LocalContext.current
    val store = remember(context) { AddonSourceStore(context) }
    val sources by store.sources.collectAsStateWithLifecycle()
    var address by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<AddonSourceConfig?>(null) }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Music sources", style = MaterialTheme.typography.titleLarge)
        Text(
            "Add a BitChord-compatible addon server. Enabled addons are queried before YouTube Music when resolving a track.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = address,
            onValueChange = { address = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Addon URL") },
            placeholder = { Text("https://music.example") },
            singleLine = true,
        )
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Name (optional)") },
            singleLine = true,
        )
        Button(
            enabled = address.isNotBlank() && !checking,
            onClick = {
                checking = true
                error = null
                // Don't persist an endpoint until it has responded and its
                // manifest has been checked.
                kotlinx.coroutines.MainScope().launch {
                    try {
                        val candidate = AddonSourceConfig(baseUrl = address.trim())
                        val manifest = withContext(Dispatchers.IO) { AddonSourceClient(candidate).manifest() }
                        store.add(address, displayName.ifBlank { manifest.name })
                        address = ""
                        displayName = ""
                    } catch (failure: Exception) {
                        error = failure.message ?: "Could not connect to addon"
                    } finally {
                        checking = false
                    }
                }
            },
        ) { Text(if (checking) "Checking…" else "Check and add source") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (sources.isEmpty()) {
            Text("No addon sources added yet.", modifier = Modifier.padding(vertical = 12.dp))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(sources, key = { it.id }) { source ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(source.name.ifBlank { source.baseUrl }, style = MaterialTheme.typography.titleMedium)
                            Text(source.baseUrl, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { removeTarget = source }) { Text("Remove") }
                        }
                        Switch(
                            checked = source.enabled,
                            onCheckedChange = { store.update(source.copy(enabled = it)) },
                        )
                    }
                }
            }
        }
    }
    removeTarget?.let { source ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove source?") },
            text = { Text("${source.name.ifBlank { source.baseUrl }} will no longer be used for playback.") },
            confirmButton = {
                TextButton(onClick = { store.remove(source.id); removeTarget = null }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("Cancel") } },
        )
    }
}
