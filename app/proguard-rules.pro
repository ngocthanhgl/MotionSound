# Keep Compose runtime
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
-keepclassmembers class * {
    @androidx.compose.runtime.Stable <fields>;
}

# Keep Media3 / ExoPlayer
# Media3 removed — using MediaPlayer + MediaSessionCompat
# Keep Coil
-keep class coil.** { *; }
-dontwarn coil.**

# Keep Activity/Service launched from manifest
-keep class com.motionsound.MainActivity { *; }
-keep class com.motionsound.service.MusicService { *; }

# Keep Drive pipeline classes
-keep class com.motionsound.drive.** { *; }
