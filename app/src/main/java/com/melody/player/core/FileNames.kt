package com.melody.player.core

/**
 * 文件名与体积的纯函数工具。
 *
 * 抽出来的原因很实际：文件名清洗与体积格式化原本散在归档、设置页、解密等好几处，
 * 各写一份就容易在某处漏掉非法字符（比如把带 `:` 的名字写进文件系统直接失败）。
 */
object FileNames {

    /** Windows/Android 上会导致建文件失败或行为诡异的字符，外加全部控制字符。 */
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

    /** 单段文件名的长度上限：给扩展名与「 (2)」这类去重后缀留足余量。 */
    const val MAX_LENGTH = 96

    /**
     * 清洗成可以安全落盘的一段名字（不含路径分隔）。
     *
     * [fallback] 在清洗后为空时使用，调用方负责给它一个带扩展名的合理默认值。
     */
    fun sanitize(raw: String, fallback: String): String {
        val cleaned = ILLEGAL.replace(raw, "_").trim().take(MAX_LENGTH).trim()
        return cleaned.ifBlank { fallback }
    }

    /** 人话的体积文本。负数（未知大小）当成 0。 */
    fun size(bytes: Long): String = when {
        bytes <= 0L -> "0 B"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "%.0f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
