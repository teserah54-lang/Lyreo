# Lyreon — ProGuard rules (release builds)

# Keep NewPipeExtractor model/extractor entry points (reflection + JS engine)
-keep class org.schabi.newpipe.** { *; }
-dontwarn org.schabi.newpipe.**
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**
-keepnames class com.grack.nanojson.** { *; }
-dontwarn com.grack.nanojson.**

# OkHttp / OkIO
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Kotlinx serialization
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <init>(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# slf4j-api (dipakai NewPipeExtractor) — binding NOP fallback by design.
# R8 gagal tanpa ini: Missing class org.slf4j.impl.StaticLoggerBinder.
-dontwarn org.slf4j.**
