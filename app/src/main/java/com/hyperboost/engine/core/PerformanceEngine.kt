// app/src/main/java/com/hyperboost/engine/core/PerformanceEngine.kt
package com.hyperboost.engine.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import com.hyperboost.engine.telemetry.FrameParsers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executes system tuning through rootless (Shizuku / ADB-level) shell commands.
 *
 * Safety model:
 *  - Every `settings` key and system property the engine changes is snapshotted ONCE (the user's
 *    original value) and restored verbatim on reset, instead of assuming "factory defaults".
 *  - Package names / hostnames are validated and every value is shell-quoted (no injection).
 *  - Tweaks that depend on the Android version or the OEM are best-effort and report why they
 *    were skipped rather than failing the whole boost.
 */
class PerformanceEngine(context: Context) {

    companion object {
        private const val TAG = "PerformanceEngine"
        private const val PREFS = "hyperboost_snapshots"
        private const val UNSET = "\u0000unset"

        // DNS Hostnames
        const val DNS_CLOUDFLARE = "one.one.one.one"
        const val DNS_GOOGLE = "dns.google"
        const val DNS_ADGUARD = "dns.adguard.com"

        private val SAFE_PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
        private val SAFE_HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")

        /** Flag names for the advanced tweaks (see "Deep customization" section below). */
        private val ADVANCED_FLAGS = listOf(
            "cachedprocs", "stayawake", "landscape", "radio", "touchoverlay",
            "nightlight", "grayscale", "forcedark", "dim", "font", "timeout"
        )
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class DisplayConfig(
        val physicalWidth: Int,
        val physicalHeight: Int,
        val overrideWidth: Int?,
        val overrideHeight: Int?,
        val physicalDensity: Int,
        val overrideDensity: Int?
    )

    // ------------------------------------------------------------------------------------------
    // Low-level helpers
    // ------------------------------------------------------------------------------------------

    private fun requirePackage(packageName: String) {
        require(SAFE_PACKAGE.matches(packageName)) { "Invalid package name: $packageName" }
    }

    private fun ShizukuShellExecutor.ShellResult.errorText(): String =
        stderr.ifBlank { stdout }.ifBlank { "exit code $exitCode" }

    /** Runs a command; success only when exit code == 0. */
    private suspend fun exec(
        command: String,
        okMessage: String,
        failPrefix: String,
        timeoutMs: Long = ShizukuShellExecutor.DEFAULT_TIMEOUT_MS
    ): Result<String> = withContext(Dispatchers.IO) {
        Log.i(TAG, command)
        ShizukuShellExecutor.execute(command, timeoutMs).mapCatching {
            if (it.exitCode == 0) okMessage else throw RuntimeException("$failPrefix: ${it.errorText()}")
        }
    }

    private suspend fun readOut(command: String): String? =
        ShizukuShellExecutor.execute(command).getOrNull()?.takeIf { it.exitCode == 0 }?.stdout?.trim()

    // ---- settings (snapshot + restore) -------------------------------------------------------

    private suspend fun snapshotSetting(ns: String, key: String) {
        val id = "$ns/$key"
        if (prefs.contains(id)) return
        val value = readOut("settings get $ns $key") ?: return
        prefs.edit().putString(id, if (value.isEmpty() || value == "null") UNSET else value).apply()
    }

    /** Writes a setting, then READS IT BACK so a silently ignored write is reported, not faked. */
    private suspend fun putSetting(ns: String, key: String, value: String): Result<Unit> {
        snapshotSetting(ns, key)
        val write = ShizukuShellExecutor.execute("settings put $ns $key ${ShizukuShellExecutor.quote(value)}").mapCatching {
            if (it.exitCode != 0) throw RuntimeException("settings put $key: ${it.errorText()}")
        }
        if (write.isFailure) return write
        val now = readOut("settings get $ns $key")
        val same = now == value || (now?.toFloatOrNull() != null && now.toFloatOrNull() == value.toFloatOrNull())
        return if (same) Result.success(Unit)
        else Result.failure(RuntimeException("$key was ignored by this device (still '$now')"))
    }

    /**
     * Restores the user's original value. If no snapshot was captured (for example, because the
     * initial read failed), leave the setting untouched by default. Deletion must be explicitly
     * requested because deleting an unknown prior value can silently change the user's setup.
     */
    private suspend fun restoreSetting(ns: String, key: String, deleteIfNoSnapshot: Boolean = false): Result<Unit> {
        val id = "$ns/$key"
        val saved = prefs.getString(id, null)
        val cmd = when {
            saved == null && !deleteIfNoSnapshot -> return Result.success(Unit)
            saved == null || saved == UNSET -> "settings delete $ns $key"
            else -> "settings put $ns $key ${ShizukuShellExecutor.quote(saved)}"
        }
        return ShizukuShellExecutor.execute(cmd).mapCatching {
            if (it.exitCode != 0) throw RuntimeException("restore $key: ${it.errorText()}")
            prefs.edit().remove(id).apply()
        }
    }

    // ---- system properties (snapshot + restore) ----------------------------------------------

    private suspend fun snapshotProp(key: String) {
        val id = "prop/$key"
        if (prefs.contains(id)) return
        val value = readOut("getprop $key") ?: return
        prefs.edit().putString(id, value).apply()
    }

    private suspend fun putProp(key: String, value: String): Result<Unit> {
        snapshotProp(key)
        val write = ShizukuShellExecutor.execute("setprop $key ${ShizukuShellExecutor.quote(value)}").mapCatching {
            if (it.exitCode != 0) throw RuntimeException("setprop $key: ${it.errorText()}")
        }
        if (write.isFailure) return write
        val now = readOut("getprop $key")
        return if (now == value) Result.success(Unit)
        else Result.failure(RuntimeException("$key was blocked by this ROM (still '$now')"))
    }

    private suspend fun restoreProp(key: String): Result<Unit> {
        val id = "prop/$key"
        val saved = prefs.getString(id, null) ?: return Result.success(Unit)
        return ShizukuShellExecutor.execute("setprop $key ${ShizukuShellExecutor.quote(saved)}").mapCatching {
            if (it.exitCode != 0) throw RuntimeException("restore $key: ${it.errorText()}")
            prefs.edit().remove(id).apply()
        }
    }

    private fun markApplied(name: String, applied: Boolean) {
        prefs.edit().putBoolean("applied/$name", applied).apply()
    }

    private fun wasApplied(name: String): Boolean = prefs.getBoolean("applied/$name", false)

    /** Packages we changed (doze whitelist / game mode / standby bucket) so Restore can undo them. */
    private fun trackPackage(kind: String, pkg: String) {
        val set = prefs.getStringSet("pkgs/$kind", emptySet()).orEmpty().toMutableSet()
        set.add(pkg)
        prefs.edit().putStringSet("pkgs/$kind", set).apply()
    }

    private fun trackedPackages(kind: String): Set<String> =
        prefs.getStringSet("pkgs/$kind", emptySet()).orEmpty().toSet()

    private fun clearTracked(kind: String) {
        prefs.edit().remove("pkgs/$kind").apply()
    }

    /** Experimental tweaks that many ROMs ignore: try them, but never fail the whole feature. */
    private suspend fun soft(block: suspend () -> Result<Unit>): Result<Unit> {
        block()
        return Result.success(Unit)
    }

    /** Runs [steps] in order, collecting failures. Success if no step failed. */
    private suspend fun runAll(okMessage: String, vararg steps: suspend () -> Result<Unit>): Result<String> {
        val errors = mutableListOf<String>()
        for (step in steps) {
            step().onFailure { errors += it.message ?: "unknown error" }
        }
        return if (errors.isEmpty()) Result.success(okMessage)
        else Result.failure(RuntimeException(errors.joinToString("; ")))
    }

    // ------------------------------------------------------------------------------------------
    // Display
    // ------------------------------------------------------------------------------------------

    /**
     * Reset display resolution to the native panel size. Resolution/scale changes were removed;
     * these reset paths remain for "Restore" and for cleaning up overrides from older builds.
     */
    suspend fun resetDisplayResolution(): Result<String> =
        exec("wm size reset", "Display resolution reset to native.", "Failed to reset resolution")
            .onSuccess { markApplied("display", false) }

    /** Reset display density (DPI) to native. */
    suspend fun resetDisplayDensity(): Result<String> =
        exec("wm density reset", "Display density reset to default.", "Failed to reset density")
            .onSuccess { markApplied("display", false) }

    /** Lock screen refresh rate (e.g. 60 / 90 / 120 / 144 Hz). */
    suspend fun lockRefreshRate(hz: Float): Result<String> = runCatching {
        val hzInt = hz.toInt()
        require(hzInt in 24..240) { "Unsupported refresh rate $hzInt" }
        hzInt
    }.fold(
        onSuccess = { hzInt ->
            runAll(
                "Refresh rate locked to ${hzInt}Hz",
                { putSetting("system", "peak_refresh_rate", hzInt.toString()) },
                { putSetting("system", "min_refresh_rate", hzInt.toString()) }
            )
        },
        onFailure = { Result.failure<String>(it) }
    )

    /** Restore the user's original (usually dynamic) refresh-rate behaviour. */
    suspend fun resetRefreshRate(): Result<String> = runAll(
        "Variable refresh rate restored.",
        { restoreSetting("system", "peak_refresh_rate") },
        { restoreSetting("system", "min_refresh_rate") }
    )

    // ------------------------------------------------------------------------------------------
    // GPU / compositor
    // ------------------------------------------------------------------------------------------

    /**
     * Force the Skia-Vulkan pipeline for HWUI and RenderEngine. (Valid values are `skiavk` /
     * `skiavkthreaded` — the literal string "vulkan" is silently ignored by Android.)
     * Applies to apps launched after the change.
     */
    suspend fun forceVulkanRenderer(enable: Boolean): Result<String> =
        (if (enable) runAll(
            "Skia-Vulkan renderer forced (restart apps to apply).",
            { putProp("debug.hwui.renderer", "skiavk") },
            { putProp("debug.renderengine.backend", "skiavkthreaded") }
        ) else runAll(
            "Default renderer restored (restart apps to apply).",
            { restoreProp("debug.hwui.renderer") },
            { restoreProp("debug.renderengine.backend") }
        )).onSuccess { markApplied("vulkan", enable) }

    /** SurfaceFlinger low-latency unsignaled buffer latching. */
    suspend fun setSurfaceFlingerLowLatency(enable: Boolean): Result<String> =
        (if (enable) runAll(
            "SurfaceFlinger instant buffer latching enabled.",
            { putProp("debug.sf.latch_unsignaled", "1") },
            { soft { putProp("debug.sf.early_app_phase_offset_ns", "500000") } },
            { soft { putProp("debug.sf.early_gl_app_phase_offset_ns", "500000") } }
        ) else runAll(
            "SurfaceFlinger standard sync restored.",
            { restoreProp("debug.sf.latch_unsignaled") },
            { restoreProp("debug.sf.early_app_phase_offset_ns") },
            { restoreProp("debug.sf.early_gl_app_phase_offset_ns") }
        )).onSuccess { markApplied("sf", enable) }

    // ------------------------------------------------------------------------------------------
    // Input / UI responsiveness
    // ------------------------------------------------------------------------------------------

    /** Window / transition / animator scales (0.0 = instant, 1.0 = default). */
    suspend fun setWindowAnimationScale(scale: Float): Result<String> {
        if (scale < 0f || scale > 10f) return Result.failure(IllegalArgumentException("Bad animation scale"))
        val okMsg = if (scale == 0f) "Zero animation speed enabled (Instant response)." else "Animation speed set to ${scale}x."
        return runAll(
            okMsg,
            { putSetting("global", "window_animation_scale", scale.toString()) },
            { putSetting("global", "transition_animation_scale", scale.toString()) },
            { putSetting("global", "animator_duration_scale", scale.toString()) }
        ).onSuccess { markApplied("anim", scale == 0f) }
    }

    /** Restore the user's own animation scales. */
    suspend fun restoreWindowAnimationScale(): Result<String> = runAll(
        "Animation speed restored.",
        { restoreSetting("global", "window_animation_scale") },
        { restoreSetting("global", "transition_animation_scale") },
        { restoreSetting("global", "animator_duration_scale") }
    ).onSuccess { markApplied("anim", false) }

    /**
     * Enables the touch setting the engine can verify, then attempts the optional
     * long-press/debounce writes only where the ROM accepts them.
     * pointer_speed is a mouse setting, not touchscreen sensitivity, and is not written.
     */
    suspend fun setTouchLatencyBoost(enable: Boolean): Result<String> {
        if (enable) {
            val primary = putSetting("system", "touch_prediction_status", "1")
            soft { putSetting("secure", "long_press_timeout", "250") }
            soft { putProp("debug.touch.press.debounce", "0") }

            return primary.fold(
                onSuccess = {
                    markApplied("touch", true)
                    Result.success(
                        "Touch prediction enabled and verified. Long-press and debounce options were applied where supported."
                    )
                },
                onFailure = {
                    markApplied("touch", false)
                    Result.failure(
                        RuntimeException(
                            "This device did not accept touch prediction (${it.message}). Touch latency was not changed.",
                            it
                        )
                    )
                }
            )
        }
        return runAll(
            "Touch options restored.",
            { restoreSetting("system", "pointer_speed") },
            { restoreSetting("system", "touch_prediction_status") },
            { restoreSetting("secure", "long_press_timeout") },
            { restoreProp("debug.touch.press.debounce") }
        ).onSuccess { markApplied("touch", false) }
    }

    // ------------------------------------------------------------------------------------------
    // Network
    // ------------------------------------------------------------------------------------------

    /** Disable Wi-Fi power-save sleeping and turn on the framework's Wi-Fi low-latency mode. */
    suspend fun setWifiGamingOptimization(enable: Boolean): Result<String> {
        if (enable) {
            val primary = putSetting("global", "wifi_suspend_optimizations_enabled", "0")
            soft { putSetting("global", "wifi_scan_always_enabled", "0") }

            // Android 10+; best effort. Do not claim it worked if the command was rejected.
            val lowLatency = ShizukuShellExecutor.execute("cmd wifi force-low-latency-mode enabled")
                .getOrNull()?.exitCode == 0
            if (lowLatency) markApplied("wifill", true)

            return primary.fold(
                onSuccess = {
                    markApplied("wifi", true)
                    Result.success(
                        if (lowLatency) {
                            "Wi-Fi low-latency mode enabled and verified."
                        } else {
                            "Wi-Fi power-save setting applied. The extra low-latency command is not supported on this ROM."
                        }
                    )
                },
                onFailure = {
                    markApplied("wifi", false)
                    Result.failure(
                        RuntimeException(
                            "This device did not accept the Wi-Fi power-save setting (${it.message}).",
                            it
                        )
                    )
                }
            )
        }
        return runAll(
            "Wi-Fi standard power saving restored.",
            { restoreSetting("global", "wifi_suspend_optimizations_enabled") },
            { restoreSetting("global", "wifi_scan_always_enabled") },
            {
                if (wasApplied("wifill")) {
                    ShizukuShellExecutor.execute("cmd wifi force-low-latency-mode disabled")
                    markApplied("wifill", false)
                }
                Result.success(Unit)
            }
        ).onSuccess { markApplied("wifi", false) }
    }

    /** Private DNS hostname for lower-latency lookups (null/blank = turn Private DNS off). */
    suspend fun setPrivateDns(hostname: String?): Result<String> {
        if (hostname.isNullOrBlank()) {
            return runAll(
                "Private DNS restored (system default).",
                { restoreSetting("global", "private_dns_mode") },
                { restoreSetting("global", "private_dns_specifier") }
            )
        }
        if (!SAFE_HOST.matches(hostname)) return Result.failure(IllegalArgumentException("Invalid DNS hostname"))
        return runAll(
            "Private DNS configured to $hostname",
            { putSetting("global", "private_dns_mode", "hostname") },
            { putSetting("global", "private_dns_specifier", hostname) }
        )
    }

    // ------------------------------------------------------------------------------------------
    // NEW: power / thermal / background-process rootless tweaks
    // ------------------------------------------------------------------------------------------

    /**
     * Sustained-performance (fixed performance) mode: asks the OS to hold clocks steady instead
     * of boosting and then thermal-throttling. Android 12+.
     */
    suspend fun setSustainedPerformance(enable: Boolean): Result<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return Result.failure(UnsupportedOperationException("Sustained performance needs Android 12+"))
        }
        val r = exec(
            "cmd power set-fixed-performance-mode-enabled $enable",
            if (enable) "Sustained performance mode ON (steadier clocks, less throttling)." else "Sustained performance mode OFF.",
            "Sustained performance unsupported on this device"
        )
        if (r.isFailure) return r
        // Verify: ask the power service what it is really doing now.
        val actual = readSustainedPerformance()
        return if (actual == null || actual == enable) {
            markApplied("sustained", enable)
            r
        } else {
            Result.failure(RuntimeException("Power service ignored sustained-performance request (still ${if (actual) "on" else "off"})"))
        }
    }

    /** Real sustained-performance state from the power service, or null if it can't be read. */
    suspend fun readSustainedPerformance(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val out = readOut("cmd power get-fixed-performance-mode-enabled 2>/dev/null")?.lowercase()
        if (out.isNullOrBlank()) return null
        return when {
            out.contains("true") -> true
            out.contains("false") -> false
            out.contains("disabled") -> false
            out.contains("enabled") -> true
            else -> null
        }
    }

    /** Silence notifications / heads-up popups while gaming (priority-only Do Not Disturb). */
    suspend fun setGameDnd(enable: Boolean): Result<String> {
        if (enable) {
            if (!wasApplied("dnd")) {
                // remember what the user had (0 = off, 1 = priority, 2 = total silence, 3 = alarms only)
                val orig = readOut("settings get global zen_mode")?.toIntOrNull() ?: 0
                prefs.edit().putInt("dnd_orig", orig).apply()
            }
            val r = exec(
                "cmd notification set_dnd priority",
                "Do Not Disturb ON — no popups mid-game.",
                "Failed to change Do Not Disturb"
            )
            if (r.isFailure) return r
            val zen = readOut("settings get global zen_mode")?.toIntOrNull()
            if (zen != null && zen == 0) {
                return Result.failure(RuntimeException("Do Not Disturb did not turn on (system zen_mode is still 0)"))
            }
            markApplied("dnd", true)
            return r
        }
        val orig = prefs.getInt("dnd_orig", 0)
        val mode = when (orig) { 1 -> "priority"; 2 -> "none"; 3 -> "alarms"; else -> "off" }
        val r = exec("cmd notification set_dnd $mode", "Do Not Disturb restored.", "Failed to restore Do Not Disturb")
        if (r.isSuccess) markApplied("dnd", false)
        return r
    }

    /**
     * Android 12+ kills "phantom" child processes of apps once more than 32 exist, which causes
     * random game / emulator crashes. Lift that limit and stop the monitor.
     */
    suspend fun setPhantomProcessProtection(enable: Boolean): Result<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return Result.failure(UnsupportedOperationException("Phantom-process limit only exists on Android 12+"))
        }
        return (if (enable) runAll(
            "Phantom-process killer disabled (fewer random background kills).",
            { putSetting("global", "settings_enable_monitor_phantom_procs", "false") },
            {
                ShizukuShellExecutor.execute("device_config put activity_manager max_phantom_processes 2147483647").mapCatching {
                    if (it.exitCode != 0) throw RuntimeException("device_config: ${it.errorText()}")
                }
            }
        ) else runAll(
            "Phantom-process limit restored.",
            { restoreSetting("global", "settings_enable_monitor_phantom_procs") },
            {
                ShizukuShellExecutor.execute("device_config delete activity_manager max_phantom_processes").mapCatching {
                    if (it.exitCode != 0) throw RuntimeException("device_config: ${it.errorText()}")
                }
            }
        )).onSuccess { markApplied("phantom", enable) }
    }

    /** Turn off Battery Saver and Adaptive Battery so the OS stops throttling CPU/background work. */
    suspend fun setBatteryThrottlingOff(enable: Boolean): Result<String> =
        (if (enable) runAll(
            "Battery Saver & Adaptive Battery throttling disabled.",
            { putSetting("global", "low_power", "0") },
            { putSetting("global", "adaptive_battery_management_enabled", "0") }
        ) else runAll(
            "Battery throttling settings restored.",
            { restoreSetting("global", "low_power") },
            { restoreSetting("global", "adaptive_battery_management_enabled") }
        )).onSuccess { markApplied("battery", enable) }

    /** Kill all cached background processes to free RAM (foreground app is untouched). */
    suspend fun killBackgroundApps(): Result<String> =
        exec("am kill-all", "Cached background processes cleared.", "Failed to clear background apps")

    /** Trim cached app data. */
    suspend fun trimSystemCaches(): Result<String> =
        exec("pm trim-caches 5000000000", "System app caches trimmed successfully.", "Failed to trim caches", 120_000L)

    /** Run the idle-maintenance job now: fstrim + background dexopt for faster storage and app starts. */
    suspend fun runStorageMaintenance(): Result<String> = runAll(
        "Storage TRIM & background dexopt triggered.",
        {
            ShizukuShellExecutor.execute("sm idle-maint run", ShizukuShellExecutor.LONG_TIMEOUT_MS).mapCatching {
                if (it.exitCode != 0) throw RuntimeException("sm idle-maint: ${it.errorText()}")
            }
        },
        {
            ShizukuShellExecutor.execute("cmd package bg-dexopt-job", ShizukuShellExecutor.LONG_TIMEOUT_MS).mapCatching {
                if (it.exitCode != 0) throw RuntimeException("bg-dexopt-job: ${it.errorText()}")
            }
        }
    )

    // ------------------------------------------------------------------------------------------
    // Per-app tuning
    // ------------------------------------------------------------------------------------------

    /** Ahead-of-time compile a game with its profile to remove JIT micro-stutter. */
    suspend fun compileAppSpeedProfile(packageName: String): Result<String> = runCatching {
        requirePackage(packageName)
    }.fold(
        onSuccess = {
            exec(
                "cmd package compile -m speed-profile -f $packageName",
                "AOT speed compilation completed for $packageName.",
                "Failed compilation",
                ShizukuShellExecutor.LONG_TIMEOUT_MS
            )
        },
        onFailure = { Result.failure<String>(it) }
    )

    /** Whitelist an active game from Doze throttling. */
    suspend fun whitelistAppFromDoze(packageName: String): Result<String> = runCatching {
        requirePackage(packageName)
    }.fold(
        onSuccess = {
            trackPackage("doze", packageName)
            exec("dumpsys deviceidle whitelist +$packageName", "$packageName exempt from battery throttling.", "Failed to whitelist")
        },
        onFailure = { Result.failure<String>(it) }
    )

    /** Set Android OS Game Mode (performance / standard / battery). */
    suspend fun setAndroidGameMode(packageName: String, mode: String = "performance"): Result<String> = runCatching {
        requirePackage(packageName)
        require(mode in setOf("performance", "standard", "battery")) { "Invalid game mode" }
    }.fold(
        onSuccess = {
            trackPackage("gamemode", packageName)
            exec("cmd game mode $mode $packageName", "Android Game Mode set to $mode for $packageName", "Game Mode unsupported or failed")
        },
        onFailure = { Result.failure<String>(it) }
    )

    /** Put a non-essential background package into the restricted standby bucket. */
    suspend fun restrictBackgroundApp(packageName: String): Result<String> = runCatching {
        requirePackage(packageName)
        require(!isProtected(packageName)) { "Protected apps can't be restricted." }
        require(!isSystemApp(packageName)) { "Only user-installed apps can be restricted." }
        if (!prefs.contains("bucket/original/$packageName")) {
            val original = readOut("am get-standby-bucket $packageName")
                ?.takeIf { Regex("^(active|working_set|frequent|rare|restricted|never|exempted|[0-9]+)$", RegexOption.IGNORE_CASE).matches(it) }
                ?: throw IllegalStateException("This Android version can't read the app's original background policy.")
            prefs.edit().putString("bucket/original/$packageName", original).apply()
        }
        val result = ShizukuShellExecutor.execute("am set-standby-bucket $packageName restricted")
            .getOrElse {
                prefs.edit().remove("bucket/original/$packageName").apply()
                throw it
            }
        if (result.exitCode != 0) throw RuntimeException("Failed to restrict $packageName: ${result.errorText()}")
        trackPackage("bucket", packageName)
        "$packageName background apps restricted. Notifications and sync may be delayed."
    }

    /** Undo [restrictBackgroundApp]. */
    suspend fun unrestrictApp(packageName: String): Result<String> = runCatching {
        requirePackage(packageName)
        val originalBucket = prefs.getString("bucket/original/$packageName", "active") ?: "active"
        val result = ShizukuShellExecutor.execute("am set-standby-bucket $packageName $originalBucket").getOrThrow()
        if (result.exitCode != 0) throw RuntimeException("Failed to restore $packageName background policy: ${result.errorText()}")
        untrackPackage("bucket", packageName)
        prefs.edit().remove("bucket/original/$packageName").apply()
        "$packageName background policy restored."
    }

    /** Apps whose standby bucket was restricted through this app. */
    fun listRestrictedBackgroundPackages(): Set<String> = trackedPackages("bucket")

    /** Run a short, read-only latency check through the Shizuku shell. */
    suspend fun runNetworkPing(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val result = ShizukuShellExecutor.execute("ping -c 4 -W 2 1.1.1.1", 15_000L).getOrThrow()
            if (result.exitCode != 0) {
                val reason = result.stdout.lines().lastOrNull { it.contains("packet loss", ignoreCase = true) }
                    ?: result.errorText()
                throw RuntimeException(reason)
            }
            val avg = Regex("=\\s*[0-9.]+/([0-9.]+)/[0-9.]+/")
                .find(result.stdout)?.groupValues?.getOrNull(1)
                ?: throw RuntimeException("The device did not return ping timing data.")
            "Average network ping to Cloudflare (1.1.1.1): $avg ms (4 packets)."
        }
    }

    // ------------------------------------------------------------------------------------------
    // Extra tweaks: window blur, guarded thermal override, battery saver, freeze, temperatures
    // ------------------------------------------------------------------------------------------

    /** Turns off real-time background blur (Android 12+). Restored from the settings snapshot. */
    suspend fun setWindowBlurDisabled(enable: Boolean): Result<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return Result.failure(UnsupportedOperationException("Window blur control needs Android 12+"))
        }
        return (if (enable) runAll(
            "Window blurs disabled (less GPU work per frame).",
            { putSetting("global", "disable_window_blurs", "1") }
        ) else runAll(
            "Window blurs restored.",
            { restoreSetting("global", "disable_window_blurs") }
        )).onSuccess { markApplied("blur", enable) }
    }

    /** Turns Battery Saver off only. Adaptive Battery is left alone (unlike [setBatteryThrottlingOff]). */
    suspend fun turnOffBatterySaver(): Result<Unit> = putSetting("global", "low_power", "0")

    /** Android 11+ cached-app freezer. More aggressive freezing can keep memory free during games. */
    suspend fun setCachedAppsFreezer(enable: Boolean): Result<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Result.failure(UnsupportedOperationException("Cached-app freezer needs Android 11+"))
        }
        return flagged("freezer", enable) {
            if (enable) runAll(
                "Cached apps freezer enabled.",
                { putSetting("global", "cached_apps_freezer", "enabled") }
            ) else runAll(
                "Cached apps freezer restored.",
                { restoreSetting("global", "cached_apps_freezer") }
            )
        }
    }

    /** Stops the screen from changing brightness automatically during a session. */
    suspend fun setAdaptiveBrightnessOff(enable: Boolean): Result<String> = flagged("brightness", enable) {
        if (enable) runAll(
            "Adaptive brightness off.",
            { putSetting("system", "screen_brightness_mode", "0") }
        ) else runAll(
            "Adaptive brightness restored.",
            { restoreSetting("system", "screen_brightness_mode") }
        )
    }

    /** Shows Android's refresh-rate overlay (developer display option). */
    suspend fun setShowRefreshRate(enable: Boolean): Result<String> = flagged("showhz", enable) {
        if (enable) runAll(
            "Refresh-rate overlay on.",
            { putSetting("system", "show_refresh_rate", "1") }
        ) else runAll(
            "Refresh-rate overlay restored.",
            { restoreSetting("system", "show_refresh_rate") }
        )
    }

    /**
     * Asks the thermal service to report "no throttling", so clocks are not cut when the chip heats up.
     * This is the riskiest tweak in the engine. The ViewModel only enables it together with a
     * temperature guard (see [readCpuGpuTemps]) that turns it back off when the device runs hot.
     * Reverted with `cmd thermalservice reset`.
     */
    suspend fun setThermalOverride(enable: Boolean): Result<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Result.failure(UnsupportedOperationException("Thermal override needs Android 10+"))
        }
        return if (enable) {
            exec(
                "cmd thermalservice override-status 0",
                "Thermal throttling override requested (temperature guard active).",
                "Thermal override is not supported on this device"
            ).onSuccess { markApplied("thermal", true) }
        } else {
            exec(
                "cmd thermalservice reset",
                "Thermal throttling restored.",
                "Failed to reset thermal override"
            ).onSuccess { markApplied("thermal", false) }
        }
    }

    /**
     * Best-effort thermal override state from the thermal service.
     * Null means this ROM did not report enough information to verify it.
     */
    suspend fun readThermalOverride(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val dump = readOut("dumpsys thermalservice") ?: return null
        val text = dump.lineSequence()
            .filter { it.contains("override", ignoreCase = true) }
            .joinToString(" ")
            .lowercase()
        if (text.isBlank()) return null
        val value = Regex(
            """override(?:\s+status)?\s*[:=]?\s*(0|1|true|false|on|off|enabled|disabled|active)""",
            RegexOption.IGNORE_CASE
        ).find(text)?.groupValues?.getOrNull(1)?.lowercase() ?: return null
        return when (value) {
            "0", "false", "off", "disabled" -> false
            "1", "true", "on", "enabled", "active" -> true
            else -> null
        }
    }

    /** CPU / GPU temperatures in °C (individual fields are null when not readable). Null = read failed. */
    suspend fun readCpuGpuTemps(): FrameParsers.Thermal? = withContext(Dispatchers.IO) {
        val script = "for z in /sys/class/thermal/thermal_zone*; do " +
            "echo \"T \$(cat \$z/type 2>/dev/null) \$(cat \$z/temp 2>/dev/null)\"; done"
        ShizukuShellExecutor.execute(script).getOrNull()
            ?.takeIf { it.exitCode == 0 }
            ?.let { FrameParsers.parseThermal(it.stdout.lines()) }
    }

    /** Apps the freeze tool must never touch: Android core, Google services, Play, Shizuku, this app. */
    private val freezeBlocklist = listOf(
        "com.android.",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.inputmethod",
        "com.google.android.apps.nexuslauncher",
        "com.google.android.permissioncontroller",
        "com.google.android.packageinstaller",
        "com.google.android.webview",
        "com.android.vending",
        "moe.shizuku.",
        "rikka.shizuku",
        "com.hyperboost.",
        "com.aistudio.hyperboost"
    )

    /** True for packages that [setPackageFrozen] refuses to freeze. */
    fun isProtected(packageName: String): Boolean =
        packageName == appContext.packageName || freezeBlocklist.any { packageName.startsWith(it) }

    /** True when the app is a system app, or when we can't confirm it's a user app (fail closed). */
    private fun isSystemApp(packageName: String): Boolean = runCatching {
        (appContext.packageManager.getApplicationInfo(packageName, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }.getOrDefault(true)

    /** Packages currently disabled for the user (i.e. frozen by the freeze tool). */
    suspend fun listFrozenPackages(): Set<String> = withContext(Dispatchers.IO) {
        readOut("pm list packages -d --user 0").orEmpty().lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .toSet()
    }

    /**
     * Freezes (disables for user 0; data and APK are kept) or un-freezes a user-installed app.
     * Verified by reading the disabled-package list back.
     */
    suspend fun setPackageFrozen(packageName: String, frozen: Boolean): Result<String> = runCatching {
        requirePackage(packageName)
        if (frozen && isProtected(packageName)) {
            throw IllegalArgumentException("$packageName is a protected system app and can't be frozen")
        }
        if (frozen && isSystemApp(packageName)) {
            throw IllegalArgumentException("$packageName is a system app. Only user-installed apps can be frozen.")
        }
        val out = ShizukuShellExecutor.execute(
            if (frozen) "pm disable-user --user 0 $packageName" else "pm enable --user 0 $packageName"
        ).getOrThrow()
        if (out.exitCode != 0) throw RuntimeException("pm: ${out.errorText()}")
        if ((packageName in listFrozenPackages()) != frozen) {
            throw RuntimeException("the system did not change $packageName (still ${if (frozen) "enabled" else "disabled"})")
        }
        if (frozen) trackPackage("frozen", packageName) else untrackPackage("frozen", packageName)
        if (frozen) "$packageName frozen. Its data is kept; unfreeze it any time."
        else "$packageName is back to normal."
    }

    private fun untrackPackage(kind: String, pkg: String) {
        val set = prefs.getStringSet("pkgs/$kind", emptySet()).orEmpty().toMutableSet()
        set.remove(pkg)
        prefs.edit().putStringSet("pkgs/$kind", set).apply()
    }

    // ------------------------------------------------------------------------------------------
    // Deep customization, visuals and session utilities
    // ------------------------------------------------------------------------------------------

    /** Advanced tweaks that are applied right now (UI reads real state from this). */
    fun activeAdvancedTweaks(): Set<String> = ADVANCED_FLAGS.filter { wasApplied(it) }.toSet()

    /** Flags [name] as applied/unapplied only if [action] succeeded. */
    private suspend fun flagged(
        name: String,
        enable: Boolean,
        action: suspend () -> Result<String>
    ): Result<String> = action().onSuccess { markApplied(name, enable) }

    /** Refresh rate currently forced via min_refresh_rate, or null when the system is in control. */
    suspend fun readRefreshLock(): Int? =
        readOut("settings get system min_refresh_rate")?.toFloatOrNull()?.toInt()?.takeIf { it > 0 }

    // ---- Deep system ----

    /** Keeps up to 64 apps cached in RAM instead of 32. Android 12+. */
    suspend fun setCachedProcessLimit(enable: Boolean): Result<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return Result.failure(UnsupportedOperationException("Cached-app limit needs Android 12+"))
        }
        val r = exec(
            if (enable) "device_config put activity_manager max_cached_processes 64"
            else "device_config delete activity_manager max_cached_processes",
            if (enable) "Up to 64 apps stay cached in memory." else "Cached-app limit restored.",
            "Cached-app limit is not supported on this device"
        )
        if (r.isFailure) return r
        if (enable) {
            markApplied("cachedprocs", true)
            val now = readOut("device_config get activity_manager max_cached_processes")
            if (now != "64") {
                ShizukuShellExecutor.execute("device_config delete activity_manager max_cached_processes")
                markApplied("cachedprocs", false)
                return Result.failure(RuntimeException("This ROM ignored the cached-app limit (still '$now')"))
            }
            return r
        }
        markApplied("cachedprocs", false)
        return r
    }

    /** Screen stays on while plugged in (AC + USB + wireless). */
    suspend fun setStayAwakeWhileCharging(enable: Boolean): Result<String> = flagged("stayawake", enable) {
        if (enable) runAll("Screen stays on while charging.", { putSetting("global", "stay_on_while_plugged_in", "7") })
        else runAll("Screen behaviour while charging restored.", { restoreSetting("global", "stay_on_while_plugged_in") })
    }

    /** Custom screen-off timeout; null restores the user's own value. */
    suspend fun setScreenTimeout(ms: Int?): Result<String> {
        if (ms != null && ms !in 15_000..1_800_000) {
            return Result.failure(IllegalArgumentException("Screen timeout must be between 15 s and 30 min"))
        }
        return flagged("timeout", ms != null) {
            if (ms != null) runAll("Screen timeout set to ${ms / 1000} s.", { putSetting("system", "screen_off_timeout", ms.toString()) })
            else runAll("Screen timeout restored.", { restoreSetting("system", "screen_off_timeout") })
        }
    }

    /** Locks rotation to landscape (games that don't rotate cleanly on their own). */
    suspend fun setLandscapeLock(enable: Boolean): Result<String> = flagged("landscape", enable) {
        if (enable) runAll(
            "Rotation locked to landscape.",
            { putSetting("system", "accelerometer_rotation", "0") },
            { putSetting("system", "user_rotation", "1") }
        ) else runAll(
            "Auto-rotate restored.",
            { restoreSetting("system", "accelerometer_rotation") },
            { restoreSetting("system", "user_rotation") }
        )
    }

    /** Stops background Bluetooth scans and the always-on cellular link while on Wi-Fi. */
    suspend fun setRadioQuiet(enable: Boolean): Result<String> = flagged("radio", enable) {
        if (enable) runAll(
            "Background Bluetooth scans off; cellular no longer kept awake beside Wi-Fi.",
            { putSetting("global", "ble_scan_always_enabled", "0") },
            { soft { putSetting("global", "mobile_data_always_on", "0") } }
        ) else runAll(
            "Radio behaviour restored.",
            { restoreSetting("global", "ble_scan_always_enabled") },
            { restoreSetting("global", "mobile_data_always_on") }
        )
    }

    /** Touch dots + coordinate bar. Handy for finding Auto Tap X/Y. */
    suspend fun setTouchOverlay(enable: Boolean): Result<String> = flagged("touchoverlay", enable) {
        if (enable) runAll(
            "Touch overlay on. Use the coordinates for Auto Tap.",
            { putSetting("system", "show_touches", "1") },
            { putSetting("system", "pointer_location", "1") }
        ) else runAll(
            "Touch overlay off.",
            { restoreSetting("system", "show_touches") },
            { restoreSetting("system", "pointer_location") }
        )
    }

    // ---- Visuals ----

    suspend fun setNightLight(enable: Boolean): Result<String> = flagged("nightlight", enable) {
        if (enable) runAll("Night Light on.", { putSetting("secure", "night_display_activated", "1") })
        else runAll("Night Light restored.", { restoreSetting("secure", "night_display_activated") })
    }

    /** Monochrome display: calmer on the eyes and lower OLED draw. */
    suspend fun setGrayscale(enable: Boolean): Result<String> = flagged("grayscale", enable) {
        if (enable) runAll(
            "Grayscale display on.",
            { putSetting("secure", "accessibility_display_daltonizer", "0") },
            { putSetting("secure", "accessibility_display_daltonizer_enabled", "1") }
        ) else runAll(
            "Colors restored.",
            { restoreSetting("secure", "accessibility_display_daltonizer_enabled") },
            { restoreSetting("secure", "accessibility_display_daltonizer") }
        )
    }

    /** Extra Dim (Android 12+), level 0-100; null restores the user's own value. */
    suspend fun setExtraDim(level: Int?): Result<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return Result.failure(UnsupportedOperationException("Extra Dim needs Android 12+"))
        }
        if (level != null && level !in 0..100) return Result.failure(IllegalArgumentException("Bad dim level"))
        return flagged("dim", level != null) {
            if (level != null) runAll(
                "Extra Dim set to $level%.",
                { putSetting("secure", "reduce_bright_colors_level", level.toString()) },
                { putSetting("secure", "reduce_bright_colors_activated", "1") }
            ) else runAll(
                "Extra Dim off.",
                { restoreSetting("secure", "reduce_bright_colors_activated") },
                { restoreSetting("secure", "reduce_bright_colors_level") }
            )
        }
    }

    /** System font scale 0.7-1.5; null restores the user's own value. */
    suspend fun setFontScale(scale: Float?): Result<String> {
        if (scale != null && scale !in 0.7f..1.5f) {
            return Result.failure(IllegalArgumentException("Font scale must be between 0.7 and 1.5"))
        }
        return flagged("font", scale != null) {
            if (scale != null) runAll("Font size set to ${scale}x.", { putSetting("system", "font_scale", scale.toString()) })
            else runAll("Font size restored.", { restoreSetting("system", "font_scale") })
        }
    }

    /** Forces the dark theme (Android 10+); the user's original mode (yes/no/auto) is remembered. */
    suspend fun setForceDark(enable: Boolean): Result<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Result.failure(UnsupportedOperationException("Forced dark theme needs Android 10+"))
        }
        if (enable) {
            if (!wasApplied("forcedark")) {
                val current = Regex("night mode:\\s*(yes|no|auto)", RegexOption.IGNORE_CASE)
                    .find(readOut("cmd uimode night").orEmpty())?.groupValues?.get(1)?.lowercase() ?: "auto"
                prefs.edit().putString("uimode_orig", current).apply()
            }
            return exec("cmd uimode night yes", "Dark theme forced on (saves power on OLED).", "Dark theme is not supported here")
                .onSuccess { markApplied("forcedark", true) }
        }
        val original = prefs.getString("uimode_orig", "auto") ?: "auto"
        return exec("cmd uimode night $original", "Original theme restored.", "Failed to restore the theme")
            .onSuccess { markApplied("forcedark", false) }
    }

    // ------------------------------------------------------------------------------------------
    // State read-back & global restore
    // ------------------------------------------------------------------------------------------

    /** Reads the real device state in one shell round-trip so toggles survive app restarts. */
    suspend fun readState(): Result<EngineState> = withContext(Dispatchers.IO) {
        val script = listOf(
            "echo anim=\$(settings get global animator_duration_scale)",
            "echo touchpred=\$(settings get system touch_prediction_status)",
            "echo wifi=\$(settings get global wifi_suspend_optimizations_enabled)",
            "echo hwui=\$(getprop debug.hwui.renderer)",
            "echo sf=\$(getprop debug.sf.latch_unsignaled)",
            "echo zen=\$(settings get global zen_mode)",
            "echo phantom=\$(settings get global settings_enable_monitor_phantom_procs)",
            "echo adaptive=\$(settings get global adaptive_battery_management_enabled)",
            "echo minhz=\$(settings get system min_refresh_rate)",
            "echo dnsmode=\$(settings get global private_dns_mode)",
            "echo dns=\$(settings get global private_dns_specifier)",
            "echo sustained=\$(cmd power get-fixed-performance-mode-enabled 2>/dev/null)",
            "echo blur=\$(settings get global disable_window_blurs)",
            "echo freezer=\$(settings get global cached_apps_freezer)",
            "echo brightmode=\$(settings get system screen_brightness_mode)",
            "echo showhz=\$(settings get system show_refresh_rate)"
        ).joinToString("; ")

        try {
            val result = ShizukuShellExecutor.execute(script).getOrThrow()
            val state = EngineStateParser.parse(
                stdout = result.stdout,
                applied = ::wasApplied
            )
            val thermalVerified = if (state.thermalOverride) readThermalOverride() else null
            Result.success(state.copy(thermalVerified = thermalVerified))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    /**
     * Restores everything the engine ever changed to the user's ORIGINAL values.
     * Returns the list of problems (empty = clean).
     */
    suspend fun restoreAllDefaults(): List<String> = withContext(Dispatchers.IO) {
        val problems = mutableListOf<String>()

        // 1) Every settings / prop key that has a snapshot.
        for (id in prefs.all.keys.toList()) {
            val r = when {
                id.startsWith("prop/") -> restoreProp(id.removePrefix("prop/"))
                id.startsWith("applied/") || id.startsWith("pkgs/") || id == "dnd_orig" -> continue
                id.contains('/') -> restoreSetting(id.substringBefore('/'), id.substringAfter('/'), deleteIfNoSnapshot = false)
                else -> continue
            }
            r.onFailure { problems += it.message ?: id }
        }

        // 2) Non-settings state we flagged as applied.
        if (wasApplied("display")) {
            resetDisplayResolution().onFailure { problems += it.message ?: "display size" }
            resetDisplayDensity().onFailure { problems += it.message ?: "display density" }
            markApplied("display", false)
        }
        if (wasApplied("sustained")) setSustainedPerformance(false).onFailure { problems += it.message ?: "sustained" }
        if (wasApplied("dnd")) setGameDnd(false).onFailure { problems += it.message ?: "dnd" }
        if (wasApplied("wifill")) {
            ShizukuShellExecutor.execute("cmd wifi force-low-latency-mode disabled")
            markApplied("wifill", false)
        }
        if (wasApplied("phantom")) {
            ShizukuShellExecutor.execute("device_config delete activity_manager max_phantom_processes")
            markApplied("phantom", false)
        }

        if (wasApplied("cachedprocs")) setCachedProcessLimit(false).onFailure { problems += it.message ?: "cached apps" }
        if (wasApplied("forcedark")) setForceDark(false).onFailure { problems += it.message ?: "dark theme" }
        // Settings-based tweaks were already restored from their snapshots in step 1; clear their flags.
        listOf(
            "stayawake", "landscape", "radio", "touchoverlay", "nightlight", "grayscale", "dim", "font", "timeout",
            "freezer", "brightness", "showhz"
        ).forEach { markApplied(it, false) }

        if (wasApplied("thermal")) setThermalOverride(false).onFailure { problems += it.message ?: "thermal" }

        // 3) Per-app changes: doze whitelist, game mode, standby bucket.
        for (pkg in trackedPackages("doze")) {
            if (SAFE_PACKAGE.matches(pkg)) ShizukuShellExecutor.execute("dumpsys deviceidle whitelist -$pkg")
        }
        clearTracked("doze")
        for (pkg in trackedPackages("gamemode")) {
            // `reset` hands control back to the system's own default for that game.
            if (SAFE_PACKAGE.matches(pkg)) ShizukuShellExecutor.execute("cmd game reset $pkg")
        }
        clearTracked("gamemode")
        for (pkg in trackedPackages("bucket")) {
            if (!SAFE_PACKAGE.matches(pkg)) continue
            val originalBucket = prefs.getString("bucket/original/$pkg", "active") ?: "active"
            val restored = ShizukuShellExecutor.execute("am set-standby-bucket $pkg $originalBucket")
                .getOrNull()?.exitCode == 0
            if (restored) {
                untrackPackage("bucket", pkg)
                prefs.edit().remove("bucket/original/$pkg").apply()
            } else problems += "could not restore background policy for $pkg"
        }

        // 4) Apps frozen by the freeze tool. Anything that fails stays tracked so the next restore retries it.
        for (pkg in trackedPackages("frozen")) {
            val ok = SAFE_PACKAGE.matches(pkg) &&
                ShizukuShellExecutor.execute("pm enable --user 0 $pkg").getOrNull()?.exitCode == 0
            if (ok) untrackPackage("frozen", pkg) else problems += "could not unfreeze $pkg"
        }

        // Settings-based toggles were restored from their snapshots above; clear their applied
        // markers so a pre-existing enabled value cannot make a switch look active again.
        listOf(
            "anim", "touch", "wifi", "vulkan", "sf", "battery", "blur", "display",
            "freezer", "brightness", "showhz"
        ).forEach { markApplied(it, false) }
        problems
    }

    /** Query current display config from `wm size` and `wm density`. */
    suspend fun getDisplayConfig(): Result<DisplayConfig> = withContext(Dispatchers.IO) {
        runCatching {
            val sizeRes = ShizukuShellExecutor.execute("wm size").getOrThrow()
            val densityRes = ShizukuShellExecutor.execute("wm density").getOrThrow()

            var physW: Int? = null
            var physH: Int? = null
            var overW: Int? = null
            var overH: Int? = null

            for (line in sizeRes.stdout.lines()) {
                if (line.contains("Physical size:")) {
                    val parts = line.substringAfter("Physical size:").trim().split("x")
                    if (parts.size == 2) {
                        physW = parts[0].trim().toIntOrNull()
                        physH = parts[1].trim().toIntOrNull()
                    }
                }
                if (line.contains("Override size:")) {
                    val parts = line.substringAfter("Override size:").trim().split("x")
                    if (parts.size == 2) {
                        overW = parts[0].trim().toIntOrNull()
                        overH = parts[1].trim().toIntOrNull()
                    }
                }
            }

            var physDpi: Int? = null
            var overDpi: Int? = null
            for (line in densityRes.stdout.lines()) {
                if (line.contains("Physical density:")) {
                    physDpi = line.substringAfter("Physical density:").trim().toIntOrNull()
                }
                if (line.contains("Override density:")) {
                    overDpi = line.substringAfter("Override density:").trim().toIntOrNull()
                }
            }

            // Refuse to guess: a wrong "native" size would make 720p downscale/reset wrong.
            val pw = physW ?: error("Could not read the panel's physical size from `wm size`")
            val ph = physH ?: error("Could not read the panel's physical size from `wm size`")
            val pd = physDpi ?: error("Could not read the panel's physical density from `wm density`")

            DisplayConfig(pw, ph, overW, overH, pd, overDpi)
        }
    }
}