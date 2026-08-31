package dev.lelonio.square.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadPolicyTest {
    @Test
    fun transientHttpFailuresAreRetryableButClientErrorsAreNot() {
        assertTrue(DownloadPolicy.isRetryableHttp(408))
        assertTrue(DownloadPolicy.isRetryableHttp(429))
        assertTrue(DownloadPolicy.isRetryableHttp(503))
        assertFalse(DownloadPolicy.isRetryableHttp(401))
        assertFalse(DownloadPolicy.isRetryableHttp(404))
    }

    @Test
    fun automaticRetryIsBounded() {
        assertTrue(DownloadPolicy.shouldRetry(1))
        assertTrue(DownloadPolicy.shouldRetry(3))
        assertFalse(DownloadPolicy.shouldRetry(4))
    }

    @Test
    fun contentTypesHaveControlledExtensions() {
        assertEquals("webm", DownloadPolicy.extensionForContentType("audio/webm"))
        assertEquals("m4a", DownloadPolicy.extensionForContentType("audio/mp4"))
        assertEquals("flac", DownloadPolicy.extensionForContentType("audio/flac"))
        assertNull(DownloadPolicy.extensionForContentType("application/octet-stream"))
    }
}
