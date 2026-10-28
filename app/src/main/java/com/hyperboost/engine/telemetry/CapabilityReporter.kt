// app/src/main/java/com/hyperboost/engine/telemetry/CapabilityReporter.kt
package com.hyperboost.engine.telemetry

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Opt-in, anonymous record of which privileged operations a device accepts.
 *
 * The tuning engine already knows the answer for every command it runs: [ShizukuShellExecutor]
 * returns an exit code. Today that knowledge dies with the session, which is why every unsupported
 * ROM has to be diagnosed from user reports. This aggregates those outcomes per command signature
 * and Android API level so unsupported modules can be found from data instead of guesswork.
 *
 * Privacy rules, enforced in code:
 *  - Off until the user turns it on. Nothing is buffered while it is off.
 *  - Only [CommandSignature] output is uploaded: a verb path, never arguments, values, package
 *    names or hostnames.
 *  - No identifier is stored with the report. The Firestore document id is derived from the
 *    signature and the API level, so reports from every device merge into the same counters.
 *  - Every failure path is swallowed: reporting must never break or slow down a tweak.
 */
object CapabilityReporter {

    private const val TAG = "CapabilityReporter"
    private const val PREFS = "hyperboost_privacy"
    private const val KEY_ENABLED = "share_capability"
    private const val FLUSH_THRESHOLD = 20

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentLinkedQueue<Sample>()
    private val flushing = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    private data class Sample(val signature: String, val ok: Boolean)

    /** Called once from [com.hyperboost.engine.HyperBoostApp]; without it the reporter is a no-op. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isEnabled(context: Context? = appContext): Boolean =
        context != null &&
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) pending.clear()
    }

    /**
     * Records one command outcome. Cheap and non-blocking: it only normalizes the command and
     * appends to an in-memory queue. Safe to call from the shell executor on every command.
     */
    fun record(command: String, exitCode: Int) {
        val context = appContext ?: return
        if (!isEnabled(context)) return
        runCatching {
            pending += Sample(CommandSignature.of(command), exitCode == 0)
            if (pending.size >= FLUSH_THRESHOLD) flush()
        }
    }

    /** Uploads whatever is queued. Called when the queue fills and when the UI goes to background. */
    fun flush() {
        val context = appContext ?: return
        if (pending.isEmpty()) return
        if (!flushing.compareAndSet(false, true)) return

        val batch = mutableListOf<Sample>()
        while (true) {
            val sample = pending.poll() ?: break
            batch += sample
        }
        if (batch.isEmpty()) {
            flushing.set(false)
            return
        }

        scope.launch {
            try {
                if (!isEnabled(context)) return@launch
                if (FirebaseApp.getApps(context).isEmpty()) {
                    Log.d(TAG, "Firebase is not configured; capability report dropped.")
                    return@launch
                }
                val auth = FirebaseAuth.getInstance()
                val user = auth.currentUser ?: auth.signInAnonymously().awaitResult().user
                if (user == null) {
                    Log.d(TAG, "Anonymous sign-in failed; capability report dropped.")
                    return@launch
                }

                val version = runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull() ?: "unknown"
                val collection = FirebaseFirestore.getInstance().collection("capability")

                // One document per (signature, API level) so counters aggregate across devices.
                batch.groupBy { it.signature }.forEach { (signature, samples) ->
                    val ok = samples.count { it.ok }
                    val fail = samples.size - ok
                    val doc = mapOf(
                        "signature" to signature,
                        "apiLevel" to Build.VERSION.SDK_INT,
                        "accepted" to FieldValue.increment(ok.toLong()),
                        "rejected" to FieldValue.increment(fail.toLong()),
                        "appVersion" to version,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    val id = signature.replace(Regex("[^A-Za-z0-9]+"), "_").take(120) +
                        "_" + Build.VERSION.SDK_INT
                    collection.document(id).set(doc, SetOptions.merge()).awaitResult()
                }
                Log.d(TAG, "Reported ${batch.size} capability samples.")
            } catch (e: Exception) {
                // Never surface this to the user; a failed report is not a failed tweak.
                Log.d(TAG, "Capability report skipped: ${e.javaClass.simpleName}")
            } finally {
                flushing.set(false)
            }
        }
    }

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitResult(): T =
        suspendCancellableCoroutine { continuation ->
            addOnCompleteListener { task ->
                if (!continuation.isActive) return@addOnCompleteListener
                if (task.isSuccessful) continuation.resume(task.result)
                else continuation.resumeWith(
                    Result.failure(task.exception ?: IllegalStateException("Firebase request failed"))
                )
            }
        }
}
