package com.melody.player.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melody.player.core.Song
import com.melody.player.ui.components.MelodyListSheet
import com.melody.player.ui.components.SheetFooterButton
import com.melody.player.ui.components.SongArtwork
import com.melody.player.ui.icons.MelodyIcons

/**
 * 播放历史里翻哪一个榜单。
 *
 * 「最近播放」找的是"我刚才听的那首"，「最常听」找的是"我一直想再听的那首" ——
 * 前者靠搜索是找不回来的（想不起歌名），后者要靠翻列表找得翻很久。
 */
enum class HistoryBoard(val label: String) {
    RECENT("最近播放"),
    MOST("最常听")
}

/**
 * 播放历史弹层：`[最近播放 | 最常听]` + 一列曲目。
 *
 * ## 为什么从曲库首页搬到这里
 * 它原来是曲库首屏的一条横滑卡片区，和歌单卡、维度切换叠在一起，
 * 三样东西都在抢"第一屏"，用户要看的歌被挤到了下面。曲库腾出来给歌单之后，
 * 历史需要一个新家 —— 「播放」那一栏本来就是回答"我在听什么、听过什么"的地方。
 *
 * ## 为什么是弹层而不是队列页里的一段
 * 播放队列那个列表上挂着**长按拖动排序**的手势（整片容器都在监听长按）。
 * 往里面塞一段不可拖的曲目区，用户在那一段上长按就会触发拖动识别器，
 * 而拖动是按"当前可见的队列行"算落点的 —— 手指下面根本没有队列行，
 * 出来的行为是"长按了、什么都没动"，比不给拖动更让人莫名其妙。
 * 弹层与那份手势互不干扰，还顺手把两个榜单收进了一个没占地方的小图标里。
 */
@Composable
internal fun HistorySheet(
    recent: List<Song>,
    most: List<Song>,
    /** 以这一批为队列，从第 index 首开始播。 */
    onPlay: (songs: List<Song>, index: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var board by remember { mutableStateOf(HistoryBoard.RECENT) }
    val songs = if (board == HistoryBoard.RECENT) recent else most

    MelodyListSheet(
        title = "播放历史",
        onDismiss = onDismiss,
        subtitle = "只记在这台设备上，卸载即清空 · 点一行就从那一首开始放",
        footer = {
            SheetFooterButton(
                text = if (songs.isEmpty()) "没有可播放的曲目" else "播放全部（${songs.size} 首）",
                enabled = songs.isNotEmpty(),
                onClick = { onPlay(songs, 0) }
            )
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BoardChip(
                    text = "${HistoryBoard.RECENT.label} ${recent.size}",
                    icon = MelodyIcons.History,
                    selected = board == HistoryBoard.RECENT,
                    onClick = { board = HistoryBoard.RECENT }
                )
                BoardChip(
                    text = "${HistoryBoard.MOST.label} ${most.size}",
                    icon = MelodyIcons.MusicNote,
                    selected = board == HistoryBoard.MOST,
                    onClick = { board = HistoryBoard.MOST }
                )
            }

            if (songs.isEmpty()) {
                // 榜单为空分两种口径说清楚：一个是"还没听过"，
                // 另一个是"记过、但那些歌现在都不在曲库里了"（文件被删或已隐藏）——
                // 合成一句"没有内容"的话，后者会被当成记录丢了
                Text(
                    text = if (board == HistoryBoard.RECENT) {
                        "还没听过歌。播放过的曲目会出现在这里，方便回头再听一遍。"
                    } else {
                        "还没有累计到 30 秒的曲目。一路快切不会把整张曲库刷成「常听」，" +
                            "停够 30 秒才算数。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(
                        items = songs,
                        key = { index, song -> "$index:${song.key}" }
                    ) { index, song ->
                        HistoryRow(song = song, onClick = { onPlay(songs, index) })
                    }
                }
            }
        }
    }
}

/** 榜单切换胶囊。与歌单卡上选中态的语义一致：选中的那颗用主色底。 */
@Composable
private fun BoardChip(
    text: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1
            )
        }
    }
}

/**
 * 弹层里的一行。
 *
 * 用不着曲目行那一套（时长、⋮ 菜单、多选、归档角标）：这里是"我要再听一遍"的出口，
 * 行上多一个可点的东西就多一次点错的机会。右侧那颗播放图标是**提示**而不是按钮 ——
 * 整行都可点，图标只是让"点它会放"这件事不用试。
 */
@Composable
private fun HistoryRow(song: Song, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SongArtwork(song = song, size = 44.dp, corner = 10.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${song.artistOrUnknown} · ${song.albumOrUnknown}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = MelodyIcons.Play,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
