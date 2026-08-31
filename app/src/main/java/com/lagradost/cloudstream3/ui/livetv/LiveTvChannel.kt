package com.lagradost.cloudstream3.ui.livetv

/**
 * Represents a single Live TV / IPTV channel parsed from an M3U / M3U8 playlist.
 *
 * @param id            Stable identifier for the channel (tvg-id if present, otherwise a hash
 *                       of the stream url + name). Used for diffing and remembering last channel.
 * @param name           Display name of the channel (from tvg-name or the trailing EXTINF text).
 * @param streamUrl      The actual playable stream url (.m3u8 / .ts / etc).
 * @param logoUrl        tvg-logo attribute, nullable.
 * @param groupTitle     group-title attribute, used to group channels (e.g. "News", "Sports").
 * @param tvgId          Raw tvg-id attribute, if present.
 * @param mirrorUrls     Alternative stream URLs for the same channel name (deduped).
 */
data class LiveTvChannel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val groupTitle: String? = null,
    val tvgId: String? = null,
    val mirrorUrls: List<String> = emptyList(),
) {
    /** Total number of streams available (primary + mirrors). */
    val totalMirrorCount: Int get() = 1 + mirrorUrls.size

    /** Get the stream URL for the given index (0 = primary, 1+ = mirrors). */
    fun getStreamUrlAtIndex(index: Int): String {
        return if (index == 0) streamUrl else mirrorUrls[index - 1]
    }

    companion object {
        fun makeId(tvgId: String?, name: String, streamUrl: String): String {
            if (!tvgId.isNullOrBlank()) return tvgId
            return "ch_${(name + streamUrl).hashCode()}"
        }

        /** Make an ID from just the channel name, used for merged channels. */
        fun makeNameId(name: String): String = "ch_name_${name.hashCode()}"
    }
}
