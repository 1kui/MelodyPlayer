package com.melody.player.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 可选主题色。每个主题只换「主色家族」（primary / secondary / tertiary 三组），
 * 中性色板（surface / outline / error）保持不变，这样切换主题时整体观感稳定，
 * 也不会出现某些主题下对比度不达标的问题。
 */
enum class AccentTheme(val label: String) {
    INDIGO("靛蓝"),
    TEAL("青绿"),
    ROSE("玫红"),
    AMBER("琥珀")
}

/** 主题模式。 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色");

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.name == key } ?: SYSTEM
    }
}

/** 一个主题色家族在浅色/深色下需要的 9 个色值。 */
private data class AccentPalette(
    val lightPrimary: Color,
    val lightOnPrimary: Color,
    val lightPrimaryContainer: Color,
    val lightOnPrimaryContainer: Color,
    val darkPrimary: Color,
    val darkOnPrimary: Color,
    val darkPrimaryContainer: Color,
    val darkOnPrimaryContainer: Color,
    val secondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val darkSecondary: Color,
    val darkSecondaryContainer: Color,
    val darkOnSecondaryContainer: Color
)

private val Indigo = AccentPalette(
    lightPrimary = Color(0xFF4C56C4),
    lightOnPrimary = Color(0xFFFFFFFF),
    lightPrimaryContainer = Color(0xFFE0E0FF),
    lightOnPrimaryContainer = Color(0xFF000C61),
    darkPrimary = Color(0xFFBDC2FF),
    darkOnPrimary = Color(0xFF1B2477),
    darkPrimaryContainer = Color(0xFF333D9D),
    darkOnPrimaryContainer = Color(0xFFE0E0FF),
    secondary = Color(0xFF5B5D72),
    secondaryContainer = Color(0xFFE0E1F9),
    onSecondaryContainer = Color(0xFF181A2C),
    darkSecondary = Color(0xFFC4C5DD),
    darkSecondaryContainer = Color(0xFF434559),
    darkOnSecondaryContainer = Color(0xFFE0E1F9)
)

private val Teal = AccentPalette(
    lightPrimary = Color(0xFF00696E),
    lightOnPrimary = Color(0xFFFFFFFF),
    lightPrimaryContainer = Color(0xFF9CF1F6),
    lightOnPrimaryContainer = Color(0xFF002022),
    darkPrimary = Color(0xFF4CD9E3),
    darkOnPrimary = Color(0xFF00373A),
    darkPrimaryContainer = Color(0xFF004F53),
    darkOnPrimaryContainer = Color(0xFF9CF1F6),
    secondary = Color(0xFF4A6365),
    secondaryContainer = Color(0xFFCCE8E9),
    onSecondaryContainer = Color(0xFF051F21),
    darkSecondary = Color(0xFFB0CCCE),
    darkSecondaryContainer = Color(0xFF324B4D),
    darkOnSecondaryContainer = Color(0xFFCCE8E9)
)

private val Rose = AccentPalette(
    lightPrimary = Color(0xFFB3204D),
    lightOnPrimary = Color(0xFFFFFFFF),
    lightPrimaryContainer = Color(0xFFFFD9E1),
    lightOnPrimaryContainer = Color(0xFF3F001C),
    darkPrimary = Color(0xFFFFB1C6),
    darkOnPrimary = Color(0xFF66002C),
    darkPrimaryContainer = Color(0xFF8E003F),
    darkOnPrimaryContainer = Color(0xFFFFD9E1),
    secondary = Color(0xFF74565E),
    secondaryContainer = Color(0xFFFFD9E1),
    onSecondaryContainer = Color(0xFF2B151B),
    darkSecondary = Color(0xFFE3BDC6),
    darkSecondaryContainer = Color(0xFF5A3F46),
    darkOnSecondaryContainer = Color(0xFFFFD9E1)
)

private val Amber = AccentPalette(
    lightPrimary = Color(0xFF7A5900),
    lightOnPrimary = Color(0xFFFFFFFF),
    lightPrimaryContainer = Color(0xFFFFDF9C),
    lightOnPrimaryContainer = Color(0xFF261A00),
    darkPrimary = Color(0xFFF3BF48),
    darkOnPrimary = Color(0xFF412D00),
    darkPrimaryContainer = Color(0xFF5D4200),
    darkOnPrimaryContainer = Color(0xFFFFDF9C),
    secondary = Color(0xFF6B5D3F),
    secondaryContainer = Color(0xFFF4E0BB),
    onSecondaryContainer = Color(0xFF241A04),
    darkSecondary = Color(0xFFD7C4A1),
    darkSecondaryContainer = Color(0xFF524629),
    darkOnSecondaryContainer = Color(0xFFF4E0BB)
)

private fun paletteOf(theme: AccentTheme): AccentPalette = when (theme) {
    AccentTheme.INDIGO -> Indigo
    AccentTheme.TEAL -> Teal
    AccentTheme.ROSE -> Rose
    AccentTheme.AMBER -> Amber
}

fun lightSchemeOf(theme: AccentTheme): androidx.compose.material3.ColorScheme {
    val p = paletteOf(theme)
    return lightColorScheme(
        primary = p.lightPrimary,
        onPrimary = p.lightOnPrimary,
        primaryContainer = p.lightPrimaryContainer,
        onPrimaryContainer = p.lightOnPrimaryContainer,
        inversePrimary = p.darkPrimary,
        secondary = p.secondary,
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = p.secondaryContainer,
        onSecondaryContainer = p.onSecondaryContainer,
        tertiary = Color(0xFF77536D),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFD7F0),
        onTertiaryContainer = Color(0xFF2E1228),
        background = Color(0xFFFBF8FF),
        onBackground = Color(0xFF1A1B21),
        surface = Color(0xFFFBF8FF),
        onSurface = Color(0xFF1A1B21),
        surfaceVariant = Color(0xFFE3E1EC),
        onSurfaceVariant = Color(0xFF46464F),
        surfaceTint = p.lightPrimary,
        inverseSurface = Color(0xFF2F3036),
        inverseOnSurface = Color(0xFFF1EFF7),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        outline = Color(0xFF777680),
        outlineVariant = Color(0xFFC7C5D0),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFFFBF8FF),
        surfaceDim = Color(0xFFDBD9E0),
        surfaceContainer = Color(0xFFEFEDF4),
        surfaceContainerHigh = Color(0xFFE9E7EF),
        surfaceContainerHighest = Color(0xFFE4E1E9),
        surfaceContainerLow = Color(0xFFF5F2FA),
        surfaceContainerLowest = Color(0xFFFFFFFF)
    )
}

fun darkSchemeOf(theme: AccentTheme): androidx.compose.material3.ColorScheme {
    val p = paletteOf(theme)
    return darkColorScheme(
        primary = p.darkPrimary,
        onPrimary = p.darkOnPrimary,
        primaryContainer = p.darkPrimaryContainer,
        onPrimaryContainer = p.darkOnPrimaryContainer,
        inversePrimary = p.lightPrimary,
        secondary = p.darkSecondary,
        onSecondary = Color(0xFF2D2F42),
        secondaryContainer = p.darkSecondaryContainer,
        onSecondaryContainer = p.darkOnSecondaryContainer,
        tertiary = Color(0xFFE7B8DD),
        onTertiary = Color(0xFF45263F),
        tertiaryContainer = Color(0xFF5E3C55),
        onTertiaryContainer = Color(0xFFFFD7F0),
        background = Color(0xFF131318),
        onBackground = Color(0xFFE4E1E9),
        surface = Color(0xFF131318),
        onSurface = Color(0xFFE4E1E9),
        surfaceVariant = Color(0xFF46464F),
        onSurfaceVariant = Color(0xFFC7C5D0),
        surfaceTint = p.darkPrimary,
        inverseSurface = Color(0xFFE4E1E9),
        inverseOnSurface = Color(0xFF303036),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF918F9A),
        outlineVariant = Color(0xFF46464F),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF39383F),
        surfaceDim = Color(0xFF131318),
        surfaceContainer = Color(0xFF1F1F25),
        surfaceContainerHigh = Color(0xFF292930),
        surfaceContainerHighest = Color(0xFF34343B),
        surfaceContainerLow = Color(0xFF1B1B21),
        surfaceContainerLowest = Color(0xFF0E0E13)
    )
}
