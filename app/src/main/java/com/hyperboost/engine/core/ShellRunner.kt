// app/src/main/java/com/hyperboost/engine/core/ShellRunner.kt
package com.hyperboost.engine.core

/**
 * Indirection over the privileged shell.
 *
 * [PerformanceEngine] needs a shell that talks to Shizuku on a real device, and a scriptable one in
 * tests. Depending on this interface instead of the [ShizukuShellExecutor] singleton keeps the
 * snapshot/restore logic verifiable without a device, which is the part of the engine that can
 * actually leave a phone in a bad state.
 */
interface ShellRunner {
    suspend fun execute(
        command: String,
        timeoutMs: Long = ShizukuShellExecutor.DEFAULT_TIMEOUT_MS
    ): Result<ShizukuShellExecutor.ShellResult>
}

/** Production implementation: every command goes through Shizuku's privileged binder. */
object ShizukuShellRunner : ShellRunner {
    override suspend fun execute(
        command: String,
        timeoutMs: Long
    ): Result<ShizukuShellExecutor.ShellResult> = ShizukuShellExecutor.execute(command, timeoutMs)
}
