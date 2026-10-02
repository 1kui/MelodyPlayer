package com.melody.player.core.tags

/**
 * 从音频文件里读出来的内嵌封面。
 *
 * 为什么单独一个类型而不是复用 [EmbeddedLyrics]：那个类型的语义是"歌词"，
 * 构造入口 `EmbeddedLyrics.of` 在没有歌词时返回 null —— 封面正好相反，
 * **文件里有图但没歌词**是最常见的情况，用它表达会直接把这个封面丢掉。
 */
data class EmbeddedArtwork(
    /** 图片字节（JPEG / PNG，取决于文件里存的是什么）。 */
    val bytes: ByteArray,
    /** 来源描述，例如 "ID3v2.3 APIC"、"FLAC PICTURE"，用于在界面上如实展示。 */
    val container: String
) {
    val isEmpty: Boolean get() = bytes.isEmpty()

    // data class 的 equals/hashCode 对数组按引用比较，这里必须显式改：
    // 单测里"解析两次应当相等"这种断言用默认实现会莫名其妙地失败。
    override fun equals(other: Any?): Boolean =
        this === other || (other is EmbeddedArtwork && container == other.container && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * container.hashCode() + bytes.contentHashCode()

    companion object {
        fun of(bytes: ByteArray, container: String): EmbeddedArtwork? {
            if (bytes.isEmpty()) return null
            return EmbeddedArtwork(bytes, container)
        }
    }
}

/**
 * 内嵌封面解析：ID3v2 的 APIC/PIC 与 FLAC 的 PICTURE 元数据块。
 *
 * ## 为什么必须自己解析，而不能用 MediaMetadataRetriever
 * `MediaMetadataRetriever.getEmbeddedPicture()` 只在**文件被系统媒体库扫过**、
 * 且平台对该容器有实现时才稳定给结果；SD 卡 / SAF 授权来的文件经常返回 null，
 * 而这恰恰是本App 主要的曲库来源。所以底层自己解字节，行为可控可测。
 *
 * 覆盖范围刻意收窄到两种：ID3v2（MP3，另有AAC/AIFF 也挂ID3）与 FLAC。
 * MP4 的 `covr`、Ogg 的 `METADATA_BLOCK_PICTURE` 注释也常见，但当前曲库里
 * 几乎没有，且每多一种格式就多一份"写坏了就毁文件"的风险 ——
 * 宁可如实告诉用户"这个格式读不到内嵌封面"，也不要给一个偶尔出错的半吊子结果。
 */
object EmbeddedArtworkParser {

    /** 单张封面的字节上限。超过的多半是"整张专辑原图塞进标签"的畸形文件。 */
    private const val MAX_ART_BYTES = 16 * 1024 * 1024

    /** ID3 标签整体上限，与 [Id3v2Parser] 保持一致。 */
    private const val MAX_TAG_BYTES = 12 * 1024 * 1024

    private const val FLAC_BLOCK_PICTURE = 6
    private const val MAX_FLAC_BLOCKS = 64

    /** APIC 的图片类型：3 = 正面封面。 */
    private const val PICTURE_TYPE_FRONT = 3

    /**
     * 按容器分发。返回 null 表示"这个文件里没有可用的内嵌封面"或"格式不支持"。
     */
    fun parse(src: ByteSource): EmbeddedArtwork? {
        val head = src.readPrefix(16)
        if (head.size < 4) return null
        return runCatching {
            when {
                head.size >= 10 && TagBytes.startsWith(head, 0, "ID3") -> parseId3v2(src)
                TagBytes.startsWith(head, 0, "fLaC") -> parseFlac(src)
                else -> null
            }
        }.getOrNull()
    }

    // ------------------------------------------------------------------ ID3v2

    /**
     * 扫描 ID3v2 标签里的 APIC（v2.3/2.4）或 PIC（v2.2）。
     *
     * 取**第一张正面封面**；只有背面/图标等其它类型时才退回取任意一张——
     * 有些老文件只写了 type=0（其它），那张也总比没有封面强。
     */
    private fun parseId3v2(src: ByteSource): EmbeddedArtwork? {
        val head = src.readPrefix(10)
        if (head.size < 10 || !TagBytes.startsWith(head, 0, "ID3")) return null

        val major = TagBytes.int8(head, 3)
        val flags = TagBytes.int8(head, 5)
        val tagSize = minOf(TagBytes.syncSafe(head, 6).toLong(), MAX_TAG_BYTES.toLong()).toInt()
        if (tagSize <= 0) return null

        var body = src.readAt(10L, tagSize)
        if (body.isEmpty()) return null
        if (flags and 0x80 != 0) body = TextCodec.deUnsynchronise(body)

        var pos = skipExtendedHeader(body, major, flags)
        if (pos < 0) return null

        val idLen = if (major == 2) 3 else 4
        val sizeLen = if (major == 2) 3 else 4
        val flagsLen = if (major == 2) 0 else 2
        val headerLen = idLen + sizeLen + flagsLen

        var fallback: EmbeddedArtwork? = null
        while (pos + headerLen <= body.size) {
            val frameId = TagBytes.ascii(body, pos, idLen)
            if (frameId.isEmpty() || frameId[0] == '\u0000') break
            if (!frameId.all { it.isLetterOrDigit() }) break

            val frameSize = when {
                major == 2 -> TagBytes.uint24be(body, pos + idLen)
                major == 3 -> TagBytes.int32beAsInt(body, pos + idLen)
                else -> TagBytes.syncSafe(body, pos + idLen)
            }
            val dataStart = pos + headerLen
            if (frameSize <= 0 || dataStart + frameSize > body.size) break
            val dataEnd = dataStart + frameSize

            if (frameId == "APIC" || frameId == "PIC") {
                val frameFlags = if (major == 2) 0 else TagBytes.uint16be(body, pos + idLen + sizeLen)
                var payloadStart = dataStart
                if (major >= 4 && (frameFlags and 0x0001) != 0) payloadStart += 4
                var payload = body.copyOfRange(payloadStart, dataEnd)
                if (major >= 4 && (frameFlags and 0x0002) != 0) {
                    payload = TextCodec.deUnsynchronise(payload)
                }
                val container = "ID3v2.$major $frameId"
                val art = readApic(payload, container)
                if (art != null) {
                    // 正面封面直接收工；先撞到的非正面先留着当备选
                    if (isFrontCover(payload)) return art
                    if (fallback == null) fallback = art
                }
            }
            pos = dataEnd
        }
        return fallback
    }

    private fun skipExtendedHeader(body: ByteArray, major: Int, flags: Int): Int {
        if (flags and 0x40 == 0) return 0
        if (body.size < 4) return -1
        return if (major >= 4) {
            TagBytes.syncSafe(body, 0)
        } else {
            4 + TagBytes.int32beAsInt(body, 0)
        }.coerceIn(0, body.size)
    }

    /**
     * APIC 载荷：[0]=encoding [1..]=MIME\0 [..]=图片类型(1B) [..]=描述符\0 图片数据。
     *
     * **MIME 字段恒为 ISO-8859-1**，不受 [0] 那个 encoding 字节支配 ——
     * 这是 ID3v2 规范里最容易踩的一条：只有描述符与后面的图片数据才按 encoding 编码。
     * 拿 encoding 去读 MIME，遇到 UTF-16 的 APIC 会去找两字节终止符，
     * 结果把图片类型、描述符长度全读错，整帧解析直接失败。
     */
    private fun readApic(payload: ByteArray, container: String): EmbeddedArtwork? {
        if (payload.size < 4) return null
        val encoding = TagBytes.int8(payload, 0)
        val (mime, afterMime) = TextCodec.readTerminated(payload, TextCodec.ENC_LATIN1, 1)
        if (afterMime >= payload.size) return null
        val normalizedMime = normalizeMime(mime)
        if (normalizedMime.isEmpty()) return null
        // 图片类型占 1 字节
        val typePos = afterMime
        if (typePos >= payload.size) return null
        val (_, afterDescriptor) = TextCodec.readTerminated(payload, encoding, typePos + 1)
        val data = payload.copyOfRange(afterDescriptor, payload.size)
        if (data.isEmpty() || data.size > MAX_ART_BYTES) return null
        return EmbeddedArtwork.of(data, container)
    }

    /** 只看图片类型字节是不是"正面封面"。 */
    private fun isFrontCover(payload: ByteArray): Boolean {
        if (payload.size < 3) return false
        val encoding = TagBytes.int8(payload, 0)
        val (_, afterMime) = TextCodec.readTerminated(payload, TextCodec.ENC_LATIN1, 1)
        return afterMime < payload.size && TagBytes.int8(payload, afterMime) == PICTURE_TYPE_FRONT
    }

    private fun normalizeMime(raw: String): String {
        val mime = raw.trim().lowercase()
        if (mime.isEmpty()) return ""
        // v2.2 的 PIC 只有 3 字符
        if (!mime.contains('/')) {
            return when (mime) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "bmp" -> "image/bmp"
                else -> return ""
            }
        }
        // 有些工具写成 "image/jpeg\0"之外还带分隔符，或用大写
        return if (mime.startsWith("image/")) mime else ""
    }

    // ------------------------------------------------------------------ FLAC

    /**
     * FLAC 的 PICTURE 块（类型 6）。
     *
     * 结构：图片类型(4B) + MIME 长度(4B) + MIME + 描述长度(4B) + 描述 +
     * 宽高深色数各 4B + 数据长度(4B) + 图片数据。
     * 前置块必须逐个跳过才能找到它，所以这里要老实遍历元数据块序列。
     *
     * **所有长度字段都是大端** —— 这一条极容易写错：同一个 FLAC 文件里的
     * VORBIS_COMMENT 块用的却是**小端**（见 [LyricsTagWriter] 的同名工具）。
     * 照着注释块的习惯顺手写成小端，会读出 0x0A000000 这种明显不对的
     * MIME 长度（真值 10），然后整块解析失败，表现为"FLAC 文件读不到内嵌封面"。
     */
    private fun parseFlac(src: ByteSource): EmbeddedArtwork? {
        val magic = src.readPrefix(4)
        if (magic.size < 4 || !TagBytes.startsWith(magic, 0, "fLaC")) return null

        var offset = 4L
        var blocks = 0
        while (blocks < MAX_FLAC_BLOCKS && offset + 4 <= src.size) {
            val header = src.readAt(offset, 4)
            if (header.size < 4) return null
            val isLast = (header[0].toInt() and 0x80) != 0
            val type = header[0].toInt() and 0x7F
            val length = TagBytes.uint24be(header, 1)
            if (length < 0) return null
            offset += 4

            if (type == FLAC_BLOCK_PICTURE) {
                val size = minOf(length, MAX_ART_BYTES)
                val block = src.readAt(offset, size)
                return readFlacPicture(block)
            }
            if (isLast) return null
            offset += length
            blocks++
        }
        return null
    }

    private fun readFlacPicture(block: ByteArray): EmbeddedArtwork? {
        if (block.size < 32) return null
        var pos = 4 // 图片类型（大端，但值我们不用）
        val mimeLen = TagBytes.int32beAsInt(block, pos)
        pos += 4
        if (mimeLen <= 0 || mimeLen > block.size || pos + mimeLen > block.size) return null
        val mime = String(block, pos, mimeLen, Charsets.US_ASCII).trim().lowercase()
        if (!mime.startsWith("image/")) return null
        pos += mimeLen
        val descLen = TagBytes.int32beAsInt(block, pos)
        pos += 4
        if (descLen < 0 || pos + descLen > block.size) return null
        pos += descLen
        // 宽/高/色深/索引色数各 4 字节
        pos += 16
        if (pos + 4 > block.size) return null
        val dataLen = TagBytes.int32beAsInt(block, pos)
        pos += 4
        if (dataLen <= 0 || pos + dataLen > block.size) return null
        return EmbeddedArtwork.of(block.copyOfRange(pos, pos + dataLen), "FLAC PICTURE")
    }
}