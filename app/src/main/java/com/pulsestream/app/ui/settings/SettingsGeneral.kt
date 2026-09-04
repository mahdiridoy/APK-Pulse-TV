package com.pulsestream.app.ui.settings

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.os.ConfigurationCompat
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder.allProviders
import com.pulsestream.app.CloudStreamApp
import com.pulsestream.app.CloudStreamApp.Companion.getKey
import com.pulsestream.app.CloudStreamApp.Companion.setKey
import com.pulsestream.app.CommonActivity
import com.pulsestream.app.CommonActivity.showToast
import com.pulsestream.app.MainActivity
import com.pulsestream.app.R
import com.lagradost.cloudstream3.app
import com.pulsestream.app.databinding.AddRemoveSitesBinding
import com.pulsestream.app.databinding.AddSiteInputBinding
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.pulsestream.app.network.initClient
import com.pulsestream.app.ui.BasePreferenceFragmentCompat
import com.pulsestream.app.ui.settings.Globals.EMULATOR
import com.pulsestream.app.ui.settings.Globals.TV
import com.pulsestream.app.ui.settings.Globals.beneneCount
import com.pulsestream.app.ui.settings.SettingsFragment.Companion.getPref
import com.pulsestream.app.ui.settings.SettingsFragment.Companion.hideOn
import com.pulsestream.app.ui.settings.SettingsFragment.Companion.setPaddingBottom
import com.pulsestream.app.ui.settings.SettingsFragment.Companion.setToolBarScrollFlags
import com.pulsestream.app.ui.settings.SettingsFragment.Companion.setUpToolbar
import com.pulsestream.app.ui.settings.utils.getChooseFolderLauncher
import com.pulsestream.app.utils.BatteryOptimizationChecker.isAppRestricted
import com.pulsestream.app.utils.BatteryOptimizationChecker.showBatteryOptimizationDialog
import com.pulsestream.app.utils.SingleSelectionHelper.showBottomDialog
import com.pulsestream.app.utils.SingleSelectionHelper.showDialog
import com.pulsestream.app.utils.SingleSelectionHelper.showMultiDialog
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.pulsestream.app.utils.UIHelper.dismissSafe
import com.pulsestream.app.utils.UIHelper.hideKeyboard
import com.pulsestream.app.utils.UIHelper.navigate
import com.pulsestream.app.utils.USER_PROVIDER_API
import com.pulsestream.app.utils.downloader.DownloadFileManagement
import com.pulsestream.app.utils.downloader.DownloadFileManagement.getBasePath
import com.pulsestream.app.utils.downloader.DownloadQueueManager
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import java.util.Locale

// Change local language settings in the app.
fun getCurrentLocale(context: Context): String {
    val conf = context.resources.configuration
    return ConfigurationCompat.getLocales(conf).get(0)?.toLanguageTag() ?: "en"
}

/**
 * List of app supported languages.
 * Language code shall be a IETF BCP 47 conformant tag
 *
 * See locales on:
 * https://github.com/unicode-org/cldr-json/blob/main/cldr-json/cldr-core/availableLocales.json
 * https://www.iana.org/assignments/language-subtag-registry/language-subtag-registry
 * https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r2/core/res/res/values/locale_config.xml
 * https://iso639-3.sil.org/code_tables/639/data/all
*/
val appLanguages = arrayListOf(
    /* begin language list */
    Pair("Afrikaans", "af"),
    Pair("Azərbaycan dili", "az"),
    Pair("Bahasa Indonesia", "in"),
    Pair("Bahasa Melayu", "ms"),
    Pair("Català", "ca"),
    Pair("Deutsch", "de"),
    Pair("English", "en"),
    Pair("Español", "es"),
    Pair("Esperanto", "eo"),
    Pair("Français", "fr"),
    Pair("Galego", "gl"),
    Pair("hrvatski", "hr"),
    Pair("Italiano", "it"),
    Pair("Latviešu valoda", "lv"),
    Pair("Lietuviu kalba", "lt"),
    Pair("Magyar", "hu"),
    Pair("Malti", "mt"),
    Pair("mmmm... monke", "qt"),
    Pair("Nederlands", "nl"),
    Pair("Norsk bokmål", "no"),
    Pair("Norsk nynorsk", "nn"),
    Pair("Polski", "pl"),
    Pair("Português", "pt"),
    Pair("Português (Brasil)", "pt-BR"),
    Pair("Română", "ro"),
    Pair("Shqip", "sq"),
    Pair("Slovencina", "sk"),
    Pair("Soomaaliga", "so"),
    Pair("Svenska", "sv"),
    Pair("Tagalog", "tl"),
    Pair("Tiếng Việt", "vi"),
    Pair("Türkçe", "tr"),
    Pair("Wikang Filipino", "fil"),
    Pair("Čeština", "cs"),
    Pair("Ελληνικά", "el"),
    Pair("Беларуская мова", "be"),
    Pair("Български", "bg"),
    Pair("Македонски", "mk"),
    Pair("Русский", "ru"),
    Pair("Українська", "uk"),
    Pair("עברית", "iw"),
    Pair("اردو", "ur"),
    Pair("العربية", "ar"),
    Pair("لهجة نجدية", "ars"),
    Pair("العامية الشامية", "apc"),
    Pair("فارسی", "fa"),
    Pair("سۆرانی کوردی", "ckb"),
    Pair("नेपाली", "ne"),
    Pair("हिन्दी", "hi"),
    Pair("অসমীয়া", "as"),
    Pair("বাংলা", "bn"),
    Pair("ଓଡ଼ିଆ", "or"),
    Pair("தமிழ்", "ta"),
    Pair("ಕನ್ನಡ", "kn"),
    Pair("മലയാളം", "ml"),
    Pair("မြန်မာ", "my"),
    Pair("ትግርኛ", "ti"),
    Pair("አማርኛ", "am"),
    Pair("中文", "zh"),
    Pair("日本語 (にほんご)", "ja"),
    Pair("中文(繁體)", "zh-TW"),
    Pair("한국어", "ko"),
/* end language list */
).sortedBy { it.first.lowercase(Locale.ROOT) } // ye, we go alphabetical, so ppl don't put their lang on top

fun Pair<String, String>.nameNextToFlagEmoji(): String {
    // fallback to [A][A] -> [?] question mak flag
    val flag = SubtitleHelper.getFlagFromIso(this.second) ?: "\ud83c\udde6\ud83c\udde6"

    return "$flag\u00a0${this.first}" // \u00a0 non-breaking space
}

class SettingsGeneral : BasePreferenceFragmentCompat() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(R.string.category_general)
        setPaddingBottom()
        setToolBarScrollFlags()
    }

    @OptIn(ExperimentalSerializationApi::class) // JsonNames is an experimental annotation for now
    @Serializable
    data class CustomSite(
        @JsonProperty("parentClassName") @JsonAlias("parentJavaClass")
        @SerialName("parentClassName") @JsonNames("parentJavaClass")
        val parentClassName: String, // ::class.simpleName
        @JsonProperty("name") @SerialName("name") val name: String,
        @JsonProperty("url") @SerialName("url") val url: String,
        @JsonProperty("lang") @SerialName("lang") val lang: String,
    )

    companion object {
        fun Fragment.pickDownloadPath(uri: Uri?, path: String?) {
            if (uri == null) return

            val context = context ?: CloudStreamApp.context ?: return
            val visual = path ?: uri.toString()
            PreferenceManager.getDefaultSharedPreferences(context).edit {
                putString(getString(R.string.download_path_key), uri.toString())
                putString(context.getString(R.string.download_path_key_visual), visual)
            }
        }
    }

    private val pathPicker = getChooseFolderLauncher { uri, path ->
        pickDownloadPath(uri, path)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        hideKeyboard()
        setPreferencesFromResource(R.xml.settings_general, rootKey)
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(requireContext())

        fun getCurrent(): MutableList<CustomSite> {
            return getKey<Array<CustomSite>>(USER_PROVIDER_API)?.toMutableList()
                ?: mutableListOf()
        }

        getPref(R.string.locale_key)?.setOnPreferenceClickListener { pref ->
            val current = getCurrentLocale(pref.context)
            val languageTagsIETF = appLanguages.map { it.second }
            val languageNames = appLanguages.map { it.nameNextToFlagEmoji() }
            val currentIndex = languageTagsIETF.indexOf(current)

            activity?.showDialog(
                languageNames, currentIndex, getString(R.string.app_language), true, { }
            ) { selectedLangIndex ->
                try {
                    val langTagIETF = languageTagsIETF[selectedLangIndex]
                    CommonActivity.setLocale(activity, langTagIETF)
                    settingsManager.edit {
                        putString(getString(R.string.locale_key), langTagIETF)
                    }
                    activity?.recreate()
                } catch (e: Exception) {
                    logError(e)
                }
            }
            return@setOnPreferenceClickListener true
        }

        getPref(R.string.battery_optimisation_key)?.hideOn(TV or EMULATOR)?.setOnPreferenceClickListener {
            val ctx = context ?: return@setOnPreferenceClickListener false

            if (isAppRestricted(ctx)) {
                ctx.showBatteryOptimizationDialog()
            } else {
                showToast(R.string.app_unrestricted_toast)
            }

            true
        }

        fun showAdd() {
            val providers = allProviders.distinctBy { it::class }.sortedBy { it.name }
            activity?.showDialog(
                providers.map { "${it.name} (${it.mainUrl})" },
                -1,
                context?.getString(R.string.add_site_pref) ?: return,
                true,
                {}) { selection ->
                val provider = providers.getOrNull(selection) ?: return@showDialog

                val binding : AddSiteInputBinding = AddSiteInputBinding.inflate(layoutInflater,null,false)

                val builder =
                    AlertDialog.Builder(context ?: return@showDialog, R.style.AlertDialogCustom)
                        .setView(binding.root)

                val dialog = builder.create()
                dialog.show()

                binding.text2.text = provider.name
                binding.applyBtt.setOnClickListener {
                    val name = binding.siteNameInput.text?.toString()
                    val url = binding.siteUrlInput.text?.toString()
                    val lang = binding.siteLangInput.text?.toString()
                    val realLang = if (lang.isNullOrBlank()) provider.lang else lang
                    val simpleName = provider::class.simpleName
                    if (url.isNullOrBlank() || name.isNullOrBlank() || simpleName == null) {
                        showToast(R.string.error_invalid_data, Toast.LENGTH_SHORT)
                        return@setOnClickListener
                    }

                    val current = getCurrent()
                    val newSite = CustomSite(simpleName, name, url, realLang)
                    current.add(newSite)
                    setKey(USER_PROVIDER_API, current.toTypedArray())
                    // reload apis
                    MainActivity.afterPluginsLoadedEvent.invoke(false)

                    dialog.dismissSafe(activity)
                }
                binding.cancelBtt.setOnClickListener {
                    dialog.dismissSafe(activity)
                }
            }
        }

        fun showDelete() {
            val current = getCurrent()

            activity?.showMultiDialog(
                current.map { it.name },
                listOf(),
                context?.getString(R.string.remove_site_pref) ?: return,
                {}) { indexes ->
                current.removeAll(indexes.map { current[it] })
                setKey(USER_PROVIDER_API, current.toTypedArray())
            }
        }

        fun showAddOrDelete() {
            val binding : AddRemoveSitesBinding = AddRemoveSitesBinding.inflate(layoutInflater,null,false)
            val builder =
                AlertDialog.Builder(context ?: return, R.style.AlertDialogCustom)
                    .setView(binding.root)

            val dialog = builder.create()
            dialog.show()

            binding.addSite.setOnClickListener {
                showAdd()
                dialog.dismissSafe(activity)
            }
            binding.removeSite.setOnClickListener {
                showDelete()
                dialog.dismissSafe(activity)
            }
        }

        getPref(R.string.override_site_key)?.setOnPreferenceClickListener { _ ->

            if (getCurrent().isEmpty()) {
                showAdd()
            } else {
                showAddOrDelete()
            }

            return@setOnPreferenceClickListener true
        }

        getPref(R.string.legal_notice_key)?.setOnPreferenceClickListener {
            val builder: AlertDialog.Builder =
                AlertDialog.Builder(it.context, R.style.AlertDialogCustom)
            builder.setTitle(R.string.legal_notice)
            builder.setMessage(R.string.legal_notice_text)
            builder.show()
            return@setOnPreferenceClickListener true
        }

        getPref(R.string.dns_key)?.setOnPreferenceClickListener {
            val prefNames = resources.getStringArray(R.array.dns_pref)
            val prefValues = resources.getIntArray(R.array.dns_pref_values)

            val currentDns =
                settingsManager.getInt(getString(R.string.dns_pref), 0)

            activity?.showBottomDialog(
                prefNames.toList(),
                prefValues.indexOf(currentDns),
                getString(R.string.dns_pref),
                true,
                {}) {
                settingsManager.edit { putInt(getString(R.string.dns_pref), prefValues[it]) }
                (context ?: CloudStreamApp.context)?.let { ctx -> app.initClient(ctx) }
            }
            return@setOnPreferenceClickListener true
        }

        fun getDownloadDirs(): List<String> {
            return safe {
                context?.let { ctx ->
                    val defaultDir = DownloadFileManagement.getDefaultDir(ctx)?.filePath()

                    val first = listOf(defaultDir)
                    (try {
                        val currentDir = ctx.getBasePath().let { it.first?.filePath() ?: it.second }

                        (first +
                                ctx.getExternalFilesDirs("").mapNotNull { it.path } +
                                currentDir)
                    } catch (e: Exception) {
                        first
                    }).filterNotNull().distinct()
                }
            } ?: emptyList()
        }

        settingsManager.edit { putBoolean(getString(R.string.jsdelivr_proxy_key), getKey<Boolean>(getString(R.string.jsdelivr_proxy_key), false) ?: false) }
        getPref(R.string.jsdelivr_proxy_key)?.setOnPreferenceChangeListener { _, newValue ->
            setKey(getString(R.string.jsdelivr_proxy_key), newValue)
            return@setOnPreferenceChangeListener true
        }

        getPref(R.string.download_parallel_key)?.setOnPreferenceChangeListener { _, _ ->
            // Notify that the queue logic has been changed
            DownloadQueueManager.forceRefreshQueue()
            return@setOnPreferenceChangeListener true
        }

        getPref(R.string.download_path_key)?.setOnPreferenceClickListener {
            val dirs = getDownloadDirs()

            val currentDir =
                settingsManager.getString(getString(R.string.download_path_key_visual), null)
                    ?: context?.let { ctx -> DownloadFileManagement.getDefaultDir(ctx)?.filePath() }

            activity?.showBottomDialog(
                dirs + listOf(getString(R.string.custom)),
                dirs.indexOf(currentDir),
                getString(R.string.download_path_pref),
                true,
                {}) {
                // Last = custom
                if (it == dirs.size) {
                    try {
                        pathPicker.launch(Uri.EMPTY)
                    } catch (e: Exception) {
                        logError(e)
                    }
                } else {
                    // Sets both visual and actual paths.
                    // key = used path
                    // visual = visual path
                    settingsManager.edit {
                        putString(getString(R.string.download_path_key), dirs[it])
                        putString(getString(R.string.download_path_key_visual), dirs[it])
                    }
                }
            }
            return@setOnPreferenceClickListener true
        }

        try {
            beneneCount =
                settingsManager.getInt(getString(R.string.benene_count), 0)
            getPref(R.string.benene_count)?.let { pref ->
                pref.summary =
                    if (beneneCount <= 0) getString(R.string.benene_count_text_none) else getString(
                        R.string.benene_count_text
                    ).format(
                        beneneCount
                    )

                pref.setOnPreferenceClickListener {
                    try {
                        beneneCount++
                        if (beneneCount%20 == 0) {
                            activity?.navigate(R.id.action_navigation_settings_general_to_easterEggMonkeFragment)
                        }
                        settingsManager.edit {
                            putInt(
                                getString(R.string.benene_count),
                                beneneCount
                            )
                        }
                        it.summary = getString(R.string.benene_count_text).format(beneneCount)
                    } catch (e: Exception) {
                        logError(e)
                    }

                    return@setOnPreferenceClickListener true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
