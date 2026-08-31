package com.lagradost.cloudstream3.ui.livetv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.core.view.isInvisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage

/**
 * Shared between the main grid browsing screen ([R.layout.item_live_tv_channel_grid]) and the
 * narrow search results panel ([R.layout.item_live_tv_channel]) - both layouts use the same view
 * ids, so plain findViewById (rather than a generated ViewBinding class, which would be tied to
 * one specific layout file) lets a single adapter serve either presentation.
 */
class LiveTvChannelAdapter(
    @LayoutRes private val itemLayoutRes: Int = R.layout.item_live_tv_channel,
    /** Called with the adapter position whenever a row gains D-pad/keyboard focus - a more
     * reliable way to know "what's focused right now" than reactively asking the RecyclerView
     * for its focusedChild at keypress time, since that has shown to be inconsistent. */
    private val onItemFocused: ((position: Int) -> Unit)? = null,
    private val onClick: (position: Int, channel: LiveTvChannel) -> Unit
) : ListAdapter<LiveTvChannel, LiveTvChannelAdapter.ChannelViewHolder>(DiffCallback()) {

    /** Id of the channel currently selected/playing, used to highlight the row. */
    var currentChannelId: String? = null
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    class ChannelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val logo: ImageView = itemView.findViewById(R.id.live_tv_channel_logo)
        val name: TextView = itemView.findViewById(R.id.live_tv_channel_name)
        val group: TextView = itemView.findViewById(R.id.live_tv_channel_group)
        val playingIndicator: View = itemView.findViewById(R.id.live_tv_channel_playing_indicator)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val itemView = LayoutInflater.from(parent.context).inflate(itemLayoutRes, parent, false)
        return ChannelViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        val channel = getItem(position)
        holder.apply {
            name.text = channel.name
            if (!channel.groupTitle.isNullOrBlank()) {
                group.text = channel.groupTitle
                group.visibility = View.VISIBLE
            } else {
                group.visibility = View.GONE
            }

            logo.loadImage(channel.logoUrl) {
                // Falls back silently to the placeholder already set as android:src on failure,
                // coil keeps the previous/placeholder drawable when the request errors.
            }

            playingIndicator.isInvisible = channel.id != currentChannelId

            itemView.setOnClickListener { onClick(bindingAdapterPosition, channel) }
            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onItemFocused?.invoke(bindingAdapterPosition)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<LiveTvChannel>() {
        override fun areItemsTheSame(oldItem: LiveTvChannel, newItem: LiveTvChannel) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: LiveTvChannel, newItem: LiveTvChannel) =
            oldItem == newItem
    }
}
