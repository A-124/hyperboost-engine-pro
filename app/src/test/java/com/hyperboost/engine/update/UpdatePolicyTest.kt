package com.hyperboost.engine.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The policy file is the only part of the update system that can change behaviour without shipping
 * an APK, so a parsing mistake there either blocks everyone or blocks nobody. Robolectric is used
 * here because `org.json` is a stub in plain JVM unit tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdatePolicyTest {

    @Test
    fun `reads every field the app uses`() {
        val policy = UpdateManager.parsePolicy(
            """
            {
              "minimumSupportedVersion": "1.7.3",
              "downloadPage": "https://example.com/download",
              "blockedVersions": ["1.7.3", "v1.6.9"],
              "blockedVersionCodes": [100042],
              "notice": "Reinstall from the release page."
            }
            """.trimIndent()
        )

        assertEquals("1.7.3", policy.minimumSupportedVersion)
        assertEquals("https://example.com/download", policy.downloadPage)
        assertEquals(listOf("1.7.3", "v1.6.9"), policy.blockedVersions)
        assertEquals(listOf(100042), policy.blockedVersionCodes)
        assertEquals("Reinstall from the release page.", policy.notice)
    }

    @Test
    fun `broken policy falls back to permissive defaults`() {
        // A policy that cannot be parsed must never block an update or a launch.
        listOf("", "not json", "{", "null").forEach { raw ->
            val policy = UpdateManager.parsePolicy(raw)
            assertEquals(UpdateManager.DEFAULT_MINIMUM_VERSION, policy.minimumSupportedVersion)
            assertEquals(UpdateManager.DEFAULT_DOWNLOAD_PAGE, policy.downloadPage)
            assertTrue(policy.blockedVersions.isEmpty())
            assertTrue(policy.blockedVersionCodes.isEmpty())
            assertNull(policy.notice)
        }
    }

    @Test
    fun `missing fields fall back without losing the ones present`() {
        val policy = UpdateManager.parsePolicy("""{"minimumSupportedVersion":"1.8.0"}""")
        assertEquals("1.8.0", policy.minimumSupportedVersion)
        assertEquals(UpdateManager.DEFAULT_DOWNLOAD_PAGE, policy.downloadPage)
    }

    @Test
    fun `non-https download page is ignored`() {
        // The value is handed to an implicit browser intent, so only https is accepted.
        val policy = UpdateManager.parsePolicy("""{"downloadPage":"javascript:alert(1)"}""")
        assertEquals(UpdateManager.DEFAULT_DOWNLOAD_PAGE, policy.downloadPage)
    }

    @Test
    fun `blocked build is detected by name, by code and by floor`() {
        val policy = UpdatePolicy(
            minimumSupportedVersion = "1.7.3",
            blockedVersions = listOf("1.7.1"),
            blockedVersionCodes = listOf(100042)
        )

        // Below the supported floor.
        assertTrue(UpdateManager.isBlocked("1.6.0", 100010, policy))
        // Listed by name, with or without the leading v.
        assertTrue(UpdateManager.isBlocked("v1.7.1", 100020, policy))
        // Listed by code: this is what catches a re-issued build that kept its version name.
        assertTrue(UpdateManager.isBlocked("1.9.0", 100042, policy))
        // A healthy, supported build is not blocked.
        assertFalse(UpdateManager.isBlocked("1.7.3", 100050, policy))
    }

    @Test
    fun `blocked version codes only match exact builds`() {
        val policy = UpdatePolicy(blockedVersionCodes = listOf(100042))
        assertTrue(UpdateManager.isBlocked("1.7.3", 100042, policy))
        assertFalse(UpdateManager.isBlocked("1.7.3", 100043, policy))
    }
}
