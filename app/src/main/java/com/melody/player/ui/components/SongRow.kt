package com.melody.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.melody.player.core.Song
import com.melody.player.core.TimeFormat
import com.melody.player.ui.icons.MelodyIcons

/**
 * 行菜单里"去掉这一行"的动作在不同页面里的两种含义。
 *
 * 文案、提示与警示色绑在一起：这三样只要有一处对不上，用户就会按着一个
 * 说"只是移出队列"的按钮去删掉 App 里的文件副本。
 */
enum class RemoveStyle(val label: String, val hint: String, val danger: Boolean) {
    /** 曲库：删的是 App 导入的副本，原文件不受影响，但副本确实没了 —— 标红。 */
    LIBRARY("移出列表", "只删掉 App 导入的这份副本，设备里的原文件不动", danger = true),

    /** 播放队列：只是不在这儿排队了，什么都没丢 —— 不标红。 */
    QUEUE("移出队列", "只从播放队列里去掉，曲库和文件都不受影响", danger = false)
}

/**
 * 曲库列表的行。
 *
 * 正在播放的那一行：标题换成主色、右侧的时长换成跳动柱 —— 用户在长列表里
 * 一眼就能定位到当前曲目，不需要靠背景色差去猜。
 */
@Composable
fun SongRow(
    song: Song,
    index: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    /**
     * 「下一首播放」。传 null 时菜单里不出现这一项 ——
     * 在**播放队列**里它是句废话（那儿的顺序就是你拖出来的），
     * 一个点了没有意义的菜单项比没有这个菜单项更糟。
     */
    onPlayNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onHide: (() -> Unit)? = null,
    /**
     * 行菜单里那个"去掉这一行"的动作。
     *
     * 文案与警示色必须跟着**上下文**走：曲库里它是"删掉导入的副本"（会丢东西，标红），
     * 到了播放队列里同一个位置的动作其实是"移出队列"（什么都没丢，不该标红）。
     * 用同一句话描述两个不同的后果，是这类菜单最典型的一个坑 ——
     * 三个字段绑成一个枚举，就不会出现"改了标题忘了改提示"这种半截状态。
     */
    onRemove: (() -> Unit)? = null,
    removeStyle: RemoveStyle = RemoveStyle.LIBRARY,
    onArchive: (() -> Unit)? = null,
    onUnarchive: (() -> Unit)? = null,
    /** 手动联网取这张专辑封面（本地标签里没有封面时用户会想试一下）。 */
    onFetchCover: (() -> Unit)? = null,
    /** 改这首歌的歌名/歌手/专辑（App 内显示，不动文件）。 */
    onEditSong: (() -> Unit)? = null,
    /** 把这首歌加进某个自建歌单（会弹出歌单选择）。 */
    onAddToPlaylist: (() -> Unit)? = null,
    /** 歌单视图里：把这一首往上挪一位。到第一位时传 null，菜单项就不出现。 */
    onMoveUp: (() -> Unit)? = null,
    /** 歌单视图里：把这一首往下挪一位。 */
    onMoveDown: (() -> Unit)? = null,
    /** 歌单视图里：只从歌单移除，不动曲库。 */
    onRemoveFromPlaylist: (() -> Unit)? = null,
    /** 已经有 App 库副本（本条就是副本，或它的原文件已归档）。 */
    archived: Boolean = false,
    /**
     * 把这一首的歌词 / 封面写进音频文件（会弹出勾选框确认）。
     *
     * 之前这个功能只挂在多选底部的「写进文件」里，要长按进多选才找得到 ——
     * 而多数用户想写的就是眼前这一首。放进单曲菜单是最短的路径。
     */
    onEmbedTags: (() -> Unit)? = null,
    /**
     * 重新读一遍这个文件里的内嵌封面。
     *
     * 存在的理由：封面有缓存，而用户可能刚在别的播放器/工具里换了图。
     * 不给一条显式重读的入口，就只能靠重启 App 清缓存。
     */
    onReparseEmbedded: (() -> Unit)? = null,
    /**
     * 看一眼这首歌在 App 里存了哪几份歌词副本（可预览、可删）。
     *
     * 这个入口以前只长在**设置页**里（那份按全库列出的清单）—— 而用户的念头从来
     * 不是"我要去整理歌词目录"，是"这首歌的歌词不对劲"。放在这一行的菜单里，
     * 就是把他正看着的那首直接送进去。
     */
    onManageLyricCopies: (() -> Unit)? = null,
    /**
     * 多选模式下这一行处于选中状态。
     *
     * 为 null 时就是普通单选态：不出复选框、行菜单照常。
     * 用 Boolean? 而不是「bool + 另一个开关」是因为界面上只有两种状态，
     * 分成两个参数就会出现「复选框显示但没人管它」这种组合。
     */
    selected: Boolean? = null,
    /** 多选模式下点这一行（切换选中）。 */
    onToggleSelect: (() -> Unit)? = null,
    /** 长按进入多选并选中这一首。 */
    onBeginSelection: (() -> Unit)? = null,
    /**
     * 禁掉这一行内建的**长按**手势，只保留单击。
     *
     * 队列页要自己用长按拖动排序，而 `combinedClickable` 与外层的
     * `detectDragGesturesAfterLongPress` 是两个**互相竞争**的长按识别器：
     * 谁先判定成"长按"另一个就废掉，表现是拖动时而行不行、时而行。
     * 所以这里给行本身一个"我只负责单击"的开关，而不是在外面硬碰手势。
     */
    longPressEnabled: Boolean = true
) {
    var menuOpen by remember { mutableStateOf(false) }
    val selectionMode = selected != null
    val haptics = LocalHapticFeedback.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            // combinedClickable 而不是 clickable：长按是多选的入口，而 clickable 只给单击回调。
            // 已在多选态时长按不再嵌套进多选，改为切换这一行的选中 —— 长按去做"再确认一次"
            // 比什么都不做强，尤其是批量操作中途想再勾两首的时候。
            // longPressEnabled = false 时退回纯 clickable：调用方（队列页）自己接长按拖动，
            // 两个长按识别器同时挂在同一行上会互相抢手势。
            .then(
                if (longPressEnabled) {
                    Modifier.combinedClickable(
                        onClick = { (if (selectionMode) onToggleSelect ?: onClick else onClick)() },
                        onLongClick = {
                            if (onBeginSelection != null || onToggleSelect != null) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (selectionMode) onToggleSelect?.invoke() else onBeginSelection?.invoke()
                            }
                        }
                    )
                } else {
                    Modifier.clickable { onClick() }
                }
            )
            .padding(
                start = if (selectionMode) 8.dp else 16.dp,
                end = 4.dp,
                top = 8.dp,
                bottom = 8.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Checkbox(
                checked = selected == true,
                onCheckedChange = { onToggleSelect?.invoke() }
            )
            Spacer(Modifier.width(6.dp))
        }

        SongArtwork(song = song, size = 52.dp, corner = 12.dp)

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (archived) {
                    Spacer(Modifier.width(6.dp))
                    ArchiveBadge()
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${song.artistOrUnknown} · ${song.albumOrUnknown}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(8.dp))

        Box(
            modifier = Modifier.width(34.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isCurrent) {
                PlayingBars(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(width = 18.dp, height = 18.dp),
                    animate = isPlaying
                )
            } else {
                Text(
                    text = TimeFormat.clock(song.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 多选模式下不挂行菜单：那一整套「下一首播放 / 编辑信息…」都是针对这一首的，
        // 而用户此时的注意力在"选了哪些"上。留着只会让人在批量操作时误点单曲操作。
        if (!selectionMode) {
            Box {
                IconAction(
                    imageVector = MelodyIcons.MoreVertical,
                    contentDescription = "「${song.title}」的更多操作",
                    onClick = { menuOpen = true },
                    iconSize = 20.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (menuOpen) {
                    MelodyActionSheet(
                        title = song.title,
                        subtitle = "${song.artistOrUnknown} · ${song.albumOrUnknown}",
                        headerArt = { SongArtwork(song = song, size = 46.dp, corner = 12.dp) },
                        actions = songRowActions(
                            onPlayNow = onClick,
                            onPlayNext = onPlayNext,
                            onAddToPlaylist = onAddToPlaylist,
                            onMoveUp = onMoveUp,
                            onMoveDown = onMoveDown,
                            onRemoveFromPlaylist = onRemoveFromPlaylist,
                            onFetchCover = onFetchCover,
                            onReparseEmbedded = onReparseEmbedded,
                            onEmbedTags = onEmbedTags,
                            onManageLyricCopies = onManageLyricCopies,
                            onEditSong = onEditSong,
                            onArchive = onArchive,
                            onUnarchive = onUnarchive,
                            onRemove = onRemove,
                            removeStyle = removeStyle,
                            onHide = onHide
                        ),
                        onDismiss = { menuOpen = false }
                    )
                }
            }
        }   // if (!selectionMode)
    }
}

/**
 * 拼出这一行的动作清单。
 *
 * 抽成独立函数有两个原因：一是让 [SongRow] 的函数体只剩布局，
 * 二是让"哪些操作在什么情况下出现"这件事集中在一处 —— 之前它散在十二个
 * `if (xxx != null)` 里，加一个功能就得回头读一遍才能确定顺序。
 *
 * 顺序按用户自己的思路排，而不是按实现的历史：
 *   播放 → 歌单 → 封面与标签 → 歌曲信息 → 曲库管理
 * 越靠下越"重"（改文件、丢东西），红色只给真正丢东西的那几个。
 */
private fun songRowActions(
    onPlayNow: () -> Unit,
    onPlayNext: (() -> Unit)?,
    onAddToPlaylist: (() -> Unit)?,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onRemoveFromPlaylist: (() -> Unit)?,
    onFetchCover: (() -> Unit)?,
    onReparseEmbedded: (() -> Unit)?,
    onEmbedTags: (() -> Unit)?,
    onManageLyricCopies: (() -> Unit)?,
    onEditSong: (() -> Unit)?,
    onArchive: (() -> Unit)?,
    onUnarchive: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    removeStyle: RemoveStyle,
    onHide: (() -> Unit)?
): List<SheetAction> {
    val play = buildList {
        add(SheetAction(MelodyIcons.Play, "立即播放", onClick = onPlayNow))
        onPlayNext?.let {
            add(
                SheetAction(
                    MelodyIcons.SkipNext,
                    "下一首播放",
                    subtitle = "插到当前这首歌后面",
                    onClick = it
                )
            )
        }
    }.startSection("播放")

    // 「加入歌单」与「排歌单顺序」互斥：已经在歌单里了，再问"要不要加"是句废话
    val playlist = onAddToPlaylist?.let {
        listOf(
            SheetAction(MelodyIcons.PlaylistAdd, "加入歌单", onClick = it)
        ).startSection("歌单")
    } ?: emptyList()

    val playlistOrder = buildList {
        onMoveUp?.let { add(SheetAction(MelodyIcons.ArrowUp, "上移一位", onClick = it)) }
        onMoveDown?.let { add(SheetAction(MelodyIcons.ArrowDown, "下移一位", onClick = it)) }
        onRemoveFromPlaylist?.let {
            add(
                SheetAction(
                    MelodyIcons.Delete,
                    "从歌单移除",
                    subtitle = "只动歌单，曲库里那首还在",
                    onClick = it
                )
            )
        }
    }.startSection("歌单顺序")

    // 这三件事是一条线上的：先挑图，再决定要不要写进文件，
    // 以及文件里那份是不是已经过期了。隔开了就要在两处之间来回找。
    val coverAndTags = buildList {
        onFetchCover?.let {
            add(
                SheetAction(
                    MelodyIcons.AlbumArt,
                    "选择专辑封面",
                    subtitle = "联网匹配一张，或从相册里挑",
                    onClick = it
                )
            )
        }
        onReparseEmbedded?.let {
            add(
                SheetAction(
                    MelodyIcons.Refresh,
                    "重新解析内嵌封面",
                    subtitle = "重读文件里的封面（在别的播放器换过图时用）",
                    onClick = it
                )
            )
        }
        onEmbedTags?.let {
            add(
                SheetAction(
                    MelodyIcons.Save,
                    "写入文件标签…",
                    subtitle = "把歌词和封面写进音频文件，写入后无法撤销",
                    onClick = it
                )
            )
        }
    }.startSection("封面与标签")

    // 单独一组而不是塞进「封面与标签」：用户找它时脑子里想的是"歌词"，
    // 而上面那组标题里没有这两个字 —— 分组标题就是这一层菜单的目录
    val lyrics = onManageLyricCopies?.let {
        listOf(
            SheetAction(
                MelodyIcons.Lyrics,
                "歌词副本…",
                subtitle = "看看 App 里为这首存了哪几份歌词，可预览、可删",
                onClick = it
            )
        ).startSection("歌词")
    } ?: emptyList()

    val info = onEditSong?.let {
        listOf(
            SheetAction(
                MelodyIcons.Edit,
                "编辑歌曲信息",
                subtitle = "只改 App 里的显示，不动文件",
                onClick = it
            )
        ).startSection("歌曲信息")
    } ?: emptyList()

    // 越靠下越"重"：会改文件、会丢东西的都在这组，红色只给真正丢东西的那两个
    val libraryOps = buildList {
        onArchive?.let {
            add(
                SheetAction(
                    MelodyIcons.Archive,
                    "归档到 App 库",
                    subtitle = "复制一份到 App 专属目录，原文件删了也不会丢",
                    onClick = it
                )
            )
        }
        onUnarchive?.let {
            add(
                SheetAction(
                    MelodyIcons.ArchiveOff,
                    "取消归档",
                    subtitle = "删掉 App 库里的副本并释放空间",
                    danger = true,
                    onClick = it
                )
            )
        }
        onHide?.let {
            add(
                SheetAction(
                    MelodyIcons.EyeOff,
                    "隐藏这首",
                    subtitle = "从列表里移开，设置页随时能恢复，文件不受影响",
                    onClick = it
                )
            )
        }
        onRemove?.let {
            add(
                SheetAction(
                    MelodyIcons.Delete,
                    removeStyle.label,
                    subtitle = removeStyle.hint,
                    danger = removeStyle.danger,
                    onClick = it
                )
            )
        }
    }.startSection("曲库管理")

    return play + playlist + playlistOrder + coverAndTags + lyrics + info + libraryOps
}

/** 把组标题打在**这一组的第一项**上；空组原样返回（不然会凭空多出一个组标题）。 */
private fun List<SheetAction>.startSection(title: String): List<SheetAction> =
    mapIndexed { i, action -> if (i == 0) action.copy(section = title) else action }

/**
 * 「已归档」小标签。
 *
 * 用 secondaryContainer 而不是主色：这是**状态**而不是可点的操作，
 * 用主色会和「正在播放」的标题抢注意力。
 */
@Composable
private fun ArchiveBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(
            text = "已归档",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}
