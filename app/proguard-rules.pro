# Keep Compose runtime
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
-keepclassmembers class * {
    @androidx.compose.runtime.Stable <fields>;
}

# Keep Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep Coil
-keep class coil.** { *; }
-dontwarn coil.**

# Keep Activity/Service launched from manifest
-keep class com.motionsound.MainActivity { *; }
-keep class com.motionsound.service.MusicService { *; }
