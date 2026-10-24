package com.hyperboost.engine.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** Receives install completion from Android's PackageInstaller for in-app updates. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (confirmation != null) {
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirmation)
            } else {
                saveFailure(context, "Android requested installation confirmation but did not provide its confirmation screen.")
            }
            return
        }

        if (status == PackageInstaller.STATUS_SUCCESS) return

        val installerMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty().trim()
        val friendlyMessage = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation was cancelled. Tap Update and install to try again."
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android or the device administrator blocked this installation."
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "The installed app conflicts with this update. Check that you downloaded the official HyperBoost release."
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This update is not compatible with this device or the installed app signature."
            PackageInstaller.STATUS_FAILURE_INVALID -> "Android rejected the APK as invalid or incomplete. Download the update again and retry."
            PackageInstaller.STATUS_FAILURE_STORAGE -> "There is not enough free storage to install this update."
            PackageInstaller.STATUS_FAILURE_TIMEOUT -> "Android timed out while installing the update. Try again."
            else -> "Android could not install the update (status $status)."
        }
        val detail = installerMessage.takeIf { it.isNotBlank() && !it.equals(friendlyMessage, ignoreCase = true) }
        saveFailure(context, friendlyMessage + (detail?.let { "\n\nAndroid details: $it" } ?: ""))
    }

    private fun saveFailure(context: Context, message: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_FAILURE, message)
            .apply()
        context.sendBroadcast(
            Intent(ACTION_INSTALL_FAILED)
                .setPackage(context.packageName)
        )
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.hyperboost.engine.action.UPDATE_INSTALL_STATUS"
        const val ACTION_INSTALL_FAILED = "com.hyperboost.engine.action.UPDATE_INSTALL_FAILED"
        const val PREFS = "hyperboost_updates"
        const val KEY_FAILURE = "pending_install_failure"
        const val EXTRA_VERSION = "update_version"
    }
}
