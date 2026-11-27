package dev.lelonio.square.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lelonio.square.data.sources.AddonSourceClient
import dev.lelonio.square.data.sources.AddonSourceConfig
import dev.lelonio.square.data.sources.AddonSourceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AddonSourcesSection() {
    val context = LocalContext.current
    val store = remember(context) { AddonSourceStore(context) }
    val scope = rememberCoroutineScope()
    val sources by store.sources.collectAsStateWithLifecycle()
    var address by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<AddonSourceConfig?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(address, { address = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Addon URL") }, placeholder = { Text("https://music.example") }, singleLine = true)
        OutlinedTextField(displayName, { displayName = it }, Modifier.fillMaxWidth(), label = { Text("Name (optional)") }, singleLine = true)
        Button(enabled = address.isNotBlank() && !checking, onClick = {
            checking = true; error = null
            scope.launch {
                try {
                    val manifest = withContext(Dispatchers.IO) { AddonSourceClient(AddonSourceConfig(baseUrl = address.trim())).manifest() }
                    store.add(address, displayName.ifBlank { manifest.name })
                    address = ""; displayName = ""
                } catch (failure: Exception) { error = failure.message ?: "Could not connect to addon" }
                finally { checking = false }
            }
        }) { Text(if (checking) "Checking…" else "Check and add source") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (sources.isEmpty()) Text("No addon sources added yet.", modifier = Modifier.padding(vertical = 12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            items(sources, key = { it.id }) { source ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(source.name.ifBlank { source.baseUrl }, style = MaterialTheme.typography.titleMedium)
                        Text(source.baseUrl, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { removeTarget = source }) { Text("Remove") }
                    }
                    Switch(checked = source.enabled, onCheckedChange = { store.update(source.copy(enabled = it)) })
                }
            }
        }
    }
    removeTarget?.let { source -> AlertDialog(onDismissRequest = { removeTarget = null }, title = { Text("Remove source?") },
        text = { Text("${source.name.ifBlank { source.baseUrl }} will no longer be used for playback.") },
        confirmButton = { TextButton(onClick = { store.remove(source.id); removeTarget = null }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("Cancel") } }) }
}
