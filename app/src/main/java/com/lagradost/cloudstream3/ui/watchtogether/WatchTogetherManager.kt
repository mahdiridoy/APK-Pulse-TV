package com.lagradost.cloudstream3.ui.watchtogether

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Small client for the PulseStream Watch Together Cloudflare Worker.
 * The manager intentionally has no dependency on the player so it can be
 * reused by Settings and the player screen.
 */
object WatchTogetherManager {
    private const val SERVER = "https://watch-together-server.pulsestream.workers.dev"
    private const val WS_SERVER = "wss://watch-together-server.pulsestream.workers.dev/ws"

    /** Base URL of the Watch Together server. */
    val serverUrl: String
        get() = SERVER

    private const val PREFS = "watch_together"
    private const val PREF_ROOM = "room_code"
    private const val PREF_TOKEN = "host_token"
    private const val PREF_ROLE = "role"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var context: Context? = null

    @Volatile
    var role: Role = Role.NONE
        private set

    @Volatile
    var roomCode: String? = null
        private set

    @Volatile
    var connected: Boolean = false
        private set

    /** Latest position the room has synced, used by the viewer to seek on load. */
    @Volatile
    var lastPositionMs: Long = 0L
        private set

    /** Latest playing state the room has synced, used by the viewer to resume paused rooms. */
    @Volatile
    var lastPlaying: Boolean = false
        private set

    /** Latest content the room has shared, used to detect what the viewer should follow. */
    @Volatile
    var lastContent: ContentInfo? = null
        private set

    /**
     * Last content that was dispatched to [contentListener]/[listener]. Used to avoid
     * re-navigating / re-loading identical content when the room re-sends its state
     * (e.g. on reconnect), which previously could bounce the viewer's screen around
     * or trigger a spurious load error right after connecting.
     */
    @Volatile
    private var lastFiredContent: ContentInfo? = null

    /** Last navigation the host broadcast, to avoid re-navigating the viewer to the same screen. */
    @Volatile
    private var lastFiredNavigate: NavigateInfo? = null

    /** Name shown to other participants. Set before creating/joining a room. */
    @Volatile
    var displayName: String = ""

    /** Last room name used when creating a room, so the dialog can prefill it. */
    @Volatile
    var lastRoomName: String? = null

    /** Current roster of everyone connected to the room (including this device). */
    @Volatile
    var users: List<RoomUser> = emptyList()
        private set

    /** Recent chat messages in the current room. */
    @Volatile
    var chatMessages: List<ChatMessage> = emptyList()
        private set

    /** Last sync position delta in ms (positive = ahead, negative = behind, 0 = synced). */
    @Volatile
    var syncDeltaMs: Long = 0L
        private set

    private var pendingContent: JSONObject? = null

    interface ContentListener {
        fun onContent(content: ContentInfo, positionMs: Long, playing: Boolean)
    }

    var contentListener: ContentListener? = null

    /** Dedicated callback for the roster, so a dialog can observe it without stealing
     * the player's [listener]. */
    var usersListener: ((List<RoomUser>) -> Unit)? = null

    /** Callback for incoming chat messages. */
    var chatListener: ((ChatMessage) -> Unit)? = null

    /** Callback for sync status updates (delta in ms). */
    var syncStatusListener: ((syncDeltaMs: Long, isPlaying: Boolean) -> Unit)? = null

    /**
     * Fired when the host navigates to another screen (tab / result). The viewer uses it to
     * auto-visit the same screen. Only relevant for viewers.
     */
    var navigateListener: ((NavigateInfo) -> Unit)? = null

    interface Listener {
        fun onConnected(role: Role, roomCode: String)
        fun onDisconnected()
        fun onError(message: String)
        fun onPlay(positionMs: Long)
        fun onPause(positionMs: Long)
        fun onSeek(positionMs: Long)
        fun onContent(content: ContentInfo, positionMs: Long, playing: Boolean)
        fun onUsersChanged(users: List<RoomUser>)
    }

    enum class Role {
        NONE,
        HOST,
        VIEWER
    }

    var listener: Listener? = null

    /** Fired with true when the socket opens and false when it closes (incl. explicit disconnect). */
    var connectionListener: ((connected: Boolean) -> Unit)? = null

    /**
     * Fired when the host leaves / the server closes the room, so the UI can
     * disconnect every remaining viewer and return them to the main menu.
     */
    var roomClosedListener: (() -> Unit)? = null

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    /**
     * Called on app startup. If a previous process was killed while connected (a force-close
     * that could never run [disconnect]), the saved room connection is left behind. Detect it
     * and invalidate it so the app always starts in a clean, disconnected state instead of
     * pretending the old room is still active. The room itself is torn down server-side as
     * soon as the dead host WebSocket is noticed.
     */
    fun cleanupStaleSession() {
        if (connected) return
        val prefs = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        if (prefs.getString(PREF_ROOM, null) == null) return
        clearSavedConnection()
        role = Role.NONE
        roomCode = null
        pendingContent = null
        lastContent = null
        lastFiredContent = null
        lastFiredNavigate = null
        lastPositionMs = 0L
        lastPlaying = false
        users = emptyList()
        chatMessages = emptyList()
        syncDeltaMs = 0L
    }

    fun createRoom(roomName: String, callback: (Result<RoomInfo>) -> Unit) {
        val body = JSONObject()
            .put("roomName", roomName)
            .toString()

        val request = Request.Builder()
            .url("$SERVER/room/create")
            .post(okhttp3.RequestBody.create("application/json".toMediaTypeCompat(), body))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { callback(Result.failure(e)) }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val text = it.body?.string().orEmpty()
                        val json = JSONObject(text)
                        if (!it.isSuccessful) {
                            throw IOException(json.optString("error", "Room creation failed."))
                        }
                        val info = RoomInfo(
                            roomName = json.getString("roomName"),
                            roomCode = json.getString("roomCode"),
                            hostToken = json.getString("hostToken")
                        )
                        mainHandler.post { callback(Result.success(info)) }
                    } catch (e: Throwable) {
                        mainHandler.post { callback(Result.failure(e)) }
                    }
                }
            }
        })
    }

    fun connectAsHost(roomCode: String, hostToken: String) {
        connect(roomCode, Role.HOST, hostToken)
    }

    fun connectAsViewer(roomCode: String) {
        connect(roomCode, Role.VIEWER, "")
    }

    /**
     * Check if a room exists on the server before attempting to connect.
     * Returns true + room info if the room exists, false + error message if not.
     */
    fun checkRoom(roomCode: String, callback: (exists: Boolean, error: String?) -> Unit) {
        val request = Request.Builder()
            .url("$SERVER/room/check?room=${java.net.URLEncoder.encode(roomCode, "UTF-8")}")
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { callback(false, "Cannot reach server.") }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val text = it.body?.string().orEmpty()
                        val json = JSONObject(text)
                        if (it.isSuccessful && json.optBoolean("exists", false)) {
                            mainHandler.post { callback(true, null) }
                        } else {
                            val error = json.optString("error", null)
                                ?: if (json.optBoolean("expired", false)) {
                                    "This room has expired."
                                } else {
                                    "Room not found. Ask the host to create a room first."
                                }
                            mainHandler.post { callback(false, error) }
                        }
                    } catch (e: Throwable) {
                        mainHandler.post { callback(false, "Failed to check room.") }
                    }
                }
            }
        })
    }

    fun connect(roomCode: String, requestedRole: Role, token: String) {
        disconnect(false)

        val cleanRoom = roomCode.trim()
        if (cleanRoom.isEmpty()) {
            notifyError("Room code is empty.")
            return
        }

        this.roomCode = cleanRoom
        this.role = requestedRole

        saveConnection(cleanRoom, requestedRole, token)

        val url = buildString {
            append(WS_SERVER)
            append("?room=")
            append(java.net.URLEncoder.encode(cleanRoom, "UTF-8"))
            append("&role=")
            append(requestedRole.name.lowercase())
            if (requestedRole == Role.HOST) {
                append("&token=")
                append(java.net.URLEncoder.encode(token, "UTF-8"))
            }
            append("&name=")
            append(java.net.URLEncoder.encode(displayName, "UTF-8"))
        }

        socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    connected = true
                    mainHandler.post {
                        listener?.onConnected(role, cleanRoom)
                        connectionListener?.invoke(true)
                        // A host can load content before the socket finished opening, so
                        // flush any buffered content once connected.
                        if (role == Role.HOST) {
                            pendingContent?.let { send(it) }
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    connected = false
                    socket = null
                    mainHandler.post {
                        listener?.onDisconnected()
                        connectionListener?.invoke(false)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    connected = false
                    socket = null
                    mainHandler.post {
                        listener?.onError(t.message ?: "WebSocket connection failed.")
                    }
                }
            }
        )
    }

    fun disconnect(clearSavedConnection: Boolean = true) {
        socket?.close(1000, "Watch Together disconnected")
        socket = null
        connected = false
        role = Role.NONE
        roomCode = null
        pendingContent = null
        lastContent = null
        lastFiredContent = null
        lastFiredNavigate = null
        lastPositionMs = 0L
        lastPlaying = false
        users = emptyList()
        chatMessages = emptyList()
        syncDeltaMs = 0L
        if (clearSavedConnection) clearSavedConnection()
        mainHandler.post {
            listener?.onDisconnected()
            connectionListener?.invoke(false)
        }
    }

    fun sendPlay(positionMs: Long) {
        if (role != Role.HOST || !connected) return
        send(JSONObject().put("type", "PLAY").put("position", positionMs))
    }

    fun sendPause(positionMs: Long) {
        if (role != Role.HOST || !connected) return
        send(JSONObject().put("type", "PAUSE").put("position", positionMs))
    }

    fun sendSeek(positionMs: Long) {
        if (role != Role.HOST || !connected) return
        send(JSONObject().put("type", "SEEK").put("position", positionMs))
    }

    fun sendContent(
        url: String,
        apiName: String,
        name: String,
        episodeId: Int?,
        episode: Int?,
        season: Int?,
        positionMs: Long,
        playing: Boolean
    ) {
        if (role != Role.HOST) return

        val json = JSONObject()
            .put("type", "OPEN_CONTENT")
            .put("contentUrl", url)
            .put("apiName", apiName)
            .put("name", name)
            .put("position", positionMs)
            .put("playing", playing)

        if (episodeId != null) json.put("episodeId", episodeId)
        if (episode != null) json.put("episode", episode)
        if (season != null) json.put("season", season)

        pendingContent = json
        if (connected) send(json)
    }

    /**
     * Host only. Tells viewers which screen the host is currently on so they can
     * auto-visit it. [destination] is one of "home", "search", "library", "downloads",
     * "settings", "live_tv" or "result"; for "result" the movie/series url is included.
     */
    fun sendNavigate(destination: String, url: String? = null, apiName: String? = null, name: String? = null) {
        if (role != Role.HOST || !connected) return
        val json = JSONObject()
            .put("type", "NAVIGATE")
            .put("destination", destination)
        if (!url.isNullOrBlank()) json.put("url", url)
        if (!apiName.isNullOrBlank()) json.put("apiName", apiName)
        if (!name.isNullOrBlank()) json.put("name", name)
        send(json)
    }

    /** Send a chat message to all participants. */
    fun sendChat(message: String) {
        if (!connected || message.isBlank()) return
        val json = JSONObject()
            .put("type", "CHAT")
            .put("message", message.trim())
            .put("sender", displayName)
            .put("timestamp", System.currentTimeMillis())
        send(json)
    }

    /** Viewer reports its current playback position so the host can compute sync delta. */
    fun sendSyncReport(positionMs: Long, playing: Boolean) {
        if (role != Role.VIEWER || !connected) return
        send(JSONObject()
            .put("type", "SYNC_REPORT")
            .put("position", positionMs)
            .put("playing", playing)
        )
    }

    private fun send(json: JSONObject) {
        socket?.send(json.toString())
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "CONNECTED" -> Unit
                "ROOM_STATE" -> {
                    val state = json.optJSONObject("state") ?: return
                    val position = state.optLong("position", 0L)
                    val playing = state.optBoolean("playing", false)
                    lastPositionMs = position
                    lastPlaying = playing
                    val contentUrl = state.optString("contentUrl", "")
                    val apiName = state.optString("apiName", "")
                    val name = state.optString("name", "")
                    if (contentUrl.isNotBlank() && apiName.isNotBlank() && name.isNotBlank()) {
                        val content = ContentInfo(
                            url = contentUrl,
                            apiName = apiName,
                            name = name,
                            episodeId = state.optIntOrNull("episodeId"),
                            episode = state.optIntOrNull("episode"),
                            season = state.optIntOrNull("season")
                        )
                        lastContent = content
                        mainHandler.post {
                            // A room can re-send its state on reconnect. Only auto-load when the
                            // viewer isn't already following this exact content, so the screen
                            // doesn't bounce or show a load error after connecting.
                            if (lastFiredContent?.sameContent(content) != true) {
                                lastFiredContent = content
                                contentListener?.onContent(content, position, playing)
                                listener?.onContent(content, position, playing)
                            }
                        }
                    }
                    // When the room has no shared content, leave the viewer's current playback
                    // untouched. Previously this paused/seeked the viewer to 0, which looked
                    // like a broken/errored session when joining an idle room.
                }
                "OPEN_CONTENT" -> {
                    val contentUrl = json.optString("contentUrl", "")
                    val apiName = json.optString("apiName", "")
                    val name = json.optString("name", "")
                    if (contentUrl.isBlank() || apiName.isBlank() || name.isBlank()) return
                    val content = ContentInfo(
                        url = contentUrl,
                        apiName = apiName,
                        name = name,
                        episodeId = json.optIntOrNull("episodeId"),
                        episode = json.optIntOrNull("episode"),
                        season = json.optIntOrNull("season")
                    )
                    mainHandler.post {
                        val position = json.optLong("position", 0L)
                        val playing = json.optBoolean("playing", false)
                        lastPositionMs = position
                        lastPlaying = playing
                        lastContent = content
                        lastFiredContent = content
                        contentListener?.onContent(content, position, playing)
                        listener?.onContent(content, position, playing)
                    }
                }
                "PLAY" -> mainHandler.post {
                    lastPositionMs = json.optLong("position", 0L)
                    lastPlaying = true
                    listener?.onPlay(json.optLong("position", 0L))
                }
                "PAUSE" -> mainHandler.post {
                    lastPositionMs = json.optLong("position", 0L)
                    lastPlaying = false
                    listener?.onPause(json.optLong("position", 0L))
                }
                "SEEK" -> mainHandler.post {
                    lastPositionMs = json.optLong("position", 0L)
                    listener?.onSeek(json.optLong("position", 0L))
                }
                "ROOM_USERS" -> mainHandler.post {
                    updateUsers(json.optJSONArray("users"))
                    listener?.onUsersChanged(users)
                    usersListener?.invoke(users)
                }
                "NAVIGATE" -> mainHandler.post {
                    val destination = json.optString("destination", "")
                    if (destination.isBlank()) return@post
                    val info = NavigateInfo(
                        destination = destination,
                        url = json.optString("url", "").takeIf { it.isNotBlank() },
                        apiName = json.optString("apiName", "").takeIf { it.isNotBlank() },
                        name = json.optString("name", "").takeIf { it.isNotBlank() }
                    )
                    // Skip a repeated broadcast to the exact same screen so the viewer isn't
                    // yanked around when the room re-sends its state on reconnect.
                    if (lastFiredNavigate?.sameAs(info) == true) return@post
                    lastFiredNavigate = info
                    navigateListener?.invoke(info)
                }
                "USER_JOINED" -> mainHandler.post {
                    val role = json.optString("role", "viewer")
                    val name = json.optString("name", if (role == "host") "Host" else "Viewer")
                    val existing = users.filterNot {
                        it.role.name.equals(role, ignoreCase = true) && it.name == name
                    }
                    users = existing + RoomUser(
                        role = if (role == "host") Role.HOST else Role.VIEWER,
                        name = name
                    )
                    listener?.onUsersChanged(users)
                    usersListener?.invoke(users)
                }
                "USER_DISCONNECTED" -> mainHandler.post {
                    val role = json.optString("role", "viewer")
                    val name = json.optString("name", if (role == "host") "Host" else "Viewer")
                    users = users.filterNot {
                        it.role.name.equals(role, ignoreCase = true) && it.name == name
                    }
                    listener?.onUsersChanged(users)
                    usersListener?.invoke(users)
                }
                "ROOM_CLOSED" -> mainHandler.post {
                    // The host left and the server closed the room. Drop the connection,
                    // refresh any open roster dialog and notify the UI to go back to the
                    // main menu (movies & series).
                    disconnect(false)
                    usersListener?.invoke(users)
                    roomClosedListener?.invoke()
                }
                "ERROR" -> notifyError(json.optString("message", "Watch Together error."))
                "CHAT" -> mainHandler.post {
                    val chatMsg = ChatMessage(
                        sender = json.optString("sender", "Unknown"),
                        message = json.optString("message", ""),
                        timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                        isOwn = json.optString("sender") == displayName,
                    )
                    chatMessages = (chatMessages + chatMsg).takeLast(100) // keep last 100
                    chatListener?.invoke(chatMsg)
                }
                "SYNC_STATUS" -> mainHandler.post {
                    // Host tells viewers their sync delta.
                    syncDeltaMs = json.optLong("deltaMs", 0L)
                    val playing = json.optBoolean("playing", false)
                    syncStatusListener?.invoke(syncDeltaMs, playing)
                }
            }
        } catch (e: Throwable) {
            notifyError("Invalid Watch Together message.")
        }
    }

    private fun notifyError(message: String) {
        mainHandler.post {
            listener?.onError(message)
            context?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun updateUsers(array: org.json.JSONArray?) {
        if (array == null) {
            users = emptyList()
            return
        }
        val list = mutableListOf<RoomUser>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val role = obj.optString("role", "viewer")
            val name = obj.optString("name", if (role == "host") "Host" else "Viewer")
            list.add(
                RoomUser(
                    role = if (role == "host") Role.HOST else Role.VIEWER,
                    name = name
                )
            )
        }
        users = list
    }

    private fun saveConnection(room: String, role: Role, token: String) {
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString(PREF_ROOM, room)
            ?.putString(PREF_ROLE, role.name)
            ?.putString(PREF_TOKEN, token)
            ?.apply()
    }

    private fun clearSavedConnection() {
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.remove(PREF_ROOM)
            ?.remove(PREF_ROLE)
            ?.remove(PREF_TOKEN)
            ?.apply()
    }

    data class RoomInfo(
        val roomName: String,
        val roomCode: String,
        val hostToken: String
    )

    data class RoomUser(
        val role: Role,
        val name: String
    )

    data class ChatMessage(
        val sender: String,
        val message: String,
        val timestamp: Long,
        val isOwn: Boolean = false,
    )

    data class ContentInfo(
        val url: String,
        val apiName: String,
        val name: String,
        val episodeId: Int?,
        val episode: Int?,
        val season: Int?
    ) {
        fun sameContent(other: ContentInfo): Boolean =
            url == other.url && apiName == other.apiName && episodeId == other.episodeId
    }

    data class NavigateInfo(
        val destination: String,
        val url: String?,
        val apiName: String?,
        val name: String?
    ) {
        fun sameAs(other: NavigateInfo): Boolean =
            destination == other.destination && url == other.url && apiName == other.apiName && name == other.name
    }
}

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun String.toMediaTypeCompat(): okhttp3.MediaType =
    this.toMediaType()
