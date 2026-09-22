package com.melody.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.melody.player.core.ArtworkShape
import com.melody.player.core.Song
import com.melody.player.ui.icons.MelodyIcons

/**
 * 歌曲封面。
 *
 * 有两层：
 *  1. **程序化渐变**（底层）—— 用标题与歌手算一个稳定的色相现场画出来。同一首歌每次
 *     进来颜色完全一致，不同歌曲之间又有区分度。它同时是"还没取到在线封面"时的占位图，
 *     所以列表滚动、切歌的瞬间都不会出现一块空白。
 *  2. **真实专辑封面**（上层）—— 在线取到封面后盖在上面。取图是异步的，
 *     拿到时 [rememberCover] 会触发一次重组，这里自然而然就换过来了。
 *
 * 没有封面时那个音符图标只在底层显示；有真封面时当然不能再盖一个音符上去。
 * 外形（圆角方形 / 圆形）由 [LocalArtworkShape] 决定。
 */
@Composable
fun SongArtwork(
    song: Song?,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = 14.dp,
    showGlyph: Boolean = true
) {
    val artworkShape = LocalArtworkShape.current
    val seed = song?.artworkSeed ?: DEFAULT_SEED
    val hue = (seed % 360).toFloat()
    val warm = Color.hsl(hue, 0.55f, 0.56f)
    val deep = Color.hsl((hue + 44f) % 360f, 0.60f, 0.33f)
    val cover = rememberCover(song)

    Box(
        modifier = modifier
            .size(size)
            .clip(if (artworkShape == ArtworkShape.CIRCLE) CircleShape else RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(warm, deep)))
    ) {
        Canvas(Modifier.matchParentSize()) {
            val minDim = this.size.minDimension
            // 右上角一圈圈同心波，做出「唱片纹路」的质感
            val center = Offset(this.size.width * 0.80f, this.size.height * 0.20f)
            for (i in 1..3) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.10f),
                    radius = minDim * 0.22f * i,
                    center = center,
                    style = Stroke(width = minDim * 0.012f)
                )
            }
            // 左下角的柔光
            drawCircle(
                color = Color.White.copy(alpha = 0.10f),
                radius = minDim * 0.34f,
                center = Offset(this.size.width * 0.12f, this.size.height * 0.92f)
            )
        }

        if (cover != null) {
            Image(
                bitmap = cover,
                contentDescription = null,
                // Apple 返回的本来就是正方形，但本地缓存里也可能混进别的比例；
                // Crop 保证任何情况下都填满且不变形
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        } else if (showGlyph) {
            // 品牌标（五根脉冲竖条），与启动图标同一套几何 —— 没有封面时的占位图案
            // 也是用户见到最多的"默认专辑封面"，必须长着新 logo 的样子
            Icon(
                imageVector = MelodyIcons.PulseBars,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(size * 0.44f),
                tint = Color.White.copy(alpha = 0.94f)
            )
        }
    }
}

private const val DEFAULT_SEED = 213

/**
 * 封面形状偏好（圆角方形 / 圆形）。
 *
 * 走 CompositionLocal 而不是 [SongArtwork] 的构造参数：这是**全局外观设置**，
 * 而 SongArtwork 有四个调用点（列表行 / 迷你条 / 队列头 / 播放页），
 * 逐个传参等于给每个调用点都留一次"忘了传"的机会 —— 真漏了一个，用户会看到
 * 播放页封面是圆的、列表里还是方的，只会觉得是改坏了。
 */
val LocalArtworkShape = compositionLocalOf { ArtworkShape.ROUNDED }
