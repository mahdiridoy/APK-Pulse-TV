package com.lagradost.cloudstream3.actions.temp

import android.app.Activity
import android.content.Context
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.player.ExtractorUri
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.LOADTYPE_INAPP
import com.lagradost.cloudstream3.ui.player.PlayerSubtitleHelper
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.VideoGenerator
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.txt

class PlayMirrorAction : VideoClickAction() {
    override val name = txt(R.string.episode_action_play_mirror)

    override val oneSource = true

    override val isPlayer = true

    override val sourceTypes: Set<ExtractorLinkType> = LOADTYPE_INAPP

    override fun shouldShow(context: Context?, video: ResultEpisode?) = true

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        val activity = context as? Activity ?: return
        val link = index?.let { result.links[it] }
        val allEpisodes = result.episodes.takeIf { list ->
            list.isNotEmpty() && list.any { it.id == video.id }
        } ?: listOf(video)
        val startIndex = allEpisodes.indexOfFirst { it.id == video.id }.coerceAtLeast(0)
        val responsePage = result.page
        val generatorMirror = object : VideoGenerator<ResultEpisode>(allEpisodes) {
            override val page: LoadResponse? = responsePage
            override val hasCache: Boolean = false
            override val canSkipLoading: Boolean = false
            override fun getId(index: Int): Int = allEpisodes.getOrNull(index)?.id ?: video.id

            override suspend fun generateLinks(
                clearCache: Boolean,
                sourceTypes: Set<ExtractorLinkType>,
                callback: (Pair<ExtractorLink?, ExtractorUri?>) -> Unit,
                subtitleCallback: (SubtitleData) -> Unit,
                offset: Int,
                isCasting: Boolean
            ): Boolean {
                if (offset == startIndex) {
                    index?.let { callback(link to null) }
                    result.subs.forEach { subtitle -> subtitleCallback(subtitle) }
                    return true
                }

                val episode = allEpisodes.getOrNull(offset) ?: return false
                val api = APIHolder.getApiFromNameNull(episode.apiName) ?: return false
                return APIRepository(api).loadLinks(
                    episode.data,
                    isCasting = isCasting,
                    subtitleCallback = { file ->
                        subtitleCallback(PlayerSubtitleHelper.getSubtitleData(file))
                    },
                    callback = { extLink ->
                        if (sourceTypes.contains(extLink.type)) callback(extLink to null)
                    }
                )
            }
        }

        activity.navigate(
            R.id.global_to_navigation_player,
            GeneratorPlayer.newInstance(
                generatorMirror, startIndex, result.syncData
            )
        )
    }
}