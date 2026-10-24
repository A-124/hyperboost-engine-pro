// app/src/main/java/com/hyperboost/engine/telemetry/GyroProbe.kt
package com.hyperboost.engine.telemetry

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Measures the gyroscope's real sample rate. Aim lag in games is often a sensor that is being
 * throttled (Battery Saver, OEM sensor hub), so this shows the number instead of guessing.
 */
object GyroProbe {

    data class Reading(val hz: Double?, val note: String)

    suspend fun measure(context: Context, windowMs: Long = 2_000L): Reading {
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            ?: return Reading(null, "This phone has no gyroscope.")

        val count = AtomicInteger(0)
        val firstNs = AtomicLong(0L)
        val lastNs = AtomicLong(0L)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (count.getAndIncrement() == 0) firstNs.set(event.timestamp)
                lastNs.set(event.timestamp)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        // FASTEST requests a 0-microsecond period on Android and may require restricted
        // HIGH_SAMPLING_RATE_SENSORS permission. GAME avoids that unsupported request.
        val registered = sensors.registerListener(
            listener, gyro, SensorManager.SENSOR_DELAY_GAME, Handler(Looper.getMainLooper())
        )
        if (!registered) {
            return Reading(null, "Your phone's gyroscope is available, but Android could not start sensor readings. Try again after closing apps that use motion sensors.")
        }
        try {
            delay(windowMs)
        } finally {
            sensors.unregisterListener(listener)
        }

        val samples = count.get()
        val spanSeconds = (lastNs.get() - firstNs.get()) / 1e9
        if (samples < 2 || spanSeconds <= 0.0) {
            return Reading(null, "Your phone did not provide gyroscope readings this time. This may be a device or system limitation. Try again, and turn off Battery Saver if it is enabled.")
        }

        val hz = (samples - 1) / spanSeconds
        val note = when {
            hz >= 90.0 -> "Good sensor response for most games."
            hz >= 45.0 -> "Usable for many games; your phone or system may limit the sampling rate."
            else -> "The sensor is reporting slowly. Your phone may limit its sampling rate; HyperBoost cannot raise a hardware limit."
        }
        return Reading(hz, note)
    }
}
