// app/src/main/java/com/hyperboost/engine/core/ShizukuManager.kt
package com.hyperboost.engine.core

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * Handles Shizuku binder connection, permission checking, listener callbacks,
 * and elevated rootless authorization state.
 */
class ShizukuManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ShizukuManager"
        const val SHIZUKU_PERMISSION_REQUEST_CODE = 4001

        @Volatile
        private var instance: ShizukuManager? = null

        fun getInstance(context: Context): ShizukuManager {
            return instance ?: synchronized(this) {
                instance ?: ShizukuManager(context.applicationContext).also { instance = it }
            }
        }
    }

    enum class ShizukuStatus {
        NOT_RUNNING,
        UNAUTHORIZED,
        AUTHORIZED
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _statusFlow = MutableStateFlow(ShizukuStatus.NOT_RUNNING)
    val statusFlow: StateFlow<ShizukuStatus> = _statusFlow.asStateFlow()

    private val _shizukuVersion = MutableStateFlow(0)
    val shizukuVersion: StateFlow<Int> = _shizukuVersion.asStateFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        evaluateState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died")
        _statusFlow.value = ShizukuStatus.NOT_RUNNING
        _shizukuVersion.value = 0
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                Log.d(TAG, "Shizuku permission request result: granted=$granted")
                evaluateState()
            }
        }

    init {
        registerListeners()
        evaluateState()
    }

    private fun registerListeners() {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register Shizuku listeners", e)
        }
    }

    fun unregisterListeners() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister Shizuku listeners", e)
        }
    }

    fun evaluateState() {
        scope.launch {
            try {
                if (!Shizuku.pingBinder()) {
                    _statusFlow.value = ShizukuStatus.NOT_RUNNING
                    _shizukuVersion.value = 0
                    return@launch
                }

                _shizukuVersion.value = Shizuku.getVersion()

                if (Shizuku.isPreV11()) {
                    _statusFlow.value = ShizukuStatus.UNAUTHORIZED
                    return@launch
                }

                val hasPermission = checkPermission()
                if (hasPermission) {
                    _statusFlow.value = ShizukuStatus.AUTHORIZED
                } else {
                    _statusFlow.value = ShizukuStatus.UNAUTHORIZED
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error evaluating Shizuku state", e)
                _statusFlow.value = ShizukuStatus.NOT_RUNNING
            }
        }
    }

    fun checkPermission(): Boolean {
        return try {
            if (!Shizuku.pingBinder()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Shizuku permission", e)
            false
        }
    }

    fun requestPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "Cannot request permission: Shizuku binder is not available")
                return
            }

            if (Shizuku.isPreV11()) {
                Log.w(TAG, "Shizuku pre-v11 permission request not supported via modern binder")
                return
            }

            if (checkPermission()) {
                _statusFlow.value = ShizukuStatus.AUTHORIZED
                return
            }

            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to invoke Shizuku.requestPermission", e)
        }
    }

    fun isAuthorized(): Boolean = _statusFlow.value == ShizukuStatus.AUTHORIZED
}
