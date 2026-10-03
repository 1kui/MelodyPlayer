package com.melody.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melody.player.core.Song
import com.melody.player.core.TimeFormat
import com.melody.player.ui.components.IconAction
import com.melody.player.ui.components.SongArtwork
import com.melody.player.ui.components.SongRow
import com.melody.player.ui.icons.MelodyIcons
import com.melody.player.ui.player.PlayerUiState

/**
 * 专辑 / 歌手详情页里每一行要用到的动作。
 *
 * 收成一个对象而不是给两个详情页各写九个参数：这两页的行菜单完全一样
 * （播放 / 下一首 / 歌单 / 标签 / 封面 / 歌词 / 编辑 / 隐藏），
 * 逐个传参等于把同一份清单抄两遍 —— 而抄错一处就是"专辑页里少一个菜单项"。
 *
 * 它**不含**歌单视图专属的动作（上移/下移/移出歌单）与多选：
 * 详情页的曲目来自聚合结果，不对应用户手排的顺序，也不参与多选
 * （多选只对"歌曲"那一张平铺列表有意义）。
 */
@Immutable
internal class LibrarySongActions(
    val onPlayNext: (Song) -> Unit,
    val onHideSong: (Song) -> Unit,
    val onFetchCover: (Song) -> Unit,
    val onEditSong: (Song) -> Unit,
    val onAddToPlaylist: (Song) -> Unit,
    /** 把这一首的歌词/封面写进音频文件（根界面弹勾选框）。 */
    val onRequestEmbed: (Song) -> Unit,
    /** 重读这一首文件里的内嵌封面。 */
    val onReparseEmbedded: (Song) -> Unit,
    /** 看这一首在 App 里存了哪几份歌词副本。 */
    val onManageLyricCopies: (Song) -> Unit,
    /** 归档这首 / 取消归档（详情页的 ⋮ 里也能做）。 */
    val onArchiveSong: (Song) -> Unit,
    val onUnarchiveSong: (Song) -> Unit
)

/**
 * 专辑详情页。
 *
 * ## 为什么是"内容替换"而不是全屏浮层
 * 它与曲库网格共用顶栏、共用搜索框：用户点开专辑之后往往还要接着搜别的，
 * 再叠一层浮层就得先退出来。内容替换 + 返回键（见 `MelodyRoot` 的页面栈编排）
 * 与"歌单视图"是同一套做法，用户不用学第二种。
 *
 * ## 为什么头部要有「全部播放」和「加入队列」
 * 点开一张专辑最常见的两个意图就是"从头听一遍"和"插到当前队列后面"。
 * 少了后者，用户只能一首首点「下一首播放」—— 十几首的专辑要点十几下。
 */
@Composable
internal fun AlbumDetail(
    album: String,
    artist: String,
    songs: List<Song>,
    state: PlayerUiState,
    actions: LibrarySongActions,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onEnqueueAll: () -> Unit,
    onPlayFrom: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    DetailScaffold(
        title = album,
        subtitle = "$artist · ${songs.size} 首 · ${TimeFormat.durationText(songs.sumOf { it.durationMs })}",
        coverSeedSong = songs.firstOrNull(),
        contentPadding = contentPadding,
        onBack = onBack,
        onPlayAll = onPlayAll,
        onEnqueueAll = onEnqueueAll,
        songs = songs,
        state = state,
        actions = actions,
        onPlayFrom = onPlayFrom,
        modifier = modifier
    )
}

/** 歌手详情页。同上，只是头部摘要换成"几首 · 多久"。 */
@Composable
internal fun ArtistDetail(
    artist: String,
    songs: List<Song>,
    state: PlayerUiState,
    actions: LibrarySongActions,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onEnqueueAll: () -> Unit,
    onPlayFrom: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    DetailScaffold(
        title = artist,
        subtitle = "${songs.size} 首 · ${TimeFormat.durationText(songs.sumOf { it.durationMs })}",
        coverSeedSong = songs.firstOrNull(),
        contentPadding = contentPadding,
        onBack = onBack,
        onPlayAll = onPlayAll,
        onEnqueueAll = onEnqueueAll,
        songs = songs,
        state = state,
        actions = actions,
        onPlayFrom = onPlayFrom,
        modifier = modifier
    )
}

/**
 * 专辑页与歌手页共用的骨架：返回行 → 大封面 → 摘要 → 两个动作 → 曲目列表。
 *
 * 两页长得几乎一样，唯一的差别是标题与摘要 —— 各写一遍就会出现
 * "专辑页的按钮间距和歌手页不一样"这种谁也说不清来由的差异。
 */
@Composable
private fun DetailScaffold(
    title: String,
    subtitle: String,
    coverSeedSong: Song?,
    songs: List<Song>,
    state: PlayerUiState,
    actions: LibrarySongActions,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onEnqueueAll: () -> Unit,
    onPlayFrom: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            bottom = contentPadding.calculateBottomPadding() + 16.dp
        )
    ) {
        item(key = "detail-header") {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 20.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconAction(
                        imageVector = MelodyIcons.ChevronLeft,
                        contentDescription = "返回曲库",
                        onClick = onBack,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "返回",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // 封面按屏宽的 52% 画：再大就把下面的曲目挤没了，
                    // 而用户点进来主要还是要看列表
                    val side = maxWidth * 0.52f
                    SongArtwork(song = coverSeedSong, size = side, corner = 22.dp)
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = onPlayAll,
                        enabled = songs.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) { Text("全部播放") }
                    FilledTonalButton(
                        onClick = onEnqueueAll,
                        enabled = songs.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) { Text("加入队列") }
                }
                Spacer(Modifier.height(2.dp))
            }
        }

        itemsIndexed(items = songs, key = { index, song -> "$index:${song.key}" }) { index, song ->
            DetailSongRow(
                song = song,
                index = index,
                state = state,
                actions = actions,
                onPlay = { onPlayFrom(index) }
            )
        }
    }
}

/**
 * 详情页里的一行曲目。
 *
 * 与曲库列表那一行是同一个 [SongRow]（图、序号、时长、跳动柱全都一致），
 * 只是**不接多选**：详情页的列表来自聚合结果，和"我勾了哪几首"不是一回事。
 */
@Composable
private fun DetailSongRow(
    song: Song,
    index: Int,
    state: PlayerUiState,
    actions: LibrarySongActions,
    onPlay: () -> Unit
) {
    SongRow(
        song = song,
        index = index,
        isCurrent = state.currentSong?.key == song.key,
        isPlaying = state.isPlaying,
        onClick = onPlay,
        onPlayNext = { actions.onPlayNext(song) },
        onHide = { actions.onHideSong(song) },
        onFetchCover = { actions.onFetchCover(song) },
        onEditSong = { actions.onEditSong(song) },
        onAddToPlaylist = { actions.onAddToPlaylist(song) },
        onEmbedTags = { actions.onRequestEmbed(song) },
        onReparseEmbedded = { actions.onReparseEmbedded(song) },
        onManageLyricCopies = { actions.onManageLyricCopies(song) },
        onArchive = if (song.archived) null else ({ actions.onArchiveSong(song) }),
        onUnarchive = if (song.archived) ({ actions.onUnarchiveSong(song) }) else null,
        archived = song.archived,
        onRemove = null,        // 详情页不提供"移出列表"：这一行是聚合出来的，不是导入的副本
        selected = null,
        onToggleSelect = null,
        onBeginSelection = null
    )
}
