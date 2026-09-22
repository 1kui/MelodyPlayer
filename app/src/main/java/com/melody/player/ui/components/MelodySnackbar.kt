package com.melody.player.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 提示条停留时长。
 *
 * Material 的 [androidx.compose.material3.SnackbarDuration.Short] 是 4 秒 —— 对
 * 「已隐藏『某某』」这种一句话的提示太长了：它飘在界面上，多留一秒都是在挡内容。
 * 1.8 秒够读完一行中文，也让连点几下的操作（比如连按 A＋ 调字号）不会堆成一串。
 */
private const val SNACKBAR_DURATION_MS = 1_800L

/**
 * 提示条容器：自己实现，而不是直接用 Material 的 `SnackbarHost`。
 *
 * 用它的原因只有三个，而这三个恰好都是它给不了的：
 *  1. **时长**：`SnackbarHost` 的时长只能从 `SnackbarDuration` 三挡里选（4 秒 / 10 秒 / 常驻），
 *     没有"比 Short 更短"这一挡。想要 1.8 秒就必须自己计时。
 *  2. **透明度**：默认提示条是实心反色块，压在列表和播放控件上很突兀；这里底色留 0.82 的
 *     不透明度，字保持全不透明 —— 背景透出来一点，仍然看得清。
 *  3. **形态**：默认是通栏矩形，这里收成居中的圆角胶囊，更像一条"提示"而不是一块横幅。
 *
 * 计时器挂在 `currentSnackbarData` 上：来了新消息就换人计时，旧的计时协程直接被取消 ——
 * 这就是"新消息顶掉旧消息"的一半（另一半在调用处：新消息会先把旧的 dismiss 掉）。
 *
 * 状态仍然存在 [SnackbarHostState] 里（`showSnackbar` / `dismiss` 的语义不变），
 * 所以业务侧（ViewModel 只管往里发字符串）不需要知道提示条长什么样。
 */
@Composable
fun MelodySnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    val data = hostState.currentSnackbarData

    // 退场动画播放期间 currentSnackbarData 已经是 null 了，内容得靠这份"最后一条"撑着，
    // 否则淡出的会是一个空壳（字先没、壳再缩）
    var shown by remember { mutableStateOf<SnackbarData?>(null) }

    LaunchedEffect(data) {
        val current = data ?: return@LaunchedEffect
        shown = current
        delay(SNACKBAR_DURATION_MS)
        current.dismiss()
    }

    AnimatedVisibility(
        visible = data != null,
        enter = fadeIn(tween(180)) + slideInVertically(tween(240)) { it / 2 },
        exit = fadeOut(tween(140)) + slideOutVertically(tween(180)) { it / 2 },
        modifier = modifier
    ) {
        // 只放一个子项：AnimatedVisibility 把所有子项都摆在 (0,0)，多子项会叠成一团
        SnackbarPill(message = shown?.visuals?.message.orEmpty())
    }
}

/** 提示条本体：反色（深色主题下是浅色）的半透明胶囊，最多两行。 */
@Composable
private fun SnackbarPill(message: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.82f),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .widthIn(max = 460.dp)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}
