@file:Suppress("DEPRECATION")

// app/src/main/java/com/hyperboost/engine/ads/AdManager.kt
package com.hyperboost.engine.ads

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hyperboost.engine.BuildConfig
import com.unity3d.ads.IUnityAdsInitializationListener
import com.unity3d.ads.IUnityAdsLoadListener
import com.unity3d.ads.IUnityAdsShowListener
import com.unity3d.ads.UnityAds
import com.unity3d.ads.UnityAdsShowOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Unity Ads wrapper: banner (see UnityBanner), interstitials after feature use,
 * and a rewarded video that unlocks 1 hour without ads.
 * Every call is wrapped so an ad problem can NEVER crash the app.
 *
 * IDs / test mode come from BuildConfig (set via env vars in app/build.gradle.kts).
 */
object AdManager {

    private const val TAG = "AdManager"
    private const val PREFS = "hyperboost_ads"
    private const val KEY_AD_FREE_UNTIL = "ad_free_until"

    /** Show an interstitial after every Nth "feature used" action (boost toggles, cleanups). */
    private const val INTERSTITIAL_EVERY = 2
    /** Never show two interstitials closer together than this. */
    private const val INTERSTITIAL_COOLDOWN_MS = 90_000L
    /** Wait before retrying a placement or init that failed. */
    private const val RETRY_DELAY_MS = 30_000L
    private const val AD_FREE_MS = 60 * 60_000L

    private val main = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var started = false
    private var actionCount = 0
    private var lastInterstitialAt = 0L

    @Volatile private var interstitialReady = false
    @Volatile private var rewardedReady = false
    @Volatile private var lastLoadError: String = ""

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _isAdFree = MutableStateFlow(false)
    val isAdFree: StateFlow<Boolean> = _isAdFree.asStateFlow()

    fun init(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        appContext = app
        refreshAdFree()
        if (BuildConfig.UNITY_GAME_ID.isBlank()) return
        try {
            UnityAds.initialize(
                app,
                BuildConfig.UNITY_GAME_ID,
                BuildConfig.UNITY_TEST_MODE,
                object : IUnityAdsInitializationListener {
                    override fun onInitializationComplete() {
                        Log.i(TAG, "Unity Ads initialized (testMode=${BuildConfig.UNITY_TEST_MODE})")
                        _isInitialized.value = true
                        preloadAll()
                    }

                    override fun onInitializationFailed(
                        error: UnityAds.UnityAdsInitializationError?,
                        message: String?
                    ) {
                        Log.w(TAG, "Unity Ads init failed: $error $message")
                        started = false
                        // Try again later instead of leaving ads dead until the next app launch.
                        main.postDelayed({ appContext?.let { init(it) } }, RETRY_DELAY_MS)
                    }
                }
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Unity Ads init threw", t)
            started = false
        }
    }

    private fun preloadAll() {
        loadWithRetry(BuildConfig.UNITY_INTERSTITIAL_ID) { interstitialReady = it }
        loadWithRetry(BuildConfig.UNITY_REWARDED_ID) { rewardedReady = it }
    }

    /** Loads one placement and keeps retrying on failure, so a no-fill never permanently disables it. */
    private fun loadWithRetry(placement: String, set: (Boolean) -> Unit) {
        load(placement) { ok ->
            set(ok)
            if (!ok) {
                main.postDelayed({ loadWithRetry(placement, set) }, RETRY_DELAY_MS)
            }
        }
    }

    private fun load(placement: String, onDone: (Boolean) -> Unit) {
        try {
            UnityAds.load(placement, object : IUnityAdsLoadListener {
                override fun onUnityAdsAdLoaded(placementId: String?) {
                    onDone(true)
                }

                override fun onUnityAdsFailedToLoad(
                    placementId: String?,
                    error: UnityAds.UnityAdsLoadError?,
                    message: String?
                ) {
                    Log.w(TAG, "Load failed for $placementId: $error $message")
                    lastLoadError = "${error?.name ?: "UNKNOWN"}: ${message ?: "no details"} (placement: $placementId)"
                    onDone(false)
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "load threw", t)
            onDone(false)
        }
    }

    private fun show(
        activity: Activity,
        placement: String,
        onComplete: (UnityAds.UnityAdsShowCompletionState?) -> Unit,
        onFail: () -> Unit
    ) {
        try {
            UnityAds.show(activity, placement, UnityAdsShowOptions(), object : IUnityAdsShowListener {
                override fun onUnityAdsShowFailure(
                    placementId: String?,
                    error: UnityAds.UnityAdsShowError?,
                    message: String?
                ) {
                    Log.w(TAG, "Show failed for $placementId: $error $message")
                    onFail()
                }

                override fun onUnityAdsShowStart(placementId: String?) {}
                override fun onUnityAdsShowClick(placementId: String?) {}
                override fun onUnityAdsShowComplete(
                    placementId: String?,
                    state: UnityAds.UnityAdsShowCompletionState?
                ) {
                    onComplete(state)
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "show threw", t)
            onFail()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Interstitial: after every 2nd feature action, at most once per 90 seconds, never while ad-free
    // ---------------------------------------------------------------------------------------

    /** Call after the user completes something useful: a boost toggle, a cleanup, etc. */
    fun onFeatureUsed(activity: Activity?) {
        actionCount++
        if (activity == null || !_isInitialized.value) return
        refreshAdFree()
        if (_isAdFree.value || !interstitialReady) return
        if (actionCount % INTERSTITIAL_EVERY != 0) return
        val now = System.currentTimeMillis()
        if (now - lastInterstitialAt < INTERSTITIAL_COOLDOWN_MS) return

        lastInterstitialAt = now
        interstitialReady = false
        show(
            activity,
            BuildConfig.UNITY_INTERSTITIAL_ID,
            onComplete = { loadWithRetry(BuildConfig.UNITY_INTERSTITIAL_ID) { interstitialReady = it } },
            onFail = { loadWithRetry(BuildConfig.UNITY_INTERSTITIAL_ID) { interstitialReady = it } }
        )
    }

    /** Kept so existing call sites keep working. */
    fun onBoostAction(activity: Activity?) = onFeatureUsed(activity)

    // ---------------------------------------------------------------------------------------
    // Rewarded: watch a short video -> 1 hour without ads
    // ---------------------------------------------------------------------------------------

    fun showRewarded(activity: Activity?, onMessage: (String) -> Unit) {
        if (activity == null) return
        if (!_isInitialized.value) {
            onMessage("Ads are still starting up — try again in a few seconds.")
            return
        }
        if (!rewardedReady) {
            // Not preloaded yet: load now and play as soon as it is ready.
            onMessage("Loading video…")
            load(BuildConfig.UNITY_REWARDED_ID) { ok ->
                rewardedReady = ok
                if (ok) playRewarded(activity, onMessage)
                else onMessage("No video available right now. Reason: $lastLoadError")
            }
            return
        }
        playRewarded(activity, onMessage)
    }

    private fun playRewarded(activity: Activity, onMessage: (String) -> Unit) {
        rewardedReady = false
        show(
            activity,
            BuildConfig.UNITY_REWARDED_ID,
            onComplete = { state ->
                if (state == UnityAds.UnityAdsShowCompletionState.COMPLETED) {
                    grantAdFree()
                    onMessage("Thank you! Ads are off for 1 hour.")
                } else {
                    onMessage("Video skipped — no reward this time.")
                }
                loadWithRetry(BuildConfig.UNITY_REWARDED_ID) { rewardedReady = it }
            },
            onFail = {
                onMessage("Couldn't play the video. Try again later.")
                loadWithRetry(BuildConfig.UNITY_REWARDED_ID) { rewardedReady = it }
            }
        )
    }

    private fun grantAdFree() {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_AD_FREE_UNTIL, System.currentTimeMillis() + AD_FREE_MS).apply()
        _isAdFree.value = true
    }

    fun refreshAdFree() {
        val ctx = appContext ?: return
        val until = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_AD_FREE_UNTIL, 0L)
        _isAdFree.value = System.currentTimeMillis() < until
    }
}

/** Finds the hosting Activity from any Context (Compose gives a ContextWrapper). */
fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
