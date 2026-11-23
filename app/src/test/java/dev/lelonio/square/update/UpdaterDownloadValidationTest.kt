package dev.lelonio.square.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterDownloadValidationTest {

    @Test
    fun acceptsAnExactNonEmptyAssetLength() {
        assertTrue(isCompleteApkDownload(expectedBytes = 1024, actualBytes = 1024))
    }

    @Test
    fun rejectsTruncatedOrOversizedAssets() {
        assertFalse(isCompleteApkDownload(expectedBytes = 1024, actualBytes = 900))
        assertFalse(isCompleteApkDownload(expectedBytes = 1024, actualBytes = 2048))
    }

    @Test
    fun acceptsACompleteNonEmptyDownloadWhenLengthIsUnknown() {
        assertTrue(isCompleteApkDownload(expectedBytes = -1, actualBytes = 512))
        assertTrue(isCompleteApkDownload(expectedBytes = 0, actualBytes = 512))
    }

    @Test
    fun rejectsEmptyDownloadsEvenWhenLengthIsUnknown() {
        assertFalse(isCompleteApkDownload(expectedBytes = -1, actualBytes = 0))
        assertFalse(isCompleteApkDownload(expectedBytes = 0, actualBytes = 0))
    }
}
