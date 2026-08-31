package dev.lelonio.square.offline

import dev.lelonio.square.backend.BackendId
import dev.lelonio.square.data.CatalogTrack

/**
 * Download capability gate for YouTube Music.
 *
 * The existing player can resolve short-lived stream URLs for online playback,
 * but that is not an authorization to turn those URLs into permanent offline
 * copies. YouTube's current Terms restrict downloading except where expressly
 * authorized by the service or with permission. The public backend in this
 * repository does not expose an authorized offline-media reference, so this
 * resolver deliberately fails closed.
 */
class YouTubeDownloadSourceResolver : DownloadSourceResolver {
    override val backend: BackendId = BackendId.YOUTUBE_MUSIC

    override suspend fun resolve(track: CatalogTrack, quality: DownloadQuality): ResolvedDownloadSource {
        throw UnsupportedDownloadException(
            "YouTube Music does not expose an authorized offline download source to this backend",
        )
    }
}
