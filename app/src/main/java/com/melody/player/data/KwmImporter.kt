package com.melody.player.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.melody.player.core.ArchivedEntry
import com.melody.player.core.ArchivedSongs
import com.melody.player.core.kwm.KwmDecoder
import com.melody.player.core.kwm.KwmException
import com.melody.player.core.kwm.KwmFile
import com.melody.player.core.tags.LyricsTagWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * 把一个 `.kwm` 变成 App 音乐库里的一首歌。
 *
 * 三步，每步都可能失败，而且**每一步的失败都只退到安全的一侧**：
 *
 *  1. **解密** —— 流式异或，结果先落在 App 音乐的库目录下的 `.part` 临时文件。
 *     放在同一个目录是有意的：同卷改名是原子的，而 `.part` 又会在下次启动时被清掉，
 *     所以进程被杀不会留下半个「看起来能用」的文件。
 *  2. **认格式** —— 靠解密后明文头部嗅探，不信扩展名（输入一律叫 `.kwm`），
 *     也不信 KWM 头里那个自述格式（它经常是空的）。嗅不出来就退回系统媒体库的判定。
 *  3. **归档** —— **按原格式**写进 App 专属目录、登记归档记录。MP3 会顺手补一份
 *     ID3v2 基础信息（标题/歌手/专辑），否则在别的播放器里就是「未知曲目」。
 *
 * v2.5 起不再做「解密后转 MP3」：曲库自己的播放器认得 FLAC / OGG / M4A，
 * 而转码既要花时间又只能换来一份有损副本，还得为此在 APK 里塞一个 MP3 编码器。
 *
 * 全程**不删用户的 `.kwm`**：那是用户的文件，App 只负责派生出一份可播放的副本。
 */
class KwmImporter(
    private val context: Context,
    private val archived: ArchivedLibrary,
    private val prefs: Prefs
) {

    /** 一次解密归档的结果。[message] 会直接展示给用户，所以要是人话。 */
    data class Outcome(
        val file: KwmFile,
        val ok: Boolean,
        val message: String,
        val entry: ArchivedEntry? = null,
        /** 归档下来的这份是不是 MP3（原文件就是 MP3 时才为真）。 */
        val mp3: Boolean = false
    )

    /**
     * 解密 [file] 并按原格式归档。
     *
     * 抛 CancellationException 时不留半个文件。
     */
    suspend fun import(file: KwmFile): Outcome = withContext(Dispatchers.IO) {
        val folder = archived.ensureDir()
        if (!folder.isDirectory) {
            return@withContext Outcome(file, false, "无法创建 App 音乐库目录")
        }

        val stamp = System.nanoTime()
        val raw = File(folder, "kwm-$stamp.raw.part")

        try {
            // ---------------------------------------------------------- 1) 解密
            val input = openInput(file)
                ?: return@withContext Outcome(file, false, "打不开这个文件（没有读取权限）")

            val decryption = try {
                input.use { source ->
                    FileOutputStream(raw).use { sink -> KwmDecoder.decrypt(source, sink) }
                }
            } catch (e: KwmException) {
                return@withContext Outcome(file, false, e.message ?: "解密失败")
            }

            // ---------------------------------------------------------- 2) 认格式
            val meta = readMeta(raw)
            val extension = decryption.extension ?: extensionOfMime(meta.mime)
            val extensionKnown = extension != null
            val finalExtension = extension ?: "bin"
            val mp3 = finalExtension == "mp3"

            // ---------------------------------------------------------- 3) 归档
            val source = raw
            val taken = archived.knownNames() + prefs.archivedSongs.map { it.name }
            val finalName = ArchivedSongs.uniqueName("${file.titleHint}.$finalExtension", taken)
            val target = File(folder, finalName)

            val title = meta.title ?: file.titleHint
            val tagRewrite = if (mp3) {
                // 只补基础信息，不动已有标签里的歌词帧
                LyricsTagWriter.writeMeta(
                    readHead(source),
                    LyricsTagWriter.Meta(meta.title ?: file.titleHint, meta.artist, meta.album)
                )
            } else {
                null
            }

            if (tagRewrite != null) {
                writeWithHead(source, target, tagRewrite.newHead, tagRewrite.skipBytes)
            } else if (!source.renameTo(target)) {
                // 同一个目录，改名失败说明文件系统不配合，退到拷贝
                source.copyTo(target, overwrite = true)
            }

            if (!target.isFile || target.length() <= 0L) {
                return@withContext Outcome(file, false, "写入 App 音乐库失败")
            }

            val entry = ArchivedEntry(
                name = finalName,
                title = title,
                artist = meta.artist,
                album = meta.album,
                durationMs = meta.durationMs,
                sizeBytes = target.length(),
                archivedAtSec = System.currentTimeMillis() / 1000L,
                // 来源是 .kwm 而不是曲库里的某一首，所以没有 sourceKey
                sourceKey = null,
                lyricsEmbedded = tagRewrite?.embedded == true
            )
            prefs.archivedSongs = listOf(entry) + prefs.archivedSongs

            Outcome(
                file = file,
                ok = true,
                message = buildMessage(
                    from = decryption.declaredFormat?.takeIf { it.isNotBlank() },
                    extension = finalExtension,
                    extensionKnown = extensionKnown,
                    mp3 = mp3
                ),
                entry = entry,
                mp3 = mp3
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Outcome(file, false, t.message?.take(80) ?: "解密失败")
        } finally {
            // 临时文件一律清掉。改名成功的话源路径已经不存在，delete() 是空操作；
            // 拷贝或中途失败时才会真的落到这里，避免留下带 .part 的半个文件。
            runCatching { raw.delete() }
        }
    }

    /** 结果文案，如实说明归档下来是什么格式、以及为什么。 */
    private fun buildMessage(
        from: String?,
        extension: String,
        extensionKnown: Boolean,
        mp3: Boolean
    ): String = when {
        !extensionKnown ->
            "已解密并归档，但认不出真实格式，已按原样保存为 .$extension（可能无法播放）"

        mp3 && from != null -> "已解密并归档（原文件就是 $from）"

        mp3 -> "已解密并归档（原文件就是 MP3）"

        else -> "已解密并归档，保留原格式 .$extension"
    }

    /**
     * 写入最终文件：先写新的标签头，再从源文件的 [skipBytes] 处把音频搬过去。
     *
     * 流式拷贝，一首 40MB 的歌不会整段读进内存。
     */
    private fun writeWithHead(source: File, target: File, head: ByteArray, skipBytes: Long) {
        FileInputStream(source).use { input ->
            var skipped = 0L
            while (skipped < skipBytes) {
                val n = input.skip(skipBytes - skipped)
                if (n <= 0) break
                skipped += n
            }
            FileOutputStream(target).use { out ->
                out.write(head)
                input.copyTo(out, COPY_BYTES)
            }
        }
    }

    private class Meta(
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long,
        val mime: String?
    )

    /**
     * 从解密出来的文件里读标签。
     *
     * 读不到就算了 —— 文件名兜底出来的标题依然可用，不能因为标签读不出来就整单失败。
     */
    private fun readMeta(file: File): Meta {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            Meta(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.takeIf { it.isNotBlank() },
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.takeIf { it.isNotBlank() },
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?.takeIf { it.isNotBlank() },
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
                mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                    ?.takeIf { it.isNotBlank() }
            )
        } catch (_: Throwable) {
            Meta(null, null, null, 0L, null)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 嗅探不出来时的兜底：让系统媒体库认一下它到底是什么。 */
    private fun extensionOfMime(mime: String?): String? = when {
        mime == null -> null
        mime.contains("mpeg", ignoreCase = true) -> "mp3"
        mime.contains("flac", ignoreCase = true) -> "flac"
        mime.contains("ogg", ignoreCase = true) -> "ogg"
        mime.contains("opus", ignoreCase = true) -> "opus"
        mime.contains("mp4", ignoreCase = true) || mime.contains("m4a", ignoreCase = true) -> "m4a"
        mime.contains("wav", ignoreCase = true) -> "wav"
        mime.contains("aac", ignoreCase = true) -> "aac"
        else -> null
    }

    private fun readHead(file: File): ByteArray {
        val capacity = minOf(file.length(), (LyricsTagWriter.HEAD_BYTES + 64).toLong()).toInt()
        if (capacity <= 0) return ByteArray(0)
        val buffer = ByteArray(capacity)
        var read = 0
        FileInputStream(file).use { input ->
            while (read < capacity) {
                val n = input.read(buffer, read, capacity - read)
                if (n <= 0) break
                read += n
            }
        }
        return if (read == capacity) buffer else buffer.copyOf(read)
    }

    private fun openInput(file: KwmFile): InputStream? {
        file.filePath?.let { path ->
            val local = File(path)
            if (local.isFile && local.canRead()) return local.inputStream()
        }
        val uri = runCatching { Uri.parse(file.uri) }.getOrNull() ?: return null
        if (uri.scheme == "file") {
            val local = uri.path?.let { File(it) }
            if (local != null && local.isFile && local.canRead()) return local.inputStream()
        }
        return runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
    }

    private companion object {
        const val COPY_BYTES = 128 * 1024
    }
}
