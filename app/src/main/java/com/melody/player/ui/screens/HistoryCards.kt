package com.melody.player.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melody.player.core.Song
import com.melody.player.ui.components.SongArtwork
import com.melody.player.ui.icons.MelodyIcons

/**
 * 曲库首页顶部的两个回访榜单。
 *
 * 「最近播放」找的是"我刚才听的那首"，「最常听」找的是"我一直想再听的那首"——
 * 前者靠搜索是找不回来的（想不起歌名），后者要靠翻列表找得翻很久。
 * 所以这两个榜单不该藏在某个二级页里，必须摆在首页。
 */
enum class HistoryBoard(val label: String) {
    RECENT("最近播放"),
    MOST("最常听")
}

/** 横滑卡片区里一共显示几张。再多了就得左右划很久才看得完，那不叫"回访"。 */
private const val CARD_LIMIT = 6

/**
 * 回访卡片区：`[最近播放 | 最常听]  ……  全部播放` + 一排横滑封面卡。
 *
 * ## 为什么两个榜单是**切换**而不是上下两排
 * 两排意味着屏幕最上一段就有 12 张卡 —— 用户打开曲库是为了看列表，
 * 结果第一屏全是"你以前听过什么"。切换之后一次只占一排，
 * 而"换个榜看看"只是一次点击。
 *
 * ## 为什么点卡片就直接放
 * 卡片是"我要再听一遍"的入口，不是"看详情"。所以点它就播，
 * 而不是进某个二级页再点一次播放键。
 */
@Composable
internal fun HistorySection(
    board: HistoryBoard,
    songs: List<Song>,
    recentCount: Int,
    mostCount: Int,
    onBoardChange: (HistoryBoard) -> Unit,
    onPlayAt: (Int) -> Unit,
    onPlayAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (recentCount == 0 && mostCount == 0) return
    val shown = songs.take(CARD_LIMIT)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BoardChip(
                text = "${HistoryBoard.RECENT.label} $recentCount",
                icon = MelodyIcons.History,
                selected = board == HistoryBoard.RECENT,
                onClick = { onBoardChange(HistoryBoard.RECENT) }
            )
            Spacer(Modifier.width(8.dp))
            BoardChip(
                text = "${HistoryBoard.MOST.label} $mostCount",
                icon = MelodyIcons.MusicNote,
                selected = board == HistoryBoard.MOST,
                onClick = { onBoardChange(HistoryBoard.MOST) }
            )
            Spacer(Modifier.weight(1f))
            // 这个榜单里剩下的歌（第 7 首往后）从哪儿放？没有"全部播放"就只能一首首点。
            // 所以先把这**整个榜单**当队列放起来：范围是榜单本身，不是整个曲库。
            TextButton(
                onClick = onPlayAll,
                enabled = shown.isNotEmpty()
            ) { Text("全部播放") }
        }

        if (shown.isEmpty()) {
            // 榜单有记录、但曲库里已经找不到这些歌（文件被删/被隐藏）。
            // 如实说一句，否则这里会是一片莫名其妙的空白。
            Text(
                text = "这个榜单上的曲目现在都不在曲库里了（文件被删或已隐藏）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 2.dp, bottom = 4.dp)
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(items = shown, key = { index, song -> "$index:${song.key}" }) { index, song ->
                    HistoryCard(song = song, onClick = { onPlayAt(index) })
                }
            }
        }
    }
}

/** 榜单切换胶囊。与歌单那一条用同一套配色，切换的语义一致。 */
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
            MaterialTheme.colorScheme.surfaceContainer
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
 * 一张回访卡片：封面 + 歌名 + 歌手。
 *
 * 宽度固定（不是 wrapContent）：横滑列表里宽度不一的卡片会让右边那张
 * 半个身子露在外面，看起来像没对齐；固定宽度滚起来是等距的。
 */
@Composable
private fun HistoryCard(song: Song, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(104.dp)
            .clickable(onClick = onClick)
    ) {
        SongArtwork(song = song, size = 104.dp, corner = 16.dp)
        Spacer(Modifier.height(6.dp))
        Text(
            text = song.title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = song.artistOrUnknown,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
