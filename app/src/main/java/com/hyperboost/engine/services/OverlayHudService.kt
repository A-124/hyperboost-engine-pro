// app/src/main/java/com/hyperboost/engine/services/OverlayHudService.kt
package com.hyperboost.engine.services

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.hyperboost.engine.MainActivity
import com.hyperboost.engine.R
import com.hyperboost.engine.telemetry.TelemetryHub
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground Service hosting the floating draggable real-time HUD and gaming crosshair.
 */
class OverlayHudService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG = "OverlayHudService"
        const val CHANNEL_ID = "hyperboost_hud_channel"
        const val NOTIFICATION_ID = 9001

        const val ACTION_START_HUD = "com.hyperboost.engine.START_HUD"
        const val ACTION_STOP_HUD = "com.hyperboost.engine.STOP_HUD"
        const val ACTION_TOGGLE_CROSSHAIR = "com.hyperboost.engine.TOGGLE_CROSSHAIR"

        private val _isHudRunning = MutableStateFlow(false)
        val isHudRunning: StateFlow<Boolean> = _isHudRunning.asStateFlow()

        private val _isCrosshairActive = MutableStateFlow(false)
        val isCrosshairActive: StateFlow<Boolean> = _isCrosshairActive.asStateFlow()

        private val _crosshairColorHex = MutableStateFlow(0xFF00E5FF.toInt())
        val crosshairColorHex: StateFlow<Int> = _crosshairColorHex.asStateFlow()

        private val _crosshairSizeDp = MutableStateFlow(24)
        val crosshairSizeDp: StateFlow<Int> = _crosshairSizeDp.asStateFlow()

        private val _crosshairStrokeDp = MutableStateFlow(2)
        val crosshairStrokeDp: StateFlow<Int> = _crosshairStrokeDp.asStateFlow()

        fun setCrosshairConfig(color: Int, size: Int, stroke: Int) {
            _crosshairColorHex.value = color
            _crosshairSizeDp.value = size
            _crosshairStrokeDp.value = stroke
        }
    }

    // Lifecycle requirements for hosting ComposeView inside a Service
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var hudComposeView: ComposeView? = null
    private var crosshairComposeView: ComposeView? = null

    private val telemetryHub by lazy { TelemetryHub.get(applicationContext) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(Bundle())
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        telemetryHub.acquire("hud", 750L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_HUD -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_CROSSHAIR -> {
                // Keep the service alive (foreground) even when only the crosshair is shown.
                startForeground(NOTIFICATION_ID, buildNotification())
                _isCrosshairActive.value = !_isCrosshairActive.value
                updateCrosshairView()
                if (!_isCrosshairActive.value && hudComposeView == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                _isHudRunning.value = true
                showHudOverlay()
                updateCrosshairView()
            }
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        return START_STICKY
    }

    private fun showHudOverlay() {
        if (hudComposeView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 180
        }

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(this@OverlayHudService)
            setViewTreeViewModelStoreOwner(this@OverlayHudService)
            setViewTreeSavedStateRegistryOwner(this@OverlayHudService)

            setContent {
                HudOverlayContent(
                    params = params,
                    onUpdateParams = { windowManager?.updateViewLayout(this@apply, params) },
                    onClose = { stopSelf() }
                )
            }
        }

        try {
            windowManager?.addView(composeView, params)
            hudComposeView = composeView
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add HUD overlay window", e)
        }
    }

    private fun updateCrosshairView() {
        if (_isCrosshairActive.value) {
            if (crosshairComposeView == null) {
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.CENTER
                }

                val view = ComposeView(this).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    setViewTreeLifecycleOwner(this@OverlayHudService)
                    setViewTreeViewModelStoreOwner(this@OverlayHudService)
                    setViewTreeSavedStateRegistryOwner(this@OverlayHudService)

                    setContent {
                        CrosshairOverlayContent()
                    }
                }

                try {
                    windowManager?.addView(view, params)
                    crosshairComposeView = view
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to add crosshair window", e)
                }
            }
        } else {
            crosshairComposeView?.let {
                try {
                    windowManager?.removeView(it)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to remove crosshair view", e)
                }
                crosshairComposeView = null
            }
        }
    }

    @Composable
    private fun CrosshairOverlayContent() {
        val colorHex by _crosshairColorHex.collectAsState()
        val sizeDp by _crosshairSizeDp.collectAsState()
        val strokeDp by _crosshairStrokeDp.collectAsState()

        val color = Color(colorHex)

        Box(
            modifier = Modifier.size(sizeDp.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val midX = w / 2f
                val midY = h / 2f
                val strokePx = strokeDp.dp.toPx()
                val gap = w * 0.2f

                // Top line
                drawLine(
                    color = color,
                    start = Offset(midX, 0f),
                    end = Offset(midX, midY - gap),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round
                )
                // Bottom line
                drawLine(
                    color = color,
                    start = Offset(midX, midY + gap),
                    end = Offset(midX, h),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round
                )
                // Left line
                drawLine(
                    color = color,
                    start = Offset(0f, midY),
                    end = Offset(midX - gap, midY),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round
                )
                // Right line
                drawLine(
                    color = color,
                    start = Offset(midX + gap, midY),
                    end = Offset(w, midY),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round
                )
                // Center dot
                drawCircle(
                    color = color,
                    radius = strokePx * 0.75f,
                    center = Offset(midX, midY)
                )
            }
        }
    }

    @Composable
    private fun HudOverlayContent(
        params: WindowManager.LayoutParams,
        onUpdateParams: () -> Unit,
        onClose: () -> Unit
    ) {
        val t by telemetryHub.telemetry.collectAsState()
        var isExpanded by remember { mutableStateOf(true) }

        val fps = t.fps
        // "Good" is judged against the panel's own refresh rate, not a hard-coded 55 FPS.
        val fpsOk = fps != null && fps >= t.refreshRateHz * 0.9f
        val fpsColor = when {
            fps == null -> Color(0xFF8B9CB3)
            fpsOk -> Color(0xFF00E676)
            fps >= t.refreshRateHz * 0.5f -> Color(0xFFFFD54F)
            else -> Color(0xFFFF9100)
        }

        Box(
            modifier = Modifier
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            params.x += dragAmount.x.toInt()
                            params.y += dragAmount.y.toInt()
                            onUpdateParams()
                        }
                    )
                }
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xE6101622))
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier.width(if (isExpanded) 200.dp else 96.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(fpsColor))
                        Text(
                            text = if (fps != null) "${fps.toInt()} FPS" else "-- FPS",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Row {
                        IconButton(onClick = { isExpanded = !isExpanded }, modifier = Modifier.size(24.dp)) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "Toggle HUD Size",
                                tint = Color(0xFF8B9CB3),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close HUD",
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                AnimatedVisibility(visible = isExpanded) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (fps == null) {
                            Text(
                                t.statusNote ?: "No frame data",
                                color = Color(0xFF90A4AE),
                                fontSize = 10.sp
                            )
                        } else {
                            FrametimeGraph(history = t.frametimeHistory, avgMs = t.avgFrametimeMs ?: 0f, refreshHz = t.refreshRateHz)
                            HudRow("1% Low", t.onePercentLowFps?.let { "${it.toInt()} FPS" } ?: "--", Color.White)
                            val jank = t.jankPercentage ?: 0f
                            HudRow("Jank", "$jank%", if (jank < 5f) Color(0xFF69F0AE) else Color(0xFFFF5252))
                        }

                        t.cpuUsagePct?.let { HudRow("CPU", "${it.toInt()}%", if (it < 85f) Color.White else Color(0xFFFF5252)) }
                        t.cpuTempC?.let { HudRow("CPU Temp", "${it.toInt()}°C", tempColor(it)) }
                        t.gpuTempC?.let { HudRow("GPU Temp", "${it.toInt()}°C", tempColor(it)) }
                        t.batteryTempC?.let { HudRow("Battery", "$it°C", tempColor(it, 40f)) }

                        val ramFrac = if (t.ramTotalMb > 0) (t.ramUsedMb.toFloat() / t.ramTotalMb).coerceIn(0f, 1f) else 0f
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            HudRow("RAM", "${t.ramUsedMb} / ${t.ramTotalMb} MB", Color.White)
                            LinearProgressIndicator(
                                progress = { ramFrac },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = if (ramFrac < 0.8f) Color(0xFF00E5FF) else Color(0xFFFF5252),
                                trackColor = Color(0x33FFFFFF)
                            )
                        }

                        t.powerMw?.let {
                            HudRow(if (t.isCharging) "Charge in" else "Power", "$it mW", Color(0xFFFFD54F))
                        }
                        if (fps != null) {
                            Text(
                                "src: ${t.frameSource.label} · ${t.refreshRateHz}Hz",
                                color = Color(0xFF607D8B),
                                fontSize = 9.sp
                            )
                        }
                    }
                }
            }
        }
    }

    private fun tempColor(c: Float, warn: Float = 80f): Color =
        if (c < warn) Color(0xFF69F0AE) else Color(0xFFFF5252)

    @Composable
    private fun HudRow(label: String, value: String, valueColor: Color) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color(0xFF90A4AE), fontSize = 11.sp)
            Text(
                value,
                color = valueColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
        }
    }

    @Composable
    private fun FrametimeGraph(history: List<Float>, avgMs: Float, refreshHz: Int) {
        // Scale to the panel: guide line at one vsync, graph tops out at 3 vsyncs (min 40 ms).
        val vsyncMs = 1000f / refreshHz.coerceAtLeast(24)
        val maxMs = maxOf(40f, vsyncMs * 3f)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            HudRow("Frametime", "$avgMs ms", Color(0xFF00E5FF))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .background(Color(0x33000000), RoundedCornerShape(4.dp))
                    .padding(2.dp)
            ) {
                val w = size.width
                val h = size.height
                val targetY = h * (1f - (vsyncMs / maxMs).coerceIn(0f, 1f))
                drawLine(
                    color = Color(0x4400E5FF),
                    start = Offset(0f, targetY),
                    end = Offset(w, targetY),
                    strokeWidth = 1.dp.toPx()
                )
                if (history.size >= 2) {
                    val stepX = w / (history.size - 1)
                    val path = Path()
                    history.forEachIndexed { i, ms ->
                        val y = (1f - (ms / maxMs).coerceIn(0f, 1f)) * h
                        if (i == 0) path.moveTo(0f, y) else path.lineTo(i * stepX, y)
                    }
                    drawPath(path = path, color = Color(0xFF00E5FF), style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HyperBoost Performance HUD",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live performance telemetry overlay notification"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, OverlayHudService::class.java).apply {
            action = ACTION_STOP_HUD
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HyperBoost Telemetry Active")
            .setContentText("Rendering real-time gaming HUD and frametime monitor.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop HUD", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        telemetryHub.release("hud")

        hudComposeView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing HUD view on destroy", e)
            }
            hudComposeView = null
        }

        crosshairComposeView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing crosshair view on destroy", e)
            }
            crosshairComposeView = null
        }

        _isHudRunning.value = false
        _isCrosshairActive.value = false

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        Log.i(TAG, "OverlayHudService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
