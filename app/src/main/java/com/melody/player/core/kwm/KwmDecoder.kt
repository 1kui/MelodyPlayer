package com.melody.player.core.kwm

import java.io.InputStream
import java.io.OutputStream

/** KWM 解密失败的原因，消息直接展示给用户，所以要是人话。 */
class KwmException(message: String) : Exception(message)

/** 一次解密的产物。 */
data class KwmDecryption(
    /** 0x18 处的文件密钥（通常等于文件名里的那串数字）。 */
    val fileKey: Long,
    /** 解密后明文头部嗅探出的真实格式；null 表示认不出来。 */
    val extension: String?,
    /** 头部自己声明的格式，仅供参考。 */
    val declaredFormat: String?,
    /** 解出来的明文字节数。 */
    val plainBytes: Long,
    /** 明文开头若干字节，用于展示与排查。 */
    val plainHead: ByteArray
) {
    // plainHead 是 ByteArray，data class 的默认 equals/hashCode 是引用比较，明确覆盖掉
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * KWM 解密。
 *
 * 算法本身只有一句话：用文件里的整数拼出一个 32 字节掩码，对音频体做循环异或。
 * 异或的自反性（`(A ^ B) ^ B = A`）保证加解密是同一个操作 —— 写文件的一方
 * 和读文件的一方共用这段代码，所以它不像看起来那样「少了一半」。
 *
 * 全程流式：一首歌十几 MB，不能整段读进内存再吐出来。
 */
object KwmDecoder {

    /** 64KB 一块 —— 与说明文档里的参考实现一致，纯属够大又能控住内存。 */
    const val BUFFER_BYTES = 64 * 1024

    /** 留存多少明文字节用于嗅探与展示。 */
    private const val HEAD_SAMPLE = 16

    /**
     * 解密 [input] 到 [output]，返回格式与统计。
     *
     * 从流的**当前位置**开始读，所以调用方传入的流应当是文件开头。
     * 抛 [KwmException] 的三种情形都对应真实的坏输入：文件太短、
     * 魔数不对（新版加密或选错了文件）、里面没有音频数据。
     */
    fun decrypt(input: InputStream, output: OutputStream): KwmDecryption {
        val header = ByteArray(KwmFormat.HEADER_SIZE)
        var headerRead = 0
        while (headerRead < header.size) {
            val n = input.read(header, headerRead, header.size - headerRead)
            if (n < 0) break
            if (n == 0) continue
            headerRead += n
        }
        if (headerRead < header.size) {
            throw KwmException("文件小于 ${KwmFormat.HEADER_SIZE} 字节，不是有效的 KWM")
        }
        if (!KwmFormat.hasMagic(header)) {
            val hex = header.take(8).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
            throw KwmException("文件头不是 ${KwmFormat.MAGIC}（实际为 $hex…），可能是新版加密或选错了文件")
        }

        val fileKey = KwmFormat.fileKeyOf(header)
        val mask = KwmFormat.buildMask(fileKey)

        val buffer = ByteArray(BUFFER_BYTES)
        val head = ByteArray(HEAD_SAMPLE)
        var headLen = 0
        var total = 0L

        /*
         * 掩码下标必须**跨读块连续**。
         *
         * 这是整个算法唯一容易写错的地方。注意它比看上去更隐蔽：我们的缓冲区是 64KB，
         * 而 65536 正好是 32 的整数倍，所以「每块内部从 0 开始用 `i % 32`」这种写法
         * 在**自己的缓冲区**上恰好是对的 —— 单元测试如果只喂一个 ByteArrayInputStream，
         * 根本测不出问题。
         *
         * 真正会踩雷的是**短读**：`ContentResolver` 打开的内容流经常一次只返回几千字节，
         * 而只要那个长度不是 32 的倍数，第二块起的所有字节都会错位解密。
         * 症状是「能打开、开头正常、后面全是噪声」，极易误判成文件损坏。
         * 所以下标必须是**流内全局**的，与 [InputStream.read] 返回多少无关。
         */
        var maskIndex = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue

            var i = 0
            while (i < n) {
                buffer[i] = (buffer[i].toInt() xor mask[maskIndex].toInt()).toByte()
                maskIndex++
                if (maskIndex == KwmFormat.MASK_SIZE) maskIndex = 0
                i++
            }

            if (headLen < head.size) {
                val take = minOf(head.size - headLen, n)
                System.arraycopy(buffer, 0, head, headLen, take)
                headLen += take
            }

            output.write(buffer, 0, n)
            total += n
        }

        if (total == 0L) throw KwmException("文件里没有音频数据")

        return KwmDecryption(
            fileKey = fileKey,
            extension = KwmFormat.sniffExtension(head),
            declaredFormat = KwmFormat.declaredFormat(header),
            plainBytes = total,
            plainHead = if (headLen == head.size) head else head.copyOf(headLen)
        )
    }
}
