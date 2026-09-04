package com.pulsestream.app.ui.settings

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.lagradost.cloudstream3.AutoDownloadMode
import com.pulsestream.app.BuildConfig
import com.pulsestream.app.CloudStreamApp
import com.pulsestream.app.CommonActivity.showToast
import com.pulsestream.app.R
import com.lagradost.cloudstream3.app
import com.pulsestream.app.databinding.LogcatBinding
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.pulsestream.app.network.initClient
import com.lagradost.cloudstream3.utils.Coroutines.runOnMainThread
import com.pulsestream.app.plugins.PluginManager
import com.pulsestream.app.services.BackupWorkManager
import com.pulsestream.app.ui.BasePreferenceFragmentCompat
import com.pulsestream.app.ui.settings.Globals.EMULATOR
import com.pulsestream.app.ui.settings.SettingsFragment.Companion.getPref
import com.pulsestream.app.ui.settings.SettingsFragment.Companion.hideOn
import com.pulsestream.app.ui.settings.SettingsFragment.Companion.setPaddingBottom
import com.pulsestream.app.ui.settings.SettingsFragment.Companion.setToolBarScrollFlags
import com.pulsestream.app.ui.settings.SettingsFragment.Companion.setUpToolbar
import com.pulsestream.app.ui.settings.utils.getChooseFolderLauncher
import com.pulsestream.app.utils.BackupUtils
import com.pulsestream.app.utils.BackupUtils.restorePrompt
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe

import com.pulsestream.app.utils.InAppUpdater.runAutoUpdate
import com.pulsestream.app.utils.SingleSelectionHelper.showBottomDialog
import com.pulsestream.app.utils.SingleSelectionHelper.showDialog
import com.pulsestream.app.utils.UIHelper.clipboardHelper
import com.pulsestream.app.utils.UIHelper.dismissSafe
import com.pulsestream.app.utils.UIHelper.hideKeyboard
import com.pulsestream.app.utils.downloader.VideoDownloadManager
import com.pulsestream.app.utils.txt
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.lang.System.currentTimeMillis
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsUpdates : BasePreferenceFragmentCompat() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(R.string.category_updates)
        setPaddingBottom()
        setToolBarScrollFlags()
    }

    private val pathPicker = getChooseFolderLauncher { uri, path ->
        if(uri == null) return@getChooseFolderLauncher

        val context = context ?: CloudStreamApp.context ?: return@getChooseFolderLauncher
        (path ?: uri.toString()).let {
            PreferenceManager.getDefaultSharedPreferences(context).edit {
                putString(getString(R.string.backup_path_key), uri.toString())
                putString(getString(R.string.backup_dir_key), it)
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        hideKeyboard()
        setPreferencesFromResource(R.xml.settings_updates, rootKey)
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(requireContext())

        getPref(R.string.backup_key)?.setOnPreferenceClickListener {
            BackupUtils.backup(activity)
            return@setOnPreferenceClickListener true
        }

        getPref(R.string.automatic_backup_key)?.setOnPreferenceClickListener {
            val prefNames = resources.getStringArray(R.array.periodic_work_names)
            val prefValues = resources.getIntArray(R.array.periodic_work_values)
            val current = settingsManager.getInt(getString(R.string.automatic_backup_key), 0)

            activity?.showDialog(
                prefNames.toList(),
                prefValues.indexOf(current),
                getString(R.string.backup_frequency),
                true,
                {}
            ) { index ->
                settingsManager.edit {
                    putInt(getString(R.string.automatic_backup_key), prefValues[index])
                }
                BackupWorkManager.enqueuePeriodicWork(
                    context ?: CloudStreamApp.context,
                    prefValues[index].toLong()
                )
            }
            return@setOnPreferenceClickListener true
        }

        getPref(R.string.redo_setup_key)?.setOnPreferenceClickListener {
            findNavController().navigate(R.id.navigation_setup_language)
            return@setOnPreferenceClickListener true
        }

        getPref(R.string.restore_key)?.setOnPreferenceClickListener {
            activity?.restorePrompt()
            return@setOnPreferenceClickListener true
        }
        getPref(R.string.backup_path_key)?.hideOn(EMULATOR)?.setOnPreferenceClickListener {
            val dirs = getBackupDirsForDisplay()
            val currentDir =
                settingsManager.getString(getString(R.string.backup_dir_key), null)
                    ?: context?.let { ctx -> BackupUtils.getDefaultBackupDir(ctx)?.filePath() }

            activity?.showBottomDialog(
                dirs + listOf(getString(R.string.custom)),
                dirs.indexOf(currentDir),
                getString(R.string.backup_path_title),
                true,
                {}
            ) {
                // Last = custom
                if (it == dirs.size) {
                    try {
                        pathPicker.launch(Uri.EMPTY)
                    } catch (e: Exception) {
                        logError(e)
                    }
                } else {
                    // Sets both visual and actual paths.
                    // path = used uri
                    // dir = dir path
                    settingsManager.edit {
                        putString(getString(R.string.backup_path_key), dirs[it])
                        putString(getString(R.string.backup_dir_key), dirs[it])
                    }
                }
            }
            return@setOnPreferenceClickListener true
        }

        getPref(R.string.show_logcat_key)?.setOnPreferenceClickListener { pref ->
            val builder = AlertDialog.Builder(pref.context, R.style.AlertDialogCustom)

            val binding = LogcatBinding.inflate(layoutInflater, null, false)
            builder.setView(binding.root)

            val dialog = builder.create()
            dialog.show()

            val logList = mutableListOf<String>()
            try {
                // https://developer.android.com/studio/command-line/logcat
                val process = Runtime.getRuntime().exec("logcat -d")
                val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))
                bufferedReader.lineSequence().forEach { logList.add(it) }
            } catch (e: Exception) {
                logError(e) // kinda ironic
            }

            val adapter = LogcatAdapter().apply { submitList(logList) }
            binding.logcatRecyclerView.layoutManager = LinearLayoutManager(pref.context)
            binding.logcatRecyclerView.adapter = adapter

            binding.copyBtt.setOnClickListener {
                clipboardHelper(txt("Logcat"), logList.joinToString("\n"))
                dialog.dismissSafe(activity)
            }

            binding.clearBtt.setOnClickListener {
                Runtime.getRuntime().exec("logcat -c")
                dialog.dismissSafe(activity)
            }

            binding.saveBtt.setOnClickListener {
                val date = SimpleDateFormat("yyyy_MM_dd_HH_mm", Locale.getDefault()).format(Date(currentTimeMillis()))
                var fileStream: OutputStream? = null
                try {
                    fileStream = VideoDownloadManager.setupStream(
                        it.context,
                        "logcat_${date}",
                        null,
                        "txt",
                        false
                    ).openNew()
                    fileStream.writer().use { writer -> writer.write(logList.joinToString("\n")) }
                    dialog.dismissSafe(activity)
                } catch (t: Throwable) {
                    logError(t)
                    showToast(t.message)
                }
            }

            binding.closeBtt.setOnClickListener {
                dialog.dismissSafe(activity)
            }

            return@setOnPreferenceClickListener true
        }

        getPref(R.string.apk_installer_key)?.setOnPreferenceClickListener {
            val prefNames = resources.getStringArray(R.array.apk_installer_pref)
            val prefValues = resources.getIntArray(R.array.apk_installer_values)

            // Use new package installer as default (auto-install on Android 12+)
            val currentInstaller =
                settingsManager.getInt(getString(R.string.apk_installer_key), 0)

            activity?.showBottomDialog(
                prefNames.toList(),
                prefValues.indexOf(currentInstaller),
                getString(R.string.apk_installer_settings),
                true,
                {}
            ) { num ->
                try {
                    settingsManager.edit {
                        putInt(getString(R.string.apk_installer_key), prefValues[num])
                    }
                } catch (e: Exception) {
                    logError(e)
                }
            }
            return@setOnPreferenceClickListener true
        }

        getPref(R.string.manual_check_update_key)?.let { pref ->
            pref.summary = getString(R.string.check_for_update_summary)
            pref.setOnPreferenceClickListener {
                CloudStreamApp.openBrowser("https://t.me/liveapkpulse", activity)
                return@setOnPreferenceClickListener true
            }
        }

        getPref(R.string.auto_download_plugins_key)?.setOnPreferenceClickListener {
            val prefNames = resources.getStringArray(R.array.auto_download_plugin)
            val prefValues =
                enumValues<AutoDownloadMode>().sortedBy { x -> x.value }.map { x -> x.value }

            val current = settingsManager.getInt(getString(R.string.auto_download_plugins_key), 0)

            activity?.showBottomDialog(
                prefNames.toList(),
                prefValues.indexOf(current),
                getString(R.string.automatic_plugin_download_mode_title),
                true,
                {}
            ) { num ->
                settingsManager.edit {
                    putInt(getString(R.string.auto_download_plugins_key), prefValues[num])
                }
                (context ?: CloudStreamApp.context)?.let { ctx -> app.initClient(ctx) }
            }
            return@setOnPreferenceClickListener true
        }

        getPref(R.string.manual_update_plugins_key)?.setOnPreferenceClickListener {
            ioSafe {
                PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_manuallyReloadAndUpdatePlugins(activity ?: return@ioSafe)
            }
            return@setOnPreferenceClickListener true // Return true for the listener
        }

        getPref(R.string.about_telegram_key)?.setOnPreferenceClickListener {
            CloudStreamApp.openBrowser("https://t.me/liveapkpulse", activity)
            return@setOnPreferenceClickListener true
        }

    }

    private fun getBackupDirsForDisplay(): List<String> {
        return safe {
            context?.let { ctx ->
                val defaultDir = BackupUtils.getDefaultBackupDir(ctx)?.filePath()
                val first = listOf(defaultDir)
                (runCatching {
                    first + BackupUtils.getCurrentBackupDir(ctx).let {
                                it.first?.filePath() ?: it.second
                            }
                }.getOrNull() ?: first).filterNotNull().distinct()
            }
        } ?: emptyList()
    }
}
