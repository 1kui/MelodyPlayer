package com.melody.player.data

import android.content.Context
import com.melody.player.core.LyricCopyEntry
import com.melody.player.core.LyricCopyFile
import com.melody.player.core.LyricCopyIndex
import com.melody.player.core.LyricCopyMeta
import com.melody.player.core.LyricOrigin
import java.io.File

/**
 * 歌词的 App 私有副本。
 *
 * 为什么必须要有它：用户从系统文件选择器导入的 `.lrc` 只是一个 `content://` URI，
 * 授权绑在本次会话/该文件上；即使补了 `takePersistableUriPermission`，
 * 用户把那个文件删掉、移动、或者清一次「最近文件」的授权，歌词就没了 ——
 * 表现就是「退出 App 再进来要重新导入」。
 *
 * 所以导入时顺手把**文本内容**复制进 App 私有目录：解析、显示都读这份副本，
 * 原来那个 URI 只作为痕迹保留。副本跟着歌曲的稳定 key 走，重命名文件也不受影响。
 *
 * ## 索引
 *
 * 副本的文件名是由 key 转义来的（`ms:123` → `ms_123-1a2b3c4d.lrc`），**不可逆**：
 * 中文、冒号在转义时全变成了下划线。所以目录里另放一份 `index.txt` 记录
 * 「这个文件是哪首歌的、叫什么、怎么来的」，供「歌词副本管理」界面展示。
 *
 * 索引只是**补充信息**，副本列表始终以磁盘上的文件为准：索引丢了最多让名字退化成
 * 文件名反推的结果，不会让副本"消失"。反过来，只有确实列到过目录时才会按目录裁剪索引。
 */
class LyricsStore(context: Context) {

    private val dir = File(context.filesDir, DIR_NAME).apply { runCatching { mkdirs() } }

    private val indexFile = File(dir, INDEX_NAME)

    fun fileFor(songKey: String): File = File(dir, fileNameFor(songKey))

    /** 原子写入：先写临时文件再改名，避免中途被杀留下半份歌词。 */
    fun save(
        songKey: String,
        text: String,
        label: String? = null,
        origin: LyricOrigin? = null,
        container: String? = null
    ): Boolean = runCatching {
        if (text.isBlank()) return false
        val target = fileFor(songKey)
        val tmp = File(dir, target.name + ".part")
        tmp.writeText(text, Charsets.UTF_8)
        if (target.exists() && !target.delete()) return false
        if (!tmp.renameTo(target)) return false
        upsertMeta(songKey, label, origin, container)
        true
    }.getOrDefault(false)

    fun load(songKey: String): String? = runCatching {
        val file = fileFor(songKey)
        if (file.isFile && file.length() > 0L) file.readText(Charsets.UTF_8) else null
    }.getOrNull()

    /**
     * 这份副本是从哪家联网来的（`网易云` / `LRCLIB`）；本地来的一份返回 null。
     *
     * 读的是索引：磁盘上的 .lrc 只有正文，来源只在索引里 —— 播放页的来源标注据此如实显示，
     * 否则自动匹配的缓存会被一律说成「网易云」，即使它其实来自另一家。
     */
    fun containerOf(songKey: String): String? =
        readIndex().firstOrNull { it.key == songKey }?.container

    fun remove(songKey: String) {
        runCatching { fileFor(songKey).delete() }
        removeMeta(songKey)
    }

    fun count(): Int = runCatching {
        dir.listFiles()?.count { it.isFile && it.name.endsWith(EXTENSION) } ?: 0
    }.getOrDefault(0)

    fun totalBytes(): Long = runCatching {
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(EXTENSION) }?.sumOf { it.length() } ?: 0L
    }.getOrDefault(0L)

    // ---------------------------------------------------------------- 副本管理

    /**
     * 列出磁盘上的全部副本，按保存时间从新到旧。
     *
     * 返回 **null 表示目录没读出来**（而不是「没有副本」）—— 这两种情况的处理完全不同，
     * 调用方在拿到 null 时应当保持原状，绝不能当成"副本都没了"。
     *
     * @param extraKeys 偏好里残留痕迹的槽位。老版本（还没有索引文件）保存的副本靠它认回歌曲。
     * @param labelFallback 索引、偏好都查不到时的兜底展示名（通常是曲库里的歌名）。
     */
    fun list(
        extraKeys: Collection<String> = emptyList(),
        labelFallback: (String?) -> String? = { null }
    ): List<LyricCopyEntry>? {
        val listed = runCatching { dir.listFiles() }.getOrNull() ?: return null
        val files = listed
            .filter { it.isFile && it.name.endsWith(EXTENSION) }
            .map { LyricCopyFile(it.name, it.length()) }

        val metas = readIndex()
        val fileNameToKey = (metas.map { it.key } + extraKeys)
            .distinct()
            .associateBy { fileNameFor(it) }

        // 只有确实列到了目录，才允许按目录裁剪索引；而且只丢"文件确实不在"的记录。
        val presentKeys = files.mapNotNullTo(HashSet()) { file -> fileNameToKey[file.name] }
        val alive = LyricCopyIndex.prune(metas, presentKeys)
        if (alive.size != metas.size) writeIndex(alive)

        return LyricCopyIndex.merge(files, alive, fileNameToKey, labelFallback)
    }

    /** 读一份副本的正文，供预览。 */
    fun readByFileName(fileName: String): String? {
        val file = safeFile(fileName) ?: return null
        return runCatching { if (file.isFile) file.readText(Charsets.UTF_8) else null }.getOrNull()
    }

    /** 删除单份副本（文件 + 索引记录）。目标文件已经不在时也算成功。 */
    fun deleteByFileName(fileName: String): Boolean {
        val file = safeFile(fileName) ?: return false
        val removed = runCatching { !file.exists() || file.delete() }.getOrDefault(false)
        if (!removed) return false
        readIndex().firstOrNull { fileNameFor(it.key) == file.name }?.let { removeMeta(it.key) }
        return true
    }

    /** 清空全部副本，返回 (文件数, 释放字节数)。 */
    fun deleteAll(): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        runCatching {
            dir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                val size = file.length()
                if (file.delete() && file.name.endsWith(EXTENSION)) {
                    count++
                    bytes += size
                }
            }
        }
        writeIndex(emptyList())
        return count to bytes
    }

    // ---------------------------------------------------------------- 索引内部实现

    private fun readIndex(): List<LyricCopyMeta> = runCatching {
        if (indexFile.isFile) LyricCopyIndex.decode(indexFile.readText(Charsets.UTF_8)) else emptyList()
    }.getOrDefault(emptyList())

    private fun writeIndex(metas: List<LyricCopyMeta>) {
        runCatching {
            val tmp = File(dir, INDEX_NAME + ".part")
            tmp.writeText(LyricCopyIndex.encode(metas), Charsets.UTF_8)
            if (indexFile.exists() && !indexFile.delete()) return
            tmp.renameTo(indexFile)
        }
    }

    private fun upsertMeta(songKey: String, label: String?, origin: LyricOrigin?, container: String?) {
        val metas = readIndex()
        val entry = LyricCopyMeta(
            key = songKey,
            label = label?.trim()?.takeIf { it.isNotEmpty() } ?: songKey,
            origin = origin,
            savedAtSec = System.currentTimeMillis() / 1000L,
            container = container?.trim()?.takeIf { it.isNotEmpty() }
        )
        writeIndex(metas.filterNot { it.key == songKey } + entry)
    }

    private fun removeMeta(songKey: String) {
        val metas = readIndex()
        val next = metas.filterNot { it.key == songKey }
        if (next.size != metas.size) writeIndex(next)
    }

    /** 只接受本目录下的直接子文件名，挡掉路径穿越（界面上传进来的名字也是它）。 */
    private fun safeFile(fileName: String): File? {
        if (!fileName.endsWith(EXTENSION)) return null
        if (File(fileName).name != fileName) return null
        return File(dir, fileName)
    }

    companion object {
        private const val DIR_NAME = "lyrics"
        private const val EXTENSION = ".lrc"
        private const val INDEX_NAME = "index.txt"

        /**
         * 偏好键（`ms:123` / `saf:-1234` / `lib:晴天.mp3`）里有冒号、斜杠、中文，
         * 直接当文件名会创建失败或落到别的目录，所以转义成 ASCII 并附上哈希防撞。
         * 纯函数，方便单测。
         */
        fun fileNameFor(songKey: String): String {
            val slug = buildString {
                songKey.forEach { c ->
                    when {
                        c in 'a'..'z' || c in 'A'..'Z' -> append(c.lowercaseChar())
                        c in '0'..'9' || c == '-' || c == '_' -> append(c)
                        else -> append('_')
                    }
                }
            }.take(60)
            return "%s-%08x%s".format(slug, songKey.hashCode(), EXTENSION)
        }
    }
}
