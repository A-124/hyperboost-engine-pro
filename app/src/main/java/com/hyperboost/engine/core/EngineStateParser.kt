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
    val sustainedPerformance: Boolean = false,
    val gameDnd: Boolean = false,
    val phantomProtection: Boolean = false,
    val batterySaverOff: Boolean = false,
    val blurDisabled: Boolean = false,
    val thermalOverride: Boolean = false,
    val thermalVerified: Boolean? = null,
    val cachedAppsFreezer: Boolean = false,
    val adaptiveBrightnessOff: Boolean = false,
    val showRefreshRate: Boolean = false,
    val refreshRateHz: Int? = null,
    val privateDns: String? = null
)

/** Pure parser for the `settings` / `getprop` read-back script. Kept JVM-testable. */
object EngineStateParser {

    fun parse(
        stdout: String,
        applied: (String) -> Boolean = { false }
    ): EngineState {
        val values = stdout.lineSequence()
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i < 0) null else line.substring(0, i) to line.substring(i + 1).trim()
            }
            .toMap()

        return EngineState(
            // A raw setting that is already in the "enabled" position is not enough: the
            // feature must have been applied by HyperBoost. Otherwise turning it off would
            // restore the user's pre-existing value and make the toggle flip back on.
            zeroAnimations = applied("anim") && values["anim"]?.toFloatOrNull() == 0f,
            touchBoost = applied("touch") && values["touchpred"] == "1",
            wifiGaming = applied("wifi") && values["wifi"] == "0",
            vulkan = applied("vulkan") && values["hwui"]?.startsWith("skiavk") == true,
            surfaceFlingerLatch = applied("sf") && values["sf"] == "1",
            sustainedPerformance = applied("sustained") && !(
                values["sustained"]?.lowercase()?.let {
                    it.contains("false") || it.contains("disabled")
                } ?: false
                ),
            gameDnd = applied("dnd") && (values["zen"]?.toIntOrNull() ?: 0) != 0,
            phantomProtection = applied("phantom") && values["phantom"] == "false",
            batterySaverOff = applied("battery") && values["adaptive"] == "0",
            blurDisabled = applied("blur") && values["blur"] == "1",
            thermalOverride = applied("thermal"),
            cachedAppsFreezer = applied("freezer") &&
                (values["freezer"] == "enabled" || values["freezer"] == "1"),
            adaptiveBrightnessOff = applied("brightness") && values["brightmode"] == "0",
            showRefreshRate = applied("showhz") && values["showhz"] == "1",
            refreshRateHz = values["minhz"]?.toFloatOrNull()?.toInt()?.takeIf { it > 0 },
            privateDns = if (values["dnsmode"] == "hostname") {
                values["dns"]?.takeIf { it.isNotBlank() && it != "null" }
            } else {
                null
            }
        )
    }
}
