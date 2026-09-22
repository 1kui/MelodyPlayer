package com.melody.player.data

import android.content.Context
import com.melody.player.core.FileNames
import java.io.File
import java.security.MessageDigest

/**
 * 在线专辑封面的 App 私有缓存目录。
 *
 * 和 [LyricsStore] 同一套路子：网络拿到的东西立刻落盘，界面与通知只读本地文件。
 * 这样断网、接口限流、用户清空数据都不会让已经取到的封面消失，
 * 也不会出现"每次进播放页都重新下载一遍"。
 *
 * ## 文件名为什么用 SHA-1 而不是 `hashCode()`
 *
 * [LyricsStore.fileNameFor] 用的是 `key.hashCode()`（Java 的 32 位字符串哈希）。
 * 那里撞了顶多是两份歌词的名字混在一起；这里撞了的后果是**给一首歌贴上另一张专辑的封面**，
 * 而且不会有任何报错 —— 正是这一版最想避免的那类静默错误。
 * 所以这里取 SHA-1 的前 16 个十六进制位（64 位），并保留可读前缀方便排查。
 */
class CoverStore(context: Context) {

    private val dir = File(context.filesDir, DIR_NAME).apply { runCatching { mkdirs() } }

    fun fileFor(songKey: String): File = File(dir, fileNameFor(songKey))

    fun exists(songKey: String): Boolean = runCatching { fileFor(songKey).isFile }.getOrDefault(false)

    fun read(songKey: String): ByteArray? = runCatching {
        val file = fileFor(songKey)
        if (file.isFile && file.length() > 0L) file.readBytes() else null
    }.getOrNull()

    /** 原子写入：先写 `.part` 再改名，避免中途被杀留下半张图（半张图解码出来是花的）。 */
    fun save(songKey: String, bytes: ByteArray): Boolean = runCatching {
        if (bytes.isEmpty()) return false
        val target = fileFor(songKey)
        val tmp = File(dir, target.name + ".part")
        tmp.writeBytes(bytes)
        if (target.exists() && !target.delete()) return false
        if (!tmp.renameTo(target)) return false
        true
    }.getOrDefault(false)

    fun delete(songKey: String): Boolean =
        runCatching { fileFor(songKey).let { !it.exists() || it.delete() } }.getOrDefault(false)

    fun count(): Int = runCatching {
        dir.listFiles()?.count { it.isFile && it.name.endsWith(EXTENSION) } ?: 0
    }.getOrDefault(0)

    fun totalBytes(): Long = runCatching {
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(EXTENSION) }?.sumOf { it.length() } ?: 0L
    }.getOrDefault(0L)

    /** 清空缓存，返回 (删除的文件数, 释放的字节数)。`.part` 残留一并清掉。 */
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
        return count to bytes
    }

    companion object {
        private const val DIR_NAME = "covers"
        private const val EXTENSION = ".jpg"

        /**
         * 纯函数（可单测）：`ms:123` → `ms_123-1a2b3c4d5e6f7081.jpg`。
         *
         * 前缀只是为了在文件管理器/日志里看得懂，真正的唯一性来自后面 64 位的摘要。
         */
        fun fileNameFor(songKey: String): String {
            val slug = FileNames.sanitize(songKey, "cover")
                .replace(' ', '_')
                .take(MAX_SLUG)
                .ifBlank { "cover" }
            return "$slug-${shortDigest(songKey)}$EXTENSION"
        }

        private const val MAX_SLUG = 40

        private fun shortDigest(songKey: String): String = runCatching {
            MessageDigest.getInstance("SHA-1")
                .digest(songKey.toByteArray(Charsets.UTF_8))
                .take(8)
                .joinToString("") { "%02x".format(it) }
        }.getOrElse { "%08x".format(songKey.hashCode()) }
    }
}
