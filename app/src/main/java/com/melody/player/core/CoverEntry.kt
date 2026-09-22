package com.melody.player.core

/**
 * 一张在线获取到的专辑封面的缓存记录。
 *
 * 之所以要**记录"查过但没查到"**（[fileName] 为空）：封面匹配要先搜 iTunes 再挑一条，
 * 一次搜索要几百毫秒。不记负结果的话，每次滚动列表、每次进入播放页都会为一首
 * "本来就搜不到封面"的歌重搜一遍（比如用户自己录的、歌名是 `录音 001`）。
 */
data class CoverEntry(
    /** iTunes 的 trackId；0 表示没匹配上。 */
    val trackId: Long,
    /** App 私有目录里的文件名；空字符串表示"查过、没匹配上"。 */
    val fileName: String,
    /**
     * 来源封面地址（iTunes 的高清地址）。
     *
     * 留着不只是为了展示：系统侧（锁屏、原子随身听）读的是 `MediaMetadata.artworkUri`，
     * 而 App 私有目录里的 `file://` 别的进程读不到 —— 有原始 http 地址时就把 http 地址给它，
     * 这样锁屏封面在图库类系统应用里才显示得出来。
     */
    val sourceUrl: String?,
    val fetchedAtSec: Long
) {

    /** 是否真的缓存到了图。 */
    val matched: Boolean get() = fileName.isNotBlank()
}
