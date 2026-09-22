package com.melody.player.playback

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.melody.player.MainActivity
import com.melody.player.R

/**
 * 承载播放器的前台服务。
 *
 * 用 [MediaSessionService] 而不是自己写 Service：通知栏媒体卡片、锁屏控制、
 * 耳机线控、蓝牙按键全部由 Media3 统一接管，UI 侧只需要一个 MediaController。
 *
 * 播放期间服务自动进入前台（foregroundServiceType=mediaPlayback），
 * 所以 Home 键退到后台、甚至划掉任务卡片之后音乐都能继续。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                // 处理音频焦点：来电/其他 App 播放时自动让路
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build()

        // MediaSession.Callback 是无抽象成员的接口，直接空实现即可
        val session = MediaSession.Builder(this, player)
            // 系统侧（通知、锁屏、原子岛的"点击出卡"）点回来时落到 App 上。
            // 不设的话部分系统只会把通知点成"没有任何反应"
            .setSessionActivity(contentIntent(this))
            .setCallback(object : MediaSession.Callback {})
            .build()

        mediaSession = session

        // 必须在 Provider 之前建渠道：Android 不允许事后调高已存在渠道的 importance，
        // 所以那个 HIGH 级别只能在这一刻抢下来（见 CHANNEL_ID）
        ensureChannel(this)
        // 上一版给"原子岛"建过一个独立渠道，功能移除后它只会白白躺在系统通知设置里 —— 删掉
        getSystemService(NotificationManager::class.java)
            .deleteNotificationChannel(ISLAND_CHANNEL_ID)

        // 通知栏渠道名必须传资源 id
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this,
                DefaultMediaNotificationProvider.NotificationIdProvider { NOTIFICATION_ID },
                CHANNEL_ID,
                R.string.notification_channel_name
            ).apply {
                // 状态栏 / 通知头部的小图标：与启动图标同一套五条声波，单色（系统按背景明暗重染）
                setSmallIcon(R.drawable.ic_stat_music)
            }
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        // 没在播放就顺手收掉自己，避免进程常驻
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        /** 快进/快退步长，与界面上的 ±10 秒按钮保持一致。 */
        const val SEEK_STEP_MS = 10_000L

        /**
         * 媒体通知渠道 id。
         *
         * 不沿用旧版的 `melody_music_island`：那个渠道的设置项文案是写给"上岛"功能的，
         * 换新 id 让用户在系统设置里看到的是一个干净、名字正确的渠道
         * （旧渠道在 [onCreate] 里被删除，不会留下僵尸项）。
         */
        const val CHANNEL_ID = "melody_playback"

        /** 上一版（音乐上岛）遗留的渠道 id，仅用于清理删除。 */
        const val ISLAND_CHANNEL_ID = "melody_music_island"

        /** 与 Media3 默认值一致，保持"锁屏只显示一条媒体通知"的既有行为。 */
        const val NOTIFICATION_ID = 1001

        /**
         * 必须在 Provider 构建通知之前调用。
         *
         * Android 不允许事后调高已存在渠道的 importance，所以 HIGH 只能在首次创建时定下来；
         * 渠道已存在时 `createNotificationChannel` 是空操作，之后用户手动调低是他的自由，我们不覆盖。
         */
        private fun ensureChannel(context: android.content.Context) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_description)
                // 媒体通知自带播放控制与进度，不需要渠道再响铃/震动打断
                setSound(null, null)
                enableVibration(false)
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        /** 手动把服务拉起来（App 首次播放时确保进程与前台服务就位）。 */
        fun intent(context: android.content.Context): Intent =
            Intent(context, PlaybackService::class.java).setAction(ACTION_LAUNCH)

        const val ACTION_LAUNCH = "com.melody.player.action.LAUNCH"

        /** 点击通知栏媒体卡片回到 App。 */
        fun contentIntent(context: android.content.Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
