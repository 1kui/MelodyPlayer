package com.melody.player.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.melody.player.BuildConfig
import com.melody.player.core.ArchivedEntry
import com.melody.player.core.ArtworkShape
import com.melody.player.core.HiddenSongEntry
import com.melody.player.core.LyricTextSize
import com.melody.player.core.TimeFormat
import com.melody.player.core.kwm.KwmFile
import com.melody.player.core.kwm.KwmFiles
import com.melody.player.core.online.CoverRegion
import com.melody.player.core.online.ITunesApi
import com.melody.player.core.online.LyricProvider
import com.melody.player.core.online.RegionPing
import com.melody.player.ui.AboutInfo
import com.melody.player.ui.components.IconAction
import com.melody.player.ui.components.MelodyInfoSheet
import com.melody.player.ui.components.MelodyListSheet
import com.melody.player.ui.components.SectionCard
import com.melody.player.ui.components.SettingRow
import com.melody.player.ui.components.SheetFooterButton
import com.melody.player.ui.icons.MelodyIcons
import com.melody.player.ui.player.PlayerUiState
import com.melody.player.ui.rememberAboutActions
import com.melody.player.ui.theme.AccentTheme
import com.melody.player.ui.theme.ThemeMode

/**
 * 已归档曲目最多逐条展示多少首（展开「App 音乐库」里的已归档列表时）。
 * 同「已隐藏」：整段是一个 item，归档几百首时不能一口气铺开。
 */
private const val ARCHIVE_LIST_LIMIT = 40

/**
 * 待解密列表最多逐条展示多少个。同「已隐藏」的道理：整段是 LazyColumn 里的**一个 item**，
 * KWM 目录动辄上百个文件，一口气铺开会让设置页卡顿。超出的部分用下方的「一键解密全部」兜。
 */
private const val KWM_LIST_LIMIT = 40

/**
 * 解密结果明细最多列多少条。批量解密 200 首时，200 行「已解密并归档」没有信息量。
 */
private const val KWM_RESULT_LIMIT = 20

// ---------------------------------------------------------------------------
// 排版栅格
//
// 设置页原来每个区块各写各的 16.dp / 18.dp / 12.dp，凑在一起就是「乱」的来源。
// 这里收成四个常量，页面上所有卡片、按钮行、展开子行的边距都从这里取：
//
//   卡片内左右内边距 = CardPad            展开子行的文字左边界 = RowInset
//   段内卡片间距     = CardGap            段与段之间再叠一个 GroupGap
// ---------------------------------------------------------------------------

/** 卡片内左右内边距的唯一来源。 */
private val CardPad = 16.dp

/** 同一段内两张卡片之间的留白。 */
private val CardGap = 12.dp

/** 换段时的额外留白（叠在 [CardGap] 之上，实际留给 28.dp）。 */
private val GroupGap = 16.dp

/**
 * 卡片内「图标 + 文字」行的文字左边界：16(卡片内边距) + 22(图标) + 16(间距)。
 *
 * 展开的子行（歌词副本的每一份）缩进到这条线上，与父行的文字对齐 ——
 * 子行各写各的 32dp/66dp 会看着像两套栅格。
 */
private val RowInset = 54.dp

@Composable
fun SettingsTopBar(modifier: Modifier = Modifier) {
    CenterAlignedTopAppBar(
        modifier = modifier,
        title = {
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    )
}

/**
 * 设置页。
 *
 * 排版按「段」组织：**曲库 → 播放与界面 → 在线内容 → 高级 → 关于**，
 * 段与段之间留白更大（[GroupGap]），同段内卡片靠得紧（[CardGap]）。
 * 十张卡片平铺时用户得逐个读标题才知道自己在看什么，分段之后扫一眼就能定位。
 *
 * 写这一页时的两条纪律：
 *  1. 任何内边距都取 [CardPad] / [RowInset]，不再出现临时数字；
 *  2. 同一件事的入口只能有一个位置（例如「清空待解密列表」只出现在列表下方），
 *     否则用户记住的位置会随状态漂移。
 */
@Composable
fun SettingsContent(
    state: PlayerUiState,
    themeMode: ThemeMode,
    accent: AccentTheme,
    onThemeModeChange: (ThemeMode) -> Unit,
    onAccentChange: (AccentTheme) -> Unit,
    onUnhide: (String) -> Unit,
    onRestoreAllHidden: () -> Unit,
    onRefresh: () -> Unit,
    onImportAudio: () -> Unit,
    onArchiveAll: () -> Unit,
    onCancelArchive: () -> Unit,
    onClearArchive: () -> Unit,
    onUnarchive: (ArchivedEntry) -> Unit,
    onAutoFetchLyricsChange: (Boolean) -> Unit,
    onKwmScanDevice: () -> Unit,
    onKwmPickFolder: () -> Unit,
    onKwmRescanFolder: () -> Unit,
    onKwmPickFiles: () -> Unit,
    onKwmRemove: (String) -> Unit,
    onKwmClearList: () -> Unit,
    onKwmDecrypt: (KwmFile) -> Unit,
    onKwmDecryptAgain: (KwmFile) -> Unit,
    onKwmDecryptAll: () -> Unit,
    onKwmCancel: () -> Unit,
    onAutoFetchCoversChange: (Boolean) -> Unit,
    onBackfillCovers: () -> Unit,
    onCancelBackfillCovers: () -> Unit,
    onClearCoverCache: () -> Unit,
    /** 重新解析整个曲库的内嵌封面（丢缓存重读文件）。 */
    onReparseEmbedded: () -> Unit,
    onSwipeSwitchSongChange: (Boolean) -> Unit,
    onArtworkShapeChange: (ArtworkShape) -> Unit,
    onLyricTextSizeChange: (LyricTextSize) -> Unit,
    onLyricProviderChange: (LyricProvider, Boolean) -> Unit,
    onCoverMinScoreChange: (Int) -> Unit,
    onCoverRegionCustomChange: (Boolean) -> Unit,
    onToggleCoverRegion: (CoverRegion) -> Unit,
    /** 依次实测各地连接延迟（进这一栏会自动跑一次，也可以手动重测）。 */
    onProbeCoverRegions: () -> Unit,
    onLibraryFolderOnlyChange: (Boolean) -> Unit,
    onPickLibraryFolder: () -> Unit,
    onClearLibraryFolder: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    var confirmClear by remember { mutableStateOf(false) }
    var confirmClearKwm by remember { mutableStateOf(false) }
    var pendingUnarchive by remember { mutableStateOf<ArchivedEntry?>(null) }
    val aboutActions = rememberAboutActions()

    /**
     * 当前打开的那条说明。
     *
     * 状态提在这一层而不是每条说明自己拿着：弹层是**独立的一层**，它盖住整页时
     * 页面里那些行仍可能被 LazyColumn 回收 —— 状态挂在行上，行一没弹层就跟着消失。
     * 放在这一层，谁来谁走都不影响。同一时刻只会有一条（弹层是模态的）。
     */
    var helpTopic by remember { mutableStateOf<HelpTopic?>(null) }

    /** 「已隐藏的曲目」的弹层是否打开。 */
    var hiddenSheetOpen by remember { mutableStateOf(false) }

    // 折叠状态：默认都收起，点标题行才展开。整页本来就长，列表默认铺开很难受
    var archivedExpanded by rememberSaveable { mutableStateOf(false) }

    pendingUnarchive?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingUnarchive = null },
            title = {
                Text(
                    text = "取消「${entry.title}」的归档？",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Text(
                    "会删掉 App 音乐库里的这份副本（${formatBytes(entry.sizeBytes)}）和归档时留下的" +
                        "歌词快照，并释放这部分空间。\n" +
                        "你的原文件不会被删：取消之后，它会重新出现在曲库列表里" +
                        "（之前因为 App 库里有副本，列表里只显示副本那一行）。\n" +
                        "注意：如果这首歌的原文件已经不在设备上了，这份副本就是仅存的一份 —— " +
                        "删掉之后不可恢复。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingUnarchive = null
                        onUnarchive(entry)
                    }
                ) { Text("取消归档", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnarchive = null }) { Text("保留") }
            }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除 App 音乐库？") },
            text = {
                Text(
                    "会删掉 App 专属目录里的 ${state.archivedCount} 个音频文件" +
                        "（共 ${formatBytes(state.archivedBytes)}）和它们的歌词副本。" +
                        "如果某些歌的原文件你已经删了，这些副本就是仅存的一份 —— 删掉之后不可恢复。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClearArchive()
                    }
                ) { Text("清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }
    if (confirmClearKwm) {
        AlertDialog(
            onDismissRequest = { confirmClearKwm = false },
            title = { Text("清空待解密列表？") },
            text = {
                Text(
                    "只会清掉列表里这 ${state.kwmFiles.size} 项记录。" +
                        "你的 .kwm 源文件不动，已经解密归档到 App 音乐库的歌也不动 —— " +
                        "下次重新扫描一下就能把列表找回来。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearKwm = false
                        onKwmClearList()
                    }
                ) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearKwm = false }) { Text("取消") }
            }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = CardPad,
            end = CardPad,
            top = CardGap,
            bottom = contentPadding.calculateBottomPadding() + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(CardGap)
    ) {
        // ================================================================ 段一：曲库

        item(key = "library") {
            SectionCard(title = "曲库") {
                val imported = state.songs.count { it.imported }
                SettingRow(
                    icon = MelodyIcons.MusicNote,
                    title = "曲目总数",
                    subtitle = buildString {
                        append("总时长 ${TimeFormat.durationText(state.totalDurationMs)}")
                        if (imported > 0) append(" · 手动导入 $imported 个文件")
                    },
                    trailing = {
                        Text(
                            text = "${state.songs.size} 首",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
                RowDivider()
                ActionRow {
                    ChipButton(
                        text = "重新扫描",
                        icon = MelodyIcons.Refresh,
                        onClick = onRefresh,
                        modifier = Modifier.weight(1f)
                    )
                    ChipButton(
                        text = "导入文件",
                        icon = MelodyIcons.ImportMusic,
                        onClick = onImportAudio,
                        modifier = Modifier.weight(1f)
                    )
                }
                RowDivider()
                SettingRow(
                    icon = MelodyIcons.Folder,
                    title = "只扫描指定文件夹",
                    subtitle = state.libraryFolderName?.let { "当前限定：$it" }
                        ?: "开启后曲库里只有那个文件夹里的歌；关掉则扫描整机"
                ) {
                    Switch(
                        checked = state.libraryFolderOnly,
                        onCheckedChange = onLibraryFolderOnlyChange
                    )
                }
                RowDivider()
                ActionRow {
                    ChipButton(
                        text = if (state.libraryFolderName == null) "选择文件夹" else "换一个文件夹",
                        icon = MelodyIcons.Folder,
                        onClick = onPickLibraryFolder
                    )
                    ChipButton(
                        text = "取消限定",
                        icon = MelodyIcons.Close,
                        onClick = onClearLibraryFolder,
                        enabled = state.libraryFolderOnly || state.libraryFolderName != null
                    )
                }
                FoldableHelp(
                    HelpTopic(
                        label = "限定文件夹是怎么扫的",
                        text = "开启后不再读取整机媒体库，只用系统文件选择器授权的那一个文件夹（连子目录一起），" +
                            "所以不需要「音乐和音频」权限，只有这一个目录的读取权。\n" +
                            "逐个文件读取标签会比整机扫描慢一些（大曲库可能要走十几秒），" +
                            "扫描过程中曲库页会显示已经找到多少首。\n" +
                            "手动导入的文件和归档到 App 音乐库的副本不受这个开关影响，它们始终在曲库里。" +
                            "文件夹授权可以被系统在设置里撤销，真被撤销时这里会提示并自动退回整机扫描。"
                    )
                ) { helpTopic = it }
            }
        }

        item(key = "app-library") {
            SectionCard(title = "App 音乐库") {
                SettingRow(
                    icon = MelodyIcons.Archive,
                    title = "已归档",
                    subtitle = "复制到 App 专属目录，清理工具不会动这里",
                    trailing = {
                        Text(
                            text = if (state.archivedCount > 0) {
                                "${state.archivedCount} 首 · ${formatBytes(state.archivedBytes)}"
                            } else {
                                "还没有"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.archivedCount > 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                )
                if (state.archiving) {
                    Column(modifier = Modifier.padding(horizontal = CardPad, vertical = 10.dp)) {
                        LinearProgressIndicator(
                            progress = { state.archiveProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "正在归档 ${state.archiveDone}/${state.archiveTotal}" +
                                    state.archiveLabel.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onCancelArchive) { Text("取消") }
                        }
                    }
                } else {
                    RowDivider()
                    ActionRow {
                        ChipButton(
                            text = "一键归档全部",
                            icon = MelodyIcons.Archive,
                            onClick = onArchiveAll,
                            modifier = Modifier.weight(1f)
                        )
                        ChipButton(
                            text = "清除归档",
                            icon = MelodyIcons.Delete,
                            onClick = { if (state.archivedCount > 0) confirmClear = true },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (state.archivedEntries.isNotEmpty()) {
                    RowDivider()
                    CollapseRow(
                        icon = MelodyIcons.Archive,
                        title = "已归档曲目",
                        subtitle = if (archivedExpanded) {
                            "收起列表"
                        } else {
                            "点这一行展开：可单独取消某一首的归档"
                        },
                        trailing = "${state.archivedEntries.size} 首",
                        expanded = archivedExpanded,
                        onToggle = { archivedExpanded = !archivedExpanded }
                    ) {
                        val shown = state.archivedEntries.take(ARCHIVE_LIST_LIMIT)
                        shown.forEach { entry ->
                            ArchivedRow(
                                entry = entry,
                                onUnarchive = { pendingUnarchive = entry }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = RowInset),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                        if (state.archivedEntries.size > ARCHIVE_LIST_LIMIT) {
                            NoteText(
                                "只列出前 $ARCHIVE_LIST_LIMIT 首，共 ${state.archivedEntries.size} 首；" +
                                    "其余用上面的「清除归档」整库清掉。"
                            )
                        }
                        NoteText(
                            "「取消归档」只删 App 库里的那份副本；原文件不会被删，" +
                                "随后会重新出现在曲库里。"
                        )
                    }
                }

                FoldableHelp(
                    HelpTopic(
                        label = "归档是怎么回事、原文件会被删吗",
                        text = "「一键归档全部」会把曲库里的歌复制到 App 专属目录" +
                            "（Android/data/com.melody.player/files/library），归档时顺手把歌词写进文件的" +
                            "内嵌标签 —— 歌词与歌曲从此就是同一个文件，重装、换手机、拷到别处都不会丢。\n" +
                            "原文件不会被删除，也不会被隐藏：同一首歌只保留一行 —— 有 App 库副本时显示副本，" +
                            "副本哪天不在了（被清理工具删掉或自己删了），原文件那一行会自动回到列表。" +
                            "真的不想看到某首歌，请在该行点 ⋮ →「隐藏这首」。"
                    )
                ) { helpTopic = it }
            }
        }

        item(key = "hidden") {
            SectionCard(title = "已隐藏的曲目") {
                val hidden = state.hiddenSongs
                val openSheet = { hiddenSheetOpen = true }
                // 隐藏列表可能几百行，摊在设置页里会把整页撑长；而它平时用不上。
                // 改成一行入口 + 底部弹层：弹层是独立的一层，页面纹丝不动，
                // 看完划走就回到原处 —— 与说明文字用的是同一套交互。
                SettingRow(
                    icon = MelodyIcons.EyeOff,
                    title = if (hidden.isEmpty()) {
                        "还没有隐藏任何曲目"
                    } else {
                        "共 ${hidden.size} 首不参与列表与搜索"
                    },
                    subtitle = if (hidden.isEmpty()) {
                        "在曲库列表里点该行的 ⋮ →「隐藏这首」，把不是歌的音频移开"
                    } else {
                        "点这一行逐首恢复（只影响显示，不删文件）"
                    },
                    onClick = if (hidden.isEmpty()) null else openSheet,
                    trailing = if (hidden.isEmpty()) {
                        null
                    } else {
                        {
                            Icon(
                                imageVector = MelodyIcons.ChevronDown,
                                contentDescription = "查看已隐藏的曲目",
                                // ChevronDown 转过 -90° 就是向右的箭头（同一个几何只维护一份）
                                modifier = Modifier
                                    .size(18.dp)
                                    .rotate(-90f),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
            }
        }

        // ============================================================ 段二：播放与界面

        item(key = "gap-playback") { GroupSpacer() }

        item(key = "playback") {
            SectionCard(title = "播放") {
                SettingRow(
                    icon = MelodyIcons.SwapVertical,
                    title = "上下滑动切换歌曲",
                    subtitle = "在播放页封面上下滑动：上滑下一首，下滑上一首"
                ) {
                    Switch(
                        checked = state.swipeSwitchSong,
                        onCheckedChange = onSwipeSwitchSongChange
                    )
                }
            }
        }

        item(key = "appearance") {
            SectionCard(title = "外观") {
                SettingRow(
                    icon = MelodyIcons.Contrast,
                    title = "主题模式",
                    subtitle = "深色模式会随系统或按此处选择切换"
                )
                ChipFlow(
                    options = ThemeMode.entries.toList(),
                    selected = themeMode,
                    labelOf = { it.label },
                    onSelect = onThemeModeChange
                )
                Spacer(Modifier.height(10.dp))
                SettingRow(
                    icon = MelodyIcons.Palette,
                    title = "主题色",
                    subtitle = "仅替换主色家族，中性色板保持一致"
                )
                ChipFlow(
                    options = AccentTheme.entries.toList(),
                    selected = accent,
                    labelOf = { it.label },
                    onSelect = onAccentChange
                )
                Spacer(Modifier.height(10.dp))
                SettingRow(
                    icon = MelodyIcons.ArtworkShape,
                    title = "封面形状",
                    subtitle = "列表、迷你条、播放页的封面统一按这个形状显示"
                )
                ChipFlow(
                    options = ArtworkShape.entries.toList(),
                    selected = state.artworkShape,
                    labelOf = { it.label },
                    onSelect = onArtworkShapeChange
                )
                Spacer(Modifier.height(10.dp))
                SettingRow(
                    icon = MelodyIcons.TextSize,
                    title = "歌词字号",
                    subtitle = "改完歌词页立刻生效；入口统一在这里（播放页不再放 A− / A＋）"
                )
                ChipFlow(
                    options = LyricTextSize.entries.toList(),
                    selected = state.lyricTextSize,
                    labelOf = { it.label },
                    onSelect = onLyricTextSizeChange
                )
            }
        }

        // ============================================================== 段三：在线内容

        item(key = "gap-online") { GroupSpacer() }

        item(key = "lyrics") {
            SectionCard(title = "歌词") {
                SettingRow(
                    icon = MelodyIcons.Lyrics,
                    title = "当前曲目歌词来源",
                    subtitle = buildString {
                        append(state.currentSong?.title ?: "尚未选择曲目")
                        state.lyricsImportedName?.let { append(" · $it") }
                    },
                    trailing = {
                        Text(
                            text = when {
                                state.lyricsLoading -> "解析中"
                                !state.lyrics.isUsable -> "暂无"
                                state.lyrics.estimated -> "自动对齐"
                                else -> "精确同步"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                )
                RowDivider()
                SettingRow(
                    icon = MelodyIcons.CloudDownload,
                    title = "自动联网获取歌词",
                    subtitle = "本地完全没有歌词时，自动去网上匹配一份"
                ) {
                    Switch(
                        checked = state.autoFetchLyrics,
                        onCheckedChange = onAutoFetchLyricsChange
                    )
                }
                RowDivider()
                SettingRow(
                    icon = MelodyIcons.CloudMusic,
                    title = "网易云获取",
                    subtitle = "按关键词搜索；有官方翻译时合成中英对照"
                ) {
                    Switch(
                        checked = LyricProvider.NETEASE in state.lyricProviders,
                        onCheckedChange = { onLyricProviderChange(LyricProvider.NETEASE, it) }
                    )
                }
                RowDivider()
                SettingRow(
                    icon = MelodyIcons.CloudLyrics,
                    title = "LRCLIB 获取",
                    subtitle = "按歌名 + 歌手 + 时长签名查询；多数记录带时间轴"
                ) {
                    Switch(
                        checked = LyricProvider.LRCLIB in state.lyricProviders,
                        onCheckedChange = { onLyricProviderChange(LyricProvider.LRCLIB, it) }
                    )
                }
                if (state.lyricProviders.isEmpty()) {
                    NoteText(
                        "两个来源都关掉了：自动匹配和「联网获取歌词」都已停用。" +
                            "要联网取词，至少打开一个。"
                    )
                }
                FoldableHelp(
                    HelpTopic(
                        label = "歌词从哪来、联网匹配的规则",
                        text = "歌词读取顺序：用户选定的那份（App 内导入的 .lrc 或联网获取的版本）→ " +
                            "文件内嵌歌词（ID3v2 的 USLT/SYLT、FLAC 与 Ogg 的 LYRICS 注释、MP4 的 ©lyr 原子）→ " +
                            "音频同目录同名 .lrc → 自动联网匹配的缓存。\n" +
                            "用播放页右上角 ⋮ →「联网获取歌词」可以联网搜候选，两个来源一起搜、列表里标明各自出处：" +
                            "LRCLIB 按歌名 + 歌手 + 时长锁定同一个录音版本，库里多数记录带时间轴；" +
                            "网易云按关键词搜索，有官方翻译时会自动合成中英对照的双语歌词。" +
                            "候选列表里自己挑一条，被选中的那份会连来源一起记下来。" +
                            "选定的结果和导入的 .lrc 一样会存进 App 私有目录，退出重进、原文件被删都不会丢；" +
                            "归档到 App 库时还可以把歌词直接写进音频文件（MP3 写 USLT 帧，FLAC 写 Vorbis Comment），" +
                            "歌词与歌曲从此合并成一个文件。内嵌歌词若没有时间戳，会按总时长自动均匀对齐并如实标注。"
                    )
                ) { helpTopic = it }
            }
        }

        item(key = "covers") {
            SectionCard(title = "专辑封面") {
                SettingRow(
                    icon = MelodyIcons.AlbumArt,
                    title = "自动联网获取封面",
                    subtitle = "本地标签没有封面时，按歌名 + 歌手去 iTunes 搜一张"
                ) {
                    Switch(
                        checked = state.autoFetchCovers,
                        onCheckedChange = onAutoFetchCoversChange
                    )
                }
                RowDivider()
                ActionRow {
                    if (state.coverWorking) {
                        ChipButton(
                            text = "停止补齐",
                            icon = MelodyIcons.Close,
                            onClick = onCancelBackfillCovers,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        ChipButton(
                            text = "为曲库补齐封面",
                            icon = MelodyIcons.AlbumArt,
                            onClick = onBackfillCovers,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    ChipButton(
                        text = "清除封面缓存",
                        icon = MelodyIcons.Delete,
                        onClick = onClearCoverCache,
                        modifier = Modifier.weight(1f)
                    )
                }
                // 「重读内嵌封面」与上面两个是**不同层的事**，所以单独一行：
                // 上面清的是 App 自己下的缓存，这里丢的是"文件里那张"的解析结果。
                // 用户在别的播放器里换过图之后，只有这个能让他在 App 里看到新图。
                RowDivider()
                SettingRow(
                    icon = MelodyIcons.Refresh,
                    title = "重新解析内嵌封面",
                    subtitle = "丢掉缓存、从音频文件里重读一遍封面。" +
                        "在别的播放器里换过图时用这个；扫完会告诉你有几首真的带封面",
                    onClick = onReparseEmbedded
                )

                if (state.coverWorking) {
                    Column(modifier = Modifier.padding(horizontal = CardPad, vertical = 6.dp)) {
                        // 进度是**确定式**的：待处理首数一开始就算出来了，百分比是真实的。
                        // 原来那条滚动条写着"已处理 N 首"，而那个 N 是**缓存总数**、
                        // 不是本轮的进度 —— 数字与进度条说的不是同一件事，越看越不对
                        LinearProgressIndicator(
                            progress = { state.coverProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 10.dp)
                        )
                        Text(
                            text = buildString {
                                append("第 ")
                                append((state.coverDone + 1).coerceAtMost(state.coverTotal))
                                append(" / ")
                                append(state.coverTotal)
                                append(" 首")
                                state.coverStage.takeIf { it.isNotBlank() }
                                    ?.let { append(" · ").append(it) }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = state.coverLabel.ifBlank { "准备中…" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "已获取 ${state.coverFetched} 张 · 没找到 ${state.coverNoMatch} 首 · " +
                                "失败 ${state.coverFailed} 首",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                        )
                    }
                } else {
                    SettingRow(
                        icon = MelodyIcons.Folder,
                        title = "已缓存 ${state.coverCount} 张封面",
                        subtitle = "占用 ${formatBytes(state.coverBytes)}"
                    )
                }

                RowDivider()
                SettingRow(
                    icon = MelodyIcons.Globe,
                    title = "自定义获取地区",
                    subtitle = if (state.coverRegionCustom) {
                        "按下面勾选的顺序依次搜；中国大陆区没有音乐目录，因此不提供"
                    } else {
                        "关着时用默认顺序：中国台湾 → 中国香港 → 美国"
                    }
                ) {
                    Switch(
                        checked = state.coverRegionCustom,
                        onCheckedChange = onCoverRegionCustomChange
                    )
                }
                if (state.coverRegionCustom) {
                    // 进这一栏就自动测一轮延迟。用户打开开关的动机就是"想挑几个地区"，
                    // 而挑的依据只有实测量出来的数字 —— 让他先点一下"测速"才知道有这回事，
                    // 等于把这个判据藏起来了。测过就不再自动重测（结果还在这一屏上）。
                    LaunchedEffect(state.coverRegionCustom, state.coverRegionPings.isEmpty()) {
                        if (state.coverRegionCustom &&
                            state.coverRegionPings.isEmpty() &&
                            !state.coverRegionPinging
                        ) {
                            onProbeCoverRegions()
                        }
                    }

                    MultiChipFlow(
                        options = CoverRegion.entries.toList(),
                        selected = state.coverRegions.toSet(),
                        labelOf = { it.label },
                        onToggle = onToggleCoverRegion,
                        // 延迟直接标在**每个地区自己身上**：哪一行对应哪个数字不用来回找，
                        // 也省掉了原来那条与选项隔了半屏的摘要行
                        badgeOf = { regionPingBadge(state.coverRegionPings[it.code]) }
                    )
                    NoteText(
                        "最多同时选 ${CoverRegion.MAX_SELECTED} 个：每多一个地区就多一次接口调用，" +
                            "而 iTunes 这个接口有限流，选太多会让「补齐封面」慢得离谱。"
                    )
                    RowDivider()
                    SettingRow(
                        icon = MelodyIcons.Clock,
                        title = "连接延迟",
                        subtitle = if (state.coverRegionPinging) {
                            "正在依次测量各地…（每测一个刷新一次，结果标在各地区上）"
                        } else {
                            "实测一次搜索请求的往返耗时；「无法连接」表示这个地区现在取不到数据"
                        },
                        trailing = {
                            TextButton(
                                onClick = onProbeCoverRegions,
                                enabled = !state.coverRegionPinging
                            ) {
                                Text(if (state.coverRegionPinging) "测速中…" else "重新测速")
                            }
                        }
                    )
                    NoteText(
                        "每个地区后面标的就是它的实测延迟（没标上的是还没测到）。" +
                            "延迟只是「哪个区排在前面」的参考：它高不代表搜不到，只是每次搜索要多等一会儿。" +
                            "先按延迟从低到高勾，再用实际搜索验一验。"
                    )
                }

                RowDivider()
                SettingRow(
                    icon = MelodyIcons.Gauge,
                    title = "匹配评分下限",
                    subtitle = "低于这个分数就不贴封面：调低更容易匹配上，也更容易贴错",
                    trailing = {
                        Text(
                            text = "${state.coverMinScore} 分",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                )
                Slider(
                    value = state.coverMinScore.toFloat(),
                    onValueChange = { onCoverMinScoreChange(it.toInt()) },
                    valueRange = ITunesApi.MIN_SCORE_FLOOR.toFloat()..ITunesApi.MAX_SCORE.toFloat(),
                    // 每 10 分一挡：评分本身是粗粒度判断，给到 1 分的精度只会让人纠结
                    steps = (ITunesApi.MAX_SCORE - ITunesApi.MIN_SCORE_FLOOR) / 10 - 1,
                    modifier = Modifier.padding(horizontal = CardPad)
                )
                NoteText(
                    when {
                        state.coverMinScore <= 60 ->
                            "现在很宽松：能匹配上的更多，但可能给某首歌贴上别人的封面。"
                        state.coverMinScore >= 130 ->
                            "现在很严格：只收歌名、歌手、时长都对得上的候选，宁可没有封面。"
                        else -> "默认 90 分 ≈「歌名对得上，且歌手或时长至少对上一个」。"
                    }
                )
                FoldableHelp(
                    HelpTopic(
                        label = "封面从哪来、怎么匹配",
                        text = "来源是 Apple 的 iTunes Search API（公开接口，不需要密钥）：只用" +
                            "「歌名 + 歌手」做关键词，不上传本地文件。中国大陆区没有音乐目录，" +
                            "因此默认按中国台湾 → 中国香港 → 美国依次搜，也可以在「自定义获取地区」里改。\n" +
                            "自动匹配按分数挑最像的一条（满分 170）：歌名完全一致 100 分、只是包含 55 分、" +
                            "字面重合度低 20 分，对不上倒扣 60；歌手对得上加 40、对不上扣 20；" +
                            "时长差 3 秒内加 30、10 秒内加 15、超过 25 秒扣 40；候选带 Live／伴奏／翻唱／" +
                            "混音这类版本词、而你的标题里没有，再扣 8–70 分。总分不到「匹配评分下限」" +
                            "（默认 90 分）就不给封面 —— 贴错封面比暂时没有更麻烦。\n" +
                            "「为曲库补齐封面」不走打分：直接取搜索结果的第一条（接口本身按相关性排序），" +
                            "几百首时才用；不满意的可以单曲自选覆盖。它同样受「自定义获取地区」影响；" +
                            "取一张封面要过三步（搜索候选 → 下载封面 → 写入缓存），进度里会写出当前在哪一步。"
                    )
                ) { helpTopic = it }
            }
        }

        // ============================================================== 段四：高级

        item(key = "gap-kwm") { GroupSpacer() }

        item(key = "kwm") {
            SectionCard(title = "KWM音乐解密") {
                val doneKeys = KwmFiles.doneKeys(state.kwmFiles, state.kwmDoneKeys)
                val pending = state.kwmFiles.size - doneKeys.size

                SettingRow(
                    icon = MelodyIcons.LockOpen,
                    title = "待解密文件",
                    subtitle = buildString {
                        append(".kwm 是加密音频容器，解密后自动归档进 App 音乐库")
                        state.kwmFolderName?.takeIf { it.isNotBlank() }
                            ?.let { append("；已授权文件夹「$it」") }
                    },
                    trailing = {
                        Text(
                            text = when {
                                state.kwmFiles.isEmpty() -> "还没有"
                                pending > 0 -> "$pending 个待处理"
                                else -> "都已处理"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (pending > 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                )
                RowDivider()

                if (state.kwmWorking) {
                    Column(modifier = Modifier.padding(horizontal = CardPad, vertical = 10.dp)) {
                        // 进度按**件数**算：不再有转码阶段，一份文件就是一个刻度
                        val overall = state.kwmProgress
                        LinearProgressIndicator(
                            progress = { overall },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = buildString {
                                    append(
                                        "正在解密 " +
                                            "${(state.kwmDone + 1).coerceAtMost(state.kwmTotal)}" +
                                            "/${state.kwmTotal}"
                                    )
                                    if (state.kwmLabel.isNotBlank()) append(" · ${state.kwmLabel}")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onKwmCancel) { Text("取消") }
                        }
                    }
                } else {
                    // 找文件的两条路固定在左，选文件 + 重扫在右；两者都是「往列表里加东西」
                    ActionRow {
                        ChipButton(
                            text = "扫描设备",
                            icon = MelodyIcons.Refresh,
                            onClick = onKwmScanDevice,
                            modifier = Modifier.weight(1f)
                        )
                        ChipButton(
                            text = "指定文件夹",
                            icon = MelodyIcons.Folder,
                            onClick = onKwmPickFolder,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    ActionRow {
                        ChipButton(
                            text = "选择文件",
                            icon = MelodyIcons.FileMusic,
                            onClick = onKwmPickFiles,
                            modifier = Modifier.weight(1f)
                        )
                        if (state.kwmFolderName != null) {
                            ChipButton(
                                text = "重扫文件夹",
                                icon = MelodyIcons.Refresh,
                                onClick = onKwmRescanFolder,
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }

                    state.kwmScanNote?.let { note ->
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = CardPad, vertical = 10.dp)
                        )
                    }

                    if (state.kwmFiles.isNotEmpty()) {
                        RowDivider()
                        state.kwmFiles.take(KWM_LIST_LIMIT).forEach { file ->
                            KwmFileRow(
                                file = file,
                                done = KwmFiles.doneKeyOf(file) in doneKeys,
                                onDecrypt = { onKwmDecrypt(file) },
                                onDecryptAgain = { onKwmDecryptAgain(file) },
                                onRemove = { onKwmRemove(file.key) }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = RowInset),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                        if (state.kwmFiles.size > KWM_LIST_LIMIT) {
                            NoteText(
                                "只列出前 $KWM_LIST_LIMIT 个，共 ${state.kwmFiles.size} 个；" +
                                    "其余用「一键解密」一并处理。"
                            )
                        }
                        // 列表下方只放这一行：主操作在左、「清空列表」在右。
                        // 清空的位置固定在这里（原来会随「是否授权过文件夹」在上下两处漂移）
                        ActionRow {
                            ChipButton(
                                text = if (pending > 0) "一键解密（$pending）" else "一键解密",
                                icon = MelodyIcons.LockOpen,
                                onClick = onKwmDecryptAll,
                                modifier = Modifier.weight(1f)
                            )
                            ChipButton(
                                text = "清空列表",
                                icon = MelodyIcons.Delete,
                                onClick = { confirmClearKwm = true },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // 结果里失败的排前面：两百行「已解密并归档」里找那两条失败是最烦的
                        val (failed, succeeded) = state.kwmResults.partition {
                            !it.substringAfterLast("：").startsWith("已解密")
                        }
                        val shown = (failed + succeeded).take(KWM_RESULT_LIMIT)
                        if (shown.isNotEmpty()) {
                            RowDivider()
                            Text(
                                text = "解密结果",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(
                                    start = CardPad,
                                    top = 10.dp,
                                    bottom = 4.dp
                                )
                            )
                            shown.forEach { line ->
                                val bad = !line.substringAfterLast("：").startsWith("已解密")
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (bad) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(horizontal = CardPad, vertical = 2.dp)
                                )
                            }
                            if (state.kwmResults.size > KWM_RESULT_LIMIT) {
                                NoteText("（另有 ${state.kwmResults.size - KWM_RESULT_LIMIT} 条未显示）")
                            }
                        }
                    }
                }

                // 只留「原理 + 怎么找文件」两件真正影响使用的事，一次读完
                FoldableHelp(
                    HelpTopic(
                        label = "解密原理、怎么找文件",
                        text = ".kwm 是一种加密音频容器：文件头 1KB 是明文说明，之后的音频整段用" +
                            "「固定口令 ⊕ 文件密钥」异或加密。解密就是把这段异或回来 —— " +
                            "无损、可逆，不联网、不上传，全程在本机完成。\n" +
                            "三条路找文件：「扫描设备」查系统媒体库最省事，但 .kwm 不是系统认识的" +
                            "媒体类型，Android 11 之后多半扫不到；「指定文件夹」授权音乐目录后递归查找，" +
                            "授权跨重启保留，新系统上最可靠；「选择文件」手动多选，一定可用。\n" +
                            "解密结果落进 App 专属目录并登记进曲库，接着就能播；原 .kwm 不会被删除，" +
                            "也不会被改动。"
                    )
                ) { helpTopic = it }
            }
        }

        // ============================================================== 段五：关于

        item(key = "gap-about") { GroupSpacer() }

        item(key = "about") {
            SectionCard(title = "关于") {
                // 品牌标：与启动图标、通知图标同一套几何（aurora 渐变底 + 五根白色竖条）。
                // 这里刻意用渐变而不是主题色：主题色是用户可换的，而这个标要和桌面上的
                // App 图标长得一样，否则"关于"里那个方块和桌面图标对不上，看着像两张皮。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CardPad, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(MaterialTheme.shapes.large)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        Color(0xFF00C2FF),
                                        Color(0xFF2563FF),
                                        Color(0xFF7C3AED)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = MelodyIcons.PulseBars,
                            contentDescription = "旋律",
                            modifier = Modifier.size(34.dp),
                            tint = Color.White
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "旋律",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Melody Player · ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                RowDivider()
                SettingRow(
                    icon = MelodyIcons.Info,
                    title = "版本 ${BuildConfig.VERSION_NAME}",
                    subtitle = "本地音乐播放器 · 构建号 ${BuildConfig.VERSION_CODE}"
                )
                RowDivider()
                SettingRow(
                    icon = MelodyIcons.Group,
                    title = "加入 QQ 群",
                    subtitle = "${AboutInfo.QQ_GROUP} · 交流用法、反馈问题、提需求",
                    onClick = aboutActions.onJoinGroup
                )
                SettingRow(
                    icon = MelodyIcons.Code,
                    title = "开源仓库",
                    subtitle = AboutInfo.REPO_URL.removePrefix("https://"),
                    onClick = aboutActions.onOpenRepo
                )
                Text(
                    text = "界面基于 Jetpack Compose + Material 3。所有图标均由代码绘制的矢量路径" +
                        "生成（ImageVector 路径指令与矢量 drawable），工程内不含任何位图资源。\n" +
                        "在线歌词与封面只发送歌名、歌手用于检索，不会上传任何本地文件；" +
                        "KWM 解密全程在本机完成，不联网、不上传。\n" +
                        "解密出来的音频按原格式（MP3 / FLAC / OGG 等）直接归档，不做二次转码 —— " +
                        "因此 APK 里不含任何音频编码器。\n" +
                        "这是个自用工具，不承诺商业级维护；用得有问题欢迎到 QQ 群或仓库提 issue。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = CardPad, vertical = 12.dp)
                )
            }
        }
    }

    // 说明弹层：挂在 LazyColumn **外面**（但仍在同一个组合作用域里）。
    // 放在列表的某一项里也能跑，但那一项一旦被滚出可视区就会被回收，
    // 挂在它上面的弹层会跟着消失 —— 而弹层是模态的，用户正读着它。
    helpTopic?.let { topic ->
        MelodyInfoSheet(
            title = topic.label,
            text = topic.text,
            onDismiss = { helpTopic = null }
        )
    }

    // 「已隐藏的曲目」的弹层。同样挂在列表外面：列表里的行会被 LazyColumn 回收，
    // 状态（和弹层）挂在行上，行一没就跟着没了
    if (hiddenSheetOpen) {
        HiddenSongsSheet(
            hidden = state.hiddenSongs,
            missingKeys = state.hiddenMissingKeys,
            onUnhide = onUnhide,
            onRestoreAll = onRestoreAllHidden,
            onDismiss = { hiddenSheetOpen = false }
        )
    }
}

/**
 * 「已隐藏的曲目」底部弹层。
 *
 * 以前是设置页里的一段折叠列表，两个毛病：
 *  1. 它只铺前 30 首（整段只是 LazyColumn 里的一个 item，不能指望它懒加载），
 *     隐藏了几百首时除了"全部恢复"就没法逐首挑；
 *  2. 摊开之后整页被顶长，读完还得重新找那一段在哪。
 * 换成弹层之后这两个都没了：弹层里是**真**的 LazyColumn（要多少有多少，
 * 不必再截断），页面也纹丝不动。
 */
@Composable
private fun HiddenSongsSheet(
    hidden: List<HiddenSongEntry>,
    missingKeys: Set<String>,
    onUnhide: (String) -> Unit,
    onRestoreAll: () -> Unit,
    onDismiss: () -> Unit
) {
    MelodyListSheet(
        title = "已隐藏的曲目",
        subtitle = if (hidden.isEmpty()) {
            null
        } else {
            "${hidden.size} 首 · 只影响显示，不删文件"
        },
        onDismiss = onDismiss,
        footer = if (hidden.isEmpty()) {
            null
        } else {
            { SheetFooterButton(text = "全部恢复（${hidden.size}）", onClick = onRestoreAll) }
        }
    ) {
        if (hidden.isEmpty()) {
            Text(
                text = "媒体库扫描会把一些不是歌的音频也算进来。\n\n" +
                    "在曲库列表里点该行的 ⋮ →「隐藏这首」，它就不再出现在曲库和搜索结果里" +
                    "（只影响显示，不删文件，也不动已排好的播放队列）。" +
                    "隐藏过的曲目会列在这里，随时可以恢复。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(items = hidden, key = { it.key }) { entry ->
                    val missing = entry.key in missingKeys
                    SettingRow(
                        icon = MelodyIcons.EyeOff,
                        title = entry.title,
                        // 文件不在设备上要如实说：否则用户按了"恢复"却什么都没回来，
                        // 会以为是恢复没生效，而不是"这个文件本来就没了"
                        subtitle = buildString {
                            entry.artist?.let { append(it) }
                            if (missing) {
                                if (isNotEmpty()) append(" · ")
                                append("文件已不在设备上")
                            }
                        }.ifEmpty { null },
                        trailing = {
                            TextButton(onClick = { onUnhide(entry.key) }) { Text("恢复") }
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }
}

/**
 * 段与段之间的留白。
 *
 * LazyColumn 的 `verticalArrangement` 已经给 [CardGap]，这里再叠一个 [GroupGap]，
 * 于是「同段 12 / 跨段 28」两档间距一眼能分辨 —— 靠间距分组比再加一行段标题干净。
 */
@Composable
private fun GroupSpacer() {
    Spacer(Modifier.height(GroupGap))
}

/** 卡片内的分隔线，左边距与行内图标对齐。 */
@Composable
private fun RowDivider(start: Dp = CardPad) {
    HorizontalDivider(
        modifier = Modifier.padding(start = start),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/** 卡片里的一行动作按钮：统一内外边距，两列等宽。 */
@Composable
private fun ActionRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CardPad, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** 卡片里的一句话说明，行距与字幕一致，避免和按钮行黏在一起。 */
@Composable
private fun NoteText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = CardPad, vertical = 8.dp)
    )
}

/** 字节数的人类可读写法，用于 App 音乐库的占用统计。 */
private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.0f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}

@Composable
private fun <T> ChipFlow(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit
) {
    // 用两行流式布局排布，避免在窄屏上被挤到换行错位
    Column(modifier = Modifier.padding(horizontal = CardPad, vertical = 4.dp)) {
        options.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { option ->
                    FilterChip(
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        label = { Text(labelOf(option)) },
                        modifier = Modifier.weight(1f),
                        leadingIcon = if (option == selected) {
                            {
                                Icon(
                                    imageVector = MelodyIcons.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else {
                            null
                        }
                    )
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * 胶囊右侧的一条小标注（延迟数字、状态词）。
 *
 * [warn] 不是"颜色更好看"的意思，而是一个承诺：标红的都是**用户需要处理**的状态。
 * 这里只有「无法连接」配得上它 —— 「测速中」会自己变，一闪一红只会让人以为出错了。
 */
internal data class ChipBadge(val text: String, val warn: Boolean = false)

/**
 * 一个地区在选项上要显示的延迟标注；`null` 表示这一项还没测过。
 *
 * 四种状态各有各的文案，别合并成一条横线：还没轮到 ≠ 正在测 ≠ 连不上 ——
 * 合并之后用户分不出"该等一下"和"该换个区"。
 *
 * 只有「连不上」带 [ChipBadge.warn]：它是唯一需要用户**做点什么**的状态
 * （换个区，或者过一会儿重测）；把"测速中"也标成警示色，测速时一闪一闪只会干扰。
 *
 * 抽成纯函数放这里（而不是直接写在组合项里）是这一页的老规矩：
 * 它写错了**不崩**，只是屏幕上少一个数字，靠真机肉眼很难复现，所以必须能进 JVM 单测。
 */
internal fun regionPingBadge(ping: RegionPing?): ChipBadge = when (ping) {
    null -> ChipBadge("未测速")
    RegionPing.Testing -> ChipBadge("测速中…")
    is RegionPing.Ok -> ChipBadge("${ping.ms} ms")
    RegionPing.Unreachable -> ChipBadge("无法连接", warn = true)
}

/**
 * 多选版的选择胶囊（[ChipFlow] 是单选）。
 *
 * 与单选版的差别只有两点：传的是选中**集合**而不是单个值，以及选中态全靠勾号表达。
 * 「一个都不选」这种边界不在这里拦 —— 它取决于业务含义（地区全不选等于没得搜，
 * 而别的多选项未必），所以由调用方的 [onToggle] 判断并给出提示。
 *
 * [badgeOf] 给每项挂一条状态标注（目前只有地区用它显示延迟）。
 * 标注画在胶囊**里面**而不是旁边：它描述的是"这个选项现在怎么样"，
 * 分开画就成了两列对不齐的信息，反而要多花一次眼睛去找对应关系。
 */
@Composable
private fun <T> MultiChipFlow(
    options: List<T>,
    selected: Set<T>,
    labelOf: (T) -> String,
    onToggle: (T) -> Unit,
    badgeOf: ((T) -> ChipBadge?)? = null
) {
    Column(modifier = Modifier.padding(horizontal = CardPad, vertical = 4.dp)) {
        options.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { option ->
                    val on = option in selected
                    val badge = badgeOf?.invoke(option)
                    val badgeSlot: (@Composable () -> Unit)? = if (badge != null) {
                        { ChipBadgeLabel(badge) }
                    } else {
                        null
                    }
                    FilterChip(
                        selected = on,
                        onClick = { onToggle(option) },
                        label = {
                            // 单行不换行：换行会让同一行的两个胶囊高低不一，
                            // 而这里的文字都短，省略号是永远用不到的安全网
                            Text(labelOf(option), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        modifier = Modifier.weight(1f),
                        leadingIcon = if (on) {
                            {
                                Icon(
                                    imageVector = MelodyIcons.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else {
                            null
                        },
                        trailingIcon = badgeSlot
                    )
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 胶囊里的状态标注。比标签小一档，免得把地区名挤掉。 */
@Composable
private fun ChipBadgeLabel(badge: ChipBadge) {
    Text(
        text = badge.text,
        style = MaterialTheme.typography.labelSmall,
        color = if (badge.warn) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1
    )
}

/**
 * 展开 / 收起的统一动画。
 *
 * 设置页里所有「点一下才铺开」的区域 —— 三处折叠列表、每一首歌的副本 ——
 * 都从这里取动画，保证点哪一处的手感都一样：
 *
 *  - 展开用竖向弹簧（dampingRatio 0.7），先冲过去一点点再落回。纯 tween 是「匀速推开」，
 *    在几百行的长列表上会显得拖沓；带一点回弹才有「弹开」的感觉；
 *  - 收起**不弹**（dampingRatio 1f）：收的时候内容在往下缩，回弹会把最后一行推出去又拽回来，
 *    看着像抖动；
 *  - 透明度只跟 110–150ms 一小段，避免「尺寸在动、字还是糊的」；
 *  - `expandFrom` / `shrinkTowards` 都取 Top，展开方向一致地向下长出来。
 *
 * 收起时内容为空、高度自然为 0，所以没展开的卡片里不会留一条空白。
 */
@Composable
private fun ExpandableContent(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(
            animationSpec = spring(
                dampingRatio = 0.7f,
                stiffness = Spring.StiffnessMediumLow,
                visibilityThreshold = IntSize.VisibilityThreshold
            ),
            expandFrom = Alignment.Top
        ) + fadeIn(animationSpec = tween(durationMillis = 140)),
        exit = shrinkVertically(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium,
                visibilityThreshold = IntSize.VisibilityThreshold
            ),
            shrinkTowards = Alignment.Top
        ) + fadeOut(animationSpec = tween(durationMillis = 110))
    ) {
        // 这里必须套一层 Column —— 不是排版洁癖，是这个 API 的硬约束：
        // AnimatedVisibility 的内容槽只接受**一个**子项，它的 measure policy 是
        //     layout(maxWidth, maxHeight) { placeables.fastForEach { it.place(0, 0) } }
        // 即所有子项都放在 (0,0)、容器高度取子项最大值。直接塞多行进来就会
        // 全部叠在同一位置、卡片只剩一行高（v2.1.1 真机上就是这么炸的）。
        Column(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

/**
 * 折叠箭头的统一写法：方向翻转走弹簧，与展开 / 收起同一条曲线。
 *
 * 不做成「ChevronUp + ChevronDown 两个图标」是因为同一套几何只该有一份 ——
 * 旋转 180° 与换图标视觉等价，却少维护一个图标。角度的回弹比尺寸更安全：
 * 尺寸回弹过头会在卡片底部顶出一条空白，角度回弹只是箭头多转十几度再落回来。
 *
 * 尺寸由调用方给：折叠列表的标题行用 20dp。
 * （说明行不再用它 —— 说明改成弹层之后没有"两个状态"可翻，见 [FoldableHelp]。）
 */
@Composable
private fun CollapseChevron(expanded: Boolean, size: Dp) {
    val angle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        )
    )
    Icon(
        imageVector = MelodyIcons.ChevronDown,
        contentDescription = if (expanded) "收起" else "展开",
        modifier = Modifier
            .size(size)
            .rotate(angle),
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * 可折叠列表的标题行。
 *
 * 设置页里有几段列表是「平时用不上、一展开又可能几百行」的（已归档曲目、已保存的歌词、
 * 已隐藏的曲目），默认铺开只会把页面撑得很长。这里统一收成一行：点整行切换，右侧箭头跟着翻转。
 *
 * 三件套各司其职，别互相重复：**标题**说这是什么（区块标题已同名时才改说状态），
 * **副标题**说点开会怎样，**[trailing]** 放数量或占用。
 *
 * 标题行与展开体是一体的：展开动画写在 [ExpandableContent] 里，[content] 只管内容 ——
 * 调用方写成 `CollapseRow(...) { 列表 }` 就自动带上弹性，不会再出现"某一处忘了加动画"。
 */
@Composable
private fun CollapseRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    trailing: String? = null,
    content: @Composable () -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = CardPad, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (trailing != null) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(4.dp))
            CollapseChevron(expanded = expanded, size = 20.dp)
        }
        // 展开体由这一行自己管：调用方只把内容塞进 content，动画不会漏做
        ExpandableContent(visible = expanded) { content() }
    }
}

/**
 * 设置页里一条「读一次就够」的说明。
 *
 * [label] 是一句概括，要能当标题用；[text] 才是全文。两者放在一起是为了让调用处
 * 写成 `FoldableHelp(HelpTopic("...", "...")) { helpTopic = it }` ——
 * 一个对象、一次传递，不会出现"标题改了、弹层里还是旧标题"这种两处不同步。
 */
private data class HelpTopic(val label: String, val text: String)

/**
 * 折叠……不，是**弹出**来的说明文字。
 *
 * 设置页里有几段「读一次就够」的长文案（归档规则、KWM 解密原理、歌词来源、封面来源）。
 * 原来点一下是就地铺开，问题是：说明在卡片里的深度不一，铺开之后它下面的开关、
 * 按钮**全都被顶下去一格**，用户读完还得重新找刚才那一行；两段同时展开时页面长得没法看。
 * 现在改为弹一层（[com.melody.player.ui.components.MelodyInfoSheet]）：
 * 页面纹丝不动，读完划走就回到原处 —— 与曲库行的菜单是同一套交互。
 *
 * 箭头朝**右**（向下那个箭头是折叠的语义，会被读成"就在这一页铺开"）。
 * 图标从旋转的 [CollapseChevron] 换成固定的右箭头：这里没有"展开/收起"两个状态，
 * 一直在转的箭头只会让人以为下面还藏着东西。
 */
@Composable
private fun FoldableHelp(topic: HelpTopic, onOpen: (HelpTopic) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(topic) }
            .padding(horizontal = CardPad, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = MelodyIcons.Info,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = topic.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = MelodyIcons.ChevronDown,
            contentDescription = "查看说明",
            // ChevronDown 转过 -90° 就是向右的箭头（同一个几何只维护一份）
            modifier = Modifier
                .size(16.dp)
                .rotate(-90f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * App 音乐库里已归档的一行 —— 右侧「取消归档」只处理这一首。
 *
 * 与「清除归档」的区别是量级：整库清除是「我不想要这个功能了」，
 * 单首取消是「这一首我不想留在 App 库里」。后者在试听 KWM 解密的成果时最常用。
 */
@Composable
private fun ArchivedRow(entry: ArchivedEntry, onUnarchive: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = CardPad, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = MelodyIcons.Archive,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    entry.artist?.takeIf { it.isNotBlank() }?.let { append("$it · ") }
                    append(formatBytes(entry.sizeBytes))
                    append(" · ")
                    append(TimeFormat.ago(entry.archivedAtSec))
                    if (entry.lyricsEmbedded) append(" · 含内嵌歌词")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onUnarchive) { Text("取消归档") }
    }
}

/**
 * 待解密列表里的一行。
 *
 * 已经解过的显示「再解一次」（用户很可能删过副本、想再来一份），没解过的点锁形图标直接解。
 * 右侧垃圾桶只把这一项从列表里去掉，不碰磁盘上的文件 —— 所以不需要二次确认。
 */
@Composable
private fun KwmFileRow(
    file: KwmFile,
    done: Boolean,
    onDecrypt: () -> Unit,
    onDecryptAgain: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = CardPad, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = MelodyIcons.FileMusic,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (done) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    // 有些来源（MediaStore）拿不到大小，宁可不说也别显示成 0 B
                    if (file.sizeBytes > 0L) {
                        append(formatBytes(file.sizeBytes))
                        append(" · ")
                    }
                    append(file.source.label)
                    if (done) append(" · 已解密")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        if (done) {
            TextButton(onClick = onDecryptAgain) { Text("再解一次") }
        } else {
            IconAction(
                imageVector = MelodyIcons.LockOpen,
                contentDescription = "解密这个文件",
                onClick = onDecrypt,
                iconSize = 20.dp,
                touchSize = 40.dp,
                tint = MaterialTheme.colorScheme.primary
            )
        }
        IconAction(
            imageVector = MelodyIcons.Delete,
            contentDescription = "从列表里去掉",
            onClick = onRemove,
            iconSize = 20.dp,
            touchSize = 40.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ChipButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    FilterChip(
        selected = false,
        onClick = onClick,
        enabled = enabled,
        label = { Text(text) },
        modifier = modifier,
        leadingIcon = {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    )
}
