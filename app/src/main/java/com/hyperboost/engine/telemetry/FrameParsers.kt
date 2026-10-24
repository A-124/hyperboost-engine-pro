// app/src/main/java/com/hyperboost/engine/telemetry/FrameParsers.kt
package com.hyperboost.engine.telemetry

/**
 * Pure (no Android dependencies) parsers for the dumpsys / sysfs output used by the telemetry
 * layer. Kept separate so they can be unit-tested on the JVM.
 */
object FrameParsers {

    /** Timestamps at or above this are SurfaceFlinger's "not presented yet" sentinel. */
    private const val PENDING_THRESHOLD = Long.MAX_VALUE / 2

    private val WHITESPACE = Regex("\\s+")
    private val FOCUS_PACKAGE = Regex("""\bu\d+\s+([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)/""")

    data class LatencyDump(val refreshPeriodNs: Long, val presentNs: LongArray)

    data class WindowStats(
        val fps: Float,
        val avgFrametimeMs: Float,
        val jankPercent: Float,
        val frames: Int,
        /** Frame-to-frame intervals (ms) inside the window, oldest first. */
        val intervalsMs: List<Float>
    )

    // ------------------------------------------------------------------------------------
    // SurfaceFlinger  (dumpsys SurfaceFlinger --latency "<layer>")
    // ------------------------------------------------------------------------------------

    /**
     * Line 1 is the vsync period (ns); every following line is
     * `desiredPresent actualPresent frameReady`. We use the ACTUAL present time, which is when the
     * frame really reached the screen.
     */
    fun parseLatency(output: String): LatencyDump? {
        val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size < 2) return null
        val period = lines[0].toLongOrNull()?.takeIf { it > 0 } ?: return null

        val present = ArrayList<Long>(lines.size)
        for (i in 1 until lines.size) {
            val cols = lines[i].split(WHITESPACE)
            if (cols.size < 3) continue
            val actual = cols[1].toLongOrNull() ?: continue
            if (actual <= 0L || actual >= PENDING_THRESHOLD) continue
            present += actual
        }
        if (present.isEmpty()) return null
        present.sort()
        return LatencyDump(period, present.toLongArray())
    }

    /** Layer names that belong to [pkg], SurfaceView (where games draw) first. */
    fun pickLayerCandidates(listOutput: String, pkg: String, max: Int = 4): List<String> {
        val skipPrefixes = listOf(
            "Background for", "Bounds for", "Task=", "ActivityRecord", "WindowToken",
            "Letterbox", "Dim Layer", "Input Consumer"
        )
        val names = listOutput.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains(pkg) }
            .filter { n -> skipPrefixes.none { n.startsWith(it) } }
            .distinct()
            .toList()
        val (surface, other) = names.partition { it.contains("SurfaceView") }
        return (surface + other).take(max)
    }

    // ------------------------------------------------------------------------------------
    // gfxinfo framestats (HWUI apps)
    // ------------------------------------------------------------------------------------

    /**
     * Returns the `FrameCompleted` timestamp (ns) of every valid frame in the
     * `---PROFILEDATA---` block. Column positions are read from the header because they differ
     * between Android versions. Frames with non-zero Flags (skipped / layout-change frames) are
     * ignored.
     */
    fun parseFramestats(output: String): LongArray? {
        val lines = output.lineSequence().map { it.trim() }.toList()
        val start = lines.indexOfFirst { it.startsWith("---PROFILEDATA---") }
        if (start < 0) return null
        val end = lines.drop(start + 1).indexOfFirst { it.startsWith("---PROFILEDATA---") }
            .let { if (it < 0) lines.size else start + 1 + it }
        val block = lines.subList(start + 1, end).filter { it.isNotEmpty() }
        if (block.size < 2) return null

        val header = block[0].split(',').map { it.trim() }
        val flagsIdx = header.indexOf("Flags").takeIf { it >= 0 } ?: 0
        val doneIdx = header.indexOf("FrameCompleted").takeIf { it >= 0 }
            ?: header.indexOf("SwapBuffers").takeIf { it >= 0 }
            ?: return null

        val out = ArrayList<Long>(block.size)
        for (i in 1 until block.size) {
            val cols = block[i].split(',')
            if (cols.size <= doneIdx) continue
            val flags = cols[flagsIdx].trim().toLongOrNull() ?: continue
            if (flags != 0L) continue
            val done = cols[doneIdx].trim().toLongOrNull() ?: continue
            if (done <= 0L || done >= PENDING_THRESHOLD) continue
            out += done
        }
        if (out.isEmpty()) return null
        out.sort()
        return out.distinct().toLongArray()
    }

    // ------------------------------------------------------------------------------------
    // Rolling-window statistics
    // ------------------------------------------------------------------------------------

    /**
     * FPS / frametime / jank over the last [windowNs] ending at [nowNs]
     * (both on the CLOCK_MONOTONIC timeline, i.e. `System.nanoTime()` on Android).
     *
     * If nothing was presented in the window the FPS really is 0 (a static screen), which is
     * reported honestly instead of being replaced by a made-up number.
     */
    fun windowStats(
        timestamps: LongArray,
        nowNs: Long,
        windowNs: Long = 1_000_000_000L,
        vsyncPeriodNs: Long = 16_666_667L
    ): WindowStats? {
        if (timestamps.isEmpty()) return null
        val windowStart = nowNs - windowNs
        val horizon = nowNs + vsyncPeriodNs * 2 // presents are scheduled up to a couple of vsyncs ahead

        var first = -1
        var last = -1
        for (i in timestamps.indices) {
            val t = timestamps[i]
            if (t > windowStart && t <= horizon) {
                if (first < 0) first = i
                last = i
            }
        }
        if (first < 0) {
            return WindowStats(0f, 0f, 0f, 0, emptyList())
        }

        val count = last - first + 1

        // Include the frame just before the window so the first interval is real.
        val from = if (first > 0) first - 1 else first
        val intervals = ArrayList<Float>(count)
        for (i in from + 1..last) {
            val dtMs = (timestamps[i] - timestamps[i - 1]) / 1e6f
            if (dtMs > 0f && dtMs < 1000f) intervals += dtMs
        }
        val avgInterval = if (intervals.isNotEmpty()) intervals.average().toFloat() else 0f

        // Live stream: rate = (frames-1) / time between first and last frame. This avoids the
        // +/-1 FPS quantisation you get from counting frames inside a fixed 1 s window.
        // Stalled stream (newest frame is old): count/window, so a freeze is NOT hidden.
        val spanSec = (timestamps[last] - timestamps[first]) / 1e9
        val lastAgeMs = (nowNs - timestamps[last]) / 1e6
        val fresh = lastAgeMs <= maxOf(avgInterval * 3f, 100f)
        val fps = (if (count >= 2 && spanSec > 0 && fresh) (count - 1) / spanSec
        else count / (windowNs / 1e9)).toFloat()

        val avg = if (avgInterval > 0f) avgInterval else if (fps > 0f) 1000f / fps else 0f

        var jank = 0
        if (intervals.size >= 2) {
            val median = intervals.sorted()[intervals.size / 2]
            val periodMs = vsyncPeriodNs / 1e6f
            // A frame is janky when it took clearly longer than the app's own cadence AND at
            // least 1.5 vsyncs. This keeps a steady 30 FPS game on a 120 Hz panel at 0% jank.
            val threshold = maxOf(median * 1.8f, periodMs * 1.5f)
            jank = intervals.count { it > threshold }
        }
        val jankPct = if (intervals.isNotEmpty()) jank * 100f / intervals.size else 0f

        return WindowStats(fps, avg, jankPct, count, intervals)
    }

    /** Percentile (0..100) of [values] using nearest-rank. */
    fun percentile(values: List<Float>, pct: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val rank = Math.ceil(pct / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    // ------------------------------------------------------------------------------------
    // Foreground app / system
    // ------------------------------------------------------------------------------------

    /** Extracts the package from `mCurrentFocus=Window{… u0 com.pkg/com.pkg.Main}` style lines. */
    fun parseForegroundPackage(output: String): String? {
        for (line in output.lineSequence()) {
            if (line.contains("=null")) continue
            val m = FOCUS_PACKAGE.find(line) ?: continue
            return m.groupValues[1]
        }
        return null
    }

    /** First line of /proc/stat -> (busyJiffies, totalJiffies). */
    fun parseCpuStat(line: String): Pair<Long, Long>? {
        val p = line.trim().split(WHITESPACE)
        if (p.size < 5 || p[0] != "cpu") return null
        val v = p.drop(1).mapNotNull { it.toLongOrNull() }
        if (v.size < 4) return null
        val idle = v[3] + (v.getOrNull(4) ?: 0L) // idle + iowait
        val total = v.take(8).sum()
        return (total - idle) to total
    }

    /** Normalises thermal-zone readings (milli-°C, deci-°C or °C) to °C; null if implausible. */
    fun normaliseTemp(raw: Long): Float? {
        val c = when {
            raw >= 1000 -> raw / 1000f
            raw in 200..999 -> raw / 10f
            else -> raw.toFloat()
        }
        return c.takeIf { it in 10f..125f }
    }

    data class Thermal(val cpuC: Float?, val gpuC: Float?)

    /** Parses lines of `T <type> <temp>` emitted by the telemetry shell script. */
    fun parseThermal(lines: List<String>): Thermal {
        var cpu: Float? = null
        var gpu: Float? = null
        val cpuRegex = Regex("(?i)cpu|tsens_tz_sensor|soc_thermal|mtktscpu|big|little|cluster|prime")
        val gpuRegex = Regex("(?i)gpu|gpuss")
        for (line in lines) {
            if (!line.startsWith("T ")) continue
            val p = line.split(WHITESPACE)
            if (p.size < 3) continue
            val temp = p.last().toLongOrNull()?.let { normaliseTemp(it) } ?: continue
            val type = p.subList(1, p.size - 1).joinToString(" ")
            if (gpuRegex.containsMatchIn(type)) gpu = maxOf(gpu ?: temp, temp)
            else if (cpuRegex.containsMatchIn(type)) cpu = maxOf(cpu ?: temp, temp)
        }
        return Thermal(cpu, gpu)
    }
}
