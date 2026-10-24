// app/src/main/java/com/hyperboost/engine/telemetry/FrametimeAnalyzer.kt
package com.hyperboost.engine.telemetry

import android.util.Log
import com.hyperboost.engine.core.ShizukuShellExecutor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Captures and parses SurfaceFlinger and gfxinfo dumpsys data via elevated shell,
 * computing real-time FPS, frametimes (ms), and frame drop/jank percentages.
 */
class FrametimeAnalyzer {

    companion object {
        private const val TAG = "FrametimeAnalyzer"
        private const val MAX_HISTORY_POINTS = 50
    }

    data class FrameTelemetry(
        val fps: Float = 0f,
        val avgFrametimeMs: Float = 16.6f,
        val jankPercentage: Float = 0f,
        val frametimeHistory: List<Float> = emptyList(),
        val refreshRateHz: Int = 60,
        val totalFramesSampled: Int = 0
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var samplingJob: Job? = null

    private val _telemetryFlow = MutableStateFlow(FrameTelemetry())
    val telemetryFlow: StateFlow<FrameTelemetry> = _telemetryFlow.asStateFlow()

    private val recentFrametimes = ArrayDeque<Float>(MAX_HISTORY_POINTS)
    private var targetedPackage: String? = null

    fun setTargetPackage(packageName: String?) {
        targetedPackage = packageName
    }

    fun startAnalyzing(sampleIntervalMs: Long = 1000L) {
        if (samplingJob?.isActive == true) return

        samplingJob = scope.launch {
            Log.d(TAG, "Starting frametime analyzer sampling loop")
            while (isActive) {
                try {
                    val telemetry = sampleMetrics()
                    _telemetryFlow.value = telemetry
                } catch (e: Exception) {
                    Log.w(TAG, "Error during frametime sampling", e)
                }
                delay(sampleIntervalMs)
            }
        }
    }

    fun stopAnalyzing() {
        samplingJob?.cancel()
        samplingJob = null
        Log.d(TAG, "Frametime analyzer stopped")
    }

    private suspend fun sampleMetrics(): FrameTelemetry {
        // First try SurfaceFlinger latency dump
        val sfResult = ShizukuShellExecutor.executeRootlessOrFallback("dumpsys SurfaceFlinger --latency")
        if (sfResult.isSuccess && sfResult.getOrNull()?.stdout?.isNotBlank() == true) {
            val parsed = parseSurfaceFlingerLatency(sfResult.getOrNull()!!.stdout)
            if (parsed != null && parsed.fps > 0f) {
                return updateHistory(parsed)
            }
        }

        // If package specified or fallback to gfxinfo
        val pkg = targetedPackage
        if (!pkg.isNullOrBlank()) {
            val gfxResult = ShizukuShellExecutor.executeRootlessOrFallback("dumpsys gfxinfo $pkg framestats")
            if (gfxResult.isSuccess && gfxResult.getOrNull()?.stdout?.isNotBlank() == true) {
                val parsedGfx = parseGfxInfo(gfxResult.getOrNull()!!.stdout)
                if (parsedGfx != null) {
                    return updateHistory(parsedGfx)
                }
            }
        }

        // No valid frame timestamps were available. Do not invent FPS or jank values.
        return FrameTelemetry(
            fps = 0f,
            avgFrametimeMs = 0f,
            jankPercentage = 0f,
            frametimeHistory = synchronized(recentFrametimes) { recentFrametimes.toList() },
            refreshRateHz = _telemetryFlow.value.refreshRateHz,
            totalFramesSampled = 0
        )
    }

    private fun parseSurfaceFlingerLatency(output: String): FrameTelemetry? {
        val lines = output.lines().filter { it.isNotBlank() }
        if (lines.size < 5) return null

        val refreshPeriodNs = lines[0].toLongOrNull() ?: 16666666L
        val refreshHz = if (refreshPeriodNs > 0) (1_000_000_000L / refreshPeriodNs).toInt() else 60

        var validFrames = 0
        var totalFrametimeNs = 0L
        var jankFrames = 0
        var prevTimestamp = 0L

        // Each line has: DesiredPresent ActualPresent FrameReady (in nanoseconds)
        for (i in 1 until lines.size) {
            val parts = lines[i].trim().split("\\s+".toRegex())
            if (parts.size >= 2) {
                val actualPresent = parts[1].toLongOrNull() ?: continue
                if (actualPresent == 0L || actualPresent == Long.MAX_VALUE) continue

                if (prevTimestamp > 0L) {
                    val dt = actualPresent - prevTimestamp
                    if (dt in 1_000_000L..500_000_000L) { // between 1ms and 500ms
                        totalFrametimeNs += dt
                        validFrames++
                        if (dt > (refreshPeriodNs * 1.5)) {
                            jankFrames++
                        }
                    }
                }
                prevTimestamp = actualPresent
            }
        }

        if (validFrames == 0) return null

        val avgDurationMs = (totalFrametimeNs.toDouble() / validFrames / 1_000_000.0).toFloat()
        val calculatedFps = if (avgDurationMs > 0f) {
            (1000f / avgDurationMs).coerceIn(1f, 144f)
        } else refreshHz.toFloat()
        val jankPct = (jankFrames.toFloat() / validFrames * 100f).coerceIn(0f, 100f)

        return FrameTelemetry(
            fps = Math.round(calculatedFps * 10f) / 10f,
            avgFrametimeMs = Math.round(avgDurationMs * 10f) / 10f,
            jankPercentage = Math.round(jankPct * 10f) / 10f,
            refreshRateHz = refreshHz,
            totalFramesSampled = validFrames
        )
    }

    private fun parseGfxInfo(output: String): FrameTelemetry? {
        // Use actual frame-completion timestamps from gfxinfo framestats. A total-frame
        // counter alone cannot establish FPS because its sampling duration is unknown.
        val timestamps = FrameParsers.parseFramestats(output) ?: return null
        if (timestamps.isEmpty()) return null
        val stats = FrameParsers.windowStats(timestamps, System.nanoTime()) ?: return null
        if (stats.frames <= 0 || stats.avgFrametimeMs <= 0f) return null

        return FrameTelemetry(
            fps = stats.fps,
            avgFrametimeMs = stats.avgFrametimeMs,
            jankPercentage = stats.jankPercent,
            frametimeHistory = emptyList(),
            refreshRateHz = 60,
            totalFramesSampled = stats.frames
        )
    }

    private fun updateHistory(sample: FrameTelemetry): FrameTelemetry {
        synchronized(recentFrametimes) {
            // Samples with no measured frames must not pollute the history with zeroes.
            if (sample.totalFramesSampled > 0 && sample.avgFrametimeMs > 0f) {
                if (recentFrametimes.size >= MAX_HISTORY_POINTS) {
                    recentFrametimes.removeFirst()
                }
                recentFrametimes.addLast(sample.avgFrametimeMs)
            }
            return sample.copy(frametimeHistory = recentFrametimes.toList())
        }
    }
}
