package dev.lelonio.square.offline

import dev.lelonio.square.backend.BackendId
import dev.lelonio.square.backend.youtube.YouTubeBackend
import dev.lelonio.square.backend.youtube.YouTubeStreamResolver
import dev.lelonio.square.data.CatalogTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class YouTubeDownloadSourceResolver : DownloadSourceResolver {
    override val backend: BackendId = BackendId.YOUTUBE_MUSIC

    override suspend fun resolve(track: CatalogTrack, quality: DownloadQuality): ResolvedDownloadSource =
        withContext(Dispatchers.IO) {
            if (!track.uri.startsWith(YouTubeBackend.TRACK_PREFIX)) {
                throw PermanentDownloadException(
                    DownloadErrorCode.UNSUPPORTED,
                    "track is not owned by YouTube Music",
                )
            }
            val stream = runCatching {
                YouTubeStreamResolver.resolveAudioUrl(track.uri, quality.maxBitrateKbps)
            }.getOrElse { error ->
                throw TransientDownloadException("could not resolve YouTube audio", error)
            }
            ResolvedDownloadSource(
                url = stream.url,
                contentTypeHint = stream.contentType,
                extensionHint = stream.extension,
            )
        }
}
