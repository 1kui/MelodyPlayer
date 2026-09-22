package com.melody.player.core

/**
 * 播放模式。四种状态互斥，由一个按钮循环切换。
 *
 * 之前是「随机」和「循环」两个独立开关，可以组合出「随机 + 单曲循环」这种
 * 自相矛盾的状态（单曲循环下随机根本不起作用），用户完全无法预测下一个会放什么。
 * 这里改成单一枚举：任一时刻只有一种模式，语义唯一，按钮的图标就等于当前状态。
 *
 * 顺序即切换顺序：顺序播放 → 列表循环 → 单曲循环 → 随机播放 → 回到顺序。
 * 与主流音乐 App 一致，从「顺序」起步也最符合「点开一首歌先顺序听完」的直觉。
 */
enum class PlayMode(val label: String) {
    SEQUENTIAL("顺序播放"),
    LIST_LOOP("列表循环"),
    SINGLE_LOOP("单曲循环"),
    SHUFFLE("随机播放");

    val next: PlayMode get() = entries[(ordinal + 1) % entries.size]

    /** 随机 / 单曲循环都属于「被用户主动改变过」，界面上用主题色标出来。 */
    val isDefault: Boolean get() = this == SEQUENTIAL

    companion object {
        /**
         * 从偏好里还原，并兼容旧版本。
         *
         * 旧版存的是 `shuffle`(Boolean) + `repeat`(Int，0/1/2 = 关/列表/单曲)，
         * 两个字段能组合出三种不一致的写法，这里按「随机优先」归一：
         * 只要 shuffle 开着就算随机播放，否则按 repeat 落到顺序/列表/单曲。
         */
        fun fromLegacy(shuffle: Boolean, repeatOrdinal: Int): PlayMode = when {
            shuffle -> SHUFFLE
            repeatOrdinal == 1 -> LIST_LOOP
            repeatOrdinal == 2 -> SINGLE_LOOP
            else -> SEQUENTIAL
        }

        fun fromOrdinal(value: Int): PlayMode =
            entries.getOrElse(value) { SEQUENTIAL }
    }
}
