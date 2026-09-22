package com.melody.player.data

import android.content.Context
import android.net.Uri
import android.util.LruCache
import com.melody.player.core.LyricCopyKeys
import com.melody.player.core.LyricLine
import com.melody.player.core.LyricOrigin
import com.melody.player.core.Lyrics
import com.melody.player.core.LyricsAligner
import com.melody.player.core.LyricsSource
import com.melody.player.core.LrcParser
import com.melody.player.core.Song
import com.melody.player.core.online.LyricMatch
import com.melody.player.core.online.LyricProvider
import com.melody.player.core.online.NetEaseApi
import com.melody.player.core.online.OnlineLyric
import com.melody.player.core.online.OnlineSong
import com.melody.player.core.tags.AudioTagReader
import com.melody.player.core.tags.ByteArraySource
import com.melody.player.core.tags.ByteSource
import com.melody.player.core.tags.ChannelByteSource
import com.melody.player.core.tags.FileByteSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.charset.Charset

/**
 * 歌词仓库：按优先级把歌词凑出来。
 *
 * 顺序是有讲究的 —— App 私有的副本最权威（它是用户亲手挑的：导入的文件或选定的
 * 在线版本，而且不会失效），其次是文件内嵌（最常见、离线可用），再是同目录的
 * 同名 .lrc，最后才是自动联网匹配到的结果。
 *
 * 读取走 [ByteSource] 抽象：有真实路径就直接随机读文件，
 * 没有就用 ContentResolver 打开可寻址的描述符，避免依赖已被废弃的 _data 列。
 */
class LyricsRepository(
    private val context: Context,
    private val prefs: Prefs
) {

    private val store = LyricsStore(context)

    private val online = OnlineLyricsClient()

    /** 第二家在线歌词来源。两家并存，见 [searchOnline] 的排序说明。 */
    private val lrcLib = LrcLibClient()

    private val cache = LruCache<String, Lyrics>(CACHE_ENTRIES)

    /** 每首歌实际命中的内嵌容器，用于在界面上如实标注来源。 */
    private val containerCache = LruCache<String, String>(CACHE_ENTRIES)

    suspend fun load(song: Song, durationMs: Long, forceRefresh: Boolean = false): Lyrics {
        if (!forceRefresh) {
            cache.get(song.key)?.let { return it }
        }
        val result = withContext(Dispatchers.IO) { buildLyrics(song, durationMs) }
        cache.put(song.key, result)
        return result
    }

    fun containerOf(song: Song): String? = containerCache.get(song.key)

    /** 手动导入歌词时选中的文件名，用于界面上如实标注「来自哪个文件」。 */
    fun importedNameOf(song: Song): String? = prefs.lyricNameFor(song.key)

    /** 用户选定的这份歌词是本地文件还是联网来的。 */
    fun originOf(song: Song): LyricOrigin? = prefs.lyricOriginFor(song.key)

    /**
     * 用户通过系统选择器指定歌词文件。
     *
     * 关键动作是 [LyricsStore.save]：把**文本内容**落到 App 私有目录。
     * 只记 URI 是不够的 —— SAF 授权会随文件被删/被移动而失效，
     * 那正是「退出 App 再进来又要重新导入」的根因。
     */
    suspend fun importLyricsFor(song: Song, uri: Uri, durationMs: Long): Lyrics? =
        withContext(Dispatchers.IO) {
            val text = readTextFromUri(uri) ?: return@withContext null
            val lyrics = fromRawText(text, LyricsSource.IMPORTED_LRC, "手动导入的 .lrc", durationMs)
            if (!lyrics.isUsable) return@withContext null

            val fileName = displayNameOf(uri)
            store.save(
                songKey = song.key,
                text = text,
                label = listOfNotNull(song.title.takeIf { it.isNotBlank() }, fileName).joinToString(" · "),
                origin = LyricOrigin.FILE
            )
            // 顺手把 SAF 授权持久化，让原始文件在被使用时仍然读得到（这是第二道保险）
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            prefs.setLyricUri(song.key, uri.toString())
            prefs.setLyricName(song.key, fileName)
            prefs.setLyricOrigin(song.key, LyricOrigin.FILE)
            cache.put(song.key, lyrics)
            lyrics
        }

    /** 清除用户选定的歌词（本地文件或在线版本都算）并重新解析。 */
    suspend fun clearImportedLyrics(song: Song, durationMs: Long): Lyrics {
        store.remove(song.key)
        store.remove(onlineCacheKey(song.key))
        prefs.setLyricUri(song.key, null)
        prefs.setLyricName(song.key, null)
        prefs.setLyricOrigin(song.key, null)
        return load(song, durationMs, forceRefresh = true)
    }

    fun hasImportedLyrics(song: Song): Boolean =
        store.load(song.key) != null || prefs.lyricUriFor(song.key) != null

    // ------------------------------------------------------------------ 联网取词

    /**
     * 搜候选：两家来源一起问。
     *
     * LRCLIB 排在前面 —— 它按「歌名 + 歌手 + 时长」锁定同一个录音版本，库里绝大多数记录
     * 带时间轴；网易云作为补充（有官方翻译，能合成中英对照）。顺序只影响展示先后，
     * 最终由用户在候选列表里自己挑。
     *
     * 两家的失败互不影响：一家超时/被限流，另一家的结果照常返回。
     *
     * **被用户在设置里关掉的那一家一个包都不发** —— 这既是省一次请求，也是尊重开关：
     * 关掉 LRCLIB 之后列表里还冒出 LRCLIB 的候选，那个开关就是假的。
     */
    suspend fun searchOnline(song: Song): List<OnlineSong> {
        val enabled = prefs.lyricProviders
        val fromLrcLib = if (LyricProvider.LRCLIB in enabled) {
            runCatching { lrcLib.search(song.title, song.artist) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val fromNetEase = if (LyricProvider.NETEASE in enabled) {
            runCatching { searchNetEase(song) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        return fromLrcLib + fromNetEase
    }

    /**
     * 网易云那一侧：先用「清洗过的歌名 + 歌手」搜，一条都没搜到再用原始歌名兜一次 ——
     * 有些歌名清洗后反而对不上（例如整首歌名就在括号里）。
     */
    private suspend fun searchNetEase(song: Song): List<OnlineSong> {
        val keyword = NetEaseApi.searchKeyword(song.title, song.artist)
        if (keyword.isNotEmpty()) {
            val hits = online.search(keyword)
            if (hits.isNotEmpty()) return hits
        }
        val fallback = song.title.trim()
        if (fallback.isEmpty() || fallback == keyword) return emptyList()
        return online.search(fallback)
    }

    /** 按候选所属的来源取词。两家接口形状差得远，但出口是同一个 [OnlineLyric]。 */
    private suspend fun fetchLyrics(candidate: OnlineSong): OnlineLyric? = when (candidate.provider) {
        LyricProvider.LRCLIB -> lrcLib.lyricsFor(candidate)
        LyricProvider.NETEASE -> online.lyricsFor(candidate)
    }

    /** 用户在候选列表里选定了一首：取词并写成「用户选定的」那一份。 */
    suspend fun applyOnlinePick(song: Song, candidate: OnlineSong, durationMs: Long): Lyrics? =
        withContext(Dispatchers.IO) {
            val fetched = fetchLyrics(candidate) ?: return@withContext null
            val container = candidate.provider.label
            val lyrics = fromRawText(
                text = fetched.lrcText,
                fallbackSource = LyricsSource.ONLINE_PICKED,
                container = container,
                durationMs = durationMs
            )
            if (!lyrics.isUsable) return@withContext null

            val label = labelOf(candidate, fetched.hasTranslation)
            store.save(
                songKey = song.key,
                text = fetched.lrcText,
                label = listOfNotNull(song.title.takeIf { it.isNotBlank() }, label).joinToString(" · "),
                origin = LyricOrigin.ONLINE,
                container = container
            )
            prefs.setLyricUri(song.key, null)
            prefs.setLyricName(song.key, label)
            prefs.setLyricOrigin(song.key, LyricOrigin.ONLINE)
            cache.put(song.key, lyrics)
            lyrics
        }

    /**
     * 本地完全没有歌词时自动匹配一份。
     *
     * 先问 LRCLIB 的签名查询（歌名 + 歌手 + 时长 —— 服务端替我们挑录音版本，一次请求
     * 连歌词一起拿回来），没命中再走网易云的关键词搜索 + 打分。
     *
     * 结果写进独立的 `net:` 槽位而不是「用户选定」的槽位：这是 App 自己猜的，
     * 优先级要低于文件内嵌与同名 .lrc，用户随时可以点「清除」退回去。
     *
     * 被用户关掉的来源直接跳过 —— 两家都关掉时这里一个请求都不发，等于自动匹配停用。
     */
    suspend fun autoFetchOnline(song: Song, durationMs: Long): Lyrics? = withContext(Dispatchers.IO) {
        val enabled = prefs.lyricProviders

        if (LyricProvider.LRCLIB in enabled) {
            val exact = lrcLib.lookup(song.title, song.artist, song.album, durationMs)
                // 接口「给了」不等于「对上了」：实测服务端时长匹配不上时会退回歌名+歌手兜底，
                // 所以拿到的记录仍要过一遍同一套打分规则，不能照单全收
                ?.let { LyricMatch.pickBest(listOf(it), song.title, song.artist, durationMs) }
            if (exact != null) {
                fetchLyrics(exact)?.let { fetched ->
                    storeAutoMatched(song, exact, fetched.lrcText, durationMs)?.let { return@withContext it }
                }
            }
        }

        if (LyricProvider.NETEASE in enabled) {
            val best = NetEaseApi.pickBest(searchNetEase(song), song.title, song.artist, durationMs)
                ?: return@withContext null
            val fetched = online.lyricsFor(best) ?: return@withContext null
            return@withContext storeAutoMatched(song, best, fetched.lrcText, durationMs)
        }
        null
    }

    /**
     * 自动匹配的结果落到 `net:` 槽位，并记下是哪一家给的。
     *
     * 来源必须存下来：播放页的来源标注要如实写清「这份词是 LRCLIB 还是网易云」，
     * 缓存读回来时没有别的地方能推断出来。
     */
    private fun storeAutoMatched(
        song: Song,
        candidate: OnlineSong,
        lrcText: String,
        durationMs: Long
    ): Lyrics? {
        val container = candidate.provider.label
        val lyrics = fromRawText(
            text = lrcText,
            fallbackSource = LyricsSource.ONLINE_AUTO,
            container = container,
            durationMs = durationMs
        )
        if (!lyrics.isUsable) return null

        store.save(
            songKey = onlineCacheKey(song.key),
            text = lrcText,
            label = listOfNotNull(song.title.takeIf { it.isNotBlank() }, "自动匹配").joinToString(" · "),
            origin = LyricOrigin.ONLINE,
            container = container
        )
        cache.put(song.key, lyrics)
        return lyrics
    }

    /** 这首歌是否已经有自动匹配的在线歌词缓存。 */
    fun hasOnlineCache(song: Song): Boolean = store.load(onlineCacheKey(song.key)) != null

    suspend fun clearOnlineCache(song: Song, durationMs: Long): Lyrics {
        store.remove(onlineCacheKey(song.key))
        return load(song, durationMs, forceRefresh = true)
    }

    /** 缓存槽位与「用户选定」的槽位分开，两者的优先级不同。 */
    private fun onlineCacheKey(songKey: String): String =
        LyricCopyKeys.AUTO_MATCH_PREFIX + songKey

    private fun labelOf(candidate: OnlineSong, translated: Boolean): String =
        buildString {
            append(candidate.provider.label)
            append(" · ")
            append(candidate.title)
            append(" - ")
            append(candidate.artistText)
            if (translated) append("（含翻译）")
        }

    /**
     * 取一首歌当前可用的歌词文本（LRC 优先），供归档时写进内嵌标签。
     *
     * 先在 App 私有副本里找，再走一遍完整的解析流程（内嵌/同名 .lrc 都算），
     * 拿不到返回 null。
     */
    suspend fun lyricTextFor(song: Song, durationMs: Long): String? {
        store.load(song.key)?.let { return it }
        val lyrics = load(song, durationMs)
        return lyrics.toLrcText()
    }

    fun invalidate(song: Song) {
        cache.remove(song.key)
        containerCache.remove(song.key)
    }

    private fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------------ 内部实现

    private fun buildLyrics(song: Song, durationMs: Long): Lyrics {
        // 1) 用户选定的歌词（App 内私有副本）：手动导入的文件，或联网选定的版本
        val picked = pickedSourceOf(song)
        // 来源名要从副本索引里读回来：联网选定的那份必须写出是网易云还是 LRCLIB。
        // 这里原来写死「用户选定」，于是**重启之后**播放页的来源标注就退化成
        // 一句废话（"在线获取 · 用户选定"）—— 明明记过来源，只是没读。
        // 「在线」这个兜底只用在旧版本留下的副本上（那时索引里还没有来源这一列，
        // 只可能是网易云，但宁可不认领，也不替它认一家）
        val onlinePicked = picked == LyricsSource.ONLINE_PICKED
        val pickedLabel = if (onlinePicked) {
            store.containerOf(song.key) ?: "在线"
        } else {
            "用户选定"
        }
        if (prefs.lyricUriFor(song.key) != null || store.load(song.key) != null) {
            store.load(song.key)?.let { text ->
                val lyrics = fromRawText(text, picked, pickedLabel, durationMs)
                if (lyrics.isUsable) return lyrics
            }
            // 副本丢了（清过数据），退回去读原始 URI
            prefs.lyricUriFor(song.key)?.let { raw ->
                runCatching {
                    readTextFromUri(Uri.parse(raw))?.let { text ->
                        val lyrics = fromRawText(text, picked, pickedLabel, durationMs)
                        if (lyrics.isUsable) {
                            // 重建副本时把来源一起带上：不带的话，这一次重建就把
                            // 「这份词是哪家给的」永久抹掉了（索引里那一列变成空）
                            store.save(
                                songKey = song.key,
                                text = text,
                                label = prefs.lyricNameFor(song.key),
                                origin = prefs.lyricOriginFor(song.key),
                                container = if (onlinePicked) {
                                    store.containerOf(song.key)
                                } else {
                                    null
                                }
                            )
                            return lyrics
                        }
                    }
                }
            }
        }

        // 2) 文件内嵌歌词
        val source = openSource(song)
        if (source != null) {
            try {
                val embedded = AudioTagReader.extract(source)
                if (embedded != null) {
                    containerCache.put(song.key, embedded.container)
                    if (embedded.synced.isNotEmpty()) {
                        return Lyrics(
                            lines = embedded.synced.map { LyricLine(it.timeMs, it.text) },
                            synced = true,
                            estimated = false,
                            source = LyricsSource.EMBEDDED_SYNCED,
                            container = embedded.container,
                            title = embedded.title,
                            artist = embedded.artist,
                            album = embedded.album
                        )
                    }
                    embedded.text?.let { text ->
                        val lyrics = fromRawText(text, LyricsSource.EMBEDDED_PLAIN, embedded.container, durationMs)
                        if (lyrics.isUsable) {
                            return lyrics.copy(
                                title = lyrics.title ?: embedded.title,
                                artist = lyrics.artist ?: embedded.artist,
                                album = lyrics.album ?: embedded.album
                            )
                        }
                    }
                }
            } catch (_: Throwable) {
                // 文件损坏或权限失效：静默退化到「无歌词」，不要打断播放
            } finally {
                runCatching { source.close() }
            }
        }

        // 3) 同目录同名 .lrc
        sidecarLyricText(song)?.let { text ->
            val lyrics = fromRawText(text, LyricsSource.SIDECAR_LRC, "同名 .lrc 文件", durationMs)
            if (lyrics.isUsable) return lyrics
        }

        // 4) 之前自动联网匹配到的缓存：本地什么都没找到时的兜底
        //    来源从副本索引里读回来 —— 自动匹配可能来自两家中的任何一家
        val autoKey = onlineCacheKey(song.key)
        store.load(autoKey)?.let { text ->
            val lyrics = fromRawText(text, LyricsSource.ONLINE_AUTO, store.containerOf(autoKey), durationMs)
            if (lyrics.isUsable) return lyrics
        }

        return Lyrics.NONE
    }

    /** 用户选定的那份歌词来自哪里 —— 决定界面上标注「手动导入」还是「在线获取」。 */
    private fun pickedSourceOf(song: Song): LyricsSource =
        if (prefs.lyricOriginFor(song.key) == LyricOrigin.ONLINE) {
            LyricsSource.ONLINE_PICKED
        } else {
            LyricsSource.IMPORTED_LRC
        }

    private fun fromRawText(
        text: String,
        fallbackSource: LyricsSource,
        container: String?,
        durationMs: Long
    ): Lyrics {
        val parsed = LrcParser.parse(text)
        if (parsed.hasTimestamps) {
            val source = when (fallbackSource) {
                LyricsSource.EMBEDDED_PLAIN -> LyricsSource.EMBEDDED_TIMED
                else -> fallbackSource
            }
            return Lyrics(
                lines = parsed.lines,
                synced = true,
                estimated = false,
                source = source,
                container = container,
                title = parsed.title,
                artist = parsed.artist,
                album = parsed.album,
                rawText = text
            )
        }
        val estimated = LyricsAligner.estimate(parsed.untimedText, durationMs)
        if (estimated.isEmpty()) return Lyrics.NONE
        return Lyrics(
            lines = estimated,
            synced = false,
            estimated = true,
            source = fallbackSource,
            container = container,
            title = parsed.title,
            artist = parsed.artist,
            album = parsed.album,
            rawText = text
        )
    }

    private fun sidecarLyricText(song: Song): String? {
        val path = song.filePath ?: return null
        val audio = File(path)
        val dir = audio.parentFile ?: return null
        val base = audio.name.substringBeforeLast('.')
        val candidates = listOf(
            File(dir, "$base.lrc"),
            File(dir, "$base.LRC"),
            File(dir, "$base.Lrc")
        )
        for (candidate in candidates) {
            if (candidate.isFile && candidate.canRead()) {
                return runCatching { decodeLyricBytes(candidate.readBytes()) }.getOrNull()
            }
        }
        return null
    }

    private fun openSource(song: Song): ByteSource? {
        // 优先真实路径：随机读取最快，也最不容易出意外
        song.filePath?.let { path ->
            val file = File(path)
            if (file.isFile && file.canRead()) {
                runCatching { return FileByteSource(file) }
            }
        }

        val uri = runCatching { Uri.parse(song.uri) }.getOrNull() ?: return null
        if (uri.scheme == "file") {
            val file = uri.path?.let { File(it) } ?: return null
            if (file.isFile && file.canRead()) {
                runCatching { return FileByteSource(file) }
            }
        }

        return runCatching {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val declaredSize = descriptor.statSize
            if (declaredSize > 0) {
                val stream = FileInputStream(descriptor.fileDescriptor)
                ChannelByteSource(stream.channel, declaredSize) { runCatching { descriptor.close() } }
            } else {
                // 管道类来源无法定位，退化成整段读入内存
                descriptor.close()
                readAllBytes(uri)?.let { ByteArraySource(it) }
            }
        }.getOrNull()
    }

    private fun readAllBytes(uri: Uri, cap: Int = MAX_INLINE_BYTES): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            val sink = java.io.ByteArrayOutputStream()
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > cap) break
                sink.write(buffer, 0, read)
            }
            sink.toByteArray()
        }
    }.getOrNull()

    private fun readTextFromUri(uri: Uri): String? = readAllBytes(uri, MAX_LYRIC_BYTES)?.let { decodeLyricBytes(it) }

    /**
     * 中文歌词文件编码五花八门：UTF-8 带不带 BOM、GBK 都很常见。
     * 先按 UTF-8 严格解码，失败再退到 GBK，避免整篇变成乱码方块。
     */
    private fun decodeLyricBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        return runCatching {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }.getOrElse {
            runCatching { String(bytes, Charset.forName("GBK")) }.getOrDefault(String(bytes, Charsets.ISO_8859_1))
        }
    }

    private companion object {
        const val CACHE_ENTRIES = 24
        const val MAX_INLINE_BYTES = 32 * 1024 * 1024
        const val MAX_LYRIC_BYTES = 2 * 1024 * 1024
    }
}
