package com.melody.player.ui.player

import androidx.compose.runtime.Immutable
import com.melody.player.core.ArchivedEntry
import com.melody.player.core.ArtworkShape
import com.melody.player.core.HiddenSongEntry
import com.melody.player.core.LyricCopyEntry
import com.melody.player.core.LyricCopyGroup
import com.melody.player.core.LyricTextSize
import com.melody.player.core.Lyrics
import com.melody.player.core.PlayMode
import com.melody.player.core.Playlist
import com.melody.player.core.Playlists
import com.melody.player.core.Song
import com.melody.player.core.SortMode
import com.melody.player.core.kwm.KwmFile
import com.melody.player.core.online.CoverRegion
import com.melody.player.core.online.ITunesApi
import com.melody.player.core.online.ITunesHit
import com.melody.player.core.online.LyricProvider
import com.melody.player.core.online.OnlineSong
import com.melody.player.core.online.RegionPing

/**
 * 播放器界面状态。
 *
 * 高频变化的播放进度刻意**不放在这里**（单独一条 StateFlow），
 * 否则每秒 4 次进度更新会让整棵界面树跟着重组。
 */
@Immutable
data class PlayerUiState(
    /** 全量曲库（MediaStore + 手动导入），**已剔除被隐藏的曲目**。 */
    val songs: List<Song> = emptyList(),
    /** 搜索/排序之后、界面真正渲染的列表。 */
    val filtered: List<Song> = emptyList(),
    /** 被用户隐藏的曲目记录，按隐藏时间从新到旧。 */
    val hiddenSongs: List<HiddenSongEntry> = emptyList(),
    /** 隐藏记录里、设备上已经找不到的那些 key（文件被删了），设置页会如实标注。 */
    val hiddenMissingKeys: Set<String> = emptySet(),
    val loading: Boolean = false,
    /** 扫描过程中的进度文案。目录树扫描要逐个读标签，比查数据库慢得多，得让用户知道在动。 */
    val scanLabel: String = "",
    val permissionGranted: Boolean = false,
    val query: String = "",
    val searchActive: Boolean = false,
    val sort: SortMode = SortMode.TITLE,

    // ------------------------------------------------------------ 自定义歌单

    /** 用户自建的全部歌单，新建排在最前。 */
    val playlists: List<Playlist> = emptyList(),
    /** 曲库页当前正在查看哪个歌单；null = 看整库。 */
    val activePlaylistId: String? = null,
    /** 「加入歌单」正在为哪首歌选目标；非空时界面弹出歌单选择列表。 */
    val playlistAddTarget: Song? = null,

    // ---------------------------------------------------------------- 批量选择

    /**
     * 当前选中的曲目 key 集合。空集 = 不在多选模式。
     *
     * 存 key 而不是下标：批量操作跑完会刷新列表（排序、搜索、歌单一改都可能让
     * 行号整体移位），存下标的话"用户选中的那 5 首"会在刷新后变成另外 5 首。
     * 刷新时残留的 key（已隐藏/已从歌单移除的）由 [pruneSelection] 清掉。
     */
    val selection: Set<String> = emptySet(),

    /** 批量动作正在执行（加歌单 / 去封面…），界面据此禁用重复点击。 */
    val batchWorking: Boolean = false,

    /** 批量动作的进度文案（正在处理第几首 / 叫什么）。 */
    val batchLabel: String = "",

    /**
     * 「把标签写进音频文件…」正在等哪几首的目标确认；null = 现在没有待确认的请求。
     *
     * 存**目标集合**而不是一个"要不要弹框"的布尔：四个入口（单曲行菜单、
     * 多选批量条、顶栏、播放页）各自的作用对象不同，框必须照着被点的那一处来。
     * 让框自己去读 `selection`，单曲入口就会写到别人身上。
     *
     * 它住在状态里而不是某个页面的 local state，是因为触发它的按钮可能在任何页面
     * （播放页是全屏浮层、顶栏在 Scaffold 上），本地状态跨不过去。
     */
    val embedRequestKeys: Set<String>? = null,

    /**
     * 批量「加入歌单」正在为哪些歌选目标；非空时界面弹出歌单选择列表。
     *
     * 与 [playlistAddTarget] 分开是因为后者是单曲、这个是一批，
     * 混成一个字段会让"当前到底在为几首选歌单"这件事说不清。
     */
    val batchPlaylistTargetKeys: Set<String> = emptySet(),

    // -------------------------------------------------- 曲库只扫描指定文件夹

    /**
     * 是否**只**扫描用户指定的那个文件夹。
     *
     * 开着时不需要读取音频权限 —— 目录树授权已经够了。所以曲库页那个
     * 「需要访问本地音乐」的引导页此时不该出现（见 [permissionGranted]）。
     */
    val libraryFolderOnly: Boolean = false,
    /** 被限定扫描的那个文件夹的显示名。 */
    val libraryFolderName: String? = null,

    /** 当前播放队列（点歌时会把当时的列表整体作为队列）。 */
    val queue: List<Song> = emptyList(),
    val currentSong: Song? = null,
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val durationMs: Long = 0L,
    /** 播放模式：顺序 / 列表循环 / 单曲循环 / 随机，由界面上的一个按钮循环切换。 */
    val playMode: PlayMode = PlayMode.SEQUENTIAL,
    val lyrics: Lyrics = Lyrics.NONE,
    val lyricsLoading: Boolean = false,
    /** 该曲是否使用用户在 App 内选定的歌词（私有副本：本地文件或联网版本）。 */
    val lyricsImported: Boolean = false,
    /** 选定歌词的原始文件名 / 在线版本名，界面上如实展示来源。 */
    val lyricsImportedName: String? = null,
    /** 正在联网搜索歌词。 */
    val onlineSearching: Boolean = false,
    /** 搜到的候选歌曲；非空时界面弹出选择列表，让用户纠正可能错误的自动匹配。 */
    val onlineCandidates: List<OnlineSong> = emptyList(),
    /** 是否在本地歌词缺失时自动联网匹配。 */
    val autoFetchLyrics: Boolean = true,
    /**
     * 联网歌词允许用哪几家来源（默认两家都开）。
     *
     * 空集合是合法状态 —— 用户把两家都关了，此时联网取词整体停用。
     * 界面据此把"没有可用来源"说清楚，而不是让用户对着一个搜不出东西的按钮猜。
     */
    val lyricProviders: Set<LyricProvider> = LyricProvider.entries.toSet(),
    /**
     * 播放页封面是否支持「上下滑动切歌」。
     * 手势是肌肉记忆也是误触源，给个总开关；关掉后只保留胶囊和左右滑动换页。
     */
    val swipeSwitchSong: Boolean = true,
    /** 歌词字号档位（四挡离散值）。歌词页的所有行都按它的 scale 缩放。 */
    val lyricTextSize: LyricTextSize = LyricTextSize.STANDARD,
    /**
     * 封面形状。播放页大封面、迷你条缩略图、列表行缩略图统一按它显示 ——
     * 只有一处变、别处不变，用户只会觉得"改坏了"。
     */
    val artworkShape: ArtworkShape = ArtworkShape.ROUNDED,
    val playbackError: String? = null,

    // ------------------------------------------------------------ 在线专辑封面

    /** 本地标签没有内嵌封面时，是否自动联网找一张。 */
    val autoFetchCovers: Boolean = true,
    /** 已缓存到本地的封面张数。 */
    val coverCount: Int = 0,
    /** 封面缓存占用的字节数。 */
    val coverBytes: Long = 0L,
    /** 正在批量补齐封面。 */
    val coverWorking: Boolean = false,
    /** 批量补齐的进度文案（当前在处理哪一首）。 */
    val coverLabel: String = "",
    /** 批量补齐这一轮总共要处理多少首（进度条的百分比靠它）。 */
    val coverTotal: Int = 0,
    /** 已经处理完多少首。 */
    val coverDone: Int = 0,
    /** 当前这一首进行到哪一步（搜索候选 / 下载封面 / 写入缓存）。 */
    val coverStage: String = "",
    /** 这一轮里成功取到封面的张数。 */
    val coverFetched: Int = 0,
    /** 这一轮里"搜到了但没一张够像"的曲目数。 */
    val coverNoMatch: Int = 0,
    /** 这一轮里网络/写入失败、下次还能再试的曲目数。 */
    val coverFailed: Int = 0,
    /** 自动匹配的评分门槛。低于它的候选直接丢弃（宁可不给，也不给别人的封面）。 */
    val coverMinScore: Int = ITunesApi.MIN_SCORE,
    /** 是否按用户选的地区搜封面。关掉就用默认回退链。 */
    val coverRegionCustom: Boolean = false,
    /** 用户选的搜索地区（[coverRegionCustom] 为真时生效）。 */
    val coverRegions: List<CoverRegion> = CoverRegion.DEFAULT,
    /**
     * 各地区最近一次测速的结果，key 是地区 code。
     *
     * 只存在内存里、不落盘：延迟是**当下的网络状况**，昨天测的 120ms 今天毫无意义，
     * 存下来只会让用户对着一个过期数字做决定。
     */
    val coverRegionPings: Map<String, RegionPing> = emptyMap(),
    /** 正在依次测速（不是"测完了"）。 */
    val coverRegionPinging: Boolean = false,

    // ------------------------------------------------------ 专辑封面：用户自选

    /**
     * 正在给哪首歌挑封面；非空时界面弹出"封面来源"选择（在线搜索 / 从相册选择 / 移除）。
     * 与歌词的候选流程同一套交互：先确认对象，再确认内容。
     */
    val coverPickTarget: Song? = null,
    /** 选定的这首歌当前是否已有封面（决定"移除封面"这一项要不要出现）。 */
    val coverPickHasExisting: Boolean = false,
    /** 在线搜到的候选封面；非空时界面弹出候选列表，用户自己挑一张。 */
    val coverCandidates: List<ITunesHit> = emptyList(),
    /** 正在联网搜索封面候选。 */
    val coverSearching: Boolean = false,

    // ------------------------------------------------------ 编辑歌曲信息

    /**
     * 正在改哪首歌的名称/歌手/专辑；非空时界面弹出编辑对话框。
     * 与封面自选同一套交互：先确认对象，再确认内容。
     */
    val editTarget: Song? = null,
    /** 这首是否改过（决定编辑框里要不要出现「恢复原始信息」）。 */
    val editHasOriginal: Boolean = false,

    // ---------------------------------------------------------------- App 音乐库

    /** 已归档到 App 专属目录的曲目数。 */
    val archivedCount: Int = 0,
    /** 归档目录占用的字节数。 */
    val archivedBytes: Long = 0L,
    /**
     * 归档记录明细（已与磁盘对齐：文件没了的记录已经剔除）。
     *
     * 有了它才能**单独取消某一首**的归档 —— 只给个总数就只能整库清除。
     */
    val archivedEntries: List<ArchivedEntry> = emptyList(),
    /**
     * App 私有目录里的歌词副本，**按歌曲归并**后的视图。
     *
     * 状态里只留归并后的这一份：按份平铺的原始列表在"按歌收拾"这件事上没用 ——
     * 同一首歌的两份散在两处，用户既看不出它们是一家，也没法表达
     * "这首歌的歌词我都不要了"。
     */
    val lyricCopyGroups: List<LyricCopyGroup> = emptyList(),
    /**
     * 「歌词副本」弹层正在看哪几首歌；null = 没打开。
     *
     * 与 [embedRequestKeys] 同一个道理：入口有三个（曲库行菜单、播放页、多选批量条），
     * 各自的作用对象不同，弹层必须照着被点的那一处来 —— 让它自己去读 [selection]，
     * 从单曲入口点开就会列出一堆不相干的歌。
     */
    val lyricCopyRequestKeys: Set<String>? = null,
    /** 正在预览的那份副本正文；null 表示没开预览。 */
    val lyricPreview: LyricCopyPreview? = null,
    val archiving: Boolean = false,
    val archiveDone: Int = 0,
    val archiveTotal: Int = 0,
    /** 当前正在归档的曲目名，进度条下方显示。 */
    val archiveLabel: String = "",

    // ------------------------------------------------------- 酷我加密文件（KWM）

    /** 发现待解密的 `.kwm` 文件，按发现顺序排列。 */
    val kwmFiles: List<KwmFile> = emptyList(),
    /** 其中已经解密过的那些（`名字|大小`），列表里会标注出来。 */
    val kwmDoneKeys: Set<String> = emptySet(),
    val kwmScanning: Boolean = false,
    /** 扫描结果说明：没找到时的原因、被系统限制等等，都要如实说。 */
    val kwmScanNote: String? = null,
    /** 已授权的扫描文件夹显示名。 */
    val kwmFolderName: String? = null,
    val kwmWorking: Boolean = false,
    val kwmDone: Int = 0,
    val kwmTotal: Int = 0,
    /** 当前正在解密的文件名。 */
    val kwmLabel: String = "",
    /** 上一次批量解密的结果明细，按文件列出，失败的会在这里说明原因。 */
    val kwmResults: List<String> = emptyList()
) {
    val totalDurationMs: Long get() = songs.sumOf { it.durationMs }

    val hasLibrary: Boolean get() = songs.isNotEmpty()

    val isQueueEmpty: Boolean get() = queue.isEmpty()

    /** 是否处于批量选择模式（选中了至少一首）。 */
    val selectionMode: Boolean get() = selection.isNotEmpty()

    val selectedCount: Int get() = selection.size

    /**
     * 曲库页是不是正看着某个歌单（而不是整库）。 */
    val isPlaylistView: Boolean get() = activePlaylistId != null

    /** 当前查看的歌单；id 对不上（歌单刚被删）时为 null，界面自动退回整库。 */
    val activePlaylist: Playlist?
        get() = playlists.firstOrNull { it.id == activePlaylistId }

    /**
     * 当前歌单解析出来的曲目，**保持歌单里的顺序**。
     *
     * 歌单视图下曲库页渲染的就是它，不走 [filtered] —— 曲库那个全局排序
     * （按标题/按时长…）在这里必须让位，否则用户手排的播放顺序一打开就被打乱。
     */
    val playlistSongs: List<Song>
        get() = activePlaylist?.let { Playlists.resolve(it, songs) } ?: emptyList()

    /** 归档进度 0f~1f；总数未知时返回 0，界面据此显示不确定进度条。 */
    val archiveProgress: Float
        get() = if (archiveTotal <= 0) 0f else (archiveDone.toFloat() / archiveTotal).coerceIn(0f, 1f)

    /** 解密进度 0f~1f；总数未知时返回 0。 */
    val kwmProgress: Float
        get() = if (kwmTotal <= 0) 0f else (kwmDone.toFloat() / kwmTotal).coerceIn(0f, 1f)

    /**
     * 封面补齐进度 0f~1f；总数未知时返回 0。
     *
     * 与归档/解密不同，这里的总数是**已知**的（待处理的曲目数一开始就算出来了），
     * 所以进度条可以给确定值 —— 一排"不知道还要多久"的滚动条，用户只会反复去点取消。
     */
    val coverProgress: Float
        get() = if (coverTotal <= 0) 0f else (coverDone.toFloat() / coverTotal).coerceIn(0f, 1f)
}

/** 预览某一份歌词副本时要读出来的正文。 */
@Immutable
data class LyricCopyPreview(
    /** 对应 [LyricCopyEntry.fileName]，删除时要靠它把预览关掉。 */
    val fileName: String,
    val label: String,
    val text: String
)
