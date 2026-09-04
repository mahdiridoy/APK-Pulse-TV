package com.pulsestream.app.ui.settings.extensions

import android.view.LayoutInflater
import android.view.ViewGroup
import com.pulsestream.app.R
import com.pulsestream.app.databinding.RepositoryItemBinding
import com.pulsestream.app.databinding.RepositoryItemTvBinding
import com.pulsestream.app.plugins.RepositoryManager.PREBUILT_REPOSITORIES
import com.pulsestream.app.ui.BaseDiffCallback
import com.pulsestream.app.ui.NoStateAdapter
import com.pulsestream.app.ui.ViewHolderState
import com.pulsestream.app.ui.settings.Globals.TV
import com.pulsestream.app.ui.settings.Globals.isLayout
import com.pulsestream.app.utils.ImageLoader.loadImage
import com.pulsestream.app.utils.UIHelper.clipboardHelper
import com.pulsestream.app.utils.getImageFromDrawable
import com.pulsestream.app.utils.txt

class RepoAdapter(
    val isSetup: Boolean,
    val clickCallback: RepoAdapter.(RepositoryData) -> Unit,
    val imageClickCallback: RepoAdapter.(RepositoryData) -> Unit,
    /** In setup mode the trash icons will be replaced with download icons */
) :
    NoStateAdapter<RepositoryData>(diffCallback = BaseDiffCallback(itemSame = { a, b ->
        a.url == b.url
    })) {

    override fun onCreateContent(parent: ViewGroup): ViewHolderState<Any> {
        val layout = if (isLayout(TV)) RepositoryItemTvBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        ) else RepositoryItemBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolderState(layout)
    }

    override fun onClearView(holder: ViewHolderState<Any>) {
        when (val binding = holder.view) {
            is RepositoryItemBinding -> clearImage(binding.entryIcon)
            is RepositoryItemTvBinding -> clearImage(binding.entryIcon)
        }
    }

    override fun onBindContent(holder: ViewHolderState<Any>, item: RepositoryData, position: Int) {
        val isPrebuilt = PREBUILT_REPOSITORIES.contains(item)
        val drawable =
            if (isSetup) R.drawable.netflix_download else R.drawable.ic_baseline_delete_outline_24
        when (val binding = holder.view) {
            is RepositoryItemTvBinding -> {
                binding.apply {
                    // Only shows icon if on setup or if it isn't a prebuilt repo.
                    // No delete buttons on prebuilt repos.
                    if (!isPrebuilt || isSetup) {
                        actionButton.setImageResource(drawable)
                    }

                    actionButton.setOnClickListener {
                        imageClickCallback(item)
                    }

                    repositoryItemRoot.setOnClickListener {
                        clickCallback(item)
                    }
                    mainText.text = item.name
                    subText.text = item.url
                    if (!item.iconUrl.isNullOrEmpty()) {
                        entryIcon.loadImage(item.iconUrl) {
                            error(
                                getImageFromDrawable(
                                    binding.root.context,
                                    R.drawable.ic_github_logo
                                )
                            )
                        }
                    } else {
                        entryIcon.loadImage(R.drawable.ic_github_logo)
                    }
                }
            }

            is RepositoryItemBinding -> {
                binding.apply {
                    // Only shows icon if on setup or if it isn't a prebuilt repo.
                    // No delete buttons on prebuilt repos.
                    if (!isPrebuilt || isSetup) {
                        actionButton.setImageResource(drawable)
                    }

                    actionButton.setOnClickListener {
                        imageClickCallback(item)
                    }

                    repositoryItemRoot.setOnClickListener {
                        clickCallback(item)
                    }

                    repositoryItemRoot.setOnLongClickListener {
                        val shareableRepoData =
                            "${item.name}$SHAREABLE_REPO_SEPARATOR\n ${item.url}"
                        clipboardHelper(txt(R.string.repo_copy_label), shareableRepoData)
                        true
                    }

                    mainText.text = item.name
                    subText.text = item.url
                    if (!item.iconUrl.isNullOrEmpty()) {
                        entryIcon.loadImage(item.iconUrl) {
                            error(
                                getImageFromDrawable(
                                    binding.root.context,
                                    R.drawable.ic_github_logo
                                )
                            )
                        }
                    } else {
                        entryIcon.loadImage(R.drawable.ic_github_logo)
                    }
                }
            }
        }
    }

    companion object {
        const val SHAREABLE_REPO_SEPARATOR = " : "
    }
}