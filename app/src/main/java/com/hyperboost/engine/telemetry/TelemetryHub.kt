// app/src/main/java/com/hyperboost/engine/telemetry/TelemetryHub.kt
package com.hyperboost.engine.telemetry

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.util.Log
import com.hyperboost.engine.core.ShizukuShellExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * Single shared source of truth for live telemetry (the dashboard and the floating HUD both read
 * from here, so only ONE set of shell probes runs no matter how many consumers are attached).
 *
 * Nothing in here is synthesised: if a value cannot be measured on this device it is `null` and
 * [Telemetry.statusNote] says why.
 */
class TelemetryHub private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TelemetryHub"
        private const val HISTORY_POINTS = 60
        private const val LOW_WINDOW = 600 // intervals kept for 1% lows (~10 s)

        @Volatile
        private var instance: TelemetryHub? = null

        fun get(context: Context): TelemetryHub =
            instance ?: synchronized(this) {
                instance ?: TelemetryHub(context.applicationContext).also { instance = it }
            }
    }

    enum class FrameSource(val label: String) {
        SURFACE_FLINGER("SurfaceFlinger"),
        GFXINFO("gfxinfo"),
        NONE("n/a")
    }

    data class Telemetry(
        // Frame pacing (null = not measurable right now)
        val fps: Float? = null,
        val avgFrametimeMs: Float? = null,
        val onePercentLowFps: Float? = null,
        val jankPercentage: Float? = null,
        val frametimeHistory: List<Float> = emptyList(),
        val frameSource: FrameSource = FrameSource.NONE,
        val measuredPackage: String? = null,
        val statusNote: String? = "Starting…",
        // Display
        val refreshRateHz: Int = 60,
        // System
        val ramUsedMb: Long = 0,
        val ramTotalMb: Long = 0,
        val batteryTempC: Float? = null,
        val cpuTempC: Float? = null,
        val gpuTempC: Float? = null,
        val cpuUsagePct: Float? = null,
        val powerMw: Int? = null,
        val isCharging: Boolean = false,
        val batteryPercent: Int = -1
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _telemetry = MutableStateFlow(Telemetry(refreshRateHz = displayRefreshHz()))
    val telemetry: StateFlow<Telemetry> = _telemetry.asStateFlow()

    /** Package currently in front, resolved from the system (works without the accessibility service). */
    private val _foregroundPackage = MutableStateFlow<String?>(null)
    val foregroundPackage: StateFlow<String?> = _foregroundPackage.asStateFlow()

    // ---- consumer management -------------------------------------------------------------
    private val consumers = HashMap<String, Long>() // owner -> requested interval ms
    private var frameJob: Job? = null
    private var systemJob: Job? = null

    @Synchronized
    fun acquire(owner: String, intervalMs: Long = 1000L) {
        consumers[owner] = intervalMs
        if (frameJob?.isActive != true) {
            frameJob = scope.launch { frameLoop() }
            systemJob = scope.launch { systemLoop() }
        }
    }

    @Synchronized
    fun release(owner: String) {
        consumers.remove(owner)
        if (consumers.isEmpty()) {
            frameJob?.cancel(); frameJob = null
            systemJob?.cancel(); systemJob = null
        }
    }

    @Synchronized
    private fun intervalMs(): Long = (consumers.values.minOrNull() ?: 1000L).coerceIn(500L, 5000L)

    // ---- frame sampling ------------------------------------------------------------------
    private var layerPackage: String? = null
    private var layerCandidates: List<String> = emptyList()
    private var activeLayer: String? = null
    private var zeroStreak = 0
    private var lastForegroundCheckMs = 0L
    private val intervalHistory = ArrayDeque<Float>()
    private val frametimeHistory = ArrayDeque<Float>()

    private fun shizukuReady(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    private suspend fun sh(cmd: String, timeoutMs: Long = 5_000L): String? =
        ShizukuShellExecutor.execute(cmd, timeoutMs).getOrNull()?.takeIf { it.exitCode == 0 }?.stdout

    private suspend fun frameLoop() {
        while (scope.isActive) {
            val started = System.currentTimeMillis()
            try {
                sampleFrames()
            } catch (e: Exception) {
                Log.w(TAG, "frame sample failed", e)
            }
            val spent = System.currentTimeMillis() - started
            delay((intervalMs() - spent).coerceAtLeast(100L))
        }
    }

    private suspend fun sampleFrames() {
        if (!shizukuReady()) {
            publishFrames(null, FrameSource.NONE, null, "Start Shizuku and grant access to measure FPS")
            return
        }

        // 1) Who is in front? (re-checked every ~2 s; a game switch is picked up quickly)
        val now = System.currentTimeMillis()
        if (now - lastForegroundCheckMs >= 2000L || _foregroundPackage.value == null) {
            lastForegroundCheckMs = now
            val out = sh("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
                ?: sh("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'")
            _foregroundPackage.value = out?.let { FrameParsers.parseForegroundPackage(it) }
        }
        val pkg = _foregroundPackage.value
        if (pkg == null) {
            publishFrames(null, FrameSource.NONE, null, "Could not detect the foreground app")
            return
        }
        if (pkg == context.packageName) {
            publishFrames(null, FrameSource.NONE, pkg, "Open a game — FPS is measured for the app in front")
            return
        }

        // 2) SurfaceFlinger present timestamps for that app's layer (covers SurfaceView games)
        if (pkg != layerPackage) {
            layerPackage = pkg
            layerCandidates = emptyList()
            activeLayer = null
            zeroStreak = 0
            intervalHistory.clear()
            frametimeHistory.clear()
        }
        if (activeLayer == null || zeroStreak >= 3) {
            discoverLayer(pkg)
            zeroStreak = 0
        }

        val layer = activeLayer
        if (layer != null) {
            val dump = sh("dumpsys SurfaceFlinger --latency ${ShizukuShellExecutor.quote(layer)}")
                ?.let { FrameParsers.parseLatency(it) }
            if (dump != null) {
                val stats = FrameParsers.windowStats(dump.presentNs, System.nanoTime(), 1_000_000_000L, dump.refreshPeriodNs)
                if (stats != null) {
                    val hz = (1e9 / dump.refreshPeriodNs).toInt().coerceIn(24, 240)
                    if (stats.frames == 0) zeroStreak++ else zeroStreak = 0
                    publishFrames(stats, FrameSource.SURFACE_FLINGER, pkg, null, hz)
                    return
                }
            }
        }

        // 3) HWUI apps: real per-frame completion times from gfxinfo framestats
        val gfx = sh("dumpsys gfxinfo ${ShizukuShellExecutor.quote(pkg)} framestats", 8_000L)
            ?.let { FrameParsers.parseFramestats(it) }
        if (gfx != null) {
            val period = (1e9 / _telemetry.value.refreshRateHz).toLong()
            val stats = FrameParsers.windowStats(gfx, System.nanoTime(), 1_000_000_000L, period)
            if (stats != null) {
                publishFrames(stats, FrameSource.GFXINFO, pkg, null)
                return
            }
        }

        publishFrames(null, FrameSource.NONE, pkg, "No frame data from this app/ROM")
    }

    /** Picks the layer of [pkg] that is actually presenting the most frames. */
    private suspend fun discoverLayer(pkg: String) {
        val list = sh("dumpsys SurfaceFlinger --list") ?: return
        layerCandidates = FrameParsers.pickLayerCandidates(list, pkg)
        var best: String? = null
        var bestFrames = -1
        for (name in layerCandidates) {
            val dump = sh("dumpsys SurfaceFlinger --latency ${ShizukuShellExecutor.quote(name)}")
                ?.let { FrameParsers.parseLatency(it) } ?: continue
            val frames = FrameParsers.windowStats(dump.presentNs, System.nanoTime(), 1_000_000_000L, dump.refreshPeriodNs)?.frames ?: 0
            if (frames > bestFrames) {
                bestFrames = frames
                best = name
            }
        }
        activeLayer = best ?: layerCandidates.firstOrNull()
    }

    private fun publishFrames(
        stats: FrameParsers.WindowStats?,
        source: FrameSource,
        pkg: String?,
        note: String?,
        hz: Int? = null
    ) {
        if (stats == null) {
            _telemetry.update {
                it.copy(
                    fps = null, avgFrametimeMs = null, onePercentLowFps = null, jankPercentage = null,
                    frameSource = source, measuredPackage = pkg, statusNote = note
                )
            }
            return
        }

        intervalHistory.addAll(stats.intervalsMs)
        while (intervalHistory.size > LOW_WINDOW) intervalHistory.removeFirst()
        // Sparkline: one point per sample (avg frametime of that second)
        if (stats.avgFrametimeMs > 0f) {
            frametimeHistory.addLast(stats.avgFrametimeMs)
            while (frametimeHistory.size > HISTORY_POINTS) frametimeHistory.removeFirst()
        }
        val p99 = FrameParsers.percentile(intervalHistory.toList(), 99f)
        val low = if (p99 > 0f) 1000f / p99 else null

        _telemetry.update {
            it.copy(
                fps = round1(stats.fps),
                avgFrametimeMs = round1(stats.avgFrametimeMs),
                onePercentLowFps = low?.let(::round1),
                jankPercentage = round1(stats.jankPercent),
                frametimeHistory = frametimeHistory.toList(),
                frameSource = source,
                measuredPackage = pkg,
                statusNote = note,
                refreshRateHz = hz ?: it.refreshRateHz
            )
        }
    }

    // ---- system sampling (RAM, battery, power, CPU, thermals) ----------------------------
    private var lastCpu: Pair<Long, Long>? = null
    private var powerEma: Double? = null
    private var tick = 0

    private suspend fun systemLoop() {
        while (scope.isActive) {
            try {
                sampleSystem()
            } catch (e: Exception) {
                Log.w(TAG, "system sample failed", e)
            }
            delay(intervalMs())
        }
    }

    private suspend fun sampleSystem() {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val totalMb = mi.totalMem / (1024 * 1024)
        val usedMb = totalMb - mi.availMem / (1024 * 1024)

        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempC = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        var mv = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        if (mv in 1..99) mv *= 1000 // some devices report volts

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val rawCurrent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val powerMw: Int? = if (rawCurrent != Int.MIN_VALUE && rawCurrent != 0 && mv > 0) {
            // Spec says µA, but some OEMs report mA. Real draw is never < 10 mA with the screen
            // on, so a value under 10 000 can only be mA.
            val microAmps = Math.abs(rawCurrent.toLong()).let { if (it < 10_000L) it * 1000L else it }
            val inst = microAmps * mv / 1_000_000.0 // µA * mV = nW -> / 1e6 = mW
            powerEma = powerEma?.let { it * 0.6 + inst * 0.4 } ?: inst
            powerEma!!.toInt()
        } else null

        // Thermals + CPU load need shell access; probe every other tick to stay light.
        var cpuUsage: Float? = _telemetry.value.cpuUsagePct
        var cpuT = _telemetry.value.cpuTempC
        var gpuT = _telemetry.value.gpuTempC
        if (tick++ % 2 == 0 && shizukuReady()) {
            val script = "head -n 1 /proc/stat; for z in /sys/class/thermal/thermal_zone*; do " +
                "echo \"T \$(cat \$z/type 2>/dev/null) \$(cat \$z/temp 2>/dev/null)\"; done"
            sh(script)?.let { out ->
                val lines = out.lines()
                lines.firstOrNull()?.let { l ->
                    FrameParsers.parseCpuStat(l)?.let { cur ->
                        lastCpu?.let { prev ->
                            val dTotal = cur.second - prev.second
                            if (dTotal > 0) cpuUsage = ((cur.first - prev.first) * 100f / dTotal).coerceIn(0f, 100f)
                        }
                        lastCpu = cur
                    }
                }
                val th = FrameParsers.parseThermal(lines)
                cpuT = th.cpuC
                gpuT = th.gpuC
            }
        }

        _telemetry.update {
            it.copy(
                ramUsedMb = usedMb, ramTotalMb = totalMb,
                batteryTempC = tempC, cpuTempC = cpuT, gpuTempC = gpuT,
                cpuUsagePct = cpuUsage?.let(::round1),
                powerMw = powerMw, isCharging = charging, batteryPercent = pct
            )
        }
    }

    // ---- helpers -------------------------------------------------------------------------
    @Suppress("DEPRECATION")
    private fun displayRefreshHz(): Int = try {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.getDisplay(0)?.refreshRate?.let { Math.round(it) } ?: 60
    } catch (_: Exception) {
        60
    }

    /** Refresh rates (Hz) the panel actually supports at its current resolution. */
    fun supportedRefreshRates(): List<Int> = try {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(0)
        val cur = display.mode
        display.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .map { Math.round(it.refreshRate) }
            .distinct().sorted()
            .ifEmpty { listOf(60) }
    } catch (_: Exception) {
        listOf(60)
    }

    private fun round1(v: Float): Float = Math.round(v * 10f) / 10f
}
