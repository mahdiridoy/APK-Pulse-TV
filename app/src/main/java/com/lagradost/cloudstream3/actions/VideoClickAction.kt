package com.lagradost.cloudstream3.actions

import android.app.Activity
import android.content.Context
import android.view.View
import com.pulsestream.app.actions.VideoClickAction as AppVideoClickAction
import com.pulsestream.app.actions.VideoClickActionHolder as AppVideoClickActionHolder

/**
 * Compatibility shim: external .cs3 plugins expect VideoClickAction at
 * [com.lagradost.cloudstream3.actions]. This class simply forwards calls to
 * the app's actual implementation in [com.pulsestream.app.actions].
 */
abstract class VideoClickAction : AppVideoClickAction()

/**
 * Compatibility shim for the holder so external plugins can use
 * [com.lagradost.cloudstream3.actions.VideoClickActionHolder.allVideoClickActions].
 */
object VideoClickActionHolder {
    val allVideoClickActions: MutableList<AppVideoClickAction>
        get() = AppVideoClickActionHolder.allVideoClickActions
}
