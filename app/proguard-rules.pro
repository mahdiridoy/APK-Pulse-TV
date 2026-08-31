# =====================================================================
# PulseStream — ProGuard / R8 rules
# =====================================================================

# Preserve line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

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
# App-specific: Keep data classes that use reflection/serialization
# =====================================================================
-keep class com.lagradost.cloudstream3.ui.livetv.LiveTvChannel { *; }
-keep class com.lagradost.cloudstream3.ui.watchtogether.WatchTogetherManager$RoomInfo { *; }
-keep class com.lagradost.cloudstream3.ui.watchtogether.WatchTogetherManager$RoomUser { *; }
-keep class com.lagradost.cloudstream3.ui.watchtogether.WatchTogetherManager$ContentInfo { *; }
-keep class com.lagradost.cloudstream3.ui.watchtogether.WatchTogetherManager$NavigateInfo { *; }
-keep class com.lagradost.cloudstream3.ui.watchtogether.WatchTogetherManager$ChatMessage { *; }

# Keep the main API classes (search/parsing uses reflection)
-keep class com.lagradost.cloudstream3.MainAPI** { *; }
-keep class com.lagradost.cloudstream3.apis.** { *; }

# Keep FileProvider (needed for APK install via FileProvider)
-keep class androidx.core.content.FileProvider { *; }

# =====================================================================
# Remove logging in release
# =====================================================================
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
