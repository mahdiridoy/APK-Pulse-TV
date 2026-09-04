package com.pulsestream.app.ui.download

import android.content.DialogInterface
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import com.google.android.material.snackbar.Snackbar
import com.pulsestream.app.CloudStreamApp.Companion.getKey
import com.pulsestream.app.CloudStreamApp.Companion.getKeys
import com.pulsestream.app.CommonActivity.activity
import com.pulsestream.app.R
import com.lagradost.cloudstream3.mvvm.logError
import com.pulsestream.app.ui.player.DownloadFileGenerator
import com.pulsestream.app.ui.player.ExtractorUri
import com.pulsestream.app.ui.player.GeneratorPlayer
import com.pulsestream.app.utils.AppContextUtils.getNameFull
import com.pulsestream.app.utils.AppContextUtils.setDefaultFocus
import com.pulsestream.app.utils.DOWNLOAD_EPISODE_CACHE
import com.pulsestream.app.utils.DOWNLOAD_HEADER_CACHE
import com.pulsestream.app.utils.SnackbarHelper.showSnackbar
import com.pulsestream.app.utils.UIHelper.navigate
import com.pulsestream.app.utils.downloader.DownloadObjects
import com.pulsestream.app.utils.downloader.DownloadQueueManager
import com.pulsestream.app.utils.downloader.VideoDownloadManager
import kotlinx.coroutines.MainScope

object DownloadButtonSetup {
    fun handleDownloadClick(click: DownloadClickEvent) {
        val id = click.data.id
        when (click.action) {
            DOWNLOAD_ACTION_DELETE_FILE -> {
                activity?.let { ctx ->
                    val builder: AlertDialog.Builder = AlertDialog.Builder(ctx)
                    val dialogClickListener =
                        DialogInterface.OnClickListener { _, which ->
                            when (which) {
                                DialogInterface.BUTTON_POSITIVE -> {
                                    VideoDownloadManager.deleteFilesAndUpdateSettings(
                                        ctx,
                                        setOf(id),
                                        MainScope()
                                    )
                                }

                                DialogInterface.BUTTON_NEGATIVE -> {
                                    // Do nothing on cancel
                                }
                            }
                        }

                    try {
                        builder.setTitle(R.string.delete_file)
                            .setMessage(
                                ctx.getString(R.string.delete_message).format(
                                    ctx.getNameFull(
                                        click.data.name,
                                        click.data.episode,
                                        click.data.season
                                    )
                                )
                            )
                            .setPositiveButton(R.string.delete, dialogClickListener)
                            .setNegativeButton(R.string.cancel, dialogClickListener)
                            .show().setDefaultFocus()
                    } catch (e: Exception) {
                        logError(e)
                        // ye you somehow fucked up formatting did you?
                    }
                }
            }

            DOWNLOAD_ACTION_PAUSE_DOWNLOAD -> {
                VideoDownloadManager.downloadEvent.invoke(
                    Pair(click.data.id, VideoDownloadManager.DownloadActionType.Pause)
                )
            }

            DOWNLOAD_ACTION_RESUME_DOWNLOAD -> {
                activity?.let { ctx ->
                    if (VideoDownloadManager.downloadStatus.containsKey(id) && VideoDownloadManager.downloadStatus[id] == VideoDownloadManager.DownloadType.IsPaused) {
                        VideoDownloadManager.downloadEvent.invoke(
                            Pair(click.data.id, VideoDownloadManager.DownloadActionType.Resume)
                        )
                    } else {
                        val pkg = VideoDownloadManager.getDownloadResumePackage(ctx, id)
                        if (pkg != null) {
                            DownloadQueueManager.addToQueue(pkg.toWrapper())
                        } else {
                            VideoDownloadManager.downloadEvent.invoke(
                                Pair(click.data.id, VideoDownloadManager.DownloadActionType.Resume)
                            )
                        }
                    }
                }
            }

            DOWNLOAD_ACTION_LONG_CLICK -> {
                activity?.let { act ->
                    val length =
                        VideoDownloadManager.getDownloadFileInfo(
                            act,
                            click.data.id
                        )?.fileLength
                            ?: 0
                    if (length > 0) {
                        showSnackbar(
                            act,
                            R.string.offline_file,
                            Snackbar.LENGTH_LONG
                        )
                    }
                }
            }

            DOWNLOAD_ACTION_CANCEL_PENDING -> {
                DownloadQueueManager.cancelDownload(id)
            }

            DOWNLOAD_ACTION_PLAY_FILE -> {
                activity?.let { act ->
                    val parent = getKey<DownloadObjects.DownloadHeaderCached>(
                        DOWNLOAD_HEADER_CACHE,
                        click.data.parentId.toString()
                    ) ?: return

                    val episodes = getKeys(DOWNLOAD_EPISODE_CACHE)
                        ?.mapNotNull {
                            getKey<DownloadObjects.DownloadEpisodeCached>(it)
                        }
                        ?.filter { it.parentId == click.data.parentId }

                    val items = mutableListOf<ExtractorUri>()
                    val allRelevantEpisodes =
                        episodes?.sortedWith(compareBy<DownloadObjects.DownloadEpisodeCached> {
                            it.season ?: 0
                        }.thenBy { it.episode })

                    allRelevantEpisodes?.forEach {
                        val keyInfo = getKey<DownloadObjects.DownloadedFileInfo>(
                            VideoDownloadManager.KEY_DOWNLOAD_INFO,
                            it.id.toString()
                        ) ?: return@forEach

                        items.add(
                            ExtractorUri(
                                // We just use a temporary placeholder for the URI,
                                // it will be updated in generateLinks().
                                // We just do this for performance since getting
                                // all paths at once can be quite expensive.
                                uri = Uri.EMPTY,
                                id = it.id,
                                parentId = it.parentId,
                                name = it.name ?: act.getString(R.string.downloaded_file),
                                season = it.season,
                                episode = it.episode,
                                headerName = parent.name,
                                tvType = parent.type,
                                basePath = keyInfo.basePath,
                                displayName = keyInfo.displayName,
                                relativePath = keyInfo.relativePath,
                            )
                        )
                    }
                    act.navigate(
                        R.id.global_to_navigation_player, GeneratorPlayer.newInstance(
                            DownloadFileGenerator(items),
                            items.indexOfFirst { it.id == click.data.id }
                        )
                    )
                }
            }
        }
    }
}