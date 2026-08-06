package dev.lelonio.square.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Resolved track lists, kept on disk between runs.
 *
 * The in-memory cache above this only helps while the app is alive, and a
 * playlist of a thousand tracks is a dozen Web API pages: every cold start paid
 * for all of them, and the user watched the rows arrive a hundred at a time.
 *
 * Spotify stamps a playlist with a `snapshot_id` that changes whenever its
 * contents do, which is what makes a cache honest here rather than a gamble: one
 * small request says whether the twelve big ones are needed at all. Albums have
 * no such stamp and need none — an album does not gain tracks.
 *
 * One file per context. A single blob would have to be rewritten in full every
 * time any playlist changed, and would be read in full to open one.
 */
class ContextCacheStore(context: Context) {

    @Serializable
    data class Entry(
        val tracks: List<CatalogTrack>,
        /** Spotify's stamp for the version this was read from; null for albums. */
        val snapshotId: String? = null,
        val savedAt: Long = 0,
    )

    private val dir = File(context.applicationContext.cacheDir, DIR_NAME)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun read(uri: String): Entry? = withContext(Dispatchers.IO) {
        val file = fileFor(uri)
        if (!file.exists()) return@withContext null
        // A half-written or outdated file is not worth a crash: dropping it
        // costs one reload, and the next save repairs it.
        runCatching { json.decodeFromString(Entry.serializer(), file.readText()) }
            .onFailure { file.delete() }
            .getOrNull()
            ?.takeIf { it.savedAt > System.currentTimeMillis() - MAX_AGE_MS }
    }

    suspend fun write(uri: String, tracks: List<CatalogTrack>, snapshotId: String?) =
        withContext(Dispatchers.IO) {
            if (tracks.isEmpty()) return@withContext
            dir.mkdirs()
            val entry = Entry(tracks, snapshotId, System.currentTimeMillis())
            runCatching { fileFor(uri).writeText(json.encodeToString(Entry.serializer(), entry)) }
            trim()
        }

    suspend fun remove(uri: String) = withContext(Dispatchers.IO) {
        fileFor(uri).delete()
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        dir.deleteRecursively()
        Unit
    }

    /** Oldest files go first, so a browsing session cannot fill the disk. */
    private fun trim() {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_ENTRIES).forEach { it.delete() }
    }

    // Hashed rather than sanitised: a URI is not a legal file name, and any
    // escaping scheme would have to survive URIs that differ only in a character
    // it escapes.
    private fun fileFor(uri: String) = File(dir, "${uri.hashCode().toUInt().toString(16)}.json")

    private companion object {
        const val DIR_NAME = "contexts"
        const val MAX_ENTRIES = 40

        /**
         * Old entries are dropped rather than trusted indefinitely.
         *
         * The snapshot check covers playlists exactly, but an album can be
         * relinked and an artist's top tracks change with no stamp to notice it
         * by, so nothing is kept for more than a week.
         */
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
