# R8 / ProGuard rules — Object Drum Studio
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.electrodig.objectdrumstudio.**$$serializer { *; }
-keepclassmembers class com.electrodig.objectdrumstudio.** {
    *** Companion;
}
-keepclasseswithmembers class com.electrodig.objectdrumstudio.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# JNI methods — keep native method declarations
-keepclasseswithmembernames class * {
    native <methods>;
}

# DrumEngine native interface
-keep class com.electrodig.objectdrumstudio.audio.DrumEngine {
    native <methods>;
}

# MediaPipe model loading (uses assets path)
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.proto.**
-dontwarn com.google.mediapipe.framework.GraphProfiler
-dontwarn com.google.mediapipe.framework.Graph

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

