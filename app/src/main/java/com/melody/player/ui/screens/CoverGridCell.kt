package com.melody.player.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melody.player.core.AlbumGroup
import com.melody.player.core.ArtistGroup
import com.melody.player.core.TimeFormat
import com.melody.player.ui.components.SongArtwork
import com.melody.player.ui.components.highlightRanges
import com.melody.player.ui.icons.MelodyIcons

/** 网格两侧的留白。与曲库列表行用的 16~20dp 同一套栅格。 */
private val GridPad = 20.dp

/**
 * 专辑封面网格（两列）。
 *
 * ## 为什么是两列
 * 封面要大到"一眼认出来"。三列时封面只剩 100dp 上下，专辑封面本身没有歌名，
 * 太小就纯粹是一堆色块 —— 那还不如直接看列表。两列在 360dp 屏上是 ~160dp 的封面，
 * 认得出、也放得下专辑名与曲目数。
 *
 * ## 为什么不用 `LazyVerticalGrid` 套在曲库那根 LazyColumn 里
 * 两个纵向滚动容器不能嵌套（外层会先吃掉手势，内层就永远滚不动）。
 * 所以专辑视图是**整根**换掉列表的那一个 —— 见 `LibraryContent` 里的分支。
 */
@Composable
internal fun AlbumGrid(
    albums: List<AlbumGroup>,
    state: LazyGridState,
    highlight: String,
    contentPadding: PaddingValues,
    onOpen: (AlbumGroup) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = state,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = GridPad,
            end = GridPad,
            top = 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 20.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(items = albums, key = { it.key }) { album ->
            AlbumCell(album = album, highlight = highlight, onClick = { onOpen(album) })
        }
    }
}

/** 一张专辑：封面 + 专辑名 + 歌手 + `N 首 · 时长`。 */
@Composable
private fun AlbumCell(
    album: AlbumGroup,
    highlight: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        // 封面撑满整个格子宽度：写死一个 dp 会在小平板上留一条空边，
        // 而"按格子宽度算"这件事只有拿得到约束时才知道
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            SongArtwork(song = album.songs.first(), size = maxWidth, corner = 18.dp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = highlightRanges(album.album, highlight, MaterialTheme.colorScheme.primary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = highlightRanges(album.artist, highlight, MaterialTheme.colorScheme.primary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${album.count} 首 · ${TimeFormat.durationText(album.durationMs)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/**
 * 歌手列表（一行一位）。
 *
 * 用列表而不是圆网格：歌手名长短差得很远（"周杰伦" vs "The Weeknd"），
 * 网格会把名字截得七零八落；一行一位则能给出完整的名字与"几首 / 多久"的摘要。
 */
@Composable
internal fun ArtistList(
    artists: List<ArtistGroup>,
    contentPadding: PaddingValues,
    highlight: String,
    onOpen: (ArtistGroup) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            top = 4.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp
        )
    ) {
        items(items = artists, key = { it.key }) { artist ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(artist) }
                    .padding(horizontal = GridPad, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 歌手头像是"某首歌的封面"，不是真正的艺人图 —— 但比一个灰底人像好认
                SongArtwork(song = artist.songs.first(), size = 48.dp, corner = 24.dp)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = highlightRanges(
                            artist.artist,
                            highlight,
                            MaterialTheme.colorScheme.primary
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${artist.count} 首 · ${TimeFormat.durationText(artist.durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                ChevronRightIcon()
            }
        }
    }
}

/** 列表右侧"能点进去"的小箭头。 */
@Composable
private fun ChevronRightIcon() {
    Icon(
        imageVector = MelodyIcons.ChevronDown,
        contentDescription = null,
        // ChevronDown 转过 -90° 就是向右的箭头（同一个几何只维护一份）
        modifier = Modifier
            .size(18.dp)
            .rotate(-90f),
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
