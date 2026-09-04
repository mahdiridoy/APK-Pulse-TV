package com.pulsestream.app
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.lagradost.api.setContext
import com.pulsestream.app.BuildConfig
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.mvvm.safeAsync
import com.pulsestream.app.plugins.PluginManager
import com.pulsestream.app.ui.settings.Globals.EMULATOR
import com.pulsestream.app.ui.settings.Globals.TV
import com.pulsestream.app.ui.settings.Globals.isLayout
import com.pulsestream.app.utils.AppContextUtils.openBrowser
import com.lagradost.cloudstream3.utils.AppDebug
import com.lagradost.cloudstream3.utils.Coroutines.runOnMainThread
import com.pulsestream.app.utils.DataStore.getKey
import com.pulsestream.app.utils.DataStore.getKeys
import com.pulsestream.app.utils.DataStore.removeKey
import com.pulsestream.app.utils.DataStore.removeKeys
import com.pulsestream.app.utils.DataStore.setKey
import com.pulsestream.app.utils.ImageLoader.buildImageLoader
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.io.PrintStream
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.system.exitProcess

class ExceptionHandler(
    val errorFile: File,
    val onError: (() -> Unit)) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            val threadId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                thread.threadId()
            } else {
                @Suppress("DEPRECATION")
                thread.id
            }
            // Log crash to diagnostics system
            com.pulsestream.app.diagnostics.PulseDiagnostics.error(
                "CRASH", "uncaughtException",
                error,
                "thread=${thread.name}",
                "threadId=$threadId",
                "pluginLoading=${PluginManager.currentlyLoading ?: "none"}"
            )
            PrintStream(errorFile).use { ps ->
                ps.println("Currently loading extension: ${PluginManager.currentlyLoading ?: "none"}")
                ps.println("Fatal exception on thread ${thread.name} ($threadId)")
                error.printStackTrace(ps)
            }
        } catch (_: FileNotFoundException) {
        }
        try {
            onError()
        } catch (_: Exception) {
        }
        exitProcess(1)
    }
}

class CloudStreamApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        // Initialize diagnostics FIRST — everything after this point is tracked
        com.pulsestream.app.diagnostics.PulseDiagnostics.init(this)
        com.pulsestream.app.diagnostics.PulseDiagnostics.logStartup("APP_ON_CREATE")
        // Initialize home page cache for cache-first loading
        com.pulsestream.app.ui.home.HomePageCache.init(filesDir)
        // If we want to initialize Coil as early as possible, maybe when
        // loading an image or GIF in a splash screen activity.
        // buildImageLoader(applicationContext)
        ExceptionHandler(filesDir.resolve("last_error")) {
            val intent = context!!.packageManager.getLaunchIntentForPackage(context!!.packageName)
            startActivity(Intent.makeRestartActivityTask(intent!!.component))
        }.also {
            exceptionHandler = it
            Thread.setDefaultUncaughtExceptionHandler(it)
        }
        AppDebug.isDebug = BuildConfig.DEBUG
        com.pulsestream.app.diagnostics.PulseDiagnostics.logStartup("APP_DEBUG_SET")

        // Cloud sync uses FirestoreRestSyncManager (REST API, no Firestore SDK
        // on the classpath — avoids the protobuf class-verification conflict).
        //
        // Community presence/stats use FirebaseRTDBManager (Realtime Database).
        //
        // On app restart the user may already be signed in from a previous session.
        // We check Firebase Auth here: if a user exists, enable + initialize sync
        // so bookmarks/history/settings flow to the cloud immediately. If not signed
        // in, this is a no-op; sync will be activated later in GoogleLoginActivity.
        // We also add an AuthStateListener to auto-enable sync whenever the user
        // signs in (e.g., after GoogleLoginActivity completes).
        try {
            val fbAuth = runCatching { com.google.firebase.auth.FirebaseAuth.getInstance() }.getOrNull()
            val signedIn = runCatching { fbAuth?.currentUser != null }.getOrDefault(false)
            if (signedIn) {
                com.pulsestream.app.sync.FirestoreRestSyncManager.setEnabled(true)
                com.pulsestream.app.sync.FirebaseRTDBManager.initialize()
            }
            com.pulsestream.app.sync.FirestoreRestSyncManager.initialize()
        } catch (_: Exception) {
        }
        com.pulsestream.app.diagnostics.PulseDiagnostics.logStartup("FIREBASE_INIT_DONE")

        // Add Firebase Auth state listener to auto-enable sync on sign-in.
        // This covers the case where user signs in after app startup (e.g., via
        // GoogleLoginActivity) or when token is refreshed.
        try {
            val fbAuth = com.google.firebase.auth.FirebaseAuth.getInstance()
            fbAuth.addAuthStateListener { auth ->
                val user = auth.currentUser
                if (user != null) {
                    com.pulsestream.app.sync.FirestoreRestSyncManager.setEnabled(true)
                    com.pulsestream.app.sync.FirestoreRestSyncManager.initialize()
                    com.pulsestream.app.sync.FirebaseRTDBManager.initialize()
                    com.pulsestream.app.sync.FirebaseRTDBManager.startPresence()
                } else {
                    // User signed out - disable sync, stop presence, reset state
                    com.pulsestream.app.sync.FirebaseRTDBManager.reset()
                    com.pulsestream.app.sync.FirestoreRestSyncManager.setEnabled(false)
                }
            }
        } catch (_: Exception) {
            // Firebase Auth may not be available on all devices
        }
    }
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        context = base
    }
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        // Coil module will be initialized globally when first loadImage() is invoked.
        return buildImageLoader(applicationContext)
    }
    companion object {
        var exceptionHandler: ExceptionHandler? = null
        /** Use to get Activity from Context. */
        tailrec fun Context.getActivity(): Activity? {
            return when (this) {
                is Activity -> this
                is ContextWrapper -> baseContext.getActivity()
                else -> null
            }
        }
        private var _context: WeakReference<Context>? = null
        var context
            get() = _context?.get()
            private set(value) {
                _context = WeakReference(value)
                setContext(value)
            }
        fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? {
            return context?.getKey(path, valueType)
        }
        fun <T : Any> setKeyClass(path: String, value: T) {
            context?.setKey(path, value)
        }
        fun removeKeys(folder: String): Int? {
            return context?.removeKeys(folder)
        }
        fun <T> setKey(path: String, value: T) {
            context?.setKey(path, value)
        }
        fun <T> setKey(folder: String, path: String, value: T) {
            context?.setKey(folder, path, value)
        }
        inline fun <reified T : Any> getKey(path: String, defVal: T?): T? {
            return context?.getKey(path, defVal)
        }
        inline fun <reified T : Any> getKey(path: String): T? {
            return context?.getKey(path)
        }
        inline fun <reified T : Any> getKey(folder: String, path: String): T? {
            return context?.getKey(folder, path)
        }
        inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T?): T? {
            return context?.getKey(folder, path, defVal)
        }
        fun getKeys(folder: String): List<String>? {
            return context?.getKeys(folder)
        }
        fun removeKey(folder: String, path: String) {
            context?.removeKey(folder, path)
        }
        fun removeKey(path: String) {
            context?.removeKey(path)
        }
        /** If fallbackWebView is true and a fragment is supplied then it will open a WebView with the URL if the browser fails. */
        fun openBrowser(url: String, fallbackWebView: Boolean = false, fragment: Fragment? = null) {
            context?.openBrowser(url, fallbackWebView, fragment)
        }
        /** Will fall back to WebView if in TV or emulator layout. */
        fun openBrowser(url: String, activity: FragmentActivity?) {
            openBrowser(
                url,
                isLayout(TV or EMULATOR),
                activity?.supportFragmentManager?.fragments?.lastOrNull()
            )
        }
    }
}
