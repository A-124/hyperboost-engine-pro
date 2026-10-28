package com.hyperboost.engine.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Sends user-initiated feedback without collecting account or contact information.
 *
 * Two paths exist on purpose:
 *  - Firestore, when the build has a Firebase configuration. Submissions are anonymous and
 *    write-only.
 *  - A pre-filled GitHub issue, when it does not. The app is distributed from GitHub, so this needs
 *    no backend, no secret and no release, and it cannot be broken by a missing configuration file.
 *    Release builds shipped without `google-services.json` for several versions, which made the
 *    Firestore path fail before it ever reached the network.
 */
object FeedbackRepository {

    /** Raised when the build has no Firebase configuration; the caller should offer the fallback. */
    class NotConfiguredException :
        IllegalStateException("This build has no Firebase configuration, so in-app feedback is unavailable.")

    private const val ISSUE_BASE = "https://github.com/A-124/hyperboost-engine-pro/issues/new"
    private val CATEGORIES = setOf("Idea", "Bug", "Other")

    /** False when `google-services.json` was absent at build time. */
    fun isConfigured(context: Context): Boolean = FirebaseApp.getApps(context).isNotEmpty()

    /** A pre-filled GitHub issue URL. Works with or without Firebase. */
    fun issueUrl(context: Context, category: String, message: String): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        val body = buildString {
            appendLine("### What happened")
            appendLine()
            appendLine(message.trim())
            appendLine()
            appendLine("### Environment")
            appendLine("- App: HyperBoost Engine Pro $version")
            appendLine("- Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("- Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            appendLine("<!-- Sent from the in-app feedback form. Remove anything you don't want public. -->")
        }
        return Uri.parse(ISSUE_BASE).buildUpon()
            .appendQueryParameter("title", "$category: ")
            .appendQueryParameter("body", body)
            .appendQueryParameter("labels", if (category == "Bug") "bug" else "feedback")
            .build()
            .toString()
    }

    suspend fun submit(context: Context, category: String, message: String): Result<Unit> = runCatching {
        require(category in CATEGORIES) { "Invalid feedback category" }
        require(message.isNotBlank() && message.length <= 2_000) { "Feedback must be 1–2000 characters" }
        if (!isConfigured(context)) throw NotConfiguredException()

        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser ?: auth.signInAnonymously().awaitResult().user
        checkNotNull(user) { "Anonymous sign-in returned no user. Enable Anonymous sign-in in Firebase Auth." }

        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        val feedback = mapOf(
            "category" to category,
            "message" to message,
            "appVersion" to version,
            "androidVersion" to Build.VERSION.RELEASE,
            "deviceModel" to Build.MODEL.take(100),
            "createdAt" to FieldValue.serverTimestamp(),
            "status" to "new"
        )
        FirebaseFirestore.getInstance().collection("feedback").add(feedback).awaitResult()
        Unit
    }

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitResult(): T =
        suspendCancellableCoroutine { continuation ->
            addOnCompleteListener { task ->
                if (!continuation.isActive) return@addOnCompleteListener
                if (task.isSuccessful) continuation.resume(task.result)
                else continuation.resumeWith(Result.failure(task.exception ?: IllegalStateException("Firebase request failed")))
            }
        }
}
