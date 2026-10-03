package com.melody.player

import com.melody.player.core.online.CoverRegion
import com.melody.player.core.online.RegionPing
import com.melody.player.core.online.latencyLabel
import com.melody.player.core.online.regionPingSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 地区测速结果的展示文案。
 *
 * 单独钉住它，是因为这里最容易"看起来对"：
 * 界面上只有一行字，写错没人会崩，只是用户看不懂 ——
 * 比如把"没测过"和"连不上"都画成一条横线，用户就没法知道该不该再点一次测速。
 */
class RegionPingLabelTest {

    @Test
    fun `测通了显示毫秒`() {
        assertEquals("320 ms", latencyLabel(RegionPing.Ok(320)))
    }

    @Test
    fun `连不上显示无法连接`() {
        assertEquals("无法连接", latencyLabel(RegionPing.Unreachable))
    }

    @Test
    fun `没测过与正在测各有各的文案而不是同一条横线`() {
        assertEquals("未测速", latencyLabel(null))
        assertEquals("测速中…", latencyLabel(RegionPing.Testing))
    }

    @Test
    fun `摘要逐条显示结果`() {
        val summary = regionPingSummary(
            mapOf(
                CoverRegion.TW.code to RegionPing.Ok(120),
                CoverRegion.HK.code to RegionPing.Unreachable
            )
        )
        assertTrue(summary.contains("中国台湾 120 ms"))
        assertTrue(summary.contains("中国香港 无法连接"))
        // 没测到的那些也要出现，否则用户以为列表里只有两个地区
        assertTrue(summary.contains("美国 未测速"))
    }

    @Test
    fun `顺序固定为地区枚举顺序而不随结果完成先后变`() {
        val all = regionPingSummary(emptyMap())
        val tw = all.indexOf("中国台湾")
        val hk = all.indexOf("中国香港")
        val us = all.indexOf("美国")
        assertTrue(tw in 0 until hk)
        assertTrue(hk in 0 until us)
        // 每个地区各一条，分隔符数量 = 地区数 - 1
        assertEquals(CoverRegion.entries.size - 1, all.count { it == '·' })
    }

    @Test
    fun `每个地区都出现在摘要里`() {
        val all = regionPingSummary(emptyMap())
        CoverRegion.entries.forEach { region ->
            assertTrue("摘要里没有 ${region.label}", all.contains(region.label))
        }
    }
}
