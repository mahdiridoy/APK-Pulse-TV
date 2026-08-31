package com.lagradost.cloudstream3.ui.livetv

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

/**
 * Handles fetching + parsing + caching of the permanent Live TV playlist.
 *
 * The playlist url is fixed as requested. Every successful fetch overwrites the local cache so
 * that the app can start instantly from cache while a fresh copy is fetched in the background.
 */
object LiveTvRepository {

    /** Permanent Live TV playlist source. */
    const val PLAYLIST_URL = "https://mahdiridoy.github.io/Tv/mahdi_iptv.m3u8"

    private const val CACHE_KEY = "live_tv_channels_cache_v1"
    private const val LAST_CHANNEL_KEY = "live_tv_last_channel_id_v1"

    /** Hard cap on a single playlist fetch so a stalled first-launch request (no cache yet)
     * can never leave the Live TV screen stuck on an empty, unresponsive grid until the app
     * is killed and restarted. On timeout we fall back to whatever cache exists and surface
     * a retryable error in the UI. */
    private const val FETCH_TIMEOUT_MS = 20_000L

    /**
     * Fetch + parse the playlist.
     * @param forceRefresh when true, bypasses the http cache to guarantee a network hit
     *                      (used for app-start auto scan and pull/swipe-down-to-refresh).
     * @return the freshly parsed channel list, or the last known-good cached list if the
     *         network request fails / returns an unparsable playlist. Never throws.
     */
    suspend fun getChannels(forceRefresh: Boolean = false): List<LiveTvChannel> {
        return try {
            val response = withTimeout(FETCH_TIMEOUT_MS) {
                app.get(
                    PLAYLIST_URL,
                    cacheTime = if (forceRefresh) 0 else 10,
                    cacheUnit = TimeUnit.MINUTES,
                )
            }
            val parsed = M3uParser.parse(response.text)
            if (parsed.isNotEmpty()) {
                saveToCache(parsed)
                parsed
            } else {
                // Empty / unparsable response, fall back to whatever we had before.
                getCachedChannels()
            }
        } catch (e: TimeoutCancellationException) {
            // Timeout is a fetch failure, not a real cancellation: fall back to cache and let
            // the UI show a retryable error instead of leaving the screen stranded.
            logError(e)
            getCachedChannels()
        } catch (e: CancellationException) {
            // Real cancellation (e.g. ViewModel cleared) must propagate, not be swallowed
            // as a fetch failure.
            throw e
        } catch (e: Exception) {
            logError(e)
            getCachedChannels()
        }
    }

    fun getCachedChannels(): List<LiveTvChannel> {
        return getKey<List<LiveTvChannel>>(CACHE_KEY) ?: emptyList()
    }

    private fun saveToCache(channels: List<LiveTvChannel>) {
        setKey(CACHE_KEY, channels)
    }

    fun getLastChannelId(): String? = getKey<String>(LAST_CHANNEL_KEY)

    fun setLastChannelId(id: String?) {
        setKey(LAST_CHANNEL_KEY, id)
    }
}
