package com.hyperboost.engine.core

/**
 * Device state read back from Android, plus whether the thermal override could be verified.
 * `thermalVerified` is null when the ROM does not report override state.
 */
data class EngineState(
    val zeroAnimations: Boolean = false,
    val touchBoost: Boolean = false,
    val wifiGaming: Boolean = false,
    val vulkan: Boolean = false,
    val surfaceFlingerLatch: Boolean = false,
    val downscaled: Boolean = false,
    val sustainedPerformance: Boolean = false,
    val gameDnd: Boolean = false,
    val phantomProtection: Boolean = false,
    val batterySaverOff: Boolean = false,
    val blurDisabled: Boolean = false,
    val thermalOverride: Boolean = false,
    val thermalVerified: Boolean? = null,
    val refreshRateHz: Int? = null,
    val privateDns: String? = null
)

/** Pure parser for the `settings` / `getprop` read-back script. Kept JVM-testable. */
object EngineStateParser {

    fun parse(
        stdout: String,
        downscaled: Boolean,
        applied: (String) -> Boolean = { false }
    ): EngineState {
        val values = stdout.lineSequence()
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i < 0) null else line.substring(0, i) to line.substring(i + 1).trim()
            }
            .toMap()

        return EngineState(
            zeroAnimations = values["anim"]?.toFloatOrNull() == 0f,
            // touch_prediction_status is the setting the engine actually writes. pointer_speed
            // is a mouse setting and must never be used to infer touchscreen boost state.
            touchBoost = values["touchpred"] == "1",
            wifiGaming = values["wifi"] == "0",
            vulkan = values["hwui"]?.startsWith("skiavk") == true,
            surfaceFlingerLatch = values["sf"] == "1",
            downscaled = downscaled,
            sustainedPerformance = values["sustained"]?.lowercase()?.let { value ->
                when {
                    value.isBlank() -> applied("sustained")
                    value.contains("true") -> true
                    value.contains("false") || value.contains("disabled") -> false
                    value.contains("enabled") -> true
                    else -> applied("sustained")
                }
            } ?: applied("sustained"),
            gameDnd = (values["zen"]?.toIntOrNull() ?: 0) != 0,
            phantomProtection = values["phantom"] == "false",
            batterySaverOff = values["adaptive"] == "0",
            blurDisabled = values["blur"] == "1",
            thermalOverride = applied("thermal"),
            refreshRateHz = values["minhz"]?.toFloatOrNull()?.toInt()?.takeIf { it > 0 },
            privateDns = if (values["dnsmode"] == "hostname") {
                values["dns"]?.takeIf { it.isNotBlank() && it != "null" }
            } else {
                null
            }
        )
    }
}
