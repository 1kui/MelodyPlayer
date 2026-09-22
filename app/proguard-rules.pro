# 保留 Media3 / Compose 反射入口（当前未开启混淆，保留规则以防后续启用）
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-keep class com.melody.player.playback.PlaybackService { *; }
