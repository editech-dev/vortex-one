package com.editech.services.utils

import android.app.Activity
import android.content.Context
import android.util.Log
import com.unity3d.ads.IUnityAdsInitializationListener
import com.unity3d.ads.IUnityAdsLoadListener
import com.unity3d.ads.IUnityAdsShowListener
import com.unity3d.ads.UnityAds
import android.os.Handler
import android.os.Looper
import android.widget.Toast

object AdManager {
    private const val GAME_ID = "6038509"
    private const val INTERSTITIAL_ID = "Interstitial_Android"
    private const val TAG = "AdManager"
    
    // Frequency cap configuration
    // Frequency cap configuration
    private const val AD_FREQUENCY_MS = 4 * 60 * 60 * 1000 // 4 horas
    private const val WATCHDOG_TIMEOUT_MS = 75000L // 75 seconds watchdog
    private var lastAdShowTime: Long = 0
    
    private var isInitialized = false
    private var isAdLoaded = false
    private var isTestMode = false
    
    private var initRetryCount = 0
    private const val MAX_INIT_RETRIES = 3
    private const val INIT_RETRY_DELAY_MS = 5000L

    fun isSdkInitialized(): Boolean {
        return isInitialized
    }

    fun initialize(context: Context, testMode: Boolean) {
        if (isInitialized) return
        isTestMode = testMode

        UnityAds.initialize(context, GAME_ID, isTestMode, object : IUnityAdsInitializationListener {
            override fun onInitializationComplete() {
                isInitialized = true
                initRetryCount = 0
                // showDebugToast(context, "Unity Ads initialized successfully")
                Log.d(TAG, "Unity Ads initialized successfully")
                // Load an ad immediately after initialization
                loadInterstitial(context)
            }

            override fun onInitializationFailed(error: UnityAds.UnityAdsInitializationError?, message: String?) {
                val errorMessage = "Unity Ads initialization failed: $error - $message"
                Log.e(TAG, errorMessage)
                // showDebugToast(context, "Init Failed: $message. Retrying...")
                
                if (initRetryCount < MAX_INIT_RETRIES) {
                    initRetryCount++
                    Log.d(TAG, "Retrying initialization in ${INIT_RETRY_DELAY_MS / 1000}s (Attempt $initRetryCount/$MAX_INIT_RETRIES)")
                    Handler(Looper.getMainLooper()).postDelayed({
                        initialize(context, testMode)
                    }, INIT_RETRY_DELAY_MS)
                } else {
                    // showDebugToast(context, "Unity Ads Init Failed permanently: $message")
                }
            }
        })
    }

    fun loadInterstitial(context: Context) {
        if (!isInitialized) {
            Log.w(TAG, "Cannot load ad: Not initialized")
            return
        }
        
        UnityAds.load(INTERSTITIAL_ID, object : IUnityAdsLoadListener {
            override fun onUnityAdsAdLoaded(placementId: String) {
                Log.d(TAG, "Interstitial loaded: $placementId")
                isAdLoaded = true
                // Handler(Looper.getMainLooper()).post { showDebugToast(context, "Debug: Ad Loaded") }
            }

            override fun onUnityAdsFailedToLoad(
                placementId: String,
                error: UnityAds.UnityAdsLoadError,
                message: String
            ) {
                Log.e(TAG, "Failed to load interstitial: $error - $message")
                     // showDebugToast(context, "Debug: Ad Load Failed: $message")
            }
        })
    }

    fun showInterstitial(activity: Activity, onComplete: () -> Unit) {
        val currentTime = System.currentTimeMillis()
        
        // Check frequency cap
        if (currentTime - lastAdShowTime < AD_FREQUENCY_MS) {
            Log.d(TAG, "Frequency cap active. Skipping ad.")
            onComplete()
            return
        }

        // Watchdog mechanics to prevent "stuck" ads
        var isCompleted = false
        val watchdogHandler = Handler(Looper.getMainLooper())
        
        // Thread-safe(ish) completion handler assurance
        val finishAd = {
            if (!isCompleted) {
                isCompleted = true
                watchdogHandler.removeCallbacksAndMessages(null) // Cancel watchdog
                // Ensure callback runs on Main Thread
                Handler(Looper.getMainLooper()).post {
                    onComplete()
                }
            }
        }

        // Watchdog Runnable
        val watchdogRunnable = Runnable {
            Log.w(TAG, "Ad Watchdog Triggered: Timeout after ${WATCHDOG_TIMEOUT_MS}ms. Forcing flow continuation.")
            finishAd()
        }

        // Start Watchdog
        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_TIMEOUT_MS)

        if (UnityAds.isInitialized && isAdLoaded) {
             UnityAds.show(activity, INTERSTITIAL_ID, object : IUnityAdsShowListener {
                override fun onUnityAdsShowStart(placementId: String) {
                    Log.d(TAG, "Ad started")
                }

                override fun onUnityAdsShowClick(placementId: String) {
                    Log.d(TAG, "Ad clicked")
                }

                override fun onUnityAdsShowComplete(
                    placementId: String,
                    state: UnityAds.UnityAdsShowCompletionState
                ) {
                    Log.d(TAG, "Ad finished")
                    lastAdShowTime = System.currentTimeMillis()
                    isAdLoaded = false
                    // Reload for next time
                    loadInterstitial(activity)
                    finishAd()
                }

                override fun onUnityAdsShowFailure(
                    placementId: String,
                    error: UnityAds.UnityAdsShowError,
                    message: String
                ) {
                    Log.e(TAG, "Ad show failed: $message")
                    isAdLoaded = false
                    // Toast removed for better UX
                    finishAd()
                }
            })
        } else {
            Log.d(TAG, "Ad not ready or SDK not initialized")
            // Try to load for next time if initialized
            if (isInitialized) loadInterstitial(activity)
            finishAd()
        }
    }

    // Banner Ad Logic
    fun loadBanner(activity: Activity, bannerContainer: android.widget.RelativeLayout) {
        if (!isInitialized) {
            Log.w(TAG, "Cannot load banner: SDK not initialized")
            return
        }

        val banner = com.unity3d.services.banners.BannerView(activity, "Banner_Android", com.unity3d.services.banners.UnityBannerSize(320, 50))
        banner.listener = object : com.unity3d.services.banners.BannerView.IListener {
            override fun onBannerLoaded(bannerAdView: com.unity3d.services.banners.BannerView) {
                Log.d(TAG, "Banner loaded")
                banner.visibility = android.view.View.VISIBLE
                // Toast.makeText(activity, "Debug: Banner Loaded", Toast.LENGTH_SHORT).show()
            }

            override fun onBannerClick(bannerAdView: com.unity3d.services.banners.BannerView) {
                Log.d(TAG, "Banner clicked")
            }

            override fun onBannerFailedToLoad(bannerAdView: com.unity3d.services.banners.BannerView, errorInfo: com.unity3d.services.banners.BannerErrorInfo) {
                Log.e(TAG, "Banner failed to load: ${errorInfo.errorMessage}")
                // Toast removed for better UX
            }

            override fun onBannerLeftApplication(bannerAdView: com.unity3d.services.banners.BannerView) {
                Log.d(TAG, "Banner left application")
            }

            override fun onBannerShown(bannerAdView: com.unity3d.services.banners.BannerView?) {
                 Log.d(TAG, "Banner shown")
                 // Toast.makeText(activity, "Debug: Banner Shown", Toast.LENGTH_SHORT).show()
            }
        }
        
        bannerContainer.removeAllViews()
        bannerContainer.addView(banner)
        banner.load()
    }
}
