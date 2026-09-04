package com.lagradost.cloudstream3

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.pulsestream.app.CloudStreamApp
import com.pulsestream.app.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.BasePlugin
import java.io.File

/**
 * Compatibility shim: external .cs3 plugins expect [CloudStreamApp] at
 * [com.lagradost.cloudstream3.CloudStreamApp] with a [Companion] object.
 *
 * External plugins compiled against the original CloudStream3 access
 * `CloudStreamApp.Companion.getKey(...)` etc. This class provides that
 * exact bytecode shape while delegating to the real app implementation.
 */
open class CloudStreamApp {

    companion object {
        /** Delegate to real app context */
        val context: Context?
            get() = com.pulsestream.app.CloudStreamApp.context

        /** Crash handler - matches original CloudStreamApp API */
        var exceptionHandler: ExceptionHandler? = null

        // DataStore accessors — delegate to real companion
        fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? =
            com.pulsestream.app.CloudStreamApp.getKeyClass(path, valueType)

        fun <T : Any> setKeyClass(path: String, value: T) =
            com.pulsestream.app.CloudStreamApp.setKeyClass(path, value)

        fun removeKeys(folder: String): Int? =
            com.pulsestream.app.CloudStreamApp.removeKeys(folder)

        fun <T> setKey(path: String, value: T) =
            com.pulsestream.app.CloudStreamApp.setKey(path, value)

        fun <T> setKey(folder: String, path: String, value: T) =
            com.pulsestream.app.CloudStreamApp.setKey(folder, path, value)

        inline fun <reified T : Any> getKey(path: String, defVal: T?): T? =
            com.pulsestream.app.CloudStreamApp.getKey(path, defVal)

        inline fun <reified T : Any> getKey(path: String): T? =
            com.pulsestream.app.CloudStreamApp.getKey(path)

        inline fun <reified T : Any> getKey(folder: String, path: String): T? =
            com.pulsestream.app.CloudStreamApp.getKey(folder, path)

        inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T?): T? =
            com.pulsestream.app.CloudStreamApp.getKey(folder, path, defVal)

        fun getKeys(folder: String): List<String>? =
            com.pulsestream.app.CloudStreamApp.getKeys(folder)

        fun removeKey(folder: String, path: String) =
            com.pulsestream.app.CloudStreamApp.removeKey(folder, path)

        fun removeKey(path: String) =
            com.pulsestream.app.CloudStreamApp.removeKey(path)

        // Browser
        fun openBrowser(url: String, fallbackWebView: Boolean = false, fragment: Fragment? = null) =
            com.pulsestream.app.CloudStreamApp.openBrowser(url, fallbackWebView, fragment)

        fun openBrowser(url: String, activity: FragmentActivity?) =
            com.pulsestream.app.CloudStreamApp.openBrowser(url, activity)

        // --- Extension/Plugin access (for external .cs3 plugins) ---
        
        /** Returns all currently loaded BasePlugin instances (external plugins call this) */
        fun getCurrentExtensions(): List<BasePlugin> =
            PluginManager.plugins.values.toList()

        /** Returns all currently loaded plugins keyed by their file path */
        val currentExtensionsMap: Map<String, BasePlugin>
            get() = PluginManager.plugins.toMap()

        /** Returns all currently loaded plugins keyed by their URL */
        val currentExtensionsByUrl: Map<String, BasePlugin>
            get() = PluginManager.urlPlugins.toMap()

        /** Whether local plugins have been loaded */
        val loadedLocalPlugins: Boolean
            get() = PluginManager.loadedLocalPlugins

        /** Whether online plugins have been loaded */
        val loadedOnlinePlugins: Boolean
            get() = PluginManager.loadedOnlinePlugins

        // Extension on Context
        tailrec fun Context.getActivity(): Activity? {
            return when (this) {
                is Activity -> this
                is ContextWrapper -> baseContext.getActivity()
                else -> null
            }
        }
    }
}

/** Exception handler class matching original CloudStreamApp API */
class ExceptionHandler(
    val errorFile: File,
    val onError: (() -> Unit)
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            java.io.PrintStream(errorFile).use { ps ->
                ps.println("Currently loading extension: ${PluginManager.currentlyLoading ?: "none"}")
                ps.println("Fatal exception on thread ${thread.name}")
                error.printStackTrace(ps)
            }
        } catch (_: Exception) {
        }
        try {
            onError()
        } catch (_: Exception) {
        }
        kotlin.system.exitProcess(1)
    }
}
