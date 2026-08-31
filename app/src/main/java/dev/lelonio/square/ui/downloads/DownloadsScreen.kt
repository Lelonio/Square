package dev.lelonio.square.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lelonio.square.R
import dev.lelonio.square.offline.DownloadManager
import dev.lelonio.square.offline.DownloadQuality
import dev.lelonio.square.offline.DownloadRecord
import dev.lelonio.square.offline.DownloadStatus
import dev.lelonio.square.ui.components.Artwork
import dev.lelonio.square.ui.components.UiStateSurface
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI over the persistent Phase 3 download state; it owns no download truth. */
@Composable
fun DownloadsScreen(
    contentPadding: PaddingValues,
    backdrop: Backdrop,
    onPlayTrack: (DownloadRecord) -> Unit = {},
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as dev.lelonio.square.SquareApplication }
    val manager = remember(app) { app.downloadManager }
    val records by manager.records.collectAsStateWithLifecycle()
    val wifiOnly by manager.wifiOnly.collectAsStateWithLifecycle()
    val quality by manager.quality.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var actualUsage by remember { mutableLongStateOf(0L) }

    val completedSize = remember(records) {
        records.filter { it.status == DownloadStatus.COMPLETED }.sumOf { it.sizeBytes }
    }
    LaunchedEffect(completedSize) {
        actualUsage = withContext(Dispatchers.IO) { manager.storageUsageBytes() }
    }

    LazyColumn(
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("settings") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
                Text(stringResource(R.string.downloads), color = Ink, style = androidx.compose.material3.MaterialTheme.typography.displayLarge)
                Text(
                    stringResource(R.string.download_storage) + ": " + formatBytes(actualUsage),
                    color = InkDim,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        item("preferences") {
            DownloadPreferencesCard(
                wifiOnly = wifiOnly,
                quality = quality,
                onWifiOnly = manager::setWifiOnly,
                onQuality = manager::setQuality,
            )
        }

        if (records.isEmpty()) {
            item("empty") {
                UiStateSurface(
                    title = stringResource(R.string.downloads_empty),
                    body = stringResource(R.string.downloads_empty_note),
                    backdrop = backdrop,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            }
        } else {
            items(records, key = { it.jobId }, contentType = { "download" }) { record ->
                DownloadRow(
                    record = record,
                    backdrop = backdrop,
                    onPlay = { onPlayTrack(record) },
                    onPause = { scope.launch(Dispatchers.IO) { manager.pause(record.jobId) } },
                    onResume = { scope.launch(Dispatchers.IO) { manager.resume(record.jobId) } },
                    onCancel = { scope.launch(Dispatchers.IO) { manager.cancel(record.jobId) } },
                    onRetry = { scope.launch(Dispatchers.IO) { manager.retry(record.jobId) } },
                    onDelete = { scope.launch(Dispatchers.IO) { manager.delete(record.jobId) } },
                )
            }
        }
    }
}

@Composable
private fun DownloadPreferencesCard(
    wifiOnly: Boolean,
    quality: DownloadQuality,
    onWifiOnly: (Boolean) -> Unit,
    onQuality: (DownloadQuality) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.download_wifi_only), color = Ink, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.download_wifi_only_note), color = InkDim, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            }
            Switch(checked = wifiOnly, onCheckedChange = onWifiOnly)
        }
        Text(
            stringResource(R.string.download_quality),
            color = InkDim,
            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DownloadQuality.entries.forEach { option ->
                LiquidButton(
                    onClick = { onQuality(option) },
                    backdrop = remember { error("Download preference backdrop is supplied by parent") },
                ) { }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    record: DownloadRecord,
    backdrop: Backdrop,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val title = record.track.name.ifBlank { record.track.uri }
    val status = statusText(record.status)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Artwork(
                url = record.track.artworkUrl,
                title = title,
                modifier = Modifier.size(60.dp),
                corner = 14.dp,
                decodeSize = 60.dp,
            )
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title, color = Ink, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(record.track.artist, color = InkDim, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, color = InkDim, style = androidx.compose.material3.MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 2.dp))
            }
            when (record.status) {
                DownloadStatus.COMPLETED -> LiquidButton(onClick = onPlay, backdrop = backdrop) { Text(stringResource(R.string.play), color = Ink) }
                DownloadStatus.DOWNLOADING, DownloadStatus.PREPARING -> LiquidButton(onClick = onPause, backdrop = backdrop) { Text(stringResource(R.string.download_pause), color = Ink) }
                DownloadStatus.PAUSED, DownloadStatus.CANCELLED -> LiquidButton(onClick = onResume, backdrop = backdrop) { Text(stringResource(R.string.download_resume), color = Ink) }
                DownloadStatus.FAILED, DownloadStatus.UNAVAILABLE -> LiquidButton(onClick = onRetry, backdrop = backdrop) { Text(stringResource(R.string.download_retry), color = Ink) }
                DownloadStatus.QUEUED -> LiquidButton(onClick = onCancel, backdrop = backdrop) { Text(stringResource(R.string.download_cancel), color = Ink) }
            }
        }
        if (record.status == DownloadStatus.DOWNLOADING || record.status == DownloadStatus.PREPARING) {
            LinearProgressIndicator(
                progress = { record.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                formatProgress(record.downloadedBytes, record.totalBytes),
                color = InkDim,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
        }
        if (record.status == DownloadStatus.COMPLETED || record.status == DownloadStatus.FAILED || record.status == DownloadStatus.UNAVAILABLE) {
            LiquidButton(onClick = onDelete, backdrop = backdrop) { Text(stringResource(R.string.download_delete), color = Ink) }
        }
    }
}

@Composable
private fun statusText(status: DownloadStatus): String = stringResource(
    when (status) {
        DownloadStatus.QUEUED -> R.string.download_status_queued
        DownloadStatus.PREPARING -> R.string.download_status_preparing
        DownloadStatus.DOWNLOADING -> R.string.download_status_downloading
        DownloadStatus.PAUSED -> R.string.download_status_paused
        DownloadStatus.COMPLETED -> R.string.download_status_completed
        DownloadStatus.FAILED -> R.string.download_status_failed
        DownloadStatus.CANCELLED -> R.string.download_status_cancelled
        DownloadStatus.UNAVAILABLE -> R.string.download_status_unavailable
    },
)

private fun formatProgress(downloaded: Long, total: Long): String =
    if (total > 0L) "${formatBytes(downloaded)} / ${formatBytes(total)}" else formatBytes(downloaded)

private fun formatBytes(value: Long): String = when {
    value < 1_000L -> "$value B"
    value < 1_000_000L -> "%.1f KB".format(value / 1_000.0)
    value < 1_000_000_000L -> "%.1f MB".format(value / 1_000_000.0)
    else -> "%.2f GB".format(value / 1_000_000_000.0)
}
