// app/src/main/java/com/hyperboost/engine/services/MacroAutomationService.kt
package com.hyperboost.engine.services

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Optional accessibility service that reports the foreground package faster than the shell
 * poller. It does not dispatch gestures or automate input; Auto Tap uses Shizuku instead.
 */
class MacroAutomationService : AccessibilityService() {

    companion object {
        private const val TAG = "GameDetectionService"

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        private val _currentForegroundPackage = MutableStateFlow<String?>(null)
        val currentForegroundPackage: StateFlow<String?> = _currentForegroundPackage.asStateFlow()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isServiceConnected.value = true
        Log.i(TAG, "Foreground game detection connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val foreground = event.packageName?.toString()
        if (!foreground.isNullOrBlank() && foreground != packageName && foreground != _currentForegroundPackage.value) {
            _currentForegroundPackage.value = foreground
            Log.d(TAG, "Foreground window changed: $foreground")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Foreground game detection interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        _isServiceConnected.value = false
        Log.i(TAG, "Foreground game detection destroyed")
    }
}
