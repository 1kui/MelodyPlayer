package com.melody.player.core

/**
 * 「这个文件算不算音乐」的判断。
 *
 * 单独抽出来是因为它有两个来源、两种判据，而它们会打架：
 *  - MediaStore 扫描靠 `IS_MUSIC` 标记，扫描整库时够用；
 *  - 限定文件夹扫描走 SAF 目录树，逐个文档地问 MIME —— 而**不可靠**：
 *    很多提供器对 `.flac` / `.ape` / `.dsf` 返回的是 `application/octet-stream`，
 *    只认 MIME 就会把一堆真歌悄悄漏掉。
 *
 * 所以这里取「MIME 说它是音频」**或**「扩展名在白名单里」，两者取或。
 * 反过来只按扩展名收，会把同名的 .mp4 视频之类挡在外面，而它们本来就该挡。
 */
object AudioFiles {

    /** 音频扩展名白名单（小写，不含点）。刻意不含 `kwm`：那是加密容器，走 KWM 那条流程。 */
    val AUDIO_EXTENSIONS: Set<String> = setOf(
        "mp3", "flac", "wav", "wave", "aac", "m4a", "m4b", "mp4a",
        "ogg", "oga", "opus", "wma", "ape", "alac",
        "aiff", "aif", "aifc", "amr", "awb", "ac3",
        "dsf", "dff", "mka", "mid", "midi"
    )

    /** 从文件名里取扩展名（不含点，小写）。没有扩展名返回空串。 */
    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }

    /** 文件名看着像音频（不看 MIME）。 */
    fun isAudioName(name: String): Boolean {
        if (name.isBlank()) return false
        return extensionOf(name) in AUDIO_EXTENSIONS
    }

    /** MIME 说是音频，或名字像音频 —— 两个判据取或，理由见上面的说明。 */
    fun isAudio(mimeType: String?, name: String): Boolean {
        // MIME 可能带参数（"audio/mpeg; charset=..."），取分号前面那段再判
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (mime == "audio" || mime.startsWith("audio/")) return true
        return isAudioName(name)
    }

    /**
     * 是否应该**跳过**整个子目录。
     *
     * 只认一种：点开头的目录名（`.thumbnails`、`.cache`、`.nomedia` 那一类）。
     * 里面装的是图片、缩略图和临时文件，扫进去只会白费时间；
     * 而「一个叫 Music2 的普通目录」绝不能被当成隐藏目录跳过 —— 漏歌比多扫一层严重得多。
     */
    fun shouldSkipDirectory(name: String): Boolean =
        name.isBlank() || name == "." || name == ".." || name.startsWith(".")
}