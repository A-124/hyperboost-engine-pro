package com.hyperboost.engine.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeedbackRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `invalid category is rejected before Firebase`() = runBlocking {
        val result = FeedbackRepository.submit(context, "Spam", "hello")
        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message.orEmpty().contains("Invalid feedback category")
        )
    }

    @Test
    fun `blank and oversized messages are rejected`() = runBlocking {
        assertTrue(FeedbackRepository.submit(context, "Idea", " ").isFailure)
        assertTrue(FeedbackRepository.submit(context, "Idea", "x".repeat(2_001)).isFailure)
    }

    @Test
    fun `missing Firebase is reported as not configured`() = runBlocking {
        val result = FeedbackRepository.submit(context, "Idea", "A valid message")
        assertTrue(result.isFailure)
        // Typed, so the dialog can offer the GitHub fallback instead of blaming the connection.
        assertTrue(result.exceptionOrNull() is FeedbackRepository.NotConfiguredException)
        assertFalse(FeedbackRepository.isConfigured(context))
    }

    @Test
    fun `github fallback carries the report and the environment`() {
        val url = FeedbackRepository.issueUrl(context, "Bug", "It crashes on launch")
        assertTrue(url.startsWith("https://github.com/A-124/hyperboost-engine-pro/issues/new?"))
        assertTrue(url.contains("labels=bug"))
        assertTrue(url.contains("It%20crashes%20on%20launch"))
        assertTrue(url.contains("API%2034"))
        assertTrue(url.contains("HyperBoost%20Engine%20Pro"))
    }
}
