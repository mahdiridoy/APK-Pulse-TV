package com.pulsestream.app.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.pulsestream.app.CloudStreamApp.Companion.getKey
import com.pulsestream.app.CloudStreamApp.Companion.setKey
import com.pulsestream.app.CommonActivity.showToast
import com.pulsestream.app.R
import com.pulsestream.app.databinding.DialogAddPlaylistBinding
import com.pulsestream.app.ui.BasePreferenceFragmentCompat
import com.pulsestream.app.ui.livetv.CustomPlaylist
import com.pulsestream.app.ui.settings.SettingsFragment.Companion.getPref
import com.pulsestream.app.ui.settings.SettingsFragment.Companion.setPaddingBottom
import com.pulsestream.app.ui.settings.SettingsFragment.Companion.setToolBarScrollFlags
import com.pulsestream.app.ui.settings.SettingsFragment.Companion.setUpToolbar
import com.pulsestream.app.utils.SingleSelectionHelper.showDialog
import com.pulsestream.app.utils.UIHelper.dismissSafe

class SettingsPlaylists : BasePreferenceFragmentCompat() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(R.string.custom_playlists_category)
        setPaddingBottom()
        setToolBarScrollFlags()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_playlists, rootKey)

        fun getCustomPlaylists(): MutableList<CustomPlaylist> {
            return getKey<Array<CustomPlaylist>>(CustomPlaylist.CACHE_KEY)?.toMutableList()
                ?: mutableListOf()
        }

        fun showAddPlaylistDialog(editPlaylist: CustomPlaylist? = null) {
            val ctx = context ?: return
            val binding = DialogAddPlaylistBinding.inflate(layoutInflater, null, false)
            val builder = AlertDialog.Builder(ctx, R.style.AlertDialogCustom).setView(binding.root)
            val dialog = builder.create()

            val types = CustomPlaylist.PlaylistType.entries.toList()
            val typeNames = types.map { it.displayName }.toTypedArray()
            val typeAdapter = ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, typeNames)
            binding.playlistTypeDropdown.setAdapter(typeAdapter)

            // Pre-fill if editing
            var selectedType = editPlaylist?.type ?: CustomPlaylist.PlaylistType.M3U
            val typeIndex = types.indexOf(selectedType)
            if (typeIndex >= 0) {
                binding.playlistTypeDropdown.setText(typeNames[typeIndex], false)
            }
            if (editPlaylist != null) {
                binding.playlistNameInput.setText(editPlaylist.name)
                binding.playlistUrlInput.setText(editPlaylist.url)
                binding.playlistUsernameInput.setText(editPlaylist.username)
                binding.playlistPasswordInput.setText(editPlaylist.password)
            }

            fun updateFieldVisibility(type: CustomPlaylist.PlaylistType) {
                selectedType = type
                when (type) {
                    CustomPlaylist.PlaylistType.M3U -> {
                        binding.xtreamFieldsContainer.visibility = View.GONE
                        binding.playlistUrlLayout.hint = getString(R.string.playlist_url_hint)
                    }
                    CustomPlaylist.PlaylistType.XTREAM_CODES -> {
                        binding.xtreamFieldsContainer.visibility = View.VISIBLE
                        binding.playlistUrlLayout.hint = getString(R.string.xtream_server_hint)
                    }
                }
            }

            // Set initial visibility
            updateFieldVisibility(selectedType)

            binding.playlistTypeDropdown.setOnItemClickListener { _, _, position, _ ->
                updateFieldVisibility(types[position])
            }

            binding.cancelPlaylistButton.setOnClickListener {
                dialog.dismissSafe(activity)
            }

            binding.savePlaylistButton.setOnClickListener {
                val name = binding.playlistNameInput.text?.toString()?.trim().orEmpty()
                val url = binding.playlistUrlInput.text?.toString()?.trim().orEmpty()
                val username = binding.playlistUsernameInput.text?.toString()?.trim().orEmpty()
                val password = binding.playlistPasswordInput.text?.toString()?.trim().orEmpty()

                // Validate
                if (name.isEmpty()) {
                    showToast(R.string.playlist_error_empty_name, Toast.LENGTH_SHORT)
                    return@setOnClickListener
                }
                if (url.isEmpty()) {
                    showToast(R.string.playlist_error_empty_url, Toast.LENGTH_SHORT)
                    return@setOnClickListener
                }
                if (selectedType == CustomPlaylist.PlaylistType.XTREAM_CODES &&
                    (username.isEmpty() || password.isEmpty())
                ) {
                    showToast(R.string.playlist_error_empty_credentials, Toast.LENGTH_SHORT)
                    return@setOnClickListener
                }

                val playlists = getCustomPlaylists()
                if (editPlaylist != null) {
                    // Update existing
                    val idx = playlists.indexOfFirst { it.id == editPlaylist.id }
                    if (idx >= 0) {
                        playlists[idx] = editPlaylist.copy(
                            name = name, url = url,
                            username = username, password = password
                        )
                    }
                    showToast(R.string.playlist_updated, Toast.LENGTH_SHORT)
                } else {
                    // Add new
                    val newPlaylist = CustomPlaylist(
                        id = CustomPlaylist.generateId(),
                        name = name,
                        type = selectedType,
                        url = url,
                        username = username,
                        password = password,
                    )
                    playlists.add(newPlaylist)
                    showToast(R.string.playlist_added, Toast.LENGTH_SHORT)
                }

                setKey(CustomPlaylist.CACHE_KEY, playlists.toTypedArray())
                dialog.dismissSafe(activity)
            }

            dialog.show()
        }

        fun showManagePlaylistsDialog() {
            val playlists = getCustomPlaylists()
            if (playlists.isEmpty()) {
                showToast(R.string.no_custom_playlists, Toast.LENGTH_SHORT)
                showAddPlaylistDialog()
                return
            }

            val names = playlists.map { it.name }.toMutableList()
            names.add(0, getString(R.string.add_custom_playlist))

            activity?.showDialog(
                names,
                -1,
                getString(R.string.manage_custom_playlists),
                true,
                {}
            ) { index ->
                if (index == 0) {
                    // "Add new" was selected
                    showAddPlaylistDialog()
                } else {
                    val playlist = playlists.getOrNull(index - 1) ?: return@showDialog
                    // Show edit/delete options
                    val options = listOf(
                        getString(R.string.save), // Edit
                        getString(R.string.delete)
                    )
                    activity?.showDialog(
                        options,
                        -1,
                        playlist.name,
                        true,
                        {}
                    ) { option ->
                        when (option) {
                            0 -> showAddPlaylistDialog(editPlaylist = playlist)
                            1 -> {
                                val updated = getCustomPlaylists()
                                updated.removeAll { it.id == playlist.id }
                                setKey(CustomPlaylist.CACHE_KEY, updated.toTypedArray())
                                showToast(R.string.playlist_deleted, Toast.LENGTH_SHORT)
                            }
                        }
                    }
                }
            }
        }

        getPref(R.string.add_custom_playlist)?.setOnPreferenceClickListener {
            val playlists = getCustomPlaylists()
            if (playlists.isEmpty()) {
                showAddPlaylistDialog()
            } else {
                showManagePlaylistsDialog()
            }
            return@setOnPreferenceClickListener true
        }
    }
}
