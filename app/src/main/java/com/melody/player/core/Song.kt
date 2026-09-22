package com.melody.player.core

/**
 * 一首本地歌曲。刻意做成纯 Kotlin 数据类（不引用任何 Android 类型），
 * 这样排序/筛选/搜索这些最容易出 bug 的逻辑可以在 JVM 单测里直接跑。
 */
data class Song(
    /** MediaStore 的 _ID，SAF 导入的歌曲为 -1。 */
    val id: Long,
    /** 播放用的 URI 字符串（content:// 或 file://）。 */
    val uri: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val displayName: String? = null,
    val mimeType: String? = null,
    /** MediaStore 暴露的真实路径，可能为 null；用于查找同名 .lrc 与走文件快速读取。 */
    val filePath: String? = null,
    /** 是否来自用户手动导入（SAF），这类歌曲不在 MediaStore 曲库里。 */
    val imported: Boolean = false,
    /**
     * 已归档到 App 专属目录的文件名（相对 [com.melody.player.data.ArchivedLibrary] 的根目录）。
     *
     * 非 null 即表示这首歌的「主副本」由 App 自己掌握，不再依赖系统媒体库。
     */
    val managedName: String? = null
) {
    /** 稳定的偏好存储键。归档曲目用文件名，它不会随媒体库重新扫描而变。 */
    val key: String get() = when {
        managedName != null -> "lib:$managedName"
        id >= 0 -> "ms:$id"
        else -> "saf:${uri.hashCode()}"
    }

    /** 是否已归档到 App 专属目录。 */
    val archived: Boolean get() = managedName != null

    val artistOrUnknown: String get() = artist?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "未知歌手"

    val albumOrUnknown: String get() = album?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "未知专辑"

    /** 用标题与歌手算一个稳定的色调索引，用于生成程序化封面（避免依赖位图资源）。 */
    val artworkSeed: Int get() = (title.hashCode() * 31 + artistOrUnknown.hashCode()) and 0x7FFFFFFF
}

/** 曲库排序方式。 */
enum class SortMode(val label: String) {
    TITLE("按标题"),
    ARTIST("按歌手"),
    ALBUM("按专辑"),
    RECENT("最近添加"),
    DURATION("按时长")
}

/**
 * 一条「已隐藏」记录。
 *
 * 只存 [key]（[Song.key]：MediaStore 的 `ms:<id>` 或 SAF 的 `saf:<hash>`）就够判断，
 * 但标题与歌手也要留一份 —— 恢复列表里得让用户看清自己当初隐藏的是哪首，
 * 而那时它已经不在曲库里了。
 */
data class HiddenSongEntry(val key: String, val title: String, val artist: String?) {
    companion object {
        fun of(song: Song) = HiddenSongEntry(song.key, song.title, song.artistOrUnknown)
    }
}

/**
 * 隐藏曲目的过滤与增删。
 *
 * 抽成纯函数是因为这里有真实的边界：曲目被重命名或重新扫描后 [Song.key] 不变
 * （MediaStore id / SAF URI 哈希稳定），所以必须按 key 判断而不是按标题；
 * 另外恢复时的顺序要可预期，不能让列表随手一 filter 就乱掉。
 */
object HiddenSongs {

    fun visible(songs: List<Song>, entries: List<HiddenSongEntry>): List<Song> {
        if (entries.isEmpty()) return songs
        val hidden = entries.mapTo(HashSet()) { it.key }
        return songs.filterNot { it.key in hidden }
    }

    /** 新隐藏的排在最前，重复的先去重 —— 最近隐藏的曲目在恢复列表里最好找。 */
    fun add(entries: List<HiddenSongEntry>, song: Song): List<HiddenSongEntry> =
        listOf(HiddenSongEntry.of(song)) + entries.filterNot { it.key == song.key }

    fun remove(entries: List<HiddenSongEntry>, key: String): List<HiddenSongEntry> =
        entries.filterNot { it.key == key }

    /** 曲库里已经找不到的条目（文件被删了），用于在恢复列表里如实标注。 */
    fun orphans(entries: List<HiddenSongEntry>, songs: List<Song>): List<HiddenSongEntry> {
        val present = songs.mapTo(HashSet()) { it.key }
        return entries.filterNot { it.key in present }
    }
}

/**
 * 一条「已归档到 App 专属目录」的记录。
 *
 * 存的是**文件名**而不是绝对路径：App 专属目录的前缀会随设备/用户/卸载重装而变，
 * 只有相对文件名是稳定的。
 */
data class ArchivedEntry(
    val name: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val sizeBytes: Long,
    val archivedAtSec: Long,
    /** 来源曲目的 [Song.key]；用于判断「这首已经归档过了」，可为 null（外部放入的文件）。 */
    val sourceKey: String? = null,
    /** 归档时是否成功把歌词写进了文件内嵌标签。 */
    val lyricsEmbedded: Boolean = false
)

/**
 * 归档记录与曲库的对照逻辑。
 *
 * 抽成纯函数是为了能单测：这里全是「集合比对 + 去重」的真实边界，
 * 出错的后果是重复归档（白占空间）或把仍在用的记录误删。
 */
object ArchivedSongs {

    /** 已经归档过的来源 key 集合。 */
    fun archivedSourceKeys(entries: List<ArchivedEntry>): Set<String> =
        entries.mapNotNull { it.sourceKey }.toSet()

    /**
     * 「已经被 App 库副本取代」的来源 key —— 只包含**副本确实在列表里的**那些。
     *
     * 刻意要求 [copies] 里能查到对应的副本：归档记录与磁盘文件可能不同步
     * （副本被删、目录一时读不到），此时必须让原曲回到列表，
     * 否则用户看到的是「歌全没了」而不是「少了一首」。
     */
    fun supersededSourceKeys(entries: List<ArchivedEntry>, copies: List<Song>): Set<String> {
        if (entries.isEmpty() || copies.isEmpty()) return emptySet()
        val present = copies.mapNotNullTo(HashSet()) { it.managedName }
        if (present.isEmpty()) return emptySet()
        return entries.filter { it.name in present }.mapNotNullTo(HashSet()) { it.sourceKey }
    }

    /**
     * 摘掉历史版本写进隐藏列表的归档来源记录。
     *
     * 旧版把「已归档的来源曲目」当成隐藏处理，归档整库之后「已隐藏的曲目」里就是整张曲库。
     * 归档与隐藏必须是两件事，所以这些记录在启动时清掉 —— 来源曲目的去留由
     * [supersededSourceKeys] 决定，而不是靠隐藏。
     */
    fun dropSupersededHidden(
        hidden: List<HiddenSongEntry>,
        entries: List<ArchivedEntry>
    ): List<HiddenSongEntry> {
        if (hidden.isEmpty() || entries.isEmpty()) return hidden
        val sources = archivedSourceKeys(entries)
        if (sources.isEmpty()) return hidden
        return hidden.filterNot { it.key in sources }
    }

    /** 文件已经不在磁盘上的记录（用户手删，或清理了一半）。 */
    fun orphans(entries: List<ArchivedEntry>, existingNames: Set<String>): List<ArchivedEntry> =
        entries.filterNot { it.name in existingNames }

    /**
     * 生成不冲突的文件名：重名时在扩展名前追加 ` (2)`、` (3)`……
     *
     * 直接覆盖会丢掉用户已经归档过的副本，所以宁可换个名字。
     */
    fun uniqueName(desired: String, taken: Set<String>): String {
        if (desired.isBlank()) return desired
        if (desired !in taken) return desired
        val dot = desired.lastIndexOf('.')
        val hasExt = dot > 0 && dot < desired.length - 1
        val base = if (hasExt) desired.substring(0, dot) else desired
        val ext = if (hasExt) desired.substring(dot) else ""
        var i = 2
        while ("$base ($i)$ext" in taken) i++
        return "$base ($i)$ext"
    }
}

/**
 * 搜索 + 排序。抽成纯函数，单测覆盖大小写、全角空格、空歌手、稳定排序等边界。
 */
object SongQuery {

    fun apply(songs: List<Song>, query: String, sort: SortMode): List<Song> {
        val filtered = filter(songs, query)
        return sortBy(filtered, sort)
    }

    fun filter(songs: List<Song>, query: String): List<Song> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return songs
        return songs.filter { song ->
            song.title.lowercase().contains(q) ||
                song.artistOrUnknown.lowercase().contains(q) ||
                song.albumOrUnknown.lowercase().contains(q) ||
                (song.displayName?.lowercase()?.contains(q) == true)
        }
    }

    fun sortBy(songs: List<Song>, sort: SortMode): List<Song> = when (sort) {
        // 统一用小写键比较，避免依赖平台 collator，中文与英文的排序行为都可预期
        SortMode.TITLE -> songs.sortedWith(compareBy({ it.title.lowercase() }))
        SortMode.ARTIST -> songs.sortedWith(
            compareBy({ it.artistOrUnknown.lowercase() }, { it.title.lowercase() })
        )
        SortMode.ALBUM -> songs.sortedWith(
            compareBy({ it.albumOrUnknown.lowercase() }, { it.title.lowercase() })
        )
        SortMode.RECENT -> songs.sortedWith(
            compareByDescending<Song> { it.dateAddedSec }.thenBy { it.title.lowercase() }
        )
        SortMode.DURATION -> songs.sortedWith(
            compareBy<Song> { it.durationMs }.thenBy { it.title.lowercase() }
        )
    }
}
