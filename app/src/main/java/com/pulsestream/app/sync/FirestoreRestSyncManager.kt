package com.pulsestream.app.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.pulsestream.app.CloudStreamApp
import com.pulsestream.app.network.buildDefaultClient
import com.google.firebase.auth.FirebaseAuth as FirebaseAuthLib
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.lagradost.cloudstream3.utils.AppUtils.toJsonLiteral
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * PulseStream cloud sync via the Firestore REST API.
 *
 * History:
 *  - v1.0.0.46-1.0.0.49 included the firebase-firestore SDK. That SDK
 *    bundled protolite-well-known-types:18.0.1 which conflicts with the
 *    version of protobuf-javalite (4.35.0) used elsewhere in the project,
 *    producing a java.lang.VerifyError at startup that manifested as an
 *    infinite splash loop on Android 8/9 devices.
 *  - v1.0.0.50 removed Firestore entirely (this class was deleted along
 *    with the dependency). Sync was disabled.
 *  - This file re-introduces sync WITHOUT the Firestore SDK, talking
 *    directly to the public Firestore REST endpoint using OkHttp (already
 *    a project dependency at 4.12.0). No firebase-firestore, no
 *    protolite-well-known-types, no protobuf conflict.
 *
 * Data model:
 *  - One document per user at users/{uid}.
 *  - A keys field (Firestore mapValue) whose entries are the same
 *    String -> JSON literal key/value pairs we already store locally in
 *    DataStore.
 *  - A lastSync field used as a coarse last-write marker.
 *
 * Threading / safety:
 *  - Every public entry point is fire-and-forget and never blocks the
 *    caller.
 *  - All Firebase / network exceptions are caught and logged; they never
 *    propagate, so the app cannot crash because of cloud sync.
 *  - If FirebaseAuth is unavailable (no Google sign-in, Google Play
 *    Services missing, init failure), the manager becomes a no-op and
 *    local SharedPreferences remain the source of truth.
 */
object FirestoreRestSyncManager {

    private const val TAG = "PulseSync"

    /** Firestore project id - must match google-services.json. */
    private const val PROJECT_ID = "pulsestream-df9a5"

    /** Collection holding per-user documents. */
    private const val USERS_COLLECTION = "users"

    private const val BASE_URL =
        "https://firestore.googleapis.com/v1/projects/" + PROJECT_ID + "/databases/(default)/documents"

    private const val KEYS_FIELD = "keys"
    private const val LAST_SYNC_FIELD = "lastSync"
    private const val SCHEMA_VERSION_FIELD = "schemaVersion"

    /** Current cloud schema version. Bump when document structure changes. */
    private const val SCHEMA_VERSION = 2

    /** Keys that contain device-specific paths and should NOT be synced. */
    private val DEVICE_LOCAL_KEYS = setOf(
        "download_path_key",
        "backup_path_key",
    )

    /** Prefixes of keys that should NOT be synced (download internals, auth tokens). */
    private val NON_SYNC_PREFIXES = listOf(
        "download_episode_cache",
        "download_episode_cache_BACKUP",
        "KEY_DOWNLOAD_INFO",
        "KEY_RESUME_IN_QUEUE",
        "KEY_RESUME_PACKAGES",
        "QUEUE_KEY",
    )

    /** Regex to detect old Kotlin toString() representations like PluginData(...) or [Lcom....] */
    private val LEGACY_TO_STRING_PATTERN = Regex(
        """^\[?[A-Z][a-zA-Z]+\(|^\[Lcom\."""
    )

    /** Coalesce rapid-fire local writes into one PATCH every 2s. */
    private const val DEBOUNCE_MS = 2_000L

    /** HTTP timeouts. */
    private const val HTTP_TIMEOUT_SECONDS = 15L

    /** Retry configuration for transient network failures. */
    private const val MAX_RETRIES = 3
    private const val RETRY_BASE_MS = 1_000L  // 1s, 2s, 4s exponential backoff
    private const val RETRY_MAX_MS = 10_000L

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    // ---- Internal state --------------------------------------------------

    /** Whether cloud sync has been explicitly turned on for this user. */
    @Volatile
    private var enabled: Boolean = false

    /** Whether FirebaseAuth has been confirmed to work. */
    @Volatile
    private var firebaseUsable: Boolean = false

    /** Whether we already pulled the document for the current session. */
    @Volatile
    private var initialized: Boolean = false

    /** The uid we are currently syncing for (changes on logout/relogin). */
    @Volatile
    private var currentUid: String? = null

    /**
     * Local view of the cloud keys map. We track this so a delete on the
     * device can be reflected as a delete on the server (without re-sending
     * every other key on every write).
     */
    private val cloudKeys: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    /** Pending dirty keys waiting for the debounce window to flush. */
    private val pendingKeys: MutableMap<String, String?> = LinkedHashMap()
    private val pendingLock = Any()
    private var debounceRunnable: Runnable? = null

    /** Cache for the OkHttpClient (lazily built on first use). */
    @Volatile
    private var httpClient: OkHttpClient? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- Public API ------------------------------------------------------

    /**
     * Toggle cloud sync. When disabled, the manager becomes a no-op: no
     * pulls, no pushes, no exceptions. Safe to call at any time.
     */
    fun setEnabled(value: Boolean) {
        enabled = value
        Log.d(TAG, "setEnabled(" + value + ")")
        if (!value) {
            // Drop cached state so a future re-enable starts clean.
            synchronized(pendingLock) {
                pendingKeys.clear()
                debounceRunnable?.let { mainHandler.removeCallbacks(it) }
                debounceRunnable = null
            }
            cloudKeys.clear()
            currentUid = null
            initialized = false
        }
    }

    /**
     * Resolve the current Firebase user (if any) and pull the user cloud
     * document so it can be merged into local SharedPreferences.
     *
     * Safe to invoke before FirebaseAuth is available (no-op), when the user
     * is not signed in (no-op), or when network is unavailable (logged).
     *
     * Never throws.
     */
    fun initialize() {
        if (!enabled) {
            Log.d(TAG, "initialize: sync disabled, skipping")
            return
        }
        if (initialized) {
            Log.d(TAG, "initialize: already initialized for " + currentUid)
            return
        }
        thread(name = "PulseSync-init", isDaemon = true) {
            try {
                val auth = runCatching { FirebaseAuthLib.getInstance() }.getOrNull()
                if (auth == null) {
                    Log.w(TAG, "initialize: FirebaseAuth unavailable, sync disabled")
                    firebaseUsable = false
                    return@thread
                }
                val user = runCatching { auth.currentUser }.getOrNull()
                if (user == null) {
                    Log.d(TAG, "initialize: no signed-in user, sync disabled")
                    firebaseUsable = false
                    return@thread
                }
                val token = fetchIdToken(auth) ?: run {
                    Log.w(TAG, "initialize: could not fetch ID token")
                    firebaseUsable = false
                    return@thread
                }
                firebaseUsable = true

                // Account isolation: if UID changed, clear old state
                val previousUid = currentUid
                if (previousUid != null && previousUid != user.uid) {
                    Log.d(TAG, "initialize: account switched from ${previousUid.take(8)}... to ${user.uid.take(8)}..., clearing old state")
                    reset()
                }

                currentUid = user.uid
                Log.d(TAG, "initialize: pulling cloud data for uid=${user.uid.take(8)}...")
                pullFromCloud(user.uid, token)
                initialized = true
                Log.d(TAG, "initialize: pull complete, now pushing local data to cloud")
                // After pulling, push ALL local data up. This ensures that:
                // 1. Data added before sync was ready gets backed up
                // 2. Any keys that were missed by individual syncKey() calls are covered
                // 3. The cloud document is always at least as up-to-date as local storage
                forcePushAll()
            } catch (t: Throwable) {
                Log.w(TAG, "initialize failed", t)
            }
        }
    }

    /**
     * Schedule a write of key to value on the cloud. Coalesces with other
     * pending writes so that a burst of local changes produces a single
     * PATCH after DEBOUNCE_MS. Fire-and-forget - never blocks, never throws.
     *
     * [value] should be a pre-serialized JSON string (from toJsonLiteral()).
     * If a raw object is passed, it will be logged as an error and converted
     * via toJsonLiteral() to avoid storing broken toString() output.
     */
    fun syncKey(key: String, value: Any?) {
        if (!enabled) return
        val literal: String? = when (value) {
            null -> null
            is String -> value
            else -> {
                // Defensive: caller should have serialized via toJsonLiteral().
                // Use toJsonLiteral() instead of toString() to produce valid JSON.
                Log.w(TAG, "syncKey: non-String value for key=$key, using toJsonLiteral()")
                try {
                    value.toJsonLiteral()
                } catch (e: Exception) {
                    Log.e(TAG, "syncKey: toJsonLiteral failed for key=$key", e)
                    value.toString()
                }
            }
        }
        scheduleWrite(key, literal)
    }

    /**
     * Schedule a delete of key in the cloud document. Fire-and-forget.
     */
    fun deleteKey(key: String) {
        if (!enabled) return
        scheduleWrite(key, null)
    }

    /**
     * Push every locally-stored key in folder (e.g. a full bookmark list)
     * to the cloud as a single batched PATCH. Fire-and-forget.
     * Skips device-local and non-syncable keys.
     */
    fun pushFolder(folder: String) {
        if (!enabled) return
        thread(name = "PulseSync-pushFolder", isDaemon = true) {
            try {
                val ctx = context ?: return@thread
                val prefs = ctx.getSharedPreferences(
                    com.pulsestream.app.utils.PREFERENCES_NAME,
                    Context.MODE_PRIVATE
                )
                val prefix = if (folder.endsWith("/")) folder else folder + "/"
                val snapshot = prefs.all
                synchronized(pendingLock) {
                    for ((rawKey, rawValue) in snapshot) {
                        if (!rawKey.startsWith(prefix)) continue
                        // Only sync string-stored entries - DataStore stores
                        // everything as a string via toJsonLiteral().
                        val literal = (rawValue as? String) ?: continue
                        // Skip device-local and non-syncable keys
                        if (rawKey in DEVICE_LOCAL_KEYS || NON_SYNC_PREFIXES.any { rawKey.startsWith(it) }) continue
                        pendingKeys[rawKey] = literal
                    }
                    scheduleFlushLocked()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "pushFolder(" + folder + ") failed", t)
            }
        }
    }

    /**
     * Push every locally-stored SharedPreferences entry to the cloud in a
     * single PATCH. Fire-and-forget. Intended for "Sign in on a new device"
     * scenarios where the device is authoritative.
     *
     * Skips device-local keys (paths, download internals, auth tokens).
     */
    fun forcePushAll() {
        if (!enabled) return
        thread(name = "PulseSync-forcePushAll", isDaemon = true) {
            try {
                val ctx = context ?: return@thread
                val prefs = ctx.getSharedPreferences(
                    com.pulsestream.app.utils.PREFERENCES_NAME,
                    Context.MODE_PRIVATE
                )
                var skipped = 0
                synchronized(pendingLock) {
                    for ((rawKey, rawValue) in prefs.all) {
                        val literal = (rawValue as? String) ?: continue
                        // Skip device-local and non-syncable keys
                        if (rawKey in DEVICE_LOCAL_KEYS || NON_SYNC_PREFIXES.any { rawKey.startsWith(it) }) {
                            skipped++
                            continue
                        }
                        pendingKeys[rawKey] = literal
                    }
                    scheduleFlushLocked()
                }
                if (skipped > 0) {
                    Log.d(TAG, "forcePushAll: skipped $skipped device-local keys")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "forcePushAll failed", t)
            }
        }
    }

    /**
     * Drop all cached cloud state. Useful when the user signs out so a
     * later sign-in for a different account starts fresh. Fire-and-forget.
     */
    fun reset() {
        synchronized(pendingLock) {
            pendingKeys.clear()
            debounceRunnable?.let { mainHandler.removeCallbacks(it) }
            debounceRunnable = null
        }
        cloudKeys.clear()
        currentUid = null
        initialized = false
        Log.d(TAG, "reset: all sync state cleared")
    }

    // ---- Internals -------------------------------------------------------

    private val context: Context?
        get() = runCatching { CloudStreamApp.context }.getOrNull()

    /**
     * Fetch a Firebase ID token for the current user. Uses getIdToken(true)
     * to force a refresh so we recover from server-side revocations across
     * app restarts.
     */
    private fun fetchIdToken(auth: FirebaseAuthLib): String? {
        val user = runCatching { auth.currentUser }.getOrNull() ?: return null
        return try {
            val task = user.getIdToken(true)
            // The task is asynchronous; we block this worker thread only.
            // Using kotlinx.coroutines.runBlocking + suspendCancellableCoroutine
            // keeps everything exception-safe (any failure -> null).
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
                    task.addOnCompleteListener { t ->
                        if (t.isSuccessful) {
                            cont.resumeWith(Result.success(t.result?.token))
                        } else {
                            Log.w(TAG, "getIdToken failed", t.exception)
                            cont.resumeWith(Result.success(null))
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "fetchIdToken failed", t)
            null
        }
    }

    private fun getClient(): OkHttpClient {
        val existing = httpClient
        if (existing != null) return existing
        val ctx = context
        val built = if (ctx != null) {
            runCatching { buildDefaultClient(ctx) }.getOrElse {
                OkHttpClient.Builder()
                    .connectTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .writeTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build()
            }
        } else {
            OkHttpClient.Builder()
                .connectTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        }
        httpClient = built
        return built
    }

    private fun scheduleWrite(key: String, value: String?) {
        if (!firebaseUsable) return
        synchronized(pendingLock) {
            pendingKeys[key] = value
            scheduleFlushLocked()
        }
    }

    /** Caller must hold pendingLock. */
    private fun scheduleFlushLocked() {
        if (!firebaseUsable || currentUid == null) return
        debounceRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            synchronized(pendingLock) {
                if (pendingKeys.isEmpty()) return@Runnable
                val snapshot = HashMap(pendingKeys)
                pendingKeys.clear()
                debounceRunnable = null
                flush(snapshot)
            }
        }
        debounceRunnable = runnable
        mainHandler.postDelayed(runnable, DEBOUNCE_MS)
    }

    private fun flush(snapshot: Map<String, String?>) {
        thread(name = "PulseSync-flush", isDaemon = true) {
            try {
                val auth = runCatching { FirebaseAuthLib.getInstance() }.getOrNull()
                    ?: return@thread
                val uid = currentUid ?: run {
                    val u = runCatching { auth.currentUser }.getOrNull()
                    if (u == null) return@thread
                    currentUid = u.uid
                    u.uid
                }
                val token = fetchIdToken(auth) ?: return@thread
                pushPatch(uid, token, snapshot)
            } catch (t: Throwable) {
                Log.w(TAG, "flush failed", t)
            }
        }
    }

    private fun pushPatch(uid: String, token: String, changes: Map<String, String?>) {
        // Build Firestore document body. We merge changes on top of the
        // locally cached cloudKeys so a delete on device still results
        // in the key being absent from the cloud map after the PATCH.
        val merged = HashMap(cloudKeys)
        for ((k, v) in changes) {
            if (v == null) merged.remove(k) else merged[k] = v
        }

        val docUrl = BASE_URL + "/" + USERS_COLLECTION + "/" + uid
        val updateMask = "updateMask.fieldPaths=" + KEYS_FIELD +
                "&updateMask.fieldPaths=" + LAST_SYNC_FIELD +
                "&updateMask.fieldPaths=" + SCHEMA_VERSION_FIELD
        val patchUrl = docUrl + "?" + updateMask

        val bodyJson = buildDocumentBody(merged)
        val debugKeys = merged.keys.take(3).map { encodeKey(it) }
        Log.d(TAG, "pushPatch: PATCHing ${merged.size} keys, sample encoded keys: $debugKeys")

        val requestBuilder = Request.Builder()
            .url(patchUrl)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .patch(bodyJson.toString().toRequestBody(JSON_MEDIA))

        var lastException: Throwable? = null
        for (attempt in 1..MAX_RETRIES) {
            try {
                val request = requestBuilder.build()
                getClient().newCall(request).execute().use { resp ->
                    when {
                        resp.isSuccessful -> {
                            Log.d(TAG, "pushPatch: SUCCESS (${resp.code}), ${merged.size} keys synced (attempt $attempt)")
                            cloudKeys.clear()
                            cloudKeys.putAll(merged)
                            return  // success — done
                        }
                        // Retryable: rate limit (429) or server error (5xx)
                        resp.code == 429 || resp.code in 500..599 -> {
                            val errorBody = resp.body.string().take(500)
                            val retryAfter = resp.header("Retry-After")?.toLongOrNull()
                            val backoffMs = if (retryAfter != null && retryAfter > 0) {
                                retryAfter * 1000
                            } else {
                                minOf(RETRY_BASE_MS * (1L shl (attempt - 1)), RETRY_MAX_MS)
                            }
                            Log.w(TAG, "pushPatch: retryable error ${resp.code} (attempt $attempt/$MAX_RETRIES), " +
                                "retrying in ${backoffMs}ms. Body: ${errorBody.take(200)}")
                            if (attempt < MAX_RETRIES) {
                                Thread.sleep(backoffMs)
                            }
                        }
                        // Non-retryable: auth (401), permission (403), bad request (400), other
                        else -> {
                            val errorBody = resp.body.string().take(1000)
                            Log.e(TAG, "pushPatch: FAILED ${resp.code} ${resp.message} (non-retryable)")
                            Log.e(TAG, "pushPatch: error body: $errorBody")
                            return  // don't retry client errors
                        }
                    }
                }
            } catch (e: java.io.IOException) {
                // Network error — retryable
                lastException = e
                val backoffMs = minOf(RETRY_BASE_MS * (1L shl (attempt - 1)), RETRY_MAX_MS)
                Log.w(TAG, "pushPatch: network error (attempt $attempt/$MAX_RETRIES): ${e.message}, " +
                    "retrying in ${backoffMs}ms")
                if (attempt < MAX_RETRIES) {
                    Thread.sleep(backoffMs)
                }
            } catch (t: Throwable) {
                // Unexpected error — don't retry
                Log.e(TAG, "pushPatch: unexpected error (non-retryable)", t)
                return
            }
        }
        // All retries exhausted
        Log.e(TAG, "pushPatch: FAILED after $MAX_RETRIES attempts. ${merged.size} keys NOT synced." +
            (lastException?.let { " Last error: ${it.message}" } ?: ""))
        // Do NOT update cloudKeys on failure — keep old state so next retry sends complete set
    }

    private fun pullFromCloud(uid: String, token: String) {
        val url = BASE_URL + "/" + USERS_COLLECTION + "/" + uid
        Log.d(TAG, "pullFromCloud: GET $url")

        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .get()

        var lastException: Throwable? = null
        for (attempt in 1..MAX_RETRIES) {
            try {
                val request = requestBuilder.build()
                getClient().newCall(request).execute().use { resp ->
                    when {
                        resp.code == 404 -> {
                            // No cloud doc yet — fine, future writes will create it
                            Log.d(TAG, "pullFromCloud: no remote document yet")
                            return
                        }
                        resp.isSuccessful -> {
                            val body = resp.body.string()
                            Log.d(TAG, "pullFromCloud: SUCCESS (${resp.code}), ${body.length} bytes (attempt $attempt)")
                            applyPulledDocument(uid, body)
                            return  // success — done
                        }
                        // Retryable: rate limit (429) or server error (5xx)
                        resp.code == 429 || resp.code in 500..599 -> {
                            val errorBody = resp.body.string().take(500)
                            val retryAfter = resp.header("Retry-After")?.toLongOrNull()
                            val backoffMs = if (retryAfter != null && retryAfter > 0) {
                                retryAfter * 1000
                            } else {
                                minOf(RETRY_BASE_MS * (1L shl (attempt - 1)), RETRY_MAX_MS)
                            }
                            Log.w(TAG, "pullFromCloud: retryable error ${resp.code} (attempt $attempt/$MAX_RETRIES), " +
                                "retrying in ${backoffMs}ms. Body: ${errorBody.take(200)}")
                            if (attempt < MAX_RETRIES) {
                                Thread.sleep(backoffMs)
                            }
                        }
                        // Non-retryable: auth (401), permission (403), bad request (400), other
                        else -> {
                            val errorBody = resp.body.string().take(500)
                            Log.e(TAG, "pullFromCloud: FAILED ${resp.code} ${resp.message} (non-retryable) — $errorBody")
                            return  // don't retry client errors
                        }
                    }
                }
            } catch (e: java.io.IOException) {
                // Network error — retryable
                lastException = e
                val backoffMs = minOf(RETRY_BASE_MS * (1L shl (attempt - 1)), RETRY_MAX_MS)
                Log.w(TAG, "pullFromCloud: network error (attempt $attempt/$MAX_RETRIES): ${e.message}, " +
                    "retrying in ${backoffMs}ms")
                if (attempt < MAX_RETRIES) {
                    Thread.sleep(backoffMs)
                }
            } catch (t: Throwable) {
                // Unexpected error — don't retry
                Log.e(TAG, "pullFromCloud: unexpected error (non-retryable)", t)
                return
            }
        }
        // All retries exhausted
        Log.e(TAG, "pullFromCloud: FAILED after $MAX_RETRIES attempts." +
            (lastException?.let { " Last error: ${it.message}" } ?: ""))
    }

    /**
     * Merge a fetched Firestore document into local SharedPreferences.
     *
     * Improvements over v1:
     *  - Schema versioning: reads/writes schemaVersion for future migrations
     *  - Per-section error handling: one bad key does NOT kill the entire restore
     *  - Legacy data migration: detects old toString() representations and skips them
     *  - Device-specific path filtering: strips keys with local filesystem paths
     *  - Diagnostic logging: every step is logged for observability
     *
     * Merge policy:
     *  - On fresh install (no local keys): cloud wins for ALL keys
     *  - On existing install: cloud only fills in missing keys (local wins for existing)
     *  - Device-local keys are always skipped
     *  - Legacy toString() values are logged and skipped
     */
    private fun applyPulledDocument(uid: String, body: String) {
        val startTime = System.currentTimeMillis()
        var totalKeys = 0
        var appliedKeys = 0
        var skippedLegacy = 0
        var skippedDeviceLocal = 0
        var skippedExisting = 0
        var failedKeys = 0

        try {
            val root = JSONObject(body)
            val fields = root.optJSONObject("fields") ?: return

            // Read schema version
            val schemaVersion = fields.optJSONObject(SCHEMA_VERSION_FIELD)
                ?.optInt("integerValue", 1) ?: 1
            Log.d(TAG, "applyPulledDocument: cloud schemaVersion=$schemaVersion")

            val keysObj = fields.optJSONObject(KEYS_FIELD) ?: return
            val mapObj = keysObj.optJSONObject("fields") ?: keysObj
            val remoteKeys = HashMap<String, String>()
            collectStringMap(mapObj, remoteKeys)
            if (remoteKeys.isEmpty()) {
                Log.d(TAG, "applyPulledDocument: no remote keys to apply")
                return
            }
            totalKeys = remoteKeys.size

            val ctx = context ?: return
            val prefs = ctx.getSharedPreferences(
                com.pulsestream.app.utils.PREFERENCES_NAME,
                Context.MODE_PRIVATE
            )

            // Determine if this is a fresh install (no user-specific keys locally)
            val isFreshInstall = prefs.all.none { (_, v) -> v is String && v.isNotEmpty() }
            Log.d(TAG, "applyPulledDocument: freshInstall=$isFreshInstall, remoteKeys=$totalKeys")

            val editor = prefs.edit()
            for ((k, v) in remoteKeys) {
                try {
                    // Skip device-local keys
                    if (k in DEVICE_LOCAL_KEYS || NON_SYNC_PREFIXES.any { k.startsWith(it) }) {
                        skippedDeviceLocal++
                        continue
                    }

                    // Skip legacy toString() representations (not valid JSON)
                    if (isLegacyToString(v)) {
                        skippedLegacy++
                        Log.d(TAG, "applyPulledDocument: skipped legacy key=$k (format=${v.take(50)}...)")
                        continue
                    }

                    if (isFreshInstall) {
                        // Fresh install: cloud wins for ALL keys
                        editor.putString(k, v)
                        appliedKeys++
                    } else {
                        // Existing install: cloud only fills in missing keys
                        if (!prefs.contains(k)) {
                            editor.putString(k, v)
                            appliedKeys++
                        } else {
                            skippedExisting++
                        }
                    }
                } catch (e: Exception) {
                    failedKeys++
                    Log.w(TAG, "applyPulledDocument: failed to process key=$k: ${e.message}")
                }
            }

            if (appliedKeys > 0) {
                editor.apply()
            }

            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "applyPulledDocument: COMPLETE uid=${uid.take(8)}..." +
                " schemaVersion=$schemaVersion" +
                " total=$totalKeys" +
                " applied=$appliedKeys" +
                " skippedExisting=$skippedExisting" +
                " skippedLegacy=$skippedLegacy" +
                " skippedDeviceLocal=$skippedDeviceLocal" +
                " failed=$failedKeys" +
                " durationMs=$duration")

            cloudKeys.clear()
            cloudKeys.putAll(remoteKeys)
        } catch (t: Throwable) {
            Log.w(TAG, "applyPulledDocument failed", t)
        }
    }

    /**
     * Detect if a value looks like a Kotlin/Java toString() representation
     * rather than valid JSON. Examples:
     *  - "[PluginData(internalName=Anikage, ...)]"
     *  - "[Lcom.pulsestream.app.utils.downloader.DownloadObjects$DownloadQueueWrapper;@38ee567"
     *  - "SearchHistoryItem(searchedAt=..., searchText=..., type=[], key=...)"
     */
    private fun isLegacyToString(value: String): Boolean {
        if (value.isBlank()) return false
        // Quick check: if it starts with { or [ followed by " it's likely JSON
        val trimmed = value.trim()
        if (trimmed.startsWith("{") && (trimmed.contains("\"") || trimmed.contains(":"))) return false
        if (trimmed.startsWith("[") && trimmed.contains("{")) return false
        // Check for known legacy patterns
        return LEGACY_TO_STRING_PATTERN.containsMatchIn(trimmed)
    }

    /**
     * Walk a Firestore mapValue.fields object whose entries are each
     * shaped like {"stringValue": "..."}. Flatten into a String->String
     * map. PulseStream local store is a flat string->string KV, so we
     * intentionally skip nested mapValues.
     */
    private fun collectStringMap(
        obj: JSONObject?,
        out: MutableMap<String, String>
    ) {
        if (obj == null) return
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val entry = obj.optJSONObject(k) ?: continue
            val sv = entry.opt("stringValue")
            if (sv != null && sv != JSONObject.NULL) {
                out[decodeKey(k)] = sv.toString()
            }
        }
    }

    /**
     * Encode key for Firestore REST API map fields.
     *
     * Firestore REST API rejects map keys containing: . * [ ] /
     * These characters cause "Property keys contains an invalid nested entity"
     * because Firestore interprets them as path separators or special syntax.
     *
     * We encode all problematic characters to safe _TOKEN_ form.
     * Order matters: encode _ first to avoid double-encoding.
     */
    private fun encodeKey(raw: String): String {
        var result = raw
        // Must encode _ first so our tokens don't get double-encoded
        result = result.replace("_", "__UNDERSCORE__")
        result = result.replace("/", "__SLASH__")
        result = result.replace(".", "__DOT__")
        result = result.replace("*", "__STAR__")
        result = result.replace("[", "__LBRACK__")
        result = result.replace("]", "__RBRACK__")
        // Firestore rejects keys starting with __
        if (result.startsWith("__")) {
            result = "__PREFIX__" + result
        }
        return result
    }

    /** Decode key from Firestore map field back to original. */
    private fun decodeKey(encoded: String): String {
        var result = encoded
        if (result.startsWith("__PREFIX__")) {
            result = result.removePrefix("__PREFIX__")
        }
        result = result.replace("__RBRACK__", "]")
        result = result.replace("__LBRACK__", "[")
        result = result.replace("__STAR__", "*")
        result = result.replace("__DOT__", ".")
        result = result.replace("__SLASH__", "/")
        result = result.replace("__UNDERSCORE__", "_")
        return result
    }

    private fun buildDocumentBody(keys: Map<String, String>): JSONObject {
        // Build: {"fields": {"keys": {"mapValue": {"fields": {k1: {"stringValue": v1}, ...}}}, "lastSync": {"stringValue": "..."}, "schemaVersion": {"integerValue": 2}}}
        val keysFields = JSONObject()
        for ((k, v) in keys) {
            keysFields.put(encodeKey(k), JSONObject().put("stringValue", v))
        }
        val mapValue = JSONObject().put("fields", keysFields)
        val keysField = JSONObject().put("mapValue", mapValue)
        val lastSyncField = JSONObject().put(
            "stringValue",
            System.currentTimeMillis().toString()
        )
        val schemaVersionField = JSONObject().put("integerValue", SCHEMA_VERSION)
        val fields = JSONObject()
            .put(KEYS_FIELD, keysField)
            .put(LAST_SYNC_FIELD, lastSyncField)
            .put(SCHEMA_VERSION_FIELD, schemaVersionField)
        return JSONObject().put("fields", fields)
    }

    @Suppress("unused") // keep JSONArray import alive if someone trims the file
    private fun jsonArrayGuard() {
        JSONArray().put(0)
    }
}
