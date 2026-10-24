package com.hyperboost.engine.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameParsersTest {

    private val now = 10_000_000_000L

    private fun steady(fps: Double, seconds: Double = 2.0): LongArray {
        val dt = (1e9 / fps).toLong()
        val out = ArrayList<Long>()
        var t = now
        while (t > now - (seconds * 1e9).toLong()) { out += t; t -= dt }
        return out.sorted().toLongArray()
    }

    @Test fun steady60IsExactly60WithNoJank() {
        val s = FrameParsers.windowStats(steady(60.0), now)!!
        assertEquals(60f, s.fps, 0.2f)
        assertEquals(16.67f, s.avgFrametimeMs, 0.1f)
        assertEquals(0f, s.jankPercent, 0.01f)
    }

    @Test fun steady30On120HzIsNotJank() {
        val s = FrameParsers.windowStats(steady(30.0), now, vsyncPeriodNs = 8_333_333L)!!
        assertEquals(30f, s.fps, 0.2f)
        assertEquals(0f, s.jankPercent, 0.01f)
    }

    @Test fun staticScreenReportsZeroNotAFakeNumber() {
        val old = steady(60.0).map { it - 3_000_000_000L }.toLongArray()
        assertEquals(0f, FrameParsers.windowStats(old, now)!!.fps, 0.0f)
    }

    @Test fun hitchIsCountedAsJank() {
        val s = steady(60.0)
        val hitch = s.mapIndexed { i, t -> if (i >= 100) t + 34_000_000L else t }.filter { it <= now }.toLongArray()
        assertTrue(FrameParsers.windowStats(hitch, now)!!.jankPercent > 0f)
    }

    @Test fun latencyParsingSkipsPendingAndZeroRows() {
        val dump = """
            16666666
            100 1000000000 105
            200 2000000000 205
            300 9223372036854775807 305
            0 0 0
        """.trimIndent()
        val parsed = FrameParsers.parseLatency(dump)!!
        assertEquals(16_666_666L, parsed.refreshPeriodNs)
        assertEquals(2, parsed.presentNs.size)
    }

    @Test fun layerPickerPrefersSurfaceViewAndSkipsBackgrounds() {
        val list = """
            StatusBar#0
            com.game.app/com.game.app.Main#0
            SurfaceView[com.game.app/com.game.app.Main]#1(BLAST)
            Background for SurfaceView[com.game.app/com.game.app.Main]#2
        """.trimIndent()
        val picks = FrameParsers.pickLayerCandidates(list, "com.game.app")
        assertEquals("SurfaceView[com.game.app/com.game.app.Main]#1(BLAST)", picks.first())
        assertTrue(picks.none { it.startsWith("Background for") })
    }

    @Test fun framestatsUsesHeaderColumnsAndSkipsFlaggedFrames() {
        val out = """
            ---PROFILEDATA---
            Flags,FrameTimelineVsyncId,IntendedVsync,FrameCompleted,
            0,1,10,9000000000,
            1,2,11,9100000000,
            0,3,12,9500000000,
            ---PROFILEDATA---
        """.trimIndent()
        val frames = FrameParsers.parseFramestats(out)!!
        assertEquals(listOf(9_000_000_000L, 9_500_000_000L), frames.toList())
    }

    @Test fun foregroundPackageParsing() {
        assertEquals("com.game.app", FrameParsers.parseForegroundPackage("mCurrentFocus=Window{a1b u0 com.game.app/com.game.app.Main}"))
        assertNull(FrameParsers.parseForegroundPackage("mCurrentFocus=null"))
    }

    @Test fun cpuStatAndThermal() {
        val a = FrameParsers.parseCpuStat("cpu  100 0 100 800 0 0 0 0")!!
        assertEquals(200L, a.first); assertEquals(1000L, a.second)
        val t = FrameParsers.parseThermal(listOf("T cpu-1-0-usr 61000", "T gpuss-0 54000", "T battery 33000"))
        assertEquals(61f, t.cpuC!!, 0.01f)
        assertEquals(54f, t.gpuC!!, 0.01f)
        assertNotNull(FrameParsers.normaliseTemp(45))
    }
}
