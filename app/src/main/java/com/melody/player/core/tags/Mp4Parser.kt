package com.melody.player.core.tags

/**
 * MP4 / M4A 解析：递归下降查找 ©lyr 原子（iTunes 规范里的歌词字段）。
 *
 * 注意两个坑：
 *  1. moov 有相当比例位于文件尾部（非 faststart 的文件），所以必须支持按偏移读取；
 *  2. meta 是 FullBox，其子原子从 +4 字节开始（跳过 version/flags），漏掉就找不到 ilst。
 */
object Mp4Parser {

    private const val MAX_DEPTH = 8
    /** 会继续向下递归的容器原子。 */
    private val CONTAINERS = setOf("moov", "udta", "meta", "ilst", "trak", "mdia", "minf", "stbl", "©lyr")

    private const val LYRICS_ATOM = "\u00A9lyr"

    fun parse(src: ByteSource): EmbeddedLyrics? {
        val head = src.readPrefix(16)
        if (head.size < 12) return null
        if (!TagBytes.startsWith(head, 4, "ftyp")) return null

        val node = findAtom(src, LYRICS_ATOM, 0L, src.size, 0) ?: return null
        val dataNode = findAtom(src, "data", node.first, node.second, 0)

        val readyText = if (dataNode != null) {
            val length = (dataNode.second - dataNode.first).toInt()
            if (length > 8) {
                val payload = src.readAt(dataNode.first, length)
                // data 原子内容：[0..3]=类型指示(1=UTF-8) [4..7]=locale [8..]=正文
                String(payload, 8, payload.size - 8, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
            } else null
        } else null

        val title = readTextAtom(src, "\u00A9nam")
        val artist = readTextAtom(src, "\u00A9ART")
        val album = readTextAtom(src, "\u00A9alb")

        return EmbeddedLyrics.of(
            container = "MP4 ©lyr",
            text = readyText,
            title = title,
            artist = artist,
            album = album
        )
    }

    private fun readTextAtom(src: ByteSource, atom: String): String? {
        val node = findAtom(src, atom, 0L, src.size, 0) ?: return null
        val dataNode = findAtom(src, "data", node.first, node.second, 0) ?: return null
        val length = (dataNode.second - dataNode.first).toInt()
        if (length <= 8) return null
        val payload = src.readAt(dataNode.first, length)
        return String(payload, 8, payload.size - 8, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
    }

    /** 返回目标原子的「内容区间」；找不到返回 null。 */
    private fun findAtom(src: ByteSource, target: String, start: Long, end: Long, depth: Int): Pair<Long, Long>? {
        if (depth > MAX_DEPTH || start >= end) return null
        var offset = start
        var guard = 0
        while (offset + 8 <= end && guard < 4096) {
            guard++
            val header = src.readAt(offset, 16)
            if (header.size < 8) return null

            var size = TagBytes.int32be(header, 0)
            val type = TagBytes.ascii(header, 4, 4)
            var headerLen = 8L
            when {
                size == 1L -> {
                    if (header.size < 16) return null
                    size = (TagBytes.int32be(header, 8) shl 32) or TagBytes.int32be(header, 12)
                    headerLen = 16L
                }
                size == 0L -> size = end - offset
            }
            if (size < headerLen || offset + size > end) return null

            val contentStart = offset + headerLen
            val contentEnd = offset + size

            if (type == target) return contentStart to contentEnd

            if (type in CONTAINERS) {
                // meta 是 FullBox，子原子前面多 4 字节 version/flags
                val childStart = if (type == "meta") contentStart + 4 else contentStart
                val found = findAtom(src, target, childStart, contentEnd, depth + 1)
                if (found != null) return found
            }
            offset = contentEnd
        }
        return null
    }
}
