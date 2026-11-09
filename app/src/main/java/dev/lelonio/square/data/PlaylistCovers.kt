package dev.lelonio.square.data

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Covers the listener chose for playlists whose service takes none (#157).
 *
 * YouTube Music renames a playlist but has no way to give it a picture, so the
 * one picked in the edit sheet is kept here, on this phone, and shown wherever
 * the playlist is. Spotify takes the picture itself and never comes here.
 *
 * One file per playlist, named by a hash of its URI and the moment it was
 * saved: a new name for every new picture, so the image loader never shows the
 * old one from its cache.
 */
object PlaylistCovers {

    private var directory: File? = null
    private val covers = java.util.concurrent.ConcurrentHashMap<String, File>()

    fun attach(context: Context) {
        val dir = File(context.applicationContext.filesDir, "playlist-covers").apply { mkdirs() }
        directory = dir
        dir.listFiles().orEmpty()
            .filter { it.name.endsWith(".jpg") }
            .groupBy { it.name.substringBefore('-') }
            .forEach { (key, files) -> files.maxByOrNull { it.name }?.let { covers[key] = it } }
    }

    /** The chosen cover as a file URI, or null where none was chosen. */
    fun coverFor(uri: String): String? = covers[key(uri)]?.takeIf { it.exists() }?.let { "file://" + it.absolutePath }

    /** Each playlist with its chosen cover, where it has one. */
    fun apply(playlists: List<CatalogPlaylist>): List<CatalogPlaylist> =
        if (covers.isEmpty()) playlists else playlists.map { playlist ->
            coverFor(playlist.uri)?.let { playlist.copy(artworkUrl = it) } ?: playlist
        }

    /** Keeps [jpeg] as the playlist's cover, in place of any before it; returns its file URI. */
    fun save(uri: String, jpeg: ByteArray): String? {
        val dir = directory ?: return null
        val key = key(uri)
        val file = File(dir, "$key-${System.currentTimeMillis()}.jpg")
        file.writeBytes(jpeg)
        covers.put(key, file)?.takeIf { it != file }?.delete()
        return "file://" + file.absolutePath
    }

    /** Forgets the playlist's cover, as when the playlist is deleted. */
    fun forget(uri: String) {
        covers.remove(key(uri))?.delete()
    }

    private fun key(uri: String): String =
        MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
