// app/src/main/java/com/hyperboost/engine/ui/MainViewModel.kt
package com.hyperboost.engine.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hyperboost.engine.core.EngineState
import com.hyperboost.engine.core.PerformanceEngine
import com.hyperboost.engine.core.ShizukuManager
import com.hyperboost.engine.services.MacroAutomationService
import com.hyperboost.engine.services.AutoTapService
import com.hyperboost.engine.services.OverlayHudService
import com.hyperboost.engine.telemetry.GyroProbe
import com.hyperboost.engine.telemetry.TelemetryHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Main ViewModel coordinating state for Shizuku, rootless performance modules,
 * live telemetry, per-game profiles, and HUD overlay services.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
        const val TOTAL_BOOSTERS = 11
        private const val MASTER_STEPS = 8

        /** Thermal guard limits. The override is turned off as soon as either one is reached. */
        const val THERMAL_CPU_LIMIT_C = 60f
        const val THERMAL_GPU_LIMIT_C = 65f
        const val THERMAL_CHECK_MS = 4_000L

        /** Choices for the level cards (index 0 = off / user's own value). */
        val FONT_STEPS: List<Float?> = listOf(null, 0.85f, 1.15f, 1.3f)
        val DIM_STEPS: List<Int?> = listOf(null, 25, 50, 75)
        val TIMEOUT_STEPS: List<Int?> = listOf(null, 60_000, 300_000, 900_000)

        /** Stability Guard thresholds (hot = cap to 60 Hz, cool = give the rate back). */
        const val STABILITY_CHECK_MS = 5_000L
        const val GUARD_CPU_HOT_C = 55f
        const val GUARD_CPU_COOL_C = 48f
        const val GUARD_GPU_HOT_C = 58f
        const val GUARD_GPU_COOL_C = 50f
        const val GUARD_BATTERY_HOT_C = 42f
        const val GUARD_BATTERY_COOL_C = 38f
        const val GUARD_LOW_BATTERY_PCT = 15
        const val GUARD_LOW_RAM_FREE_PCT = 10f
        const val GUARD_CLEAN_COOLDOWN_MS = 120_000L
    }

    private val context: Context get() = getApplication<Application>().applicationContext
    private val shizukuManager = ShizukuManager.getInstance(context)
    val performanceEngine = PerformanceEngine(context)
    private val telemetryHub = TelemetryHub.get(context)
    private val gamePrefs = context.getSharedPreferences("hyperboost_games", Context.MODE_PRIVATE)

    data class GameProfile(
        val packageName: String,
        val appName: String,
        val icon: android.graphics.drawable.Drawable?,
        val targetResolutionScale: Float = 1.0f,
        val targetRefreshRate: Int = 120,
        val forceVulkan: Boolean = true,
        val isOptimized: Boolean = false,
        val isCompiled: Boolean = false
    )

    /** Every value is a real measurement; null means "not measurable right now" (never faked). */
    data class TelemetryState(
        val fps: Float? = null,
        val frametimeMs: Float? = null,
        val onePercentLowFps: Float? = null,
        val jankPercentage: Float? = null,
        val frametimeHistory: List<Float> = emptyList(),
        val frameSource: String = "n/a",
        val measuredPackage: String? = null,
        val statusNote: String? = null,
        val ramUsedMb: Long = 0,
        val ramTotalMb: Long = 0,
        val batteryTempCelsius: Float? = null,
        val cpuTempCelsius: Float? = null,
        val gpuTempCelsius: Float? = null,
        val cpuUsagePct: Float? = null,
        val powerDrainMw: Int? = null,
        val isCharging: Boolean = false,
        val batteryPercent: Int = -1,
        val displayResolution: String = "Detecting...",
        val refreshRateHz: Int = 60
    )

    // Shizuku & Service States
    val shizukuStatus: StateFlow<ShizukuManager.ShizukuStatus> = shizukuManager.statusFlow
    val shizukuVersion: StateFlow<Int> = shizukuManager.shizukuVersion
    val isHudRunning: StateFlow<Boolean> = OverlayHudService.isHudRunning
    val isCrosshairActive: StateFlow<Boolean> = OverlayHudService.isCrosshairActive
    val crosshairColorHex: StateFlow<Int> = OverlayHudService.crosshairColorHex
    val crosshairSizeDp: StateFlow<Int> = OverlayHudService.crosshairSizeDp
    val crosshairStrokeDp: StateFlow<Int> = OverlayHudService.crosshairStrokeDp
    val isAccessibilityActive: StateFlow<Boolean> = MacroAutomationService.isServiceConnected

    // Real-Time Telemetry
    private val _telemetryState = MutableStateFlow(TelemetryState())
    val telemetryState: StateFlow<TelemetryState> = _telemetryState.asStateFlow()

    // Easy Enable Rootless Engine Boost Toggles
    private val _isMasterTurboActive = MutableStateFlow(false)
    val isMasterTurboActive: StateFlow<Boolean> = _isMasterTurboActive.asStateFlow()

    private val _isTouchBoostActive = MutableStateFlow(false)
    val isTouchBoostActive: StateFlow<Boolean> = _isTouchBoostActive.asStateFlow()

    private val _isZeroAnimationActive = MutableStateFlow(false)
    val isZeroAnimationActive: StateFlow<Boolean> = _isZeroAnimationActive.asStateFlow()

    private val _isWifiGamingActive = MutableStateFlow(false)
    val isWifiGamingActive: StateFlow<Boolean> = _isWifiGamingActive.asStateFlow()

    private val _isVulkanActive = MutableStateFlow(false)
    val isVulkanActive: StateFlow<Boolean> = _isVulkanActive.asStateFlow()

    private val _isSurfaceFlingerUncapped = MutableStateFlow(false)
    val isSurfaceFlingerUncapped: StateFlow<Boolean> = _isSurfaceFlingerUncapped.asStateFlow()

    private val _isSustainedPerfActive = MutableStateFlow(false)
    val isSustainedPerfActive: StateFlow<Boolean> = _isSustainedPerfActive.asStateFlow()

    private val _isGameDndActive = MutableStateFlow(false)
    val isGameDndActive: StateFlow<Boolean> = _isGameDndActive.asStateFlow()

    private val _isPhantomProtectionActive = MutableStateFlow(false)
    val isPhantomProtectionActive: StateFlow<Boolean> = _isPhantomProtectionActive.asStateFlow()

    private val _isBatteryThrottleOff = MutableStateFlow(false)
    val isBatteryThrottleOff: StateFlow<Boolean> = _isBatteryThrottleOff.asStateFlow()

    private val _isBlurDisabled = MutableStateFlow(false)
    val isBlurDisabled: StateFlow<Boolean> = _isBlurDisabled.asStateFlow()

    private val _isThermalOverrideActive = MutableStateFlow(false)
    val isThermalOverrideActive: StateFlow<Boolean> = _isThermalOverrideActive.asStateFlow()

    /** null = not reported by this ROM. */
    private val _thermalVerified = MutableStateFlow<Boolean?>(null)
    val thermalVerified: StateFlow<Boolean?> = _thermalVerified.asStateFlow()

    private val _isCachedFreezerActive = MutableStateFlow(false)
    val isCachedFreezerActive: StateFlow<Boolean> = _isCachedFreezerActive.asStateFlow()

    private val _isAdaptiveBrightnessOff = MutableStateFlow(false)
    val isAdaptiveBrightnessOff: StateFlow<Boolean> = _isAdaptiveBrightnessOff.asStateFlow()

    private val _isShowRefreshRateActive = MutableStateFlow(false)
    val isShowRefreshRateActive: StateFlow<Boolean> = _isShowRefreshRateActive.asStateFlow()

    private val _userApps = MutableStateFlow<List<UserApp>>(emptyList())
    val userApps: StateFlow<List<UserApp>> = _userApps.asStateFlow()

    val isAutoTapRunning: StateFlow<Boolean> = AutoTapService.isRunning

    private var thermalGuardJob: Job? = null

    /** Cleanup that must still run after this ViewModel has been cleared. */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A user-installed app as shown in the freeze list. */
    data class UserApp(
        val packageName: String,
        val appName: String,
        val frozen: Boolean,
        val backgroundRestricted: Boolean
    )

    private val _supportedRefreshRates = MutableStateFlow(telemetryHub.supportedRefreshRates())
    val supportedRefreshRates: StateFlow<List<Int>> = _supportedRefreshRates.asStateFlow()
    private val maxRefreshRate: Int get() = _supportedRefreshRates.value.maxOrNull() ?: 60

    private val _selectedRefreshRate = MutableStateFlow(telemetryHub.supportedRefreshRates().maxOrNull() ?: 60)
    val selectedRefreshRate: StateFlow<Int> = _selectedRefreshRate.asStateFlow()

    private val _selectedDns = MutableStateFlow<String?>(null)
    val selectedDns: StateFlow<String?> = _selectedDns.asStateFlow()

    private val _installedGames = MutableStateFlow<List<GameProfile>>(emptyList())
    val installedGames: StateFlow<List<GameProfile>> = _installedGames.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _userFeedbackMessage = MutableSharedFlow<String>(extraBufferCapacity = 5)
    val userFeedbackMessage: SharedFlow<String> = _userFeedbackMessage.asSharedFlow()

    // Active boosters count
    val activeBoostersCount: StateFlow<Int> = combine(
        listOf(
            _isTouchBoostActive,
            _isZeroAnimationActive,
            _isWifiGamingActive,
            _isVulkanActive,
            _isSurfaceFlingerUncapped,
            _isSustainedPerfActive,
            _isGameDndActive,
            _isPhantomProtectionActive,
            _isBatteryThrottleOff,
            _isBlurDisabled,
            _isThermalOverrideActive
        )
    ) { values: Array<Boolean> ->
        values.count { it }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Package whose profile is currently applied (so a profile is applied once per session, not per event). */
    private var activeProfilePackage: String? = null
    private var profileLockedRefresh = false

    private val knownGamePrefixes = listOf(
        "com.tencent.ig", "com.pubg", "com.activision.callofduty", "com.garena.game", "com.miHoYo",
        "com.HoYoverse", "com.mobile.legends", "com.moonton", "com.dts.freefire", "com.supercell",
        "com.roblox", "com.mojang", "com.epicgames", "com.ea.game", "com.king.", "com.netease",
        "com.proximabeta", "com.levelinfinite", "com.krafton", "com.riotgames", "com.nianticlabs",
        "com.gameloft", "com.kiloo", "com.innersloth", "com.criticalforceentertainment", "com.rockstargames"
    )

    // --- Advanced tweaks, stability guard, session automation ---
    private val _advancedActive = MutableStateFlow<Set<String>>(emptySet())
    val advancedActive: StateFlow<Set<String>> = _advancedActive.asStateFlow()

    private val _levels = MutableStateFlow<Map<String, Int>>(emptyMap())
    val levels: StateFlow<Map<String, Int>> = _levels.asStateFlow()

    private val _stabilityGuardOn = MutableStateFlow(gamePrefs.getBoolean("auto/guard", false))
    val stabilityGuardOn: StateFlow<Boolean> = _stabilityGuardOn.asStateFlow()

    private val _sessionAutomation =
        MutableStateFlow(gamePrefs.getStringSet("auto/session", emptySet()).orEmpty().toSet())
    val sessionAutomation: StateFlow<Set<String>> = _sessionAutomation.asStateFlow()

    private var stabilityJob: Job? = null
    private var guardRestoreHz: Int? = null // non-null while capped; 0 = hand control back to the system
    private var lastAutoCleanMs = 0L
    private val sessionApplied = mutableSetOf<String>()

    init {
        observeTelemetry()
        loadInstalledGames()
        refreshDisplayConfig()
        observeForegroundAppChanges()
        syncStateWhenAuthorized()
        refreshAdvanced()
        if (_stabilityGuardOn.value) startStabilityGuard()
    }

    /** The dashboard only samples while it is on screen (the HUD keeps its own sampling alive). */
    fun setUiVisible(visible: Boolean) {
        if (visible) {
            _supportedRefreshRates.value = telemetryHub.supportedRefreshRates()
            telemetryHub.acquire("ui", 1000L)
        } else telemetryHub.release("ui")
    }

    override fun onCleared() {
        telemetryHub.release("ui")
        thermalGuardJob?.cancel()
        thermalGuardJob = null
        // The temperature guard dies with this screen, so the thermal override must not outlive it.
        if (_isThermalOverrideActive.value) {
            cleanupScope.launch { performanceEngine.setThermalOverride(false) }
        }
        stabilityJob?.cancel()
        telemetryHub.release("guard")
        if (guardRestoreHz != null) cleanupScope.launch { releaseRefreshCap() }
        super.onCleared()
    }

    /** Re-reads the real device state each time Shizuku becomes authorized (survives app restarts). */
    private fun syncStateWhenAuthorized() {
        viewModelScope.launch {
            shizukuStatus.collect { status ->
                if (status == ShizukuManager.ShizukuStatus.AUTHORIZED) {
                    syncStateFromDevice()
                    cleanupLegacyDisplayOverride()
                }
            }
        }
    }

    /**
     * The render-scale feature was removed. If an earlier build left a wm size/density override,
     * recover it once Shizuku is authorized, and drop the old per-game scale preferences.
     */
    private fun cleanupLegacyDisplayOverride() {
        if (gamePrefs.getBoolean("display/legacy_cleanup_done", false)) return
        val legacyKeys = gamePrefs.all.keys.filter { it.startsWith("scale/") || it.startsWith("res/") }
        viewModelScope.launch {
            val config = performanceEngine.getDisplayConfig().getOrNull()
            val overridden = config != null &&
                (config.overrideWidth != null || config.overrideDensity != null)
            if (overridden && legacyKeys.isNotEmpty()) {
                performanceEngine.resetDisplayResolution()
                performanceEngine.resetDisplayDensity()
                _userFeedbackMessage.emit("Removed a display override left by the earlier render-scale build.")
            }
            if (legacyKeys.isNotEmpty()) {
                gamePrefs.edit().apply { legacyKeys.forEach { remove(it) } }.apply()
            }
            gamePrefs.edit().putBoolean("display/legacy_cleanup_done", true).apply()
            refreshDisplayConfig()
        }
    }

    private suspend fun syncStateFromDevice() {
        performanceEngine.readState().onSuccess { s ->
            applyEngineState(s)
            // A thermal override left behind by an earlier session has no guard running, so undo it now.
            if (s.thermalOverride && thermalGuardJob == null) {
                performanceEngine.setThermalOverride(false).onSuccess {
                    _userFeedbackMessage.emit(
                        "Thermal override from your last session was turned off (its temperature guard only runs while Hyperboost is open)."
                    )
                }
            }
        }
    }

    private suspend fun reconcileEngineState() {
        performanceEngine.readState().onSuccess { applyEngineState(it) }
    }

    private fun applyEngineState(s: EngineState) {
        _isZeroAnimationActive.value = s.zeroAnimations
        _isTouchBoostActive.value = s.touchBoost
        _isWifiGamingActive.value = s.wifiGaming
        _isVulkanActive.value = s.vulkan
        _isSurfaceFlingerUncapped.value = s.surfaceFlingerLatch
        _isSustainedPerfActive.value = s.sustainedPerformance
        _isGameDndActive.value = s.gameDnd
        _isPhantomProtectionActive.value = s.phantomProtection
        _isBatteryThrottleOff.value = s.batterySaverOff
        _isBlurDisabled.value = s.blurDisabled
        // The override only counts as active while its guard is running in this session, and it is
        // not shown as active when the ROM explicitly reports that the override did not take effect.
        _isThermalOverrideActive.value =
            s.thermalOverride && thermalGuardJob != null && s.thermalVerified != false
        _thermalVerified.value = s.thermalVerified
        _isCachedFreezerActive.value = s.cachedAppsFreezer
        _isAdaptiveBrightnessOff.value = s.adaptiveBrightnessOff
        _isShowRefreshRateActive.value = s.showRefreshRate
        _selectedRefreshRate.value = s.refreshRateHz ?: _telemetryState.value.refreshRateHz
        _selectedDns.value = s.privateDns
        _isMasterTurboActive.value =
            s.touchBoost && s.zeroAnimations && s.vulkan && s.surfaceFlingerLatch
        refreshAdvanced()
    }

    private fun observeTelemetry() {
        viewModelScope.launch {
            telemetryHub.telemetry.collect { t ->
                _telemetryState.update { current ->
                    current.copy(
                        fps = t.fps,
                        frametimeMs = t.avgFrametimeMs,
                        onePercentLowFps = t.onePercentLowFps,
                        jankPercentage = t.jankPercentage,
                        frametimeHistory = t.frametimeHistory,
                        frameSource = t.frameSource.label,
                        measuredPackage = t.measuredPackage,
                        statusNote = t.statusNote,
                        ramUsedMb = t.ramUsedMb,
                        ramTotalMb = t.ramTotalMb,
                        batteryTempCelsius = t.batteryTempC,
                        cpuTempCelsius = t.cpuTempC,
                        gpuTempCelsius = t.gpuTempC,
                        cpuUsagePct = t.cpuUsagePct,
                        powerDrainMw = t.powerMw,
                        isCharging = t.isCharging,
                        batteryPercent = t.batteryPercent,
                        refreshRateHz = t.refreshRateHz
                    )
                }
            }
        }
    }

    private fun observeForegroundAppChanges() {
        // The system-resolved foreground package works with Shizuku alone; the accessibility
        // service (if enabled) just makes detection faster.
        viewModelScope.launch {
            merge(telemetryHub.foregroundPackage, MacroAutomationService.currentForegroundPackage)
                .filterNotNull()
                .distinctUntilChanged()
                .collect { pkg -> onForegroundChanged(pkg) }
        }
    }

    private suspend fun onForegroundChanged(pkg: String) {
        if (pkg == context.packageName) return
        val game = _installedGames.value.find { it.packageName == pkg }
        if (game != null && game.isOptimized) {
            if (activeProfilePackage == pkg) return
            activeProfilePackage = pkg
            _userFeedbackMessage.emit("Game detected: ${game.appName} — applying its profile")
            applyGameProfile(game)
            startGameSession()
        } else if (activeProfilePackage != null) {
            // Left the game: give the refresh rate back to the user's own setting (unless Master Turbo owns it).
            val left = activeProfilePackage
            activeProfilePackage = null
            if (profileLockedRefresh && !_isMasterTurboActive.value) {
                profileLockedRefresh = false
                performanceEngine.resetRefreshRate()
                _selectedRefreshRate.value = telemetryHub.telemetry.value.refreshRateHz
                Log.i(TAG, "Left $left; refresh rate restored")
            }
            endGameSession()
        }
    }

    fun requestShizukuPermission() {
        shizukuManager.requestPermission()
    }

    fun reevaluateShizuku() {
        shizukuManager.evaluateState()
    }

    /**
     * Master Turbo One-Tap Boost. Each subsystem is applied independently: one unsupported tweak
     * (e.g. Android-12-only features) no longer aborts the rest. Turning it off restores the
     * user's ORIGINAL values from the snapshot, not assumed factory defaults.
     * Do Not Disturb stays opt-in so calls are never silenced by surprise.
     */
    fun toggleMasterTurbo() {
        viewModelScope.launch {
            _isBusy.value = true
            val turnOn = !_isMasterTurboActive.value
            try {
                if (turnOn) {
                    val failures = mutableListOf<String>()
                    suspend fun step(flow: MutableStateFlow<Boolean>, label: String, block: suspend () -> Result<String>) {
                        val r = block()
                        if (r.isSuccess) {
                            flow.value = true
                        } else {
                            failures += "$label: ${r.exceptionOrNull()?.message}"
                        }
                    }

                    step(_isVulkanActive, "Vulkan") { performanceEngine.forceVulkanRenderer(true) }
                    step(_isZeroAnimationActive, "Animations") { performanceEngine.setWindowAnimationScale(0f) }
                    step(_isTouchBoostActive, "Touch") { performanceEngine.setTouchLatencyBoost(true) }
                    step(_isWifiGamingActive, "Wi-Fi") { performanceEngine.setWifiGamingOptimization(true) }
                    step(_isSurfaceFlingerUncapped, "SurfaceFlinger") { performanceEngine.setSurfaceFlingerLowLatency(true) }
                    step(_isSustainedPerfActive, "Sustained perf") { performanceEngine.setSustainedPerformance(true) }
                    step(_isPhantomProtectionActive, "Phantom killer") { performanceEngine.setPhantomProcessProtection(true) }
                    step(_isBatteryThrottleOff, "Battery throttle") { performanceEngine.setBatteryThrottlingOff(true) }
                    performanceEngine.lockRefreshRate(_selectedRefreshRate.value.toFloat())
                        .onFailure { failures += "Refresh rate: ${it.message}" }
                    performanceEngine.killBackgroundApps()

                    // Trust the device, not our own bookkeeping: re-read what is REALLY active.
                    syncStateFromDevice()
                    val verified = listOf(
                        _isVulkanActive, _isZeroAnimationActive, _isTouchBoostActive, _isWifiGamingActive,
                        _isSurfaceFlingerUncapped, _isSustainedPerfActive, _isPhantomProtectionActive, _isBatteryThrottleOff
                    ).count { it.value }
                    _isMasterTurboActive.value = verified > 0
                    val note = failures.firstOrNull()
                        ?.let { " (${failures.size} skipped — ${it.take(90)})" } ?: ""
                    _userFeedbackMessage.emit("🔥 HYPERBOOST: $verified/$MASTER_STEPS tweaks verified active$note")
                } else {
                    restoreEverything()
                }
            } catch (e: Exception) {
                _userFeedbackMessage.emit("Error applying boost: ${e.message}")
            } finally {
                _isBusy.value = false
                refreshDisplayConfig()
            }
        }
    }

    /** Panic button: restore every tweak to the values the user had before HyperBoost touched them. */
    fun restoreDefaults() {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                restoreEverything()
            } finally {
                _isBusy.value = false
                refreshDisplayConfig()
            }
        }
    }

    private suspend fun restoreEverything() {
        sessionApplied.clear()
        guardRestoreHz = null
        // Stop the guard first so it can't race the restore, then undo everything the engine changed.
        thermalGuardJob?.cancel()
        thermalGuardJob = null
        if (AutoTapService.isRunning.value) stopAutoTap()
        val problems = performanceEngine.restoreAllDefaults()

        // Show the REAL state after restoring (not an assumption), so the switches never lie.
        val synced = performanceEngine.readState().onSuccess { applyEngineState(it) }.isSuccess
        if (!synced) {
            _isMasterTurboActive.value = false
            _isVulkanActive.value = false
            _isZeroAnimationActive.value = false
            _isTouchBoostActive.value = false
            _isWifiGamingActive.value = false
            _isSurfaceFlingerUncapped.value = false
            _isSustainedPerfActive.value = false
            _isGameDndActive.value = false
            _isPhantomProtectionActive.value = false
            _isBatteryThrottleOff.value = false
            _isBlurDisabled.value = false
            _isThermalOverrideActive.value = false
            _thermalVerified.value = null
        }
        _isMasterTurboActive.value = false
        refreshAdvanced()
        _userFeedbackMessage.emit(
            if (problems.isEmpty()) "System restored to your original settings."
            else "Restored with ${problems.size} issue(s): ${problems.first()}"
        )
    }

    private fun friendlyFeatureError(label: String): String =
        "$label couldn't be enabled. Your phone may not support this feature, or Android may be blocking it. Check the required permission or Shizuku connection, then try again."

    private fun runToggle(
        flow: MutableStateFlow<Boolean>,
        label: String,
        apply: suspend (Boolean) -> Result<String>
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            val next = !flow.value
            try {
                val res = apply(next).getOrThrow()
                flow.value = next
                _userFeedbackMessage.emit(res)
                reconcileEngineState()
            } catch (e: Exception) {
                _userFeedbackMessage.emit(friendlyFeatureError(label))
                reconcileEngineState()
            } finally {
                _isBusy.value = false
            }
        }
    }

    private fun runAction(label: String, action: suspend () -> Result<String>) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                _userFeedbackMessage.emit(action().getOrThrow())
            } catch (e: Exception) {
                _userFeedbackMessage.emit("$label error: ${e.message}")
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun toggleSustainedPerformance() =
        runToggle(_isSustainedPerfActive, "Sustained performance") { performanceEngine.setSustainedPerformance(it) }

    fun toggleGameDnd() =
        runToggle(_isGameDndActive, "Do Not Disturb") { performanceEngine.setGameDnd(it) }

    fun togglePhantomProtection() =
        runToggle(_isPhantomProtectionActive, "Phantom killer") { performanceEngine.setPhantomProcessProtection(it) }

    fun toggleBatteryThrottle() =
        runToggle(_isBatteryThrottleOff, "Battery throttle") { performanceEngine.setBatteryThrottlingOff(it) }

    // ------------------------------------------------------------------------------------------
    // Benimaru-style tools: window blur, guarded thermal override, gyro check, freeze, auto tap
    // ------------------------------------------------------------------------------------------

    private fun emitMessage(text: String) {
        viewModelScope.launch { _userFeedbackMessage.emit(text) }
    }

    fun toggleWindowBlur() =
        runToggle(_isBlurDisabled, "Window blur") { performanceEngine.setWindowBlurDisabled(it) }

    fun toggleCachedAppsFreezer() =
        runToggle(_isCachedFreezerActive, "Cached apps freezer") { performanceEngine.setCachedAppsFreezer(it) }

    fun toggleAdaptiveBrightness() =
        runToggle(_isAdaptiveBrightnessOff, "Adaptive brightness") { performanceEngine.setAdaptiveBrightnessOff(it) }

    fun toggleShowRefreshRate() =
        runToggle(_isShowRefreshRateActive, "Refresh-rate overlay") { performanceEngine.setShowRefreshRate(it) }

    /**
     * Thermal throttling override. It is only enabled while the temperature guard is running, and it
     * can't outlive this screen: [onCleared] turns it off again.
     */
    fun toggleThermalOverride() {
        viewModelScope.launch {
            _isBusy.value = true
            val next = !_isThermalOverrideActive.value
            try {
                if (next) {
                    val temps = performanceEngine.readCpuGpuTemps()
                    if (temps?.cpuC == null || temps?.gpuC == null) {
                        throw IllegalStateException("both CPU and GPU temperatures must be readable for the safety guard")
                    }
                    val res = performanceEngine.setThermalOverride(true).getOrThrow()
                    _isThermalOverrideActive.value = true
                    startThermalGuard()
                    reconcileEngineState()
                    val verification = when (_thermalVerified.value) {
                        true -> "Override verified active."
                        false -> "The thermal service still reports throttling enabled; the override did not take effect."
                        null -> "This ROM does not report override state; the temperature guard remains active."
                    }
                    _userFeedbackMessage.emit(
                        "$res $verification Auto-off at ${THERMAL_CPU_LIMIT_C.toInt()}°C CPU / ${THERMAL_GPU_LIMIT_C.toInt()}°C GPU."
                    )
                } else {
                    thermalGuardJob?.cancel()
                    thermalGuardJob = null
                    val res = performanceEngine.setThermalOverride(false).getOrThrow()
                    _isThermalOverrideActive.value = false
                    _thermalVerified.value = null
                    _userFeedbackMessage.emit(res)
                }
            } catch (e: Exception) {
                _userFeedbackMessage.emit("Thermal throttling error: ${e.message}")
                syncStateFromDevice()
            } finally {
                _isBusy.value = false
            }
        }
    }

    /** Reads CPU/GPU temperatures every few seconds and reverts the override as soon as a limit is hit. */
    private fun startThermalGuard() {
        thermalGuardJob?.cancel()
        thermalGuardJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(THERMAL_CHECK_MS)
                val temps = performanceEngine.readCpuGpuTemps()
                val cpu = temps?.cpuC
                val gpu = temps?.gpuC
                val hot = (cpu != null && cpu >= THERMAL_CPU_LIMIT_C) ||
                    (gpu != null && gpu >= THERMAL_GPU_LIMIT_C)
                // Fail closed: if both sensors stop reporting, we can no longer prove the device is safe.
                // If disabling fails, keep retrying on the next tick instead of silently abandoning the guard.
                val readingsUnavailable = cpu == null || gpu == null
                if ((hot || readingsUnavailable) && performanceEngine.setThermalOverride(false).isSuccess) {
                    _isThermalOverrideActive.value = false
                    _thermalVerified.value = null
                    _userFeedbackMessage.emit(
                        if (readingsUnavailable) {
                            "Temperature readings were lost. Thermal override turned off as a safety precaution."
                        } else {
                            "Temperature guard: ${cpu?.toInt() ?: "?"}°C CPU / ${gpu?.toInt() ?: "?"}°C GPU. Thermal override turned off to protect your phone."
                        }
                    )
                    break
                }
            }
        }
    }

    /** Turns Battery Saver off, then measures the gyroscope's real sample rate. */
    fun runGyroCheck() = runAction("Gyro check") {
        val saver = performanceEngine.turnOffBatterySaver()
        val prefix = if (saver.isSuccess) "Battery Saver off. " else "Could not turn Battery Saver off. "
        val reading = GyroProbe.measure(context)
        val hz = reading.hz
        if (hz == null) Result.failure<String>(RuntimeException(prefix + reading.note))
        else Result.success(prefix + "Gyro runs at about ${hz.roundToInt()} Hz. " + reading.note)
    }

    /** Loads the user-installed apps and which of them are frozen. System and protected apps are never listed. */
    @Suppress("DEPRECATION")
    fun loadUserApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val frozenSet = performanceEngine.listFrozenPackages()
            val restrictedSet = performanceEngine.listRestrictedBackgroundPackages()
            val labels = pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
                .asSequence()
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                .filterNot { performanceEngine.isProtected(it.packageName) }
                .associate { it.packageName to pm.getApplicationLabel(it).toString() }
            _userApps.value = (labels.keys + frozenSet + restrictedSet)
                .filterNot { performanceEngine.isProtected(it) }
                .map { pkg ->
                    UserApp(
                        pkg,
                        labels[pkg] ?: pkg,
                        frozen = pkg in frozenSet,
                        backgroundRestricted = pkg in restrictedSet
                    )
                }
                .sortedBy { it.appName.lowercase() }
        }
    }

    fun setAppFrozen(packageName: String, frozen: Boolean) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                _userFeedbackMessage.emit(performanceEngine.setPackageFrozen(packageName, frozen).getOrThrow())
            } catch (e: Exception) {
                _userFeedbackMessage.emit("Freeze error: ${e.message}")
            } finally {
                _isBusy.value = false
                loadUserApps()
            }
        }
    }

    fun setAppBackgroundRestricted(packageName: String, restricted: Boolean) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val result = if (restricted) performanceEngine.restrictBackgroundApp(packageName)
                else performanceEngine.unrestrictApp(packageName)
                _userFeedbackMessage.emit(result.getOrThrow())
            } catch (e: Exception) {
                _userFeedbackMessage.emit("Background limit error: ${e.message}")
            } finally {
                _isBusy.value = false
                loadUserApps()
            }
        }
    }

    fun runNetworkPing() = runAction("Network ping") { performanceEngine.runNetworkPing() }

    fun startAutoTap(config: AutoTapService.Config) {
        when {
            !Settings.canDrawOverlays(context) ->
                emitMessage("Overlay permission is needed for the auto tap button.")
            shizukuStatus.value != ShizukuManager.ShizukuStatus.AUTHORIZED ->
                emitMessage("Auto tap needs Shizuku. Start it and allow Hyperboost first.")
            else -> runCatching { context.startService(AutoTapService.intentFor(context, config)) }
                .onFailure { emitMessage("Could not start auto tap: ${it.message}") }
        }
    }

    fun stopAutoTap() {
        runCatching {
            context.startService(Intent(context, AutoTapService::class.java).apply { action = AutoTapService.ACTION_STOP })
        }
    }

    fun killBackgroundApps() = runAction("Background clean") { performanceEngine.killBackgroundApps() }

    fun runStorageMaintenance() = runAction("Storage maintenance") { performanceEngine.runStorageMaintenance() }

    fun toggleTouchBoost() {
        viewModelScope.launch {
            _isBusy.value = true
            val next = !_isTouchBoostActive.value
            try {
                val res = performanceEngine.setTouchLatencyBoost(next).getOrThrow()
                _isTouchBoostActive.value = next
                _userFeedbackMessage.emit(res)
                reconcileEngineState()
            } catch (e: Exception) {
                _userFeedbackMessage.emit("Touch boost error: ${e.message}")
                reconcileEngineState()
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun toggleZeroAnimations() {
        viewModelScope.launch {
            _isBusy.value = true
            val next = !_isZeroAnimationActive.value
            try {
                val res = if (next) performanceEngine.setWindowAnimationScale(0f).getOrThrow()
                else performanceEngine.restoreWindowAnimationScale().getOrThrow()
                _isZeroAnimationActive.value = next
                _userFeedbackMessage.emit(res)
                reconcileEngineState()
            } catch (e: Exception) {
                _userFeedbackMessage.emit("Animation error: ${e.message}")
                reconcileEngineState()
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun toggleWifiGaming() {
        viewModelScope.launch {
            _isBusy.value = true
            val next = !_isWifiGamingActive.value
            try {
                val res = performanceEngine.setWifiGamingOptimization(next).getOrThrow()
                _isWifiGamingActive.value = next
                _userFeedbackMessage.emit(res)
                reconcileEngineState()
            } catch (e: Exception) {
                _userFeedbackMessage.emit("Wi-Fi boost error: ${e.message}")
                reconcileEngineState()
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun toggleVulkan() {
        viewModelScope.launch {
            _isBusy.value = true
            val next = !_isVulkanActive.value
            try {
                val res = performanceEngine.forceVulkanRenderer(next).getOrThrow()
                _isVulkanActive.value = next
                _userFeedbackMessage.emit(res)
                reconcileEngineState()
            } catch (e: Exception) {
                _userFeedbackMessage.emit("Vulkan error: ${e.message}")
                reconcileEngineState()
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun toggleSurfaceFlingerLatency() {
        viewModelScope.launch {
            _isBusy.value = true
            val next = !_isSurfaceFlingerUncapped.value
            try {
                val res = performanceEngine.setSurfaceFlingerLowLatency(next).getOrThrow()
                _isSurfaceFlingerUncapped.value = next
                _userFeedbackMessage.emit(res)
                reconcileEngineState()
            } catch (e: Exception) {
                _userFeedbackMessage.emit("SurfaceFlinger error: ${e.message}")
                reconcileEngineState()
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun setRefreshRate(hz: Int) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val res = performanceEngine.lockRefreshRate(hz.toFloat()).getOrThrow()
                _selectedRefreshRate.value = hz
                _userFeedbackMessage.emit(res)
                reconcileEngineState()
            } catch (e: Exception) {
                _userFeedbackMessage.emit("Refresh rate error: ${e.message}")
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun setPrivateDns(hostname: String?) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val res = performanceEngine.setPrivateDns(hostname).getOrThrow()
                _selectedDns.value = hostname
                _userFeedbackMessage.emit(res)
                reconcileEngineState()
            } catch (e: Exception) {
                _userFeedbackMessage.emit("DNS error: ${e.message}")
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun compileGameShaders(game: GameProfile) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val res = performanceEngine.compileAppSpeedProfile(game.packageName).getOrThrow()
                performanceEngine.whitelistAppFromDoze(game.packageName)
                performanceEngine.setAndroidGameMode(game.packageName, "performance")

                val updated = _installedGames.value.map {
                    if (it.packageName == game.packageName) it.copy(isCompiled = true, isOptimized = true)
                    else it
                }
                _installedGames.value = updated
                gamePrefs.edit().putBoolean("compiled/${game.packageName}", true).apply()
                _userFeedbackMessage.emit("AOT compilation completed for ${game.appName}.")
            } catch (e: Exception) {
                _userFeedbackMessage.emit("Compilation error: ${e.message}")
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun trimCaches() {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val res = performanceEngine.trimSystemCaches().getOrThrow()
                _userFeedbackMessage.emit(res)
            } catch (e: Exception) {
                _userFeedbackMessage.emit("Cache trim error: ${e.message}")
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun toggleHudOverlay() {
        if (!Settings.canDrawOverlays(context)) {
            viewModelScope.launch {
                _userFeedbackMessage.emit("Overlay permission required. Tap 'Grant Overlay Permission'.")
            }
            return
        }

        val intent = Intent(context, OverlayHudService::class.java)
        if (OverlayHudService.isHudRunning.value) {
            intent.action = OverlayHudService.ACTION_STOP_HUD
        } else {
            intent.action = OverlayHudService.ACTION_START_HUD
        }
        context.startService(intent)
    }

    fun toggleCrosshair() {
        if (!Settings.canDrawOverlays(context)) {
            viewModelScope.launch {
                _userFeedbackMessage.emit("Overlay permission required for crosshair.")
            }
            return
        }
        val intent = Intent(context, OverlayHudService::class.java).apply {
            action = OverlayHudService.ACTION_TOGGLE_CROSSHAIR
        }
        context.startService(intent)
    }

    fun updateCrosshairSettings(color: Int, sizeDp: Int, strokeDp: Int) {
        OverlayHudService.setCrosshairConfig(color, sizeDp, strokeDp)
    }

    fun launchGame(game: GameProfile) {
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(game.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            } else {
                viewModelScope.launch {
                    _userFeedbackMessage.emit("Could not launch ${game.appName}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch game", e)
        }
    }

    fun toggleGameOptimization(game: GameProfile) {
        val updatedList = _installedGames.value.map {
            if (it.packageName == game.packageName) it.copy(isOptimized = !it.isOptimized)
            else it
        }
        _installedGames.value = updatedList
        updatedList.find { it.packageName == game.packageName }?.let {
            gamePrefs.edit().putBoolean("opt/${it.packageName}", it.isOptimized).apply()
        }
    }

    private fun applyGameProfile(game: GameProfile) {
        viewModelScope.launch {
            val results = mutableListOf<String>()
            // Clamp to what the panel really supports instead of requesting a rate it doesn't have.
            val hz = _supportedRefreshRates.value
                .filter { it <= game.targetRefreshRate }.maxOrNull() ?: maxRefreshRate
            performanceEngine.lockRefreshRate(hz.toFloat())
                .onSuccess { profileLockedRefresh = true; _selectedRefreshRate.value = hz }
                .onFailure { results += "refresh: ${it.message}" }
            if (game.forceVulkan) {
                performanceEngine.forceVulkanRenderer(true)
                    .onSuccess { _isVulkanActive.value = true }
                    .onFailure { results += "vulkan: ${it.message}" }
            }
            performanceEngine.setAndroidGameMode(game.packageName, "performance")
                .onFailure { results += "game mode: ${it.message}" }
            performanceEngine.whitelistAppFromDoze(game.packageName)
                .onFailure { results += "doze: ${it.message}" }
            if (results.isNotEmpty()) {
                Log.w(TAG, "Profile for ${game.packageName} partly applied: $results")
                _userFeedbackMessage.emit("${game.appName}: ${results.size} tweak(s) unsupported on this device")
            }
        }
    }

    fun refreshDisplayConfig() {
        viewModelScope.launch {
            val config = performanceEngine.getDisplayConfig().getOrNull()
            if (config != null) {
                val resString = if (config.overrideWidth != null && config.overrideHeight != null) {
                    "${config.overrideWidth}x${config.overrideHeight} (Scaled)"
                } else {
                    "${config.physicalWidth}x${config.physicalHeight} (Native)"
                }
                _telemetryState.update { it.copy(displayResolution = resString) }
            }
        }
    }

    private fun loadInstalledGames() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val target = maxRefreshRate
            val list = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { it.packageName != context.packageName }
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 }
                .filter { app ->
                    @Suppress("DEPRECATION")
                    app.category == ApplicationInfo.CATEGORY_GAME ||
                        (app.flags and ApplicationInfo.FLAG_IS_GAME) != 0 ||
                        knownGamePrefixes.any { app.packageName.startsWith(it) }
                }
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { app ->
                    GameProfile(
                        packageName = app.packageName,
                        appName = pm.getApplicationLabel(app).toString(),
                        icon = pm.getApplicationIcon(app),
                        targetRefreshRate = target,
                        forceVulkan = true,
                        // Remembered per game; new games default to auto-boost on.
                        isOptimized = gamePrefs.getBoolean("opt/${app.packageName}", true),
                        isCompiled = gamePrefs.getBoolean("compiled/${app.packageName}", false)
                    )
                }
                .sortedBy { it.appName.lowercase() }
                .toList()

            withContext(Dispatchers.Main) { _installedGames.value = list }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Advanced tweaks
    // ------------------------------------------------------------------------------------------

    private fun refreshAdvanced() {
        val active = performanceEngine.activeAdvancedTweaks()
        _advancedActive.value = active
        _levels.value = listOf("font", "dim", "timeout").associateWith { key ->
            if (key in active) gamePrefs.getInt("lvl/$key", 0) else 0
        }
    }

    private fun runAdvanced(name: String, label: String, apply: suspend (Boolean) -> Result<String>) {
        viewModelScope.launch {
            _isBusy.value = true
            val next = name !in _advancedActive.value
            try {
                val res = apply(next).getOrThrow()
                _advancedActive.update { if (next) it + name else it - name }
                _userFeedbackMessage.emit(res)
                reconcileEngineState()
            } catch (e: Exception) {
                _userFeedbackMessage.emit("$label error: ${e.message}")
            } finally {
                _isBusy.value = false
            }
        }
    }

    private fun applyLevel(key: String, index: Int, label: String, apply: suspend () -> Result<String>) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                val res = apply().getOrThrow()
                gamePrefs.edit().putInt("lvl/$key", index).apply()
                _levels.update { it + (key to index) }
                _advancedActive.update { if (index == 0) it - key else it + key }
                _userFeedbackMessage.emit(res)
                reconcileEngineState()
            } catch (e: Exception) {
                _userFeedbackMessage.emit("$label error: ${e.message}")
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun toggleCachedProcesses() = runAdvanced("cachedprocs", "Cached apps") { performanceEngine.setCachedProcessLimit(it) }
    fun toggleStayAwake() = runAdvanced("stayawake", "Stay awake") { performanceEngine.setStayAwakeWhileCharging(it) }
    fun toggleLandscapeLock() = runAdvanced("landscape", "Landscape lock") { performanceEngine.setLandscapeLock(it) }
    fun toggleRadioQuiet() = runAdvanced("radio", "Radio quiet") { performanceEngine.setRadioQuiet(it) }
    fun toggleTouchOverlay() = runAdvanced("touchoverlay", "Touch overlay") { performanceEngine.setTouchOverlay(it) }
    fun toggleNightLight() = runAdvanced("nightlight", "Night Light") { performanceEngine.setNightLight(it) }
    fun toggleGrayscale() = runAdvanced("grayscale", "Grayscale") { performanceEngine.setGrayscale(it) }
    fun toggleForceDark() = runAdvanced("forcedark", "Dark theme") { performanceEngine.setForceDark(it) }

    fun setFontLevel(i: Int) = applyLevel("font", i, "Font size") { performanceEngine.setFontScale(FONT_STEPS[i]) }
    fun setDimLevel(i: Int) = applyLevel("dim", i, "Extra Dim") { performanceEngine.setExtraDim(DIM_STEPS[i]) }
    fun setTimeoutLevel(i: Int) = applyLevel("timeout", i, "Screen timeout") { performanceEngine.setScreenTimeout(TIMEOUT_STEPS[i]) }

    fun copyDiagnostics() {
        val t = _telemetryState.value
        val adv = _advancedActive.value.joinToString().ifBlank { "none" }
        val report = buildString {
            appendLine("HyperBoost Engine Pro report")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("Display: ${t.displayResolution} @ ${t.refreshRateHz} Hz")
            appendLine("RAM: ${t.ramUsedMb}/${t.ramTotalMb} MB")
            appendLine("Temps: CPU ${t.cpuTempCelsius ?: "--"} C, GPU ${t.gpuTempCelsius ?: "--"} C, battery ${t.batteryTempCelsius ?: "--"} C")
            appendLine("Shizuku: ${shizukuStatus.value} (v${shizukuVersion.value})")
            appendLine("Core boosters active: ${activeBoostersCount.value}; advanced: $adv")
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("HyperBoost report", report))
        emitMessage("Device report copied to the clipboard.")
    }

    // ------------------------------------------------------------------------------------------
    // Stability Guard: heat / low battery cap to 60 Hz, low-RAM clean during games
    // ------------------------------------------------------------------------------------------

    fun toggleStabilityGuard() {
        val next = !_stabilityGuardOn.value
        _stabilityGuardOn.value = next
        gamePrefs.edit().putBoolean("auto/guard", next).apply()
        if (next) {
            startStabilityGuard()
            emitMessage("Stability Guard on.")
        } else {
            stabilityJob?.cancel()
            stabilityJob = null
            telemetryHub.release("guard")
            if (guardRestoreHz != null) cleanupScope.launch { releaseRefreshCap() }
            emitMessage("Stability Guard off.")
        }
    }

    private fun startStabilityGuard() {
        stabilityJob?.cancel()
        telemetryHub.acquire("guard", STABILITY_CHECK_MS) // keeps sampling alive while a game is in front
        stabilityJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(STABILITY_CHECK_MS)
                if (shizukuStatus.value != ShizukuManager.ShizukuStatus.AUTHORIZED) continue
                guardTick(_telemetryState.value)
            }
        }
    }

    private suspend fun guardTick(t: TelemetryState) {
        val cpu = t.cpuTempCelsius
        val gpu = t.gpuTempCelsius
        val bat = t.batteryTempCelsius
        val lowBattery = t.batteryPercent in 0..GUARD_LOW_BATTERY_PCT && !t.isCharging
        val hot = (cpu != null && cpu >= GUARD_CPU_HOT_C) ||
            (gpu != null && gpu >= GUARD_GPU_HOT_C) ||
            (bat != null && bat >= GUARD_BATTERY_HOT_C)
        val cool = (cpu == null || cpu <= GUARD_CPU_COOL_C) &&
            (gpu == null || gpu <= GUARD_GPU_COOL_C) &&
            (bat == null || bat <= GUARD_BATTERY_COOL_C)

        if ((hot || lowBattery) && guardRestoreHz == null && t.refreshRateHz > 60) {
            val previous = performanceEngine.readRefreshLock() ?: 0
            performanceEngine.lockRefreshRate(60f).onSuccess {
                guardRestoreHz = previous
                _selectedRefreshRate.value = 60
                _userFeedbackMessage.emit(
                    if (hot) "Stability Guard: device is hot, refresh capped at 60 Hz."
                    else "Stability Guard: battery is low, refresh capped at 60 Hz."
                )
            }
        } else if (guardRestoreHz != null && cool && !lowBattery) {
            releaseRefreshCap()
        }

        // Low free RAM while an optimized game is running: clear cached background apps (with a cooldown).
        val freePct = if (t.ramTotalMb > 0) (t.ramTotalMb - t.ramUsedMb) * 100f / t.ramTotalMb else 100f
        val now = System.currentTimeMillis()
        if (activeProfilePackage != null && freePct < GUARD_LOW_RAM_FREE_PCT &&
            now - lastAutoCleanMs > GUARD_CLEAN_COOLDOWN_MS
        ) {
            lastAutoCleanMs = now
            performanceEngine.killBackgroundApps().onSuccess {
                _userFeedbackMessage.emit("Stability Guard: only ${freePct.toInt()}% RAM free, background apps cleared.")
            }
        }
    }

    private suspend fun releaseRefreshCap() {
        val hz = guardRestoreHz ?: return
        val r = if (hz > 0) performanceEngine.lockRefreshRate(hz.toFloat()) else performanceEngine.resetRefreshRate()
        if (r.isSuccess) {
            guardRestoreHz = null
            _selectedRefreshRate.value = if (hz > 0) hz else telemetryHub.telemetry.value.refreshRateHz
            _userFeedbackMessage.emit("Stability Guard: temperatures are normal again, refresh rate restored.")
        }
    }

    // ------------------------------------------------------------------------------------------
    // Game Session Automation (runs only for games with Optimize switched on)
    // ------------------------------------------------------------------------------------------

    fun toggleSessionAutomation(key: String) {
        val next = _sessionAutomation.value.let { if (key in it) it - key else it + key }
        _sessionAutomation.value = next
        gamePrefs.edit().putStringSet("auto/session", next).apply()
    }

    private suspend fun startGameSession() {
        val wanted = _sessionAutomation.value
        if ("dnd" in wanted && !_isGameDndActive.value) {
            performanceEngine.setGameDnd(true).onSuccess { _isGameDndActive.value = true; sessionApplied += "dnd" }
        }
        if ("stayawake" in wanted && "stayawake" !in _advancedActive.value) {
            performanceEngine.setStayAwakeWhileCharging(true)
                .onSuccess { _advancedActive.update { it + "stayawake" }; sessionApplied += "stayawake" }
        }
        if ("landscape" in wanted && "landscape" !in _advancedActive.value) {
            performanceEngine.setLandscapeLock(true)
                .onSuccess { _advancedActive.update { it + "landscape" }; sessionApplied += "landscape" }
        }
        if ("clean" in wanted) performanceEngine.killBackgroundApps()
    }

    /** Undoes only what the session itself switched on; tweaks you enabled by hand stay as they are. */
    private suspend fun endGameSession() {
        for (key in sessionApplied.toList()) {
            when (key) {
                "dnd" -> performanceEngine.setGameDnd(false).onSuccess { _isGameDndActive.value = false }
                "stayawake" -> performanceEngine.setStayAwakeWhileCharging(false)
                    .onSuccess { _advancedActive.update { it - "stayawake" } }
                "landscape" -> performanceEngine.setLandscapeLock(false)
                    .onSuccess { _advancedActive.update { it - "landscape" } }
            }
        }
        sessionApplied.clear()
    }
}