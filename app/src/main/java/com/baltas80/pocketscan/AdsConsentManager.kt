package com.baltas80.pocketscan

import android.app.Activity
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Handles EU consent before requesting AdMob ads.
 *
 * Production ad unit: reads `@string/admob_banner_unit_id` (manifest placeholder). While the value
 * is still a placeholder, Google's public test unit is used so no real traffic is generated.
 * Before release, replace the placeholder in `values/strings.xml` with the real production unit ID.
 */
object AdsConsentManager {
    private const val TEST_BANNER_AD_UNIT = "ca-app-pub-3940256099942544/9214589741"
    private var initialized = false

    private fun bannerUnitId(activity: Activity): String {
        val configured = activity.getString(R.string.admob_banner_unit_id).trim()
        return if (configured.isEmpty() || configured.startsWith("REPLACE_WITH")) TEST_BANNER_AD_UNIT else configured
    }

    fun prepare(activity: Activity, container: android.widget.FrameLayout) {
        val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
        val params = ConsentRequestParameters.Builder().build()
        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    if (consentInformation.canRequestAds()) {
                        loadBanner(activity, container)
                    }
                }
            },
            {
                if (consentInformation.canRequestAds()) {
                    loadBanner(activity, container)
                }
            }
        )

        if (consentInformation.canRequestAds()) {
            loadBanner(activity, container)
        }
    }

    private fun loadBanner(activity: Activity, container: android.widget.FrameLayout) {
        if (!initialized) {
            MobileAds.initialize(activity) {}
            initialized = true
        }
        if (container.childCount > 0) return

        container.post {
            val widthPx = container.width
            if (widthPx <= 0) return@post
            val density = activity.resources.displayMetrics.density
            val widthDp = (widthPx / density).toInt()
            val adView = AdView(activity)
            adView.adUnitId = bannerUnitId(activity)
            adView.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, widthDp))
            container.addView(
                adView,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
            adView.loadAd(AdRequest.Builder().build())
        }
    }
}
