# R8 rules for the release build.
#
# The build type has referenced this file since the module was created and the file was never
# written, so `assembleRelease` failed on every attempt with "supplied proguard configuration
# does not exist" — which is why there has never been a release APK. Everything below is a rule
# the app genuinely needs; libraries that ship their own consumer rules (Compose, Hilt, Room,
# OkHttp, Okio, Health Connect) are deliberately absent, because repeating their keeps here is
# how a rule file rots out of step with the library it was copied from.

# ---------------------------------------------------------------- kotlinx.serialization
#
# The plugin generates a `Companion.serializer()` for every @Serializable class and reaches it
# reflectively at the entry point. R8 cannot see that call, so without these the DTOs shrink away
# and every API response fails to parse — at run time, on a release build, which is the worst
# place to find out.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# The app's own serialisable types: the DTOs the Worker contract is written in, and the models
# they decode into.
-keep,includedescriptorclasses class dev.healthhub.**$$serializer { *; }
-keepclassmembers class dev.healthhub.** {
    *** Companion;
}
-keepclasseswithmembers class dev.healthhub.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ------------------------------------------------------------------------- MapLibre Native
#
# The renderer is C++ behind JNI: the native side looks its Java peers up by name, so anything
# R8 renames is a class the map cannot find at run time. It fails as a blank rectangle with
# working zoom buttons — the same silent shape as the worker-URL trap on the web side.
-keep class org.maplibre.android.** { *; }
-keep interface org.maplibre.android.** { *; }
-keep class com.mapbox.** { *; }
-dontwarn org.maplibre.android.**

# ------------------------------------------------------------------------------ Health Connect
#
# Record types are resolved from KClass at run time by the SDK's own registry. The registry is
# kept by the SDK's consumer rules; the reflection lands on the record classes themselves.
-keep class androidx.health.connect.client.records.** { *; }

# ------------------------------------------------------------------------------------ misc
#
# Nothing in the app catches by class name, so stack traces are the only reason to keep the
# source file attribute — kept because a crash report with `Unknown Source` on every frame is
# not a crash report.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
