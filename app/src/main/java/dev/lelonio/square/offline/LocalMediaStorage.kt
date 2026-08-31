package dev.lelonio.square.offline

import android.content.Context
import android.media.MediaMetadataRetriever
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalMediaStorage(context: Context) {
    val root: File = context.applicationContext.filesDir.resolve("offline-media")
    private val partialRoot = root.resolve(".partial")

    suspend fun validate(file: File, expectedBytes: Long = -1L): Boolean = withContext(Dispatchers.IO) {
        validateSync(file, expectedBytes)
    }

    fun validateSync(file: File, expectedBytes: Long = -1L): Boolean {
        if (!isInsideRoot(file) || !file.isFile || file.length() <= 0L) return false
        if (expectedBytes > 0L && file.length() != expectedBytes) return false
        return runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.let { it > 0L } ?: false
            }
        }.getOrDefault(false)
    }

    fun finalFile(logicalKey: String, extension: String): File {
        val safeExtension = extension.removePrefix(".").lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
        return root.resolve("${sha256(logicalKey)}.$safeExtension")
    }

    fun tempFile(jobId: String): File {
        require(jobId.matches(Regex("[a-fA-F0-9-]{8,64}"))) { "invalid job id" }
        partialRoot.mkdirs()
        return partialRoot.resolve("$jobId.part")
    }

    fun resolveStoredPath(relativePath: String): File? {
        if (relativePath.isBlank() || relativePath.contains('\\') || File(relativePath).isAbsolute) return null
        val candidate = root.resolve(relativePath)
        return candidate.takeIf {
            isInsideRoot(it) && it != root && !it.toPath().startsWith(partialRoot.toPath())
        }
    }

    suspend fun finalize(temp: File, target: File) = withContext(Dispatchers.IO) {
        require(isInsidePartial(temp)) { "temporary file outside managed partial directory" }
        require(isInsideRoot(target) && !target.toPath().startsWith(partialRoot.toPath())) {
            "destination outside managed media directory"
        }
        root.mkdirs()
        target.parentFile?.mkdirs()
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    suspend fun delete(file: File?) = withContext(Dispatchers.IO) {
        if (file == null) return@withContext
        require(isInsideRoot(file)) { "refusing to delete outside managed media directory" }
        file.delete()
    }

    suspend fun reconcilePartialFiles(activeJobIds: Set<String>) = withContext(Dispatchers.IO) {
        if (!partialRoot.isDirectory) return@withContext
        partialRoot.listFiles().orEmpty().forEach { file ->
            val jobId = file.name.removeSuffix(".part")
            if (jobId !in activeJobIds) file.delete()
        }
    }

    suspend fun reconcileFinalFiles(knownRelativePaths: Set<String>) = withContext(Dispatchers.IO) {
        if (!root.isDirectory) return@withContext
        root.walkTopDown()
            .filter { it.isFile && !it.toPath().startsWith(partialRoot.toPath()) }
            .forEach { file ->
                val relative = file.relativeTo(root).path
                if (relative !in knownRelativePaths) file.delete()
            }
    }

    suspend fun usageBytes(): Long = withContext(Dispatchers.IO) {
        root.walkTopDown()
            .filter { it.isFile && !it.toPath().startsWith(partialRoot.toPath()) }
            .sumOf { it.length() }
    }

    suspend fun freeBytes(): Long = withContext(Dispatchers.IO) { root.parentFile?.usableSpace ?: 0L }

    fun isInsideRoot(file: File): Boolean = canonicalInside(file, root)
    private fun isInsidePartial(file: File): Boolean = canonicalInside(file, partialRoot)

    private fun canonicalInside(file: File, base: File): Boolean = runCatching {
        file.canonicalFile.toPath().startsWith(base.canonicalFile.toPath())
    }.getOrDefault(false)

    companion object {
        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
