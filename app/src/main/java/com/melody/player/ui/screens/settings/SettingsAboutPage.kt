package com.melody.player.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.melody.player.BuildConfig
import com.melody.player.ui.AboutInfo
import com.melody.player.ui.components.SectionCard
import com.melody.player.ui.components.SettingRow
import com.melody.player.ui.icons.MelodyIcons
import com.melody.player.ui.rememberAboutActions

/**
 * 「关于」二级页。
 *
 * 这一页没有设置项，所以不接 `PlayerUiState` —— 它需要的一切都是常量
 * （[BuildConfig]、[AboutInfo]）或者外部动作（跳 QQ 群 / 仓库）。少传一个状态参数，
 * 它就永远不会因为播放状态变化而重组。
 */
@Composable
internal fun SettingsAboutPage(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val aboutActions = rememberAboutActions()

    SettingsPageList(contentPadding = contentPadding, modifier = modifier) {
        item(key = "about") {
            SectionCard(title = "关于") {
                // 品牌标：与启动图标、通知图标同一套几何（aurora 渐变底 + 五根白色竖条）。
                // 这里刻意用渐变而不是主题色：主题色是用户可换的，而这个标要和桌面上的
                // App 图标长得一样，否则"关于"里那个方块和桌面图标对不上，看着像两张皮。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CardPad, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(MaterialTheme.shapes.large)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        Color(0xFF00C2FF),
                                        Color(0xFF2563FF),
                                        Color(0xFF7C3AED)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = MelodyIcons.PulseBars,
                            contentDescription = "旋律",
                            modifier = Modifier.size(34.dp),
                            tint = Color.White
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "旋律",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Melody Player · ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                RowDivider()
                SettingRow(
                    icon = MelodyIcons.Info,
                    title = "版本 ${BuildConfig.VERSION_NAME}",
                    subtitle = "本地音乐播放器 · 构建号 ${BuildConfig.VERSION_CODE}"
                )
                RowDivider()
                SettingRow(
                    icon = MelodyIcons.Group,
                    title = "加入 QQ 群",
                    subtitle = "${AboutInfo.QQ_GROUP} · 交流用法、反馈问题、提需求",
                    onClick = aboutActions.onJoinGroup
                )
                SettingRow(
                    icon = MelodyIcons.Code,
                    title = "开源仓库",
                    subtitle = AboutInfo.REPO_URL.removePrefix("https://"),
                    onClick = aboutActions.onOpenRepo
                )
                Text(
                    text = "界面基于 Jetpack Compose + Material 3。所有图标均由代码绘制的矢量路径" +
                        "生成（ImageVector 路径指令与矢量 drawable），工程内不含任何位图资源。\n" +
                        "在线歌词与封面只发送歌名、歌手用于检索，不会上传任何本地文件；" +
                        "KWM 解密全程在本机完成，不联网、不上传。\n" +
                        "解密出来的音频按原格式（MP3 / FLAC / OGG 等）直接归档，不做二次转码 —— " +
                        "因此 APK 里不含任何音频编码器。\n" +
                        "这是个自用工具，不承诺商业级维护；用得有问题欢迎到 QQ 群或仓库提 issue。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = CardPad, vertical = 12.dp)
                )
            }
        }
    }
}
