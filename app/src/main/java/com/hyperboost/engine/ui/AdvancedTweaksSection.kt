// app/src/main/java/com/hyperboost/engine/ui/AdvancedTweaksSection.kt
package com.hyperboost.engine.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** System customization, visuals, stability and automation tools (all restored by "Restore"). */
@Composable
fun AdvancedTweaksSection(viewModel: MainViewModel) {
    val active by viewModel.advancedActive.collectAsState()
    val levels by viewModel.levels.collectAsState()
    val guardOn by viewModel.stabilityGuardOn.collectAsState()
    val session by viewModel.sessionAutomation.collectAsState()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GroupLabel("SYSTEM CUSTOMIZATION")
        EasyToggleCard(
            title = "Keep More Apps Cached",
            description = "Raises the cached-app limit from 32 to 64 so games and chats reload less (Android 12+)",
            icon = Icons.Default.Memory,
            isActive = "cachedprocs" in active,
            onToggle = { viewModel.toggleCachedProcesses() },
            tag = "toggle_cached_processes"
        )
        EasyToggleCard(
            title = "Stay Awake While Charging",
            description = "Screen never sleeps while plugged in. Good for long sessions on a charger",
            icon = Icons.Default.BatteryChargingFull,
            isActive = "stayawake" in active,
            onToggle = { viewModel.toggleStayAwake() },
            tag = "toggle_stay_awake"
        )
        EasyToggleCard(
            title = "Landscape Lock",
            description = "Locks rotation to landscape. Your original auto-rotate setting is restored afterwards",
            icon = Icons.Default.ScreenLockLandscape,
            isActive = "landscape" in active,
            onToggle = { viewModel.toggleLandscapeLock() },
            tag = "toggle_landscape_lock"
        )
        EasyToggleCard(
            title = "Quiet Radios",
            description = "Stops background Bluetooth scans and the always-on cellular link beside Wi-Fi",
            icon = Icons.Default.WifiOff,
            isActive = "radio" in active,
            onToggle = { viewModel.toggleRadioQuiet() },
            tag = "toggle_radio_quiet"
        )
        EasyToggleCard(
            title = "Touch Overlay",
            description = "Shows touch dots and live X/Y coordinates. Use it to pick Auto Tap positions",
            icon = Icons.Default.TouchApp,
            isActive = "touchoverlay" in active,
            onToggle = { viewModel.toggleTouchOverlay() },
            tag = "toggle_touch_overlay"
        )
        ChoiceCard(
            title = "Screen Timeout",
            description = "Longer timeout for sessions where you don't touch the screen often",
            icon = Icons.Default.Timer,
            options = listOf("Default", "1 min", "5 min", "15 min"),
            selected = levels["timeout"] ?: 0,
            onSelect = { viewModel.setTimeoutLevel(it) }
        )

        GroupLabel("VISUAL TWEAKS")
        EasyToggleCard(
            title = "Force Dark Theme",
            description = "Switches the whole system to dark (saves power on OLED). Android 10+",
            icon = Icons.Default.DarkMode,
            isActive = "forcedark" in active,
            onToggle = { viewModel.toggleForceDark() },
            tag = "toggle_force_dark"
        )
        EasyToggleCard(
            title = "Night Light",
            description = "Warm, low-blue-light screen for late-night sessions",
            icon = Icons.Default.NightsStay,
            isActive = "nightlight" in active,
            onToggle = { viewModel.toggleNightLight() },
            tag = "toggle_night_light"
        )
        EasyToggleCard(
            title = "Grayscale Display",
            description = "Monochrome screen: calmer on the eyes, less OLED draw",
            icon = Icons.Default.FilterBAndW,
            isActive = "grayscale" in active,
            onToggle = { viewModel.toggleGrayscale() },
            tag = "toggle_grayscale"
        )
        ChoiceCard(
            title = "Extra Dim",
            description = "Dims below the lowest brightness level (Android 12+)",
            icon = Icons.Default.BrightnessLow,
            options = listOf("Off", "25%", "50%", "75%"),
            selected = levels["dim"] ?: 0,
            onSelect = { viewModel.setDimLevel(it) }
        )
        ChoiceCard(
            title = "Font Size",
            description = "System-wide text scale. Smaller fits more on screen, larger is easier to read",
            icon = Icons.Default.FormatSize,
            options = listOf("Default", "0.85×", "1.15×", "1.3×"),
            selected = levels["font"] ?: 0,
            onSelect = { viewModel.setFontLevel(it) }
        )

        GroupLabel("STABILITY & AUTOMATION")
        EasyToggleCard(
            title = "Stability Guard",
            description = "Caps to 60 Hz when hot (CPU 55°C / GPU 58°C / battery 42°C) or under 15% battery, " +
                "then restores it. Clears background apps mid-game if free RAM drops below 10%",
            icon = Icons.Default.Shield,
            isActive = guardOn,
            onToggle = { viewModel.toggleStabilityGuard() },
            tag = "toggle_stability_guard"
        )
        SessionCard(
            selected = session,
            onToggle = { viewModel.toggleSessionAutomation(it) }
        )
        ActionTile(
            modifier = Modifier.fillMaxWidth(),
            title = "Copy Device Report",
            subtitle = "Device, display, RAM, temperatures and active tweaks, ready to paste into a bug report",
            icon = Icons.Default.ContentCopy,
            tint = Color(0xFF40C4FF),
            onClick = { viewModel.copyDiagnostics() }
        )
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun ChoiceCard(
    title: String,
    description: String,
    icon: ImageVector,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    val active = selected != 0
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (active) Color(0xFF13202E) else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    icon, contentDescription = null,
                    tint = if (active) Color(0xFF00E5FF) else Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (active) Color(0xFF00E5FF) else Color.White
                    )
                    Text(text = description, style = MaterialTheme.typography.bodySmall, color = Color(0xFF90A4AE), fontSize = 11.sp)
                }
            }
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEachIndexed { index, label ->
                    FilterChip(selected = index == selected, onClick = { onSelect(index) }, label = { Text(label) })
                }
            }
        }
    }
}

@Composable
private fun SessionCard(selected: Set<String>, onToggle: (String) -> Unit) {
    val options = listOf(
        "dnd" to "Do Not Disturb",
        "clean" to "Clean RAM",
        "stayawake" to "Stay awake",
        "landscape" to "Landscape lock"
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                Column {
                    Text(
                        text = "Game Session Automation",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Text(
                        text = "Runs when a game with Optimize on comes to the front, and undoes only what it switched on when you leave",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF90A4AE),
                        fontSize = 11.sp
                    )
                }
            }
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (key, label) ->
                    FilterChip(selected = key in selected, onClick = { onToggle(key) }, label = { Text(label) })
                }
            }
        }
    }
}