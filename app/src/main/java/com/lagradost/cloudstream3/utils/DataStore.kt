package com.lagradost.cloudstream3.utils

import android.content.Context
import com.pulsestream.app.utils.DataStore as RealDataStore

/**
 * Shim class for external .cs3 plugins compiled against the original CloudStream3 API.
 *
 * External plugins (e.g. Ultima) reference com.lagradost.cloudstream3.utils.DataStore
 * but the real implementation was moved to com.pulsestream.app.utils.DataStore.
 * This shim delegates all calls to the real implementation.
 *
 * SafePathClassLoader forces parent-first delegation for com.lagradost.cloudstream3.*
 * classes, so this shim is loaded instead of the plugin's own bundled copy.
 */
object DataStore {

    /**
     * Legacy mapper property — some plugins reference DataStore.mapper.
     * The original CloudStream3 DataStore exposed a Jackson ObjectMapper here.
     */
    @Deprecated(
        "Use AppUtils.parseJson directly.",
        level = DeprecationLevel.WARNING,
    )
    val mapper get() = com.lagradost.cloudstream3.mapper

    // ---- Delegate all methods to the real DataStore ----

    fun Context.getSharedPrefs() = RealDataStore.run { this@getSharedPrefs.getSharedPrefs() }

    fun getFolderName(folder: String, path: String): String =
        RealDataStore.getFolderName(folder, path)

    fun editor(context: Context, isEditingAppSettings: Boolean = false) =
        RealDataStore.editor(context, isEditingAppSettings)

    fun Context.getDefaultSharedPrefs() = RealDataStore.run { this@getDefaultSharedPrefs.getDefaultSharedPrefs() }

    fun Context.getKeys(folder: String) = RealDataStore.run { this@getKeys.getKeys(folder) }

    fun Context.containsKey(folder: String, path: String) =
        RealDataStore.run { this@containsKey.containsKey(folder, path) }

    fun Context.containsKey(path: String) = RealDataStore.run { this@containsKey.containsKey(path) }

    fun Context.removeKey(folder: String, path: String) =
        RealDataStore.run { this@removeKey.removeKey(folder, path) }

    fun Context.removeKey(path: String) =
        RealDataStore.run { this@removeKey.removeKey(path) }

    fun Context.removeKeys(folder: String) = RealDataStore.run { this@removeKeys.removeKeys(folder) }

    fun <T> Context.setKey(path: String, value: T) =
        RealDataStore.run { this@setKey.setKey(path, value) }

    fun <T> Context.setKey(folder: String, path: String, value: T) =
        RealDataStore.run { this@setKey.setKey(folder, path, value) }

    fun <T : Any> Context.getKey(path: String, valueType: Class<T>): T? =
        RealDataStore.run { this@getKey.getKey(path, valueType) }

    inline fun <reified T : Any> Context.getKey(path: String, defVal: T?): T? =
        RealDataStore.run { this@getKey.getKey(path, defVal) }

    inline fun <reified T : Any> Context.getKey(path: String): T? =
        RealDataStore.run { this@getKey.getKey<T>(path) }

    inline fun <reified T : Any> Context.getKey(folder: String, path: String): T? =
        RealDataStore.run { this@getKey.getKey(folder, path) }

    inline fun <reified T : Any> Context.getKey(folder: String, path: String, defVal: T?): T? =
        RealDataStore.run { this@getKey.getKey(folder, path, defVal) }

    @Deprecated(
        "Use parseJson<T>(this) directly instead.",
        level = DeprecationLevel.WARNING,
    )
    inline fun <reified T : Any> String.toKotlinObject(): T =
        com.lagradost.cloudstream3.utils.AppUtils.parseJson(this)

    @Deprecated(
        "Use parseJson<T>(this) directly instead.",
        level = DeprecationLevel.WARNING,
    )
    fun <T : Any> String.toKotlinObject(valueType: Class<T>): T =
        com.lagradost.cloudstream3.utils.AppUtils.parseJson(this, valueType.kotlin)
}
