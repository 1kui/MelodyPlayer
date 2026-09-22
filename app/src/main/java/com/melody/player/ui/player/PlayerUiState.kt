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
import com.melody.player.core.Song
import com.melody.player.core.SortMode
import com.melody.player.core.kwm.KwmFile
import com.melody.player.core.online.CoverRegion
import com.melody.player.core.online.ITunesApi
import com.melody.player.core.online.ITunesHit
import com.melody.player.core.online.LyricProvider
import com.melody.player.core.online.OnlineSong

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
    val permissionGranted: Boolean = false,
    /** 是否至少成功扫描过一次，用于区分「还没扫描」和「扫描完确实没有音乐」。 */
    val everScanned: Boolean = false,
    val query: String = "",
    val searchActive: Boolean = false,
    val sort: SortMode = SortMode.TITLE,
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
    /** App 私有目录里的歌词副本，按保存时间从新到旧（含自动匹配的缓存）。 */
    val lyricCopies: List<LyricCopyEntry> = emptyList(),
    /** 上面那些副本**按歌曲归并**后的视图，界面按歌展开管理。 */
    val lyricCopyGroups: List<LyricCopyGroup> = emptyList(),
    /** 歌词副本占用的字节数。 */
    val lyricCopiesBytes: Long = 0L,
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
