package com.melody.player

import com.melody.player.core.kwm.KwmDecoder
import com.melody.player.core.kwm.KwmException
import com.melody.player.core.kwm.KwmFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * KWM 解密的回归测试。
 *
 * 这里刻意分成两半，因为**两类错误的表现完全不同**：
 *
 *  1. **真实文件逐字节比对** —— 用的是与说明文档同源算法生成、且与作者那份
 *     `kwm_decrypt.py` 的输出做过逐字节核对的夹具。它保证「整条链路」是对的：
 *     头部解析、密钥取值、掩码派生、循环异或，任何一环错都不可能碰巧对上。
 *  2. **合成边界用例** —— 掩码下标跨块连续是唯一一处「写错了前 64KB 还是对的」的地方，
 *     而真实夹具都不到 64KB，根本踩不到。所以必须自己造一个跨块的数据。
 *
 * 掩码的真值全部来自参考实现（不是本工程算出来的），写死在测试里 ——
 * 否则一旦 `buildMask` 被改错，用它生成期望值的测试会跟着一起错，等于没测。
 */
class KwmDecoderTest {

    // ------------------------------------------------------------ 掩码派生

    /**
     * 掩码必须与参考实现逐字节一致。
     *
     * 期望值由作者提供的算法算出（`mask(95769)` 也印在《KWM解密说明.md》里）：
     * 文件密钥先转**十进制字符串**，循环填充到 32 位，再与固定串逐字符异或。
     */
    @Test
    fun maskMatchesReferenceImplementation() {
        assertEquals(
            "745a7842765061417f777e4253047c0f5b07730850027b034d0b7c587956775e",
            hex(KwmFormat.buildMask(95769L))
        )
        assertEquals(
            "7c5d7c407a5f63477b7d7342520574045d0470075e067e0640077f587e5d7d5f",
            hex(KwmFormat.buildMask(1234567L))
        )
        assertEquals(
            "7d5f7f447f596446797e7747540275065e00750159077c054402795f7f5f7e5b",
            hex(KwmFormat.buildMask(0L))
        )
        assertEquals(
            "7a587843785e63417e79704053057201590772065e007b0243057e587858795c",
            hex(KwmFormat.buildMask(7L))
        )
    }

    /**
     * 密钥按**无符号** 64 位解释。
     *
     * 8 字节密钥里有高位为 1 的字节时，按有符号读会得到一个负数，
     * 十进制串会多出一个负号、掩码整串作废 —— 表现为「解密出来全是噪声」。
     */
    @Test
    fun maskTreatsFileKeyAsUnsigned() {
        // Long.MIN_VALUE 的无符号读法是 2^63 = 9223372036854775808
        assertEquals(
            "745d7d477c5e66467a787f425005720356007d085b057f064300795c79577b5f",
            hex(KwmFormat.buildMask(Long.MIN_VALUE))
        )
        assertEquals(
            "9223372036854775808",
            Long.MIN_VALUE.toULong().toString()
        )
    }

    // ------------------------------------------------------------ 头部解析

    @Test
    fun detectsMagicOnlyWhenItReallyIsOne() {
        assertTrue(KwmFormat.hasMagic(header(95769L)))
        // 差一个字节就不认 —— 「差不多」在这里等于把用户的普通音频当加密文件处理
        val notMagic = header(95769L).also { it[7] = 'X'.code.toByte() }
        assertTrue(!KwmFormat.hasMagic(notMagic))
        assertTrue(!KwmFormat.hasMagic(ByteArray(8)))
    }

    @Test
    fun readsFileKeyAsLittleEndian() {
        val h = header(95769L)
        assertEquals(95769L, KwmFormat.fileKeyOf(h))
        // 0x18 处 8 个字节全 0xFF → 无符号 2^64-1，按有符号读是 -1
        val allOnes = ByteArray(KwmFormat.HEADER_SIZE).also { h2 ->
            for (i in 0 until 8) h2[KwmFormat.KEY_OFFSET + i] = 0xFF.toByte()
        }
        assertEquals(-1L, KwmFormat.fileKeyOf(allOnes))
        assertEquals("18446744073709551615", KwmFormat.fileKeyOf(allOnes).toULong().toString())
    }

    @Test
    fun readsDeclaredFormatOnlyWhenPrintable() {
        assertEquals("OGG", KwmFormat.declaredFormat(header(95769L, format = "OGG")))
        // 全 0 的格式标记在真实文件里很常见，必须如实返回 null 而不是三个空字符
        assertNull(KwmFormat.declaredFormat(header(95769L, format = "\u0000\u0000\u0000")))
    }

    // ------------------------------------------------------------ 格式嗅探

    /** 嗅探必须看**明文**头几个字节；扩展名和 KWM 头里的格式标记都不可信。 */
    @Test
    fun sniffsRealFormatFromPlainHead() {
        assertEquals("ogg", KwmFormat.sniffExtension("OggS\u0000\u0002".toLatin1()))
        assertEquals("flac", KwmFormat.sniffExtension("fLaC\u0000\u0000".toLatin1()))
        assertEquals("wav", KwmFormat.sniffExtension("RIFF\u0010\u0000".toLatin1()))
        assertEquals("mp3", KwmFormat.sniffExtension("ID3\u0003\u0000".toLatin1()))
        assertEquals("m4a", KwmFormat.sniffExtension("....ftypM4A ".toLatin1()))
        // 裸 MPEG 帧同步：11 个 1 + 层号非 0
        assertEquals("mp3", KwmFormat.sniffExtension(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)))
        // ADTS/AAC 也是 0xFF 0xF?，但层号字段为 0，加 .mp3 后缀不一定能被解出来 → 不认
        assertNull(KwmFormat.sniffExtension(byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50.toByte(), 0x00)))
        // 认不出来就返回 null，交给上层保留原样并如实告知，不硬猜
        assertNull(KwmFormat.sniffExtension("nonsense bytes".toLatin1()))
        assertNull(KwmFormat.sniffExtension(byteArrayOf(0x4F)))
        assertNull(KwmFormat.sniffExtension(ByteArray(0)))
    }

    // ------------------------------------------------------------ 真实文件

    @Test
    fun decryptsOggFixtureByteForByte() {
        val kwm = resource("/kwm/sample_ogg.kwm")
        val expected = resource("/kwm/sample_ogg_expected.ogg")

        val result = decrypt(kwm)

        assertArrayEquals(expected, result.plain)
        assertEquals(expected.size.toLong(), result.plainBytes)
        assertEquals(95769L, result.fileKey)
        assertEquals("ogg", result.extension)
        assertEquals("OggS", result.plainHead.copyOf(4).toLatin1())
    }

    @Test
    fun decryptsMp3FixtureByteForByte() {
        val kwm = resource("/kwm/sample_mp3.kwm")
        val expected = resource("/kwm/sample_mp3_expected.mp3")

        val result = decrypt(kwm)

        assertArrayEquals(expected, result.plain)
        assertEquals(expected.size.toLong(), result.plainBytes)
        assertEquals(1234567L, result.fileKey)
        assertEquals("mp3", result.extension)
        assertEquals("ID3", result.plainHead.copyOf(3).toLatin1())
    }

    /**
     * 掩码下标必须**跨读块连续**，而且这条只有在**短读**下才暴露得出来。
     *
     * 这里有个很隐蔽的陷阱：解码缓冲区是 64KB，而 65536 恰恰是 32 的整数倍，
     * 所以「每块内部从 0 开始用 `i % 32`」这种写法在**自己的缓冲区**上碰巧是对的 ——
     * 单测如果只喂一个 ByteArrayInputStream（每次都吐满 64KB），压根测不出来。
     * 这个用例最初就是这么写的，把解码器改成按块重置下标后它照样通过。
     *
     * 真正踩雷的是短读：`ContentResolver` 打开的内容流经常一次只返回几千字节，
     * 只要那个长度不是 32 的倍数，第二块起的所有字节就会整体错位。
     * 所以这里专门造一个「每次最多吐 1000 字节」的流（1000 % 32 = 8）。
     */
    @Test
    fun maskIndexIsContinuousAcrossShortReads() {
        // 这两条断言是这个用例「还测得出问题」的前提，写出来免得以后被人改没了
        assertTrue(
            "短读长度必须是 32 的非整数倍，否则重置下标也看不出来",
            SHORT_READ % KwmFormat.MASK_SIZE != 0
        )
        assertTrue(
            "解码缓冲区若是 32 的整数倍，只靠整块读同样测不出问题",
            KwmDecoder.BUFFER_BYTES % KwmFormat.MASK_SIZE == 0
        )

        // 长度取到跨过解码器自己的 64KB 缓冲区，顺便覆盖「缓冲区边界」这条路径
        val length = KwmDecoder.BUFFER_BYTES * 2 + SHORT_READ * 3
        val plain = ByteArray(length)
        // 开头放真格式的魔数，顺带验证嗅探取的是第一段现有字节
        "OggS".toLatin1().copyInto(plain, 0)
        // 非周期数据：明文若恰好以 32 为周期，错位有可能被掩盖掉
        var seed = 0x12345678
        for (i in 4 until length) {
            seed = seed * 1103515245 + 12345
            plain[i] = (seed ushr 16).toByte()
        }

        val kwm = container(key = 95769L, encrypted = xorWithMask(plain, MASK_95769))

        val result = decryptStream(ChoppedStream(kwm, SHORT_READ))

        assertArrayEquals(plain, result.plain)
        assertEquals(length.toLong(), result.plainBytes)
        assertEquals("ogg", result.extension)
    }

    // ------------------------------------------------------------ 坏输入

    @Test
    fun rejectsFileShorterThanHeader() {
        // 只给了半个头部：既读不出密钥也不可能有音频体
        val short = ByteArray(KwmFormat.HEADER_SIZE - 1).also {
            KwmFormat.MAGIC.toByteArray(Charsets.ISO_8859_1).copyInto(it)
        }
        val error = assertKwmException(short)
        assertTrue(error.contains("1024") || error.contains("0x400"))
    }

    @Test
    fun rejectsWrongMagic() {
        // 一个普通文件被误选：必须报错，绝不能把用户的正常音频「解密」成噪声覆盖掉
        val wrong = container(key = 95769L, encrypted = ByteArray(4096))
        wrong[0] = 'X'.code.toByte() // yeelion → Xeelion
        val error = assertKwmException(wrong)
        assertTrue(error.contains(KwmFormat.MAGIC))
    }

    @Test
    fun rejectsHeaderWithoutAudioBody() {
        // 头部合法但一个字节音频都没有：解出来是空文件，归档进去只会多一首「空歌」
        val headerOnly = container(key = 95769L, encrypted = ByteArray(0))
        val error = assertKwmException(headerOnly)
        assertTrue(error.contains("音频"))
    }

    // ------------------------------------------------------------ 辅助

    private class Decrypted(val plain: ByteArray, val plainBytes: Long, val fileKey: Long, val extension: String?, val plainHead: ByteArray)

    private fun decrypt(bytes: ByteArray): Decrypted = decryptStream(ByteArrayInputStream(bytes))

    private fun decryptStream(stream: java.io.InputStream): Decrypted {
        val out = ByteArrayOutputStream()
        val result = KwmDecoder.decrypt(stream, out)
        return Decrypted(out.toByteArray(), result.plainBytes, result.fileKey, result.extension, result.plainHead)
    }

    /**
     * 每次最多吐 [maxRead] 字节的流。
     *
     * 真实的内容流（`ContentResolver.openInputStream`）就是这样「读不满」的，
     * 而 [ByteArrayInputStream] 永远读满 —— 用它测不出跨块下标的错误。
     */
    private class ChoppedStream(private val data: ByteArray, private val maxRead: Int) : java.io.InputStream() {
        private var pos = 0

        override fun read(): Int = if (pos >= data.size) -1 else data[pos++].toInt() and 0xFF

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(len, maxRead, data.size - pos)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }

    private fun assertKwmException(bytes: ByteArray): String {
        try {
            decrypt(bytes)
        } catch (e: KwmException) {
            return e.message.orEmpty()
        }
        fail("应当抛出 KwmException")
        return ""
    }

    /** 造一个头部合法、指定位数的加密体，用来单独检验某条错误分支。 */
    private fun container(key: Long, encrypted: ByteArray): ByteArray {
        val head = header(key, format = "")
        head[KwmFormat.FORMAT_OFFSET] = 0
        head[KwmFormat.FORMAT_OFFSET + 1] = 0
        head[KwmFormat.FORMAT_OFFSET + 2] = 0
        return head + encrypted
    }

    /** 1024 字节头部：魔数 + 版本 + 小端密钥 + 可选的 3 字节格式标记。 */
    private fun header(key: Long, format: String = "OGG"): ByteArray {
        val h = ByteArray(KwmFormat.HEADER_SIZE)
        KwmFormat.MAGIC.toByteArray(Charsets.ISO_8859_1).copyInto(h)
        for (i in 0 until 8) {
            h[KwmFormat.KEY_OFFSET + i] = ((key ushr (8 * i)) and 0xFF).toByte()
        }
        if (format.isNotEmpty()) {
            format.toLatin1().copyInto(h, KwmFormat.FORMAT_OFFSET)
        }
        return h
    }

    private fun xorWithMask(input: ByteArray, mask: ByteArray): ByteArray =
        ByteArray(input.size) { i -> (input[i].toInt() xor mask[i % mask.size].toInt()).toByte() }

    private fun resource(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(path)) { "缺少测试夹具 $path" }.use { it.readBytes() }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun String.toLatin1(): ByteArray = toByteArray(Charsets.ISO_8859_1)

    private fun ByteArray.toLatin1(): String = toString(Charsets.ISO_8859_1)

    private companion object {
        /** 短读长度：刻意取 32 的非整数倍，否则「按下标重置」这种错法看不出来。 */
        const val SHORT_READ = 1000

        /** key = 95769 时的掩码，来自参考实现（文档里那个真实文件用的就是这把密钥）。 */
        val MASK_95769 = byteArrayOf(
            0x74, 0x5a, 0x78, 0x42, 0x76, 0x50, 0x61, 0x41,
            0x7f, 0x77, 0x7e, 0x42, 0x53, 0x04, 0x7c, 0x0f,
            0x5b, 0x07, 0x73, 0x08, 0x50, 0x02, 0x7b, 0x03,
            0x4d, 0x0b, 0x7c, 0x58, 0x79, 0x56, 0x77, 0x5e
        )
    }
}
