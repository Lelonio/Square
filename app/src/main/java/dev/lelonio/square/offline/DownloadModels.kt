package dev.lelonio.square.offline

import dev.lelonio.square.backend.BackendId
import dev.lelonio.square.data.CatalogTrack
import kotlinx.serialization.Serializable

@Serializable
enum class DownloadStatus {
    QUEUED,
    PREPARING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
    UNAVAILABLE,
}

@Serializable
enum class DownloadErrorCode {
    NONE,
    NETWORK,
    HTTP,
    AUTHORIZATION,
    UNSUPPORTED,
    INVALID_MEDIA,
    STORAGE,
    MALFORMED_RESPONSE,
    CANCELLED,
    UNKNOWN,
}

@Serializable
enum class DownloadQuality(val maxBitrateKbps: Int) {
    LOW(128),
    STANDARD(192),
    HIGH(256),
    MAX(320),
}

@Serializable
data class DownloadRecord(
    val jobId: String,
    val track: CatalogTrack,
    val backend: BackendId,
    val remoteRef: String,
    val localPath: String? = null,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val sizeBytes: Long = 0L,
    val errorCode: DownloadErrorCode = DownloadErrorCode.NONE,
    val errorMessage: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val quality: DownloadQuality = DownloadQuality.STANDARD,
    val retryCount: Int = 0,
    val collectionIds: Set<String> = emptySet(),
)

data class ResolvedDownloadSource(
    val url: String,
    val contentTypeHint: String? = null,
    val extensionHint: String? = null,
)

data class OfflineMedia(
    val track: CatalogTrack,
    val path: java.io.File,
    val sizeBytes: Long,
    val quality: DownloadQuality,
)

fun DownloadRecord.logicalKey(): String =
    "${backend.name}|$remoteRef|${quality.name}"
