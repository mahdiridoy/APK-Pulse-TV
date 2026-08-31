package com.lagradost.cloudstream3.ui.livetv

/**
 * Lightweight parser for Extended M3U / M3U8 IPTV playlists.
 *
 * Supports lines of the form:
 * #EXTM3U
 * #EXTINF:-1 tvg-id="id" tvg-name="Name" tvg-logo="http://logo.png" group-title="Group",Channel Name
 * #EXTVLCOPT:... (ignored, optional extra tags between EXTINF and the url are skipped)
 * http://server/stream.m3u8
 *
 * It is intentionally forgiving: malformed / partial entries are skipped rather than
 * throwing, so a single bad line in a large playlist never breaks the whole list.
 */
object M3uParser {

    private val ATTRIBUTE_REGEX = Regex("([a-zA-Z0-9\\-]+)=\"([^\"]*)\"")

    fun parse(raw: String?): List<LiveTvChannel> {
        if (raw.isNullOrBlank()) return emptyList()

        // Normalize line endings and split, dropping empty lines up front.
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split("\n")

        val channels = ArrayList<LiveTvChannel>()
        val seenIds = HashSet<String>()

        var pendingTvgId: String? = null
        var pendingTvgName: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null
        var pendingDisplayName: String? = null

        fun resetPending() {
            pendingTvgId = null
            pendingTvgName = null
            pendingLogo = null
            pendingGroup = null
            pendingDisplayName = null
        }

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTM3U") -> {
                    // Header, nothing to do.
                }

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    // Everything after the LAST comma on this line is the display name,
                    // everything before it (after the colon) contains the duration + attributes.
                    val colonIndex = line.indexOf(':')
                    val body = if (colonIndex != -1) line.substring(colonIndex + 1) else ""
                    val lastComma = body.lastIndexOf(',')

                    val attrPart = if (lastComma != -1) body.substring(0, lastComma) else body
                    val namePart = if (lastComma != -1) body.substring(lastComma + 1).trim() else null

                    var tvgId: String? = null
                    var tvgName: String? = null
                    var logo: String? = null
                    var group: String? = null

                    for (match in ATTRIBUTE_REGEX.findAll(attrPart)) {
                        val key = match.groupValues[1].lowercase()
                        val value = match.groupValues[2]
                        when (key) {
                            "tvg-id" -> tvgId = value.ifBlank { null }
                            "tvg-name" -> tvgName = value.ifBlank { null }
                            "tvg-logo" -> logo = value.ifBlank { null }
                            "group-title" -> group = value.ifBlank { null }
                        }
                    }

                    pendingTvgId = tvgId
                    pendingTvgName = tvgName
                    pendingLogo = logo
                    pendingGroup = group
                    pendingDisplayName = namePart?.ifBlank { null }
                }

                line.startsWith("#") -> {
                    // Any other tag (#EXTVLCOPT, #EXTGRP, comments, etc) - ignored, but does
                    // not clear the pending EXTINF info since the url line still follows.
                }

                else -> {
                    // This is a URL / stream line.
                    val url = line
                    if (url.isNotBlank() && (url.contains("://"))) {
                        val name = pendingDisplayName?.takeIf { it.isNotBlank() }
                            ?: pendingTvgName?.takeIf { it.isNotBlank() }
                            ?: url.substringAfterLast('/').substringBefore('?')
                                .ifBlank { "Unnamed Channel" }

                        val id = LiveTvChannel.makeId(pendingTvgId, name, url)
                        if (seenIds.add(id)) {
                            channels.add(
                                LiveTvChannel(
                                    id = id,
                                    name = name,
                                    streamUrl = url,
                                    logoUrl = pendingLogo,
                                    groupTitle = pendingGroup,
                                    tvgId = pendingTvgId,
                                )
                            )
                        }
                    }
                    resetPending()
                }
            }
        }

        return deduplicateByName(channels)
    }

    /**
     * Merge channels with the exact same display name (case-sensitive).
     * The first occurrence becomes the primary; subsequent ones become mirrors.
     * This way users see one entry per channel, and if the primary server dies
     * the player can automatically try the next mirror.
     */
    private fun deduplicateByName(channels: List<LiveTvChannel>): List<LiveTvChannel> {
        if (channels.isEmpty()) return channels

        // LinkedHashMap preserves insertion order.
        val grouped = LinkedHashMap<String, MutableList<LiveTvChannel>>()
        for (ch in channels) {
            grouped.getOrPut(ch.name) { mutableListOf() }.add(ch)
        }

        return grouped.map { (name, group) ->
            if (group.size == 1) {
                // Unique name — return as-is with a stable name-based ID.
                val ch = group[0]
                ch.copy(id = LiveTvChannel.makeNameId(name))
            } else {
                // Multiple streams with the same name — merge mirrors.
                val primary = group[0]
                val mirrors = group.drop(1).map { it.streamUrl }
                primary.copy(
                    id = LiveTvChannel.makeNameId(name),
                    mirrorUrls = mirrors,
                )
            }
        }
    }
}
