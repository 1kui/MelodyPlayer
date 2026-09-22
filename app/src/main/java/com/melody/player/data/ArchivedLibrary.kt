package com.melody.player.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.StatFs
import com.melody.player.core.ArchivedEntry
import com.melody.player.core.Song
import com.melody.player.core.tags.LyricsTagWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** 归档失败的原因，直接展示给用户，所以消息要是人话。 */
class ArchiveException(message: String) : Exception(message)

/**
 * App 专属的音乐库目录。
 *
 * 目标是解决「用户误删」：系统媒体库里的文件散落在 /Music、/Download、微信目录下，
 * 任何一个清理工具或手滑都可能删掉。归档把它们复制到 `Android/data/<包名>/files/library/`，
 * 这里只有 App 自己会动。
 *
 * 归档时顺带把歌词写进文件的内嵌标签（能写的话），于是「歌词 + 歌曲」合并成了一个文件 ——
 * 就算以后把文件拷到别处、或者 App 数据被清，歌词也还在文件里。
 *
 * 两个必须如实告诉用户的点：
 *  - 原文件不会被删，只是从列表里隐藏，避免同曲出现两条；
 *  - 卸载 App 会连这个目录一起删掉，所以它防的是「误删」，不是「卸载」。
 */
class ArchivedLibrary(private val context: Context) {

    val dir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, DIR_NAME)

    /**
     * 取归档目录，必要时创建，并顺手清掉上次中途失败留下的 `.part` 半成品。
     *
     * 归档是「先写 .part 再改名」的，进程被杀就会留下半个文件；
     * 它们不会被任何记录引用，留着只会白占空间。
     */
    fun ensureDir(): File = dir.apply {
        runCatching { mkdirs() }
        runCatching {
            listFiles()?.forEach { if (it.isFile && it.name.endsWith(".part")) it.delete() }
        }
    }

    fun fileOf(entry: ArchivedEntry): File = File(dir, entry.name)

    /**
     * 目录里的文件名；**返回 null 表示「读不到目录」而不是「目录是空的」**。
     *
     * 这个区分很重要：调用方会拿它当依据裁剪归档记录，把「一时读不到」当成
     * 「文件都没了」会把记录连带清空 —— 副本还在磁盘上，App 却永久性地忘了它们。
     */
    fun existingNames(): Set<String>? {
        val folder = dir
        if (!folder.isDirectory) return null
        return runCatching {
            folder.listFiles()?.filter { it.isFile }?.mapTo(HashSet()) { it.name }
        }.getOrNull()
    }

    /** 尽力而为的文件名集合，拿不到就当空 —— 只用于生成不重名的文件名。 */
    fun knownNames(): Set<String> = existingNames() ?: emptySet()

    fun totalBytes(): Long = runCatching {
        dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    }.getOrDefault(0L)

    /** 剩余空间；拿不到时返回 -1，调用方据此跳过空间预检而不是直接拒绝。 */
    fun freeBytes(): Long = runCatching {
        ensureDir()
        StatFs(dir.absolutePath).availableBytes
    }.getOrDefault(-1L)

    // ------------------------------------------------------------------ 归档

    /**
     * 把一首歌复制到 App 专属目录，[lyrics] 非空时写进文件内嵌标签。
     *
     * 整个过程在 IO 线程；失败会抛 [ArchiveException]，且不留下半个文件。
     */
    suspend fun archive(song: Song, lyrics: String?, name: String): ArchivedEntry = withContext(Dispatchers.IO) {
        val folder = ensureDir()
        if (!folder.isDirectory) throw ArchiveException("无法创建 App 音乐库目录")

        val safeName = name.takeIf { it.isNotBlank() } ?: "audio.mp3"
        val target = File(folder, safeName)
        if (target.exists()) throw ArchiveException("同名文件已存在")

        val expected = song.sizeBytes.takeIf { it > 0L }
        if (expected != null) {
            val free = freeBytes()
            val need = expected + expected / 8 + 1024L * 1024L
            if (free in 1 until need) throw ArchiveException("存储空间不足")
        }

        val tmp = File(folder, "$safeName.part")
        var embedded = false
        var container: String? = null
        var copied = 0L

        try {
            openInput(song).use { raw ->
                val counting = CountingInputStream(raw)
                val head = readHead(counting)
                if (head.isEmpty()) throw ArchiveException("读不到文件内容")

                val rewrite = lyrics
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        LyricsTagWriter.rewrite(
                            head = head,
                            lyrics = it,
                            meta = LyricsTagWriter.Meta(song.title, song.artist, song.album)
                        )
                    }

                FileOutputStream(tmp).use { out ->
                    if (rewrite != null) {
                        out.write(rewrite.newHead)
                        // head 里 skipBytes 之后的部分是音频数据，不能跟着标签一起丢掉
                        val skip = rewrite.skipBytes.toInt()
                        if (skip < head.size) out.write(head, skip, head.size - skip)
                        embedded = rewrite.embedded
                        container = rewrite.container
                    } else {
                        out.write(head)
                    }
                    counting.copyTo(out, BUFFER_BYTES)
                }
                copied = counting.count
            }

            if (expected != null && copied < expected) throw ArchiveException("复制不完整，请重试")
            if (!tmp.renameTo(target)) throw ArchiveException("无法写入 App 音乐库")
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            if (t is ArchiveException) throw t
            throw ArchiveException(t.message?.take(80) ?: "归档失败")
        }
        lastContainer = container

        ArchivedEntry(
            name = safeName,
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationMs = song.durationMs,
            sizeBytes = target.length(),
            archivedAtSec = System.currentTimeMillis() / 1000L,
            sourceKey = song.key.takeIf { !song.archived },
            lyricsEmbedded = embedded
        )
    }

    /** 上一次归档实际用的容器（仅用于展示，不进偏好）。 */
    @Volatile
    var lastContainer: String? = null
        private set

    // ------------------------------------------------------------------ 读取与清理

    /** 把归档记录还原成曲库里的 [Song]；文件已被删掉的记录返回 null。 */
    fun loadSongs(entries: List<ArchivedEntry>): List<Song> = entries.mapNotNull { entry ->
        val file = File(dir, entry.name)
        if (!file.isFile || !file.canRead()) return@mapNotNull null
        Song(
            id = -1L,
            uri = Uri.fromFile(file).toString(),
            title = entry.title,
            artist = entry.artist,
            album = entry.album,
            durationMs = entry.durationMs,
            sizeBytes = if (entry.sizeBytes > 0L) entry.sizeBytes else file.length(),
            dateAddedSec = entry.archivedAtSec,
            displayName = entry.name,
            mimeType = mimeOf(entry.name),
            filePath = file.absolutePath,
            imported = false,
            managedName = entry.name
        )
    }

    /** 删除整个 App 音乐库，返回 (文件数, 释放字节数)。 */
    fun deleteAll(): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        runCatching {
            dir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                val size = file.length()
                if (file.delete()) {
                    count++
                    bytes += size
                }
            }
        }
        return count to bytes
    }

    /**
     * 给「目录里有文件、记录里却没有」的副本补一条记录。
     *
     * 记录只是索引，目录里的文件才是唯一事实。历史版本在列目录失败时会把记录当垃圾清掉，
     * 或者用户自己把歌拷进这个目录 —— 两种情况的表象都是「文件还在，App 里却看不到」。
     * 这里以目录为准补回记录，标题/歌手尽量从文件标签里读，读不出来就用文件名兜底。
     *
     * 全程 runCatching：补记录失败只该少一首，不该让整次扫描挂掉。
     */
    fun adoptOrphans(entries: List<ArchivedEntry>, names: Set<String>): List<ArchivedEntry> {
        if (names.isEmpty()) return emptyList()
        val known = entries.mapTo(HashSet()) { it.name }
        val adopted = ArrayList<ArchivedEntry>()
        names.forEach { name ->
            if (name in known || !isAudioName(name)) return@forEach
            runCatching {
                val file = File(dir, name)
                if (!file.isFile) return@forEach
                val meta = readMeta(file)
                adopted += ArchivedEntry(
                    name = name,
                    title = meta.title?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.'),
                    artist = meta.artist,
                    album = meta.album,
                    durationMs = meta.durationMs,
                    sizeBytes = file.length(),
                    archivedAtSec = (file.lastModified() / 1000L).coerceAtLeast(0L),
                    // 来源未知：这首可能本来就是用户自己拷进来的
                    sourceKey = null,
                    lyricsEmbedded = false
                )
            }
        }
        return adopted.sortedByDescending { it.archivedAtSec }
    }

    /** 目录里只有音频才值得补记录，`.part` 半成品与别的杂物一律跳过。 */
    private fun isAudioName(name: String): Boolean {
        if (name.endsWith(".part")) return false
        return mimeOf(name) != null
    }

    private class Meta(val title: String?, val artist: String?, val album: String?, val durationMs: Long)

    private fun readMeta(file: File): Meta {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            Meta(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            )
        } catch (_: Throwable) {
            Meta(null, null, null, 0L)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 归档副本建议使用的文件名：优先原始文件名，保证扩展名正确。 */
    fun suggestName(song: Song, taken: Set<String>): String {
        val raw = song.displayName?.takeIf { it.isNotBlank() }
            ?: ((song.title.takeIf { it.isNotBlank() } ?: "audio") + extensionOf(song))
        val cleaned = raw
            .replace(ILLEGAL_CHARS, "_")
            .trim()
            .take(96)
            .ifBlank { "audio" + extensionOf(song) }
        return com.melody.player.core.ArchivedSongs.uniqueName(cleaned, taken)
    }

    private fun extensionOf(song: Song): String {
        song.displayName?.substringAfterLast('.', "")?.takeIf { it.length in 2..5 }?.let { return ".$it" }
        song.mimeType?.let { mime ->
            when {
                mime.contains("mpeg") -> return ".mp3"
                mime.contains("flac") -> return ".flac"
                mime.contains("mp4") || mime.contains("m4a") -> return ".m4a"
                mime.contains("ogg") -> return ".ogg"
                mime.contains("opus") -> return ".opus"
                mime.contains("wav") || mime.contains("x-wav") -> return ".wav"
                mime.contains("aac") -> return ".aac"
            }
        }
        song.uri.substringAfterLast('.', "").takeIf { it.length in 2..5 && it.none { c -> !c.isLetterOrDigit() } }
            ?.let { return ".$it" }
        return ".mp3"
    }

    private fun mimeOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a", "mp4" -> "audio/mp4"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wav" -> "audio/x-wav"
        "aac" -> "audio/aac"
        else -> null
    }

    private fun openInput(song: Song): InputStream {
        song.filePath?.let { path ->
            val file = File(path)
            if (file.isFile && file.canRead()) return file.inputStream()
        }
        val uri = runCatching { Uri.parse(song.uri) }.getOrNull() ?: throw ArchiveException("文件地址无效")
        if (uri.scheme == "file") {
            val file = uri.path?.let { File(it) }
            if (file != null && file.isFile && file.canRead()) return file.inputStream()
        }
        return runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            ?: throw ArchiveException("没有读取权限，请重新扫描媒体库")
    }

    private fun readHead(input: InputStream): ByteArray {
        val buffer = ByteArray(LyricsTagWriter.HEAD_BYTES + 64)
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n <= 0) break
            read += n
        }
        return if (read == buffer.size) buffer else buffer.copyOf(read)
    }

    /** 统计从源文件真实读到的字节数，用来确认复制没有被截断。 */
    private class CountingInputStream(private val delegate: InputStream) : InputStream() {
        var count = 0L
            private set

        override fun read(): Int {
            val value = delegate.read()
            if (value >= 0) count++
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = delegate.read(b, off, len)
            if (n > 0) count += n
            return n
        }

        override fun available(): Int = delegate.available()

        override fun close() {
            runCatching { delegate.close() }
        }
    }

    private companion object {
        const val DIR_NAME = "library"
        const val BUFFER_BYTES = 128 * 1024
        val ILLEGAL_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")
    }
}
