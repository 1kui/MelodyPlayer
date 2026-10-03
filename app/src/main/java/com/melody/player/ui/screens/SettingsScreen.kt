package com.melody.player.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.melody.player.core.ArchivedEntry
import com.melody.player.core.ArtworkShape
import com.melody.player.core.LyricTextSize
import com.melody.player.core.kwm.KwmFile
import com.melody.player.core.online.CoverRegion
import com.melody.player.core.online.LyricProvider
import com.melody.player.ui.components.IconAction
import com.melody.player.ui.components.MelodyInfoSheet
import com.melody.player.ui.icons.MelodyIcons
import com.melody.player.ui.player.PlayerUiState
import com.melody.player.ui.screens.settings.HelpTopic
import com.melody.player.ui.screens.settings.SettingsAboutPage
import com.melody.player.ui.screens.settings.SettingsAppearancePage
import com.melody.player.ui.screens.settings.SettingsArchivePage
import com.melody.player.ui.screens.settings.SettingsCoverPage
import com.melody.player.ui.screens.settings.SettingsHiddenPage
import com.melody.player.ui.screens.settings.SettingsKwmPage
import com.melody.player.ui.screens.settings.SettingsLibraryPage
import com.melody.player.ui.screens.settings.SettingsLyricsPage
import com.melody.player.ui.screens.settings.SettingsPlaybackPage
import com.melody.player.ui.theme.AccentTheme
import com.melody.player.ui.theme.ThemeMode
import kotlinx.coroutines.flow.StateFlow

/**
 * 设置页里的一个**页面**（不是"分区"）。
 *
 * 拆这个枚举是为了让"设置页现在停在哪一层"变成一个纯数据：项目没有导航库，
 * 页面栈只能自己拿状态管 —— 而一个状态要放在 [com.melody.player.ui.MelodyRoot] 里，
 * 就必须是个能存进 `remember` 的简单值。枚举天然满足，还顺手给了"从别处跳到某个
 * 二级页"的口子（例如歌词取不到时提示"去『歌词』看看"，不必让用户自己找）。
 *
 * [ROOT] 是首屏（常用 + 分组入口），其余九个各是一张卡片的内容。
 */
enum class SettingsPage(val title: String, val isRoot: Boolean = false) {
    ROOT("设置", isRoot = true),
    LIBRARY("曲库"),
    ARCHIVE("App 音乐库"),
    HIDDEN("已隐藏的曲目"),
    PLAYBACK("播放"),
    APPEARANCE("外观"),
    LYRICS("歌词"),
    COVER("专辑封面"),
    KWM("KWM音乐解密"),
    ABOUT("关于")
}

/**
 * 设置页顶栏：首屏居中写「设置」，二级页左侧是返回箭头、中间是页名。
 *
 * 页名写在顶栏而不是只写在正文里，是因为二级页**没有别的定位物** ——
 * 用户从首屏点进来之后，屏幕上只有内容，看不出自己站在哪一层。
 */
@Composable
fun SettingsTopBar(
    page: SettingsPage,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    CenterAlignedTopAppBar(
        modifier = modifier,
        title = {
            Text(
                text = page.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        navigationIcon = {
            if (!page.isRoot) {
                IconAction(
                    imageVector = MelodyIcons.ChevronLeft,
                    contentDescription = "返回设置",
                    onClick = onBack,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

/**
 * 设置页要用到的全部动作。
 *
 * 收成一个对象而不是给九个页面各写一份参数表：这批回调跨页重复度极高
 * （曲库页要用 refresh/importAudio，归档页也要用 refresh），逐个传的话
 * 每加一个功能就要改七八个签名。这里一个对象走到底，
 * 页面自己只挑它需要的用；代价是页面能看到与自己无关的回调 ——
 * 但这比"签名里躺着四十个参数"好读得多。
 *
 * **只装动作与全局外观偏好**（主题模式、主题色）：其余一切从 [PlayerUiState] 读，
 * 免得同一个数字有两个来源。
 */
@Immutable
class SettingsActions(
    // ---------------------------------------------------------------- 页面导航
    val navigate: (SettingsPage) -> Unit,
    val back: () -> Unit,

    // ------------------------------------------------------------ 主题（全局）
    val themeMode: ThemeMode,
    val accent: AccentTheme,
    val setThemeMode: (ThemeMode) -> Unit,
    val setAccent: (AccentTheme) -> Unit,

    // ---------------------------------------------------------------- 曲库
    val refresh: () -> Unit,
    val importAudio: () -> Unit,
    val setLibraryFolderOnly: (Boolean) -> Unit,
    val pickLibraryFolder: () -> Unit,
    val clearLibraryFolder: () -> Unit,

    // ------------------------------------------------------------ App 音乐库
    val archiveAll: () -> Unit,
    val cancelArchive: () -> Unit,
    val clearArchive: () -> Unit,
    val unarchive: (ArchivedEntry) -> Unit,

    // -------------------------------------------------------------- 已隐藏
    val unhide: (String) -> Unit,
    val restoreAllHidden: () -> Unit,

    // ---------------------------------------------------- 歌词（含副本全局入口）
    val setAutoFetchLyrics: (Boolean) -> Unit,
    val setLyricProvider: (LyricProvider, Boolean) -> Unit,
    val openLyricCopiesAll: () -> Unit,

    // -------------------------------------------------------------- 播放
    val setSwipeSwitchSong: (Boolean) -> Unit,
    val cyclePlayMode: () -> Unit,
    val openSleepTimer: () -> Unit,

    // -------------------------------------------------------------- 外观
    val setArtworkShape: (ArtworkShape) -> Unit,
    val setLyricTextSize: (LyricTextSize) -> Unit,

    // -------------------------------------------------------------- 封面
    val setAutoFetchCovers: (Boolean) -> Unit,
    val backfillCovers: () -> Unit,
    val cancelBackfillCovers: () -> Unit,
    val clearCoverCache: () -> Unit,
    val reparseEmbedded: () -> Unit,
    val setCoverMinScore: (Int) -> Unit,
    val setCoverRegionCustom: (Boolean) -> Unit,
    val toggleCoverRegion: (CoverRegion) -> Unit,
    val probeCoverRegions: () -> Unit,

    // ---------------------------------------------------------------- KWM
    val kwmScanDevice: () -> Unit,
    val kwmPickFolder: () -> Unit,
    val kwmRescanFolder: () -> Unit,
    val kwmPickFiles: () -> Unit,
    val kwmRemove: (String) -> Unit,
    val kwmClearList: () -> Unit,
    val kwmDecrypt: (KwmFile) -> Unit,
    val kwmDecryptAgain: (KwmFile) -> Unit,
    val kwmDecryptAll: () -> Unit,
    val kwmCancel: () -> Unit
)

/**
 * 设置页的内容容器。
 *
 * 它是**唯一**知道"现在该画哪一页"的地方：首屏画 [SettingsRootContent]，
 * 其余按 [SettingsPage] 分发到各自的二级页。每个二级页只管自己那张卡片的正文，
 * 顶栏由 [SettingsTopBar] 在根界面给（二级页里再放一个顶栏就变成两层了）。
 *
 * 说明弹层（[HelpTopic] → `MelodyInfoSheet`）挂在这一层而不是各页里：
 * 它是模态的、盖住整页，而页面里的行随时可能被 LazyColumn 回收 ——
 * 状态挂在行上，行一没弹层就跟着消失。九个页面共用一个状态，同一时刻只有一条。
 */
@Composable
fun SettingsContent(
    state: PlayerUiState,
    page: SettingsPage,
    actions: SettingsActions,
    /**
     * 睡眠定时剩余时间。只有「常用」卡与「播放」页用它，但都挂在这一层往下传 ——
     * 它是一条每秒推送的流，谁把它当普通值收下来，谁那一整棵树就会每秒重组一遍。
     */
    sleepRemaining: StateFlow<Long?>,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    var helpTopic by remember { mutableStateOf<HelpTopic?>(null) }
    val openHelp: (HelpTopic) -> Unit = { helpTopic = it }

    when (page) {
        SettingsPage.ROOT -> SettingsRootContent(
            state = state,
            actions = actions,
            sleepRemaining = sleepRemaining,
            contentPadding = contentPadding,
            modifier = modifier
        )

        SettingsPage.LIBRARY -> SettingsLibraryPage(state, actions, contentPadding, openHelp, modifier)
        SettingsPage.ARCHIVE -> SettingsArchivePage(state, actions, contentPadding, openHelp, modifier)
        SettingsPage.HIDDEN -> SettingsHiddenPage(state, actions, contentPadding, modifier)
        SettingsPage.PLAYBACK -> SettingsPlaybackPage(
            state = state,
            actions = actions,
            sleepRemaining = sleepRemaining,
            contentPadding = contentPadding,
            modifier = modifier
        )
        SettingsPage.APPEARANCE -> SettingsAppearancePage(state, actions, contentPadding, modifier)
        SettingsPage.LYRICS -> SettingsLyricsPage(state, actions, contentPadding, openHelp, modifier)
        SettingsPage.COVER -> SettingsCoverPage(state, actions, contentPadding, openHelp, modifier)
        SettingsPage.KWM -> SettingsKwmPage(state, actions, contentPadding, openHelp, modifier)
        SettingsPage.ABOUT -> SettingsAboutPage(contentPadding, modifier)
    }

    helpTopic?.let { topic ->
        MelodyInfoSheet(
            title = topic.label,
            text = topic.text,
            onDismiss = { helpTopic = null }
        )
    }
}
