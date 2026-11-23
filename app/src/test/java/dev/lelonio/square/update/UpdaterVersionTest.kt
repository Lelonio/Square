package dev.lelonio.square.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterVersionTest {
    private fun newer(candidate: String, installed: String) = Updater.isNewer(candidate, installed)

    @Test fun releaseIsNewerThanItsBeta() = assertTrue(newer("2.11.0", "2.11.0-beta1"))
    @Test fun betaIsOlderThanItsRelease() = assertFalse(newer("2.11.0-beta1", "2.11.0"))
    @Test fun laterBetaIsNewer() = assertTrue(newer("2.11.0-beta2", "2.11.0-beta1"))
    @Test fun betaTenAfterBetaNine() = assertTrue(newer("2.11.0-beta10", "2.11.0-beta9"))
    @Test fun sameVersionIsNotNewer() = assertFalse(newer("2.11.0", "2.11.0"))
    @Test fun nextPatchIsNewerThanBeta() = assertTrue(newer("2.11.1", "2.11.0-beta1"))
    @Test fun numericNotString() = assertTrue(newer("2.10.0", "2.9.4"))
    @Test fun olderReleaseIsNotNewer() = assertFalse(newer("2.10.4", "2.11.0-beta1"))
    @Test fun tagPrefixIgnored() = assertTrue(newer("v2.11.0", "2.11.0-beta1"))
}
