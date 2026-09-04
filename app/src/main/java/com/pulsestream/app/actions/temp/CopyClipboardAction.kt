package com.pulsestream.app.actions.temp

import android.content.Context
import com.pulsestream.app.actions.VideoClickAction
import com.pulsestream.app.ui.result.LinkLoadingResult
import com.pulsestream.app.ui.result.ResultEpisode
import com.pulsestream.app.utils.txt
import com.pulsestream.app.utils.UIHelper.clipboardHelper

class CopyClipboardAction: VideoClickAction() {
    override val name = txt("Copy to clipboard")

    override val oneSource = true

    override fun shouldShow(context: Context?, video: ResultEpisode?) = true

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        if (index == null) return
        val link = result.links.getOrNull(index) ?: return
        clipboardHelper(txt(link.name), link.url)
    }
}