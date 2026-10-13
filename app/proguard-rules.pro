# GoIt Transit - ProGuard / R8 Rules

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keepclassmembers class * {
    companion object;
}
-keepclassmembers class * {
    *** Companion;
}
-keepclassmembers class * extends kotlinx.serialization.internal.GeneratedSerializer {
    <init>(...);
}
-keep,allowobfuscation,allowshrinking class com.taha.goit.data.model.** { *; }
-keep,allowobfuscation,allowshrinking class com.taha.goit.data.local.** { *; }
-keep,allowobfuscation,allowshrinking class com.taha.goit.service.** { *; }

# MapLibre GL Native SDK (11.13.5 - 16 KB page-size uyumlu)
-keep class org.maplibre.android.** { *; }
-dontwarn org.maplibre.android.**

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Signature
-keepattributes *Annotation*
-keepclassmembers class okhttp3.internal.publicsuffix.PublicSuffixDatabase {
    private static final byte[] *;
}

# Kotlin Coroutines
-dontwarn kotlinx.coroutines.**
-keepclassmembernames class kotlinx.coroutines.** {
    volatile <fields>;
}

# D-6: Strip debug and verbose logging in release builds
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

