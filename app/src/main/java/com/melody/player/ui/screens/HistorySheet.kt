package com.melody.player.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melody.player.core.Song
import com.melody.player.ui.components.MelodyListSheet
import com.melody.player.ui.components.SheetFooterButton
import com.melody.player.ui.components.SongArtwork
import com.melody.player.ui.icons.MelodyIcons

/**
 * 「最近常听」弹层：排好序的一列曲目，点一行就从那一首开始放。
 *
 * ## 为什么只剩「最常听」这一份
 * 这里原来是 `[最近播放 | 最常听]` 两个榜单用一个胶囊切换。撤掉「最近播放」是因为
 * **两个榜单回答的其实是同一个问题**："我想不起歌名，但我知道刚才 / 一直听的是哪首"
 * —— 而两个榜单里常常是同一批歌，用户每次点开都要先做一次"该翻哪一个"的选择，
 * 换来的却是两份高度重叠的列表。留「最常听」是因为它是**搜索与翻列表都替代不了**的那个：
 * 最近听过的还在通知栏和迷你条上找得到，而"我反复听的那几首"只有这份榜单记得住。
 *
 * ## 为什么从曲库首页搬到这里
 * 它原来是曲库首屏的一条横滑卡片区，和歌单卡、维度切换叠在一起，三样东西都在抢第一屏。
 * 「播放」那一栏本来就是回答"我在听什么、听过什么"的地方，而它是"想回头再听一遍"的入口 ——
 * 与队列放在同一屏最顺手。
 *
 * ## 为什么是弹层而不是队列页里的一段
 * 播放队列那个列表上挂着**长按拖动排序**的手势（整片容器都在监听长按）。
 * 往里面塞一段不可拖的曲目区，用户在那一段上长按就会触发拖动识别器，
 * 而拖动是按"当前可见的队列行"算落点的 —— 手指下面根本没有队列行，
 * 出来的行为是"长按了、什么都没动"，比不给拖动更让人莫名其妙。
 */
@Composable
internal fun HistorySheet(
    /** 最常听的曲目（已经按播放次数排好序）。 */
    most: List<Song>,
    /** 以这一批为队列，从第 index 首开始播。 */
    onPlay: (songs: List<Song>, index: Int) -> Unit,
    onDismiss: () -> Unit
) {
    MelodyListSheet(
        title = "最近常听",
        onDismiss = onDismiss,
        subtitle = "听满 30 秒才算一笔 · 只记在这台设备上，卸载即清空",
        footer = {
            SheetFooterButton(
                text = if (most.isEmpty()) "没有可播放的曲目" else "播放全部（${most.size} 首）",
                enabled = most.isNotEmpty(),
                onClick = { onPlay(most, 0) }
            )
        }
    ) {
        if (most.isEmpty()) {
            // 说清"为什么这里还是空的"：一路快切（每首不到 30 秒）不会把整张曲库刷成"常听"，
            // 这不是坏了，是故意的 —— 否则这份榜单第二天就没法看了
            Text(
                text = "还没有听满 30 秒的曲目。一路快切不会把整张曲库刷成「常听」——" +
                    "一首歌停够 30 秒才会记进来，所以这里排在前面的都是你真正反复听的。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                itemsIndexed(
                    items = most,
                    key = { index, song -> "$index:${song.key}" }
                ) { index, song ->
                    HistoryRow(song = song, onClick = { onPlay(most, index) })
                }
            }
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
