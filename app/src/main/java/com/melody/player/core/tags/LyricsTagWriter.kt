package com.melody.player.core.tags

/**
 * 把歌词写进音频文件自带的标签区 —— 也就是「歌词与歌曲合并成一个文件」。
 *
 * 两个设计要点：
 *
 * 1. **只产出「新的文件头 + 原文件里要丢掉的字节数」**，音频数据由调用方流式拷贝。
 *    一首 40 MB 的 MP3 不必整段读进内存，出错的窗口也只有头部那一段。
 * 2. **保留原有帧**。直接重建标签会把封面图（APIC）、ReplayGain 之类的帧弄丢，
 *    所以这里只摘掉歌词相关的帧，其余原样搬过去。
 *
 * 覆盖 MP3（ID3v2 的 USLT）与 FLAC（Vorbis Comment 的 LYRICS）。返回 null 表示
 * 这个容器不适合原地重写（M4A / Ogg / WAV 等），调用方应当退到 App 私有的歌词副本 ——
 * 对播放效果没有区别，只是歌词不再跟着文件走。
 */
object LyricsTagWriter {

    /** 建标签时需要读进内存的头部字节数上限；超过这个长度的标签一律不写。 */
    const val HEAD_BYTES = 4 * 1024 * 1024

    /** 写标签时顺带补上的基础信息（仅在原文件缺这一帧时使用）。 */
    data class Meta(val title: String? = null, val artist: String? = null, val album: String? = null)

    /**
     * 重写方案。
     *
     * [newHead] 直接写在文件开头，然后从原文件的 [skipBytes] 处继续拷贝音频数据。
     */
    data class Rewrite(
        val newHead: ByteArray,
        val skipBytes: Long,
        val container: String,
        val embedded: Boolean
    )

    private const val ID3_HEADER = 10
    private const val BLOCK_VORBIS_COMMENT = 4
    private const val BLOCK_PADDING = 1
    private const val BLOCK_STREAMINFO = 0

    /** USLT 的语言字段必须是 3 个 ASCII 字符且非空；未知语言用 XXX。 */
    private const val LANGUAGE = "XXX"

    private val LYRICS_KEYS = setOf(
        "LYRICS", "LYRIC", "UNSYNCEDLYRICS", "UNSYNCHRONISEDLYRICS", "SYNCEDLYRICS", "LRC"
    )

    /**
     * 生成重写方案。返回 null 表示「写不进去」，调用方应改用 App 私有歌词副本。
     *
     * @param head 文件开头的字节（至少覆盖整个标签；见 [HEAD_BYTES]）
     * @param lyrics LRC 文本（带时间戳最好，纯文本也能放）
     */
    fun rewrite(head: ByteArray, lyrics: String, meta: Meta = Meta()): Rewrite? {
        val text = lyrics.trim()
        if (text.isEmpty()) return null
        return when {
            TagBytes.startsWith(head, 0, "ID3") -> rewriteId3v2(head, text, meta)
            TagBytes.startsWith(head, 0, "fLaC") -> rewriteFlac(head, text)
            looksLikeMpegAudio(head) ->
                Rewrite(id3Tag(3, emptyList(), text, meta), 0L, "MP3 · 新建 ID3v2.3", true)
            else -> null
        }
    }

    /**
     * 只写基础信息（标题/歌手/专辑），不带歌词。
     *
     * 给「KWM 解密出来的 MP3」用：那类文件是凭空编码出来的，一个标签都没有，
     * 不写的话在别的播放器里就是「未知曲目」。
     *
     * 与 [rewrite] 的关键区别：**已有标签里的歌词帧要保留**。
     * [rewrite] 是「用户改歌词」，摘掉旧歌词再写新的；这里是「补基础信息」，
     * 顺手把文件原有的歌词删掉显然是错的。
     *
     * 只处理 MP3（ID3v2）：解密默认输出就是 MP3，够用；其它容器原样保留，
     * 不做没验证过的重写。三个字段全空时返回 null，不做无意义的改动。
     */
    fun writeMeta(head: ByteArray, meta: Meta): Rewrite? {
        if (meta.title == null && meta.artist == null && meta.album == null) return null
        return when {
            TagBytes.startsWith(head, 0, "ID3") -> rewriteId3v2(head, null, meta)
            looksLikeMpegAudio(head) ->
                Rewrite(id3Tag(3, emptyList(), null, meta), 0L, "MP3 · 新建 ID3v2.3", false)
            else -> null
        }
    }

    // ------------------------------------------------------------------ ID3v2

    /**
     * 重写 ID3v2 标签。
     *
     * [lyrics] 为 null 表示「只补基础信息」——此时**保留**原有的歌词帧，
     * 只把缺的 TIT2/TPE1/TALB 补上。
     */
    private fun rewriteId3v2(head: ByteArray, lyrics: String?, meta: Meta): Rewrite? {
        if (head.size < ID3_HEADER) return null
        val major = TagBytes.int8(head, 3)
        val flags = TagBytes.int8(head, 5)
        val tagSize = TagBytes.syncSafe(head, 6)
        if (tagSize <= 0) return null
        if (ID3_HEADER + tagSize > head.size) return null

        val hasFooter = (flags and 0x10) != 0
        val skip = ID3_HEADER.toLong() + tagSize + if (hasFooter) 10L else 0L

        // v2.2 的帧 ID 是 3 字符、长度是 3 字节，与 3/4 不兼容：保留帧会写出坏标签，
        // 这种情况下宁可重建一个干净的 v2.3 标签（丢掉封面，但保证文件可用）。
        if (major !in 3..4) {
            return Rewrite(id3Tag(3, emptyList(), lyrics, meta), skip, "MP3 · 重建 ID3v2.3", lyrics != null)
        }
        // 整标签级的 unsynchronisation 会把 FF 拆成 FF 00；照搬帧字节会破坏位流。
        if (flags and 0x80 != 0) return null

        val end = ID3_HEADER + tagSize
        var pos = ID3_HEADER
        if (flags and 0x40 != 0) {
            val ext = if (major >= 4) TagBytes.syncSafe(head, pos) else 4 + TagBytes.int32beAsInt(head, pos)
            if (ext <= 0) return null
            pos += ext
        }

        val kept = ArrayList<ByteArray>()
        while (pos + 10 <= end) {
            val id = TagBytes.ascii(head, pos, 4)
            if (id.isEmpty() || id[0] == '\u0000' || !id.all { it.isLetterOrDigit() }) break
            val size = if (major >= 4) TagBytes.syncSafe(head, pos + 4) else TagBytes.int32beAsInt(head, pos + 4)
            if (size <= 0 || pos + 10 + size > end) break
            val next = pos + 10 + size
            // 换歌词时摘掉旧歌词帧；只补基础信息时原样保留
            if (lyrics == null || !isLyricsFrame(id, head, pos + 10, size)) {
                kept.add(head.copyOfRange(pos, next))
            }
            pos = next
        }

        return Rewrite(id3Tag(major, kept, lyrics, meta), skip, "MP3 · ID3v2.$major USLT", lyrics != null)
    }

    /** 歌词帧：USLT/SYLT 直接算，TXXX 要看描述符。 */
    private fun isLyricsFrame(id: String, data: ByteArray, offset: Int, size: Int): Boolean = when (id) {
        "USLT", "SYLT" -> true
        "TXXX" -> size > 1 && runCatching {
            val encoding = TagBytes.int8(data, offset)
            val descriptor = TextCodec.readTerminated(data, encoding, offset + 1, offset + size).first
            descriptor.trim().uppercase() in LYRICS_KEYS
        }.getOrDefault(false)
        else -> false
    }

    private fun id3Tag(major: Int, kept: List<ByteArray>, lyrics: String?, meta: Meta): ByteArray {
        val frames = ByteWriter()
        fun appendText(id: String, value: String?) {
            val text = value?.trim().takeUnless { it.isNullOrEmpty() } ?: return
            // 原标签里已经有这一帧就别重复添加
            val present = kept.any { it.size >= 4 && TagBytes.ascii(it, 0, 4) == id }
            if (!present) frames.raw(id3Frame(id, major, textPayload(text, major)))
        }
        appendText("TIT2", meta.title)
        appendText("TPE1", meta.artist)
        appendText("TALB", meta.album)
        kept.forEach { frames.raw(it) }
        // lyrics 为 null 表示「只补基础信息」，此时原标签里的歌词帧已经原样搬过来了
        lyrics?.let { frames.raw(id3Frame("USLT", major, usltPayload(it, major))) }

        val body = frames.toByteArray()
        return ByteWriter()
            .ascii("ID3")
            .u8(major)
            .u8(0)
            .u8(0)
            .syncSafe(body.size)
            .raw(body)
            .toByteArray()
    }

    private fun id3Frame(id: String, major: Int, payload: ByteArray): ByteArray {
        val w = ByteWriter().ascii(id)
        if (major >= 4) w.syncSafe(payload.size) else w.u32be(payload.size.toLong())
        return w.u16be(0).raw(payload).toByteArray()
    }

    /** v2.3 只允许 latin1 与 UTF-16，所以低版本用带 BOM 的 UTF-16；v2.4 直接用 UTF-8。 */
    private fun usltPayload(lyrics: String, major: Int): ByteArray {
        val w = if (major >= 4) {
            ByteWriter().u8(TextCodec.ENC_UTF8).ascii(LANGUAGE).u8(0).utf8(lyrics)
        } else {
            ByteWriter().u8(TextCodec.ENC_UTF16_BOM).ascii(LANGUAGE).u16be(0).utf16Bom(lyrics)
        }
        return w.toByteArray()
    }

    private fun textPayload(text: String, major: Int): ByteArray = if (major >= 4) {
        ByteWriter().u8(TextCodec.ENC_UTF8).utf8(text).toByteArray()
    } else {
        ByteWriter().u8(TextCodec.ENC_UTF16_BOM).utf16Bom(text).toByteArray()
    }

    /** MPEG 音频帧同步字：11 个 1 + 层号非 0（排除 ADTS/AAC，那种文件加 ID3 头不一定被解）。 */
    private fun looksLikeMpegAudio(head: ByteArray): Boolean {
        var i = 0
        while (i + 1 < head.size && i < 4096) {
            val b0 = TagBytes.int8(head, i)
            val b1 = TagBytes.int8(head, i + 1)
            if (b0 == 0xFF && (b1 and 0xE0) == 0xE0 && (b1 and 0x06) != 0) return true
            i++
        }
        return false
    }

    // ------------------------------------------------------------------ FLAC

    private fun rewriteFlac(head: ByteArray, lyrics: String): Rewrite? {
        if (head.size < 8) return null

        val kept = ArrayList<ByteArray>()
        var vendor = "Melody Player"
        var comments: List<String> = emptyList()
        var streamInfoSeen = false
        var pos = 4
        var end = 4
        var last = false

        while (!last) {
            if (pos + 4 > head.size) return null
            val first = TagBytes.int8(head, pos)
            last = (first and 0x80) != 0
            val type = first and 0x7F
            val length = TagBytes.uint24be(head, pos + 1)
            if (pos + 4 + length > head.size) return null

            when (type) {
                BLOCK_VORBIS_COMMENT -> {
                    val parsed = parseVorbis(head, pos + 4, length)
                    vendor = parsed.first
                    comments = parsed.second
                }
                // 填充块只是占位，重排元数据区时丢弃即可
                BLOCK_PADDING -> Unit
                else -> {
                    if (type == BLOCK_STREAMINFO) streamInfoSeen = true
                    // 先清掉「最后一块」标志，等全部块排完再由我们指定
                    val raw = head.copyOfRange(pos, pos + 4 + length)
                    raw[0] = (raw[0].toInt() and 0x7F).toByte()
                    kept.add(raw)
                }
            }
            pos += 4 + length
            end = pos
        }
        // STREAMINFO 必须是第一个块，缺了说明这不是我们能安全重写的 FLAC
        if (!streamInfoSeen) return null

        val entries = comments.filterNot { keyOf(it) in LYRICS_KEYS } + "LYRICS=$lyrics"
        val commentBlock = vorbisBlock(vendor, entries)
        commentBlock[0] = (commentBlock[0].toInt() or 0x80).toByte()

        val w = ByteWriter().ascii("fLaC")
        kept.forEach { w.raw(it) }
        w.raw(commentBlock)
        return Rewrite(w.toByteArray(), end.toLong(), "FLAC · Vorbis Comment", true)
    }

    /** 返回厂商串与原始 "KEY=VALUE" 条目（大小写原样保留）。 */
    private fun parseVorbis(data: ByteArray, offset: Int, length: Int): Pair<String, List<String>> {
        val end = offset + length
        if (offset < 0 || end > data.size) return "Melody Player" to emptyList()
        val vendorLen = TagBytes.uint32le(data, offset).toInt()
        if (vendorLen < 0 || offset + 4 + vendorLen > end) return "Melody Player" to emptyList()
        val vendor = String(data, offset + 4, vendorLen, Charsets.UTF_8)
        var pos = offset + 4 + vendorLen
        if (pos + 4 > end) return vendor to emptyList()
        val count = TagBytes.uint32le(data, pos).toInt()
        pos += 4

        val out = ArrayList<String>()
        var i = 0
        while (i < count && pos + 4 <= end) {
            val len = TagBytes.uint32le(data, pos).toInt()
            pos += 4
            if (len < 0 || pos + len > end) break
            out.add(String(data, pos, len, Charsets.UTF_8))
            pos += len
            i++
        }
        return vendor to out
    }

    private fun keyOf(entry: String): String = entry.substringBefore('=').trim().uppercase()

    private fun vorbisBlock(vendor: String, entries: List<String>): ByteArray {
        val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
        val body = ByteWriter().u32le(vendorBytes.size.toLong()).raw(vendorBytes).u32le(entries.size.toLong())
        entries.forEach { entry ->
            val bytes = entry.toByteArray(Charsets.UTF_8)
            body.u32le(bytes.size.toLong()).raw(bytes)
        }
        val payload = body.toByteArray()
        return ByteWriter().u8(BLOCK_VORBIS_COMMENT).u24be(payload.size).raw(payload).toByteArray()
    }
}
