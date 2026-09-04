package com.pulsestream.app.ui.library

import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.doOnAttach
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView.OnFlingListener
import com.google.android.material.appbar.AppBarLayout
import com.pulsestream.app.R
import com.pulsestream.app.databinding.LibraryViewpagerPageBinding
import com.pulsestream.app.syncproviders.SyncAPI
import com.pulsestream.app.ui.BaseAdapter
import com.pulsestream.app.ui.BaseDiffCallback
import com.pulsestream.app.ui.ViewHolderState
import com.pulsestream.app.ui.home.getSafeParcelable
import com.pulsestream.app.ui.search.SearchClickCallback
import com.pulsestream.app.ui.settings.Globals.EMULATOR
import com.pulsestream.app.ui.settings.Globals.TV
import com.pulsestream.app.ui.settings.Globals.isLayout
import com.pulsestream.app.utils.UIHelper.getSpanCount

class ViewpagerAdapterViewHolderState(val binding: LibraryViewpagerPageBinding) :
    ViewHolderState<Bundle>(binding) {
    override fun save(): Bundle =
        Bundle().apply {
            putParcelable(
                "pageRecyclerview",
                binding.pageRecyclerview.layoutManager?.onSaveInstanceState()
            )
        }

    override fun restore(state: Bundle) {
        state.getSafeParcelable<Parcelable>("pageRecyclerview")?.let { recycle ->
            binding.pageRecyclerview.layoutManager?.onRestoreInstanceState(recycle)
        }
    }
}

class ViewpagerAdapter(
    val scrollCallback: (isScrollingDown: Boolean) -> Unit,
    val clickCallback: (SearchClickCallback) -> Unit
) : BaseAdapter<SyncAPI.Page, Bundle>(
    id = "ViewpagerAdapter".hashCode(),
    diffCallback = BaseDiffCallback(
        itemSame = { a, b ->
            a.title == b.title
        },
        contentSame = { a, b ->
            a.items == b.items && a.title == b.title
        }
    )) {

    override fun onCreateContent(parent: ViewGroup): ViewHolderState<Bundle> {
        return ViewpagerAdapterViewHolderState(
            LibraryViewpagerPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
    }

    override fun onUpdateContent(
        holder: ViewHolderState<Bundle>,
        item: SyncAPI.Page,
        position: Int
    ) {
        val binding = holder.view
        if (binding !is LibraryViewpagerPageBinding) return
        (binding.pageRecyclerview.adapter as? PageAdapter)?.submitList(item.items)
        binding.pageRecyclerview.scrollToPosition(0)
    }

    override fun onBindContent(holder: ViewHolderState<Bundle>, item: SyncAPI.Page, position: Int) {
        val binding = holder.view
        if (binding !is LibraryViewpagerPageBinding) return

        binding.pageRecyclerview.tag = position
        binding.pageRecyclerview.apply {
            spanCount = binding.root.context.getSpanCount()
            if (adapter == null) { //  || rebind
                // Only add the items after it has been attached since the items rely on ItemWidth
                // Which is only determined after the recyclerview is attached.
                // If this fails then item height becomes 0 when there is only one item
                doOnAttach {
                    adapter = PageAdapter(
                        this,
                        clickCallback
                    ).apply {
                        submitList(item.items)
                    }
                }
            } else {
                (adapter as? PageAdapter)?.submitList(item.items)
                // scrollToPosition(0)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                    val diff = scrollY - oldScrollY

                    //Expand the top Appbar based on scroll direction up/down, simulate phone behavior
                    if (isLayout(TV or EMULATOR)) {
                        binding.root.rootView.findViewById<AppBarLayout>(R.id.search_bar)
                            ?.apply {
                                if (diff <= 0)
                                    setExpanded(true)
                                else
                                    setExpanded(false)
                            }
                    }
                    if (diff == 0) return@setOnScrollChangeListener

                    scrollCallback.invoke(diff > 0)
                }
            } else {
                onFlingListener = object : OnFlingListener() {
                    override fun onFling(velocityX: Int, velocityY: Int): Boolean {
                        scrollCallback.invoke(velocityY > 0)
                        return false
                    }
                }
            }
        }
    }
}