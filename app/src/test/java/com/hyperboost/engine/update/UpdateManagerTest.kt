package com.hyperboost.engine.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun compareVersions_identicalVersions_returnsZero() {
        assertEquals(0, UpdateManager.compareVersions("1.3.7", "1.3.7"))
        assertEquals(0, UpdateManager.compareVersions("v1.3.7", "1.3.7"))
        assertEquals(0, UpdateManager.compareVersions("v1.3.7", "v1.3.7"))
    }

    @Test
    fun compareVersions_newerVersion_returnsPositive() {
        assertTrue(UpdateManager.compareVersions("1.3.8", "1.3.7") > 0)
        assertTrue(UpdateManager.compareVersions("1.4.0", "1.3.7") > 0)
        assertTrue(UpdateManager.compareVersions("2.0.0", "1.9.9") > 0)
    }

    @Test
    fun compareVersions_olderVersion_returnsNegative() {
        assertTrue(UpdateManager.compareVersions("1.3.6", "1.3.7") < 0)
        assertTrue(UpdateManager.compareVersions("1.2.9", "1.3.0") < 0)
        assertTrue(UpdateManager.compareVersions("1.0.0", "1.0.1") < 0)
    }

    @Test
    fun compareVersions_prereleaseVersions() {
        // A release candidate/prerelease is considered older than final release
        assertTrue(UpdateManager.compareVersions("1.3.8-beta", "1.3.8") < 0)
        assertTrue(UpdateManager.compareVersions("1.3.8", "1.3.8-beta") > 0)
    }

    @Test
    fun compatibilityResult_types() {
        val compatible = CompatibilityResult.Compatible
        val cannotVerify = CompatibilityResult.CannotVerify
        val incompatible = CompatibilityResult.Incompatible("abc123installed", "def456update")

        assertEquals(CompatibilityResult.Compatible, compatible)
        assertEquals(CompatibilityResult.CannotVerify, cannotVerify)
        assertEquals("abc123installed", incompatible.installedCertSha256)
        assertEquals("def456update", incompatible.updateCertSha256)
    }
}
