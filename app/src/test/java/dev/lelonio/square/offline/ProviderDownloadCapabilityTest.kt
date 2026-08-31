package dev.lelonio.square.offline

import dev.lelonio.square.data.CatalogTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class ProviderDownloadCapabilityTest {
    @Test
    fun youtubeFailsClosedWithoutAuthorizedOfflineSource() = runBlocking {
        val resolver = YouTubeDownloadSourceResolver()
        val error = assertFailsWith<PermanentDownloadException> {
            resolver.resolve(CatalogTrack(uri = "ytmusic:track:abc", name = "x", artist = "y"), DownloadQuality.STANDARD)
        }
        assertEquals(DownloadErrorCode.UNSUPPORTED, error.code)
    }
}
