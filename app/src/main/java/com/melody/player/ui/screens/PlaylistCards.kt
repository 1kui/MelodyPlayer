package com.melody.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melody.player.core.Playlist
import com.melody.player.core.Song
import com.melody.player.ui.components.SongArtwork
import com.melody.player.ui.icons.MelodyIcons

/**
 * 曲库首屏顶部的横向歌单卡。
 *
 * ## 为什么把三层选择器收成这一条
 * 上一版这里是三段上下叠着的"选择器"：歌单胶囊行（`全部 40 / 各歌单 / 新建`）、
 * 维度分段控件（`歌曲 / 专辑 / 歌手`）、榜单胶囊行（`最近播放 / 最常听`）。
 * 三段各用了不同的控件形态、不同的左右边距、不同的高度，凑在一起就是用户说的
 * "排版错位、不好看"；而且第一屏几乎全被它们占满，真正要找的歌反倒要往下翻。
 *
 * 现在只剩这一条卡：**首张固定是「全部歌曲」，它就是原来的「全部」**——
 * 也就是"默认歌单"这个位置；后面是用户自建的歌单，末尾一张「新建歌单」。
 * 维度切换搬进了顶栏的 ⋮（见 [LibraryTopBar]，进去之后有明确的返回按钮），
 * 「最近常听」搬到了播放队列页（见 `HistorySheet`）。
 *
 * ## 为什么用卡片而不是胶囊
 * 歌单有封面（就是里面第一首的封面），胶囊放不下这张图 —— 而"我认得这张封面"
 * 比"我读这个名字"快得多，尤其在歌单名是"1""好听"这种的时候。
 */
@Composable
internal fun PlaylistCardRow(
    playlists: List<Playlist>,
    /** 当前选中的歌单 id；`null` = 正在看「全部歌曲」。 */
    activeId: String?,
    totalCount: Int,
    /** 取一个歌单的封面（里面第一首还在曲库里的歌）。取不到就画占位图。 */
    coverOf: (Playlist) -> Song?,
    onSelect: (String?) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "all-songs") {
            AllSongsCard(
                count = totalCount,
                selected = activeId == null,
                onClick = { onSelect(null) }
            )
        }
        items(items = playlists, key = { it.id }) { playlist ->
            PlaylistCard(
                playlist = playlist,
                cover = coverOf(playlist),
                selected = playlist.id == activeId,
                onClick = { onSelect(playlist.id) }
            )
        }
        item(key = "new-playlist") { NewPlaylistCard(onClick = onCreate) }
    }
}

/** 卡片边长。定死而不是 wrapContent：宽度不一的卡会让右边那张露出半个身子，看着像没对齐。 */
private val CardSide = 88.dp

/** 封面圆角。比行内小图（44dp / 10dp）更方一些 —— 大图用同样的比例会显得太圆。 */
private val CardCorner = 16.dp

/**
 * 「全部歌曲」——即默认歌单，也就是原来的「全部」。
 *
 * 刻意**不用**某首歌的封面：它代表的是整库，拿任意一首的封面来当门面，
 * 用户会以为那是一张真的歌单，点进去发现是全部 40 首。
 * 用底色 + 音符把"这里没有具体封面"说清楚。
 */
@Composable
private fun AllSongsCard(count: Int, selected: Boolean, onClick: () -> Unit) {
    CardFrame(
        selected = selected,
        onClick = onClick,
        title = "全部歌曲",
        subtitle = "$count 首"
    ) {
        Box(
            modifier = Modifier
                .size(CardSide)
                .clip(RoundedCornerShape(CardCorner))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = MelodyIcons.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(34.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun PlaylistCard(
    playlist: Playlist,
    cover: Song?,
    selected: Boolean,
    onClick: () -> Unit
) {
    CardFrame(
        selected = selected,
        onClick = onClick,
        title = playlist.name,
        subtitle = if (playlist.songKeys.isEmpty()) "空歌单" else "${playlist.songKeys.size} 首"
    ) {
        Box(modifier = Modifier.size(CardSide)) {
            if (cover != null) {
                SongArtwork(song = cover, size = CardSide, corner = CardCorner)
            } else {
                // 认不回封面（空歌单，或里面的歌都已被删）：给个中性占位，
                // 但**名字照旧显示** —— 空歌单正是最需要点进去加歌的那一个
                Box(
                    modifier = Modifier
                        .size(CardSide)
                        .clip(RoundedCornerShape(CardCorner))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = MelodyIcons.Playlist,
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // 选中描边画在封面**之上**：`Modifier.border` 排在链上前段时会被后画的图盖住，
            // 所以只能单独叠一层 matchParentSize 的 Box 来描这一圈
            if (selected) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(CardCorner)
                        )
                )
            }
        }
    }
}

@Composable
private fun NewPlaylistCard(onClick: () -> Unit) {
    Column(modifier = Modifier.width(CardSide).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .size(CardSide)
                .clip(RoundedCornerShape(CardCorner))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = MelodyIcons.Plus,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "新建歌单",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // 副标题留成空串，但要占住这一行 —— 三种卡的高度与文字基线必须一致
        // （见 CardFrame 的说明），少一行会让这一张比旁边矮一截。
        // 这里原来写的是「挑几首收进来」：六个字在 88dp 的卡宽下，系统字号一调大
        // 就被截成「挑几首收…」，一排里只有它在冒省略号，看着像是排版坏了。
        // 那半句话也不是必须的 ——「新建歌单」四个字加一个加号已经把这件事说完了。
        Text(
            text = "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 三种卡共用的骨架：封面 + 名字 + 一行小字。
 *
 * 抽出来是因为**三种卡的文字基线必须一致** —— 分别写三份的话，
 * 上下两行文字的起始位置会差一两个 dp，"全部歌曲"那张就会比旁边的歌单卡高一点，
 * 一整排看着就是歪的（上一版正是栽在这种地方）。
 */
@Composable
private fun CardFrame(
    selected: Boolean,
    onClick: () -> Unit,
    title: String,
    subtitle: String,
    cover: @Composable () -> Unit
) {
    Column(modifier = Modifier.width(CardSide).clickable(onClick = onClick)) {
        cover()
        Spacer(Modifier.height(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            // 选中在文字上也要有个落点：卡片在横滑列表里可能只有一半露在屏幕上，
            // 光靠封面那一圈描边，用户未必看得见自己选的是哪一张
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
