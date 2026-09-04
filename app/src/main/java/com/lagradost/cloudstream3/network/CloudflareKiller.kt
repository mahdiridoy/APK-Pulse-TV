package com.lagradost.cloudstream3.network

import com.pulsestream.app.network.CloudflareKiller as RealCloudflareKiller
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Compatibility shim: external .cs3 plugins expect [CloudflareKiller] at
 * [com.lagradost.cloudstream3.network.CloudflareKiller] (the original
 * CloudStream3 package). This class delegates to the real implementation
 * at [com.pulsestream.app.network.CloudflareKiller].
 */
class CloudflareKiller : Interceptor {
    private val delegate = RealCloudflareKiller()

    /** Saved cookies for bypass */
    val savedCookies: MutableMap<String, Map<String, String>>
        get() = delegate.savedCookies

    /** Gets headers with cookies and webview user agent */
    fun getCookieHeaders(url: String): Headers =
        delegate.getCookieHeaders(url)

    /** OkHttp interceptor — delegates to real implementation */
    override fun intercept(chain: Interceptor.Chain): Response =
        delegate.intercept(chain)

    companion object {
        const val TAG = "CloudflareKiller"

        fun parseCookieMap(cookie: String): Map<String, String> =
            RealCloudflareKiller.parseCookieMap(cookie)
    }
}
