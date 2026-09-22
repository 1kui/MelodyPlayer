package com.melody.player.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import java.io.File

/**
 * 无封面歌曲的媒体通知占位图：直接把启动图标（渐变底 + 脉冲竖条）渲染成一张
 * PNG 放进私有目录，作为 [androidx.media3.common.MediaMetadata.artworkUri] 的兜底。
 *
 * 为什么需要它：通知栏的大图标槽位来自媒体元数据的封面；这首歌没有封面时
 * MediaStyle 的大图标为空，系统（vivo 的通知面板）会自己画一个"通用音符"占位 ——
 * 用户看到的就是那个旧的紫色音符，误以为是 App 的 logo 没换。把自家的品牌图
 * 喂给大图标槽位，占位显示的就是我们自己的封面，而不是系统的默认图形。
 *
 * 生成一次、永久复用（文件存在即跳过），App 更新后图标变了也无所谓 ——
 * 占位图不必逐版本刷新，真在意可以清数据重建。
 */
object BrandArtwork {

    @Volatile
    private var cached: Uri? = null

    /** 占位图的本地 URI；渲染失败（极端机型）返回 null，通知退回系统默认占位。 */
    fun ensure(context: Context): Uri? {
        cached?.let { return it }
        return runCatching {
            val dir = File(context.filesDir, "brand").apply { mkdirs() }
            val file = File(dir, "notification_artwork.png")
            if (!file.exists() || file.length() == 0L) {
                render(context)?.compress(Bitmap.CompressFormat.PNG, 90, file.outputStream())
            }
            if (file.exists() && file.length() > 0L) {
                Uri.fromFile(file).also { cached = it }
            } else {
                null
            }
        }.getOrNull()
    }

    /**
     * 直接把应用图标画满画布即可：自适应图标的 draw() 自己会按顺序画背景层和前景层
     * （渐变底 + 居中的脉冲竖条），不需要取分层 —— 取分层的
     * getBackground()/getForeground() 是 API 33 才有的。
     * 非自适应图标（老机型 / 第三方 ROM）就画它本身。
     */
    private fun render(context: Context): Bitmap? = runCatching {
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        icon.setBounds(0, 0, size, size)
        icon.draw(canvas)
        bitmap
    }.getOrNull()
}
