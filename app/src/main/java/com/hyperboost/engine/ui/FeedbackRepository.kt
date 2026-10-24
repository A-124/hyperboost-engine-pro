package com.hyperboost.engine.ui

import android.content.Context
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Sends user-initiated feedback without collecting account or contact information. */
object FeedbackRepository {
    suspend fun submit(context: Context, category: String, message: String): Result<Unit> = runCatching {
        require(category in setOf("Idea", "Bug", "Other")) { "Invalid feedback category" }
        require(message.isNotBlank() && message.length <= 2_000) { "Feedback must be 1–2000 characters" }
        check(FirebaseApp.getApps(context).isNotEmpty()) {
            "Firebase is not configured. Add app/google-services.json."
        }

        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser ?: auth.signInAnonymously().awaitResult().user
        checkNotNull(user) { "Anonymous sign-in did not return a user" }

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
