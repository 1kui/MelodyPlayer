package com.melody.player.core

/**
 * 磁盘上一份歌词副本的「视图模型」。
 *
 * 它描述的是**一个文件**，而不是某首歌的歌词状态 —— 同一首歌可能有两份副本
 * （用户选定的那份、联网自动匹配的缓存那份），也可能一份都没有。
 */
data class LyricCopyEntry(
    /** 磁盘上的文件名，同时是条目的稳定标识（预览/删除都按它）。 */
    val fileName: String,
    /** 属于哪首歌；索引缺失时为 null —— 这种孤儿副本只能删，关联不回歌曲。 */
    val key: String?,
    val label: String,
    /** `file` = 导入的 .lrc，`online` = 联网取的，`archived` = 归档时的快照，null = 未知。 */
    val origin: LyricOrigin?,
    val savedAtSec: Long = 0L,
    val sizeBytes: Long = 0L,
    /** 联网来源（网易云 / LRCLIB）；非联网来源或老索引里没有时为 null。 */
    val container: String? = null
) {
    /** 自动匹配的缓存 —— 优先级最低的那一档，界面上必须和用户选定的那份区分开。 */
    val autoMatched: Boolean get() = key?.let { LyricCopyKeys.isAuto(it) } == true

    /** 关联的歌曲 key（剥掉自动匹配槽位前缀）。 */
    val songKey: String? get() = key?.let { LyricCopyKeys.baseKeyOf(it) }
}

/** 磁盘上一个歌词副本文件（只有文件系统知道的信息）。 */
data class LyricCopyFile(val name: String, val sizeBytes: Long)

/** 副本索引里的一条记录：把文件名背后的「这是谁」记下来。 */
data class LyricCopyMeta(
    val key: String,
    val label: String,
    val origin: LyricOrigin?,
    val savedAtSec: Long,
    /**
     * 联网来源（如 `网易云` / `LRCLIB`），本地的副本为 null。
     *
     * 单独记一列而不是塞进 [label]：来源要参与逻辑（播放页的来源标注要如实写清是哪一家），
     * 从展示文案里反解析出来既脆弱又没法测。老版本的索引没有这一列，解码时按缺席处理。
     */
    val container: String? = null
)

/**
 * 副本槽位的命名规则。
 *
 * 「自动匹配缓存」必须有自己的槽位，否则它会顶掉用户亲手选定/导入的那份 ——
 * 两者的优先级不同，是两回事。
 */
object LyricCopyKeys {

    const val AUTO_MATCH_PREFIX = "net:"

    fun isAuto(storedKey: String): Boolean = storedKey.startsWith(AUTO_MATCH_PREFIX)

    /** 自动匹配槽位 → 它对应的歌曲 key；其它槽位原样返回。 */
    fun baseKeyOf(storedKey: String): String = storedKey.removePrefix(AUTO_MATCH_PREFIX)
}

/**
 * 副本索引的编解码与「磁盘为准」的合并逻辑。
 *
 * 全是纯函数，可以在 JVM 单测里直接跑 —— 这里最容易出的 bug 是
 * 「把读不到当成不存在」，那会把一份好好的歌词从记录里永久抹掉。
 */
object LyricCopyIndex {

    private const val HEADER = "# melody lyrics index v1"

    // ---------------------------------------------------------------- 编解码

    /**
     * 制表符分隔的行式格式：比 JSON 好读，而且不依赖 Android 的 org.json（单测里它是空壳）。
     *
     * 第 5 列（联网来源）是后加的：解码时按「可有可无」处理，
     * 所以老版本写下的 4 列索引升级后照样读得回来。
     */
    fun encode(metas: List<LyricCopyMeta>): String {
        val sb = StringBuilder(HEADER).append('\n')
        metas.forEach { meta ->
            sb.append(escape(meta.key)).append('\t')
                .append(escape(meta.label)).append('\t')
                .append(meta.origin?.key.orEmpty()).append('\t')
                .append(meta.savedAtSec).append('\t')
                .append(escape(meta.container.orEmpty())).append('\n')
        }
        return sb.toString()
    }

    fun decode(raw: String?): List<LyricCopyMeta> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 4) return@mapNotNull null
                val key = unescape(parts[0]).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                LyricCopyMeta(
                    key = key,
                    label = unescape(parts[1]).ifBlank { key },
                    origin = LyricOrigin.fromKey(parts[2]),
                    savedAtSec = parts[3].trim().toLongOrNull() ?: 0L,
                    container = parts.getOrNull(4)?.let(::unescape)?.takeIf { it.isNotBlank() }
                )
            }
            .toList()
    }

    private fun escape(raw: String): String = buildString(raw.length + 8) {
        raw.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(c)
            }
        }
    }

    private fun unescape(raw: String): String = buildString(raw.length) {
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i == raw.length - 1) {
                append(c)
                i++
                continue
            }
            when (raw[i + 1]) {
                '\\' -> { append('\\'); i += 2 }
                't' -> { append('\t'); i += 2 }
                'n' -> { append('\n'); i += 2 }
                'r' -> { append('\r'); i += 2 }
                else -> { append(c); i++ }
            }
        }
    }

    // ---------------------------------------------------------------- 合并

    /**
     * 以**磁盘为准**合并：目录里每个文件都必须出现在结果里，索引只负责补上「这是谁」。
     *
     * 反方向（索引里有、磁盘上没有）的记录由 [prune] 挑出，调用方据此清理 ——
     * 但只有在**确实成功列出过目录**时才该这么做。
     *
     * @param fileNameToKey 文件名 → key 的映射，调用方用槽位命名规则算出来。
     *                      它同时来自索引和偏好里残留的痕迹，所以老版本（还没有索引）
     *                      保存的副本升级后也能认回歌曲，而不是变成一堆孤儿。
     * @param labelFallback 索引、偏好都查不到时的兜底展示名（通常是曲库里的歌名）。
     */
    fun merge(
        files: List<LyricCopyFile>,
        metas: List<LyricCopyMeta>,
        fileNameToKey: Map<String, String>,
        labelFallback: (String?) -> String? = { null }
    ): List<LyricCopyEntry> {
        val byKey = metas.associateBy { it.key }
        return files.map { file ->
            val key = fileNameToKey[file.name]
            val meta = key?.let { byKey[it] }
            LyricCopyEntry(
                fileName = file.name,
                key = key,
                label = meta?.label
                    ?: labelFallback(key)
                    ?: labelFromFileName(file.name),
                origin = meta?.origin,
                savedAtSec = meta?.savedAtSec ?: 0L,
                sizeBytes = file.sizeBytes,
                container = meta?.container
            )
        }.sortedWith(
            compareByDescending<LyricCopyEntry> { it.savedAtSec }.thenBy { it.fileName }
        )
    }

    /** 索引里指向的文件已经不在磁盘上了，这些记录该丢。 */
    fun prune(metas: List<LyricCopyMeta>, presentKeys: Set<String>): List<LyricCopyMeta> =
        metas.filter { it.key in presentKeys }

    /**
     * 索引丢了时只能从文件名反推一个能看的名。
     *
     * 文件名的形状是 `slug-1a2b3c4d.lrc`（冒号、斜杠、中文都在转义时变成了下划线，
     * 所以还原不回来，只能算个"看得出形状"的兜底）：去掉哈希尾巴与扩展名，
     * 下划线还原成空格。
     */
    fun labelFromFileName(fileName: String): String {
        val stem = fileName.substringBeforeLast('.', fileName)
        return stem.replace(HASH_SUFFIX, "")
            .replace('_', ' ')
            .trim()
            .ifBlank { fileName }
    }

    private val HASH_SUFFIX = Regex("-[0-9a-fA-F]{8}$")
}
