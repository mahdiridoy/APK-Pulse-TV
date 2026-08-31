package com.lagradost.cloudstream3.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.databinding.ActivitySplashBinding
import com.lagradost.cloudstream3.ui.account.AccountSelectActivity

/**
 * The actual launcher entry point. Shows the sponsor ad (view-only, no clicks/touches reach it)
 * for [SPLASH_DURATION_MS] while the rest of the app finishes initializing in the background
 * (CloudStreamApp.onCreate() already runs before this activity even starts), then hands off to
 * the normal app flow via [AccountSelectActivity].
 */
class SplashActivity : AppCompatActivity() {

    private var proceeded = false
    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null

    companion object {
        private const val SPLASH_DURATION_MS = 5000L
        private const val AD_URL = "https://mahdiridoy.github.io/Tv/ad.html"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        val binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        webView = binding.splashAdWebview.apply {
            settings.javaScriptEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.domStorageEnabled = true
            isClickable = false
            isLongClickable = false
            webViewClient = object : WebViewClient() {
                // View-only: never let the ad page navigate away to some other page.
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    return true
                }
            }
            try {
                loadUrl(AD_URL)
            } catch (_: Throwable) {
                // If the ad fails to load for any reason, never block the app from starting.
            }
        }

        // The transparent view already sitting on top of the WebView absorbs all touches by
        // simply being clickable with no listener attached, per view-only requirement.

        handler.postDelayed({ proceedToApp() }, SPLASH_DURATION_MS)
    }

    private fun proceedToApp() {
        if (proceeded) return
        proceeded = true
        startActivity(Intent(this, AccountSelectActivity::class.java))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView?.apply {
            stopLoading()
            webViewClient = WebViewClient()
            destroy()
        }
        webView = null
        super.onDestroy()
    }
}
