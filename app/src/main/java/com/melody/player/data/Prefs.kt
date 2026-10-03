package com.melody.player.data

import android.content.Context
import android.content.SharedPreferences
import com.melody.player.core.ArchivedEntry
import com.melody.player.core.ArtworkShape
import com.melody.player.core.CoverEntry
import com.melody.player.core.HiddenSongEntry
import com.melody.player.core.LyricOrigin
import com.melody.player.core.LyricTextSize
import com.melody.player.core.PlayMode
import com.melody.player.core.PlayRecord
import com.melody.player.core.Playlist
import com.melody.player.core.SongEdit
import com.melody.player.core.SortMode
import com.melody.player.core.online.CoverRegion
import com.melody.player.core.online.ITunesApi
import com.melody.player.core.online.LyricProvider
import com.melody.player.ui.theme.AccentTheme
import com.melody.player.ui.theme.ThemeMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * 轻量偏好存储。
 *
 * 本机 Gradle 缓存里没有 DataStore，所以用 SharedPreferences + org.json 手写编解码，
 * 够用且零额外依赖。
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("melody_prefs", Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = ThemeMode.fromKey(sp.getString(KEY_THEME_MODE, null))
        set(value) = sp.edit().putString(KEY_THEME_MODE, value.name).apply()

    var accentTheme: AccentTheme
        get() = runCatching { AccentTheme.valueOf(sp.getString(KEY_ACCENT, null) ?: AccentTheme.INDIGO.name) }
            .getOrDefault(AccentTheme.INDIGO)
        set(value) = sp.edit().putString(KEY_ACCENT, value.name).apply()

    var sortMode: SortMode
        get() = runCatching { SortMode.valueOf(sp.getString(KEY_SORT, null) ?: SortMode.TITLE.name) }
            .getOrDefault(SortMode.TITLE)
        set(value) = sp.edit().putString(KEY_SORT, value.name).apply()

    /** 上次播放的歌曲，用于冷启动恢复。 */
    var lastSongKey: String?
        get() = sp.getString(KEY_LAST_SONG, null)
        set(value) = sp.edit().putString(KEY_LAST_SONG, value).apply()

    /**
     * 播放模式。
     *
     * 旧版本把「随机」和「循环」拆成两个字段存（[KEY_SHUFFLE] + [KEY_REPEAT]），
     * 读的时候做一次迁移，用户升级后不会突然变回顺序播放。
     */
    var playMode: PlayMode
        get() {
            val stored = sp.getInt(KEY_PLAY_MODE, -1)
            if (stored >= 0) return PlayMode.fromOrdinal(stored)
            return PlayMode.fromLegacy(
                shuffle = sp.getBoolean(KEY_SHUFFLE, false),
                repeatOrdinal = sp.getInt(KEY_REPEAT, 0)
            )
        }
        set(value) = sp.edit().putInt(KEY_PLAY_MODE, value.ordinal).apply()

    /** 本地完全找不到歌词时，是否自动联网匹配一份。默认开启。 */
    var autoFetchLyrics: Boolean
        get() = sp.getBoolean(KEY_AUTO_LYRICS, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_LYRICS, value).apply()

    /**
     * 播放页封面上「上下滑动切歌」手势的开关。默认开 —— 这是新版播放页的核心交互；
     * 但手势这种东西一旦误触就很难受，所以必须可以关。
     */
    var swipeSwitchSong: Boolean
        get() = sp.getBoolean(KEY_SWIPE_SWITCH, true)
        set(value) = sp.edit().putBoolean(KEY_SWIPE_SWITCH, value).apply()

    /** 歌词字号档位。默认「标准」，也就是旧版本一直用的那个字号。 */
    var lyricTextSize: LyricTextSize
        get() = LyricTextSize.fromKey(sp.getString(KEY_LYRIC_TEXT_SIZE, null))
        set(value) = sp.edit().putString(KEY_LYRIC_TEXT_SIZE, value.name).apply()

    /** 封面形状（圆角方形 / 圆形）。默认圆角方形，与旧版本一致。 */
    var artworkShape: ArtworkShape
        get() = ArtworkShape.fromKey(sp.getString(KEY_ARTWORK_SHAPE, null))
        set(value) = sp.edit().putString(KEY_ARTWORK_SHAPE, value.name).apply()

    /**
     * 联网歌词允许用哪几家来源，默认两家都开。
     *
     * 存成名字列表而不是两个 boolean：以后再加第三家来源时不用动存储结构。
     * 注意**空集合是合法状态**（用户把两家都关了），所以不能拿"读出来是空"当"没存过" ——
     * 判据是**键存不存在**，否则关掉两家后下次冷启动会自己又打开。
     */
    var lyricProviders: Set<LyricProvider>
        get() {
            val raw = sp.getString(KEY_LYRIC_PROVIDERS, null) ?: return LyricProvider.entries.toSet()
            return decodeArray(raw).mapNotNullTo(LinkedHashSet()) { name ->
                LyricProvider.entries.firstOrNull { it.name == name }
            }
        }
        set(value) = sp.edit()
            .putString(KEY_LYRIC_PROVIDERS, encodeArray(value.map { it.name }))
            .apply()

    /**
     * 封面自动匹配的评分门槛，默认 [ITunesApi.MIN_SCORE]。
     *
     * 调低 → 更容易匹配上，但更容易贴错；调高 → 宁可不给封面，也不给别人的。
     * 读的时候夹一次范围：手改过偏好文件也不会把功能弄成永远不匹配。
     */
    var coverMinScore: Int
        get() = sp.getInt(KEY_COVER_MIN_SCORE, ITunesApi.MIN_SCORE)
            .coerceIn(ITunesApi.MIN_SCORE_FLOOR, ITunesApi.MAX_SCORE)
        set(value) = sp.edit()
            .putInt(
                KEY_COVER_MIN_SCORE,
                value.coerceIn(ITunesApi.MIN_SCORE_FLOOR, ITunesApi.MAX_SCORE)
            )
            .apply()

    /** 是否按用户选的地区搜封面（关掉就用 [ITunesApi.COUNTRIES] 那条默认链）。 */
    var coverRegionCustom: Boolean
        get() = sp.getBoolean(KEY_COVER_REGION_CUSTOM, false)
        set(value) = sp.edit().putBoolean(KEY_COVER_REGION_CUSTOM, value).apply()

    /** 用户选的搜索地区（仅在 [coverRegionCustom] 为真时生效）。 */
    var coverRegions: List<CoverRegion>
        get() = CoverRegion.sanitize(decodeArray(sp.getString(KEY_COVER_REGIONS, null)))
        set(value) = sp.edit()
            .putString(KEY_COVER_REGIONS, encodeArray(value.map { it.code }))
            .apply()

    /** 实际生效的地区顺序 —— 取封面的每个入口都从这里拿，别各自判断开关。 */
    fun effectiveCoverRegionCodes(): List<String> =
        if (coverRegionCustom) coverRegions.map { it.code } else ITunesApi.COUNTRIES

    /**
     * 本地标签没有内嵌封面时，是否自动联网找一张。默认开启。
     *
     * 注意它管的是**自动**：用户在歌曲菜单里手动点「获取专辑封面」时不受这个开关限制 ——
     * 明确的操作意图优先于一个早就设好的偏好。
     */
    var autoFetchCovers: Boolean
        get() = sp.getBoolean(KEY_AUTO_COVERS, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_COVERS, value).apply()

    /**
     * 某首歌的封面缓存记录（含"查过但没查到"的负结果）。
     *
     * 存 JSON 字符串而不是拆成 `cover_id_` / `cover_file_` 几个键：这是一条记录，
     * 拆开存就会出现"改了 id 没改文件名"这类半截状态，而封面这种缓存半截状态的表现
     * 就是**贴着别人的封面**。
     */
    fun coverEntryFor(songKey: String): CoverEntry? = runCatching {
        val raw = sp.getString(KEY_COVER_PREFIX + songKey, null) ?: return null
        val obj = JSONObject(raw)
        CoverEntry(
            trackId = obj.optLong("t", 0L),
            fileName = obj.optString("f"),
            sourceUrl = obj.optString("u").takeIf { it.isNotBlank() },
            fetchedAtSec = obj.optLong("at", 0L)
        )
    }.getOrNull()

    fun setCoverEntry(songKey: String, entry: CoverEntry?) {
        val editor = sp.edit()
        if (entry == null) {
            editor.remove(KEY_COVER_PREFIX + songKey)
        } else {
            editor.putString(
                KEY_COVER_PREFIX + songKey,
                JSONObject().apply {
                    put("t", entry.trackId)
                    put("f", entry.fileName)
                    entry.sourceUrl?.let { put("u", it) }
                    put("at", entry.fetchedAtSec)
                }.toString()
            )
        }
        editor.apply()
    }

    /** 有封面记录的歌曲 key。清缓存时用它连带清掉记录。 */
    fun coverKeys(): Set<String> = runCatching {
        sp.all.keys.mapNotNullTo(HashSet()) { raw ->
            raw.takeIf { it.startsWith(KEY_COVER_PREFIX) }
                ?.removePrefix(KEY_COVER_PREFIX)
                ?.takeIf { it.isNotBlank() }
        }
    }.getOrDefault(emptySet())

    fun clearCoverEntries() {
        val editor = sp.edit()
        sp.all.keys.filter { it.startsWith(KEY_COVER_PREFIX) }.forEach { editor.remove(it) }
        editor.apply()
    }

    /** 用户通过系统文件选择器导入的音频 URI 列表。 */
    var importedAudioUris: List<String>
        get() = decodeArray(sp.getString(KEY_IMPORTED_AUDIO, null))
        set(value) = sp.edit().putString(KEY_IMPORTED_AUDIO, encodeArray(value)).apply()

    /**
     * 被隐藏（不想在曲库/搜索里看到）的曲目。
     *
     * 存 JSON 对象数组而不是纯 key 列表：恢复列表要显示标题与歌手，
     * 而那些曲目此刻已经不在曲库里了，拿不到 [com.melody.player.core.Song] 实例。
     */
    var hiddenSongs: List<HiddenSongEntry>
        get() = decodeHidden(sp.getString(KEY_HIDDEN, null))
        set(value) = sp.edit().putString(KEY_HIDDEN, encodeHidden(value)).apply()

    /** 用户手动为某首歌指定的歌词文件 URI。 */
    fun lyricUriFor(songKey: String): String? = sp.getString(KEY_LYRIC_PREFIX + songKey, null)

    fun setLyricUri(songKey: String, uri: String?) {
        val editor = sp.edit()
        if (uri == null) editor.remove(KEY_LYRIC_PREFIX + songKey) else editor.putString(KEY_LYRIC_PREFIX + songKey, uri)
        editor.apply()
    }

    /** 手动导入歌词时的原始文件名（App 私有副本不保留文件名，单独记一份用于展示）。 */
    fun lyricNameFor(songKey: String): String? = sp.getString(KEY_LYRIC_NAME_PREFIX + songKey, null)

    fun setLyricName(songKey: String, name: String?) {
        val editor = sp.edit()
        if (name == null) editor.remove(KEY_LYRIC_NAME_PREFIX + songKey)
        else editor.putString(KEY_LYRIC_NAME_PREFIX + songKey, name)
        editor.apply()
    }

    /**
     * 歌词是怎么来的：`file` = 从文件导入，`online` = 联网获取。
     *
     * 两者都落在 App 私有副本的同一个槽位（都是用户明确指定的，优先级最高），
     * 但界面上的来源标注必须区分开，所以单独记一份。
     */
    fun lyricOriginFor(songKey: String): LyricOrigin? =
        LyricOrigin.fromKey(sp.getString(KEY_LYRIC_ORIGIN_PREFIX + songKey, null))

    fun setLyricOrigin(songKey: String, origin: LyricOrigin?) {
        val editor = sp.edit()
        if (origin == null) editor.remove(KEY_LYRIC_ORIGIN_PREFIX + songKey)
        else editor.putString(KEY_LYRIC_ORIGIN_PREFIX + songKey, origin.key)
        editor.apply()
    }

    /**
     * 某首歌的歌词时间轴偏移（毫秒，正 = 歌词延后）。
     *
     * **每曲独立**，不做"全局默认 + 单曲覆盖"：全局默认会让没调过的歌也莫名偏移，
     * 用户下次听到那首时会以为是歌词源本身有问题，归因不出来。
     * 值为 0 时把键删掉，偏好文件不会攒下一堆 `= 0` 的死键。
     */
    fun lyricOffsetFor(songKey: String): Long = sp.getLong(KEY_LYRIC_OFFSET_PREFIX + songKey, 0L)

    fun setLyricOffset(songKey: String, offsetMs: Long) {
        val editor = sp.edit()
        if (offsetMs == 0L) editor.remove(KEY_LYRIC_OFFSET_PREFIX + songKey)
        else editor.putLong(KEY_LYRIC_OFFSET_PREFIX + songKey, offsetMs)
        editor.apply()
    }

    /**
     * 播放历史（最近播放 / 最常听共用这一张表）。
     *
     * 纯本地、卸载即失 —— 没有账号也就没有云端可同步，界面文案要如实说明，
     * 免得用户以为重装之后还能找回来。
     */
    var playHistory: List<PlayRecord>
        get() = decodePlayHistory(sp.getString(KEY_PLAY_HISTORY, null))
        set(value) = sp.edit().putString(KEY_PLAY_HISTORY, encodePlayHistory(value)).apply()

    /** 最近搜索过的关键词，最新的在前。 */
    var recentSearches: List<String>
        get() = decodeArray(sp.getString(KEY_RECENT_SEARCHES, null))
        set(value) = sp.edit().putString(KEY_RECENT_SEARCHES, encodeArray(value)).apply()

    /**
     * 偏好里留有歌词痕迹的全部槽位。
     *
     * 给「歌词副本管理」用：副本索引万一丢了，只要用户当初是在 App 里导入/联网获取的，
     * 这里就还留着那个槽位，凭它能把磁盘上的文件认回歌曲，而不是退化成一堆孤儿。
     */
    fun knownLyricKeys(): Set<String> = runCatching {
        sp.all.keys.mapNotNullTo(HashSet()) { raw ->
            val key = when {
                raw.startsWith(KEY_LYRIC_ORIGIN_PREFIX) -> raw.removePrefix(KEY_LYRIC_ORIGIN_PREFIX)
                raw.startsWith(KEY_LYRIC_NAME_PREFIX) -> raw.removePrefix(KEY_LYRIC_NAME_PREFIX)
                raw.startsWith(KEY_LYRIC_PREFIX) -> raw.removePrefix(KEY_LYRIC_PREFIX)
                else -> null
            }
            key?.takeIf { it.isNotBlank() }
        }
    }.getOrDefault(emptySet())

    /** 已归档到 App 专属目录的曲目，按归档时间从新到旧。 */
    var archivedSongs: List<ArchivedEntry>
        get() = decodeArchived(sp.getString(KEY_ARCHIVED, null))
        set(value) = sp.edit().putString(KEY_ARCHIVED, encodeArchived(value)).apply()

    /**
     * 用户自建歌单，**新建的排在最前**。
     *
     * 只存曲目的 [com.melody.player.core.Song.key]（见 [com.melody.player.core.Playlist]），
     * 顺序即播放顺序。这里不再排一次序：和歌单里的手排顺序较劲，
     * 用户会看到"我排好的歌单一打开就变了"。
     */
    var playlists: List<Playlist>
        get() = decodePlaylists(sp.getString(KEY_PLAYLISTS, null))
        set(value) = sp.edit().putString(KEY_PLAYLISTS, encodePlaylists(value)).apply()

    /**
     * 是否**只**扫描用户指定的那个文件夹。
     *
     * 默认关：绝大多数人就是想听整机里的歌，一上来就限定文件夹反而扫不到东西。
     */
    var libraryFolderOnly: Boolean
        get() = sp.getBoolean(KEY_LIB_FOLDER_ONLY, false)
        set(value) = sp.edit().putBoolean(KEY_LIB_FOLDER_ONLY, value).apply()

    /**
     * 用户授权的曲库目录树 URI。
     *
     * 目录树授权能跨重启保留，但要记下 URI 才能在下次冷启动直接复用；
     * **授权随时可能被用户在系统设置里撤销**，所以真正开扫前还要复查一次（见
     * [AudioLibrary.hasTreePermission]），不能只看这个字符串在不在。
     */
    var libraryFolderUri: String?
        get() = sp.getString(KEY_LIB_FOLDER_URI, null)
        set(value) = sp.edit().putString(KEY_LIB_FOLDER_URI, value).apply()

    /** 用户给这个文件夹起的显示名（SAF 给不出好看的路径，只能自己记）。 */
    var libraryFolderName: String?
        get() = sp.getString(KEY_LIB_FOLDER_NAME, null)
        set(value) = sp.edit().putString(KEY_LIB_FOLDER_NAME, value).apply()

    /** 是否已经成功扫描过至少一次（用于区分「首次进入」与「真的没有音乐」）。 */
    var hasScannedOnce: Boolean
        get() = sp.getBoolean(KEY_SCANNED, false)
        set(value) = sp.edit().putBoolean(KEY_SCANNED, value).apply()

    /** 用户授权给「扫描文件夹」的目录树 URI；记下来所以重开 App 不用再选一次。 */
    var kwmFolderUri: String?
        get() = sp.getString(KEY_KWM_FOLDER, null)
        set(value) = sp.edit().putString(KEY_KWM_FOLDER, value).apply()

    /** 用户给这个文件夹起的显示名（SAF 给不了人类可读的路径，只能自己记）。 */
    var kwmFolderName: String?
        get() = sp.getString(KEY_KWM_FOLDER_NAME, null)
        set(value) = sp.edit().putString(KEY_KWM_FOLDER_NAME, value).apply()

    /**
     * 已经解密过的 `.kwm`（`名字|大小`）。
     *
     * 用来在列表里标注「已解密」并避免重复产出副本 —— 用户很容易对同一个文件再点一次，
     * 而那次的结果只会是库里多出一份「歌名 (2).mp3」。
     */
    var kwmDone: Set<String>
        get() = decodeArray(sp.getString(KEY_KWM_DONE, null)).toSet()
        set(value) = sp.edit().putString(KEY_KWM_DONE, encodeArray(value.toList())).apply()

    /**
     * 用户改过的歌曲信息（歌名/歌手/专辑），按 [com.melody.player.core.Song.key] 索引。
     *
     * 记录里带着编辑前的原值，「恢复原始信息」不需要回头翻扫描结果。
     * 整体存成一个 JSON 对象：编辑是低频操作，读整张表的代价可以忽略，
     * 拆成 `edit_<key>` 一堆零散键反而让「改了名没改歌手」这类半截状态有机可乘。
     */
    var songEdits: Map<String, SongEdit>
        get() = decodeSongEdits(sp.getString(KEY_SONG_EDITS, null))
        set(value) = sp.edit().putString(KEY_SONG_EDITS, encodeSongEdits(value)).apply()

    private fun decodeSongEdits(raw: String?): Map<String, SongEdit> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            val out = LinkedHashMap<String, SongEdit>()
            obj.keys().forEach { key ->
                val o = obj.optJSONObject(key) ?: return@forEach
                val title = o.optString("t").takeIf { it.isNotBlank() } ?: return@forEach
                out[key] = SongEdit(
                    title = title,
                    artist = o.optString("a").takeIf { it.isNotBlank() },
                    album = o.optString("b").takeIf { it.isNotBlank() },
                    originalTitle = o.optString("ot").takeIf { it.isNotBlank() } ?: "未命名曲目",
                    originalArtist = o.optString("oa").takeIf { it.isNotBlank() },
                    originalAlbum = o.optString("ob").takeIf { it.isNotBlank() }
                )
            }
            out
        }.getOrDefault(emptyMap())
    }

    private fun encodeSongEdits(edits: Map<String, SongEdit>): String {
        val obj = JSONObject()
        edits.forEach { (key, edit) ->
            obj.put(
                key,
                JSONObject().apply {
                    put("t", edit.title)
                    edit.artist?.let { put("a", it) }
                    edit.album?.let { put("b", it) }
                    put("ot", edit.originalTitle)
                    edit.originalArtist?.let { put("oa", it) }
                    edit.originalAlbum?.let { put("ob", it) }
                }
            )
        }
        return obj.toString()
    }

    private fun decodePlayHistory(raw: String?): List<PlayRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                // 没有 key 的记录是死条目：点不动也删不掉，直接丢
                val key = obj.optString("k").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                PlayRecord(
                    key = key,
                    title = obj.optString("t").takeIf { it.isNotBlank() } ?: "未命名曲目",
                    artist = obj.optString("a").takeIf { it.isNotBlank() },
                    lastPlayedAtSec = obj.optLong("at", 0L),
                    playCount = obj.optInt("c", 0)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun encodePlayHistory(records: List<PlayRecord>): String {
        val arr = JSONArray()
        records.forEach { record ->
            arr.put(
                JSONObject().apply {
                    put("k", record.key)
                    put("t", record.title)
                    record.artist?.let { put("a", it) }
                    put("at", record.lastPlayedAtSec)
                    put("c", record.playCount)
                }
            )
        }
        return arr.toString()
    }

    private fun decodeArray(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }.getOrDefault(emptyList())
    }

    private fun encodeArray(values: List<String>): String {
        val arr = JSONArray()
        values.forEach { arr.put(it) }
        return arr.toString()
    }

    private fun decodeHidden(raw: String?): List<HiddenSongEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val key = obj.optString("k").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                HiddenSongEntry(
                    key = key,
                    title = obj.optString("t").takeIf { it.isNotBlank() } ?: "未命名曲目",
                    artist = obj.optString("a").takeIf { it.isNotBlank() }
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeHidden(entries: List<HiddenSongEntry>): String {
        val arr = JSONArray()
        entries.forEach { entry ->
            arr.put(
                JSONObject().apply {
                    put("k", entry.key)
                    put("t", entry.title)
                    entry.artist?.let { put("a", it) }
                }
            )
        }
        return arr.toString()
    }

    private fun decodeArchived(raw: String?): List<ArchivedEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = obj.optString("n").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ArchivedEntry(
                    name = name,
                    title = obj.optString("t").takeIf { it.isNotBlank() } ?: "未命名曲目",
                    artist = obj.optString("a").takeIf { it.isNotBlank() },
                    album = obj.optString("b").takeIf { it.isNotBlank() },
                    durationMs = obj.optLong("d", 0L),
                    sizeBytes = obj.optLong("s", 0L),
                    archivedAtSec = obj.optLong("at", 0L),
                    sourceKey = obj.optString("k").takeIf { it.isNotBlank() },
                    lyricsEmbedded = obj.optBoolean("le", false)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeArchived(entries: List<ArchivedEntry>): String {
        val arr = JSONArray()
        entries.forEach { entry ->
            arr.put(
                JSONObject().apply {
                    put("n", entry.name)
                    put("t", entry.title)
                    entry.artist?.let { put("a", it) }
                    entry.album?.let { put("b", it) }
                    put("d", entry.durationMs)
                    put("s", entry.sizeBytes)
                    put("at", entry.archivedAtSec)
                    entry.sourceKey?.let { put("k", it) }
                    put("le", entry.lyricsEmbedded)
                }
            )
        }
        return arr.toString()
    }

    private fun decodePlaylists(raw: String?): List<Playlist> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                // id 是歌单的地址，改名/加歌都靠它定位；缺 id 的记录直接丢，
                // 留着的话界面上会出现一个点不动也删不掉的条目
                val id = obj.optString("i").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = obj.optString("n").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val keys = obj.optJSONArray("k") ?: JSONArray()
                Playlist(
                    id = id,
                    name = name,
                    songKeys = (0 until keys.length()).mapNotNull { keys.optString(it).takeIf(String::isNotBlank) },
                    createdAtSec = obj.optLong("at", 0L)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun encodePlaylists(playlists: List<Playlist>): String {
        val arr = JSONArray()
        playlists.forEach { playlist ->
            val keys = JSONArray()
            playlist.songKeys.forEach { keys.put(it) }
            arr.put(
                JSONObject().apply {
                    put("i", playlist.id)
                    put("n", playlist.name)
                    put("k", keys)
                    put("at", playlist.createdAtSec)
                }
            )
        }
        return arr.toString()
    }

    private companion object {
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_ACCENT = "accent_theme"
        const val KEY_SORT = "sort_mode"
        const val KEY_LAST_SONG = "last_song"
        const val KEY_PLAY_MODE = "play_mode"
        // 旧版的播放模式字段，只读不写，用于一次性迁移
        const val KEY_SHUFFLE = "shuffle"
        const val KEY_REPEAT = "repeat"
        const val KEY_AUTO_LYRICS = "auto_fetch_lyrics"
        const val KEY_SWIPE_SWITCH = "swipe_switch_song"
        const val KEY_LYRIC_TEXT_SIZE = "lyric_text_size"
        const val KEY_ARTWORK_SHAPE = "artwork_shape"
        const val KEY_LYRIC_PROVIDERS = "lyric_providers"
        const val KEY_AUTO_COVERS = "auto_fetch_covers"
        const val KEY_COVER_MIN_SCORE = "cover_min_score"
        const val KEY_COVER_REGION_CUSTOM = "cover_region_custom"
        const val KEY_COVER_REGIONS = "cover_regions"
        const val KEY_COVER_PREFIX = "cover_"
        const val KEY_IMPORTED_AUDIO = "imported_audio"
        const val KEY_LYRIC_PREFIX = "lyric_uri_"
        const val KEY_LYRIC_NAME_PREFIX = "lyric_name_"
        const val KEY_LYRIC_ORIGIN_PREFIX = "lyric_origin_"
        const val KEY_LYRIC_OFFSET_PREFIX = "lyric_offset_"
        const val KEY_PLAY_HISTORY = "play_history"
        const val KEY_RECENT_SEARCHES = "recent_searches"
        const val KEY_SCANNED = "scanned_once"
        const val KEY_HIDDEN = "hidden_songs"
        const val KEY_ARCHIVED = "archived_songs"
        const val KEY_PLAYLISTS = "playlists"
        const val KEY_LIB_FOLDER_ONLY = "library_folder_only"
        const val KEY_LIB_FOLDER_URI = "library_folder_uri"
        const val KEY_LIB_FOLDER_NAME = "library_folder_name"
        const val KEY_KWM_FOLDER = "kwm_folder_uri"
        const val KEY_KWM_FOLDER_NAME = "kwm_folder_name"
        const val KEY_KWM_DONE = "kwm_done"
        const val KEY_SONG_EDITS = "song_edits"
    }
}
