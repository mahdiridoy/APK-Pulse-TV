package com.pulsestream.app.diagnostics

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.pulsestream.app.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Centralized runtime diagnostics for PulseStream.
 *
 * Provides:
 * - Structured event logging (search, plugins, screens, performance)
 * - Performance measurement with slow-operation detection
 * - Persistent log file with rotation
 * - Crash/non-fatal capture
 * - Duplicate request detection
 * - Request/operation tracing via IDs
 *
 * Usage:
 *   PulseDiagnostics.init(context)
 *   val reqId = PulseDiagnostics.newRequestId()
 *   PulseDiagnostics.event("SEARCH", "query=teach", "provider=X", "durationMs=842", "status=SUCCESS")
 *   PulseDiagnostics.measure("MovieSection", "loadMovies") { ... }
 */
object PulseDiagnostics {

    private const val TAG = "PulseDiag"
    private const val LOG_DIR = "diagnostics"
    private const val LOG_CURRENT = "runtime-current.log"
    private const val LOG_PREVIOUS = "runtime-previous.log"
    private const val MAX_LOG_SIZE_BYTES = 2 * 1024 * 1024L // 2 MB per file

    // Slow operation thresholds (ms) — internal so inline functions can access them
    internal const val THRESHOLD_SLOW = 1_000L
    internal const val THRESHOLD_VERY_SLOW = 3_000L
    internal const val THRESHOLD_CRITICAL = 5_000L

    private var logDir: File? = null
    private var sessionId: String = UUID.randomUUID().toString().take(8)
    private var appStartTime: Long = 0L

    /** In-flight request tracker for duplicate detection. */
    private val inFlightRequests = ConcurrentHashMap<String, Long>()

    /** Request counter for generating unique IDs. */
    private val requestCounter = AtomicLong(0)

    // ---- Screen Performance Tracking ----
    /** Timestamp (elapsedRealtime) when each screen was last created. */
    private val screenCreateTimes = ConcurrentHashMap<String, Long>()

    // ---- Hang Detection ----
    private const val HANG_CHECK_INTERVAL_MS = 3_000L  // check every 3s
    private const val HANG_THRESHOLD_MS = 5_000L       // main thread blocked > 5s = hang
    private var hangHandler: Handler? = null
    private var hangThread: HandlerThread? = null
    @Volatile private var hangDetectorRunning = false
    private val hangHeartbeat = AtomicLong(0L)
    private val hangReported = AtomicBoolean(false)

    // ---- Initialization ----

    fun init(context: Context) {
        appStartTime = SystemClock.elapsedRealtime()
        logDir = File(context.filesDir, LOG_DIR).also { it.mkdirs() }
        rotateLogIfNeeded()
        event("APP", "SESSION_START", "sessionId=$sessionId", "version=${BuildConfig.VERSION_NAME}")
        Log.d(TAG, "Diagnostics initialized, sessionId=$sessionId")
        startHangDetector()
    }

    // ---- Public API ----

    /** Generate a unique request/operation ID. */
    fun newRequestId(): String {
        val id = "REQ-${requestCounter.incrementAndGet().toString(16).uppercase()}"
        return id
    }

    /** Log a structured diagnostic event. */
    fun event(category: String, vararg fields: String) {
        val timestamp = formatTimestamp()
        val thread = Thread.currentThread().name
        val line = "[$timestamp][$category][thread=$thread] ${fields.joinToString(" | ")}"
        Log.d(TAG, line)
        writeToFile(line)
    }

    /** Log a diagnostic event with an explicit tag. */
    fun eventWithTag(tag: String, category: String, vararg fields: String) {
        val timestamp = formatTimestamp()
        val thread = Thread.currentThread().name
        val line = "[$timestamp][$category][tag=$tag][thread=$thread] ${fields.joinToString(" | ")}"
        Log.d(tag, line)
        writeToFile(line)
    }

    /** Measure execution time of a block. Logs duration and slow-operation warnings. */
    fun <T> measure(screen: String, operation: String, requestId: String = "", block: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        val result = block()
        val duration = SystemClock.elapsedRealtime() - start

        val fields = mutableListOf(
            "screen=$screen",
            "operation=$operation",
            "durationMs=$duration",
        )
        if (requestId.isNotEmpty()) fields.add("requestId=$requestId")

        when {
            duration >= THRESHOLD_CRITICAL -> {
                fields.add("status=CRITICAL")
                Log.e(TAG, "SLOW_OPERATION [CRITICAL]: ${fields.joinToString(" | ")}")
            }
            duration >= THRESHOLD_VERY_SLOW -> {
                fields.add("status=VERY_SLOW")
                Log.w(TAG, "SLOW_OPERATION [VERY_SLOW]: ${fields.joinToString(" | ")}")
            }
            duration >= THRESHOLD_SLOW -> {
                fields.add("status=SLOW")
                Log.w(TAG, "SLOW_OPERATION [SLOW]: ${fields.joinToString(" | ")}")
            }
            else -> {
                fields.add("status=OK")
            }
        }

        event("PERFORMANCE", *fields.toTypedArray())
        return result
    }

    /** Start a timed operation. Returns a stop function that logs the duration. */
    fun startTimed(screen: String, operation: String, requestId: String = ""): () -> Unit {
        val start = SystemClock.elapsedRealtime()
        return {
            val duration = SystemClock.elapsedRealtime() - start
            val fields = mutableListOf(
                "screen=$screen",
                "operation=$operation",
                "durationMs=$duration",
            )
            if (requestId.isNotEmpty()) fields.add("requestId=$requestId")
            when {
                duration >= THRESHOLD_CRITICAL -> fields.add("status=CRITICAL")
                duration >= THRESHOLD_VERY_SLOW -> fields.add("status=VERY_SLOW")
                duration >= THRESHOLD_SLOW -> fields.add("status=SLOW")
                else -> fields.add("status=OK")
            }
            event("PERFORMANCE", *fields.toTypedArray())
        }
    }

    /** Track a request for duplicate detection. Returns true if this is a new request, false if duplicate. */
    fun trackRequest(requestId: String, key: String): Boolean {
        val now = System.currentTimeMillis()
        val previous = inFlightRequests.put(key, now)
        if (previous != null && (now - previous) < 500) {
            event("DUPLICATE_REQUEST", "requestId=$requestId", "key=$key", "gapMs=${now - previous}")
            return false
        }
        return true
    }

    /** Remove a request from in-flight tracking. */
    fun completeRequest(key: String) {
        inFlightRequests.remove(key)
    }

    /** Log a screen lifecycle event with performance tracking. */
    fun screenEvent(screen: String, lifecycle: String) {
        val now = SystemClock.elapsedRealtime()
        when (lifecycle) {
            "CREATE" -> {
                screenCreateTimes[screen] = now
                event("SCREEN", "screen=$screen", "lifecycle=$lifecycle")
            }
            "RESUME" -> {
                val createTime = screenCreateTimes.remove(screen)
                if (createTime != null) {
                    val transitionMs = now - createTime
                    val status = when {
                        transitionMs >= THRESHOLD_CRITICAL -> "CRITICAL"
                        transitionMs >= THRESHOLD_VERY_SLOW -> "VERY_SLOW"
                        transitionMs >= THRESHOLD_SLOW -> "SLOW"
                        else -> "OK"
                    }
                    event("SCREEN", "screen=$screen", "lifecycle=$lifecycle",
                        "transitionMs=$transitionMs", "status=$status")
                    if (status != "OK") {
                        Log.w(TAG, "SLOW_SCREEN_TRANSITION: $screen CREATE→RESUME took ${transitionMs}ms [$status]")
                    }
                } else {
                    event("SCREEN", "screen=$screen", "lifecycle=$lifecycle")
                }
            }
            else -> {
                event("SCREEN", "screen=$screen", "lifecycle=$lifecycle")
            }
        }
    }

    /** Log a plugin event. */
    fun pluginEvent(plugin: String, operation: String, vararg extra: String) {
        val fields = mutableListOf("plugin=$plugin", "operation=$operation")
        fields.addAll(extra)
        event("PLUGIN", *fields.toTypedArray())
    }

    /** Log a search event. */
    fun searchEvent(screen: String, query: String, vararg extra: String) {
        val fields = mutableListOf("screen=$screen", "query=$query", "queryLength=${query.length}")
        fields.addAll(extra)
        event("SEARCH", *fields.toTypedArray())
    }

    /** Log a network event. */
    fun networkEvent(provider: String, operation: String, durationMs: Long, status: String) {
        event("NETWORK", "provider=$provider", "operation=$operation", "durationMs=$durationMs", "status=$status")
    }

    /** Log a Firebase event. */
    fun firebaseEvent(service: String, operation: String, vararg extra: String) {
        val fields = mutableListOf("service=$service", "operation=$operation")
        fields.addAll(extra)
        event("FIREBASE", *fields.toTypedArray())
    }

    /** Log a non-fatal error. */
    fun error(tag: String, operation: String, error: Throwable, vararg extra: String) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val fields = mutableListOf(
            "operation=$operation",
            "errorType=${error.javaClass.simpleName}",
            "errorMessage=${error.message?.take(200) ?: "unknown"}",
        )
        fields.addAll(extra)
        eventWithTag(tag, "ERROR", *fields.toTypedArray())
        // Also write full stack trace to file
        writeToFile("[$tag] STACK_TRACE for $operation:\n$sw")
    }

    /** Log app startup timing. */
    fun logStartup(phase: String) {
        val elapsed = SystemClock.elapsedRealtime() - appStartTime
        event("STARTUP", "phase=$phase", "elapsedMs=$elapsed")
    }

    // ---- Hang Detection ----

    /**
     * Starts a lightweight background watchdog that pings the main thread every
     * HANG_CHECK_INTERVAL_MS. If the main thread fails to process the ping
     * within HANG_THRESHOLD_MS, a HANG event is logged.
     *
     * Safe to call multiple times — only one detector runs.
     */
    fun startHangDetector() {
        if (hangDetectorRunning) return
        hangDetectorRunning = true
        hangReported.set(false)

        hangThread = HandlerThread("PulseDiag-Watchdog").apply { start() }
        hangHandler = Handler(hangThread!!.looper)

        // Post initial heartbeat
        hangHeartbeat.set(SystemClock.elapsedRealtime())
        postHeartbeatToMainThread()

        // Schedule periodic checks on the watchdog thread
        hangHandler?.post(object : Runnable {
            override fun run() {
                if (!hangDetectorRunning) return
                val lastHeartbeat = hangHeartbeat.get()
                val now = SystemClock.elapsedRealtime()
                val stallDuration = now - lastHeartbeat

                if (stallDuration >= HANG_THRESHOLD_MS && hangReported.compareAndSet(false, true)) {
                    // Main thread is stuck
                    val msg = "HANG_DETECTED stallMs=$stallDuration thresholdMs=$HANG_THRESHOLD_MS"
                    Log.e(TAG, msg)
                    event("HANG", msg,
                        "mainThreadBlocked=true",
                        "stallMs=$stallDuration")
                    writeToFile("[HANG] Main thread blocked for ${stallDuration}ms — possible ANR risk")
                } else if (stallDuration < HANG_THRESHOLD_MS) {
                    // Main thread is responsive, reset reported flag
                    hangReported.set(false)
                }

                // Schedule next check
                hangHandler?.postDelayed(this, HANG_CHECK_INTERVAL_MS)
            }
        })
        Log.d(TAG, "Hang detector started (interval=${HANG_CHECK_INTERVAL_MS}ms, threshold=${HANG_THRESHOLD_MS}ms)")
    }

    /** Posts a heartbeat ping to the main thread. If the main thread is alive, it sets the timestamp. */
    private fun postHeartbeatToMainThread() {
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            hangHeartbeat.set(SystemClock.elapsedRealtime())
        }
    }

    /** Stops the hang detector. Called on app shutdown or diagnostic disable. */
    fun stopHangDetector() {
        hangDetectorRunning = false
        hangHandler?.removeCallbacksAndMessages(null)
        hangThread?.quitSafely()
        hangThread = null
        hangHandler = null
        Log.d(TAG, "Hang detector stopped")
    }

    // ---- Internal ----

    private fun formatTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    }

    private fun rotateLogIfNeeded() {
        val dir = logDir ?: return
        val current = File(dir, LOG_CURRENT)
        if (current.exists() && current.length() > MAX_LOG_SIZE_BYTES) {
            val previous = File(dir, LOG_PREVIOUS)
            if (previous.exists()) previous.delete()
            current.renameTo(previous)
        }
    }

    private fun writeToFile(line: String) {
        try {
            val dir = logDir ?: return
            val file = File(dir, LOG_CURRENT)
            FileOutputStream(file, true).use { fos ->
                fos.write((line + "\n").toByteArray())
            }
        } catch (_: Exception) {
            // Diagnostics must never crash the app
        }
    }

    /** Get the diagnostics directory for export. */
    fun getLogDirectory(): File? = logDir

    /** Get diagnostic summary. */
    fun getSummary(): String {
        val elapsed = SystemClock.elapsedRealtime() - appStartTime
        return buildString {
            appendLine("=== PulseStream Diagnostic Summary ===")
            appendLine("Session: $sessionId")
            appendLine("Version: ${BuildConfig.VERSION_NAME}")
            appendLine("Uptime: ${elapsed}ms")
            appendLine("In-flight requests: ${inFlightRequests.size}")
            appendLine("Request count: ${requestCounter.get()}")
            appendLine("Log directory: ${logDir?.absolutePath ?: "N/A"}")
        }
    }
}
