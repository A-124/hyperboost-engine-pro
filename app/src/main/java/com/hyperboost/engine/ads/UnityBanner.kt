@file:Suppress("DEPRECATION")

// app/src/main/java/com/hyperboost/engine/ads/UnityBanner.kt
package com.hyperboost.engine.ads

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hyperboost.engine.BuildConfig
import com.unity3d.services.banners.BannerErrorInfo
import com.unity3d.services.banners.BannerView
import com.unity3d.services.banners.UnityBannerSize

private const val BANNER_TAG = "UnityBanner"
private const val BANNER_HEIGHT_DP = 50
private const val RETRY_DELAY_MS = 30_000L
private const val MAX_RETRY_DELAY_MS = 5 * 60_000L
private const val REFRESH_MS = 60_000L

/**
 * Owns one Unity BannerView: loads it, retries on no-fill, refreshes while visible,
 * and reports whether an ad is actually on screen so the layout never shows an empty black strip.
 */
private class BannerController(
    private val activity: Activity,
    private val container: FrameLayout,
    private val onVisibilityChanged: (Boolean) -> Unit
) : BannerView.IListener {

    private val handler = Handler(Looper.getMainLooper())
    private var banner: BannerView? = null
    private var everLoaded = false
    private var destroyed = false
    private var retryDelayMs = RETRY_DELAY_MS
    private val reload = Runnable { load() }

    fun start() = load()

    fun destroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        try {
            banner?.destroy()
        } catch (t: Throwable) {
            Log.w(BANNER_TAG, "destroy threw", t)
        }
        banner = null
    }

    private fun load() {
        if (destroyed) return
        try {
            val view = banner ?: BannerView(
                activity,
                BuildConfig.UNITY_BANNER_ID,
                UnityBannerSize(320, BANNER_HEIGHT_DP)
            ).also { created ->
                created.setListener(this)
                container.removeAllViews()
                container.addView(
                    created,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    )
                )
                banner = created
            }
            view.load()
        } catch (t: Throwable) {
            Log.w(BANNER_TAG, "Banner setup threw", t)
            scheduleRetry()
        }
    }

    override fun onBannerLoaded(bannerAdView: BannerView?) {
        retryDelayMs = RETRY_DELAY_MS
        everLoaded = true
        onVisibilityChanged(true)
        scheduleRefresh()
    }

    override fun onBannerShown(bannerAdView: BannerView?) {}
    override fun onBannerClick(bannerAdView: BannerView?) {}
    override fun onBannerLeftApplication(bannerAdView: BannerView?) {}

    override fun onBannerFailedToLoad(bannerAdView: BannerView?, errorInfo: BannerErrorInfo?) {
        val detail = "${errorInfo?.errorCode ?: "UNKNOWN"}: ${errorInfo?.errorMessage ?: "No details"}"
        Log.w(BANNER_TAG, "Banner failed: $detail (gameId=${BuildConfig.UNITY_GAME_ID}, placement=${BuildConfig.UNITY_BANNER_ID})")
        // Keep the last good ad on screen during a refresh failure; only hide if we never had one.
        if (!everLoaded) onVisibilityChanged(false)
        scheduleRetry()
    }

    private fun scheduleRefresh() {
        if (destroyed) return
        handler.removeCallbacks(reload)
        handler.postDelayed(reload, REFRESH_MS)
    }

    private fun scheduleRetry() {
        if (destroyed) return
        handler.removeCallbacks(reload)
        handler.postDelayed(reload, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
    }
}

private class BannerHolder {
    var controller: BannerController? = null
}

/**
 * Bottom banner (320x50). Takes no space and draws nothing until Unity Ads is ready,
 * an ad has actually filled, and the user is not ad-free.
 */
@Composable
fun UnityBanner(modifier: Modifier = Modifier) {
    val ready by AdManager.isInitialized.collectAsState()
    val adFree by AdManager.isAdFree.collectAsState()
    var loaded by remember { mutableStateOf(false) }
    val holder = remember { BannerHolder() }

    if (!ready || adFree) return

    DisposableEffect(Unit) {
        onDispose {
            holder.controller?.destroy()
            holder.controller = null
        }
    }

    // Failed loads stay invisible. The controller continues retrying in the background.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (loaded) BANNER_HEIGHT_DP.dp else 0.dp)
            .clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(BANNER_HEIGHT_DP.dp),
            factory = { context ->
                FrameLayout(context).also { container ->
                    val activity = context.findActivity()
                    if (activity != null) {
                        holder.controller = BannerController(activity, container) { visible ->
                            loaded = visible
                        }.also { it.start() }
                    }
                }
            }
        )
    }
}
