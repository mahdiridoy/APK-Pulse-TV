package com.pulsestream.app.ui.livetv

import com.pulsestream.app.CloudStreamApp.Companion.getKey
import com.pulsestream.app.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

/**
 * Handles fetching + parsing + caching of the permanent Live TV playlist.
 *
 * Supports two built-in M3U server sources (Server 1, Server 2).
 * The selected server persists across sessions via DataStore.
 */
object LiveTvRepository {

    /** Available built-in M3U server sources. */
    enum class Server(val displayName: String, val url: String) {
        SERVER_1("Server 1", "https://mahdiridoy.github.io/Tv/mahdi_iptv.m3u8"),
        SERVER_2("Server 2", "https://mahdiridoy.github.io/Tv/ridoyiptv.m3u"),
    }

    /** Default server index (Server 1). */
    private const val DEFAULT_SERVER = 0

    private const val CACHE_KEY = "live_tv_channels_cache_v1"
    private const val LAST_CHANNEL_KEY = "live_tv_last_channel_id_v1"
    private const val SELECTED_SERVER_KEY = "live_tv_selected_server_v1"

    /** Hard cap on a single playlist fetch so a stalled first-launch request (no cache yet)
     *  can never leave the Live TV screen stuck on an empty, unresponsive grid until the app
     *  is killed and restarted. On timeout we fall back to whatever cache exists and surface
     *  a retryable error in the UI. */
    private const val FETCH_TIMEOUT_MS = 20_000L

    /**
     * Fetch + parse the playlist from the currently selected server.
     *
     * @param forceRefresh when true, bypasses the http cache to guarantee a network hit
     *                      (used for pull/swipe-down-to-refresh).
     * @return the freshly parsed channel list, or the last known-good cached list if the
     *         network request fails / returns an unparsable playlist. Never throws.
     */
    suspend fun getChannels(forceRefresh: Boolean = false): List<LiveTvChannel> {
        return try {
            val server = getSelectedServer()
            val response = withTimeout(FETCH_TIMEOUT_MS) {
                app.get(
                    server.url,
                    cacheTime = if (forceRefresh) 0 else 10,
                    cacheUnit = TimeUnit.MINUTES,
                )
            }
            val channels = M3uParser.parse(response.text)
            if (channels.isNotEmpty()) {
                saveToCache(channels)
                channels
            } else {
                getCachedChannels()
            }
        } catch (e: TimeoutCancellationException) {
            logError(e)
            getCachedChannels()
        } catch (e: CancellationException) {
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

    /**
     * Returns the currently selected server, falling back to SERVER_1 if the stored index
     * is out of range (e.g. first launch or corrupt data).
     */
    fun getSelectedServer(): Server {
        val index = getKey<Int>(SELECTED_SERVER_KEY) ?: DEFAULT_SERVER
        return Server.entries.getOrElse(index) { Server.SERVER_1 }
    }

    /**
     * Persists the selected server index and returns the new [Server].
     */
    fun setSelectedServer(server: Server): Server {
        setKey(SELECTED_SERVER_KEY, server.ordinal)
        return server
    }

    /**
     * Returns all available servers.
     */
    fun getAvailableServers(): List<Server> = Server.entries.toList()
}
