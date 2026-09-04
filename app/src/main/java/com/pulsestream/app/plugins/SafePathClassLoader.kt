package com.pulsestream.app.plugins

import dalvik.system.PathClassLoader

/**
 * Custom ClassLoader for external .cs3 plugins that forces parent-first
 * delegation for all com.lagradost.cloudstream3.* classes.
 *
 * External plugins are compiled against the original CloudStream3 codebase
 * and bundle their own copies of CloudStream3 classes (CloudStreamApp,
 * MainAPI, Plugin, etc.) in their DEX files. On Android, the default
 * PathClassLoader behavior can sometimes resolve these from the plugin's
 * own DEX rather than from the parent classloader, causing:
 *   - NoSuchFieldError (Companion fields missing)
 *   - NoSuchMethodError (methods added in our fork missing)
 *   - ClassCastException (class loaded by wrong classloader)
 *
 * This classloader intercepts loadClass calls and always delegates
 * com.lagradost.cloudstream3.* to the parent, ensuring external plugins
 * use our shim/library versions of these classes.
 */
class SafePathClassLoader(
    dexPath: String,
    parent: ClassLoader?
) : PathClassLoader(dexPath, parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // Force parent-first delegation for all CloudStream3 library classes.
        // This ensures external plugins always use our shim versions of
        // CloudStreamApp, Plugin, BasePlugin, MainAPI, etc.
        if (name.startsWith("com.lagradost.cloudstream3.")) {
            try {
                return parent?.loadClass(name)
                    ?: throw ClassNotFoundException("No parent classloader for $name")
            } catch (_: ClassNotFoundException) {
                // Fall through to default loading if parent doesn't have it
            }
        }
        return super.loadClass(name, resolve)
    }

    /**
     * Preloads a class through this classloader's delegation logic.
     * Forces resolution of shim classes via parent-first delegation before
     * the plugin's DEX is fully initialized, ensuring host shims are cached
     * in the parent classloader before the plugin's bundled copies can be resolved.
     */
    fun preloadShimClass(className: String): Class<*>? {
        return try {
            loadClass(className, false)
        } catch (e: ClassNotFoundException) {
            null
        } catch (e: Throwable) {
            null
        }
    }
}
