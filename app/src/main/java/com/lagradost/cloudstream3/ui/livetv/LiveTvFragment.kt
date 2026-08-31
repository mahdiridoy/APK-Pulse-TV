package com.lagradost.cloudstream3.ui.livetv

import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.lagradost.cloudstream3.CommonActivity.keyEventListener
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentLiveTvBinding
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.attachBackPressedCallback
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.detachBackPressedCallback
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding

class LiveTvFragment : BaseFragment<FragmentLiveTvBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentLiveTvBinding::inflate)
) {

    private val viewModel: LiveTvViewModel by activityViewModels()

    private var isSearchOpen = false
    private var hasRequestedInitialGridFocus = false
    private var lastFocusedGridPosition = 0
    private val mainAdapter = LiveTvChannelAdapter(
        itemLayoutRes = R.layout.item_live_tv_channel_grid,
        onItemFocused = { position -> lastFocusedGridPosition = position }
    ) { _, channel -> onChannelPicked(channel) }
    private val searchAdapter = LiveTvChannelAdapter { _, channel -> onChannelPicked(channel) }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
    )

    /** At least 3 columns on phone (more on wider/tablet-class screens), exactly 5 on TV. */
    private fun computeGridSpanCount(): Int {
        return if (isLayout(TV or EMULATOR)) {
            5
        } else {
            val screenWidthDp = resources.configuration.screenWidthDp
            (screenWidthDp / 120).coerceAtLeast(3)
        }
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(view, padLeft = isLayout(TV or EMULATOR))
    }

    override fun onBindingCreated(binding: FragmentLiveTvBinding, savedInstanceState: Bundle?) {
        binding.liveTvChannelList.layoutManager = GridLayoutManager(requireContext(), computeGridSpanCount())
        binding.liveTvChannelList.adapter = mainAdapter

        binding.liveTvRefreshButton.setOnClickListener {
            viewModel.refresh(forceRefresh = true, showSpinner = false)
        }
        binding.liveTvSwipeRefresh.setOnRefreshListener {
            viewModel.refresh(forceRefresh = true, showSpinner = false)
        }
        binding.liveTvSearchButton.setOnClickListener { openSearchPanel() }

        binding.liveTvListRetryButton.setOnClickListener {
            binding.liveTvListErrorContainer.visibility = View.GONE
            viewModel.refresh(forceRefresh = true, showSpinner = true)
        }

        setupSearchPanel(binding)
        setupObservers(binding)

        viewModel.loadInitial()

        binding.root.isFocusableInTouchMode = true
    }

    private fun onChannelPicked(channel: LiveTvChannel) {
        val list = viewModel.channels.value ?: return
        val idx = list.indexOfFirst { it.id == channel.id }
        if (idx >= 0) viewModel.selectIndex(idx)
        closeSearchPanel()
        findNavController().navigate(R.id.action_navigation_live_tv_to_navigation_live_tv_player)
    }

    // region Search panel

    private fun setupSearchPanel(binding: FragmentLiveTvBinding) {
        binding.liveTvSearchResults.layoutManager = LinearLayoutManager(requireContext())
        binding.liveTvSearchResults.adapter = searchAdapter

        binding.liveTvSearchClose.setOnClickListener { closeSearchPanel() }
        binding.liveTvSearchScrim.setOnClickListener { closeSearchPanel() }

        binding.liveTvSearchInput.addTextChangedListener { editable ->
            filterSearch(editable?.toString().orEmpty())
        }

        val closeOnSwipeLeft = View.OnTouchListener { v, event ->
            handleSearchPanelSwipe(v, event)
        }
        binding.liveTvSearchDragHandle.setOnTouchListener(closeOnSwipeLeft)

        binding.liveTvOpenSearchEdge.setOnTouchListener { v, event ->
            handleOpenEdgeSwipe(v, event)
        }
    }

    private var openEdgeSwipeStartX = 0f
    private fun handleOpenEdgeSwipe(v: View, event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                openEdgeSwipeStartX = event.x
                true
            }

            MotionEvent.ACTION_UP -> {
                val dx = event.x - openEdgeSwipeStartX
                if (dx < -dp(30f)) {
                    openSearchPanel()
                }
                true
            }

            MotionEvent.ACTION_CANCEL -> true
            else -> true
        }
    }

    private var searchSwipeStartX = 0f
    private fun handleSearchPanelSwipe(v: View, event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                searchSwipeStartX = event.x
                true
            }

            MotionEvent.ACTION_UP -> {
                val dx = event.x - searchSwipeStartX
                if (dx > dp(50f)) {
                    closeSearchPanel()
                }
                true
            }

            MotionEvent.ACTION_CANCEL -> true
            else -> true
        }
    }

    /** Search only shows results once the user has actually typed something - an empty query
     * shows an empty state instead of dumping the entire channel list into the panel. */
    private fun filterSearch(query: String) {
        val binding = binding ?: return
        if (query.isBlank()) {
            searchAdapter.submitList(emptyList())
            binding.liveTvSearchEmpty.visibility = View.GONE
            return
        }

        val all = viewModel.channels.value ?: emptyList()
        val filtered = all.filter { it.name.contains(query, ignoreCase = true) }
        searchAdapter.currentChannelId = viewModel.currentChannel()?.id
        searchAdapter.submitList(filtered)
        binding.liveTvSearchEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openSearchPanel() {
        val binding = binding ?: return
        if (isSearchOpen) return
        isSearchOpen = true
        binding.liveTvSearchInput.text?.clear()
        filterSearch("")
        binding.liveTvSearchPanel.visibility = View.VISIBLE
        binding.liveTvSearchScrim.visibility = View.VISIBLE
        binding.liveTvSearchPanel.animate().translationX(0f).setDuration(220).start()

        if (isLayout(TV or EMULATOR)) {
            binding.liveTvSearchInput.post { binding.liveTvSearchInput.requestFocus() }
        }
    }

    private fun closeSearchPanel() {
        val binding = binding ?: return
        if (!isSearchOpen) return
        isSearchOpen = false

        val panelWidth = binding.liveTvSearchPanel.width.takeIf { it > 0 } ?: dp(300f).toInt()
        binding.liveTvSearchPanel.animate()
            .translationX(panelWidth.toFloat())
            .setDuration(220)
            .withEndAction { binding.liveTvSearchPanel.visibility = View.INVISIBLE }
            .start()
        binding.liveTvSearchScrim.visibility = View.GONE

        if (isLayout(TV or EMULATOR)) {
            moveGridFocus(lastFocusedGridPosition.coerceAtMost(mainAdapter.itemCount - 1))
        }
    }

    // endregion

    // region TV remote key handling (search opens from the right at the end of every visual
    // row, so the panel is always one press away without wrapping through the grid, and the
    // menu rail is reachable from the leftmost column).
    //
    // Note: this app's own getNextFocus() (in CommonActivity) only follows explicit XML
    // nextFocusRight/Up/etc chains - it does not do automatic spatial focus search. Dynamically
    // inflated grid items have no such XML attributes, so "alreadyHandledFocus" is always false
    // here; all actual grid navigation below is done manually via moveGridFocus.

    /** Kept as a direct reference so onPause only clears the global listener if it's still
     * this fragment's own - Fragment transition lifecycle order between the list and player
     * screens isn't strictly guaranteed, so the outgoing fragment's cleanup can otherwise race
     * with and wipe out the incoming fragment's listener right after it's set. */
    private var myKeyEventListener: ((Pair<KeyEvent?, Boolean>) -> Boolean)? = null

    /** Moves grid focus to [position], scrolling it into view first if it isn't currently laid out. */
    private fun moveGridFocus(position: Int) {
        val binding = binding ?: return
        val itemCount = mainAdapter.itemCount
        if (position < 0 || position >= itemCount) return
        val list = binding.liveTvChannelList
        val holder = list.findViewHolderForAdapterPosition(position)
        if (holder != null) {
            holder.itemView.requestFocus()
        } else {
            list.scrollToPosition(position)
            list.post {
                binding.liveTvChannelList.findViewHolderForAdapterPosition(position)?.itemView
                    ?.requestFocus()
            }
        }
    }

    /**
     * The TV rail is the escape route from every Live TV channel.  Do not make people traverse
     * a whole row (or the entire grid) just to reach Movies, Search, Downloads, or Settings.
     */
    private fun focusNavigationRail(): Boolean {
        val railItem = activity?.findViewById<View>(R.id.navigation_live_tv) ?: return false
        railItem.requestFocus()
        return true
    }

    private fun focusLiveTvSearch(): Boolean {
        val searchButton = binding?.liveTvSearchButton ?: return false
        searchButton.requestFocus()
        return true
    }

    private fun setupTvKeyHandling() {
        val listener: (Pair<KeyEvent?, Boolean>) -> Boolean = { pair ->
            val event = pair.first
            if (event == null || event.action != KeyEvent.ACTION_DOWN) {
                false
            } else if (isSearchOpen) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        closeSearchPanel(); true
                    }
                    else -> false
                }
            } else {
                // The activity-wide listener also receives events while the rail and toolbar are
                // focused. Only take over arrows while a channel card actually owns focus.
                val list = binding?.liveTvChannelList
                if (list == null || !list.hasFocus()) {
                    false
                } else when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val spanCount = computeGridSpanCount()
                    val itemCount = mainAdapter.itemCount
                    val pos = lastFocusedGridPosition
                    if (itemCount > 0 && (pos % spanCount == spanCount - 1 || pos >= itemCount - 1)) {
                        // End of a visual row, or the final channel in a partial last row - open search.
                        openSearchPanel()
                    } else if (itemCount > 0) {
                        moveGridFocus(pos + 1)
                    }
                    true
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val spanCount = computeGridSpanCount()
                    val pos = lastFocusedGridPosition
                    if (pos % spanCount == 0) {
                        // Leftmost column - escape to the menu/rail.
                        focusNavigationRail()
                    } else {
                        moveGridFocus(pos - 1)
                    }
                    true
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val spanCount = computeGridSpanCount()
                    val itemCount = mainAdapter.itemCount
                    val target = lastFocusedGridPosition + spanCount
                    if (target < itemCount) {
                        moveGridFocus(target)
                        true
                    } else false // Bottom row - let focus leave the grid normally.
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    val spanCount = computeGridSpanCount()
                    val target = lastFocusedGridPosition - spanCount
                    if (target >= 0) {
                        moveGridFocus(target)
                        true
                    } else {
                        // Up from the first row always lands on the visible Live TV Search
                        // control, making search available with a normal D-pad path.
                        focusLiveTvSearch()
                    }
                }

                else -> false
                }
            }
        }
        myKeyEventListener = listener
        keyEventListener = listener
    }

    // endregion

    private fun setupObservers(binding: FragmentLiveTvBinding) {
        observe(viewModel.channels) { channels ->
            mainAdapter.currentChannelId = viewModel.currentChannel()?.id
            mainAdapter.submitList(channels)
            if (isSearchOpen) filterSearch(binding.liveTvSearchInput.text?.toString().orEmpty())

            binding.liveTvListLoading.visibility = View.GONE
            if (channels.isEmpty() && viewModel.isLoading.value != true) {
                binding.liveTvListErrorContainer.visibility = View.VISIBLE
                binding.liveTvListErrorText.text = getString(R.string.live_tv_no_channels)
            } else {
                binding.liveTvListErrorContainer.visibility = View.GONE
            }

            // TV has no cursor, so D-pad navigation needs an initial focus target to move from -
            // without this, arrow keys do nothing until the user finds some other way to focus
            // a channel first. Only done once so later refreshes don't steal focus away.
            if (channels.isNotEmpty() && !hasRequestedInitialGridFocus && isLayout(TV or EMULATOR)) {
                hasRequestedInitialGridFocus = true
                lastFocusedGridPosition = 0
                moveGridFocus(0)
            }
        }

        observe(viewModel.currentIndex) {
            mainAdapter.currentChannelId = viewModel.currentChannel()?.id
            searchAdapter.currentChannelId = viewModel.currentChannel()?.id
        }

        observe(viewModel.isLoading) { loading ->
            binding?.liveTvListLoading?.visibility = if (loading) View.VISIBLE else View.GONE
        }

        observe(viewModel.isRefreshing) { refreshing ->
            binding?.liveTvSwipeRefresh?.isRefreshing = refreshing
        }

        observe(viewModel.loadError) { error ->
            if (error != null) {
                binding?.liveTvListErrorContainer?.visibility = View.VISIBLE
                binding?.liveTvListErrorText?.text = getString(R.string.live_tv_no_channels)
                viewModel.clearError()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupTvKeyHandling()
        activity?.attachBackPressedCallback("LiveTvFragment") {
            if (isSearchOpen) {
                closeSearchPanel()
            } else {
                runDefault()
            }
        }
        binding?.let { currentBinding ->
            if (isLayout(TV or EMULATOR) && mainAdapter.itemCount > 0) {
                // Do not overwrite the first channel's focus after a cached first launch.
                moveGridFocus(lastFocusedGridPosition.coerceIn(0, mainAdapter.itemCount - 1))
            } else {
                currentBinding.root.requestFocus()
            }
        }
        // Safety net: if the very first load attempt failed (e.g. network not fully ready yet
        // right after a cold app start), this retries instead of leaving the screen stuck empty
        // - viewModel.loadInitial() is itself a no-op once a channel list has actually loaded.
        viewModel.loadInitial()
    }

    override fun onPause() {
        if (keyEventListener === myKeyEventListener) keyEventListener = null
        super.onPause()
    }

    override fun onDestroyView() {
        activity?.detachBackPressedCallback("LiveTvFragment")
        if (keyEventListener === myKeyEventListener) keyEventListener = null
        super.onDestroyView()
    }
}
