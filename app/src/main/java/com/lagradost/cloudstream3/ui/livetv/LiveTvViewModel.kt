package com.lagradost.cloudstream3.ui.livetv

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LiveTvResolution(val label: String, val maxWidth: Int, val maxHeight: Int) {
    AUTO("Auto", Int.MAX_VALUE, Int.MAX_VALUE),
    R1080("1080p", 1920, 1080),
    R720("720p", 1280, 720),
    R480("480p", 854, 480),
    R360("360p", 640, 360),
}

/**
 * Shared between [LiveTvFragment] (channel list) and [LiveTvPlayerFragment] (fullscreen player)
 * via activityViewModels(), so selecting a channel on the list screen and navigating to the
 * player screen doesn't need any nav-graph arguments.
 */
class LiveTvViewModel : ViewModel() {

    private val _channels = MutableLiveData<List<LiveTvChannel>>(emptyList())
    val channels: LiveData<List<LiveTvChannel>> = _channels

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _isRefreshing = MutableLiveData(false)
    val isRefreshing: LiveData<Boolean> = _isRefreshing

    /** Index of the currently selected/playing channel inside [channels]'s current value. */
    private val _currentIndex = MutableLiveData(-1)
    val currentIndex: LiveData<Int> = _currentIndex

    /** Emits an error message once, consumed by the fragment (single-shot). */
    private val _loadError = MutableLiveData<String?>(null)
    val loadError: LiveData<String?> = _loadError

    private val _selectedResolution = MutableLiveData(LiveTvResolution.AUTO)
    val selectedResolution: LiveData<LiveTvResolution> = _selectedResolution

    fun setResolution(resolution: LiveTvResolution) {
        _selectedResolution.value = resolution
    }

    private var hasLoadedSuccessfully = false
    private var isRefreshInFlight = false

    fun currentChannel(): LiveTvChannel? {
        val list = _channels.value ?: return null
        val idx = _currentIndex.value ?: -1
        return list.getOrNull(idx)
    }

    /**
     * Loads channels. On first call it will show cached channels instantly (if any) then
     * kick off a network refresh in the background - this satisfies "auto scan for update
     * the channels" on app start without blocking the UI.
     *
     * Safe to call every time the screen becomes visible (e.g. from onResume): if a previous
     * attempt already produced a non-empty channel list this is a no-op, but if the very first
     * cold-start attempt failed for some transient reason (e.g. network not fully ready yet
     * immediately after process start) it will retry rather than getting stuck empty until the
     * whole app process is killed and restarted.
     */
    fun loadInitial() {
        if (hasLoadedSuccessfully) return

        val cached = LiveTvRepository.getCachedChannels()
        if (cached.isNotEmpty()) {
            _channels.value = cached
            restoreLastChannelIndex(cached)
            hasLoadedSuccessfully = true
            refresh(forceRefresh = true, showSpinner = false)
        } else {
            _isLoading.value = true
            refresh(forceRefresh = true, showSpinner = true)
        }
    }

    fun refresh(forceRefresh: Boolean = true, showSpinner: Boolean = false) {
        // A cold start invokes loading from both view creation and resume. Coalesce those calls
        // so the first screen cannot be left behind by competing responses.
        if (isRefreshInFlight) return
        isRefreshInFlight = true
        if (showSpinner) _isLoading.value = true else _isRefreshing.value = true

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    LiveTvRepository.getChannels(forceRefresh = forceRefresh)
                }
                if (result.isEmpty()) {
                    if ((_channels.value ?: emptyList()).isEmpty()) {
                        _loadError.value = "no_channels"
                    }
                    return@launch
                }

                val previousId = currentChannel()?.id ?: LiveTvRepository.getLastChannelId()
                _channels.value = result
                hasLoadedSuccessfully = true

                val restoredIndex = previousId?.let { id -> result.indexOfFirst { it.id == id } } ?: -1
                _currentIndex.value = when {
                    restoredIndex >= 0 -> restoredIndex
                    (_currentIndex.value ?: -1) < 0 -> 0
                    else -> _currentIndex.value ?: 0
                }
            } finally {
                isRefreshInFlight = false
                _isLoading.value = false
                _isRefreshing.value = false
            }
        }
    }

    private fun restoreLastChannelIndex(list: List<LiveTvChannel>) {
        val lastId = LiveTvRepository.getLastChannelId()
        val idx = if (lastId != null) list.indexOfFirst { it.id == lastId } else -1
        _currentIndex.value = if (idx >= 0) idx else 0
    }

    fun selectIndex(index: Int) {
        val list = _channels.value ?: return
        if (index !in list.indices) return
        _currentIndex.value = index
        LiveTvRepository.setLastChannelId(list[index].id)
    }

    /** Moves forward, wrapping around, and skipping the channel currently at [failedIndex] if given. */
    fun nextChannel() {
        val list = _channels.value ?: return
        if (list.isEmpty()) return
        val idx = _currentIndex.value ?: -1
        val next = if (idx < 0) 0 else (idx + 1) % list.size
        selectIndex(next)
    }

    fun previousChannel() {
        val list = _channels.value ?: return
        if (list.isEmpty()) return
        val idx = _currentIndex.value ?: -1
        val prev = if (idx < 0) 0 else (idx - 1 + list.size) % list.size
        selectIndex(prev)
    }

    /** True index is at the last channel in the list - used to trigger the TV-remote search-open gesture. */
    fun isAtLastChannel(): Boolean {
        val list = _channels.value ?: return false
        val idx = _currentIndex.value ?: -1
        return list.isNotEmpty() && idx == list.lastIndex
    }

    fun clearError() {
        _loadError.value = null
    }
}
