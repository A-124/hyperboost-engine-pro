// app/src/main/java/com/hyperboost/engine/MainActivity.kt
package com.hyperboost.engine

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.hyperboost.engine.update.CompatibilityResult
import com.hyperboost.engine.update.UpdateInfo
import com.hyperboost.engine.update.UpdateManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.hyperboost.engine.ads.AdManager
import com.hyperboost.engine.ui.DashboardScreen
import com.hyperboost.engine.ui.MainViewModel
import com.hyperboost.engine.ui.theme.HyperBoostTheme
import java.io.File

/**
 * Main Activity of HyperBoost Engine Pro.
 * Configures edge-to-edge Compose container, binds permissions, and launches the dashboard.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private var pendingUpdate: UpdateInfo? = null

    private val updateInstallFailureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            showPendingInstallFailure()
        }
    }

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            // Notification permission handled
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        checkPostNotificationPermission()
        AdManager.init(this)
        lifecycleScope.launch { checkForUpdates(manual = false) }

        setContent {
            HyperBoostTheme {
                DashboardScreen(
                    viewModel = viewModel,
                    onOpenOverlaySettings = { openOverlaySettings() },
                    onOpenAccessibilitySettings = { openAccessibilitySettings() },
                    onCheckForUpdates = { lifecycleScope.launch { checkForUpdates(manual = true) } },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setUiVisible(true)
        ContextCompat.registerReceiver(
            this,
            updateInstallFailureReceiver,
            IntentFilter(com.hyperboost.engine.update.UpdateInstallReceiver.ACTION_INSTALL_FAILED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        viewModel.setUiVisible(false)
        runCatching { unregisterReceiver(updateInstallFailureReceiver) }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        showPendingInstallFailure()
        viewModel.reevaluateShizuku()
        viewModel.refreshDisplayConfig()

        // If the user was redirected to settings to grant package install permission,
        // automatically resume the update download once they return.
        val pending = pendingUpdate
        if (pending != null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls())) {
            pendingUpdate = null
            startUpdateDownload(pending)
        }
    }

    private fun showPendingInstallFailure() {
        val prefs = getSharedPreferences(com.hyperboost.engine.update.UpdateInstallReceiver.PREFS, Context.MODE_PRIVATE)
        val message = prefs.getString(com.hyperboost.engine.update.UpdateInstallReceiver.KEY_FAILURE, null)
            ?: return
        prefs.edit().remove(com.hyperboost.engine.update.UpdateInstallReceiver.KEY_FAILURE).apply()
        if (!isFinishing) {
            AlertDialog.Builder(this)
                .setTitle("Update couldn't install")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private suspend fun checkForUpdates(manual: Boolean) {
        val prefs = getSharedPreferences("hyperboost_updates", Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong("last_check_timestamp", 0L)
        val now = System.currentTimeMillis()

        // Throttle automatic checks to once every 6 hours to avoid GitHub API 403 rate limits
        if (!manual && (now - lastCheck < 6 * 3600 * 1000L)) {
            return
        }

        try {
            val update = UpdateManager.fetchLatest()
            prefs.edit().putLong("last_check_timestamp", now).apply()

            val current = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
            val mandatory = UpdateManager.compareVersions(current, update.minimumSupportedVersion) < 0
            val available = UpdateManager.compareVersions(update.version, current) > 0

            if (!mandatory && !available) {
                if (manual) AlertDialog.Builder(this)
                    .setTitle("You're up to date")
                    .setMessage("HyperBoost Engine Pro $current is the latest version.")
                    .setPositiveButton("OK", null)
                    .show()
                return
            }

            val builder = AlertDialog.Builder(this)
                .setTitle(if (mandatory) "Update required" else "Update available")
                .setMessage("HyperBoost Engine Pro ${update.version} is available." +
                    (if (mandatory) "\n\nYour installed version is no longer supported. Update to continue." else "") +
                    (if (update.notes.isNotBlank()) "\n\n" + update.notes.take(900) else ""))
                .setPositiveButton("Download and install", null)
                .setCancelable(!mandatory)
            if (!mandatory) builder.setNegativeButton("Later", null)

            val dialog = builder.create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    dialog.dismiss()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                        pendingUpdate = update
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Permission required")
                            .setMessage("To install updates, please enable 'Install unknown apps' for HyperBoost.")
                            .setPositiveButton("Settings") { _, _ ->
                                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                        return@setOnClickListener
                    }
                    startUpdateDownload(update)
                }
            }
            dialog.show()
        } catch (_: Exception) {
            // Keep JSON/API/HTTP internals out of messages shown to ordinary users.
            if (manual) AlertDialog.Builder(this)
                .setTitle("Update check unavailable")
                .setMessage(
                    "We couldn't check for an update right now. Check your internet connection and try again later. " +
                        "Your installed version has not been changed."
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun startUpdateDownload(update: UpdateInfo) {
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
        }
        val statusText = TextView(this).apply {
            text = "Starting download..."
            setPadding(0, 24, 0, 0)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
            addView(progressBar)
            addView(statusText)
        }
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Downloading HyperBoost ${update.version}")
            .setView(layout)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            try {
                val apkFile = UpdateManager.downloadAndInstall(this@MainActivity, update) { bytesDownloaded, totalBytes, percent ->
                    progressBar.progress = percent
                    val mbDownloaded = bytesDownloaded / (1024 * 1024.0)
                    val mbTotal = totalBytes / (1024 * 1024.0)
                    statusText.text = "Downloading... $percent% (%.1f MB / %.1f MB)".format(mbDownloaded, mbTotal)
                }
                progressDialog.dismiss()
                handleDownloadedApk(apkFile, update)
            } catch (e: Exception) {
                progressDialog.dismiss()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Update failed")
                    .setMessage(e.message ?: "Unable to download or verify the update.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun handleDownloadedApk(apkFile: File, update: UpdateInfo) {
        val compatibility = UpdateManager.checkSignatureCompatibility(this, apkFile)
        when (compatibility) {
            is CompatibilityResult.Incompatible -> {
                // If signatures differ, Android will reject the install with "Package conflicts with an existing package".
                val savedApk = UpdateManager.saveApkToDownloads(this, apkFile, update.version)
                val location = savedApk?.label
                val message = buildString {
                    append("The update cannot overwrite the current app directly because it was signed with a different certificate ")
                    append("(common when updating between local/debug builds and official release builds).\n\n")
                    if (location != null) {
                        append("The update was saved to:\n$location\n\n")
                        append("To install:\n1. Uninstall the current HyperBoost app.\n2. Open Downloads and tap the APK.\n\n")
                    } else {
                        append("The update is ready in app storage.\n\n")
                        append("To install:\n1. Uninstall the current HyperBoost app.\n2. Tap Share APK and save it somewhere you can open.\n\n")
                    }
                    append("Sharing the APK does not install it.")
                }

                AlertDialog.Builder(this)
                    .setTitle("Package Conflict Detected")
                    .setMessage(message)
                    .setPositiveButton("Uninstall current app") { _, _ ->
                        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
                    }
                    .setNeutralButton("Share APK") { _, _ ->
                        shareApk(apkFile, update.version)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            else -> {
                UpdateManager.launchInstallIntent(this, apkFile)
            }
        }
    }

    private fun shareApk(apk: File, version: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.updates", apk)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share HyperBoost $version APK"))
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Could not share the APK")
                .setMessage(e.message ?: "The update is in app storage.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun checkPostNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun openOverlaySettings() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (ex: Exception) {
                // Fallback
            }
        }
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback
        }
    }
}
