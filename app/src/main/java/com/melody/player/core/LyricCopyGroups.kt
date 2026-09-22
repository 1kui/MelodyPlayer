package com.melody.player.core

/**
 * 一首歌名下的全部歌词副本。
 *
 * 磁盘上副本是**按槽位**存的：同一首歌可以同时有两份 ——
 * 「用户选定的那份」（导入的 .lrc 或手动选的联网版本）和「联网自动匹配的缓存」。
 * 按份平铺展开时用户看到的是两条孤立的行，看不出它们其实是同一首歌的，
 * 更没法「这首歌的歌词我都不要了」。所以界面上按歌归并。
 */
data class LyricCopyGroup(
    /**
     * 组内副本共有的歌曲 key（已剥掉自动匹配槽位前缀）。
     * null = 索引缺失的孤儿副本，它们认不回歌曲，全部合在 [LyricCopyGroups.ORPHAN_TITLE] 一组里。
     */
    val songKey: String?,
    val title: String,
    val entries: List<LyricCopyEntry>
) {
    val count: Int get() = entries.size

    val sizeBytes: Long get() = entries.sumOf { it.sizeBytes }

    /** 用户选定的那一份 —— 播放时优先用的就是它；没有则为 null。 */
    val selected: LyricCopyEntry? get() = entries.firstOrNull { !it.autoMatched }

    /** 自动匹配留下的缓存份数：删了还会重新匹配，和「选定」那份不是一回事。 */
    val autoCount: Int get() = entries.count { it.autoMatched }

    /** 组里最近一次保存时间，用于排序与展示。 */
    val latestSavedAtSec: Long get() = entries.maxOfOrNull { it.savedAtSec } ?: 0L

    /** 界面上的展开标识：孤儿组也要有个稳定 key。 */
    val expandKey: String get() = songKey ?: ORPHAN_EXPAND_KEY

    companion object {
        /** 孤儿组的展开标识。真实 key 不会为空串（形如 `ms:123` / `lib:xxx.mp3`）。 */
        const val ORPHAN_EXPAND_KEY = "orphan"
    }
}

/**
 * 「按歌曲归并」这一步是纯逻辑，抽出来单测。
 *
 * 出错的后果都不小：分错组会让用户以为某首歌的歌词不见了；
 * 把孤儿副本丢掉更糟 —— 磁盘上明明还有的歌词，界面上就是看不到。
 */
object LyricCopyGroups {

    /** 认不回歌曲的副本（索引丢了、偏好痕迹也没了）统一归到这一组。 */
    const val ORPHAN_TITLE = "未关联的副本"

    /**
     * 按歌曲归并副本。
     *
     * - 组的顺序：**未关联的排最后**，其余按组内最近保存时间从新到旧；
     * - 组内顺序：用户选定的那份排最前（它是播放时真正生效的那份），其余按时间从新到旧；
     * - 组名优先用曲库里的歌名（[titleOf]），查不到才退回副本标签里的歌名前缀。
     *
     * [titleOf] 由调用方注入（曲库视图模型手里才有完整的 key → 歌名对照表），
     * 所以这个对象本身不依赖 Android，能在 JVM 单测里直接跑。
     */
    fun group(
        entries: List<LyricCopyEntry>,
        titleOf: (String) -> String? = { null }
    ): List<LyricCopyGroup> {
        if (entries.isEmpty()) return emptyList()

        // LinkedHashMap 而不是 groupBy：同一次刷新里的相对顺序保持稳定，界面不会自己跳来跳去
        val buckets = LinkedHashMap<String?, MutableList<LyricCopyEntry>>()
        entries.forEach { entry ->
            buckets.getOrPut(entry.songKey) { mutableListOf() }.add(entry)
        }

        return buckets
            .map { (key, list) ->
                val ordered = list.sortedWith(
                    compareBy<LyricCopyEntry> { it.autoMatched }      // 选定那份在前
                        .thenByDescending { it.savedAtSec }
                        .thenBy { it.fileName }
                )
                LyricCopyGroup(songKey = key, title = groupTitle(key, ordered, titleOf), entries = ordered)
            }
            .sortedWith(
                compareBy<LyricCopyGroup> { it.songKey == null }  // 未关联的沉底
                    .thenByDescending { it.latestSavedAtSec }
                    .thenBy { it.title }
            )
    }

    /**
     * 组名。名字里刻意不叫 `titleOf` —— 那会和 [group] 的入参 lambda 撞名，
     * 调用处解析到的是 lambda 而不是这个函数（编译器只会报「参数个数不对」）。
     */
    private fun groupTitle(key: String?, entries: List<LyricCopyEntry>, lookup: (String) -> String?): String {
        if (key == null) return ORPHAN_TITLE
        lookup(key)?.takeIf { it.isNotBlank() }?.let { return it }
        // 曲库里查不到（原文件被删、媒体库还没扫到、授权失效）：退回副本标签。
        // 标签一律是「歌名 · 来源说明」拼出来的，所以第一个「 · 」之前就是歌名。
        // 歌名自己带「 · 」时会被截短 —— 已知的降级，但仍然比显示一堆槽位名强。
        entries.firstNotNullOfOrNull { entry ->
            entry.label.substringBefore(LABEL_SEPARATOR).trim().takeIf { it.isNotBlank() }
        }?.let { return it }
        return FALLBACK_TITLE
    }

    private const val LABEL_SEPARATOR = " · "
    private const val FALLBACK_TITLE = "未知曲目"
}
