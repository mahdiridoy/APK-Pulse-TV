package com.pulsestream.app.ui.settings

import android.os.Bundle
import android.view.View
import com.pulsestream.app.CloudStreamApp.Companion.getKeys
import com.pulsestream.app.R
import com.lagradost.cloudstream3.mvvm.logError
import com.pulsestream.app.sync.FirebaseRTDBManager
import com.pulsestream.app.ui.BasePreferenceFragmentCompat
import com.pulsestream.app.ui.settings.SettingsFragment.Companion.getPref
import com.pulsestream.app.ui.settings.SettingsFragment.Companion.setPaddingBottom
import com.pulsestream.app.ui.settings.SettingsFragment.Companion.setToolBarScrollFlags
import com.pulsestream.app.ui.settings.SettingsFragment.Companion.setUpToolbar
import com.pulsestream.app.utils.DataStoreHelper
import com.pulsestream.app.utils.downloader.VideoDownloadManager

/**
 * Community statistics screen backed by Firebase Realtime Database.
 *
 * Displays:
 * - Live Watching (real-time from RTDB presence nodes)
 * - Total Installed (unique registered devices from RTDB)
 * - Total Downloads (server-side aggregate from RTDB)
 * - Your Favorites / Downloads / History (local device stats)
 *
 * Replaces the previous Cloudflare Worker-based implementation.
 * RTDB is now the authoritative source for all Community realtime data.
 */
class SettingsCommunity : BasePreferenceFragmentCompat() {

    private var listenerKey: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(R.string.community_stats_category)
        setPaddingBottom()
        setToolBarScrollFlags()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_community, rootKey)

        val liveWatchingPref = getPref(R.string.live_watching_label)
        val totalDownloadsPref = getPref(R.string.total_downloads_label)
        val totalInstalledPref = getPref(R.string.total_installed_label)
        val favoritesPref = getPref(R.string.local_favorites_label)
        val downloadsPref = getPref(R.string.local_downloads_label)
        val historyPref = getPref(R.string.local_history_label)

        if (liveWatchingPref != null && totalDownloadsPref != null && totalInstalledPref != null) {
            // Start presence tracking (sets this device as online in RTDB)
            FirebaseRTDBManager.startPresence()

            // Subscribe to real-time stats from Firebase RTDB
            listenerKey = FirebaseRTDBManager.subscribeStats(
                requireContext(),
                object : FirebaseRTDBManager.StatsCallback {
                    override fun onLiveWatchingUpdated(count: Int) {
                        view?.post {
                            liveWatchingPref.summary = "● $count"
                        }
                    }

                    override fun onTotalUsersUpdated(count: Int) {
                        // Total users = Total Installed in UI
                        view?.post {
                            totalInstalledPref.summary = formatNumber(count)
                        }
                    }

                    override fun onTotalInstalledUpdated(count: Int) {
                        // Device count — not directly shown in UI, no-op
                    }

                    override fun onTotalDownloadsUpdated(count: Int) {
                        view?.post {
                            totalDownloadsPref.summary = formatNumber(count)
                        }
                    }
                }
            )

            // Load local stats (favorites, downloads, history) — these are device-local
            loadLocalStats(liveWatchingPref, totalDownloadsPref, totalInstalledPref,
                favoritesPref, downloadsPref, historyPref)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Unsubscribe from RTDB listeners when leaving the screen
        listenerKey?.let {
            FirebaseRTDBManager.unsubscribeStats(it)
            listenerKey = null
        }
    }

    /**
     * Load local device stats that aren't stored in Firebase.
     * These are computed client-side from DataStore/SharedPreferences.
     */
    private fun loadLocalStats(
        liveWatchingPref: androidx.preference.Preference?,
        totalDownloadsPref: androidx.preference.Preference?,
        totalInstalledPref: androidx.preference.Preference?,
        favoritesPref: androidx.preference.Preference?,
        downloadsPref: androidx.preference.Preference?,
        historyPref: androidx.preference.Preference?
    ) {
        // Note: totalDownloadsPref is updated by RTDB listener above.
        // Local downloads count is separate (device-local download queue).
        try {
            val favoritesCount = DataStoreHelper.getAllFavorites().size
            val downloadsCount = getKeys(VideoDownloadManager.KEY_DOWNLOAD_INFO)?.size ?: 0
            val historyCount = try {
                DataStoreHelper.getAllResumeStateIds()?.size ?: 0
            } catch (_: Exception) { 0 }

            view?.post {
                if (favoritesPref != null) favoritesPref.summary = formatNumber(favoritesCount)
                if (downloadsPref != null) downloadsPref.summary = formatNumber(downloadsCount)
                if (historyPref != null) historyPref.summary = formatNumber(historyCount)
            }
        } catch (_: Exception) {}
    }

    private fun formatNumber(n: Int): String = when {
        n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format("%.1fk", n / 1_000.0)
        else -> n.toString()
    }
}
