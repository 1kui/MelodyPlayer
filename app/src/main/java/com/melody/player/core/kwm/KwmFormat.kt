package com.melody.player.core.kwm

/**
 * 酷我（KuWo）KWM 加密容器的格式常量与派生规则。
 *
 * 这是**纯 Kotlin**，不引用任何 Android 类型 —— 解密算法里有几处特别容易写错的地方
 * （掩码串的循环填充、掩码下标跨块连续、8 字节密钥的无符号解释），
 * 放在这里就能在 JVM 单测里直接拿真实文件回归。
 *
 * 文件布局（固定，没有压缩也没有分块）：
 *
 * | 偏移   | 长度    | 内容                          |
 * |--------|---------|-------------------------------|
 * | 0x00   | 16 字节 | 魔数 `yeelion-kuwo-tme`        |
 * | 0x10   | 8 字节  | 版本号 + 保留                  |
 * | 0x18   | 8 字节  | 文件密钥（小端无符号整数）      |
 * | 0x2C   | 3 字节  | 真实音频格式标记（有的话）      |
 * | 0x400  | 其余    | 被 XOR 加密的**整个**音频文件  |
 *
 * 「整个」这个词是核对过的：说明文档里那个真实文件输入 12,720,513 字节、
 * 输出 12,719,489 字节，差值正好是 1024 的头部 —— 所以 0x400 之后是完整的
 * 音频文件（含它自己的容器头），而不是「去掉了头的音频数据」。
 *
 * 关于合法用途：这层壳挡的只是「双击直接播放」，解开它等价于格式转换。
 * 它的作用域是用户自己合法下载的音乐的跨设备播放与归档。
 */
object KwmFormat {

    /** 文件头魔数。`yeelion` 是酷我母公司的英文名，`tme` 指腾讯音乐娱乐集团。 */
    const val MAGIC = "yeelion-kuwo-tme"

    /** 头部固定 1024 字节，之后才是被加密的音频体。 */
    const val HEADER_SIZE = 0x400

    /** 8 字节文件密钥的偏移。 */
    const val KEY_OFFSET = 0x18

    /** 3 字节真实格式标记的偏移（内容仅供参考，真正的格式靠明文头部嗅探）。 */
    const val FORMAT_OFFSET = 0x2C

    /** 掩码长度固定 32 字节。 */
    const val MASK_SIZE = 32

    /**
     * 酷我客户端内置的固定串。
     *
     * 掩码 = 这个串 ⊕ 由文件密钥派生的 32 字符密钥。也就是说「密钥」一半来自文件
     * （明文躺在 0x18）、一半来自客户端二进制 —— 这正是所有本地 DRM 的死结：
     * 客户端必须能离线解密，所以解密所需的一切必然在本机拿得到。
     */
    const val PREDEFINED_KEY = "MoOtOiTvINGwd2E6n0E1i7L5t2IoOoNk"

    /**
     * 由文件密钥派生 32 字节 XOR 掩码。
     *
     * 两步：
     *  1. 把密钥写成**十进制字符串**，不足 32 位就循环重复、超过则截断；
     *  2. 与 [PREDEFINED_KEY] 逐字符异或。
     *
     * 注意用**无符号**解释这 8 个字节：真实值通常不到 2^63，但格式上没这个保证，
     * 高位一旦被置 1，按有符号读会变成负数、派生出一串错误的掩码。
     */
    fun buildMask(fileKey: Long): ByteArray {
        val raw = fileKey.toULong().toString()
        val key32 = if (raw.length >= MASK_SIZE) {
            raw.substring(0, MASK_SIZE)
        } else {
            // 至少重复到 32 位再截断；raw 不可能为空（最短是 "0"）
            raw.repeat(MASK_SIZE / raw.length + 1).substring(0, MASK_SIZE)
        }
        return ByteArray(MASK_SIZE) { i ->
            (PREDEFINED_KEY[i].code xor key32[i].code).toByte()
        }
    }

    /** 头部是否以魔数开头。 */
    fun hasMagic(header: ByteArray): Boolean {
        if (header.size < MAGIC.length) return false
        for (i in MAGIC.indices) {
            if (header[i].toInt() and 0xFF != MAGIC[i].code) return false
        }
        return true
    }

    /** 取 0x18 处的 8 字节小端无符号整数。 */
    fun fileKeyOf(header: ByteArray): Long {
        if (header.size < KEY_OFFSET + 8) return 0L
        var value = 0L
        for (i in 0 until 8) {
            value = value or ((header[KEY_OFFSET + i].toLong() and 0xFF) shl (8 * i))
        }
        return value
    }

    /** 头部 0x2C 处的格式标记（酷我自己写的，不一定与真实格式一致，仅供展示）。 */
    fun declaredFormat(header: ByteArray): String? {
        if (header.size < FORMAT_OFFSET + 3) return null
        val text = buildString {
            for (i in 0 until 3) {
                val c = header[FORMAT_OFFSET + i].toInt() and 0xFF
                if (c == 0 || c !in 0x20..0x7E) return@buildString
                append(c.toChar())
            }
        }
        return text.takeIf { it.length == 3 }
    }

    /**
     * 从**明文**头部嗅探真实音频格式。
     *
     * 为什么必须嗅探：KWM 头里那个 3 字节格式标记不总是有值，而扩展名更不可信
     * （输入文件叫 `.kwm`）。真正算数的只有解密后那几个字节。
     * 返回 null 表示认不出来，调用方应保留原样并如实告知，而不是硬猜一个扩展名。
     */
    fun sniffExtension(head: ByteArray): String? {
        if (head.size >= 4) {
            if (head.startsWithAscii(0, "OggS")) return "ogg"
            if (head.startsWithAscii(0, "fLaC")) return "flac"
            if (head.startsWithAscii(0, "RIFF")) return "wav"
        }
        if (head.size >= 3 && head.startsWithAscii(0, "ID3")) return "mp3"
        if (head.size >= 8 && head.startsWithAscii(4, "ftyp")) return "m4a"
        // 裸 MPEG 帧同步字：11 个 1 + 层号非 0（后者用来排除 ADTS/AAC，那种格式加 .mp3 后缀不一定被解）
        if (head.size >= 2) {
            val b0 = head[0].toInt() and 0xFF
            val b1 = head[1].toInt() and 0xFF
            if (b0 == 0xFF && (b1 and 0xE0) == 0xE0 && (b1 and 0x06) != 0) return "mp3"
        }
        return null
    }

    private fun ByteArray.startsWithAscii(offset: Int, text: String): Boolean {
        if (size < offset + text.length) return false
        for (i in text.indices) {
            if (this[offset + i].toInt() and 0xFF != text[i].code) return false
        }
        return true
    }
}
