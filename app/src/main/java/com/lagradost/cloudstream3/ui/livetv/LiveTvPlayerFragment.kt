package com.lagradost.cloudstream3.ui.livetv

import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.PopupMenu
import androidx.fragment.app.activityViewModels
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.navigation.fragment.findNavController
import com.lagradost.cloudstream3.CommonActivity.keyEventListener
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.databinding.FragmentLiveTvPlayerBinding
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.attachBackPressedCallback
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.detachBackPressedCallback
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.hideSystemUI
import com.lagradost.cloudstream3.utils.UIHelper.showSystemUI

@OptIn(UnstableApi::class)
class LiveTvPlayerFragment : BaseFragment<FragmentLiveTvPlayerBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentLiveTvPlayerBinding::inflate)
) {

    private val viewModel: LiveTvViewModel by activityViewModels()

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var loadedChannelId: String? = null
    private var currentMirrorIndex = 0
    private val failedChannelIdsThisCycle = mutableSetOf<String>()

    private var hideTopBarRunnable: Runnable? = null
    private var hideOsdRunnable: Runnable? = null

    private var previousOrientation: Int? = null

    // Fullscreen immersive video: system bars are hidden/shown explicitly via
    // enterFullscreenMode()/exitFullscreenMode(), so no inset padding is needed here.
    override fun fixLayout(view: View) {}

    override fun onBindingCreated(binding: FragmentLiveTvPlayerBinding, savedInstanceState: Bundle?) {
        setupPlayer(binding)
        setupGestures(binding)
        setupObservers(binding)

        binding.liveTvBackButton.setOnClickListener { goBackToList() }
        binding.liveTvResolutionButton.setOnClickListener { showResolutionMenu(it) }

        binding.liveTvRetryButton.setOnClickListener {
            binding.liveTvErrorContainer.visibility = View.GONE
            viewModel.refresh(forceRefresh = true, showSpinner = true)
        }

        binding.root.isFocusableInTouchMode = true

        // Play whatever channel is currently selected in the shared ViewModel (set by the list
        // screen right before navigating here).
        viewModel.currentChannel()?.let { playChannel(it) }
    }

    private fun goBackToList() {
        if (findNavController().popBackStack().not()) {
            findNavController().navigate(R.id.navigation_live_tv)
        }
    }

    // region Fullscreen / orientation

    private fun enterFullscreenMode() {
        val act = activity ?: return
        act.hideSystemUI()
        act.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (isLayout(PHONE)) {
            previousOrientation = act.requestedOrientation
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private var isEnteringPiP = false

    private fun exitFullscreenMode() {
        val act = activity ?: return
        act.showSystemUI()
        act.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (isLayout(PHONE) && !isInPictureInPictureMode()) {
            act.requestedOrientation =
                previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun enterPiPMode() {
        val act = activity ?: return
        isEnteringPiP = true
        try {
            val aspectRatio = Rational(16, 9)
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .build()
            act.enterPictureInPictureMode(params)
        } catch (e: Exception) {
            isEnteringPiP = false
        }
    }

    private fun isInPictureInPictureMode(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                activity?.isInPictureInPictureMode == true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        val binding = binding ?: return
        if (isInPictureInPictureMode) {
            // In PiP: hide all UI controls, keep only the player surface.
            binding.liveTvTopBar.visibility = View.GONE
            binding.liveTvOsd.visibility = View.GONE
            binding.liveTvQualityIndicator.visibility = View.GONE
            binding.liveTvGestureOverlay.visibility = View.GONE
            binding.liveTvResolutionButton.visibility = View.GONE
            binding.liveTvBackButton.visibility = View.GONE
            binding.liveTvErrorContainer.visibility = View.GONE
            activity?.hideSystemUI()
        } else {
            // Returned from PiP: restore UI.
            binding.liveTvGestureOverlay.visibility = View.VISIBLE
            binding.liveTvResolutionButton.visibility = View.VISIBLE
            binding.liveTvBackButton.visibility = View.VISIBLE
            isEnteringPiP = false
        }
    }

    // endregion

    // region Player setup

    private fun buildPlayer(context: Context): Pair<ExoPlayer, DefaultTrackSelector> {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val selector = DefaultTrackSelector(context)
        val exoPlayer = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(selector)
            .setLivePlaybackSpeedControl(
                DefaultLivePlaybackSpeedControl.Builder()
                    .setFallbackMaxPlaybackSpeed(1.03f)
                    .setFallbackMinPlaybackSpeed(0.97f)
                    .build()
            )
            .build()
        return exoPlayer to selector
    }

    private fun setupPlayer(binding: FragmentLiveTvPlayerBinding) {
        val (exoPlayer, selector) = buildPlayer(requireContext())
        exoPlayer.addListener(playerListener)
        binding.liveTvPlayerView.player = exoPlayer
        player = exoPlayer
        trackSelector = selector
        applyResolution(viewModel.selectedResolution.value ?: LiveTvResolution.AUTO)
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val binding = binding ?: return
            when (playbackState) {
                Player.STATE_READY -> {
                    failedChannelIdsThisCycle.clear()
                    currentMirrorIndex = 0
                    binding.liveTvLoading.visibility = View.GONE
                }

                Player.STATE_BUFFERING -> {
                    binding.liveTvLoading.visibility = View.VISIBLE
                }

                else -> Unit
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            // Re-apply resolution when tracks change so the selected quality is enforced
            // after HLS manifests are parsed and track groups become available.
            val resolution = viewModel.selectedResolution.value ?: LiveTvResolution.AUTO
            applyResolution(resolution)
            // Pin the exact track if a specific quality is selected.
            if (resolution != LiveTvResolution.AUTO) {
                pinExactTrack(tracks, resolution)
            }
            updateQualityIndicator(tracks)
        }

        override fun onPlayerError(error: PlaybackException) {
            val binding = binding ?: return
            binding.liveTvLoading.visibility = View.GONE

            // Try next mirror for the same channel before giving up.
            if (tryNextMirror()) return

            // All mirrors exhausted — mark this channel as failed.
            val failedChannel = viewModel.currentChannel()
            if (failedChannel != null) failedChannelIdsThisCycle.add(failedChannel.id)

            val totalChannels = viewModel.channels.value?.size ?: 0
            if (totalChannels > 0 && failedChannelIdsThisCycle.size < totalChannels) {
                showOsd(getString(R.string.live_tv_channel_unavailable))
                binding.root.postDelayed({ viewModel.nextChannel() }, 500)
            } else {
                showError(getString(R.string.live_tv_no_channels))
            }
        }
    }

    private fun playChannel(channel: LiveTvChannel) {
        if (loadedChannelId == channel.id) return
        loadedChannelId = channel.id
        currentMirrorIndex = 0
        loadMirror(channel, 0)
    }

    /**
     * Load a specific mirror index for the given channel.
     * Index 0 = primary URL, 1+ = mirror URLs from other servers.
     */
    private fun loadMirror(channel: LiveTvChannel, mirrorIndex: Int) {
        val url = channel.getStreamUrlAtIndex(mirrorIndex)
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()

        player?.apply {
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }

        binding?.liveTvErrorContainer?.visibility = View.GONE
        updateTopBar(channel)

        if (mirrorIndex > 0) {
            showOsd(getString(
                R.string.live_tv_trying_mirror,
                channel.name,
                mirrorIndex + 1,
                channel.totalMirrorCount,
            ))
        } else {
            showChannelOsd(channel)
        }
    }

    /** Try the next mirror for the current channel. Returns true if a mirror was found. */
    private fun tryNextMirror(): Boolean {
        val channel = viewModel.currentChannel() ?: return false
        val nextIndex = currentMirrorIndex + 1
        if (nextIndex < channel.totalMirrorCount) {
            currentMirrorIndex = nextIndex
            loadMirror(channel, nextIndex)
            return true
        }
        return false
    }

    // endregion

    // region Resolution selector

    private fun applyResolution(resolution: LiveTvResolution) {
        val selector = trackSelector ?: return
        val params = selector.buildUponParameters()
        if (resolution == LiveTvResolution.AUTO) {
            // Auto: clear constraints, let ExoPlayer adaptive bitrate work.
            params.clearVideoSizeConstraints()
            params.setForceHighestSupportedBitrate(false)
        } else {
            // Force exact quality: use tight constraints so the initial selection is correct.
            // The actual track pin happens in pinExactTrack() via onTracksChanged.
            params.setMinVideoSize(resolution.maxWidth, resolution.maxHeight)
            params.setMaxVideoSize(resolution.maxWidth, resolution.maxHeight)
            params.setForceHighestSupportedBitrate(true)
        }
        selector.parameters = params.build()
    }

    /**
     * Called from onTracksChanged. When a specific quality is selected, find the best matching
     * video track and pin it with a TrackSelectionOverride so ExoPlayer cannot fall back to
     * a different quality — even across HLS manifest refreshes.
     */
    private fun pinExactTrack(tracks: Tracks, resolution: LiveTvResolution) {
        if (resolution == LiveTvResolution.AUTO) return
        val selector = trackSelector ?: return

        var bestGroupIndex = -1
        var bestTrackIndex = -1
        var bestHeightDiff = Int.MAX_VALUE

        for (groupIndex in 0 until tracks.groups.size) {
            val group = tracks.groups[groupIndex]
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                // Match by height first (most reliable for HLS renditions).
                val heightDiff = kotlin.math.abs(format.height - resolution.maxHeight)
                if (heightDiff < bestHeightDiff) {
                    bestHeightDiff = heightDiff
                    bestGroupIndex = groupIndex
                    bestTrackIndex = trackIndex
                }
            }
        }

        if (bestGroupIndex >= 0) {
            val group = tracks.groups[bestGroupIndex]
            val override = TrackSelectionOverride(
                group.mediaTrackGroup,
                listOf(bestTrackIndex)
            )
            val params = selector.buildUponParameters()
            params.setOverrideForType(override)
            selector.parameters = params.build()
        }
    }

    private fun showResolutionMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        val current = viewModel.selectedResolution.value ?: LiveTvResolution.AUTO
        LiveTvResolution.entries.forEachIndexed { index, res ->
            val label = if (res == current) "${res.label} \u2713" else res.label
            popup.menu.add(0, index, index, label)
        }
        popup.setOnMenuItemClickListener { item ->
            val resolution = LiveTvResolution.entries[item.itemId]
            viewModel.setResolution(resolution)
            applyResolution(resolution)
            showOsd(getString(R.string.live_tv_resolution_set_format, resolution.label))
            true
        }
        popup.show()
    }

    // endregion

    // region Quality indicator

    /**
     * Reads the currently selected video track and updates the on-screen quality indicator
     * with resolution + bitrate information. Called from onTracksChanged so it stays current
     * as HLS manifests refresh.
     */
    private fun updateQualityIndicator(tracks: Tracks) {
        val binding = binding ?: return
        val p = player ?: return

        var width: Int? = null
        var height: Int? = null
        var bitrate: Int? = null

        for (group in 0 until tracks.groups.size) {
            val trackGroup = tracks.groups[group]
            if (trackGroup.type != C.TRACK_TYPE_VIDEO) continue
            for (track in 0 until trackGroup.length) {
                if (trackGroup.isTrackSelected(track)) {
                    val format = trackGroup.getTrackFormat(track)
                    width = format.width.takeIf { it > 0 }
                    height = format.height.takeIf { it > 0 }
                    bitrate = format.bitrate.takeIf { it > 0 }
                    break
                }
            }
        }

        if (width != null && height != null) {
            val resLabel = when {
                height >= 2160 -> "4K"
                height >= 1440 -> "1440p"
                height >= 1080 -> "1080p"
                height >= 720 -> "720p"
                height >= 480 -> "480p"
                height >= 360 -> "360p"
                else -> "${height}p"
            }
            val bitrateStr = if (bitrate != null) {
                if (bitrate >= 1_000_000) {
                    String.format("%.1f Mbps", bitrate / 1_000_000.0)
                } else {
                    String.format("%d kbps", bitrate / 1000)
                }
            } else null

            val indicator = if (bitrateStr != null) {
                getString(R.string.live_tv_quality_indicator, resLabel, bitrateStr)
            } else resLabel

            binding.liveTvQualityIndicator.text = indicator
            binding.liveTvQualityIndicator.visibility = View.VISIBLE
        } else {
            binding.liveTvQualityIndicator.visibility = View.GONE
        }
    }

    // endregion

    // region Gestures

    private fun setupGestures(binding: FragmentLiveTvPlayerBinding) {
        val helper = LiveTvGestureHelper(
            context = requireContext(),
            onNextChannel = { viewModel.nextChannel() },
            onPreviousChannel = { viewModel.previousChannel() },
            onOpenSearch = { goBackToList() },
            onRefresh = { },
            onVolumeStep = { step -> adjustVolume(step) },
            onBrightnessStep = { step -> adjustBrightness(step) },
        )
        binding.liveTvGestureOverlay.setOnTouchListener(helper)
    }

    private fun adjustVolume(direction: Float) {
        val am = requireContext().getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val next = (current + if (direction > 0) 1 else -1).coerceIn(0, max)
        if (next != current) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
        }
        val percent = if (max > 0) (next * 100 / max) else 0
        showOsd(getString(R.string.live_tv_volume_format, percent))
    }

    private fun adjustBrightness(direction: Float) {
        val window = activity?.window ?: return
        val lp = window.attributes
        val current = lp.screenBrightness.takeIf { it in 0f..1f } ?: 0.5f
        val next = (current + if (direction > 0) 0.05f else -0.05f).coerceIn(0.02f, 1f)
        lp.screenBrightness = next
        window.attributes = lp
        showOsd(getString(R.string.live_tv_brightness_format, (next * 100).toInt()))
    }

    // endregion

    // region TV remote key handling

    /** Kept as a direct reference so onPause only clears the global listener if it's still
     * this fragment's own - Fragment transition lifecycle order between the list and player
     * screens isn't strictly guaranteed, so the outgoing fragment's cleanup can otherwise race
     * with and wipe out the incoming fragment's listener right after it's set. */
    private var myKeyEventListener: ((Pair<KeyEvent?, Boolean>) -> Boolean)? = null

    private fun setupTvKeyHandling() {
        val listener: (Pair<KeyEvent?, Boolean>) -> Boolean = { pair ->
            val event = pair.first
            if (event == null || event.action != KeyEvent.ACTION_DOWN) {
                false
            } else when (event.keyCode) {
                // CH+ moves forward (next channel), CH- moves backward (previous channel).
                KeyEvent.KEYCODE_CHANNEL_UP -> {
                    viewModel.nextChannel(); true
                }

                KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    viewModel.previousChannel(); true
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    goBackToList(); true
                }

                else -> false
            }
        }
        myKeyEventListener = listener
        keyEventListener = listener
    }

    // endregion

    // region Observers & UI helpers

    private fun setupObservers(binding: FragmentLiveTvPlayerBinding) {
        observe(viewModel.currentIndex) {
            val channel = viewModel.currentChannel() ?: return@observe
            playChannel(channel)
        }

        observe(viewModel.isLoading) { loading ->
            binding.liveTvLoading.visibility = if (loading) View.VISIBLE else View.GONE
        }
    }

    private fun updateTopBar(channel: LiveTvChannel) {
        val binding = binding ?: return
        binding.liveTvTopBarName.text = channel.name
        binding.liveTvTopBarLogo.loadImage(channel.logoUrl)
        binding.liveTvTopBar.visibility = View.VISIBLE
        binding.liveTvTopBar.animate().cancel()
        binding.liveTvTopBar.alpha = 1f

        hideTopBarRunnable?.let { binding.root.removeCallbacks(it) }
        val runnable = Runnable {
            binding.liveTvTopBar.animate().alpha(0f).setDuration(300).start()
        }
        hideTopBarRunnable = runnable
        binding.root.postDelayed(runnable, 3500)
    }

    private fun showChannelOsd(channel: LiveTvChannel) {
        val binding = binding ?: return
        binding.liveTvOsdText.text = channel.name
        binding.liveTvOsdIcon.loadImage(channel.logoUrl)
        showOsdInternal()
    }

    private fun showOsd(text: String) {
        val binding = binding ?: return
        binding.liveTvOsdText.text = text
        binding.liveTvOsdIcon.setImageResource(R.drawable.ic_live_tv_placeholder)
        showOsdInternal()
    }

    private fun showOsdInternal() {
        val binding = binding ?: return
        binding.liveTvOsd.animate().cancel()
        binding.liveTvOsd.visibility = View.VISIBLE
        binding.liveTvOsd.alpha = 1f

        hideOsdRunnable?.let { binding.root.removeCallbacks(it) }
        val runnable = Runnable {
            binding.liveTvOsd.animate().alpha(0f).setDuration(250)
                .withEndAction { binding.liveTvOsd.visibility = View.GONE }.start()
        }
        hideOsdRunnable = runnable
        binding.root.postDelayed(runnable, 1200)
    }

    private fun showError(message: String) {
        val binding = binding ?: return
        binding.liveTvLoading.visibility = View.GONE
        binding.liveTvErrorText.text = message
        binding.liveTvErrorContainer.visibility = View.VISIBLE
    }

    // endregion

    override fun onResume() {
        super.onResume()
        enterFullscreenMode()
        setupTvKeyHandling()
        activity?.attachBackPressedCallback("LiveTvPlayerFragment") {
            goBackToList()
        }
        viewModel.currentChannel()?.let { player?.playWhenReady = true }
        binding?.root?.requestFocus()
    }

    override fun onPause() {
        if (keyEventListener === myKeyEventListener) keyEventListener = null
        exitFullscreenMode()
        player?.playWhenReady = false
        super.onPause()
    }

    override fun onDestroyView() {
        activity?.detachBackPressedCallback("LiveTvPlayerFragment")
        if (keyEventListener === myKeyEventListener) keyEventListener = null

        val binding = binding
        if (binding != null) {
            hideTopBarRunnable?.let { binding.root.removeCallbacks(it) }
            hideOsdRunnable?.let { binding.root.removeCallbacks(it) }
        }

        player?.removeListener(playerListener)
        player?.release()
        player = null
        trackSelector = null
        loadedChannelId = null
        currentMirrorIndex = 0

        super.onDestroyView()
    }
}
