package com.melody.player.core.tags

/** 大端/小端整数字节读取的小工具，避免到处写位运算。 */
internal object TagBytes {

    fun ascii(data: ByteArray, offset: Int, length: Int): String {
        if (offset < 0 || offset + length > data.size || length <= 0) return ""
        val sb = StringBuilder(length)
        for (i in offset until offset + length) {
            sb.append((data[i].toInt() and 0xFF).toChar())
        }
        return sb.toString()
    }

    fun int8(data: ByteArray, offset: Int): Int =
        if (offset in data.indices) data[offset].toInt() and 0xFF else 0

    /** 大端 16 位无符号。 */
    fun uint16be(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    /** 大端 24 位无符号。 */
    fun uint24be(data: ByteArray, offset: Int): Int {
        if (offset + 2 >= data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 16) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            (data[offset + 2].toInt() and 0xFF)
    }

    /** 大端 32 位（可超出 Int 范围时截断为 -1）。 */
    fun int32be(data: ByteArray, offset: Int): Long {
        if (offset + 3 >= data.size) return -1L
        return ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
    }

    fun int32beAsInt(data: ByteArray, offset: Int): Int = int32be(data, offset).toInt()

    /** 小端 16 位无符号。 */
    fun uint16le(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        return ((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset].toInt() and 0xFF)
    }

    /** 小端 32 位无符号（返回 Long 防止符号污染）。 */
    fun uint32le(data: ByteArray, offset: Int): Long {
        if (offset + 3 >= data.size) return -1L
        return ((data[offset + 3].toLong() and 0xFF) shl 24) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            (data[offset].toLong() and 0xFF)
    }

    /** ID3v2 的 syncsafe 长度：每字节只用低 7 位。 */
    fun syncSafe(data: ByteArray, offset: Int): Int {
        if (offset + 3 >= data.size) return 0
        return ((data[offset].toInt() and 0x7F) shl 21) or
            ((data[offset + 1].toInt() and 0x7F) shl 14) or
            ((data[offset + 2].toInt() and 0x7F) shl 7) or
            (data[offset + 3].toInt() and 0x7F)
    }

    fun startsWith(data: ByteArray, offset: Int, token: String): Boolean {
        if (offset < 0 || offset + token.length > data.size) return false
        for (i in token.indices) {
            if ((data[offset + i].toInt() and 0xFF) != token[i].code) return false
        }
        return true
    }
}
