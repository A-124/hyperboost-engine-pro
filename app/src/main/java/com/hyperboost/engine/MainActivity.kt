// app/src/main/java/com/hyperboost/engine/MainActivity.kt
package com.hyperboost.engine

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
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
    private var pendingInstallDialogShown = false

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
        checkPendingInstall()
    }

    override fun onStart() {
        super.onStart()
        viewModel.setUiVisible(true)
    }

    override fun onStop() {
        viewModel.setUiVisible(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        viewModel.reevaluateShizuku()
        viewModel.refreshDisplayConfig()

        // If the user was redirected to settings to grant package install permission,
        // automatically resume the update download once they return.
        val pending = pendingUpdate
        if (pending != null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls())) {
            pendingUpdate = null
            startUpdateDownload(pending)
        }
        checkPendingInstall()
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

            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val currentName = packageInfo.versionName ?: "0.0.0"
            val currentCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            val mandatory = UpdateManager.compareVersions(currentName, update.minimumSupportedVersion) < 0
            val available = UpdateManager.isNewer(currentCode, currentName, update)

            if (!mandatory && !available) {
                if (manual) AlertDialog.Builder(this)
                    .setTitle("You're up to date")
                    .setMessage("HyperBoost Engine Pro $currentName is the latest version.")
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
                    statusText.text = UpdateManager.formatDownloadProgress(percent, bytesDownloaded, totalBytes)
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
                markPendingInstall(apkFile, update)
                try {
                    UpdateManager.launchInstallIntent(this, apkFile)
                } catch (e: Exception) {
                    clearPendingInstall()
                    AlertDialog.Builder(this)
                        .setTitle("Couldn't open installer")
                        .setMessage(
                            "Android's package installer could not be opened (${e.message}). " +
                                "You can share the APK and install it manually."
                        )
                        .setPositiveButton("Share APK") { _, _ -> shareApk(apkFile, update.version) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
    }

    private fun markPendingInstall(apk: File, update: UpdateInfo) {
        val targetCode = if (update.versionCode > 0) {
            update.versionCode
        } else {
            @Suppress("DEPRECATION")
            val info = packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info?.longVersionCode?.toInt() ?: 0
            } else {
                info?.versionCode ?: 0
            }
        }
        markPendingInstall(apk, targetCode, update.version)
    }

    private fun markPendingInstall(apk: File, targetCode: Int, version: String) {
        pendingInstallDialogShown = false
        getSharedPreferences("hyperboost_updates", Context.MODE_PRIVATE).edit()
            .putInt("pending_install_code", targetCode)
            .putString("pending_install_path", apk.absolutePath)
            .putString("pending_install_version", version)
            .apply()
    }

    private fun clearPendingInstall() {
        getSharedPreferences("hyperboost_updates", Context.MODE_PRIVATE).edit()
            .remove("pending_install_code")
            .remove("pending_install_path")
            .remove("pending_install_version")
            .apply()
    }

    /**
     * The system installer does not report back to us. If the installed versionCode did not reach
     * the target after opening it, offer the APK again instead of leaving the user stuck.
     */
    private fun checkPendingInstall() {
        if (pendingInstallDialogShown) return
        val prefs = getSharedPreferences("hyperboost_updates", Context.MODE_PRIVATE)
        val targetCode = prefs.getInt("pending_install_code", 0)
        if (targetCode <= 0) return

        val info = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (_: Exception) {
            return
        }
        val currentCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        if (currentCode >= targetCode) {
            clearPendingInstall()
            return
        }

        val path = prefs.getString("pending_install_path", null)
        val apk = path?.let { File(it) }?.takeIf { it.isFile && it.length() > 0L }
        val version = prefs.getString("pending_install_version", "update") ?: "update"
        pendingInstallDialogShown = true

        val builder = AlertDialog.Builder(this)
            .setTitle("Update didn't finish")
            .setMessage(
                "Android's installer did not complete the update to $version. " +
                    "You can open the saved APK again, share it, or check for updates again."
            )
            .setOnDismissListener { pendingInstallDialogShown = false }
            .setOnCancelListener {
                pendingInstallDialogShown = false
                clearPendingInstall()
            }

        if (apk != null) {
            builder.setPositiveButton("Open APK") { _, _ ->
                try {
                    UpdateManager.launchInstallIntent(this, apk)
                    markPendingInstall(apk, targetCode, version)
                } catch (e: Exception) {
                    clearPendingInstall()
                    AlertDialog.Builder(this)
                        .setTitle("Couldn't open installer")
                        .setMessage(e.message ?: "Share the APK and install it manually.")
                        .setPositiveButton("Share APK") { _, _ -> shareApk(apk, version) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            builder.setNeutralButton("Share APK") { _, _ ->
                clearPendingInstall()
                shareApk(apk, version)
            }
        } else {
            builder.setPositiveButton("Check again") { _, _ ->
                clearPendingInstall()
                lifecycleScope.launch { checkForUpdates(manual = true) }
            }
        }
        builder.setNegativeButton("Dismiss") { _, _ -> clearPendingInstall() }
        builder.show()
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
