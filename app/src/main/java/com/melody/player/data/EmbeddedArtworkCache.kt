package com.melody.player.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import com.melody.player.core.Song
import com.melody.player.core.tags.ByteSource
import com.melody.player.core.tags.ChannelByteSource
import com.melody.player.core.tags.EmbeddedArtworkParser
import com.melody.player.core.tags.FileByteSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * 文件**内嵌封面**的读取与缓存（ID3 的 APIC / FLAC 的 PICTURE）。
 *
 * ## 为什么单独一个类，而不是在 [AlbumArtRepository] 里顺手读一下
 * 两者的生命周期完全不同：App 缓存是**会被用户清掉、会被 App 重建**的临时数据，
 * 内嵌封面是**躺在音频文件里、App 重启也还在**的固有属性。
 * 混在一起会出现一个说不清的状态："显示的是 App 存的还是文件里的？"——
 * 尤其在用户点了「去掉封面」之后，两者的先后关系直接决定界面上出现什么。
 * 分开之后，[com.melody.player.ui.components.rememberCover] 里的三级顺序是显式的。
 *
 * ## 为什么按字节缓存而不是按 Bitmap
 * 位图解码很贵（一张 512×512 的 APIC 解码约 10ms），而 [LazyColumn] 滚动时
 * 每一行每帧都可能来问一次。缓存字节（几十到几百 KB，一首歌只占一条）比缓存位图
 * 便宜得多，而解码只做一次 —— 每首歌解一次，之后都从字节出图。
 */
class EmbeddedArtworkCache(context: Context) {

    private val appContext = context.applicationContext

    private val bytes = LruCache<String, ByteArray>(MAX_CACHED_BYTES)

    /**
     * 取这首歌内嵌的封面字节；没有 / 读不到返回 null。
     *
     * 命中缓存时**同步返回**：这是曲库滚动路径，异步会导致每次滚动都闪一下占位图。
     *
     * [force] 为 true 时**跳过缓存**重新读一遍文件，并把结果（哪怕是 null）写回缓存。
     * 用户在别的播放器里换了封面、又用别的工具改过文件时，缓存里那份已经过期，
     * 而界面上还照着旧字节画 —— 这时只有重新读文件才能对上（见 `reparseEmbedded`）。
     */
    suspend fun get(song: Song, force: Boolean = false): ByteArray? {
        if (!force) bytes.get(song.key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val parsed = openSource(song)?.use { EmbeddedArtworkParser.parse(it) }
            // 超大图不进内存缓存：几百首这种歌会把 LruCache 撑爆，
            // 而实际曲库里几乎不会有这种文件
            if (parsed == null) {
                // 强制重解析时读到"没有"也要覆盖旧值：文件里的封面被别的工具删掉了，
                // 缓存里留着一份就会让删除看起来没生效
                if (force) bytes.remove(song.key)
                return@withContext null
            }
            if (parsed.bytes.size <= MAX_CACHED_BYTES) bytes.put(song.key, parsed.bytes)
            parsed.bytes
        }
    }

    /**
     * 解码后的位图，供通知栏等需要 Bitmap 的地方用。
     *
     * 调用方应当节制使用：整张播放列表都解一遍会吃掉可观内存。
     */
    suspend fun bitmapOf(song: Song): Bitmap? {
        val raw = get(song) ?: return null
        return withContext(Dispatchers.Default) {
            runCatching { BitmapFactory.decodeByteArray(raw, 0, raw.size) }.getOrNull()
        }
    }

    fun clear() {
        bytes.evictAll()
    }

    /**
     * 强制重新解析一批曲目的内嵌封面，返回**确实带内嵌封面**的 key 集合。
     *
     * 循环放在这里而不是 ViewModel，是因为"丢缓存 → 重读 → 回填"这三步必须贴着
     * 缓存的实现走：漏了回填这一步，界面刷新后又会各自去读一遍盘，
     * 几百首歌就是几百次重复 IO。
     *
     * [onProgress] 每处理一首回调一次（已处理数），界面据此显示进度。
     */
    suspend fun reparse(
        songs: List<Song>,
        onProgress: (suspend (done: Int, total: Int) -> Unit)? = null
    ): Set<String> = withContext(Dispatchers.IO) {
        val found = LinkedHashSet<String>()
        songs.forEachIndexed { index, song ->
            if (get(song, force = true) != null) found.add(song.key)
            onProgress?.invoke(index + 1, songs.size)
        }
        found
    }

    /**
     * 只丢掉指定几首的缓存。
     *
     * 什么时候需要：**App 改写了文件里的封面之后**（批量嵌入标签）。
     * 文件变了但缓存里还是旧字节的话，用户会看到「写成功了，封面却没变」——
     * 而写封面本来就是为了让别的播放器显示出新图，这里必须立刻反映出来。
     */
    fun evict(keys: Collection<String>) {
        keys.forEach { bytes.remove(it) }
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
        return runCatching {
            val descriptor = appContext.contentResolver.openFileDescriptor(uri, "r") ?: return@runCatching null
            val size = descriptor.statSize
            if (size <= 0) {
                descriptor.close()
                return@runCatching null
            }
            val stream = FileInputStream(descriptor.fileDescriptor)
            ChannelByteSource(stream.channel, size) {
                runCatching { descriptor.close() }
            }
        }.getOrNull()
    }

    companion object {
        /** 约 24MB：够存上百张 512px 封面，又不会在低端机上造成压力。 */
        private const val MAX_CACHED_BYTES = 24 * 1024 * 1024

        @Volatile
        private var instance: EmbeddedArtworkCache? = null

        fun of(context: Context): EmbeddedArtworkCache =
            instance ?: synchronized(this) {
                instance ?: EmbeddedArtworkCache(context).also { instance = it }
            }
    }
}