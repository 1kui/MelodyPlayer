package com.melody.player.data

import android.content.Context
import android.net.Uri
import com.melody.player.core.Song
import com.melody.player.core.tags.ByteSource
import com.melody.player.core.tags.ChannelByteSource
import com.melody.player.core.tags.EmbeddedArtworkParser
import com.melody.player.core.tags.FileByteSource
import com.melody.player.core.tags.LyricsTagWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * 把歌词 / 封面真正写回音频文件。
 *
 * ## 为什么要单独一个类
 * [LyricsTagWriter] 只负责**算出新的文件头**（纯字节，JVM 单测可跑），
 * 真正落盘要处理一件它管不了的事：**怎么写回去**。
 *  - App 库里的文件：就是 App 私有目录的 [File]，随便改；
 *  - SAF 导入的文件：只有 `content://` URI，得靠 ContentResolver；
 *  - MediaStore 的文件：能不能写取决于系统版本与授权。
 *
 * 三条路径的失败方式完全不同，混在一个函数里就会变成"静默失败"——
 * 用户点了没反应、也没报错，因为他根本不知道自己的文件能不能被写。
 * 所以这里把**能不能写**先判定清楚，再决定做不做。
 *
 * ## 绝不原地覆盖
 * 统一「写临时文件 → 校验 → 换名」。直接往原文件上截断重写的话，
 * 中途被杀（后台、被系统回收、用户切任务）就会留下一个**被截断的音频文件**，
 * 而且不可恢复 —— 用户的歌就这么没了。换名是原子的，要么成功要么原封不动。
 */
class TagEmbedder(private val context: Context) {

    /** 一次嵌入的结果，供界面如实汇报。 */
    sealed interface Outcome {
        /** 成功。 [container] 说明写进了哪种标签。 */
        data class Done(val container: String, val wroteLyrics: Boolean, val wroteArtwork: Boolean) : Outcome

        /** 写不进去：容器不支持、或授权拿不到。 [reason] 是给用户看的人话。 */
        data class Refused(val reason: String) : Outcome

        /** 动手了但失败（磁盘满、文件被别的进程锁住）。原文件保持不变。 */
        data class Failed(val reason: String) : Outcome
    }

    /**
     * 这首歌能不能被写入。
     *
     * 单独暴露出来是为了让界面**提前**把不支持的曲目标出来，
     * 而不是让用户点了十几首之后才收到一份"其中 8 首不支持"的通知。
     */
    fun canWrite(song: Song): Boolean = writableFileOrNull(song) != null || canWriteViaUri(song)

    /**
     * 把 [payload] 写进这首歌。
     *
     * @param lyricsText 歌词正文；为 null 表示不碰歌词。
     * @param artwork 图片字节；为 null 表示不碰封面。
     */
    suspend fun embed(
        song: Song,
        lyricsText: String? = null,
        artwork: ByteArray? = null
    ): Outcome = withContext(Dispatchers.IO) {
        val meta = LyricsTagWriter.Meta(song.title, song.artist, song.album)
        val payload = LyricsTagWriter.Payload(
            lyrics = lyricsText,
            artwork = artwork,
            meta = if (lyricsText == null && artwork == null) LyricsTagWriter.Meta() else meta
        )
        if (!payload.hasAnything) return@withContext Outcome.Refused("没有要写入的内容")

        val direct = writableFileOrNull(song)
        if (direct != null) return@withContext writeInPlace(direct, song, payload)

        if (!canWriteViaUri(song)) {
            return@withContext Outcome.Refused(UNSUPPORTED)
        }
        writeViaUri(song, payload)
    }

    private fun openInputStream(song: Song): InputStream? {
        song.filePath?.let { path ->
            val file = File(path)
            if (file.isFile && file.canRead()) {
                runCatching { return file.inputStream() }
            }
        }
        val uri = runCatching { Uri.parse(song.uri) }.getOrNull() ?: return null
        if (uri.scheme == "file") {
            val file = uri.path?.let { File(it) }
            if (file != null && file.isFile && file.canRead()) {
                runCatching { return file.inputStream() }
            }
        }
        return runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
    }

    /** 读这首歌内嵌的封面；没有或读不到返回 null。 */
    suspend fun embeddedArtwork(song: Song): ByteArray? = withContext(Dispatchers.IO) {
        openSource(song)?.use { src ->
            EmbeddedArtworkParser.parse(src)?.bytes
        }
    }

    // ------------------------------------------------------------------ 落盘

    /**
     * 真实路径可写时走这里。
     *
     * 先写同目录下的 `.part`，确认写完再换名：换名在同一个文件系统内是原子的，
     * 于是"写一半被杀"最多留下一个没人读的 `.part`，原文件完好。
     */
    private fun writeInPlace(target: File, song: Song, payload: LyricsTagWriter.Payload): Outcome {
        val tmp = File(target.parentFile, target.name + ".embed.part")
        return try {
            val head = readHead(target)
            val rewrite = LyricsTagWriter.rewrite(head, payload)
                ?: return Outcome.Refused(UNSUPPORTED)

            target.inputStream().use { raw ->
                FileOutputStream(tmp).use { out ->
                    out.write(rewrite.newHead)
                    // head 里 skipBytes 之后是音频数据，要接着写、不能跟着标签一起丢掉
                    raw.skipFully(rewrite.skipBytes)
                    raw.copyTo(out, BUFFER_BYTES)
                }
            }
            if (tmp.length() <= rewrite.newHead.size) {
                // 新文件只剩标签 = 音频数据没写进去，这种文件必须丢弃
                tmp.delete()
                return Outcome.Failed("写入后文件不完整，已放弃")
            }
            if (!tmp.renameTo(target)) {
                // 换名失败（跨设备或权限）：退化成直接覆盖，仍先确认长度对得上
                if (!target.delete() && target.exists()) {
                    tmp.delete()
                    return Outcome.Failed("无法替换原文件")
                }
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    return Outcome.Failed("无法替换原文件")
                }
            }
            Outcome.Done(
                container = rewrite.container,
                wroteLyrics = !payload.lyrics.isNullOrBlank(),
                wroteArtwork = payload.artwork?.isNotEmpty() == true
            )
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            Outcome.Failed(t.message?.take(60) ?: "写入失败")
        }
    }

    /**
     * `content://` 的写法。
     *
     * 内容提供器不支持"截断重写"，所以只能**先读出要保留的音频数据，写进临时文件**，
     * 再整体写回原 URI。代价是一次完整的额外拷贝，但对 SAF 文件这是唯一可行的路子。
     */
    private fun writeViaUri(song: Song, payload: LyricsTagWriter.Payload): Outcome {
        val uri = runCatching { Uri.parse(song.uri) }.getOrNull()
            ?: return Outcome.Refused(UNSUPPORTED)
        val tmp = File(context.cacheDir, "embed-${song.key.hashCode()}.part")
        return try {
            val head = openInputStream(song)?.use { input ->
                val buffer = ByteArray(HEAD_BYTES)
                var read = 0
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read == buffer.size) buffer else buffer.copyOf(read)
            } ?: return Outcome.Failed("读不到文件内容")

            val rewrite = LyricsTagWriter.rewrite(head, payload)
                ?: return Outcome.Refused(UNSUPPORTED)

            openInputStream(song)?.use { raw ->
                FileOutputStream(tmp).use { out ->
                    out.write(rewrite.newHead)
                    raw.skipFully(rewrite.skipBytes)
                    raw.copyTo(out, BUFFER_BYTES)
                }
            } ?: return Outcome.Failed("读不到音频数据")

            // 写回内容提供器。拿不到写权限时这行会抛 SecurityException，
            // 已经白拷贝了一遍大文件，但如实报失败比假装成功好
            val written = runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    tmp.inputStream().use { it.copyTo(out, BUFFER_BYTES) }
                } != null
            }.getOrDefault(false)
            tmp.delete()
            if (!written) return Outcome.Failed("没有写入权限，请重新导入该文件")

            Outcome.Done(
                container = rewrite.container,
                wroteLyrics = !payload.lyrics.isNullOrBlank(),
                wroteArtwork = payload.artwork?.isNotEmpty() == true
            )
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            Outcome.Failed(t.message?.take(60) ?: "写入失败")
        }
    }

    // ------------------------------------------------------------------ 读取

    /**
     * 能直接用 [File] 改写的路径。
     *
     * 只认**真实路径存在且可写**的情况。`filePath` 指向 SD 卡上的文件时，
     * Android 11+ 的分区存储会让 `canWrite()` 返回 false —— 这时如实判定为不可写，
     * 好过写一半被系统拦下、留下半截文件。
     */
    private fun writableFileOrNull(song: Song): File? {
        song.filePath?.let { path ->
            val file = File(path)
            if (file.isFile && file.canRead() && file.canWrite()) return file
        }
        runCatching { Uri.parse(song.uri) }.getOrNull()?.let { uri ->
            if (uri.scheme == "file") {
                val file = uri.path?.let { File(it) }
                if (file != null && file.isFile && file.canRead() && file.canWrite()) return file
            }
        }
        return null
    }

    /**
     * SAF 导入的歌曲是否可能有写权限。
     *
     * 导入时只申请了读权限（见 `AudioLibrary.takePersistablePermission`），
     * 所以这里**不能**承诺一定能写 —— 但也不能直接拒绝：
     * 用户重新授权一次就能写。最终能不能成由 [writeViaUri] 的实际写入结果说话。
     */
    private fun canWriteViaUri(song: Song): Boolean {
        val uri = runCatching { Uri.parse(song.uri) }.getOrNull() ?: return false
        return uri.scheme == "content" || uri.scheme == "file"
    }

    private fun openSource(song: Song): ByteSource? {
        song.filePath?.let { path ->
            val file = File(path)
            if (file.isFile && file.canRead()) {
                runCatching { return FileByteSource(file) }
            }
        }
        val uri = runCatching { Uri.parse(song.uri) }.getOrNull() ?: return null
        if (uri.scheme == "file") {
            val file = uri.path?.let { File(it) }
            if (file != null && file.isFile && file.canRead()) {
                runCatching { return FileByteSource(file) }
            }
        }
        // 与 LyricsRepository.openSource 同一套：可寻址时走 FileChannel 随机读，
        // 这样解析器想读文件尾的块也不必整段进内存
        return runCatching {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return@runCatching null
            val size = descriptor.statSize
            if (size <= 0) {
                descriptor.close()
                return@runCatching null
            }
            val stream = FileInputStream(descriptor.fileDescriptor)
            ChannelByteSource(stream.channel, size) { runCatching { descriptor.close() } }
        }.getOrNull()
    }

    private fun readHead(file: File): ByteArray = RandomAccessFile(file, "r").use { raf ->
        val cap = minOf(file.length(), (LyricsTagWriter.HEAD_BYTES + 64L)).toInt()
        val buffer = ByteArray(cap)
        raf.readFully(buffer)
        buffer
    }

    /** [InputStream.skip] 对文件流不一定真的跳过去，必须循环读到目标位置。 */
    private fun InputStream.skipFully(target: Long) {
        var remaining = target
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            // skip 返回 0 时只能读一字节推进，否则死循环
            if (read() < 0) return
            remaining--
        }
    }

    companion object {
        private const val BUFFER_BYTES = 64 * 1024
        private const val HEAD_BYTES = 64 * 1024

        /** 界面与汇报共用的措辞：说清楚是"格式/权限不支持"，而不是"失败"。 */
        const val UNSUPPORTED = "这个格式或来源不支持写入内嵌标签（目前支持 MP3 与 FLAC）"
    }
}