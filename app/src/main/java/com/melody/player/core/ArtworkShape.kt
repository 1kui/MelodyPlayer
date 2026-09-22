package com.melody.player.core

/**
 * 封面形状。
 *
 * 圆角方形是默认值（接近实体唱片封套的比例，也和列表行里的缩略图观感一致），
 * 圆形是给喜欢圆形专辑图的人 —— 播放页看到一张圆封面，观感上和"唱片"更贴。
 */
enum class ArtworkShape(val label: String) {
    ROUNDED("圆角方形"),
    CIRCLE("圆形");

    companion object {
        fun fromKey(key: String?): ArtworkShape = entries.firstOrNull { it.name == key } ?: ROUNDED
    }
}
