package dev.lelonio.square.offline

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import dev.lelonio.square.backend.BackendId
import dev.lelonio.square.data.CatalogTrack

class DownloadManager(
    context: Context,
    maxConcurrent: Int = 2,
) {
    private val appContext = context.applicationContext
    private val state = DownloadStateStore(appContext)
    private val storage = LocalMediaStorage(appContext)
    private val preferences = DownloadPreferences(appContext)
    private val work = WorkManager.getInstance(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueMutex = Mutex()
    private val permits = Semaphore(maxConcurrent.coerceAtLeast(1))
    private val downloader = Downloader()
    private val resolvers = mapOf(BackendId.YOUTUBE_MUSIC to YouTubeDownloadSourceResolver())

    val records: StateFlow<List<DownloadRecord>> = state.records
    val wifiOnly: StateFlow<Boolean> = preferences.wifiOnly
    val quality: StateFlow<DownloadQuality> = preferences.quality

    init { scope.launch { reconcile() } }

    suspend fun enqueue(track: CatalogTrack, collectionId: String? = null): String = queueMutex.withLock {
        val backend = backendFor(track.uri)
        val qualityValue = preferences.quality.value
        val key = DownloadStateStore.jobIdFor(backend, track.uri, qualityValue)
        val existing = state.records.value.firstOrNull { it.jobId == key }
        val now = System.currentTimeMillis()
        val collections = existing?.collectionIds.orEmpty() + listOfNotNull(collectionId)
        val record = when {
            existing != null && existing.status == DownloadStatus.COMPLETED -> existing.copy(track = track, collectionIds = collections, updatedAtEpochMs = now)
            existing != null -> existing.copy(
                track = track,
                status = DownloadStatus.QUEUED,
                errorCode = DownloadErrorCode.NONE,
                errorMessage = null,
                retryCount = 0,
                collectionIds = collections,
                updatedAtEpochMs = now,
            )
            else -> DownloadRecord(
                jobId = key,
                track = track,
                backend = backend,
                remoteRef = track.uri,
                status = DownloadStatus.QUEUED,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
                quality = qualityValue,
                collectionIds = collections,
            )
        }
        state.upsert(record)
        if (record.status != DownloadStatus.COMPLETED) schedule(record.jobId, replace = existing != null)
        key
    }

    suspend fun enqueueTracks(tracks: List<CatalogTrack>, collectionId: String? = null): List<String> = tracks.map { enqueue(it, collectionId) }
    suspend fun enqueueAlbum(albumUri: String, tracks: List<CatalogTrack>): List<String> = enqueueTracks(tracks, "album:$albumUri")
    suspend fun enqueuePlaylist(playlistUri: String, tracks: List<CatalogTrack>): List<String> = enqueueTracks(tracks, "playlist:$playlistUri")
    suspend fun enqueueAll(tracks: List<CatalogTrack>): List<String> = enqueueTracks(tracks, "all")

    suspend fun pause(jobId: String) {
        val record = state.get(jobId) ?: return
        if (record.status == DownloadStatus.COMPLETED) return
        work.cancelUniqueWork(jobId)
        state.upsert(record.copy(status = DownloadStatus.PAUSED, updatedAtEpochMs = System.currentTimeMillis()))
    }

    suspend fun resume(jobId: String) {
        val record = state.get(jobId) ?: return
        if (record.status != DownloadStatus.PAUSED && record.status != DownloadStatus.CANCELLED) return
        state.upsert(record.copy(
            status = DownloadStatus.QUEUED,
            errorCode = DownloadErrorCode.NONE,
            errorMessage = null,
            retryCount = 0,
            updatedAtEpochMs = System.currentTimeMillis(),
        ))
        schedule(jobId, replace = true)
    }

    suspend fun cancel(jobId: String) {
        work.cancelUniqueWork(jobId)
        state.get(jobId)?.let { state.upsert(it.copy(
            status = DownloadStatus.CANCELLED,
            errorCode = DownloadErrorCode.CANCELLED,
            errorMessage = "cancelled by user",
            updatedAtEpochMs = System.currentTimeMillis(),
        )) }
    }

    suspend fun retry(jobId: String) {
        val record = state.get(jobId) ?: return
        if (record.status !in setOf(DownloadStatus.FAILED, DownloadStatus.UNAVAILABLE, DownloadStatus.CANCELLED)) return
        state.upsert(record.copy(
            status = DownloadStatus.QUEUED,
            errorCode = DownloadErrorCode.NONE,
            errorMessage = null,
            retryCount = 0,
            updatedAtEpochMs = System.currentTimeMillis(),
        ))
        schedule(jobId, replace = true)
    }

    suspend fun delete(jobId: String) {
        work.cancelUniqueWork(jobId)
        val record = state.get(jobId) ?: return
        record.localPath?.let { storage.delete(storage.resolveStoredPath(it)) }
        state.remove(jobId)
    }

    suspend fun deletePlaylist(playlistUri: String) {
        val target = "playlist:$playlistUri"
        records.value.filter { target in it.collectionIds }.forEach { record ->
            val remaining = record.collectionIds - target
            if (remaining.isEmpty()) delete(record.jobId)
            else state.upsert(record.copy(collectionIds = remaining, updatedAtEpochMs = System.currentTimeMillis()))
        }
    }

    suspend fun reconcile() = queueMutex.withLock {
        val now = System.currentTimeMillis()
        records.value.forEach { record ->
            if (record.status == DownloadStatus.COMPLETED) {
                val file = record.localPath?.let(storage::resolveStoredPath)
                if (file == null || !storage.validate(file, record.sizeBytes)) {
                    storage.delete(file)
                    state.upsert(record.copy(
                        localPath = null,
                        status = DownloadStatus.FAILED,
                        progress = 0f,
                        downloadedBytes = 0L,
                        sizeBytes = 0L,
                        errorCode = DownloadErrorCode.INVALID_MEDIA,
                        errorMessage = "local media missing or invalid",
                        updatedAtEpochMs = now,
                    ))
                }
            } else if (record.status == DownloadStatus.DOWNLOADING || record.status == DownloadStatus.PREPARING) {
                state.upsert(record.copy(status = DownloadStatus.QUEUED, updatedAtEpochMs = now))
            }
        }
        storage.reconcilePartialFiles(emptySet())
        storage.reconcileFinalFiles(records.value.mapNotNull { it.localPath }.toSet())
        records.value.filter { it.status == DownloadStatus.QUEUED }.forEach { schedule(it.jobId, replace = false) }
    }

    suspend fun available(trackUri: String): OfflineMedia? = withContext(Dispatchers.IO) {
        records.value.firstOrNull { it.remoteRef == trackUri && it.status == DownloadStatus.COMPLETED }?.let { record ->
            val file = record.localPath?.let(storage::resolveStoredPath) ?: return@let null
            if (!storage.validate(file, record.sizeBytes)) return@let null
            OfflineMedia(record.track, file, file.length(), record.quality)
        }
    }

    fun resolveLocalPathSync(trackUri: String): File? = records.value.firstOrNull {
        it.remoteRef == trackUri && it.status == DownloadStatus.COMPLETED
    }?.let { record ->
        val file = record.localPath?.let(storage::resolveStoredPath) ?: return@let null
        file.takeIf { storage.validateSync(it, record.sizeBytes) }
    }

    suspend fun storageUsageBytes(): Long = storage.usageBytes()
    suspend fun freeStorageBytes(): Long = storage.freeBytes()

    fun setWifiOnly(value: Boolean) {
        preferences.setWifiOnly(value)
        scope.launch { reconcile() }
    }

    fun setQuality(value: DownloadQuality) = preferences.setQuality(value)

    internal suspend fun runWorker(jobId: String): WorkerRunResult = permits.withPermit {
        val record = state.get(jobId) ?: return@withPermit WorkerRunResult.DONE
        if (record.status == DownloadStatus.CANCELLED || record.status == DownloadStatus.PAUSED) return@withPermit WorkerRunResult.DONE
        val resolver = resolvers[record.backend] ?: run {
            state.upsert(record.copy(
                status = DownloadStatus.UNAVAILABLE,
                errorCode = DownloadErrorCode.UNSUPPORTED,
                errorMessage = "${record.backend} does not expose an offline-safe download source",
                updatedAtEpochMs = System.currentTimeMillis(),
            ))
            return@withPermit WorkerRunResult.DONE
        }

        try {
            if (storage.freeBytes() < MIN_FREE_BYTES) {
                throw PermanentDownloadException(DownloadErrorCode.STORAGE, "not enough free storage")
            }
            state.upsert(record.copy(status = DownloadStatus.PREPARING, updatedAtEpochMs = System.currentTimeMillis()))
            val source = resolver.resolve(record.track, record.quality)
            val temp = storage.tempFile(record.jobId)
            var lastStorageCheck = 0L
            val result = downloader.download(source, temp) { downloaded, total ->
                if (downloaded - lastStorageCheck >= STORAGE_CHECK_INTERVAL) {
                    lastStorageCheck = downloaded
                    if (storage.freeBytes() < MIN_FREE_BYTES) {
                        throw PermanentDownloadException(DownloadErrorCode.STORAGE, "storage became low during download")
                    }
                }
                val current = state.get(jobId) ?: return@download
                state.upsert(current.copy(
                    status = DownloadStatus.DOWNLOADING,
                    progress = if (total > 0) downloaded.toFloat() / total else 0f,
                    downloadedBytes = downloaded,
                    totalBytes = total,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ))
            }
            val target = storage.finalFile(record.logicalKey(), result.extension)
            if (!storage.validate(temp, result.totalBytes)) {
                storage.delete(temp)
                throw PermanentDownloadException(DownloadErrorCode.INVALID_MEDIA, "downloaded media failed validation")
            }
            storage.finalize(temp, target)
            if (!storage.validate(target, result.totalBytes)) {
                storage.delete(target)
                throw PermanentDownloadException(DownloadErrorCode.INVALID_MEDIA, "final media failed validation")
            }
            state.upsert(record.copy(
                localPath = target.relativeTo(storage.root).path,
                status = DownloadStatus.COMPLETED,
                progress = 1f,
                downloadedBytes = result.bytes,
                totalBytes = result.totalBytes,
                sizeBytes = result.bytes,
                errorCode = DownloadErrorCode.NONE,
                errorMessage = null,
                updatedAtEpochMs = System.currentTimeMillis(),
            ))
            WorkerRunResult.DONE
        } catch (error: PermanentDownloadException) {
            state.upsert(record.copy(
                status = if (error.code == DownloadErrorCode.UNSUPPORTED) DownloadStatus.UNAVAILABLE else DownloadStatus.FAILED,
                errorCode = error.code,
                errorMessage = error.message?.take(240),
                updatedAtEpochMs = System.currentTimeMillis(),
            ))
            WorkerRunResult.DONE
        } catch (error: TransientDownloadException) {
            val retry = record.retryCount + 1
            if (!DownloadPolicy.shouldRetry(retry)) {
                state.upsert(record.copy(
                    status = DownloadStatus.FAILED,
                    retryCount = retry,
                    errorCode = DownloadErrorCode.NETWORK,
                    errorMessage = error.message?.take(240),
                    updatedAtEpochMs = System.currentTimeMillis(),
                ))
                WorkerRunResult.DONE
            } else {
                state.upsert(record.copy(
                    status = DownloadStatus.QUEUED,
                    retryCount = retry,
                    errorCode = DownloadErrorCode.NETWORK,
                    errorMessage = error.message?.take(240),
                    updatedAtEpochMs = System.currentTimeMillis(),
                ))
                WorkerRunResult.RETRY
            }
        }
    }

    private suspend fun schedule(jobId: String, replace: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (preferences.wifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_JOB_ID to jobId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        work.enqueueUniqueWork(jobId, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    private fun backendFor(uri: String): BackendId = when {
        uri.startsWith(dev.lelonio.square.backend.youtube.YouTubeBackend.URI_SCHEME) -> BackendId.YOUTUBE_MUSIC
        uri.startsWith("spotify:") -> BackendId.SPOTIFY
        else -> throw UnsupportedDownloadException("no download backend owns $uri")
    }

    internal enum class WorkerRunResult { DONE, RETRY }

    private companion object {
        const val MIN_FREE_BYTES = 50L * 1024L * 1024L
        const val STORAGE_CHECK_INTERVAL = 4L * 1024L * 1024L
    }
}
