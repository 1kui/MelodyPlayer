package com.melody.player.core.kwm

import com.melody.player.core.FileNames

/** 这个 `.kwm` 是从哪儿发现的 —— 界面上要如实标注来源。 */
enum class KwmSource(val label: String) {
    DEVICE("设备扫描"),
    FOLDER("指定文件夹"),
    PICKED("手动选择")
}

/**
 * 磁盘上一个待解密的 `.kwm` 文件。
 *
 * 纯 Kotlin（[uri] 是字符串），因为发现与去重这些逻辑同样值得单测 ——
 * 重复项会让用户以为自己解了两遍、白占空间。
 */
data class KwmFile(
    /** 显示用的文件名，含 `.kwm` 后缀。 */
    val name: String,
    /** `content://` 或 `file://`。SAF 与 MediaStore 给的地址在重启后都仍然有效。 */
    val uri: String,
    val sizeBytes: Long,
    val modifiedSec: Long,
    val source: KwmSource,
    /** 有真实路径时走文件直读，比 ContentResolver 快得多；可为 null。 */
    val filePath: String? = null
) {
    /** 稳定标识：URI 不会随重命名/重扫描而变；URI 为空时退到 名字+大小。 */
    val key: String get() = uri.ifBlank { "$name|$sizeBytes" }

    /** 派生出的标题（去掉 `.kwm` 后缀），解密后若文件自带标签则会被标签覆盖。 */
    val titleHint: String get() = KwmFiles.outputBaseName(name)
}

/**
 * `.kwm` 的识别、命名与列表合并。
 *
 * 全是纯函数：这里的每个判断都对应一个具体的用户可见后果 ——
 * 认错了文件名、把非 KWM 文件混进来、或者同一份文件在列表里出现两次。
 */
object KwmFiles {

    const val SUFFIX = ".kwm"

    /** 只有以 `.kwm` 结尾的才算。大小写不敏感（拷来拷去很容易变成 `.KWM`）。 */
    fun isKwmName(name: String): Boolean = name.endsWith(SUFFIX, ignoreCase = true)

    /**
     * 由来源文件名派生输出用的基名（不含扩展名）。
     *
     * 例：`我不难过.kwm` → `我不难过`，之后接上解密嗅探出的真实扩展名。
     * 名字里的非法字符（`: * ?` 等）会被清掉，否则建文件会直接失败。
     */
    fun outputBaseName(sourceName: String): String {
        val stem = sourceName.substringBeforeLast('.', sourceName).ifBlank { sourceName }
        return FileNames.sanitize(stem, "解密音频")
    }

    /**
     * 合并两批发现结果。
     *
     * 按 [KwmFile.key] 去重，**保留原有的位置与顺序**，新发现的接在后面 ——
     * 「扫描设备」与「手动选择」很容易撞上同一个文件，而列表顺序一跳用户会以为丢东西了。
     */
    fun merge(existing: List<KwmFile>, added: List<KwmFile>): List<KwmFile> {
        if (added.isEmpty()) return existing
        val seen = existing.mapTo(HashSet()) { it.key }
        val merged = ArrayList<KwmFile>(existing.size + added.size)
        merged.addAll(existing)
        added.forEach { file ->
            if (seen.add(file.key)) merged.add(file)
        }
        return merged
    }

    /** 移除指定项（界面上的「从列表里去掉」，不动磁盘文件）。 */
    fun remove(files: List<KwmFile>, keys: Set<String>): List<KwmFile> =
        if (keys.isEmpty()) files else files.filterNot { it.key in keys }

    /**
     * 「这份已经解密过了」的标记。
     *
     * 用 名字 + 大小 而不是 URI：同一个文件经 SAF 与 MediaStore 拿到的 URI 可能不同，
     * 但这两个字段一致，用户也能一眼看懂为什么标成已处理。
     */
    fun doneKeyOf(file: KwmFile): String = "${file.name}|${file.sizeBytes}"

    /** 已处理过的 key 集合，用于在列表里标注、以及避免重复解密出两份副本。 */
    fun doneKeys(files: List<KwmFile>, done: Set<String>): Set<String> =
        files.mapNotNullTo(HashSet()) { file ->
            doneKeyOf(file).takeIf { it in done }
        }
}
