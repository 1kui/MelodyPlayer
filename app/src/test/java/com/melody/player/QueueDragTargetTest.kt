package com.melody.player

import com.melody.player.ui.screens.RowLayout
import com.melody.player.ui.screens.queueDragTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 队列拖动排序的落点判算。
 *
 * 这段逻辑单看只有几行，但它错了不会崩溃、只会**行为很怪**：
 * 行跟不住手、列表抽搐、或者干脆拖不远。
 * 而这些都要在真机上用手指试出来，回归成本极高 ——
 * 所以把判算抽成纯函数，在这里把边界情形一次钉死。
 *
 * 这批断言是有来历的：判据最初写成"跨过任意一行的中心"（原地不动也乱换位），
 * 改成"只看紧邻的下一行"之后又变成一次只能挪一格（用户报"不能拖到指定位置"）。
 * 下面是修正后的语义：**被拖中心落在哪个槽位上，就落到哪里**。
 */
class QueueDragTargetTest {

    /** 5 行等高 100px，依次排在 0/100/200/300/400，中心 50/150/250/350/450。 */
    private fun rows(count: Int = 5, height: Int = 100): List<RowLayout> =
        (0 until count).map {
            RowLayout(index = it, offset = it * height, end = (it + 1) * height, size = height)
        }

    @Test
    fun `原地不动时目标就是自己`() {
        val rows = rows()
        assertEquals(2, queueDragTarget(rows, rows[2].center))
    }

    @Test
    fun `可以一次拖到任意位置而不是一格一格挪`() {
        // 这是这个函数存在的核心理由。第 0 行直接拖到第 4 行中心：
        // 早期"一次只挪一格"的版本这里会返回 1，用户就得反复拖
        val rows = rows()
        assertEquals(4, queueDragTarget(rows, rows[4].center))
        // 反方向同理
        assertEquals(0, queueDragTarget(rows, rows[0].center))
    }

    @Test
    fun `中心落在第 3 行槽位内即是第 3 行`() {
        val rows = rows()
        // 第 3 行占 300..400，中心 350。落点 320 与 380 都该算第 3 行
        assertEquals(3, queueDragTarget(rows, 320f))
        assertEquals(3, queueDragTarget(rows, 380f))
    }

    @Test
    fun `中心恰好等于某行中心时落在该行`() {
        val rows = rows()
        assertEquals(3, queueDragTarget(rows, 350f))
        assertEquals(1, queueDragTarget(rows, 150f))
    }

    @Test
    fun `拖到列表最上方落第一行`() {
        val rows = rows()
        assertEquals(0, queueDragTarget(rows, -500f))
    }

    @Test
    fun `拖到列表最下方落最后一行`() {
        val rows = rows()
        assertEquals(4, queueDragTarget(rows, 9999f))
    }

    @Test
    fun `行高不等时按各自的槽位算`() {
        // 0 号高 120（0..120，中心 60）、1 号高 80（120..200，中心 160）、
        // 2 号高 200（200..400，中心 300）
        val rows = listOf(
            RowLayout(0, 0, 120, 120),
            RowLayout(1, 120, 200, 80),
            RowLayout(2, 200, 400, 200)
        )
        assertEquals(0, queueDragTarget(rows, 60f))
        assertEquals(1, queueDragTarget(rows, 160f))
        assertEquals(2, queueDragTarget(rows, 390f))
        // 边界按半开区间 [offset, end) 算：119 还落在第 0 行（0..120）里，
        // 上沿 120 就归第 1 行；同理第 2 行的上沿 200 归第 2 行
        assertEquals(0, queueDragTarget(rows, 100f))
        assertEquals(0, queueDragTarget(rows, 119f))
        assertEquals(1, queueDragTarget(rows, 120f))
        assertEquals(2, queueDragTarget(rows, 200f))
    }

    @Test
    fun `可见行不是从 0 开始时下标照样正确`() {
        // 列表滚到中间，可见的只有队列里的第 5 到第 8 首
        val rows = listOf(
            RowLayout(5, 0, 100, 100),
            RowLayout(6, 100, 200, 100),
            RowLayout(7, 200, 300, 100),
            RowLayout(8, 300, 400, 100)
        )
        assertEquals(5, queueDragTarget(rows, -100f))
        assertEquals(6, queueDragTarget(rows, 150f))
        assertEquals(8, queueDragTarget(rows, 9999f))
    }

    @Test
    fun `空列表返回 -1 而不是崩`() {
        assertEquals(-1, queueDragTarget(emptyList(), 123f))
    }

    @Test
    fun `落点随中心单调变化不会来回跳`() {
        // 拖动中最怕的是"行在两格之间来回横跳"。判据只要单调，
        // 就不会出现"手指往下走、行却往回蹦"的现象
        val rows = rows(count = 6)
        var last = -1
        var center = -50f
        while (center <= 700f) {
            val target = queueDragTarget(rows, center)
            assertTrue("中心 $center 时落点 $target 比上一次 $last 还小", target >= last)
            last = target
            center += 7f
        }
        assertEquals(5, last)
    }
}
