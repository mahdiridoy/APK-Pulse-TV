package com.lagradost.cloudstream3.utils

import io.ktor.http.Url

object AdBlocker {
    private val adDomains = setOf(
        "omg10.com",
        "popads.net",
        "popadscdn.net",
        "popcash.net",
        "popunder.net",
        "popunderit.com",
        "popmyads.com",
        "popunderjs.com",
        "popuscash.com",
        "sitepopup.com",
        "adsterra.com",
        "adsterra.net",
        "adbana.com",
        "adbana.net",
        "adk2x.com",
        "adnium.com",
        "adpushup.com",
        "adreactor.com",
        "adserver.com",
        "advertising.com",
        "advertserve.com",
        "advertstream.com",
        "adshost1.com",
        "clickadu.com",
        "propellerads.com",
        "propellerclick.com",
        "exoclick.com",
        "juicyads.com",
        "juicyadss.com",
        "onclickads.net",
        "onclckds.com",
        "trafficjunky.net",
        "zedo.com",
        "cpmstar.com",
        "smartyads.com",
        "bannerbunk.com",
        "intentmedia.net",
        "taboola.com",
        "outbrain.com",
        "revcontent.com",
        "mgid.com",
        "majesticsofaindia.com",
        "circleftp.net",
        "googlesyndication.com",
        "doubleclick.net",
        "googletagservices.com",
        "googleadservices.com",
        "adservice.google.com",
    )

    private val adNameRegex = Regex(
        """(?i)\b(ad|ads|advert|advertisement|sponsored|sponsor|popup|popunder|pop-under)\b"""
    )

    private val adPathRegex = Regex(
        """(?i)(scratchcard|slideup|popunder|popupad|adframe|ads-frame|bannerad|advert|adsense|doubleclick)"""
    )

    fun isAdDomain(host: String): Boolean {
        val lower = host.lowercase().trim()
        return adDomains.any { lower == it || lower.endsWith(".$it") }
    }

    fun isAdUrl(url: String): Boolean {
        return runCatching {
            val parsed = Url(url)
            isAdDomain(parsed.host) || adPathRegex.containsMatchIn(parsed.encodedPath)
        }.getOrDefault(false)
    }

    fun isAdName(name: String): Boolean {
        return adNameRegex.containsMatchIn(name)
    }

    fun isAdLink(link: ExtractorLink): Boolean {
        return isAdUrl(link.url) || isAdName(link.name)
    }
}