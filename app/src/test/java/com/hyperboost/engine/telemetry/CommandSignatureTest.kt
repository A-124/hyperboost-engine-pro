package com.hyperboost.engine.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The capability report leaves the device, so these tests are as much a privacy check as a
 * formatting check: no value, package name or hostname may survive [CommandSignature.of].
 */
class CommandSignatureTest {

    @Test
    fun `settings writes keep the namespace and key but drop the value`() {
        assertEquals(
            "settings put global window_animation_scale",
            CommandSignature.of("settings put global window_animation_scale '0.0'")
        )
        assertEquals(
            "settings put system peak_refresh_rate",
            CommandSignature.of("settings put system peak_refresh_rate 120")
        )
    }

    @Test
    fun `settings reads keep the key`() {
        assertEquals(
            "settings get system touch_prediction_status",
            CommandSignature.of("settings get system touch_prediction_status")
        )
    }

    @Test
    fun `package names are replaced`() {
        assertEquals(
            "am set-standby-bucket <pkg>",
            CommandSignature.of("am set-standby-bucket com.example.somegame restricted")
        )
        // Only the verb path is kept for `cmd`; the mode name adds nothing to a support matrix.
        assertEquals(
            "cmd game mode",
            CommandSignature.of("cmd game mode performance com.example.somegame")
        )
    }

    @Test
    fun `hostnames and quoted values never survive`() {
        val signature = CommandSignature.of("settings put global private_dns_specifier 'one.one.one.one'")
        assertFalse(signature.contains("one.one.one.one"))

        val hostSignature = CommandSignature.of("ping -c 1 dns.google")
        assertFalse(hostSignature.contains("dns.google"))
    }

    @Test
    fun `property commands keep the property name`() {
        // Property names are AOSP constants and are the signal the report needs, so the key keeps
        // its dots while the value is still removed.
        assertEquals(
            "setprop debug.hwui.renderer <v>",
            CommandSignature.of("setprop debug.hwui.renderer 'skiavk'")
        )
        assertEquals("getprop debug.sf.latch_unsignaled", CommandSignature.of("getprop debug.sf.latch_unsignaled"))
    }

    @Test
    fun `paths are replaced`() {
        assertEquals("pm install <path>", CommandSignature.of("pm install /data/local/tmp/update.apk"))
    }

    @Test
    fun `long values are replaced`() {
        val signature = CommandSignature.of("settings put global something " + "x".repeat(80))
        assertFalse(signature.contains("xxxx"))
    }

    @Test
    fun `blank input is reported as unknown`() {
        assertEquals("unknown", CommandSignature.of("   "))
    }

    @Test
    fun `quoted values containing spaces stay one token`() {
        assertEquals(
            "settings put global private_dns_specifier",
            CommandSignature.of("settings put global private_dns_specifier 'a b c'")
        )
    }
}
