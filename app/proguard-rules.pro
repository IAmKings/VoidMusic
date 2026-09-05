# R8 / ProGuard rules — Void Music
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.electrodig.voidmusic.**$$serializer { *; }
-keepclassmembers class com.electrodig.voidmusic.** {
    *** Companion;
}
-keepclasseswithmembers class com.electrodig.voidmusic.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# JNI methods — keep native method declarations
-keepclasseswithmembernames class * {
    native <methods>;
}

# DrumEngine native interface
-keep class com.electrodig.voidmusic.audio.DrumEngine {
    native <methods>;
}

# MediaPipe model loading (uses assets path)
-keep class com.google.mediapipe.** { *; }
# MediaPipe's Graph initializes Flogger via stack inspection. Flogger's factory,
# caller finder, and utility frames must remain separate; R8 inlining any part
# of this chain makes Release builds crash during Graph initialization.
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.mediapipe.proto.**
-dontwarn com.google.mediapipe.framework.GraphProfiler
-dontwarn com.google.mediapipe.framework.Graph

# protobuf-javalite 4.26.1 official shrinker contract. MediaPipe serializes
# generated messages reflectively, so R8 must not rename their backing fields.
-assumevalues class com.google.protobuf.Android {
    static boolean ASSUME_ANDROID return true;
}
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# OpenCV (uses JNI)
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# CameraX
-dontwarn androidx.camera.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory {}
-keepnames class kotlinx.coroutines.android.AndroidExceptionPreHandler {}

# General
-dontwarn javax.annotation.**
-dontwarn java.lang.invoke.**
