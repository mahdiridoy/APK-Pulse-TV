package com.pulsestream.app.actions.temp

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.pulsestream.app.actions.OpenInAppAction
import com.pulsestream.app.ui.result.LinkLoadingResult
import com.pulsestream.app.ui.result.ResultEpisode
import com.pulsestream.app.utils.txt

/** https://github.com/devgianlu/Aria2Android */
@Suppress("unused")
class Aria2Package : OpenInAppAction(
    appName = txt("Aria2"),
    packageName = "com.gianlu.aria2android",
    intentClass = "com.gianlu.aria2android.MainActivity"
) {
    override val oneSource: Boolean = true
    override suspend fun putExtra(
        context: Context,
        intent: Intent,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        throw NotImplementedError("Aria2Android is missing getIntent, and onNewIntent, meaning it cant handle intents")
    }

    override fun onResult(activity: Activity, intent: Intent?) = Unit
}