package com.melody.player.data

import android.content.Context

/**
 * 进程内唯一的 [AlbumArtRepository]。
 *
 * 封面缓存**本来就应该只有一份**：界面要读解码后的位图，通知服务要读文件字节，
 * 两边各建一个实例就等于同一张图解两遍、缓存两份。而它只依赖 applicationContext，
 * 没有生命周期问题。
 *
 * 不用依赖注入框架：本工程一处都没有，为一个缓存引入一套 DI 不值得。
 */
object AlbumArt {

    @Volatile
    private var instance: AlbumArtRepository? = null

    fun of(context: Context): AlbumArtRepository = instance ?: synchronized(this) {
        instance ?: AlbumArtRepository(context.applicationContext).also { instance = it }
    }
}
