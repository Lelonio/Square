package dev.lelonio.square.offline

import dev.lelonio.square.data.CatalogTrack
import kotlinx.coroutines.flow.StateFlow

class OfflineLibrary(private val downloads: DownloadManager) {
    val downloadsState: StateFlow<List<DownloadRecord>> = downloads.records

    suspend fun available(trackUri: String): OfflineMedia? = downloads.available(trackUri)

    suspend fun delete(trackUri: String) {
        downloads.records.value.firstOrNull { it.remoteRef == trackUri }?.let { downloads.delete(it.jobId) }
    }

    suspend fun deletePlaylist(playlistUri: String) = downloads.deletePlaylist(playlistUri)

    suspend fun usageBytes(): Long = downloads.storageUsageBytes()
    suspend fun freeBytes(): Long = downloads.freeStorageBytes()

    fun isDownloaded(track: CatalogTrack): Boolean =
        downloads.records.value.any { it.remoteRef == track.uri && it.status == DownloadStatus.COMPLETED }
}
