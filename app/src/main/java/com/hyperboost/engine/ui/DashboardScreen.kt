// app/src/main/java/com/hyperboost/engine/ui/DashboardScreen.kt
package com.hyperboost.engine.ui

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import android.content.Intent
import com.hyperboost.engine.ads.AdManager
import com.hyperboost.engine.ads.UnityBanner
import com.hyperboost.engine.ads.findActivity
import kotlinx.coroutines.launch
import com.hyperboost.engine.core.PerformanceEngine
import com.hyperboost.engine.core.ShizukuManager
import com.hyperboost.engine.telemetry.CapabilityReporter
import com.hyperboost.engine.ui.components.HbCard
import com.hyperboost.engine.ui.components.StatChip
import com.hyperboost.engine.ui.components.StatTile
import com.hyperboost.engine.ui.theme.DangerRed
import com.hyperboost.engine.ui.theme.NeonCyan
import com.hyperboost.engine.ui.theme.NeonGreen
import com.hyperboost.engine.ui.theme.TextSecondary

/** Top-level sections of the dashboard. Each tab shows a focused set of cards. */
private enum class DashTab(val label: String, val icon: ImageVector) {
    Boost("Boost", Icons.Default.Bolt),
    Monitor("Monitor", Icons.Default.Monitor),
    Tools("Tools", Icons.Default.Build),
    Games("Games", Icons.Default.SportsEsports)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onOpenOverlaySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onCheckForUpdates: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shizukuStatus by viewModel.shizukuStatus.collectAsState()
    val shizukuVersion by viewModel.shizukuVersion.collectAsState()
    val telemetry by viewModel.telemetryState.collectAsState()
    val games by viewModel.installedGames.collectAsState()
    val isHudRunning by viewModel.isHudRunning.collectAsState()
    val isCrosshairActive by viewModel.isCrosshairActive.collectAsState()
    val isAccessibilityActive by viewModel.isAccessibilityActive.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()

    // Easy Enable States
    val isMasterTurboActive by viewModel.isMasterTurboActive.collectAsState()
    val isTouchBoostActive by viewModel.isTouchBoostActive.collectAsState()
    val isZeroAnimationActive by viewModel.isZeroAnimationActive.collectAsState()
    val isWifiGamingActive by viewModel.isWifiGamingActive.collectAsState()
    val isVulkanActive by viewModel.isVulkanActive.collectAsState()
    val isSurfaceFlingerUncapped by viewModel.isSurfaceFlingerUncapped.collectAsState()
    val isCachedFreezerActive by viewModel.isCachedFreezerActive.collectAsState()
    val isAdaptiveBrightnessOff by viewModel.isAdaptiveBrightnessOff.collectAsState()
    val isShowRefreshRateActive by viewModel.isShowRefreshRateActive.collectAsState()
    val selectedRefreshRate by viewModel.selectedRefreshRate.collectAsState()
    val supportedRefreshRates by viewModel.supportedRefreshRates.collectAsState()
    val selectedDns by viewModel.selectedDns.collectAsState()
    val activeBoostersCount by viewModel.activeBoostersCount.collectAsState()
    val isSustainedPerfActive by viewModel.isSustainedPerfActive.collectAsState()
    val isGameDndActive by viewModel.isGameDndActive.collectAsState()
    val isPhantomProtectionActive by viewModel.isPhantomProtectionActive.collectAsState()
    val isBatteryThrottleOff by viewModel.isBatteryThrottleOff.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val adContext = LocalContext.current
    val adScope = rememberCoroutineScope()
    val isAdFree by AdManager.isAdFree.collectAsState()
    var showCrosshairDialog by remember { mutableStateOf(false) }
    var showAdbGuideDialog by remember { mutableStateOf(false) }
    var showFeedbackDialog by remember { mutableStateOf(false) }
    var configuringModeGame by remember { mutableStateOf<MainViewModel.GameProfile?>(null) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val currentTab = DashTab.entries[selectedTab]
    val shizukuAuthorized = shizukuStatus == ShizukuManager.ShizukuStatus.AUTHORIZED

    LaunchedEffect(Unit) {
        viewModel.userFeedbackMessage.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column {
                UnityBanner()
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    DashTab.entries.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = index == selectedTab,
                            onClick = { selectedTab = index },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = NeonCyan,
                                selectedTextColor = NeonCyan,
                                indicatorColor = NeonCyan.copy(alpha = 0.16f)
                            )
                        )
                    }
                }
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "HyperBoost",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp
                            )
                        )
                        Text(
                            text = "Performance engine",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = NeonCyan
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    StatChip(
                        label = if (shizukuAuthorized) "SHIZUKU" else "NO SHIZUKU",
                        color = if (shizukuAuthorized) NeonGreen else DangerRed
                    )
                    IconButton(
                        onClick = onCheckForUpdates,
                        modifier = Modifier.testTag("check_updates_button")
                    ) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = "Check for updates", tint = NeonCyan)
                    }
                    IconButton(onClick = { showFeedbackDialog = true }) {
                        Icon(Icons.Default.Feedback, contentDescription = "Send feedback", tint = NeonCyan)
                    }
                    IconButton(
                        onClick = { viewModel.reevaluateShizuku() },
                        modifier = Modifier.testTag("refresh_status_button")
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh status",
                            tint = NeonCyan
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
        ) {
            // Shizuku status stays visible on every tab.
            item(key = "shizuku_status") {
                ShizukuStatusBanner(
                    status = shizukuStatus,
                    version = shizukuVersion,
                    onRequestPermission = { viewModel.requestShizukuPermission() },
                    onOpenGuide = { showAdbGuideDialog = true }
                )
            }

            when (currentTab) {
                DashTab.Boost -> {
                    item(key = "master_turbo") {
                        MasterTurboHeroCard(
                            isActive = isMasterTurboActive,
                            activeCount = activeBoostersCount,
                            totalCount = MainViewModel.TOTAL_BOOSTERS,
                            isBusy = isBusy,
                            onToggle = {
                                viewModel.toggleMasterTurbo()
                                AdManager.onBoostAction(adContext.findActivity())
                            }
                        )
                    }
                    item(key = "easy_enable") {
                        EasyEnableOptimizationSection(
                            isTouchBoostActive = isTouchBoostActive,
                            onToggleTouch = { viewModel.toggleTouchBoost() },
                            isZeroAnimationActive = isZeroAnimationActive,
                            onToggleAnimations = { viewModel.toggleZeroAnimations() },
                            isWifiGamingActive = isWifiGamingActive,
                            onToggleWifi = { viewModel.toggleWifiGaming() },
                            isVulkanActive = isVulkanActive,
                            onToggleVulkan = { viewModel.toggleVulkan() },
                            isSurfaceFlingerUncapped = isSurfaceFlingerUncapped,
                            onToggleSurfaceFlinger = { viewModel.toggleSurfaceFlingerLatency() },
                            selectedRefreshRate = selectedRefreshRate,
                            supportedRefreshRates = supportedRefreshRates,
                            onSelectRefreshRate = { viewModel.setRefreshRate(it) },
                            selectedDns = selectedDns,
                            onSelectDns = { viewModel.setPrivateDns(it) },
                            onTrimCaches = { viewModel.trimCaches(); AdManager.onFeatureUsed(adContext.findActivity()) }
                        )
                    }
                    item(key = "gaming_tuning") {
                        GamingTuningSection(
                            isCachedFreezerActive = isCachedFreezerActive,
                            onToggleFreezer = { viewModel.toggleCachedAppsFreezer() },
                            isAdaptiveBrightnessOff = isAdaptiveBrightnessOff,
                            onToggleAdaptiveBrightness = { viewModel.toggleAdaptiveBrightness() },
                            isShowRefreshRateActive = isShowRefreshRateActive,
                            onToggleShowRefreshRate = { viewModel.toggleShowRefreshRate() }
                        )
                    }
                    item(key = "advanced_tuning") {
                        AdvancedTuningSection(
                            isSustainedPerfActive = isSustainedPerfActive,
                            onToggleSustainedPerf = { viewModel.toggleSustainedPerformance() },
                            isGameDndActive = isGameDndActive,
                            onToggleGameDnd = { viewModel.toggleGameDnd() },
                            isPhantomProtectionActive = isPhantomProtectionActive,
                            onTogglePhantom = { viewModel.togglePhantomProtection() },
                            isBatteryThrottleOff = isBatteryThrottleOff,
                            onToggleBattery = { viewModel.toggleBatteryThrottle() },
                            onKillBackground = { viewModel.killBackgroundApps(); AdManager.onFeatureUsed(adContext.findActivity()) },
                            onStorageMaintenance = { viewModel.runStorageMaintenance(); AdManager.onFeatureUsed(adContext.findActivity()) },
                            onRestoreDefaults = { viewModel.restoreDefaults() }
                        )
                    }
                    item(key = "ad_tile") {
                        ActionTile(
                            modifier = Modifier.fillMaxWidth(),
                            title = if (isAdFree) "Ads are off for now" else "Go ad-free for 1 hour",
                            subtitle = if (isAdFree) "Thanks for supporting HyperBoost!" else "Watch one short video to hide all ads",
                            icon = Icons.Default.Favorite,
                            tint = NeonGreen,
                            onClick = {
                                AdManager.showRewarded(adContext.findActivity()) { msg ->
                                    adScope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                            }
                        )
                    }
                }

                DashTab.Monitor -> {
                    item(key = "telemetry") {
                        LiveTelemetrySection(telemetry = telemetry)
                    }
                }

                DashTab.Tools -> {
                    item(key = "more_tools") {
                        ExtraTweaksSection(viewModel = viewModel)
                    }
                    item(key = "advanced_tweaks") {
                        AdvancedTweaksSection(viewModel = viewModel)
                    }
                    item(key = "hud_controls") {
                        HudControlsCard(
                            isHudRunning = isHudRunning,
                            isCrosshairActive = isCrosshairActive,
                            isAccessibilityActive = isAccessibilityActive,
                            onToggleHud = { viewModel.toggleHudOverlay() },
                            onToggleCrosshair = { viewModel.toggleCrosshair() },
                            onConfigureCrosshair = { showCrosshairDialog = true },
                            onOpenOverlaySettings = onOpenOverlaySettings,
                            onOpenAccessibilitySettings = onOpenAccessibilitySettings
                        )
                    }
                }

                DashTab.Games -> {
                    item(key = "games") {
                        GameLauncherSection(
                            games = games,
                            onLaunchGame = { viewModel.launchGame(it) },
                            onToggleGameOpt = { viewModel.toggleGameOptimization(it) },
                            onCompileShaders = { viewModel.compileGameShaders(it) },
                            onConfigureGameMode = { configuringModeGame = it }
                        )
                    }
                }
            }
        }
    }

    if (showCrosshairDialog) {
        CrosshairConfigDialog(
            viewModel = viewModel,
            onDismiss = { showCrosshairDialog = false }
        )
    }

    if (showAdbGuideDialog) {
        AdbGuideDialog(onDismiss = { showAdbGuideDialog = false })
    }
    if (showFeedbackDialog) {
        FeedbackDialog(
            onDismiss = { showFeedbackDialog = false },
            onSubmit = { category, message -> FeedbackRepository.submit(adContext, category, message) },
            onGitHubFallback = { category, message -> FeedbackRepository.issueUrl(adContext, category, message) },
            onResult = { sent ->
                if (sent) showFeedbackDialog = false
                adScope.launch {
                    snackbarHostState.showSnackbar(
                        if (sent) "Thanks, your feedback was sent."
                        else "Feedback was not sent. You can still send it from GitHub."
                    )
                }
            }
        )
    }
    configuringModeGame?.let { game ->
        GameModeDialog(
            game = game,
            onDismiss = { configuringModeGame = null },
            onSelect = { mode -> viewModel.setGameMode(game.packageName, mode) }
        )
    }
}

@Composable
private fun FeedbackDialog(
    onDismiss: () -> Unit,
    onSubmit: suspend (String, String) -> Result<Unit>,
    onGitHubFallback: (String, String) -> String,
    onResult: (Boolean) -> Unit
) {
    var category by rememberSaveable { mutableStateOf("Idea") }
    var message by rememberSaveable { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var shareCapability by remember { mutableStateOf(CapabilityReporter.isEnabled(context)) }

    fun openOnGitHub() {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(onGitHubFallback(category, message.trim())))
            )
            onDismiss()
        }.onFailure { error = "Couldn't open the browser. The repository is github.com/A-124/hyperboost-engine-pro." }
    }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("Send feedback") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Tell us what you think. Your message includes the app version, Android version, and device model, but no account or contact details.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Idea", "Bug", "Other").forEach { option ->
                        FilterChip(selected = category == option, onClick = { category = option }, label = { Text(option) })
                    }
                }
                OutlinedTextField(
                    value = message,
                    onValueChange = { if (it.length <= 2000) message = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Your feedback") },
                    placeholder = { Text("What would you like us to know?") },
                    minLines = 4,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    supportingText = { Text("${message.length}/2000") }
                )

                // Shown only after a failed attempt: the real reason, plus a path that always works.
                error?.let { reason ->
                    Text(reason, style = MaterialTheme.typography.bodySmall, color = DangerRed)
                    TextButton(
                        onClick = { openOnGitHub() },
                        enabled = message.isNotBlank(),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("Send on GitHub instead") }
                }

                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Share device compatibility", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Anonymous. Records which settings your device accepts or rejects — never values, app names or messages.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = shareCapability,
                        onCheckedChange = { enabled ->
                            shareCapability = enabled
                            CapabilityReporter.setEnabled(context, enabled)
                            if (enabled) CapabilityReporter.flush()
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = message.isNotBlank() && !submitting,
                onClick = {
                    submitting = true
                    error = null
                    scope.launch {
                        val result = runCatching { onSubmit(category, message.trim()) }
                            .getOrElse { Result.failure(it) }
                        submitting = false
                        if (result.isSuccess) {
                            onResult(true)
                        } else {
                            // Surface the actual reason instead of blaming the connection.
                            error = result.exceptionOrNull()?.message?.take(200)
                                ?: "Feedback could not be sent."
                            onResult(false)
                        }
                    }
                }
            ) { if (submitting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancel") } }
    )
}

/**
 * Hero card: one tap engages every rootless booster. Shows live module count
 * and state, and the whole card is tappable (the switch stays as a precise control).
 */
@Composable
fun MasterTurboHeroCard(
    isActive: Boolean,
    activeCount: Int,
    totalCount: Int,
    isBusy: Boolean,
    onToggle: () -> Unit
) {
    val safeTotal = if (totalCount <= 0) 1 else totalCount
    val shownCount = if (isActive) totalCount else activeCount
    val progress = if (isActive) 1f else (activeCount.toFloat() / safeTotal).coerceIn(0f, 1f)
    val statusLabel = when {
        isBusy -> "WORKING"
        isActive -> "ENGAGED"
        else -> "STANDBY"
    }

    HbCard(
        modifier = Modifier.testTag("master_turbo_card"),
        accent = if (isActive) NeonCyan else null,
        onClick = if (isBusy) null else onToggle
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .then(
                        if (isActive) Modifier.background(
                            Brush.linearGradient(listOf(NeonCyan, NeonGreen))
                        ) else Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    tint = if (isActive) Color(0xFF001E2B) else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(30.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Master Boost",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.4.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (isActive) "All subsystems engaged" else "One tap to accelerate your games",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = isActive,
                onCheckedChange = { onToggle() },
                enabled = !isBusy,
                modifier = Modifier.testTag("switch_master_turbo")
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatTile(
                label = "Modules",
                value = "$shownCount/$totalCount",
                valueColor = NeonCyan,
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "Status",
                value = statusLabel,
                valueColor = when (statusLabel) {
                    "ENGAGED" -> NeonGreen
                    "WORKING" -> NeonCyan
                    else -> TextSecondary
                },
                modifier = Modifier.weight(1f)
            )
        }

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = if (isActive) NeonGreen else NeonCyan,
            trackColor = Color(0x1FFFFFFF)
        )
    }
}

/**
 * Easy Enable Optimization Section: Clear, interactive toggles for all modules.
 */
@Composable
fun EasyEnableOptimizationSection(
    isTouchBoostActive: Boolean,
    onToggleTouch: () -> Unit,
    isZeroAnimationActive: Boolean,
    onToggleAnimations: () -> Unit,
    isWifiGamingActive: Boolean,
    onToggleWifi: () -> Unit,
    isVulkanActive: Boolean,
    onToggleVulkan: () -> Unit,
    isSurfaceFlingerUncapped: Boolean,
    onToggleSurfaceFlinger: () -> Unit,
    selectedRefreshRate: Int,
    supportedRefreshRates: List<Int>,
    onSelectRefreshRate: (Int) -> Unit,
    selectedDns: String?,
    onSelectDns: (String?) -> Unit,
    onTrimCaches: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "EASY ROOTLESS BOOST MODULES",
            style = MaterialTheme.typography.labelMedium.copy(
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.primary
        )

        // 1. Touch & Latency Overclock Card
        EasyToggleCard(
            title = "Touch & Debounce Overclock",
            description = "Configures supported touch-prediction and debounce options; results vary by device",
            icon = Icons.Default.TouchApp,
            isActive = isTouchBoostActive,
            onToggle = onToggleTouch,
            tag = "toggle_touch_boost",
            status = if (isTouchBoostActive) "Verified on this device" else null
        )

        // 2. Zero Window Animations Card
        EasyToggleCard(
            title = "Zero Window Animations (0.0x)",
            description = "Eliminates UI transition latency for instantaneous app & game response",
            icon = Icons.Default.Speed,
            isActive = isZeroAnimationActive,
            onToggle = onToggleAnimations,
            tag = "toggle_zero_animations"
        )

        // 3. Wi-Fi Gaming Low-Latency Card
        EasyToggleCard(
            title = "Wi-Fi Low-Latency Lock",
            description = "Reduces power-save Wi-Fi stalls where the device supports it",
            icon = Icons.Default.Wifi,
            isActive = isWifiGamingActive,
            onToggle = onToggleWifi,
            tag = "toggle_wifi_gaming",
            status = if (isWifiGamingActive) "Verified on this device" else null
        )

        // 4. Force Vulkan GPU Engine Card
        EasyToggleCard(
            title = "Force Vulkan GPU Pipeline",
            description = "Routes system HWUI and game driver rendering through Vulkan",
            icon = Icons.Default.Layers,
            isActive = isVulkanActive,
            onToggle = onToggleVulkan,
            tag = "toggle_vulkan"
        )

        // 5. SurfaceFlinger Uncapped Latching Card
        EasyToggleCard(
            title = "SurfaceFlinger Instant Latch",
            description = "Unsignaled buffer submission eliminates compositor queue latency",
            icon = Icons.Default.Timeline,
            isActive = isSurfaceFlingerUncapped,
            onToggle = onToggleSurfaceFlinger,
            tag = "toggle_surfaceflinger"
        )

        // Refresh Rate Selector Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(20.dp))
                        Text("Refresh Rate Lock", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Text("${selectedRefreshRate}Hz Active", fontSize = 12.sp, color = Color(0xFF00E676), fontWeight = FontWeight.Bold)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    supportedRefreshRates.forEach { hz ->
                        val isSelected = selectedRefreshRate == hz
                        OutlinedButton(
                            onClick = { onSelectRefreshRate(hz) },
                            modifier = Modifier.weight(1f).testTag("btn_hz_$hz"),
                            colors = if (isSelected) ButtonDefaults.outlinedButtonColors(
                                containerColor = Color(0xFF00E5FF),
                                contentColor = Color(0xFF001E2B)
                            ) else ButtonDefaults.outlinedButtonColors()
                        ) {
                            Text("${hz}Hz", fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        }

        // DNS & Cache Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // DNS Selector Card
            Card(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        when (selectedDns) {
                            null -> onSelectDns(PerformanceEngine.DNS_CLOUDFLARE)
                            PerformanceEngine.DNS_CLOUDFLARE -> onSelectDns(PerformanceEngine.DNS_GOOGLE)
                            else -> onSelectDns(null)
                        }
                    },
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Dns, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(16.dp))
                        Text("Gaming DNS", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Text(
                        text = when (selectedDns) {
                            PerformanceEngine.DNS_CLOUDFLARE -> "Cloudflare 1.1.1.1"
                            PerformanceEngine.DNS_GOOGLE -> "Google DNS"
                            else -> "Default (Tap to Switch)"
                        },
                        fontSize = 11.sp,
                        color = if (selectedDns != null) Color(0xFF00E676) else Color(0xFF8B9CB3),
                        maxLines = 1
                    )
                }
            }

            // Cache Trim Button Card
            Card(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onTrimCaches() },
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.CleaningServices, contentDescription = null, tint = Color(0xFFFF9100), modifier = Modifier.size(16.dp))
                        Text("Trim Caches", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Text(
                        text = "Trim app caches (up to 5 GB)",
                        fontSize = 11.sp,
                        color = Color(0xFF8B9CB3),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** Resolution presets plus the extra gaming tuning toggles. */
@Composable
fun GamingTuningSection(
    isCachedFreezerActive: Boolean,
    onToggleFreezer: () -> Unit,
    isAdaptiveBrightnessOff: Boolean,
    onToggleAdaptiveBrightness: () -> Unit,
    isShowRefreshRateActive: Boolean,
    onToggleShowRefreshRate: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "MORE GAMING TUNING",
            style = MaterialTheme.typography.labelMedium.copy(
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.primary
        )

        EasyToggleCard(
            title = "Cached Apps Freezer",
            description = "Freezes cached apps more aggressively (Android 11+)",
            icon = Icons.Default.AcUnit,
            isActive = isCachedFreezerActive,
            onToggle = onToggleFreezer,
            tag = "toggle_cached_freezer",
            status = if (isCachedFreezerActive) "Verified on this device" else null
        )

        EasyToggleCard(
            title = "Adaptive Brightness Off",
            description = "Keeps the screen brightness fixed during a session",
            icon = Icons.Default.WbSunny,
            isActive = isAdaptiveBrightnessOff,
            onToggle = onToggleAdaptiveBrightness,
            tag = "toggle_adaptive_brightness",
            status = if (isAdaptiveBrightnessOff) "Verified on this device" else null
        )

        EasyToggleCard(
            title = "Show Refresh Rate",
            description = "Adds Android's refresh-rate overlay",
            icon = Icons.Default.Speed,
            isActive = isShowRefreshRateActive,
            onToggle = onToggleShowRefreshRate,
            tag = "toggle_show_refresh_rate",
            status = if (isShowRefreshRateActive) "Verified on this device" else null
        )
    }
}

/**
 * Advanced rootless tuning: power / thermal / background-process / storage tweaks plus a
 * one-tap "restore my original settings" panic button.
 */
@Composable
fun AdvancedTuningSection(
    isSustainedPerfActive: Boolean,
    onToggleSustainedPerf: () -> Unit,
    isGameDndActive: Boolean,
    onToggleGameDnd: () -> Unit,
    isPhantomProtectionActive: Boolean,
    onTogglePhantom: () -> Unit,
    isBatteryThrottleOff: Boolean,
    onToggleBattery: () -> Unit,
    onKillBackground: () -> Unit,
    onStorageMaintenance: () -> Unit,
    onRestoreDefaults: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "ADVANCED ROOTLESS TUNING",
            style = MaterialTheme.typography.labelMedium.copy(
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.primary
        )

        EasyToggleCard(
            title = "Sustained Performance Mode",
            description = "Holds steadier CPU/GPU clocks to cut thermal throttling (Android 12+)",
            icon = Icons.Default.Bolt,
            isActive = isSustainedPerfActive,
            onToggle = onToggleSustainedPerf,
            tag = "toggle_sustained_perf"
        )

        EasyToggleCard(
            title = "Phantom Process Shield",
            description = "Stops Android 12+ from randomly killing game & emulator child processes",
            icon = Icons.Default.Shield,
            isActive = isPhantomProtectionActive,
            onToggle = onTogglePhantom,
            tag = "toggle_phantom_shield"
        )

        EasyToggleCard(
            title = "No Battery Throttling",
            description = "Turns off Battery Saver and Adaptive Battery; device behavior varies",
            icon = Icons.Default.BatteryChargingFull,
            isActive = isBatteryThrottleOff,
            onToggle = onToggleBattery,
            tag = "toggle_battery_throttle"
        )

        EasyToggleCard(
            title = "Game Do Not Disturb",
            description = "Priority-only DND: no popups or heads-up banners mid-match",
            icon = Icons.Default.NotificationsOff,
            isActive = isGameDndActive,
            onToggle = onToggleGameDnd,
            tag = "toggle_game_dnd"
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionTile(
                modifier = Modifier.weight(1f),
                title = "Clear Background",
                subtitle = "Kill cached apps",
                icon = Icons.Default.Memory,
                tint = Color(0xFF00E5FF),
                onClick = onKillBackground
            )
            ActionTile(
                modifier = Modifier.weight(1f),
                title = "Storage Boost",
                subtitle = "TRIM + dexopt",
                icon = Icons.Default.Storage,
                tint = Color(0xFFFF9100),
                onClick = onStorageMaintenance
            )
        }

        ActionTile(
            modifier = Modifier.fillMaxWidth(),
            title = "Restore My Original Settings",
            subtitle = "Undo every tweak back to the exact values you had before",
            icon = Icons.Default.RestartAlt,
            tint = Color(0xFFFF5252),
            onClick = onRestoreDefaults
        )
    }
}

@Composable
fun ActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Text(text = subtitle, fontSize = 11.sp, color = Color(0xFF8B9CB3))
        }
    }
}

/**
 * Reusable Easy-Toggle Card with description, icon, and switch.
 */
@Composable
fun EasyToggleCard(
    title: String,
    description: String,
    icon: ImageVector,
    isActive: Boolean,
    onToggle: () -> Unit,
    tag: String,
    status: String? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onToggle() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) Color(0xFF13202E) else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (isActive) CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(listOf(Color(0xFF00E5FF), Color(0xFF00E676)))
        ) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (isActive) Color(0xFF00E5FF) else Color(0xFF222C3D)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isActive) Color(0xFF001E2B) else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (isActive) Color(0xFF00E5FF) else Color.White
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF90A4AE),
                        fontSize = 11.sp
                    )
                    if (status != null) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isActive) NeonGreen else Color(0xFF90A4AE),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                }
            }

            Switch(
                checked = isActive,
                onCheckedChange = { onToggle() },
                modifier = Modifier.testTag(tag)
            )
        }
    }
}

@Composable
fun ShizukuStatusBanner(
    status: ShizukuManager.ShizukuStatus,
    version: Int,
    onRequestPermission: () -> Unit,
    onOpenGuide: () -> Unit
) {
    val isAuthorized = status == ShizukuManager.ShizukuStatus.AUTHORIZED
    val isRunning = status != ShizukuManager.ShizukuStatus.NOT_RUNNING

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isAuthorized) Color(0x1A00E676) else Color(0x22FF5252)
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(
                listOf(
                    if (isAuthorized) Color(0xFF00E676) else Color(0xFFFF5252),
                    Color(0xFF00E5FF)
                )
            )
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = if (isAuthorized) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = "Status Icon",
                        tint = if (isAuthorized) Color(0xFF00E676) else Color(0xFFFF5252),
                        modifier = Modifier.size(28.dp)
                    )
                    Column {
                        Text(
                            text = when (status) {
                                ShizukuManager.ShizukuStatus.AUTHORIZED -> "Shizuku Shell Elevated"
                                ShizukuManager.ShizukuStatus.UNAUTHORIZED -> "Shizuku Authorization Needed"
                                ShizukuManager.ShizukuStatus.NOT_RUNNING -> "Shizuku Service Inactive"
                            },
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Text(
                            text = if (isAuthorized) "Privileged Binder API v$version active"
                            else "Rootless command execution requires Shizuku",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF90A4AE)
                        )
                    }
                }
            }

            if (!isAuthorized) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isRunning) {
                        Button(
                            onClick = onRequestPermission,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_request_shizuku"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00E5FF),
                                contentColor = Color(0xFF001E2B)
                            )
                        ) {
                            Text("Grant Permission", fontWeight = FontWeight.Bold)
                        }
                    }
                    OutlinedButton(
                        onClick = onOpenGuide,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("btn_shizuku_guide")
                    ) {
                        Text("Setup Guide")
                    }
                }
            }
        }
    }
}

@Composable
fun LiveTelemetrySection(telemetry: MainViewModel.TelemetryState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "LIVE ENGINE TELEMETRY",
            style = MaterialTheme.typography.labelMedium.copy(
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.primary
        )

        val fps = telemetry.fps
        val hz = telemetry.refreshRateHz
        val fpsColor = when {
            fps == null -> Color(0xFF8B9CB3)
            fps >= hz * 0.9f -> Color(0xFF00E676)
            fps >= hz * 0.5f -> Color(0xFFFFD54F)
            else -> Color(0xFFFF9100)
        }
        val jank = telemetry.jankPercentage
        val grey = Color(0xFF8B9CB3)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TelemetryCard(Modifier.weight(1f), 125.dp, "LIVE FPS", Icons.Default.Speed, Color(0xFF00E5FF)) {
                Text(
                    text = fps?.let { "${it.toInt()}" } ?: "--",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = fpsColor
                )
                Text(
                    text = if (fps != null)
                        "1% low ${telemetry.onePercentLowFps?.toInt() ?: "--"} · ${hz}Hz panel"
                    else telemetry.statusNote ?: "No data",
                    fontSize = 10.sp,
                    color = grey,
                    maxLines = 2
                )
            }
            TelemetryCard(Modifier.weight(1f), 125.dp, "FRAMETIME", Icons.Default.Timeline, Color(0xFF00E5FF)) {
                Text(
                    text = telemetry.frametimeMs?.let { "$it ms" } ?: "--",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = Color.White
                )
                Text(
                    text = jank?.let { "Jank: $it%" } ?: "Jank: --",
                    fontSize = 11.sp,
                    color = when {
                        jank == null -> grey
                        jank < 5f -> Color(0xFF00E676)
                        else -> Color(0xFFFF5252)
                    }
                )
                Text(
                    text = if (fps != null) "via ${telemetry.frameSource}" else "not measuring",
                    fontSize = 10.sp,
                    color = grey
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TelemetryCard(Modifier.weight(1f), 105.dp, "CPU / GPU", Icons.Default.Memory, Color(0xFF00E5FF)) {
                Text(
                    text = telemetry.cpuUsagePct?.let { "${it.toInt()}% load" } ?: "-- load",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White
                )
                val cpuT = telemetry.cpuTempCelsius?.let { "${it.toInt()}°" } ?: "--"
                val gpuT = telemetry.gpuTempCelsius?.let { "${it.toInt()}°" } ?: "--"
                Text("CPU $cpuT · GPU $gpuT", fontSize = 11.sp, color = grey)
            }
            TelemetryCard(Modifier.weight(1f), 105.dp, "MEMORY", Icons.Default.Memory, Color(0xFF00E5FF)) {
                Text(
                    text = "${telemetry.ramUsedMb} / ${telemetry.ramTotalMb} MB",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White
                )
                val ramFrac = if (telemetry.ramTotalMb > 0)
                    (telemetry.ramUsedMb.toFloat() / telemetry.ramTotalMb.toFloat()).coerceIn(0f, 1f)
                else 0f
                LinearProgressIndicator(
                    progress = { ramFrac },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = if (ramFrac < 0.8f) Color(0xFF00E5FF) else Color(0xFFFF5252),
                    trackColor = Color(0x33FFFFFF)
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TelemetryCard(Modifier.weight(1f), 105.dp, "BATTERY / POWER", Icons.Default.DeviceThermostat, Color(0xFFFF9100)) {
                val bt = telemetry.batteryTempCelsius
                Text(
                    text = bt?.let { "$it°C" } ?: "--",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = if (bt == null) grey else if (bt < 40f) Color(0xFF00E676) else Color(0xFFFF5252)
                )
                val pw = telemetry.powerDrainMw
                Text(
                    text = when {
                        pw == null -> "power n/a"
                        telemetry.isCharging -> "charging +$pw mW"
                        else -> "drawing $pw mW"
                    },
                    fontSize = 10.sp,
                    color = grey
                )
            }
            TelemetryCard(Modifier.weight(1f), 105.dp, "DISPLAY", Icons.Default.Speed, Color(0xFF00E5FF)) {
                Text(
                    text = "${hz}Hz",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = Color.White
                )
                Text(text = telemetry.displayResolution, fontSize = 10.sp, color = grey, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TelemetryCard(
    modifier: Modifier,
    height: androidx.compose.ui.unit.Dp,
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.height(height),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontSize = 11.sp, color = Color(0xFF90A4AE), fontWeight = FontWeight.Bold)
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            }
            content()
        }
    }
}

@Composable
fun HudControlsCard(
    isHudRunning: Boolean,
    isCrosshairActive: Boolean,
    isAccessibilityActive: Boolean,
    onToggleHud: () -> Unit,
    onToggleCrosshair: () -> Unit,
    onConfigureCrosshair: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "FLOATING OVERLAY & SERVICES",
                style = MaterialTheme.typography.labelMedium.copy(
                    letterSpacing = 1.2.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.primary
            )

            // Telemetry HUD Toggle Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Floating Telemetry HUD", fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Live draggable FPS, frametime curve, and temp", fontSize = 12.sp, color = Color(0xFF90A4AE))
                }
                Switch(
                    checked = isHudRunning,
                    onCheckedChange = { onToggleHud() },
                    modifier = Modifier.testTag("switch_hud_overlay")
                )
            }

            HorizontalDivider(color = Color(0x1FFFFFFF))

            // Crosshair Toggle Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Screen Center Crosshair", fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Hardware crosshair overlay for shooter games", fontSize = 12.sp, color = Color(0xFF90A4AE))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(
                        onClick = onConfigureCrosshair,
                        modifier = Modifier.testTag("btn_configure_crosshair")
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = "Crosshair Settings", tint = MaterialTheme.colorScheme.primary)
                    }
                    Switch(
                        checked = isCrosshairActive,
                        onCheckedChange = { onToggleCrosshair() },
                        modifier = Modifier.testTag("switch_crosshair")
                    )
                }
            }

            HorizontalDivider(color = Color(0x1FFFFFFF))

            // Permissions helper buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenOverlaySettings,
                    modifier = Modifier.weight(1f).testTag("btn_overlay_permission")
                ) {
                    Text("Overlay Perms", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onOpenAccessibilitySettings,
                    modifier = Modifier.weight(1f).testTag("btn_accessibility_service")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isAccessibilityActive) Color(0xFF00E676) else Color(0xFFFF5252))
                        )
                        Text("Game Detection", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun GameLauncherSection(
    games: List<MainViewModel.GameProfile>,
    onLaunchGame: (MainViewModel.GameProfile) -> Unit,
    onToggleGameOpt: (MainViewModel.GameProfile) -> Unit,
    onCompileShaders: (MainViewModel.GameProfile) -> Unit,
    onConfigureGameMode: (MainViewModel.GameProfile) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "GAME PROFILES & ART PRE-COMPILER",
                style = MaterialTheme.typography.labelMedium.copy(
                    letterSpacing = 1.2.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "${games.size} Apps",
                fontSize = 12.sp,
                color = Color(0xFF90A4AE)
            )
        }

        if (games.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No games detected on this device", color = Color(0xFF8B9CB3))
                }
            }
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(games) { game ->
                    GameCard(
                        game = game,
                        onLaunch = { onLaunchGame(game) },
                        onToggleOpt = { onToggleGameOpt(game) },
                        onCompile = { onCompileShaders(game) },
                        onConfigureGameMode = { onConfigureGameMode(game) }
                    )
                }
            }
        }
    }
}

@Composable
fun GameCard(
    game: MainViewModel.GameProfile,
    onLaunch: () -> Unit,
    onToggleOpt: () -> Unit,
    onCompile: () -> Unit,
    onConfigureGameMode: () -> Unit
) {
    val modeLabel = game.gameMode.replaceFirstChar { it.uppercase() }
    Card(
        modifier = Modifier
            .width(170.dp)
            .height(210.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = if (game.isOptimized)
            CardDefaults.outlinedCardBorder().copy(
                brush = Brush.verticalGradient(listOf(Color(0xFF00E5FF), Color(0xFF00E676)))
            )
        else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                if (game.icon != null) {
                    val bitmap = remember(game.icon) {
                        game.icon.toBitmap(96, 96).asImageBitmap()
                    }
                    Image(
                        bitmap = bitmap,
                        contentDescription = game.appName,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFF2C394B), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.SportsEsports, contentDescription = null, tint = Color.White)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onConfigureGameMode,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = "Game mode",
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Switch(
                        checked = game.isOptimized,
                        onCheckedChange = { onToggleOpt() },
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = game.appName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    color = Color.White
                )
                Text(
                    text = (if (game.isCompiled) "✓ AOT Shaders Compiled"
                    else if (game.isOptimized) "Auto-Boost ${game.targetRefreshRate}Hz"
                    else "Standard Profile") + " · $modeLabel",
                    fontSize = 10.sp,
                    color = if (game.isCompiled) Color(0xFF00E676)
                    else if (game.isOptimized) Color(0xFF00E5FF)
                    else Color(0xFF8B9CB3)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = onCompile,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("Pre-compile AOT", fontSize = 10.sp)
                }

                Button(
                    onClick = onLaunch,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00E5FF),
                        contentColor = Color(0xFF001E2B)
                    )
                ) {
                    Text("Launch", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun GameModeDialog(
    game: MainViewModel.GameProfile,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val modes = listOf(
        "performance" to "Performance",
        "standard" to "Standard",
        "battery" to "Battery"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Game mode") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(game.appName, fontWeight = FontWeight.Bold)
                Text(
                    "Android 12+ only. Applied when this game comes to the front with Optimize on; support varies by device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF90A4AE)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    modes.forEach { (id, label) ->
                        FilterChip(
                            selected = game.gameMode == id,
                            onClick = {
                                onSelect(id)
                                onDismiss()
                            },
                            label = { Text(label) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun CrosshairConfigDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val currentColorHex by viewModel.crosshairColorHex.collectAsState()
    val currentSize by viewModel.crosshairSizeDp.collectAsState()
    val currentStroke by viewModel.crosshairStrokeDp.collectAsState()

    var selectedColor by remember { mutableStateOf(currentColorHex) }
    var selectedSize by remember { mutableStateOf(currentSize) }
    var selectedStroke by remember { mutableStateOf(currentStroke) }

    val presetColors = listOf(
        0xFF00E5FF.toInt(), // Cyan
        0xFF00E676.toInt(), // Green
        0xFFFF5252.toInt(), // Red
        0xFFFFD600.toInt(), // Yellow
        0xFFFFFFFF.toInt()  // White
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crosshair HUD Customizer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Color Picker Row
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Reticle Color", fontSize = 12.sp, color = Color(0xFF90A4AE))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        presetColors.forEach { color ->
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(color))
                                    .border(
                                        width = if (selectedColor == color) 3.dp else 1.dp,
                                        color = if (selectedColor == color) Color.White else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable { selectedColor = color }
                            )
                        }
                    }
                }

                // Size Slider
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Reticle Size: ${selectedSize}dp", fontSize = 12.sp, color = Color(0xFF90A4AE))
                    Slider(
                        value = selectedSize.toFloat(),
                        onValueChange = { selectedSize = it.toInt() },
                        valueRange = 16f..48f,
                        steps = 7
                    )
                }

                // Stroke Slider
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Stroke Thickness: ${selectedStroke}dp", fontSize = 12.sp, color = Color(0xFF90A4AE))
                    Slider(
                        value = selectedStroke.toFloat(),
                        onValueChange = { selectedStroke = it.toInt() },
                        valueRange = 1f..6f,
                        steps = 4
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.updateCrosshairSettings(selectedColor, selectedSize, selectedStroke)
                    onDismiss()
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun AdbGuideDialog(onDismiss: () -> Unit) {
    val steps = listOf(
        "1. Install Shizuku from Google Play or from its GitHub page.",
        "2. Open Settings > About phone and tap Build number 7 times to enable Developer options.",
        "3. Open Settings > Developer options and turn on Wireless debugging. Keep Wi-Fi on.",
        "4. Open Shizuku and follow its Wireless debugging steps to pair with a pairing code.",
        "5. In Shizuku, start the service using Wireless debugging.",
        "6. Come back here, tap the refresh icon at the top, then tap Grant Permission."
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Easy Shizuku Setup") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Android 11 or newer. No computer needed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF00E5FF)
                )
                steps.forEach { step ->
                    Text(step, style = MaterialTheme.typography.bodySmall, color = Color(0xFF8B9CB3))
                }
                Text(
                    "Shizuku may need to be started again after a reboot.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF00E676)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got It")
            }
        }
    )
}