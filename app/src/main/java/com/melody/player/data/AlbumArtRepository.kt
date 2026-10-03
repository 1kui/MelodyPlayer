package com.melody.player.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import com.melody.player.core.CoverEntry
import com.melody.player.core.Song
import com.melody.player.core.online.ITunesApi
import com.melody.player.core.online.ITunesHit
import com.melody.player.core.online.TextMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 取一张封面要经过的三步，用来在界面上**如实显示卡在哪一步**。
 *
 * 只有「已处理 N 首」一个数字时，卡在"搜不到"和"图片下载不动"看起来是一模一样的：
 * 都是长时间没动静。分开之后用户至少知道该等还是该关。
 */
enum class CoverStage(val label: String) {
    /** 问 iTunes 要候选列表（这一步受接口限流，批量时最耗时）。 */
    SEARCH("搜索候选"),

    /** 下载封面图片（走 CDN，与 Search API 不是一套限流）。 */
    DOWNLOAD("下载封面"),

    /** 解码 + 压到 512px + 落盘 + 记账。 */
    SAVE("写入缓存")
}

/** 一次取封面的结果。 */
sealed interface CoverResult {

    /** 之前已经取到过，直接用本地文件，没联网。 */
    data object Cached : CoverResult

    /** 这次真的取到了（刚下载并落盘）。 */
    data object Fetched : CoverResult

    /** 搜过了但没找到像的（已记下负结果，下次不会重复搜）。 */
    data object NoMatch : CoverResult

    /** 网络、写入失败。**不代表这首歌没有封面**，下次还能再试。 */
    data object Failed : CoverResult

    /** 用户关掉了「自动获取」，且这次不是手动触发。 */
    data object Disabled : CoverResult
}

/**
 * 在线专辑封面的总入口：匹配 → 下载 → 压缩 → 落盘 → 记录，并把位图在内存里缓存一层。
 *
 * ## 为什么图片要重新编码一遍
 * 取封面时已经让 CDN 给 [EDGE_PX] 见方的图，但"CDN 会不会照办"不是我们能保证的事
 * （尺寸段识别失败会退化成 100×100，Apple 也换过 CDN 格式）。而这张图的字节最终要
 * 被塞进 `MediaMetadata.artworkData` —— 那是要过 **Binder** 的，几百 KB 的位图
 * 会让整个 MediaItem 传输变得又慢又险。
 * 所以无论对面给什么，落盘前统一解一次码、按需缩小、重编成 JPEG：
 *  **有上限，就不会有意外**。
 *
 * ## 为什么内存里还要缓存位图
 * Compose 每次重组都会要封面。每次都读盘 + 解码一张 512×512 的 JPEG（约 10ms）
 * 会让列表滚动直接掉帧，所以按字节数做一个 [LruCache]。
 */
class AlbumArtRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = Prefs(appContext)
    private val store = CoverStore(appContext)
    private val client = ITunesClient()

    private val bitmaps = object : LruCache<String, Bitmap>(MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 候选列表的小图缓存，按条数计（100×100 的图一张才几十 KB）。 */
    private val thumbs = LruCache<String, Bitmap>(THUMB_CACHE_ENTRIES)

    // ------------------------------------------------------------------ 读

    /**
     * 只查内存缓存，**不碰磁盘**。
     *
     * 给 Compose 用：列表滚动时每一行都会问一次，这里做磁盘 IO 会直接掉帧。
     */
    fun peek(songKey: String): Bitmap? = bitmaps.get(songKey)

    /** 读盘 + 解码，跑在 IO 线程，结果进内存缓存。 */
    suspend fun load(songKey: String): Bitmap? = withContext(Dispatchers.IO) {
        bitmaps.get(songKey) ?: run {
            val bytes = store.read(songKey) ?: return@run null
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                ?.also { bitmaps.put(songKey, it) }
        }
    }

    fun entry(songKey: String): CoverEntry? = prefs.coverEntryFor(songKey)

    /** 本地缓存文件的 `file://` 地址，供 App 内使用。 */
    fun localFile(songKey: String): File? = store.fileFor(songKey).takeIf { it.isFile }

    /**
     * 给 `MediaMetadata.artworkUri` 用的地址。
     *
     * **优先给 http 原地址而不是本地 `file://`**：App 私有目录别的进程读不到，
     * 而锁屏、原子随身听这些系统界面是在**它们自己的进程**里加载封面的。
     * 本地文件只作为兜底（没有原始地址时才用）。
     */
    fun artworkUri(songKey: String): Uri? {
        val entry = prefs.coverEntryFor(songKey) ?: return null
        if (!entry.matched) return null
        entry.sourceUrl?.takeIf { it.isNotBlank() }?.let { return Uri.parse(it) }
        return localFile(songKey)?.let { Uri.fromFile(it) }
    }

    /**
     * 位图字节，给 `MediaMetadata.artworkData` 用。
     *
     * **只应该用在"当前播放的那一首"上**：整个播放列表每个人一条的话，
     * 2000 首歌就是上百 MB 常驻内存 + 每次 `setMediaItems` 都要过一遍 Binder。
     */
    fun artworkData(songKey: String): ByteArray? = store.read(songKey)

    fun stats(): Pair<Int, Long> = store.count() to store.totalBytes()

    /**
     * 测一个地区的连接延迟（毫秒）；连不上返回 null。
     *
     * 只是把 [ITunesClient.probe] 透出去。设置页要让用户看出"哪个区排在前面才搜得快"，
     * 而"快"得实测 —— 凭地区名猜是猜不出来的（同一个地区在不同运营商下的差别
     * 远大于地区之间的差别）。
     */
    suspend fun probeRegion(country: String): Long? = client.probe(country)

    // ------------------------------------------------------------------ 用户自选

    /**
     * 在线搜索封面候选，供用户自己挑。
     *
     * 与 [ensure] 的"自动挑一张"不同，这里把各地区曲库的结果**合并去重**后全部交给用户：
     * 自动匹配只该是兜底，人在场时选择权应该在人手里。合并顺序就是地区的顺序
     * （默认 TW → HK → US，或用户在设置里自定义的那几个），攒够 [CANDIDATE_LIMIT] 条就停，
     * 不把每个地区的请求都发完（Search API 有限流，搜一次候选最多占"地区数"个额度）。
     *
     * 地区顺序统一走 [Prefs.effectiveCoverRegionCodes]，与自动匹配用的是同一份 ——
     * 两处各判断一次开关，早晚会出现"自动匹配按 TW 搜、候选列表按 US 搜"这种鬼故事。
     */
    suspend fun searchCandidates(keyword: String): List<ITunesHit> =
        searchCandidatesIn(keyword, prefs.effectiveCoverRegionCodes())

    suspend fun searchCandidatesIn(keyword: String, countries: List<String>): List<ITunesHit> =
        withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext emptyList()
        val seen = HashSet<Long>()
        val out = ArrayList<ITunesHit>()
        for (country in countries) {
            val hits = client.search(keyword, country) ?: continue
            for (hit in hits) {
                // 没有封面地址的条目没有候选价值；同一首在相邻地区的目录里重复出现很常见
                if (hit.artworkUrl100 == null) continue
                if (!seen.add(hit.trackId)) continue
                out.add(hit)
                if (out.size >= CANDIDATE_LIMIT) return@withContext out
            }
        }
        out
    }

    /** 把用户选定的候选下载落盘。失败返回 false（原有封面保持不动）。 */
    suspend fun applyCandidate(
        song: Song,
        hit: ITunesHit,
        onStage: (CoverStage) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val url = ITunesApi.artworkUrl(hit.artworkUrl100 ?: return@withContext false)
            ?: return@withContext false
        onStage(CoverStage.DOWNLOAD)
        val raw = client.download(url) ?: return@withContext false
        val bytes = normalize(raw) ?: return@withContext false
        onStage(CoverStage.SAVE)
        if (!store.save(song.key, bytes)) return@withContext false
        prefs.setCoverEntry(
            song.key,
            CoverEntry(
                trackId = hit.trackId,
                fileName = CoverStore.fileNameFor(song.key),
                sourceUrl = url,
                fetchedAtSec = nowSec()
            )
        )
        bitmaps.remove(song.key)
        true
    }

    /**
     * 批量补齐用：自动取候选列表的**第一个**下载落盘。
     *
     * 与 [ensure] 的"按歌名/歌手/时长打分挑一张"不同 —— 打分挑出来的经常不是用户
     * 心里那张；iTunes Search API 返回的顺序本身就是相关性排序，第一个候选就是
     * "搜这个名字时排最前面的那张"，比自作聪明的打分更符合预期。人在场时当然
     * 还是应该用 [searchCandidates] 让用户自己挑，这条只服务几百首的无人值守批量。
     */
    suspend fun applyFirstCandidate(
        song: Song,
        onStage: (CoverStage) -> Unit = {}
    ): CoverResult = withContext(Dispatchers.IO) {
        val keyword = TextMatch.searchKeyword(song.title, song.artist)
        if (keyword.isBlank()) return@withContext CoverResult.NoMatch
        onStage(CoverStage.SEARCH)
        val first = searchCandidatesIn(keyword, prefs.effectiveCoverRegionCodes()).firstOrNull() ?: run {
            // 与 ensure 同一套负结果记账，别让搜不到的歌每次批量都白搜一遍
            prefs.setCoverEntry(song.key, CoverEntry(0L, "", null, nowSec()))
            return@withContext CoverResult.NoMatch
        }
        if (applyCandidate(song, first, onStage)) CoverResult.Fetched else CoverResult.Failed
    }

    /**
     * 保存用户从相册挑的自定义封面。
     *
     * 与联网封面的唯一差别：没有 http 原地址（`sourceUrl` 为 null，`artworkUri`
     * 退回本地 `file://`）。通知/锁屏的大图由**本进程**的 BitmapLoader 从媒体元数据
     * 解码后直接画进通知，不需要别的进程能读这个私有文件 —— 显示不受影响。
     */
    suspend fun saveCustom(song: Song, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val raw = runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(chunk)
                    if (n <= 0) break
                    total += n
                    // 相册原图动辄十几 MB，超出上限直接放弃 —— 反正要压到 512，取前段没有意义
                    if (total > MAX_IMPORT_BYTES) return@use null
                    buffer.write(chunk, 0, n)
                }
                buffer.toByteArray()
            }
        }.getOrNull()
        val bytes = normalize(raw ?: return@withContext false) ?: return@withContext false
        if (!store.save(song.key, bytes)) return@withContext false
        prefs.setCoverEntry(
            song.key,
            CoverEntry(
                trackId = 0L,
                fileName = CoverStore.fileNameFor(song.key),
                sourceUrl = null,
                fetchedAtSec = nowSec()
            )
        )
        bitmaps.remove(song.key)
        true
    }

    /** 移除这首歌的封面缓存与记录（联网取的和自定义的都算）。 */
    fun remove(song: Song): Boolean {
        bitmaps.remove(song.key)
        thumbs.remove("thumb-" + song.key)
        prefs.setCoverEntry(song.key, null)
        return store.delete(song.key)
    }

    /**
     * 候选列表的小图（100×100）。图片 CDN 与 Search API 不是一套限流，列表里并发取没问题；
     * 内存缓存一层，滚动/重组时不会反复下载同一张。
     */
    suspend fun thumbFor(hit: ITunesHit): Bitmap? = withContext(Dispatchers.IO) {
        val key = "thumb-" + hit.trackId
        thumbs.get(key) ?: run {
            val url = ITunesApi.artworkUrl(hit.artworkUrl100 ?: return@run null, THUMB_PX) ?: return@run null
            val bytes = client.download(url) ?: return@run null
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                ?.also { thumbs.put(key, it) }
        }
    }

    // ------------------------------------------------------------------ 写

    /**
     * 确保这首歌有封面。已经取到过的（或有负结果记录的）直接返回，不联网。
     *
     * 整个函数跑在 IO 线程：里面有读盘、写盘和联网，调用方（ViewModel）通常在主线程。
     *
     * @param force 用户手动点的「获取专辑封面」。此时忽略"自动获取"开关与已有的负结果 ——
     *              用户明确要求重查，说明他认为上次的结果不对。
     * @param onStage 当前进行到哪一步，界面据此显示细进度。
     */
    suspend fun ensure(
        song: Song,
        force: Boolean = false,
        onStage: (CoverStage) -> Unit = {}
    ): CoverResult = withContext(Dispatchers.IO) {
        val existing = prefs.coverEntryFor(song.key)
        if (!force && existing != null) {
            if (!existing.matched) return@withContext CoverResult.NoMatch
            // 记录说取到过，但文件可能被"清除缓存"或用户清数据弄没了
            if (store.exists(song.key)) return@withContext CoverResult.Cached
        }
        if (!force && !prefs.autoFetchCovers) return@withContext CoverResult.Disabled

        val keyword = TextMatch.searchKeyword(song.title, song.artist)
        if (keyword.isBlank()) return@withContext CoverResult.NoMatch

        onStage(CoverStage.SEARCH)
        val match = client.findCover(
            title = song.title,
            artist = song.artist,
            durationMs = song.durationMs,
            keyword = keyword,
            // 门槛与地区都从设置里取：用户调过之后，自动匹配必须按他调的那套来，
            // 否则设置项就只是摆设
            minScore = prefs.coverMinScore,
            countries = prefs.effectiveCoverRegionCodes()
        )
        if (match == null) {
            // 负结果也记账：否则每次进播放页都会为同一首搜不到的歌唱一次空
            prefs.setCoverEntry(song.key, CoverEntry(0L, "", null, nowSec()))
            return@withContext CoverResult.NoMatch
        }

        onStage(CoverStage.DOWNLOAD)
        val raw = client.download(match.artworkUrl) ?: return@withContext CoverResult.Failed
        val bytes = normalize(raw) ?: return@withContext CoverResult.Failed
        onStage(CoverStage.SAVE)
        if (!store.save(song.key, bytes)) return@withContext CoverResult.Failed

        prefs.setCoverEntry(
            song.key,
            CoverEntry(
                trackId = match.hit.trackId,
                fileName = CoverStore.fileNameFor(song.key),
                sourceUrl = match.artworkUrl,
                fetchedAtSec = nowSec()
            )
        )
        // 内存缓存里可能还留着旧图（手动重取的情况），丢掉让它下次读盘
        bitmaps.remove(song.key)
        CoverResult.Fetched
    }

    /**
     * 清空全部封面缓存（文件 + 记录），返回释放的字节数。
     *
     * 记录必须一起清：只删文件不清记录的话，界面会认为"这首歌已经取到过封面"，
     * 于是既不显示图、也不再尝试联网 —— 表现就是"清了缓存之后封面再也回不来"。
     */
    fun clear(): Long {
        val (_, bytes) = store.deleteAll()
        prefs.clearCoverEntries()
        bitmaps.evictAll()
        return bytes
    }

    // ------------------------------------------------------------------ 内部

    private fun nowSec(): Long = System.currentTimeMillis() / 1000L

    /**
     * 统一压成一张不大于 [EDGE_PX] 的 JPEG。
     *
     * 解码不了（对面给的不是图，比如一个 HTML 错误页）时返回 null，让调用方按失败处理 ——
     * 把脏字节落盘的话，之后每次解码都会失败，而且记录里显示"已有封面"。
     */
    private fun normalize(raw: ByteArray): ByteArray? {
        if (raw.size <= SMALL_ENOUGH_BYTES) {
            // 已经很小了仍要确认它是张图：脏数据落盘的代价比多解码一次大得多
            return if (BitmapFactory.decodeByteArray(raw, 0, raw.size) != null) raw else null
        }
        val decoded = runCatching { BitmapFactory.decodeByteArray(raw, 0, raw.size) }.getOrNull() ?: return null
        val scaled = scaleDown(decoded)
        val out = ByteArrayOutputStream()
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return null
        val encoded = out.toByteArray()
        // 重编后反而更大（原图很小时可能发生），那就用原图
        return if (encoded.size < raw.size) encoded else raw
    }

    private fun scaleDown(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= EDGE_PX) return source
        val ratio = EDGE_PX.toFloat() / longest
        val target = Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true
        )
        if (target != source) source.recycle()
        return target
    }

    private companion object {
        /** 落盘边长。播放页最大也就铺满屏宽，512 足够；再大只是让 Binder 更吃力。 */
        const val EDGE_PX = 512
        const val JPEG_QUALITY = 85

        /** 小于这个体积就不重新编码了（CDN 正常返回的 512px 图约 40–70KB）。 */
        const val SMALL_ENOUGH_BYTES = 120 * 1024

        /** 位图内存缓存上限，约等于 12 张 512×512 的 ARGB_8888。 */
        const val MEMORY_CACHE_BYTES = 12 * 512 * 512 * 4

        /** 候选列表上限：一屏能扫完的量，再多用户就会用相册自己挑了。 */
        const val CANDIDATE_LIMIT = 12

        /** 候选小图边长（对应 CDN 的 `100x100bb` 段）。 */
        const val THUMB_PX = 100

        /** 小图缓存条数。 */
        const val THUMB_CACHE_ENTRIES = 64

        /** 相册导入的字节上限。 */
        const val MAX_IMPORT_BYTES = 20 * 1024 * 1024
    }
}
