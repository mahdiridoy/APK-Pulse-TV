package com.lagradost.cloudstream3.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainActivity.Companion.deleteFileOnExit
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.services.PackageInstallerService
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.GitInfo.currentCommitHash
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okio.BufferedSink
import okio.buffer
import okio.sink
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

object InAppUpdater {
    private const val LOG_TAG = "InAppUpdater"

    @Serializable
    private data class GithubAsset(
        @JsonProperty("name") @SerialName("name") val name: String,
        @JsonProperty("size") @SerialName("size") val size: Int, // Size in bytes
        @JsonProperty("browser_download_url") @SerialName("browser_download_url") val browserDownloadUrl: String,
        @JsonProperty("content_type") @SerialName("content_type") val contentType: String, // application/vnd.android.package-archive
    )

    @Serializable
    private data class GithubRelease(
        @JsonProperty("tag_name") @SerialName("tag_name") val tagName: String, // Version code
        @JsonProperty("body") @SerialName("body") val body: String, // Description
        @JsonProperty("assets") @SerialName("assets") val assets: List<GithubAsset>,
        @JsonProperty("target_commitish") @SerialName("target_commitish") val targetCommitish: String, // Branch
        @JsonProperty("prerelease") @SerialName("prerelease") val prerelease: Boolean,
        @JsonProperty("node_id") @SerialName("node_id") val nodeId: String,
    )

    @Serializable
    private data class GithubObject(
        @JsonProperty("sha") @SerialName("sha") val sha: String, // SHA-256 hash
        @JsonProperty("type") @SerialName("type") val type: String,
        @JsonProperty("url") @SerialName("url") val url: String,
    )

    @Serializable
    private data class GithubTag(
        @JsonProperty("object") @SerialName("object") val githubObject: GithubObject,
    )

    @Serializable
    private data class Update(
        @JsonProperty("shouldUpdate") @SerialName("shouldUpdate") val shouldUpdate: Boolean,
        @JsonProperty("updateURL") @SerialName("updateURL") val updateURL: String?,
        @JsonProperty("updateVersion") @SerialName("updateVersion") val updateVersion: String?,
        @JsonProperty("changelog") @SerialName("changelog") val changelog: String?,
        @JsonProperty("updateNodeId") @SerialName("updateNodeId") val updateNodeId: String?,
    )

    private const val UPDATE_CHECK_URL = "https://watch-together-server.pulsestream.workers.dev/update/check"

    private suspend fun Activity.getAppUpdate(installPrerelease: Boolean): Update {
        val currentVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0.0"
        } catch (_: NameNotFoundException) {
            "0.0.0.0"
        }

        return try {
            val url = "$UPDATE_CHECK_URL?v=${java.net.URLEncoder.encode(currentVersion, "UTF-8")}"
            val response = app.get(url)
            
            // Check if response is successful
            if (response.code == 200) {
                val json = org.json.JSONObject(response.text)
                val shouldUpdate = json.optBoolean("updateAvailable", false)
                val version = json.optString("version", null)
                val downloadUrl = json.optString("downloadUrl", null)
                val changelog = json.optString("changelog", null)

                if (shouldUpdate && !downloadUrl.isNullOrBlank()) {
                    Log.d(LOG_TAG, "Update available: $version")
                    Update(
                        shouldUpdate = true,
                        updateURL = downloadUrl,
                        updateVersion = version,
                        changelog = changelog,
                        updateNodeId = version,
                    )
                } else {
                    Log.d(LOG_TAG, "App is up to date: $currentVersion")
                    Update(false, null, null, null, null)
                }
            } else {
                Log.w(LOG_TAG, "Update endpoint returned ${response.code}")
                Update(false, null, null, null, null)
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Update check failed: ${e.message}")
            Update(false, null, null, null, null)
        }
    }

    private val updateLock = Mutex()

    private suspend fun Activity.downloadUpdate(url: String): Boolean {
        try {
            Log.d(LOG_TAG, "Downloading update: $url")
            val appUpdateName = "PulseStream"
            val appUpdateSuffix = "apk"

            // Delete all old updates
            this.cacheDir.listFiles()?.filter {
                it.name.startsWith(appUpdateName) && it.extension == appUpdateSuffix
            }?.forEach { deleteFileOnExit(it) }

            val downloadedFile = File.createTempFile(appUpdateName, ".$appUpdateSuffix")
            val sink: BufferedSink = downloadedFile.sink().buffer()

            updateLock.withLock {
                sink.writeAll(app.get(url).body.source())
                sink.close()
                openApk(this, Uri.fromFile(downloadedFile))
            }

            return true
        } catch (e: Exception) {
            logError(e)
            return false
        }
    }

    private fun openApk(context: Context, uri: Uri) = safe {
        val path = uri.path ?: return@safe
        val contentUri = FileProvider.getUriForFile(
            context, BuildConfig.APPLICATION_ID + ".provider", File(path)
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            data = contentUri
        }
        context.startActivity(installIntent)
    }

    /**
     * @param checkAutoUpdate if the update check was launched automatically
     * @param installPrerelease if we want to install the pre-release version
     */
    suspend fun Activity.runAutoUpdate(
        checkAutoUpdate: Boolean = true, installPrerelease: Boolean = false
    ): Boolean {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        val autoUpdateEnabled =
            settingsManager.getBoolean(getString(R.string.auto_update_key), true)
        if (checkAutoUpdate && !autoUpdateEnabled) {
            return false
        }

        val update = getAppUpdate(installPrerelease)
        if (!update.shouldUpdate || update.updateURL == null) {
            return false
        }

        // Check if update should be skipped
        val updateNodeId = settingsManager.getString(
            getString(R.string.skip_update_key), ""
        )

        // Skips the update if its an automatic update and the update is skipped
        // This allows updating manually
        if (update.updateNodeId.equals(updateNodeId) && checkAutoUpdate) {
            return false
        }

        runOnUiThread {
            safe {
                val currentVersion = packageName?.let {
                    packageManager.getPackageInfo(it, 0)
                }

                val builder = AlertDialog.Builder(this, R.style.AlertDialogCustom)
                builder.setTitle(
                    getString(R.string.new_update_format).format(
                        currentVersion?.versionName, update.updateVersion
                    )
                )

                val logRegex = Regex("\\[(.*?)]\\((.*?)\\)")
                val sanitizedChangelog = update.changelog?.replace(logRegex) { matchResult ->
                    matchResult.groupValues[1]
                } // Sanitized because it looks cluttered

                builder.setMessage(sanitizedChangelog)
                builder.apply {
                    setPositiveButton(R.string.update) { _, _ ->
                        // Forcefully start any delayed installations
                        if (ApkInstaller.delayedInstaller?.startInstallation() == true) return@setPositiveButton

                        showToast(R.string.download_started, Toast.LENGTH_LONG)

                        // Check if the setting hasn't been changed
                        if (settingsManager.getInt(
                                getString(R.string.apk_installer_key), -1
                            ) == -1
                        ) {
                            // Set to legacy installer if using MIUI
                            if (isMiUi()) {
                                settingsManager.edit {
                                    putInt(getString(R.string.apk_installer_key), 1)
                                }
                            }
                        }

                        val currentInstaller = settingsManager.getInt(
                            getString(R.string.apk_installer_key), 0
                        )

                        when (currentInstaller) {
                            // New method
                            0 -> {
                                val intent = PackageInstallerService.Companion.getIntent(
                                    this@runAutoUpdate, update.updateURL
                                )
                                ContextCompat.startForegroundService(
                                    this@runAutoUpdate, intent
                                )
                            }
                            // Legacy
                            1 -> {
                                ioSafe {
                                    if (!downloadUpdate(update.updateURL)) {
                                        runOnUiThread {
                                            showToast(
                                                R.string.download_failed, Toast.LENGTH_LONG
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    setNegativeButton(R.string.cancel) { _, _ -> }

                    if (checkAutoUpdate) {
                        setNeutralButton(R.string.skip_update) { _, _ ->
                            settingsManager.edit {
                                putString(
                                    getString(R.string.skip_update_key), update.updateNodeId ?: ""
                                )
                            }
                        }
                    }
                }
                builder.show().setDefaultFocus()
            }
        }
        return true
    }

    private fun isMiUi(): Boolean = !getSystemProperty("ro.miui.ui.version.name").isNullOrEmpty()

    private fun getSystemProperty(propName: String): String? = try {
        val p = Runtime.getRuntime().exec("getprop $propName")
        BufferedReader(InputStreamReader(p.inputStream), 1024).use {
            it.readLine()
        }
    } catch (_: IOException) {
        null
    }
}
