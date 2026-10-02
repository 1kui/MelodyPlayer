package com.melody.player.core

/**
 * 歌词字号档位。
 *
 * 存枚举名而不是一个浮点缩放系数：档位是**离散**的，界面上就只有四挡；存系数的话，
 * 哪天调了基准字号，老用户的设置会变成"比别人小 3%"这种谁也说不清的状态。
 *
 * [scale] 乘在基准字号（16sp）上，而不是直接给绝对字号 —— 系统字体设置被放大时，
 * 歌词跟着一起放大，不会被这里的档位钉死。
 */
enum class LyricTextSize(val label: String, val scale: Float) {
    SMALL("小", 0.86f),
    STANDARD("标准", 1.0f),
    LARGE("大", 1.18f),
    XLARGE("特大", 1.38f);

    companion object {
        fun fromKey(key: String?): LyricTextSize = entries.firstOrNull { it.name == key } ?: STANDARD
    }
}
