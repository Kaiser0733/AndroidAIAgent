# ProGuard rules for AndroidAIAgent
# Add project-specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the SDK's proguard-android-optimize.txt file.

# Keep Kotlin metadata for reflection-based libs
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, EnclosingMethod

# kotlinx.serialization
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
