package com.pulsestream.app.sync

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.pulsestream.app.CloudStreamApp
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Firebase Realtime Database manager for Community presence and statistics.
 *
 * RTDB Structure:
 *   community/
 *     presence/{uid}/{sessionId}/  — online presence per session
 *       state: "online"
 *       lastSeen: timestamp
 *       device: { model, manufacturer, sdk }
 *     users/{uid}/  — idempotent user registration
 *       registeredAt: timestamp
 *       lastLogin: timestamp
 *     devices/{deviceId}/  — idempotent device registration
 *       uid: string
 *       registeredAt: timestamp
 *       lastSeen: timestamp
 *
 * Responsibilities:
 *   - Total Users: count of unique Firebase Auth UIDs
 *   - Live Watching: count of unique UIDs with online presence
 *   - Total Installed: count of unique registered devices
 *   - Real-time listeners for Community UI updates
 *
 * Thread safety: All public methods are safe to call from any thread.
 * Never throws — all exceptions are caught and logged.
 */
object FirebaseRTDBManager {

    private const val TAG = "FirebaseRTDB"
    private const val COMMUNITY_PATH = "community"
    private const val PRESENCE_PATH = "community/presence"
    private const val USERS_PATH = "community/users"
    private const val DEVICES_PATH = "community/devices"

    /** Maximum age (ms) before a presence entry is considered stale. */
    private const val PRESENCE_STALE_MS = 2 * 60 * 1000L // 2 minutes

    /** M3 FIX: Minimum interval between recordDownload() calls to prevent counter inflation. */
    private const val DOWNLOAD_DEBOUNCE_MS = 5_000L // 5 seconds

    /** Timestamp of last recordDownload() call. */
    @Volatile
    private var lastDownloadRecordTime = 0L

    private val database: FirebaseDatabase? = runCatching {
        FirebaseDatabase.getInstance()
    }.getOrNull()

    /** Current session ID for this app instance. */
    private val sessionId: String = UUID.randomUUID().toString().take(8)

    /** The device ID (ANDROID_ID) for idempotent device registration. */
    @Volatile
    private var deviceId: String? = null

    /** Whether presence is currently active. */
    @Volatile
    private var presenceActive = false

    /** Handler for scheduling/cancelling the heartbeat. */
    private val heartbeatHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** The currently-active heartbeat Runnable, if any. */
    @Volatile
    private var heartbeatRunnable: Runnable? = null

    /** Local listeners for stats updates. */
    private val statsListeners = ConcurrentHashMap<String, ValueEventListener>()

    /** Callback interface for stats updates. */
    interface StatsCallback {
        fun onLiveWatchingUpdated(count: Int)
        fun onTotalUsersUpdated(count: Int)
        fun onTotalInstalledUpdated(count: Int)
        fun onTotalDownloadsUpdated(count: Int)
    }

    // ---- Public API ------------------------------------------------------

    /**
     * Initialize RTDB. Call once from CloudStreamApp.onCreate() or after
     * Firebase Auth confirms a signed-in user. Safe to call multiple times.
     */
    fun initialize() {
        val db = database ?: run {
            Log.w(TAG, "initialize: Firebase Database unavailable")
            return
        }
        Log.d(TAG, "initialize: RTDB ready, sessionId=$sessionId")

        // Enable persistence for offline support
        runCatching { db.setPersistenceEnabled(true) }

        // Register user idempotently if signed in
        registerCurrentUser()

        // Register device idempotently
        registerCurrentDevice()
    }

    /**
     * Start presence tracking. Call when user is authenticated and app is in foreground.
     * Sets up onDisconnect to automatically clean up.
     */
    fun startPresence() {
        val db = database ?: return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            Log.d(TAG, "startPresence: no signed-in user")
            return
        }
        if (presenceActive) {
            Log.d(TAG, "startPresence: already active for uid=${uid.take(8)}...")
            return
        }

        val presenceRef = db.getReference(PRESENCE_PATH).child(uid).child(sessionId)

        // Write presence data
        val presenceData = hashMapOf(
            "state" to "online",
            "lastSeen" to ServerValue.TIMESTAMP,
            "device" to hashMapOf(
                "model" to Build.MODEL,
                "manufacturer" to Build.MANUFACTURER,
                "sdk" to Build.VERSION.SDK_INT
            )
        )

        presenceRef.setValue(presenceData).addOnSuccessListener {
            Log.d(TAG, "startPresence: online presence set for uid=${uid.take(8)}... session=$sessionId")
            presenceActive = true
        }.addOnFailureListener { e ->
            Log.w(TAG, "startPresence: failed to set presence", e)
        }

        // Set up onDisconnect to remove this presence entry
        presenceRef.onDisconnect().removeValue().addOnSuccessListener {
            Log.d(TAG, "startPresence: onDisconnect registered")
        }.addOnFailureListener { e ->
            Log.w(TAG, "startPresence: failed to register onDisconnect", e)
        }

        // Update lastSeen periodically (every 60 seconds) to detect stale entries
        startPresenceHeartbeat(presenceRef)
    }

    /**
     * Stop presence tracking. Call on logout or app backgrounding.
     * Removes the presence entry immediately.
     */
    fun stopPresence() {
        val db = database ?: return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        // Cancel heartbeat first so it doesn't fire after we remove presence
        stopPresenceHeartbeat()

        if (!presenceActive) return

        val presenceRef = db.getReference(PRESENCE_PATH).child(uid).child(sessionId)
        presenceRef.removeValue().addOnSuccessListener {
            Log.d(TAG, "stopPresence: presence removed for uid=${uid.take(8)}... session=$sessionId")
            presenceActive = false
        }.addOnFailureListener { e ->
            Log.w(TAG, "stopPresence: failed to remove presence", e)
        }
    }

    /**
     * Register a download completion. Increments the totalDownloads counter.
     * Uses RTDB transaction for atomicity.
     * Requires an authenticated user — silently no-ops if not signed in.
     */
    fun recordDownload() {
        val db = database ?: return
        if (FirebaseAuth.getInstance().currentUser == null) {
            Log.d(TAG, "recordDownload: skipped — no authenticated user")
            return
        }
        // M3 FIX: Debounce to prevent rapid-fire counter inflation (e.g. 20-episode batch)
        val now = System.currentTimeMillis()
        if (now - lastDownloadRecordTime < DOWNLOAD_DEBOUNCE_MS) {
            Log.d(TAG, "recordDownload: skipped — debounce (${now - lastDownloadRecordTime}ms since last)")
            return
        }
        lastDownloadRecordTime = now
        val downloadsRef = db.getReference("$COMMUNITY_PATH/stats/totalDownloads")
        downloadsRef.runTransaction(object : com.google.firebase.database.Transaction.Handler {
            override fun doTransaction(mutableData: com.google.firebase.database.MutableData): com.google.firebase.database.Transaction.Result {
                val current = mutableData.getValue(Long::class.java) ?: 0L
                mutableData.value = current + 1
                return com.google.firebase.database.Transaction.success(mutableData)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, currentData: DataSnapshot?) {
                if (error != null) {
                    Log.w(TAG, "recordDownload: transaction failed", error.toException())
                } else if (committed) {
                    Log.d(TAG, "recordDownload: count incremented")
                }
            }
        })
    }

    /**
     * Subscribe to real-time stats updates. Returns a listener key for unsubscription.
     * Callbacks are invoked on the main thread.
     */
    fun subscribeStats(context: Context, callback: StatsCallback): String {
        val db = database ?: run {
            callback.onLiveWatchingUpdated(0)
            callback.onTotalUsersUpdated(0)
            callback.onTotalInstalledUpdated(0)
            return "noop"
        }

        val listenerKey = UUID.randomUUID().toString()

        // Listen for live watching count (unique UIDs with online presence)
        val presenceRef = db.getReference(PRESENCE_PATH)
        val presenceListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val now = System.currentTimeMillis()
                val onlineUids = mutableSetOf<String>()

                for (uidSnap in snapshot.children) {
                    val uid = uidSnap.key ?: continue
                    // Check if any session for this UID is online and not stale
                    for (sessionSnap in uidSnap.children) {
                        val lastSeen = sessionSnap.child("lastSeen").getValue(Long::class.java) ?: 0L
                        val state = sessionSnap.child("state").getValue(String::class.java) ?: ""
                        if (state == "online" && (now - lastSeen) < PRESENCE_STALE_MS) {
                            onlineUids.add(uid)
                            break // One online session is enough for this UID
                        }
                    }
                }
                callback.onLiveWatchingUpdated(onlineUids.size)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "presence listener cancelled", error.toException())
            }
        }
        presenceRef.addValueEventListener(presenceListener)
        statsListeners["${listenerKey}_presence"] = presenceListener

        // Listen for total users count
        val usersRef = db.getReference(USERS_PATH)
        val usersListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val count = snapshot.childrenCount.toInt()
                callback.onTotalUsersUpdated(count)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "users listener cancelled", error.toException())
            }
        }
        usersRef.addValueEventListener(usersListener)
        statsListeners["${listenerKey}_users"] = usersListener

        // Listen for total installed count
        val devicesRef = db.getReference(DEVICES_PATH)
        val devicesListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val count = snapshot.childrenCount.toInt()
                callback.onTotalInstalledUpdated(count)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "devices listener cancelled", error.toException())
            }
        }
        devicesRef.addValueEventListener(devicesListener)
        statsListeners["${listenerKey}_devices"] = devicesListener

        // Listen for total downloads count (atomic counter)
        val downloadsRef = db.getReference("$COMMUNITY_PATH/stats/totalDownloads")
        val downloadsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val count = snapshot.getValue(Long::class.java)?.toInt() ?: 0
                callback.onTotalDownloadsUpdated(count)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "downloads listener cancelled", error.toException())
            }
        }
        downloadsRef.addValueEventListener(downloadsListener)
        statsListeners["${listenerKey}_downloads"] = downloadsListener

        Log.d(TAG, "subscribeStats: listeners registered, key=$listenerKey")
        return listenerKey
    }

    /**
     * Unsubscribe from stats updates. Call when leaving the Community screen.
     */
    fun unsubscribeStats(listenerKey: String) {
        val db = database ?: return

        listOf("presence", "users", "devices", "downloads").forEach { type ->
            val key = "${listenerKey}_$type"
            statsListeners.remove(key)?.let { listener ->
                val ref = when (type) {
                    "presence" -> db.getReference(PRESENCE_PATH)
                    "users" -> db.getReference(USERS_PATH)
                    "devices" -> db.getReference(DEVICES_PATH)
                    "downloads" -> db.getReference("$COMMUNITY_PATH/stats/totalDownloads")
                    else -> return@forEach
                }
                ref.removeEventListener(listener)
                Log.d(TAG, "unsubscribeStats: removed $type listener")
            }
        }
    }

    /**
     * Reset all state. Call on logout.
     */
    fun reset() {
        stopPresence()
        stopPresenceHeartbeat()
        statsListeners.clear()
        presenceActive = false
        Log.d(TAG, "reset: all state cleared")
    }

    // ---- Internals -------------------------------------------------------

    /**
     * Register the current Firebase Auth user idempotently.
     * Uses UID as the key, so repeated logins don't create duplicates.
     */
    private fun registerCurrentUser() {
        val db = database ?: return
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val uid = user.uid

        val userRef = db.getReference(USERS_PATH).child(uid)
        val userData = hashMapOf(
            "lastLogin" to ServerValue.TIMESTAMP,
            "email" to (user.email ?: ""),
            "displayName" to (user.displayName ?: "")
        )

        // Use updateChildren to avoid overwriting registeredAt
        userRef.updateChildren(userData as Map<String, Any>).addOnSuccessListener {
            // Set registeredAt only if this is a new user
            userRef.child("registeredAt").get().addOnSuccessListener { snapshot ->
                if (!snapshot.exists()) {
                    userRef.child("registeredAt").setValue(ServerValue.TIMESTAMP)
                }
            }
            Log.d(TAG, "registerCurrentUser: user registered/updated, uid=${uid.take(8)}...")
        }.addOnFailureListener { e ->
            Log.w(TAG, "registerCurrentUser: failed", e)
        }
    }

    /**
     * Register the current device idempotently using ANDROID_ID.
     * Uses device ID as the key, so repeated app launches don't create duplicates.
     */
    private fun registerCurrentDevice() {
        val db = database ?: return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        val ctx = runCatching { CloudStreamApp.context }.getOrNull() ?: return
        val androidId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        if (androidId.isNullOrEmpty()) {
            Log.w(TAG, "registerCurrentDevice: ANDROID_ID is null or empty")
            return
        }
        deviceId = androidId

        val deviceRef = db.getReference(DEVICES_PATH).child(androidId)
        val deviceData = hashMapOf(
            "uid" to uid,
            "lastSeen" to ServerValue.TIMESTAMP,
            "model" to Build.MODEL,
            "manufacturer" to Build.MANUFACTURER,
            "sdk" to Build.VERSION.SDK_INT
        )

        // Use updateChildren to avoid overwriting registeredAt
        deviceRef.updateChildren(deviceData as Map<String, Any>).addOnSuccessListener {
            // Set registeredAt only if this is a new device
            deviceRef.child("registeredAt").get().addOnSuccessListener { snapshot ->
                if (!snapshot.exists()) {
                    deviceRef.child("registeredAt").setValue(ServerValue.TIMESTAMP)
                }
            }
            Log.d(TAG, "registerCurrentDevice: device registered/updated, id=$androidId")
        }.addOnFailureListener { e ->
            Log.w(TAG, "registerCurrentDevice: failed", e)
        }
    }

    /** Heartbeat to keep presence alive and detect stale entries. */
    private fun startPresenceHeartbeat(presenceRef: com.google.firebase.database.DatabaseReference) {
        // Cancel any previous heartbeat first
        stopPresenceHeartbeat()
        val runnable = object : Runnable {
            override fun run() {
                if (!presenceActive) return
                presenceRef.child("lastSeen").setValue(ServerValue.TIMESTAMP)
                    .addOnFailureListener { e ->
                        Log.w(TAG, "heartbeat: failed to update lastSeen", e)
                    }
                // Schedule next heartbeat in 60 seconds
                heartbeatHandler.postDelayed(this, 60_000L)
            }
        }
        heartbeatRunnable = runnable
        heartbeatHandler.postDelayed(runnable, 60_000L)
    }

    /** Stop and remove any active heartbeat Runnable. */
    private fun stopPresenceHeartbeat() {
        heartbeatRunnable?.let { heartbeatHandler.removeCallbacks(it) }
        heartbeatRunnable = null
    }
}
