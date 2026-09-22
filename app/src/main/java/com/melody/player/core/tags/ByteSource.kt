package com.melody.player.core.tags

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * 可随机读取的字节来源。
 *
 * 之所以自己做一层抽象而不是直接用 InputStream：ID3 标签在文件头、FLAC 元数据块也在文件头，
 * 但 MP4 的 moov 原子有相当比例位于文件尾部，必须支持按偏移读取才能高效拿到歌词。
 */
interface ByteSource : Closeable {

    /** 数据总长度（字节）。 */
    val size: Long

    /**
     * 从 [offset] 开始读取最多 [length] 字节。
     * 允许返回少于 [length] 字节（读到文件尾时）。
     */
    fun readAt(offset: Long, length: Int): ByteArray

    /** 读取文件前 [maxBytes] 字节，用于格式嗅探与头部解析。 */
    fun readPrefix(maxBytes: Int): ByteArray = readAt(0L, maxBytes)

    override fun close() {}
}

/** 内存字节数组来源：单元测试与「不可寻址的 content URI」回退路径使用。 */
class ByteArraySource(private val data: ByteArray) : ByteSource {

    override val size: Long get() = data.size.toLong()

    override fun readAt(offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset >= data.size || length <= 0) return ByteArray(0)
        val start = offset.toInt()
        val end = minOf(data.size, start + length)
        return data.copyOfRange(start, end)
    }
}

/** 本地文件来源，基于 RandomAccessFile 随机读取。 */
class FileByteSource(private val file: File) : ByteSource {

    private val raf = RandomAccessFile(file, "r")

    override val size: Long get() = raf.length()

    override fun readAt(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length <= 0) return ByteArray(0)
        val len = minOf(length.toLong(), size - offset).toInt()
        if (len <= 0) return ByteArray(0)
        raf.seek(offset)
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = raf.read(buf, read, len - read)
            if (n <= 0) break
            read += n
        }
        return if (read == len) buf else buf.copyOf(read)
    }

    override fun close() {
        runCatching { raf.close() }
    }
}

/**
 * 基于 FileChannel 的来源，用于 ContentResolver 打开的可寻址文件描述符
 * （content:// 形式的音乐文件走这条路，避免依赖 _data 列的真实路径）。
 */
class ChannelByteSource(
    channel: FileChannel,
    override val size: Long,
    private val onClose: () -> Unit = {}
) : ByteSource {

    private val ch = channel

    override fun readAt(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length <= 0) return ByteArray(0)
        val len = minOf(length.toLong(), size - offset).toInt()
        if (len <= 0) return ByteArray(0)
        val buf = ByteBuffer.allocate(len)
        var pos = offset
        while (buf.hasRemaining()) {
            val n = ch.read(buf, pos)
            if (n <= 0) break
            pos += n
        }
        return if (buf.remaining() == 0) buf.array() else buf.array().copyOf(buf.position())
    }

    override fun close() {
        runCatching { ch.close() }
        runCatching { onClose() }
    }
}
