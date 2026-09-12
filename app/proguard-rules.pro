# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keepclassmembers class * {
    @dagger.hilt.android.qualifiers.ApplicationContext <fields>;
}

# WebView JavaScript Interface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.alal.downloader.feature.browser.** { *; }

# Keep download models for serialization
-keep class com.alal.downloader.core.engine.DownloadState { *; }
-keep class com.alal.downloader.core.engine.DownloadRequest { *; }
-keep class com.alal.downloader.core.engine.DownloadError { *; }
-keep class com.alal.downloader.core.engine.Segment { *; }
-keep class com.alal.downloader.core.data.DownloadEntity { *; }
-keep class com.alal.downloader.core.data.SegmentEntity { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Keep sealed classes
-keep class com.alal.downloader.core.engine.DownloadError$* { *; }

# Compose
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.**

# General
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Keep source file names and line numbers for readable crash traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep Services and BroadcastReceivers (referenced by name in AndroidManifest)
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends androidx.work.Worker

# Keep Parcelable implementations
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
