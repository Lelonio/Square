package dev.lelonio.square.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The audio already on the phone.
 *
 * Square hands downloading to whatever app the user has for it, so what comes
 * back is a file somewhere on the device rather than anything this app placed.
 * MediaStore is therefore the only sensible way to find it: it answers "what
 * audio is on this phone", which is the actual question, while a hardcoded
 * folder answers "what audio is where I guessed", and every downloader lets its
 * user change that.
 *
 * It also means the tags the downloader wrote are already parsed. Title, artist,
 * album, duration and the embedded cover come back from the index rather than
 * from this app opening files and reading headers.
 *
 * Visible whichever source is selected. A file on the phone is not a third
 * streaming service to switch to; it is there in both.
 */
object LocalLibrary {

    /** The URI scheme local tracks carry, so every screen can tell them apart. */
    const val PREFIX = "local:track:"

    /**
     * The list itself, which is a context like any playlist.
     *
     * It is not one on any service, so it has no id to be addressed by: this is
     * a name the app gives its own shelf, and everything that opens a track
     * list recognises it the same way it recognises a playlist URI.
     */
    const val CONTEXT_URI = "local:files"

    fun isLocalContext(uri: String?): Boolean = uri == CONTEXT_URI

    /**
     * What the shelf carries where a playlist carries a cover URL.
     *
     * Not a URL at all: there is no picture to fetch for a folder of files, and
     * the tile is drawn by the app at whatever size it is asked for. See
     * Artwork, which recognises this and hands over to its own drawing.
     */
    const val COVER = "square:local-files-cover"

    /**
     * The permission needed to read any of this.
     *
     * Split at Android 13, where the single storage permission was broken up by
     * media type. Asking for audio alone is the whole point of the newer one:
     * this app has no business seeing photographs.
     */
    val permission: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun idOfUri(uri: String): Long = uri.removePrefix(PREFIX).toLongOrNull() ?: -1L

    fun isLocal(uri: String): Boolean = uri.startsWith(PREFIX)

    /**
     * The content URI a player can open.
     *
     * Built back from the id rather than stored: a `content://` URI is only
     * meaningful while the row it points at exists, and keeping one in a saved
     * queue would be keeping a handle to a file that may since have been
     * deleted.
     */
    fun contentUri(uri: String): android.net.Uri =
        ContentUris.withAppendedId(collection(), idOfUri(uri))

    /**
     * Everything the index calls music, newest first.
     *
     * Newest first because these arrive by download: what was fetched last is
     * what the user came looking for, and alphabetical order would bury it.
     */
    suspend fun tracks(context: Context): List<CatalogTrack> = withContext(Dispatchers.IO) {
        if (!granted(context)) return@withContext emptyList()

        val columns = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
        )

        runCatching {
            context.contentResolver.query(
                collection(),
                columns,
                // Ringtones, notification sounds and voice recordings are all
                // in here too, and none of them belong in a music library.
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val added = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            CatalogTrack(
                                uri = PREFIX + cursor.getLong(id),
                                name = cursor.getString(title).orEmpty(),
                                // MediaStore says "<unknown>" for an untagged
                                // file, which is worse than saying nothing.
                                artist = cursor.getString(artist)
                                    ?.takeUnless { it == MediaStore.UNKNOWN_STRING }
                                    .orEmpty(),
                                album = cursor.getString(album).orEmpty(),
                                durationMs = cursor.getLong(duration),
                                artworkUrl = artworkUri(cursor.getLong(albumId)),
                                // The same field a playlist carries, so the
                                // "recently added" order works here too. The
                                // index counts in seconds; everything else in
                                // this app reads an ISO-8601 stamp.
                                addedAt = isoStamp(cursor.getLong(added)),
                            ),
                        )
                    }
                }
            }.orEmpty()
        }.getOrElse {
            android.util.Log.w(TAG, "could not read the media store: $it")
            emptyList()
        }
    }

    /**
     * The album art, as something the image loader can fetch.
     *
     * The album's artwork rather than the file's own embedded picture: the
     * former is one URI the loader can cache, and the latter would mean opening
     * and decoding every file to build a list.
     */
    /** Seconds since the epoch, as the rest of the app spells a date. */
    private fun isoStamp(seconds: Long): String? =
        if (seconds <= 0) {
            null
        } else {
            java.time.Instant.ofEpochSecond(seconds).toString()
        }

    private fun artworkUri(albumId: Long): String? =
        if (albumId <= 0) null else "content://media/external/audio/albumart/$albumId"

    private fun collection() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

    private const val TAG = "LocalLibrary"
}
