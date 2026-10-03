package com.melody.player

import com.melody.player.core.online.CoverRegion
import com.melody.player.core.online.RegionPing
import com.melody.player.ui.screens.regionPingBadge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 地区测速结果在选项上的展示文案。
 *
 * 单独钉住它，是因为这里最容易"看起来对"：
 * 画在选项上的只有一行小字，写错没人会崩，只是用户看不懂 ——
 * 比如把"没测过"和"连不上"都画成一条横线，用户就没法知道该不该再点一次测速。
 *
 * 这一版把结果从"开关下面的一行摘要"改成了"每个选项自带一条标注"，
 * 所以断言的对象从 `regionPingSummary` 换成了 `regionPingBadge`；
 * 顺序由胶囊的选项顺序（[CoverRegion.entries]）保证，不再靠拼字符串。
 */
class RegionPingLabelTest {

    @Test
    fun `测通了显示毫秒`() {
        assertEquals("320 ms", regionPingBadge(RegionPing.Ok(320)).text)
    }

    @Test
    fun `连不上显示无法连接`() {
        assertEquals("无法连接", regionPingBadge(RegionPing.Unreachable).text)
    }

    @Test
    fun `没测过与正在测各有各的文案而不是同一条横线`() {
        assertEquals("未测速", regionPingBadge(null).text)
        assertEquals("测速中…", regionPingBadge(RegionPing.Testing).text)
    }

    @Test
    fun `只有连不上才标警示色`() {
        // 标红是一个承诺："这个状态需要你做点什么"。测速中会自己变，
        // 一闪一闪只会让人以为出错了；测通了更是正常状态。
        assertTrue(regionPingBadge(RegionPing.Unreachable).warn)
        assertFalse(regionPingBadge(RegionPing.Testing).warn)
        assertFalse(regionPingBadge(RegionPing.Ok(120)).warn)
        assertFalse(regionPingBadge(null).warn)
    }

    @Test
    fun `选项顺序按枚举声明且默认链排在最前`() {
        // 胶囊直接用 CoverRegion.entries 当选项，所以"按延迟从低到高勾"这件事
        // 至少要保证用户最常用的那三个区（默认链）排在最前面、且顺序稳定。
        // 谁把枚举重排了，这条会替他兜住。
        assertEquals(CoverRegion.DEFAULT, CoverRegion.entries.take(CoverRegion.DEFAULT.size))
    }
}
