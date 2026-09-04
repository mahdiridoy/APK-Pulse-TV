package com.pulsestream.app.ui.livetv

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Represents a user-added custom playlist for the Live TV section.
 *
 * Supports two types:
 * - M3U: Standard M3U/M3U8 playlist with a direct URL
 * - Xtream Codes: IPTV provider using server URL + username + password
 *   (fetched via the provider's get.php endpoint which returns M3U)
 */
data class CustomPlaylist(
    @JsonProperty("id") val id: String,
    @JsonProperty("name") val name: String,
    @JsonProperty("type") val type: PlaylistType,
    @JsonProperty("url") val url: String = "",
    @JsonProperty("username") val username: String = "",
    @JsonProperty("password") val password: String = "",
) {
    enum class PlaylistType(val displayName: String) {
        M3U("M3U Playlist"),
        XTREAM_CODES("Xtream Codes"),
    }

    /** Whether this is a valid playlist (has required fields). */
    val isValid: Boolean
        get() = name.isNotBlank() && when (type) {
            PlaylistType.M3U -> url.isNotBlank()
            PlaylistType.XTREAM_CODES -> url.isNotBlank() && username.isNotBlank() && password.isNotBlank()
        }

    /** Returns the actual URL to fetch the M3U content from. */
    fun getFetchUrl(): String {
        return when (type) {
            PlaylistType.M3U -> url
            PlaylistType.XTREAM_CODES -> {
                // Xtream Codes API: get.php returns M3U playlist
                val base = url.trimEnd('/')
                "$base/get.php?username=$username&password=$password&type=m3u_plus"
            }
        }
    }

    companion object {
        fun generateId(): String = "custom_${System.currentTimeMillis()}"

        /** Cache key for storing the list of custom playlists. */
        const val CACHE_KEY = "live_tv_custom_playlists_v1"
    }
}
