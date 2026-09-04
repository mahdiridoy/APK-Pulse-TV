package com.pulsestream.app.ui.settings.extensions

import android.app.Activity
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.fasterxml.jackson.annotation.JsonProperty
import com.pulsestream.app.CloudStreamApp.Companion.getKey
import com.pulsestream.app.CloudStreamApp.Companion.setKey
import com.pulsestream.app.R
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.debugAssert
import com.lagradost.cloudstream3.MainAPI
import com.pulsestream.app.plugins.PluginManager
import com.pulsestream.app.plugins.PluginManager.getPluginsOnline
import com.pulsestream.app.plugins.RepositoryManager
import com.pulsestream.app.plugins.RepositoryManager.PREBUILT_REPOSITORIES
import com.lagradost.cloudstream3.TvType
import com.pulsestream.app.utils.UiText
import com.pulsestream.app.utils.txt
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.mvvm.logError
import com.pulsestream.app.CommonActivity.showToast
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RepositoryData(
    @JsonProperty("iconUrl") @SerialName("iconUrl") val iconUrl: String? = null,
    @JsonProperty("name") @SerialName("name") val name: String = "",
    @JsonProperty("url") @SerialName("url") val url: String = "",
) {
    constructor(name: String, url: String): this(null, name, url)
}

const val REPOSITORIES_KEY = "REPOSITORIES_KEY"
const val DEFAULT_REPOS_ADDED_KEY = "DEFAULT_REPOS_ADDED"

class ExtensionsViewModel : ViewModel() {
    data class PluginStats(
        val total: Int,

        val downloaded: Int,
        val disabled: Int,
        val notDownloaded: Int,

        val downloadedText: UiText,
        val disabledText: UiText,
        val notDownloadedText: UiText,
    )

    private val _repositories = MutableLiveData<Array<RepositoryData>>()
    val repositories: LiveData<Array<RepositoryData>> = _repositories

    private val _pluginStats: MutableLiveData<PluginStats?> = MutableLiveData(null)
    val pluginStats: LiveData<PluginStats?> = _pluginStats

    //TODO CACHE GET REQUESTS
    // DO not use viewModelScope.launchSafe, it will ANR on slow internet
    fun loadStats() = ioSafe {
        val urls = (getKey<Array<RepositoryData>>(REPOSITORIES_KEY)
            ?: emptyArray()) + PREBUILT_REPOSITORIES

        val onlinePlugins = urls.toList().amap {
            RepositoryManager.getRepoPlugins(it)?.toList() ?: emptyList()
        }.flatten().distinctBy { it.plugin.url }

        // Iterates over all offline plugins, compares to remote repo and returns the plugins which are outdated
        val outdatedPlugins = getPluginsOnline().flatMap { savedData ->
            onlinePlugins.filter { onlineData -> savedData.internalName == onlineData.plugin.internalName }
                .map { onlineData ->
                    PluginManager.OnlinePluginData(savedData, onlineData)
                }
        }.distinctBy { it.onlineData.plugin.url }

        val total = onlinePlugins.count()
        val disabled = outdatedPlugins.count { it.isDisabled }
        val downloadedTotal = outdatedPlugins.count()
        val downloaded = downloadedTotal - disabled
        val notDownloaded = total - downloadedTotal
        val stats = PluginStats(
            total,
            downloaded,
            disabled,
            notDownloaded,
            txt(R.string.plugins_downloaded, downloaded),
            txt(R.string.plugins_disabled, disabled),
            txt(R.string.plugins_not_downloaded, notDownloaded)
        )
        debugAssert({ stats.downloaded + stats.notDownloaded + stats.disabled != stats.total }) {
            "downloaded(${stats.downloaded}) + notDownloaded(${stats.notDownloaded}) + disabled(${stats.disabled}) != total(${stats.total})"
        }
        _pluginStats.postValue(stats)
    }

    private fun repos() = (getKey<Array<RepositoryData>>(REPOSITORIES_KEY)
        ?: emptyArray()) + PREBUILT_REPOSITORIES

    fun loadRepositories() {
        val urls = repos()
        _repositories.postValue(urls)
    }

    /**
     * Add default repositories on first launch.
     * This is a suspend function — it completes ONLY after all repos have been
     * parsed and stored.  Callers that need to auto-install plugins after this
     * must call this function *before* autoInstallPluginsFromRepositories().
     */
    suspend fun addDefaultRepositoriesIfNeeded() {
        val defaultReposAdded = getKey<Boolean>(DEFAULT_REPOS_ADDED_KEY) ?: false
        if (defaultReposAdded) return

        try {
            val defaultRepos = arrayOf(
                RepositoryData(
                    "ReCloudstream",
                    "https://raw.githubusercontent.com/recloudstream/extensions/master/repo.json"
                ),
                RepositoryData(
                    "Phisher Extensions",
                    "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json"
                ),
                RepositoryData(
                    "CSX",
                    "https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/CS.json"
                ),
                RepositoryData(
                    "Redowan",
                    "https://raw.githubusercontent.com/redowan99/Redowan-CloudStream/master/repo.json"
                ),
                RepositoryData(
                    "NetMirror",
                    "https://raw.githubusercontent.com/Sushan64/NetMirror-Extension/refs/heads/builds/Netflix.json"
                ),
                RepositoryData(
                    "Cinephile",
                    "https://raw.githubusercontent.com/rockhero1234/cinephile/refs/heads/builds/repo.json"
                ),
                RepositoryData(
                    "Cartoony",
                    "https://raw.githubusercontent.com/med1245/cartoonyrepo/builds/repo.json"
                )
            )

                // Add each repository
            for (repo in defaultRepos) {
                try {
                    val repoObj = RepositoryManager.parseRepository(repo.url) ?: continue
                    val newRepo = RepositoryData(repoObj.iconUrl, repo.name, repo.url)
                    RepositoryManager.addRepository(newRepo)
                } catch (e: Exception) {
                    logError(e)
                }
            }

            setKey<Boolean>(DEFAULT_REPOS_ADDED_KEY, true)

            // Trigger reload
            loadRepositories()
        } catch (e: Exception) {
            logError(e)
        }
    }

    /**
     * Auto-install plugins from all repositories.
     * This is a suspend function — it completes only after every plugin has been
     * downloaded (or skipped).  Must be called AFTER addDefaultRepositoriesIfNeeded()
     * so the repository list is already populated.
     */
    suspend fun autoInstallPluginsFromRepositories(activity: Activity) {
        try {
            val urls = (getKey<Array<RepositoryData>>(REPOSITORIES_KEY)
                ?: emptyArray()) + PREBUILT_REPOSITORIES

            val onlinePlugins = urls.toList().amap {
                RepositoryManager.getRepoPlugins(it)?.toList() ?: emptyList()
            }.flatten().distinctBy { it.plugin.url }

            val newDownloadPlugins = mutableListOf<String>()

            // Download and install all plugins
            for (onlineData in onlinePlugins) {
                val sitePlugin = onlineData.plugin

                if (sitePlugin.url.isBlank() || sitePlugin.repositoryUrl.isNullOrBlank()) {
                    continue
                }

                // Skip if already downloaded
                if (PluginManager.getPluginPath(activity, sitePlugin.internalName, onlineData.repositoryData.url).exists()) {
                    continue
                }

                // Skip NSFW if disabled
                val tvtypesList = sitePlugin.tvTypes ?: listOf()
                if (tvtypesList.contains(TvType.NSFW.name) && !MainAPI.settingsForProvider.enableAdult) {
                    continue
                }

                // Download and install plugin
                val success = PluginManager.downloadPlugin(
                    activity,
                    sitePlugin.url,
                    sitePlugin.fileHash,
                    sitePlugin.internalName,
                    onlineData.repositoryData.url,
                    true // loadPlugin = true
                )

                if (success) {
                    newDownloadPlugins.add(sitePlugin.name)
                }
            }

            if (newDownloadPlugins.isNotEmpty()) {
                main {
                    showToast(activity, "Installed ${newDownloadPlugins.size} plugins")
                }
            }
        } catch (e: Exception) {
            logError(e)
        }
    }
}