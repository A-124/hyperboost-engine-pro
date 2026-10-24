// app/src/main/java/com/hyperboost/engine/core/ShizukuShellExecutor.kt
package com.hyperboost.engine.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeoutException

/**
 * Executes elevated shell commands using Shizuku's privileged binder process.
 *
 * stdout and stderr are drained concurrently (reading them one after another can deadlock when
 * the unread pipe fills up) and every command has a timeout so a hung `cmd` call can never
 * freeze the UI's busy state forever.
 */
object ShizukuShellExecutor {

    private const val TAG = "ShizukuShellExecutor"
    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val LONG_TIMEOUT_MS = 300_000L

    data class ShellResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val isSuccess: Boolean = exitCode == 0
    )

    /** Quote a value so it is always a single, inert shell word. */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    suspend fun execute(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Result<ShellResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (!Shizuku.pingBinder()) {
                throw IllegalStateException("Shizuku service is not running or binder is unavailable.")
            }
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Shizuku permission has not been granted.")
            }

            Log.d(TAG, "Executing privileged shell command: $command")
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply {
                isAccessible = true
            }
            val process = newProcessMethod.invoke(null, arrayOf("sh", "-c", command), null, null) as Process

            try {
                coroutineScope {
                    val stdout = async { process.inputStream.bufferedReader().use { it.readText() } }
                    val stderr = async { process.errorStream.bufferedReader().use { it.readText() } }
                    // Shizuku's remote process does not implement waitFor(timeout) reliably (exitValue()
                    // can still throw "process hasn't exited"), so use the blocking waitFor() and
                    // enforce the timeout ourselves.
                    val exit = async { process.waitFor() }

                    val exitCode = withTimeoutOrNull(timeoutMs) { exit.await() }
                    if (exitCode == null) {
                        process.destroy()
                        throw TimeoutException("Command timed out after ${timeoutMs / 1000}s")
                    }

                    val result = ShellResult(
                        exitCode = exitCode,
                        stdout = stdout.await().trim(),
                        stderr = stderr.await().trim()
                    )
                    Log.d(
                        TAG,
                        "Command finished (${result.exitCode}): stdout=${result.stdout.take(80)}, stderr=${result.stderr.take(80)}"
                    )
                    result
                }
            } finally {
                runCatching { process.destroy() }
            }
        }
    }

    suspend fun executeRootlessOrFallback(command: String): Result<ShellResult> = withContext(Dispatchers.IO) {
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            execute(command)
        } else {
            runCatching {
                Log.d(TAG, "Executing standard unprivileged shell fallback: $command")
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                coroutineScope {
                    val stdout = async { process.inputStream.bufferedReader().use { it.readText().trim() } }
                    val stderr = async { process.errorStream.bufferedReader().use { it.readText().trim() } }
                    val exitCode = process.waitFor()
                    ShellResult(exitCode, stdout.await(), stderr.await())
                }
            }
        }
    }
}
