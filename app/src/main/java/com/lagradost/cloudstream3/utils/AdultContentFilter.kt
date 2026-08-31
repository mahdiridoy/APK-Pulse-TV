package com.lagradost.cloudstream3.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Best-effort title-based adult content filter.
 *
 * The Adult Content Filter setting drives [MainAPI.settingsForProvider.enableAdult] (see
 * MainActivity.onCreate), which already blocks separately-NSFW-tagged provider plugins from
 * loading at all. That doesn't help when a general-purpose provider mixes explicit titles into
 * its normal results - [SearchResponse] has no per-item "isAdult" flag providers are required to
 * set, so this filters by matching common explicit-content keywords/patterns against the title
 * as a second layer, applied to Search and Home results.
 */
object AdultContentFilter {

    /** True when the filter should currently be applied (i.e. adult content should be hidden). */
    val isEnabled: Boolean
        get() = !MainAPI.settingsForProvider.enableAdult

    private val keywordRegex = Regex(
        pattern = """(?i)\b(""" +
            listOf(
                "porn(o|hub|star)?", "xxx", "hentai", "nsfw", "erotic(a)?", "sex(y|ual)?",
                "nud(e|es|ity)", "naked", "topless", "18\\+", "adult\\s?(film|movie|video|xxx)",
                "camgirl", "escort", "fetish", "onlyfans", "javhd?", "brazzers",
                "hardcore", "softcore", "milf", "swinger", "strip(per|tease)", "orgy",
                "gangbang", "bdsm", "kinky", "lickerish", "playboy", "bareback",
                "cuckold", "creampie", "boobs", "tits", "big\\s?ass", "anal", "blowjob",
                "shemale", "transsexual", "voyeur", "gonewild", "threesome", "dildo",
                "bondage", "dominatrix", "deepthroat", "squirting", "chaturbate",
                "sexcam", "nude\\s?(beach|girl|women|woman)", "lingerie\\s?xxx", "xxx\\s?18",
            ).joinToString("|") +
            """)\b"""
    )

    fun isLikelyAdult(title: String?): Boolean {
        if (title.isNullOrBlank()) return false
        return keywordRegex.containsMatchIn(title)
    }

    fun isLikelyAdult(response: SearchResponse): Boolean {
        if (response.type == TvType.NSFW) return true
        return isLikelyAdult(response.name)
    }

    fun <T : SearchResponse> filterList(items: List<T>): List<T> {
        if (!isEnabled) return items
        return items.filterNot { isLikelyAdult(it) }
    }

    private const val SKIN_RATIO_THRESHOLD = 0.32f

    private val imageCache = ConcurrentHashMap<String, Boolean>()

    private fun isSkinPixel(r: Int, g: Int, b: Int): Boolean {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if (r <= 95 || g <= 40 || b <= 20) return false
        if (max - min <= 15) return false
        if (abs(r - g) <= 15) return false
        if (r <= g || r <= b) return false
        val cb = 128 - 0.168736 * r - 0.331264 * g + 0.5 * b
        val cr = 128 + 0.5 * r - 0.418688 * g - 0.081312 * b
        if (cb in 85.0..135.0 && cr in 135.0..180.0) return true
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val cmax = maxOf(rf, gf, bf)
        val cmin = minOf(rf, gf, bf)
        val delta = cmax - cmin
        if (delta == 0f) return false
        var hue = when (cmax) {
            rf -> 60 * (((gf - bf) / delta) % 6)
            gf -> 60 * (((bf - rf) / delta) + 2)
            else -> 60 * (((rf - gf) / delta) + 4)
        }
        if (hue < 0) hue += 360
        return hue <= 25 || hue >= 335
    }

    private fun decodeDownsampled(bytes: ByteArray, maxDim: Int = 128): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private suspend fun fetchAndCheck(url: String): Boolean {
        val response = app.get(url)
        val bytes = response.okhttpResponse.body?.byteStream()?.use { it.readBytes() } ?: return false
        val bitmap = decodeDownsampled(bytes) ?: return false
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return false
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        var skin = 0
        for (pixel in pixels) {
            if (isSkinPixel((pixel shr 16) and 0xFF, (pixel shr 8) and 0xFF, pixel and 0xFF)) skin++
        }
        return skin.toFloat() / pixels.size >= SKIN_RATIO_THRESHOLD
    }

    private suspend fun isImageAdult(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        imageCache[url]?.let { return it }
        val result = runCatching { fetchAndCheck(url) }.getOrDefault(false)
        imageCache[url] = result
        return result
    }

    /**
     * Second layer on top of [filterList]: downloads the poster/backdrop of every remaining item
     * and drops the ones whose image is mostly skin-toned (best-effort on-device nude detection).
     */
    suspend fun <T : SearchResponse> filterByImage(items: List<T>): List<T> {
        if (!isEnabled) return items
        if (items.isEmpty()) return items
        val semaphore = Semaphore(8)
        return coroutineScope {
            val keep = items.map { item ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        !isImageAdult(item.posterUrl)
                    }
                }
            }.awaitAll()
            items.filterIndexed { index, _ -> keep[index] }
        }
    }
}
