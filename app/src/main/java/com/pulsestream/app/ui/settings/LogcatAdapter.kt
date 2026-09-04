package com.pulsestream.app.ui.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import com.pulsestream.app.databinding.ItemLogcatBinding
import com.pulsestream.app.ui.BaseDiffCallback
import com.pulsestream.app.ui.NoStateAdapter
import com.pulsestream.app.ui.ViewHolderState

class LogcatAdapter() : NoStateAdapter<String>(
    diffCallback = BaseDiffCallback(
        itemSame = String::equals,
        contentSame = String::equals
    )
) {
    override fun onCreateContent(parent: ViewGroup): ViewHolderState<Any> {
        return ViewHolderState(
            ItemLogcatBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindContent(holder: ViewHolderState<Any>, item: String, position: Int) {
        (holder.view as? ItemLogcatBinding)?.apply {
            logText.text = item
        }
    }
}