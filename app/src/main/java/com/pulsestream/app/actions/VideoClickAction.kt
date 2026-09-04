package com.pulsestream.app.actions

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.ActivityOptionsCompat
import com.lagradost.api.Log
import com.pulsestream.app.CommonActivity
import com.lagradost.cloudstream3.ErrorLoadingException
import com.pulsestream.app.MainActivity
import com.pulsestream.app.R
import com.pulsestream.app.actions.temp.BiglyBTPackage
import com.pulsestream.app.actions.temp.CopyClipboardAction
import com.pulsestream.app.actions.temp.JustPlayerPackage
import com.pulsestream.app.actions.temp.LibreTorrentPackage
import com.pulsestream.app.actions.temp.MpvExPackage
import com.pulsestream.app.actions.temp.MpvKtPackage
import com.pulsestream.app.actions.temp.MpvKtPreviewPackage
import com.pulsestream.app.actions.temp.MpvPackage
import com.pulsestream.app.actions.temp.MpvRxPackage
import com.pulsestream.app.actions.temp.MpvYTDLPackage
import com.pulsestream.app.actions.temp.NextPlayerPackage
import com.pulsestream.app.actions.temp.OnlyPlayer
import com.pulsestream.app.actions.temp.PlayInBrowserAction
import com.pulsestream.app.actions.temp.PlayMirrorAction
import com.pulsestream.app.actions.temp.ViewM3U8Action
import com.pulsestream.app.actions.temp.VlcNightlyPackage
import com.pulsestream.app.actions.temp.VlcPackage
import com.pulsestream.app.actions.temp.WebVideoCastPackage
import com.pulsestream.app.actions.temp.fcast.FcastAction
import com.lagradost.cloudstream3.mvvm.logError
import com.pulsestream.app.ui.result.LinkLoadingResult
import com.pulsestream.app.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.Coroutines.atomicListOf
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.pulsestream.app.utils.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask

object VideoClickActionHolder {
    val allVideoClickActions = atomicListOf(
        // Default
        PlayInBrowserAction(),
        CopyClipboardAction(),
        ViewM3U8Action(),
        PlayMirrorAction(),
        // main support external apps
        VlcPackage(),
        MpvPackage(),
        MpvExPackage(),
        NextPlayerPackage(),
        JustPlayerPackage(),
        FcastAction(),
        LibreTorrentPackage(),
        BiglyBTPackage(),
        // forks/backup apps
        VlcNightlyPackage(),
        WebVideoCastPackage(),
        MpvYTDLPackage(),
        MpvKtPackage(),
        MpvKtPreviewPackage(),
        OnlyPlayer(),
        MpvRxPackage(),
        // Always Ask option
        AlwaysAskAction(),
        // added by plugins
        // ...
    )

    init {
        Log.d("VideoClickActionHolder", "allVideoClickActions: ${allVideoClickActions.map { it.uniqueId() }}")
    }

    private const val ACTION_ID_OFFSET = 1000

    fun makeOptionMap(activity: Activity?, video: ResultEpisode) = allVideoClickActions
        // We need to have index before filtering
        .mapIndexed { id, it -> it to id + ACTION_ID_OFFSET }
        .filter { it.first.shouldShowSafe(activity, video) }
        .map { it.first.name to it.second }


    fun getActionById(id: Int): VideoClickAction? = allVideoClickActions.getOrNull(id - ACTION_ID_OFFSET)

    fun getByUniqueId(uniqueId: String): VideoClickAction? = allVideoClickActions.firstOrNull { it.uniqueId() == uniqueId }

    fun uniqueIdToId(uniqueId: String?): Int? {
        if (uniqueId == null) return null
        return allVideoClickActions
            .mapIndexed { id, it -> it to id + ACTION_ID_OFFSET }
            .firstOrNull { it.first.uniqueId() == uniqueId }
            ?.second
    }

    fun getPlayers(activity: Activity? = null) = allVideoClickActions.filter { it.isPlayer && it.shouldShowSafe(activity, null) }
}

abstract class VideoClickAction {
    abstract val name: UiText

    /** if true, the app will show dialog to select source - result.links[index] */
    open val oneSource : Boolean = false

    /** if true, this action could be selected as default player (one press action) in settings */
    open val isPlayer: Boolean = false

    /** Which type of sources this action can handle. */
    open val sourceTypes: Set<ExtractorLinkType> = ExtractorLinkType.entries.toSet()

    /** Determines which plugin a given provider is from. This is the full path to the plugin. */
    var sourcePlugin: String? = null

    /** Even if VideoClickAction should not run any UI code, startActivity requires it,
     * this is a wrapper for runOnUiThread in a suspended safe context that bubble up exceptions  */
    @Throws
    suspend fun <T> uiThread(callable : Callable<T>) : T? {
        val future = FutureTask{
            try {
                Result.success(callable.call())
            } catch (t : Throwable) {
                Result.failure(t)
            }
        }
        CommonActivity.activity?.runOnUiThread(future) ?: throw ErrorLoadingException("No UI Activity, this should never happened")
        val result = withContext(Dispatchers.IO) {
            return@withContext future.get()
        }
        return result.getOrThrow()
    }

    /** Internally uses activityResultLauncher,
     * use this when the activity has a result like watched position */
    @Throws
    suspend fun launchResult(intent : Intent?, options : ActivityOptionsCompat? = null) {
        if (intent == null) {
            return
        }

        uiThread {
            MainActivity.activityResultLauncher?.launch(intent,options)
        }
    }

    /** Internally uses startActivity, use this when you don't
     * have any result that needs to be stored when exiting the activity  */
    @Throws
    suspend fun launch(intent : Intent?, bundle : Bundle? = null) {
        if (intent == null) {
            return
        }

        uiThread {
            CommonActivity.activity?.startActivity(intent, bundle)
        }
    }

    fun uniqueId() = "$sourcePlugin:${this::class.qualifiedName}"

    @Throws
    abstract fun shouldShow(context: Context?, video: ResultEpisode?): Boolean

    /** Safe version of shouldShow, as we don't trust extension devs to handle exceptions,
     * however no dev *should* throw in shouldShow */
    fun shouldShowSafe(context: Context?, video: ResultEpisode?): Boolean {
        return try {
            shouldShow(context,video)
        } catch (t : Throwable) {
            logError(t)
            false
        }
    }

    /**
     *  This function is called when the action is clicked.
     *  @param context The current activity
     *  @param video The episode/movie that was clicked
     *  @param result The result of the link loading, contains video & subtitle links
     *  @param index if oneSource is true, this is the index of the selected source
     */
    @Throws
    abstract suspend fun runAction(context: Context?, video: ResultEpisode, result: LinkLoadingResult, index: Int?)

    /** Safe version of runAction, as we don't trust extension devs to handle exceptions */
    fun runActionSafe(context: Context?, video: ResultEpisode, result: LinkLoadingResult, index: Int?) = ioSafe {
        try {
            runAction(context, video, result, index)
        }  catch (_ : NotImplementedError) {
            CommonActivity.showToast("runAction has not been implemented for ${name.asStringNull(context)}, please contact the extension developer of $sourcePlugin", Toast.LENGTH_LONG)
        } catch (error : ErrorLoadingException) {
            CommonActivity.showToast(error.message, Toast.LENGTH_LONG)
        } catch (_: ActivityNotFoundException) {
            CommonActivity.showToast(R.string.app_not_found_error, Toast.LENGTH_LONG)
        } catch (t : Throwable) {
            logError(t)
            CommonActivity.showToast(t.toString(), Toast.LENGTH_LONG)
        }
    }
}
