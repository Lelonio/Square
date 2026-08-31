package dev.lelonio.square.offline

import dev.lelonio.square.backend.BackendId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DownloadIdentityTest {
    @Test
    fun sameTrackAndQualityShareOneStableJobId() {
        val first = DownloadStateStore.jobIdFor(BackendId.YOUTUBE_MUSIC, "ytmusic:track:abc", DownloadQuality.HIGH)
        val second = DownloadStateStore.jobIdFor(BackendId.YOUTUBE_MUSIC, "ytmusic:track:abc", DownloadQuality.HIGH)
        assertEquals(first, second)
    }

    @Test
    fun qualityAndBackendArePartOfDownloadIdentity() {
        val high = DownloadStateStore.jobIdFor(BackendId.YOUTUBE_MUSIC, "ytmusic:track:abc", DownloadQuality.HIGH)
        val low = DownloadStateStore.jobIdFor(BackendId.YOUTUBE_MUSIC, "ytmusic:track:abc", DownloadQuality.LOW)
        val spotify = DownloadStateStore.jobIdFor(BackendId.SPOTIFY, "ytmusic:track:abc", DownloadQuality.HIGH)
        assertNotEquals(high, low)
        assertNotEquals(high, spotify)
    }
}
