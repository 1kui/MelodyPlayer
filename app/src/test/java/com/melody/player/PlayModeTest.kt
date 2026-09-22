package com.melody.player

import com.melody.player.core.PlayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放模式的循环与旧偏好迁移。
 *
 * 之前的实现是「随机」+「循环」两个独立开关，能组合出「单曲循环 + 随机」
 * 这种互相矛盾的状态。改成单一枚举后，必须保证：
 *  - 四个状态一定能转回来（不会漏掉或重复）
 *  - 老用户升级后不会莫名其妙变回顺序播放
 */
class PlayModeTest {

    @Test
    fun `循环一圈回到起点`() {
        var mode = PlayMode.SEQUENTIAL
        val seen = mutableListOf(mode)
        repeat(PlayMode.entries.size - 1) {
            mode = mode.next
            seen.add(mode)
        }
        assertEquals(
            listOf(
                PlayMode.SEQUENTIAL,
                PlayMode.LIST_LOOP,
                PlayMode.SINGLE_LOOP,
                PlayMode.SHUFFLE
            ),
            seen
        )
        assertEquals(PlayMode.SEQUENTIAL, mode.next)
    }

    @Test
    fun `每个状态的下一个都不等于自己`() {
        PlayMode.entries.forEach { assertEquals(false, it.next == it) }
    }

    @Test
    fun `只有顺序播放是默认态`() {
        assertTrue(PlayMode.SEQUENTIAL.isDefault)
        PlayMode.entries.filter { it != PlayMode.SEQUENTIAL }
            .forEach { assertFalse(it.isDefault) }
    }

    @Test
    fun `旧版随机开关直接映射成随机播放`() {
        assertEquals(PlayMode.SHUFFLE, PlayMode.fromLegacy(shuffle = true, repeatOrdinal = 0))
        // 旧版允许「随机 + 单曲循环」并存，迁移时以随机为准（单曲循环下随机本就不生效）
        assertEquals(PlayMode.SHUFFLE, PlayMode.fromLegacy(shuffle = true, repeatOrdinal = 2))
    }

    @Test
    fun `旧版循环序号按 关 列表 单曲 映射`() {
        assertEquals(PlayMode.SEQUENTIAL, PlayMode.fromLegacy(shuffle = false, repeatOrdinal = 0))
        assertEquals(PlayMode.LIST_LOOP, PlayMode.fromLegacy(shuffle = false, repeatOrdinal = 1))
        assertEquals(PlayMode.SINGLE_LOOP, PlayMode.fromLegacy(shuffle = false, repeatOrdinal = 2))
    }

    @Test
    fun `越界的存储值退化到顺序播放而不是崩溃`() {
        assertEquals(PlayMode.SEQUENTIAL, PlayMode.fromOrdinal(-1))
        assertEquals(PlayMode.SEQUENTIAL, PlayMode.fromOrdinal(99))
        PlayMode.entries.forEach { assertEquals(it, PlayMode.fromOrdinal(it.ordinal)) }
    }
}
