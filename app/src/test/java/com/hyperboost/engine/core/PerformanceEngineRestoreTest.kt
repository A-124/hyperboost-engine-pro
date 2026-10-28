package com.hyperboost.engine.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Restore is the part of the engine that can leave a phone worse than it found it: it writes back
 * values the user had before HyperBoost touched anything. These tests drive the real engine against
 * a scriptable shell, so the snapshot/restore contract is checked without a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PerformanceEngineRestoreTest {

    private lateinit var context: Context
    private lateinit var shell: FakeShell
    private lateinit var engine: PerformanceEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("hyperboost_snapshots", Context.MODE_PRIVATE)
            .edit().clear().commit()
        shell = FakeShell()
        engine = PerformanceEngine(context, shell)
    }

    @Test
    fun `animation scales return to the user's original values`() = runBlocking {
        shell.settings["global/window_animation_scale"] = "1.0"
        shell.settings["global/transition_animation_scale"] = "1.0"
        shell.settings["global/animator_duration_scale"] = "1.0"

        assertTrue(engine.setWindowAnimationScale(0f).isSuccess)
        assertEquals("0.0", shell.settings["global/window_animation_scale"])
        assertEquals("0.0", shell.settings["global/animator_duration_scale"])

        assertTrue(engine.restoreWindowAnimationScale().isSuccess)
        assertEquals("1.0", shell.settings["global/window_animation_scale"])
        assertEquals("1.0", shell.settings["global/transition_animation_scale"])
        assertEquals("1.0", shell.settings["global/animator_duration_scale"])
    }

    @Test
    fun `refresh rate round trip restores the original rate`() = runBlocking {
        shell.settings["system/peak_refresh_rate"] = "60.0"
        shell.settings["system/min_refresh_rate"] = "60.0"

        assertTrue(engine.lockRefreshRate(120f).isSuccess)
        assertEquals("120", shell.settings["system/peak_refresh_rate"])

        assertTrue(engine.resetRefreshRate().isSuccess)
        assertEquals("60.0", shell.settings["system/peak_refresh_rate"])
        assertEquals("60.0", shell.settings["system/min_refresh_rate"])
    }

    @Test
    fun `restore without a snapshot never deletes the setting`() = runBlocking {
        // Nothing was applied by HyperBoost, so there is no original value to go back to. Deleting
        // here would silently change a setting the user chose themselves.
        shell.settings["global/window_animation_scale"] = "0.5"

        assertTrue(engine.restoreWindowAnimationScale().isSuccess)
        assertEquals("0.5", shell.settings["global/window_animation_scale"])
        assertFalse(shell.commands.any { it.startsWith("settings delete") })
    }

    @Test
    fun `an originally unset setting is deleted on restore`() = runBlocking {
        // The device reports "null", which means "no value set". Restore has to remove it again
        // instead of writing the literal string back.
        assertTrue(engine.setWindowAnimationScale(0f).isSuccess)
        assertEquals("0.0", shell.settings["global/window_animation_scale"])

        assertTrue(engine.restoreWindowAnimationScale().isSuccess)
        assertFalse(shell.settings.containsKey("global/window_animation_scale"))
        assertTrue(shell.commands.any { it == "settings delete global window_animation_scale" })
    }

    @Test
    fun `a write the ROM silently ignores is reported as a failure`() = runBlocking {
        shell.settings["system/touch_prediction_status"] = "0"
        // The command exits 0 but the value never changes; this is the common OEM behaviour that a
        // naive implementation would report as success.
        shell.ignoredKeys += "system/touch_prediction_status"

        val result = engine.setTouchLatencyBoost(true)

        assertTrue(result.isFailure)
        assertEquals("0", shell.settings["system/touch_prediction_status"])
        assertTrue(
            result.exceptionOrNull()?.message.orEmpty().contains("did not accept touch prediction")
        )
    }

    @Test
    fun `a rejected read is reported and never deletes on restore`() = runBlocking {
        shell.settings["global/window_animation_scale"] = "1.0"
        shell.failReads = true

        // The write goes through, but it cannot be verified while reads fail, so the engine must
        // report a failure instead of claiming the tweak is active.
        assertTrue(engine.setWindowAnimationScale(0f).isFailure)

        // No snapshot was captured, so restore has nothing to write back. It must not delete the
        // setting either: that would silently change a value the user chose.
        shell.failReads = false
        assertTrue(engine.restoreWindowAnimationScale().isSuccess)
        assertFalse(shell.commands.any { it.startsWith("settings delete") })
    }

    /** A shell that records commands and applies `settings` / `setprop` the way Android would. */
    private class FakeShell : ShellRunner {
        val settings = mutableMapOf<String, String>()
        val props = mutableMapOf<String, String>()
        val commands = mutableListOf<String>()
        val ignoredKeys = mutableSetOf<String>()
        var failReads = false

        override suspend fun execute(
            command: String,
            timeoutMs: Long
        ): Result<ShizukuShellExecutor.ShellResult> = runCatching {
            commands += command
            val parts = command.split(" ")
            when {
                parts[0] == "settings" && parts.size >= 4 && parts[1] == "get" -> {
                    if (failReads) {
                        ShizukuShellExecutor.ShellResult(1, "", "read failed")
                    } else {
                        ShizukuShellExecutor.ShellResult(0, settings["${parts[2]}/${parts[3]}"] ?: "null", "")
                    }
                }
                parts[0] == "settings" && parts.size >= 5 && parts[1] == "put" -> {
                    val key = "${parts[2]}/${parts[3]}"
                    if (key !in ignoredKeys) {
                        settings[key] = unquote(parts.drop(4).joinToString(" "))
                    }
                    ShizukuShellExecutor.ShellResult(0, "", "")
                }
                parts[0] == "settings" && parts.size >= 4 && parts[1] == "delete" -> {
                    settings.remove("${parts[2]}/${parts[3]}")
                    ShizukuShellExecutor.ShellResult(0, "", "")
                }
                parts[0] == "getprop" && parts.size >= 2 -> {
                    if (failReads) {
                        ShizukuShellExecutor.ShellResult(1, "", "read failed")
                    } else {
                        ShizukuShellExecutor.ShellResult(0, props[parts[1]] ?: "", "")
                    }
                }
                parts[0] == "setprop" && parts.size >= 3 -> {
                    props[parts[1]] = unquote(parts.drop(2).joinToString(" "))
                    ShizukuShellExecutor.ShellResult(0, "", "")
                }
                else -> ShizukuShellExecutor.ShellResult(0, "", "")
            }
        }

        private fun unquote(value: String): String =
            value.removePrefix("'").removeSuffix("'")
    }
}
