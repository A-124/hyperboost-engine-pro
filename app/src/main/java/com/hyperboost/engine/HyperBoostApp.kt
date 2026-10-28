// app/src/main/java/com/hyperboost/engine/HyperBoostApp.kt
package com.hyperboost.engine

import android.app.Application
import com.hyperboost.engine.telemetry.CapabilityReporter

/**
 * Process entry point. Its only job is to give the opt-in capability reporter an application
 * context, because the shell executor that feeds it has no context of its own.
 */
class HyperBoostApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CapabilityReporter.init(this)
    }
}
