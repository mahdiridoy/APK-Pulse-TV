# =====================================================================
# PulseStream — ProGuard / R8 rules
# =====================================================================

# Preserve line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# =====================================================================
# SECURITY: keep code intact but strip logs
# =====================================================================
# NOTE: -dontobfuscate is required because plugins (.cs3 files) are compiled
# against host app class names. R8 renaming breaks plugin class resolution.
-dontobfuscate

# =====================================================================
# Generated missing rules (R8 auto-detect)
# =====================================================================
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn javax.script.ScriptEngineFactory

# =====================================================================
# Kotlin Serialization / Jackson (used by InAppUpdater, etc.)
# =====================================================================
-keepattributes *Annotation*
-dontwarn javax.annotation.**
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.JsonProperty <fields>;
}
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

# =====================================================================
# Keep SitePlugin and PluginWrapper for JSON deserialization (Jackson)
# =====================================================================
-keep class com.pulsestream.app.plugins.SitePlugin { *; }
-keep class com.pulsestream.app.plugins.PluginWrapper { *; }
-keep class com.pulsestream.app.plugins.Repository { *; }
-keep class com.pulsestream.app.plugins.RepositoryData { *; }
-keep class com.pulsestream.app.plugins.PluginData { *; }
-keep class com.pulsestream.app.plugins.PluginWrapper { *; }

# Keep all plugin-related classes for JSON deserialization
-keep class com.pulsestream.app.plugins.** { *; }

# =====================================================================
# Media3 / ExoPlayer (core playback engine)
# =====================================================================
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-keep class com.google.android.exoplayer2.** { *; }
-dontwarn com.google.android.exoplayer2.**

# =====================================================================
# OkHttp (HTTP client + WebSocket for Watch Together)
# =====================================================================
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# =====================================================================
# Coil (image loading)
# =====================================================================
-dontwarn coil.**
-keep class coil.** { *; }

# =====================================================================
# AndroidX / Material
# =====================================================================
-dontwarn androidx.**
-dontwarn com.google.android.material.**

# =====================================================================
# Coroutines
# =====================================================================
-dontwarn kotlinx.coroutines.**

# =====================================================================
# Rhino JavaScript engine (used by provider scrapers)
# =====================================================================
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-keep class org.mozilla.javascript.** { *; }

# =====================================================================
# Core Java APIs not available on Android (referenced by Rhino/etc.)
# =====================================================================
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn java.lang.instrument.**
-dontwarn java.management.**
-dontwarn javax.management.**
-dontwarn javax.naming.**
-dontwarn javax.script.**
-dontwarn javax.security.auth.**
-dontwarn javax.sql.**

# =====================================================================
# Keep ALL CloudStream3 and lagradost classes for extension compatibility
# Extensions (.cs3 files) are loaded at runtime via PathClassLoader with
# the host app's classloader as parent. Extensions reference host app
# classes by their ORIGINAL names. If R8 renames/removes any class that
# an extension references, the extension fails with NoClassDefFoundError
# and its providers never register (-> "Provider test 0/0").
#
# The safest approach is to keep the ENTIRE packages so no class used by
# any extension is ever renamed or stripped.
# =====================================================================
-keep class com.pulsestream.app.** { *; }
-keep class com.lagradost.** { *; }

# Keep FileProvider (needed for APK install via FileProvider)
-keep class androidx.core.content.FileProvider { *; }

# =====================================================================
# Firebase / Google Play Services
# =====================================================================
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# =====================================================================
# Remove logging in release (anti-debug: prevents log-based analysis)
# =====================================================================
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# =====================================================================
# SECURITY: Obfuscate the security class internals
# =====================================================================
-keep class com.pulsestream.app.security.AppSecurity { *; }
-keepclassmembers class com.pulsestream.app.security.** {
    <fields>;
    <methods>;
}
