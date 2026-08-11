package dev.lelonio.square.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.lelonio.square.backend.youtube.YouTubeBackend
import dev.lelonio.square.data.CatalogPlaylist

/**
 * Downloading, handed to whoever the user already trusts with it.
 *
 * Square does not fetch the file. Two attempts went the other way and both are
 * worth recording, because the reasons are the argument for this one.
 *
 * The first fetched the audio stream itself. It worked and it was not worth
 * having: one file at a time with no resuming, no notification while it ran, no
 * way to queue a playlist, no tags written into what came out, and nowhere in
 * the app to find the results. Most of that is not extraction, it is a download
 * manager, and writing a second-rate one inside a music player is a permanent
 * cost for a feature other people have finished.
 *
 * The second was going to bundle yt-dlp through youtubedl-android, which is
 * what the good downloaders use. Measured before writing any logic: the three
 * modules alone take the APK from 22.3 MB to 81.0 MB, most of it ffmpeg at
 * 35.6 MB and Python at 14.3 MB. That is roughly four times the whole app, paid
 * by everyone on every update, since the in-app updater fetches the APK whole.
 *
 * So the app sends a link. Where it goes is answered by what is installed.
 */

/** The public address of a playlist, album or artist, for something else to open. */
fun CatalogPlaylist.openLink(): String {
    val id = uri.substringAfterLast(':')
    return when {
        uri.startsWith(YouTubeBackend.PLAYLIST_PREFIX) ->
            "https://music.youtube.com/playlist?list=$id"
        uri.startsWith(YouTubeBackend.ARTIST_PREFIX) ->
            "https://music.youtube.com/channel/$id"
        uri.startsWith("spotify:album:") -> "https://open.spotify.com/album/$id"
        uri.startsWith("spotify:artist:") -> "https://open.spotify.com/artist/$id"
        else -> "https://open.spotify.com/playlist/$id"
    }
}

/**
 * The downloader this app hands links to.
 *
 * Named, rather than "whatever takes a link". The menu says YTDLnis and means
 * it: a chooser in front of someone who has already installed a downloader is
 * asking them to confirm a decision they made when they installed it, and a
 * chooser in front of someone who has not is a list of apps that cannot do the
 * job. Being specific lets the entry answer both cases properly.
 *
 * Must be declared in the manifest's `queries` block or it is invisible from
 * Android 11 on, and the lookup below can never find it.
 */
private const val YTDLNIS = "com.deniscerri.ytdl"

/**
 * Where to get it.
 *
 * F-Droid rather than the Play Store, where it is not and cannot be: it is
 * built on yt-dlp. The project lists GitHub releases, F-Droid and IzzyOnDroid
 * as its only trusted sources; this is the one that is an install page rather
 * than a list of files.
 */
private const val YTDLNIS_PAGE = "https://f-droid.org/en/packages/com.deniscerri.ytdl"

/** Whether the link will go straight there, or to the install page instead. */
fun downloaderInstalled(context: Context): Boolean =
    runCatching {
        context.packageManager.getPackageInfo(YTDLNIS, PackageManager.GET_ACTIVITIES)
    }.isSuccess

/**
 * Sends the link to YTDLnis, or offers to go and install it.
 *
 * Installed is not enough on its own to send straight in: an app can be present
 * and not handle the intent, and `setPackage` to one that does not throws
 * rather than falling back. The resolve is what makes the direct path safe.
 */
fun sendForDownload(context: Context, url: String) {
    val share = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, url)

    val handled = context.packageManager
        .queryIntentActivities(Intent(share).setPackage(YTDLNIS), 0)
        .isNotEmpty()

    val intent = if (handled) {
        Intent(share).setPackage(YTDLNIS)
    } else {
        // Nothing is downloaded here and nothing is installed behind the user's
        // back: this opens the page and stops, and the rest is their decision.
        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(YTDLNIS_PAGE))
    }

    runCatching { context.startActivity(intent) }
        .onFailure { android.util.Log.w(TAG, "could not hand off the link: $it") }
}

private const val TAG = "DownloadHandoff"
