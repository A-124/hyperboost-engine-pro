// app/src/main/java/com/hyperboost/engine/services/AutoTapService.kt
package com.hyperboost.engine.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.hyperboost.engine.core.ShizukuShellExecutor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Floating ■ button that repeats a tap or a swipe at a fixed screen point by running Shizuku's
 * `input` command. No Accessibility permission is needed.
 *
 * Guard rails:
 *  - the interval is clamped to at least [MIN_INTERVAL_MS];
 *  - every run stops itself after [MAX_RUN_MS];
 *  - it stops after [MAX_CONSECUTIVE_FAILURES] failed inputs in a row;
 *  - it stops when the ■ button or the notification's Stop action is used.
 */
class AutoTapService : Service() {

    enum class Mode { TAP, SWIPE }

    data class Config(
        val mode: Mode = Mode.TAP,
        val x: Int = 540,
        val y: Int = 1200,
        val dx: Int = 0,
        val dy: Int = -300,
        val intervalMs: Long = 1_000L,
        val swipeMs: Long = 250L
    ) {
        /** Keeps every value in a safe range, so a typo can't hammer the device. */
        fun sanitized(): Config = copy(
            x = x.coerceAtLeast(0),
            y = y.coerceAtLeast(0),
            intervalMs = intervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS),
            swipeMs = swipeMs.coerceIn(50L, 5_000L)
        )
    }

    companion object {
        private const val TAG = "AutoTapService"
        const val CHANNEL_ID = "hyperboost_autotap_channel"
        const val NOTIFICATION_ID = 9002

        const val ACTION_START = "com.hyperboost.engine.AUTOTAP_START"
        const val ACTION_STOP = "com.hyperboost.engine.AUTOTAP_STOP"

        private const val EXTRA_MODE = "mode"
        private const val EXTRA_X = "x"
        private const val EXTRA_Y = "y"
        private const val EXTRA_DX = "dx"
        private const val EXTRA_DY = "dy"
        private const val EXTRA_INTERVAL = "interval"
        private const val EXTRA_SWIPE_MS = "swipe_ms"

        const val MIN_INTERVAL_MS = 300L
        const val MAX_INTERVAL_MS = 60_000L
        const val MAX_RUN_MS = 30 * 60_000L
        private const val MAX_CONSECUTIVE_FAILURES = 5

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun intentFor(context: Context, config: Config): Intent {
            val c = config.sanitized()
            return Intent(context, AutoTapService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODE, c.mode.name)
                putExtra(EXTRA_X, c.x)
                putExtra(EXTRA_Y, c.y)
                putExtra(EXTRA_DX, c.dx)
                putExtra(EXTRA_DY, c.dy)
                putExtra(EXTRA_INTERVAL, c.intervalMs)
                putExtra(EXTRA_SWIPE_MS, c.swipeMs)
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var overlay: TextView? = null
    private var loopJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (loopJob?.isActive == true) return START_NOT_STICKY
                startForeground(NOTIFICATION_ID, buildNotification())
                showOverlayButton()
                startLoop(configFrom(intent))
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        removeOverlayButton()
        _isRunning.value = false
        super.onDestroy()
    }

    private fun configFrom(intent: Intent): Config = Config(
        mode = runCatching { Mode.valueOf(intent.getStringExtra(EXTRA_MODE).orEmpty()) }.getOrDefault(Mode.TAP),
        x = intent.getIntExtra(EXTRA_X, 540),
        y = intent.getIntExtra(EXTRA_Y, 1200),
        dx = intent.getIntExtra(EXTRA_DX, 0),
        dy = intent.getIntExtra(EXTRA_DY, -300),
        intervalMs = intent.getLongExtra(EXTRA_INTERVAL, 1_000L),
        swipeMs = intent.getLongExtra(EXTRA_SWIPE_MS, 250L)
    ).sanitized()

    private fun startLoop(config: Config) {
        _isRunning.value = true
        val startedAt = SystemClock.elapsedRealtime()
        loopJob = scope.launch {
            var failures = 0
            while (isActive) {
                if (SystemClock.elapsedRealtime() - startedAt >= MAX_RUN_MS) {
                    Log.i(TAG, "Auto tap reached its time limit")
                    break
                }
                val command = when (config.mode) {
                    Mode.TAP -> "input tap ${config.x} ${config.y}"
                    Mode.SWIPE -> "input swipe ${config.x} ${config.y} " +
                        "${(config.x + config.dx).coerceAtLeast(0)} ${(config.y + config.dy).coerceAtLeast(0)} " +
                        "${config.swipeMs}"
                }
                val ok = ShizukuShellExecutor.execute(command, 5_000L).getOrNull()?.exitCode == 0
                failures = if (ok) 0 else failures + 1
                if (failures >= MAX_CONSECUTIVE_FAILURES) {
                    Log.w(TAG, "Auto tap stopped after $failures failed inputs")
                    break
                }
                delay(config.intervalMs)
            }
            _isRunning.value = false
            stopSelf()
        }
    }

    private fun showOverlayButton() {
        if (overlay != null) return
        val size = (56 * resources.displayMetrics.density).toInt()

        val button = TextView(this).apply {
            text = "■"
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(android.graphics.Color.parseColor("#001E2B"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor("#00E5FF"))
            }
            contentDescription = "Stop auto tap"
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 400
        }

        // Drag to move; a plain tap (no drag) stops the run.
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { windowManager?.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) stopSelf()
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(button, params)
            overlay = button
        } catch (e: Exception) {
            // Usually means the "Display over other apps" permission was revoked.
            Log.e(TAG, "Could not show the auto tap button", e)
            stopSelf()
        }
    }

    private fun removeOverlayButton() {
        overlay?.let { runCatching { windowManager?.removeView(it) } }
        overlay = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Auto tap", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, AutoTapService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Auto tap running")
            .setContentText("Stops after ${MAX_RUN_MS / 60_000} min, or use the ■ button or Stop here.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .build()
    }
}
