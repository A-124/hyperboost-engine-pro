// app/src/main/java/com/hyperboost/engine/ui/ExtraTweaksSection.kt
package com.hyperboost.engine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperboost.engine.services.AutoTapService

/**
 * Tools ported from Benimaru Tool's feature set, plus their safety rails:
 * window blur, guarded thermal override, gyro check, auto tap and freeze apps.
 * The 720p display change is confirmed here too (it reverts after a short countdown).
 */
@Composable
fun ExtraTweaksSection(viewModel: MainViewModel) {
    val isBlurDisabled by viewModel.isBlurDisabled.collectAsState()
    val isThermalOn by viewModel.isThermalOverrideActive.collectAsState()
    val thermalVerified by viewModel.thermalVerified.collectAsState()
    val pendingSeconds by viewModel.pendingRevertSeconds.collectAsState()
    val isAutoTapRunning by viewModel.isAutoTapRunning.collectAsState()

    var showAutoTapDialog by remember { mutableStateOf(false) }
    var showFreezeDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "MORE ROOTLESS TOOLS",
            style = MaterialTheme.typography.labelMedium.copy(
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.primary
        )

        EasyToggleCard(
            title = "Disable Window Blurs",
            description = "Skips the real-time background blur behind panels and dialogs (Android 12+)",
            icon = Icons.Default.BlurOff,
            isActive = isBlurDisabled,
            onToggle = { viewModel.toggleWindowBlur() },
            tag = "toggle_window_blur"
        )

        EasyToggleCard(
            title = "Disable Thermal Throttling",
            description = "Stops the phone slowing down when hot. Guarded: turns itself off at 60°C CPU / 65°C GPU and when you close the app",
            icon = Icons.Default.Thermostat,
            isActive = isThermalOn,
            onToggle = { viewModel.toggleThermalOverride() },
            tag = "toggle_thermal_override",
            status = when {
                thermalVerified == false -> "Not active — the thermal service rejected the override"
                !isThermalOn -> null
                thermalVerified == true -> "Verified active on this device"
                else -> "Requested — this ROM does not report override state"
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionTile(
                modifier = Modifier.weight(1f),
                title = "Gyro Check",
                subtitle = "Battery Saver off, then measure gyro Hz",
                icon = Icons.Default.Sensors,
                tint = Color(0xFFB388FF),
                onClick = { viewModel.runGyroCheck() }
            )
            ActionTile(
                modifier = Modifier.weight(1f),
                title = if (isAutoTapRunning) "Stop Auto Tap" else "Auto Tap",
                subtitle = if (isAutoTapRunning) "Running. Tap ■ to stop" else "Floating tap or swipe",
                icon = Icons.Default.TouchApp,
                tint = Color(0xFF00E676),
                onClick = {
                    if (isAutoTapRunning) viewModel.stopAutoTap() else showAutoTapDialog = true
                }
            )
        }

        ActionTile(
            modifier = Modifier.fillMaxWidth(),
            title = "Network Ping Test",
            subtitle = "Measure 4-packet latency to Cloudflare using Shizuku",
            icon = Icons.Default.Speed,
            tint = Color(0xFF40C4FF),
            onClick = { viewModel.runNetworkPing() }
        )

        ActionTile(
            modifier = Modifier.fillMaxWidth(),
            title = "Freeze Apps",
            subtitle = "Disable apps you don't use. Their data is kept, and Restore undoes it",
            icon = Icons.Default.AcUnit,
            tint = Color(0xFF40C4FF),
            onClick = {
                viewModel.loadUserApps()
                showFreezeDialog = true
            }
        )
    }

    pendingSeconds?.let { seconds ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Keep this display size?") },
            text = {
                Text(
                    "Reverting in ${seconds}s if you don't confirm. " +
                        "This stops a bad display mode from leaving the screen unusable."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.keepResolution() }) { Text("Keep") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.revertResolutionNow() }) { Text("Revert now") }
            }
        )
    }

    if (showAutoTapDialog) {
        AutoTapDialog(
            onDismiss = { showAutoTapDialog = false },
            onStart = { config ->
                viewModel.startAutoTap(config)
                showAutoTapDialog = false
            }
        )
    }

    if (showFreezeDialog) {
        FreezeAppsDialog(
            viewModel = viewModel,
            onDismiss = { showFreezeDialog = false }
        )
    }
}

@Composable
private fun AutoTapDialog(
    onDismiss: () -> Unit,
    onStart: (AutoTapService.Config) -> Unit
) {
    var mode by remember { mutableStateOf(AutoTapService.Mode.TAP) }
    var x by remember { mutableStateOf("540") }
    var y by remember { mutableStateOf("1200") }
    var dx by remember { mutableStateOf("0") }
    var dy by remember { mutableStateOf("-300") }
    var intervalMs by remember { mutableStateOf("1000") }
    var swipeMs by remember { mutableStateOf("250") }

    // Keeps only digits, plus a leading minus sign for the swipe offsets.
    fun digits(text: String) = text.filter { it.isDigit() || it == '-' }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Auto Tap") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == AutoTapService.Mode.TAP,
                        onClick = { mode = AutoTapService.Mode.TAP },
                        label = { Text("Tap") }
                    )
                    FilterChip(
                        selected = mode == AutoTapService.Mode.SWIPE,
                        onClick = { mode = AutoTapService.Mode.SWIPE },
                        label = { Text("Swipe") }
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = x,
                        onValueChange = { x = digits(it) },
                        label = { Text("X (px)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = y,
                        onValueChange = { y = digits(it) },
                        label = { Text("Y (px)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (mode == AutoTapService.Mode.SWIPE) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = dx,
                            onValueChange = { dx = digits(it) },
                            label = { Text("Swipe ΔX") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = dy,
                            onValueChange = { dy = digits(it) },
                            label = { Text("Swipe ΔY") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = swipeMs,
                        onValueChange = { swipeMs = digits(it) },
                        label = { Text("Swipe duration (ms)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = intervalMs,
                    onValueChange = { intervalMs = digits(it) },
                    label = { Text("Interval (ms, min ${AutoTapService.MIN_INTERVAL_MS})") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "Find exact coordinates with Developer options → Pointer location. " +
                        "Automated input can get accounts banned in some games. " +
                        "It stops by itself after ${AutoTapService.MAX_RUN_MS / 60_000} minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF90A4AE),
                    fontSize = 11.sp
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onStart(
                        AutoTapService.Config(
                            mode = mode,
                            x = x.toIntOrNull() ?: 0,
                            y = y.toIntOrNull() ?: 0,
                            dx = dx.toIntOrNull() ?: 0,
                            dy = dy.toIntOrNull() ?: 0,
                            intervalMs = intervalMs.toLongOrNull() ?: 1_000L,
                            swipeMs = swipeMs.toLongOrNull() ?: 250L
                        )
                    )
                }
            ) { Text("Start") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun FreezeAppsDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val apps by viewModel.userApps.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Freeze apps") },
        text = {
            Column {
                Text(
                    "Freeze stops an app until you unfreeze it. Background limit can delay its notifications and sync. " +
                        "Only user-installed apps are listed; Restore returns changed policies.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF90A4AE),
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (apps.isEmpty()) {
                    Text("No user-installed apps found.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                        items(apps, key = { it.packageName }) { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.appName,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = app.packageName,
                                        fontSize = 11.sp,
                                        color = Color(0xFF90A4AE),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Freeze", fontSize = 10.sp, modifier = Modifier.padding(end = 4.dp))
                                        Switch(
                                            checked = app.frozen,
                                            onCheckedChange = { viewModel.setAppFrozen(app.packageName, it) }
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Limit", fontSize = 10.sp, modifier = Modifier.padding(end = 4.dp))
                                        Switch(
                                            checked = app.backgroundRestricted,
                                            onCheckedChange = {
                                                viewModel.setAppBackgroundRestricted(app.packageName, it)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}
