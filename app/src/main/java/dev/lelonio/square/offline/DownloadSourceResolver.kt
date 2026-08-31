package dev.lelonio.square.offline

import dev.lelonio.square.backend.BackendId
import dev.lelonio.square.data.CatalogTrack

interface DownloadSourceResolver {
    val backend: BackendId
    suspend fun resolve(track: CatalogTrack, quality: DownloadQuality): ResolvedDownloadSource
}

class UnsupportedDownloadException(message: String) : Exception(message)
class PermanentDownloadException(val code: DownloadErrorCode, message: String) : Exception(message)
class TransientDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)
