package com.hyperboost.engine.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.hyperboost.engine.MainActivity
import com.hyperboost.engine.R
import com.hyperboost.engine.core.PerformanceEngine
import com.hyperboost.engine.telemetry.TelemetryHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * One-tap Master Boost from the Quick Settings panel. It runs the same rootless modules as the
 * in-app Master Boost, but only while a short-lived foreground service keeps the process alive.
 */
class QuickBoostService : Service() {

    companion object {
        private const val CHANNEL_ID = "hyperboost_quick_boost"
        private const val NOTIFICATION_ID = 9003
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        running = true
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Applying Master Boost…"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )

        scope.launch {
            try {
                val ready = try {
                    Shizuku.pingBinder() &&
                        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                } catch (_: Throwable) {
                    false
                }
                if (!ready) {
                    update("Start Shizuku and authorize HyperBoost, then try again.")
                    delay(4_000)
                    return@launch
                }

                val engine = PerformanceEngine(this@QuickBoostService)
                suspend fun step(block: suspend () -> Result<String>) {
                    runCatching { block() }
                }

                step { engine.forceVulkanRenderer(true) }
                step { engine.setWindowAnimationScale(0f) }
                step { engine.setTouchLatencyBoost(true) }
                step { engine.setWifiGamingOptimization(true) }
                step { engine.setSurfaceFlingerLowLatency(true) }
                step { engine.setSustainedPerformance(true) }
                step { engine.setPhantomProcessProtection(true) }
                step { engine.setBatteryThrottlingOff(true) }

                val hz = TelemetryHub.get(this@QuickBoostService)
                    .supportedRefreshRates().maxOrNull() ?: 60
                engine.lockRefreshRate(hz.toFloat())
                engine.killBackgroundApps()

                val state = engine.readState().getOrNull()
                val verified = state?.let {
                    listOf(
                        it.touchBoost, it.zeroAnimations, it.vulkan, it.surfaceFlingerLatch,
                        it.sustainedPerformance, it.phantomProtection, it.batterySaverOff, it.wifiGaming
                    ).count { active -> active }
                } ?: 0
                update(if (verified > 0) "Master Boost applied: $verified/8 active" else "Boost requested; open HyperBoost for details")
                refreshTile()
                delay(4_000)
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    private fun update(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HyperBoost Engine Pro")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(launch)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun refreshTile() {
        try {
            TileService.requestListeningState(
                this,
                android.content.ComponentName(this, BoostTileService::class.java)
            )
        } catch (_: Exception) {
            // Tile is not present; nothing to refresh.
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Quick boost",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Runs Master Boost from the Quick Settings tile"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}
