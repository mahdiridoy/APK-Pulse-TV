package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources
import android.util.Log
import com.pulsestream.app.actions.VideoClickAction
import com.pulsestream.app.actions.VideoClickActionHolder
import kotlin.Throws

/**
 * Compatibility shim: external .cs3 plugins expect this class at
 * [com.lagradost.cloudstream3.plugins.Plugin] (the original CloudStream3
 * package) so they can `extends Plugin` in their manifest. The real
 * plugin implementation lives in [com.pulsestream.app.plugins.Plugin];
 * this class simply forwards the registration calls to the app's holder.
 */
abstract class Plugin : BasePlugin() {
    /**
     * Called when your Plugin is loaded
     * @param context Context
     */
    @Throws(Throwable::class)
    open fun load(context: Context) {
        // If not overridden by an extension then try the cross-platform load()
        load()
    }

    /**
     * Used to register VideoClickAction instances
     */
    fun registerVideoClickAction(element: VideoClickAction) {
        Log.i(PLUGIN_TAG, "Adding ${element.name} VideoClickAction")
        element.sourcePlugin = this.filename
        VideoClickActionHolder.allVideoClickActions.add(element)
    }

    /**
     * This will contain your resources if you specified requiresResources in gradle
     */
    var resources: Resources? = null

    /**
     * This will add a button in the settings allowing you to add custom settings
     */
    var openSettings: ((context: Context) -> Unit)? = null
}
