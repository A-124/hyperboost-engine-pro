// app/src/main/java/com/hyperboost/engine/services/MacroAutomationService.kt
package com.hyperboost.engine.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Accessibility service detecting foreground apps for per-game profile automation
 * and dispatching rootless gesture macros (tap, swipe, rapid auto-click).
 */
class MacroAutomationService : AccessibilityService() {

    companion object {
        private const val TAG = "MacroAutomationService"

        @Volatile
        private var instance: MacroAutomationService? = null

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        private val _currentForegroundPackage = MutableStateFlow<String?>(null)
        val currentForegroundPackage: StateFlow<String?> = _currentForegroundPackage.asStateFlow()

        private val _macroEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
        val macroEvents: SharedFlow<String> = _macroEvents.asSharedFlow()

        fun getInstance(): MacroAutomationService? = instance

        fun isRunning(): Boolean = _isServiceConnected.value
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceConnected.value = true
        Log.i(TAG, "MacroAutomationService connected and ready")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkgName = event.packageName?.toString()
            if (!pkgName.isNullOrBlank() && pkgName != packageName && pkgName != _currentForegroundPackage.value) {
                _currentForegroundPackage.value = pkgName
                Log.d(TAG, "Foreground window changed: $pkgName")
                serviceScope.launch {
                    _macroEvents.emit("Foreground: $pkgName")
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "MacroAutomationService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        _isServiceConnected.value = false
        Log.i(TAG, "MacroAutomationService destroyed")
    }

    /**
     * Executes a single tap gesture at (x, y) without root.
     */
    fun performTap(x: Float, y: Float, durationMs: Long = 50L, callback: ((Boolean) -> Unit)? = null) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Tap gesture completed at ($x, $y)")
                callback?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Tap gesture cancelled at ($x, $y)")
                callback?.invoke(false)
            }
        }, null)
    }

    /**
     * Executes a swipe gesture from (startX, startY) to (endX, endY).
     */
    fun performSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 250L,
        callback: ((Boolean) -> Unit)? = null
    ) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(10L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Swipe completed from ($startX, $startY) to ($endX, $endY)")
                callback?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Swipe cancelled")
                callback?.invoke(false)
            }
        }, null)
    }

    /**
     * Executes rapid repeated taps at specified coordinates for gaming macros.
     */
    fun triggerRapidTapMacro(x: Float, y: Float, count: Int = 5, intervalMs: Long = 80L) {
        serviceScope.launch {
            for (i in 1..count) {
                performTap(x, y, durationMs = 30L)
                delay(intervalMs)
            }
            _macroEvents.emit("Macro: Dispatched $count rapid taps at ($x, $y)")
        }
    }
}
