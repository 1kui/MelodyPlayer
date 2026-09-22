package com.melody.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.melody.player.data.Prefs
import com.melody.player.ui.MelodyRoot
import com.melody.player.ui.player.PlayerViewModel
import com.melody.player.ui.theme.MelodyTheme
import com.melody.player.ui.theme.ThemeMode

/**
 * 单 Activity 架构。
 *
 * 主题模式与主题色在 Activity 层面持有并即时落盘，切换时不需要重建 Activity；
 * 播放器状态则完全托管给 [PlayerViewModel]，旋转屏幕不会中断播放。
 */
class MainActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by lazy {
        ViewModelProvider(this, PlayerViewModel.Factory(application))[PlayerViewModel::class.java]
    }

    private val prefs: Prefs by lazy { Prefs(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            var themeMode by remember { mutableStateOf(prefs.themeMode) }
            var accent by remember { mutableStateOf(prefs.accentTheme) }

            MelodyTheme(themeMode = themeMode, accent = accent) {
                val dark = when (themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
                // 状态栏/导航栏图标颜色要跟着主题走，否则浅色背景下白图标会看不见
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }

                MelodyRoot(
                    vm = viewModel,
                    themeMode = themeMode,
                    accent = accent,
                    onThemeModeChange = { mode ->
                        prefs.themeMode = mode
                        themeMode = mode
                    },
                    onAccentChange = { theme ->
                        prefs.accentTheme = theme
                        accent = theme
                    }
                )
            }
        }
    }
}
