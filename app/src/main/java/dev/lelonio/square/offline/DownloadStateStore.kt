package dev.lelonio.square.offline

import android.content.Context
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class DownloadState(val records: List<DownloadRecord> = emptyList())

class DownloadStateStore(context: Context) {
    private val root = context.applicationContext.filesDir.resolve("downloads")
    private val stateFile = root.resolve("state.json")
    private val tempFile = root.resolve("state.json.tmp")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _records = MutableStateFlow(loadFromDisk())

    val records: StateFlow<List<DownloadRecord>> = _records.asStateFlow()

    suspend fun upsert(record: DownloadRecord) = mutate { current ->
        val index = current.indexOfFirst { it.jobId == record.jobId }
        if (index < 0) current + record else current.toMutableList().also { it[index] = record }
    }

    suspend fun remove(jobId: String) = mutate { current -> current.filterNot { it.jobId == jobId } }

    suspend fun replaceAll(records: List<DownloadRecord>) = mutate { records.distinctBy { it.jobId } }

    suspend fun get(jobId: String): DownloadRecord? = records.value.firstOrNull { it.jobId == jobId }

    private suspend fun mutate(transform: (List<DownloadRecord>) -> List<DownloadRecord>) {
        mutex.withLock {
            val next = transform(_records.value).sortedBy { it.createdAtEpochMs }
            withContext(Dispatchers.IO) {
                root.mkdirs()
                tempFile.outputStream().use { stream ->
                    stream.writer(Charsets.UTF_8).use { writer ->
                        writer.write(json.encodeToString(DownloadState.serializer(), DownloadState(next)))
                    }
                    stream.fd.sync()
                }
                try {
                    Files.move(
                        tempFile.toPath(),
                        stateFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        tempFile.toPath(),
                        stateFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            }
            _records.value = next
        }
    }

    private fun loadFromDisk(): List<DownloadRecord> = runCatching {
        if (!stateFile.isFile) return emptyList()
        json.decodeFromString<DownloadState>(stateFile.readText()).records
            .distinctBy { it.jobId }
            .sortedBy { it.createdAtEpochMs }
    }.getOrElse { emptyList() }

    companion object {
        fun jobIdFor(backend: dev.lelonio.square.backend.BackendId, uri: String, quality: DownloadQuality): String =
            sha256("${backend.name}|$uri|${quality.name}")

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
