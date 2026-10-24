package com.hyperboost.engine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineStateParserTest {

    private fun parse(
        stdout: String,
        downscaled: Boolean = false,
        applied: Set<String> = emptySet()
    ) = EngineStateParser.parse(stdout, downscaled, applied::contains)

    @Test
    fun `reads the settings the engine actually writes`() {
        val state = parse(
            """
            anim=0.0
            touchpred=1
            wifi=0
            hwui=skiavk
            sf=1
            zen=1
            phantom=false
            adaptive=0
            minhz=90.0
            dnsmode=hostname
            dns=one.one.one.one
            sustained=true
            blur=1
            """.trimIndent(),
            downscaled = true
        )

        assertTrue(state.zeroAnimations)
        assertTrue(state.touchBoost)
        assertTrue(state.wifiGaming)
        assertTrue(state.vulkan)
        assertTrue(state.surfaceFlingerLatch)
        assertTrue(state.downscaled)
        assertTrue(state.sustainedPerformance)
        assertTrue(state.gameDnd)
        assertTrue(state.phantomProtection)
        assertTrue(state.batterySaverOff)
        assertTrue(state.blurDisabled)
        assertEquals(90, state.refreshRateHz)
        assertEquals("one.one.one.one", state.privateDns)
    }

    @Test
    fun `pointer speed never implies touch boost`() {
        val state = parse("ptr=7\ntouchpred=0")
        assertFalse(state.touchBoost)
    }

    @Test
    fun `missing fields do not invent active state`() {
        val state = parse("")
        assertFalse(state.zeroAnimations)
        assertFalse(state.touchBoost)
        assertFalse(state.wifiGaming)
        assertFalse(state.vulkan)
        assertFalse(state.thermalOverride)
        assertNull(state.refreshRateHz)
        assertNull(state.privateDns)
        assertNull(state.thermalVerified)
    }

    @Test
    fun `sustained falls back to the persisted flag when the service is silent`() {
        assertTrue(parse("sustained=", applied = setOf("sustained")).sustainedPerformance)
        assertFalse(parse("sustained=").sustainedPerformance)
        assertTrue(parse("sustained=enabled").sustainedPerformance)
        assertFalse(parse("sustained=disabled", applied = setOf("sustained")).sustainedPerformance)
    }

    @Test
    fun `private dns is only reported when mode is hostname`() {
        assertNull(parse("dnsmode=off\ndns=one.one.one.one").privateDns)
        assertNull(parse("dnsmode=hostname\ndns=null").privateDns)
        assertEquals("dns.google", parse("dnsmode=hostname\ndns=dns.google").privateDns)
    }

    @Test
    fun `reads the extra tuning modules`() {
        val state = parse("freezer=enabled\nbrightmode=0\nshowhz=1")
        assertTrue(state.cachedAppsFreezer)
        assertTrue(state.adaptiveBrightnessOff)
        assertTrue(state.showRefreshRate)
    }

    @Test
    fun `extra tuning modules default off`() {
        val state = parse("freezer=disabled\nbrightmode=1\nshowhz=0")
        assertFalse(state.cachedAppsFreezer)
        assertFalse(state.adaptiveBrightnessOff)
        assertFalse(state.showRefreshRate)
    }

    @Test
    fun `thermal override uses the applied flag and starts unverified`() {
        val state = parse("", applied = setOf("thermal"))
        assertTrue(state.thermalOverride)
        assertNull(state.thermalVerified)
    }
}
