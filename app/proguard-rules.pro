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

# Keep ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Keep manifest-referenced classes
-keep class com.motionsound.MainActivity { *; }

# Keep annotations
-keepattributes *Annotation*
-keepattributes Signature

# ONNX Runtime JNI bridges resolve native methods reflectively
-keepclasseswithmembernames class com.motionsound.stem.** {
    native <methods>;
}
