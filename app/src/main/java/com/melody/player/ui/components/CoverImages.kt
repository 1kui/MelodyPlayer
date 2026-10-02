package com.melody.player.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.melody.player.core.Song
import com.melody.player.data.AlbumArt
import com.melody.player.data.EmbeddedArtworkCache

/**
 * 「封面又变了一张」的信号。
 *
 * 取封面是异步的（网络 + 磁盘），拿到之后**必须有人告诉界面**，否则那一行会一直
 * 停在渐变占位图上，要等下一次滚动才刷新出来；播放页更是永远不刷新 —— 那正是用户
 * 盯着看的地方。所以取到封面后调一次 [notifyCoverChanged]，所有读过它的组件一起重组。
 *
 * 用一个模块级 Compose 状态而不是把它塞进 [com.melody.player.ui.player.PlayerUiState]：
 * 后者一变整棵界面树都要重新收集，而这个信号只关封面一件事。
 */
private var coverRevision by mutableIntStateOf(0)

/** 有新封面落盘时调用（只在主线程调）。 */
fun notifyCoverChanged() {
    coverRevision++
}

/**
 * 取一首歌的封面位图；没有就返回 null，由调用方退回渐变占位图。
 *
 * 分两步是有意的：[AlbumArtRepository.peek] 只查内存，能命中的（刚看过、或刚取到的）
 * 当帧就画出来；没命中的交给 [LaunchedEffect] 去 IO 线程读盘解码，
 * 期间先显示占位图。**绝不能在 Compose 里直接读盘**：LazyColumn 一滚动就是几十次调用，
 * 主线程 IO 会让列表直接卡住。
 *
 * ## 三级来源，顺序不能换
 *  1. **App 缓存**（联网匹配 / 相册自定义）：用户主动设过的，优先级最高 ——
 *     他刚选的那张凭什么被文件里的旧图盖掉。
 *  2. **文件内嵌封面**（ID3 的 APIC、FLAC 的 PICTURE）：App 没有缓存时用这张。
 *     这是「去掉封面」确认框里承诺的"退回内嵌封面"的实际实现 ——
 *     在这个功能补上之前，那句话是假的（界面上只会退回渐变占位图）。
 *  3. 都没有 → 渐变占位图。
 *
 * 内嵌封面读一次就记进 [EmbeddedArtworkCache]：文件在那儿不会变，
 * 每行每次重组都去解一次字节纯属浪费（一张 512×512 的 APIC 有几百 KB）。
 */
@Composable
fun rememberCover(song: Song?): ImageBitmap? {
    val key = song?.key ?: return null
    val context = LocalContext.current
    val repository = remember { AlbumArt.of(context) }
    val embedded = remember { EmbeddedArtworkCache.of(context) }
    val revision = coverRevision

    var image by remember(key) { mutableStateOf(repository.peek(key)?.asImageBitmap()) }
    // 每次跑 Effect 都要真的重新 load 一遍：换封面后旧位图还留在这个 state 里
    // （不为 null），带 `if (image == null)` 守卫的版本会一直画旧图 —— 那正是
    // 「重新选了封面却还是旧封面」的原因。不变时 load 命中内存缓存，代价可忽略。
    LaunchedEffect(key, revision) {
        val fromApp = repository.load(key)?.asImageBitmap()
        if (fromApp != null) {
            image = fromApp
            return@LaunchedEffect
        }
        // App 这层没有 → 退回文件内嵌的封面
        val bytes = embedded.get(song)
        image = bytes?.let { bytes ->
            runCatching {
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    return image
}