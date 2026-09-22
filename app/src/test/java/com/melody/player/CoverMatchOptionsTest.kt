package com.melody.player

import com.melody.player.core.online.CoverRegion
import com.melody.player.core.online.ITunesApi
import com.melody.player.core.online.ITunesHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.4 新增的两个「用户可调」的纯逻辑：搜索地区链与匹配门槛。
 *
 * 这两处的失败都是**静默**的 —— 地区选错只是搜不到、门槛调错只是多贴/少贴几张封面，
 * 都不会报错。所以用单测把边界钉住：脏数据要有确定行为，门槛要真的按传进去的值生效。
 */
class CoverMatchOptionsTest {

    private fun hit(
        trackId: Long,
        title: String,
        artist: String = "",
        durationMs: Long = 0L
    ) = ITunesHit(
        trackId = trackId,
        collectionId = 0L,
        title = title,
        artist = artist,
        album = "",
        artworkUrl100 = null,
        durationMs = durationMs,
        releaseDate = null,
        genre = null
    )

    // ------------------------------------------------------------------ 地区

    @Test
    fun `默认地区链是华语优先且不含中国大陆`() {
        assertEquals(
            listOf(CoverRegion.TW, CoverRegion.HK, CoverRegion.US),
            CoverRegion.DEFAULT
        )
        // 接口那边用的就是这串 code —— 两份数据必须是同一份，否则"默认行为"会有两种说法
        assertEquals(CoverRegion.DEFAULT_CODES, ITunesApi.COUNTRIES)
        assertTrue(ITunesApi.COUNTRIES.first() == "TW")
        // 中国大陆区 iTunes Store 没有音乐目录（夹具 search_qingtian_cn.json 就是那个空响应），
        // 所以连"可选项"都不提供：一块永远搜不到东西的勾选框，只会让人以为功能坏了
        assertTrue(CoverRegion.entries.none { it.code == "CN" })
    }

    @Test
    fun `地区按 code 查找不区分大小写`() {
        assertEquals(CoverRegion.TW, CoverRegion.fromCode("TW"))
        assertEquals(CoverRegion.TW, CoverRegion.fromCode("tw"))
        assertEquals(CoverRegion.TW, CoverRegion.fromCode("  Tw "))
        assertNull(CoverRegion.fromCode("CN"))
        assertNull(CoverRegion.fromCode(""))
        assertNull(CoverRegion.fromCode(null))
    }

    @Test
    fun `自定义地区会被收敛成合法列表`() {
        // 去重 + 顺序即用户选择顺序（先选的先搜）
        assertEquals(
            listOf(CoverRegion.JP, CoverRegion.US),
            CoverRegion.sanitize(listOf("JP", "US", "JP"))
        )
        // 不认识的 code 丢掉，不影响其余
        assertEquals(
            listOf(CoverRegion.HK),
            CoverRegion.sanitize(listOf("HK", "CN", "XX", ""))
        )
        // 超过上限的部分裁掉：每多一个地区就多一次接口调用，而接口有限流
        assertEquals(
            CoverRegion.MAX_SELECTED,
            CoverRegion.sanitize(CoverRegion.entries.map { it.code }).size
        )
    }

    @Test
    fun `一个地区都没有时退回默认链而不是留空`() {
        // 空列表意味着「哪个地区都不搜」= 取封面永远失败。那不是用户会主动想要的状态，
        // 只可能是存储坏了，所以必须有一个安全的落点
        assertEquals(CoverRegion.DEFAULT, CoverRegion.sanitize(emptyList()))
        assertEquals(CoverRegion.DEFAULT, CoverRegion.sanitize(listOf("CN", "不存在的区")))
    }

    // ------------------------------------------------------------------ 门槛

    @Test
    fun `满分候选刚好卡在上限上`() {
        // 歌名一致 100 + 歌手一致 40 + 时长 0 秒差 30 = 170
        val perfect = hit(1L, "Perfect", artist = "Ed Sheeran", durationMs = 263_400L)
        assertEquals(ITunesApi.MAX_SCORE, ITunesApi.score(perfect, "Perfect", "Ed Sheeran", 263_400L))
        // 门槛 <= 分数才收；调高到满分之上就一个都不剩（这就是"宁可不给"的那一端）
        assertEquals(
            perfect,
            ITunesApi.pickBest(listOf(perfect), "Perfect", "Ed Sheeran", 263_400L, ITunesApi.MAX_SCORE)
        )
        assertNull(
            ITunesApi.pickBest(
                listOf(perfect), "Perfect", "Ed Sheeran", 263_400L, ITunesApi.MAX_SCORE + 1
            )
        )
    }

    @Test
    fun `同一个候选在门槛两侧得到相反的结果`() {
        // 歌名完全一致 100，但歌手字段是空的（本地标签常见）→ 倒扣 20 = 80 分，
        // 正好落在默认阈值 90 之下、又在"宽松"的 70 之上
        val loose = hit(2L, "Perfect", artist = "")
        assertEquals(80, ITunesApi.score(loose, "Perfect", "Ed Sheeran", 0L))

        assertNull("默认 90 分应该把它挡掉", ITunesApi.pickBest(listOf(loose), "Perfect", "Ed Sheeran", 0L))
        assertEquals(
            "门槛调到 70 就该收下它（这正是设置页那个滑块的作用）",
            loose,
            ITunesApi.pickBest(listOf(loose), "Perfect", "Ed Sheeran", 0L, 70)
        )
    }

    @Test
    fun `门槛默认值与可调范围自洽`() {
        assertTrue(ITunesApi.MIN_SCORE_FLOOR < ITunesApi.MIN_SCORE)
        assertTrue(ITunesApi.MIN_SCORE < ITunesApi.MAX_SCORE)
        // 默认值必须落在可调区间内，否则偏好存储会一读就被夹走、用户看到的与存的不一致
        assertTrue(ITunesApi.MIN_SCORE in ITunesApi.MIN_SCORE_FLOOR..ITunesApi.MAX_SCORE)
        // 滑块按"每 10 分一挡"排，区间宽度得能被 10 整除，否则最后一挡会是个畸形的短区间
        assertEquals(0, (ITunesApi.MAX_SCORE - ITunesApi.MIN_SCORE_FLOOR) % 10)
    }
}
