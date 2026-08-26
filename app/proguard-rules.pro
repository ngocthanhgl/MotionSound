# Keep Compose runtime
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
-keepclassmembers class * {
    @androidx.compose.runtime.Stable <fields>;
}

# Keep Coil
-keep class coil.** { *; }
-dontwarn coil.**

# Keep LiteRT GPU delegate (driver binding via reflection)
-keep class org.tensorflow.lite.gpu.** { *; }
-dontwarn org.tensorflow.lite.**

# Keep manifest-referenced classes
-keep class com.motionsound.MainActivity { *; }

# Keep annotations
-keepattributes *Annotation*
-keepattributes Signature

# ONNX Runtime JNI bridges resolve native methods reflectively
-keepclasseswithmembernames class com.motionsound.stem.** {
    native <methods>;
}
