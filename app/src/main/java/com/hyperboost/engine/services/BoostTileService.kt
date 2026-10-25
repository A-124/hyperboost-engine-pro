package com.hyperboost.engine.services

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.hyperboost.engine.MainActivity
import com.hyperboost.engine.core.PerformanceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/** Boost-only Quick Settings tile. Tapping it runs Master Boost; it never restores settings. */
class BoostTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (!shizukuReady()) {
            openApp()
            return
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, QuickBoostService::class.java)
        )
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = "Boosting…"
            updateTile()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun updateTile() {
        scope.launch {
            val state = if (shizukuReady()) {
                runCatching { PerformanceEngine(this@BoostTileService).readState().getOrNull() }.getOrNull()
            } else {
                null
            }
            val active = state?.let {
                listOf(
                    it.touchBoost, it.zeroAnimations, it.vulkan, it.surfaceFlingerLatch,
                    it.sustainedPerformance, it.phantomProtection, it.batterySaverOff, it.wifiGaming
                ).any { enabled -> enabled }
            } ?: false
            withContext(Dispatchers.Main) {
                qsTile?.apply {
                    label = "HyperBoost"
                    this.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    updateTile()
                }
            }
        }
    }

    private fun shizukuReady(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (_: Exception) {
            // The system will keep the tile; the user can open the app normally.
        }
    }
}
