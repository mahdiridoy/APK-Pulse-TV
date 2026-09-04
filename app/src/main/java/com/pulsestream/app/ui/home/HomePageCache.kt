package com.pulsestream.app.ui.home

import android.util.Log
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Disk-based cache for home page data, enabling cache-first loading.
 *
 * Stores serialized [HomePageResponse] data per provider in JSON files.
 * TTL-based invalidation (default: 1 hour).
 *
 * Usage:
 *   HomePageCache.init(context.filesDir)
 *   val cached = HomePageCache.get(providerName)  // read
 *   HomePageCache.put(providerName, response)     // write
 */
object HomePageCache {

    private const val TAG = "HomePageCache"
    private const val CACHE_DIR = "home_page_cache"
    private const val CACHE_TTL_MS = 60 * 60 * 1000L  // 1 hour
    private const val MAX_ITEMS_PER_SECTION = 50       // cap per section to limit disk usage

    private var cacheDir: File? = null

    /** M5 FIX: Flag to track if cache was invalidated before init */
    @Volatile
    private var pendingInvalidation = false

    /** C2 FIX: Current account prefix for cache isolation */
    @Volatile
    private var currentAccountPrefix: String = ""

    /** M1 FIX: Use Collections.synchronizedMap for belt-and-suspenders thread safety on LRU */
    private val memoryCache: MutableMap<String, CacheEntry> = Collections.synchronizedMap(
        object : LinkedHashMap<String, CacheEntry>(6, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
                return size > 5
            }
        }
    )

    fun init(filesDir: File) {
        cacheDir = File(filesDir, CACHE_DIR).also { it.mkdirs() }
        // M5 FIX: Process any pending invalidation that was requested before init
        if (pendingInvalidation) {
            pendingInvalidation = false
            memoryCache.clear()
            cacheDir?.listFiles()?.forEach { it.delete() }
            Log.d(TAG, "Pending invalidation applied after init")
        }
        Log.d(TAG, "Cache initialized at ${cacheDir?.absolutePath}")
    }

    /**
     * C2 FIX: Invalidate all caches when switching accounts.
     * Must be called from DataStoreHelper.setAccount() on account switch.
     */
    fun onAccountSwitch(newAccountKey: String) {
        val newPrefix = "$newAccountKey/"
        if (currentAccountPrefix == newPrefix) return
        currentAccountPrefix = newPrefix
        invalidateAll()
        Log.d(TAG, "Account switched to $newPrefix, all caches invalidated")
    }

    /**
     * Read cached home page for a provider. Returns null if cache miss or expired.
     */
    @Synchronized
    fun get(providerName: String): HomePageResponse? {
        // 1. Check memory cache
        memoryCache[providerName]?.let { entry ->
            if (System.currentTimeMillis() - entry.timestamp < CACHE_TTL_MS) {
                Log.d(TAG, "Memory cache HIT: $providerName")
                return entry.response
            }
            // expired
            memoryCache.remove(providerName)
        }

        // 2. Check disk cache
        val file = cacheFile(providerName) ?: return null
        if (!file.exists()) return null

        val age = System.currentTimeMillis() - file.lastModified()
        if (age > CACHE_TTL_MS) {
            file.delete()
            Log.d(TAG, "Disk cache EXPIRED: $providerName (${age}ms old)")
            return null
        }

        return try {
            val json = file.readText()
            val response = deserializeResponse(json)
            // Populate memory cache
            memoryCache[providerName] = CacheEntry(response, System.currentTimeMillis())
            Log.d(TAG, "Disk cache HIT: $providerName (${response.items.size} sections)")
            response
        } catch (e: Exception) {
            Log.w(TAG, "Disk cache READ error: $providerName — ${e.message}")
            file.delete()
            null
        }
    }

    /**
     * Write home page data to cache.
     */
    @Synchronized
    fun put(providerName: String, response: HomePageResponse) {
        try {
            val json = serializeResponse(response)
            val file = cacheFile(providerName) ?: return
            file.writeText(json)
            // Update memory cache
            memoryCache[providerName] = CacheEntry(response, System.currentTimeMillis())
            Log.d(TAG, "Cache WRITE: $providerName (${response.items.size} sections, ${json.length} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "Cache WRITE error: $providerName — ${e.message}")
        }
    }

    /**
     * Invalidate cache for a specific provider.
     */
    @Synchronized
    fun invalidate(providerName: String) {
        memoryCache.remove(providerName)
        cacheFile(providerName)?.let { file ->
            if (file.exists()) file.delete()
        }
        Log.d(TAG, "Cache INVALIDATED: $providerName")
    }

    /**
     * Invalidate all caches.
     */
    @Synchronized
    fun invalidateAll() {
        memoryCache.clear()
        val dir = cacheDir
        if (dir == null) {
            // M5 FIX: Queue invalidation for after init() completes
            pendingInvalidation = true
            Log.w(TAG, "invalidateAll: cacheDir is null — queued for after init()")
            return
        }
        dir.listFiles()?.forEach { it.delete() }
        Log.d(TAG, "All caches INVALIDATED")
    }

    // ---- Serialization ----

    private fun serializeResponse(response: HomePageResponse): String {
        val root = JSONObject()
        root.put("hasNext", response.hasNext)
        root.put("schemaVersion", 1)

        val itemsArray = JSONArray()
        for (section in response.items) {
            val sectionObj = JSONObject()
            sectionObj.put("name", section.name)
            sectionObj.put("isHorizontalImages", section.isHorizontalImages)

            val listArray = JSONArray()
            val itemsToCache = section.list.take(MAX_ITEMS_PER_SECTION)
            for (item in itemsToCache) {
                listArray.put(serializeSearchResponse(item))
            }
            sectionObj.put("list", listArray)
            itemsArray.put(sectionObj)
        }
        root.put("items", itemsArray)
        return root.toString()
    }

    private fun serializeSearchResponse(item: SearchResponse): JSONObject {
        val obj = JSONObject()
        obj.put("name", item.name)
        obj.put("url", item.url)
        obj.put("apiName", item.apiName)
        obj.put("type", item.type?.name)
        obj.put("posterUrl", item.posterUrl)
        obj.put("id", item.id)
        obj.put("quality", item.quality?.name)
        obj.put("score", item.score?.toFloat())

        // Subclass-specific fields
        when (item) {
            is MovieSearchResponse -> {
                obj.put("year", item.year)
            }
            is TvSeriesSearchResponse -> {
                obj.put("year", item.year)
                obj.put("episodes", item.episodes)
            }
            else -> { /* no extra fields */ }
        }
        return obj
    }

    private fun deserializeResponse(json: String): HomePageResponse {
        val root = JSONObject(json)
        val hasNext = root.optBoolean("hasNext", false)
        val itemsArray = root.optJSONArray("items") ?: return newHomePageResponse(emptyList(), hasNext)

        val sections = mutableListOf<HomePageList>()
        for (i in 0 until itemsArray.length()) {
            val sectionObj = itemsArray.getJSONObject(i)
            val name = sectionObj.getString("name")
            val isHorizontalImages = sectionObj.optBoolean("isHorizontalImages", false)

            val listArray = sectionObj.optJSONArray("list") ?: continue
            val items = mutableListOf<SearchResponse>()
            for (j in 0 until listArray.length()) {
                val itemObj = listArray.getJSONObject(j)
                deserializeSearchResponse(itemObj)?.let { items.add(it) }
            }
            sections.add(HomePageList(name, items, isHorizontalImages))
        }
        return newHomePageResponse(sections, hasNext)
    }

    @Suppress("DEPRECATION_ERROR")
    private fun deserializeSearchResponse(obj: JSONObject): SearchResponse? {
        return try {
            val name = obj.getString("name")
            val url = obj.getString("url")
            val apiName = obj.getString("apiName")
            val typeName = obj.optString("type", "")
            val type = typeName.ifEmpty { null }?.let { runCatching { TvType.valueOf(it) }.getOrNull() }
            val posterUrl = obj.optString("posterUrl", "").ifEmpty { null }
            val id = obj.optInt("id", -1).takeIf { it >= 0 }
            val qualityName = obj.optString("quality", "")
            val quality = qualityName.ifEmpty { null }?.let { runCatching { SearchQuality.valueOf(it) }.getOrNull() }
            val scoreValue = obj.optDouble("score", Double.NaN).takeIf { !it.isNaN() }?.toFloat()

            // Determine subtype by available fields
            val hasYear = obj.has("year")
            val hasEpisodes = obj.has("episodes")

            when {
                hasEpisodes -> {
                    TvSeriesSearchResponse(
                        name = name,
                        url = url,
                        apiName = apiName,
                        type = type ?: TvType.TvSeries,
                        posterUrl = posterUrl,
                        year = obj.optInt("year", 0).takeIf { it > 0 },
                        episodes = obj.optInt("episodes", 0).takeIf { it > 0 },
                        id = id,
                        quality = quality,
                        score = scoreValue?.let { Score.from10(it) }
                    )
                }
                hasYear -> {
                    MovieSearchResponse(
                        name = name,
                        url = url,
                        apiName = apiName,
                        type = type ?: TvType.Movie,
                        posterUrl = posterUrl,
                        year = obj.optInt("year", 0).takeIf { it > 0 },
                        id = id,
                        quality = quality,
                        score = scoreValue?.let { Score.from10(it) }
                    )
                }
                else -> {
                    MovieSearchResponse(
                        name = name,
                        url = url,
                        apiName = apiName,
                        type = type ?: TvType.Movie,
                        posterUrl = posterUrl,
                        id = id,
                        quality = quality,
                        score = scoreValue?.let { Score.from10(it) }
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize item: ${e.message}")
            null
        }
    }

    private fun cacheFile(providerName: String): File? {
        val dir = cacheDir ?: return null
        val safeName = providerName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(dir, "$safeName.json")
    }

    private data class CacheEntry(
        val response: HomePageResponse,
        val timestamp: Long
    )
}
