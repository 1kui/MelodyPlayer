package com.melody.player

import com.melody.player.ui.screens.DragDecision
import com.melody.player.ui.screens.RowLayout
import com.melody.player.ui.screens.queueDragTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 队列拖动排序的换位判算。
 *
 * 这段逻辑单看只有十几行，但它错了不会崩溃、只会**行为很怪**：
 * 拖动时行跟不住手、列表抽搐、或者干脆换不动。
 * 而这些都要在真机上用手指试出来，回归成本极高 ——
 * 所以把判算抽成纯函数，在这里把边界情形一次钉死。
 *
 * 这批断言是有来历的：判据最初写成"跨过任意一行的中心"（而不是只看紧邻的下一行），
 * 补偿的符号也写反了，两处都被下面的用例逮住。
 */
class QueueDragTargetTest {

    /** 5 行等高 100px，依次排在 0/100/200/300/400，中心 50/150/250/350/450。 */
    private fun rows(count: Int = 5, height: Int = 100): List<RowLayout> =
        (0 until count).map {
            RowLayout(index = it, offset = it * height, end = (it + 1) * height, size = height)
        }

    @Test
    fun `原地不动时返回 null`() {
        // 第 2 行中心 250，相邻两行是 150 与 350，都没过 —— 不该换位。
        // 早期版本会把"所有中心在它上方的行"都当目标，于是中心 50 的第 0 行
        // 也被判成"已跨过"，原地就乱换位
        val rows = rows()
        assertNull(queueDragTarget(rows, draggingIndex = 2, draggedCenter = rows[2].center))
    }

    @Test
    fun `没跨过下一行中心时不该换位`() {
        val rows = rows()
        // 第 1 行中心 150，往下只挪 10px → 160，下一行中心 250 还没跨过
        assertNull(queueDragTarget(rows, draggingIndex = 1, draggedCenter = 160f))
    }

    @Test
    fun `往下拖跨过下一行中心即换位且补偿为负`() {
        val rows = rows()
        // 第 2 行中心 250，拖到 370 > 下一行（第 3 行）中心 350
        val decision = queueDragTarget(rows, draggingIndex = 2, draggedCenter = 370f)
        assertEquals(3, decision?.to)
        // 往下换位后基准位置变低，偏移必须**变小**才能把行留在手指底下。
        // 符号写反的表征是行朝反方向窜一大截
        assertEquals(-100f, decision?.compensation)
    }

    @Test
    fun `往上拖跨过上一行中心即换位且补偿为正`() {
        val rows = rows()
        // 第 2 行中心 250，拖到 130 < 上一行（第 1 行）中心 150
        val decision = queueDragTarget(rows, draggingIndex = 2, draggedCenter = 130f)
        assertEquals(1, decision?.to)
        assertEquals(100f, decision?.compensation)
    }

    @Test
    fun `一次只挪一格而不是跳到最远的那一行`() {
        val rows = rows()
        // 第 0 行中心 50，一次拖到 460：中心线跨过了 1/2/3/4 行。
        // 但一次只挪一格，剩下几格靠后续 onDrag 继续推进 ——
        // 一次跳到第 4 格会让行在手指底下瞬移，反而更跟不住
        val decision = queueDragTarget(rows, draggingIndex = 0, draggedCenter = 460f)
        assertEquals(1, decision?.to)
    }

    @Test
    fun `补偿量按被拖行自己的高度算而不是被跨过那行的`() {
        val rows = listOf(
            RowLayout(0, 0, 120, 120),
            RowLayout(1, 120, 200, 80),
            RowLayout(2, 200, 400, 200)
        )
        // 中心正好等于下一行中心 160：不算跨过
        assertNull(queueDragTarget(rows, draggingIndex = 0, draggedCenter = 160f))
        // 拖过一点：170 > 160，换位
        val decision = queueDragTarget(rows, draggingIndex = 0, draggedCenter = 170f)
        assertEquals(1, decision?.to)
        // 用**被拖行**的高度 120（不是被跨过那行的 80）：
        // 换位后变的是被拖行自己的基准位置
        assertEquals(-120f, decision?.compensation)
    }

    @Test
    fun `找不到被拖行时返回 null 而不是崩`() {
        // 列表刚滚出去、这一行已经不在可见区里 —— layoutInfo 里就没有它
        val rows = rows(count = 3)
        assertNull(queueDragTarget(rows, draggingIndex = 99, draggedCenter = 150f))
    }

    @Test
    fun `到列表两端时不会越界`() {
        val rows = rows(count = 3)
        // 第一行往上拖到很远：没有上一行，不换位
        assertNull(queueDragTarget(rows, draggingIndex = 0, draggedCenter = -500f))
        // 最后一行往下拖到很远：没有下一行，不换位
        assertNull(queueDragTarget(rows, draggingIndex = 2, draggedCenter = 9999f))
    }

    @Test
    fun `连续拖动时行始终贴着手指`() {
        // 这一条是上面所有规则的联合检验：符号、逐格推进、基准跟踪，
        // 三者只要有一处错，末尾的 rendered 就会偏离手指
        val rows = rows(count = 8)
        var index = 0                       // 当前下标
        var offset = 0f                     // 相对该行基准位置的手指位移
        var base = rows[0].offset.toFloat() // 该行当前的基准位置
        val startCenter = rows[0].center    // 按下时手指正落在行中心上

        repeat(6) {
            offset += 40f                   // 手指又往下走了 40px
            val center = base + offset + rows[index].size / 2f
            val decision = queueDragTarget(rows, draggingIndex = index, draggedCenter = center)
            if (decision != null) {
                offset += decision.compensation
                base = rows[decision.to].offset.toFloat()
                index = decision.to
            }
        }

        // 渲染出来的中心必须仍然等于手指位置
        val rendered = base + offset + rows[index].size / 2f
        assertEquals(startCenter + 6 * 40f, rendered, 0.5f)
    }

    @Test
    fun `连拖六步共推进两格`() {
        val rows = rows(count = 8)
        var index = 0
        var offset = 0f
        var base = rows[0].offset.toFloat()

        repeat(6) {
            offset += 40f
            val center = base + offset + rows[index].size / 2f
            val decision = queueDragTarget(rows, draggingIndex = index, draggedCenter = center)
            if (decision != null) {
                offset += decision.compensation
                base = rows[decision.to].offset.toFloat()
                index = decision.to
            }
        }
        // 每次 40px、每格 100px：手指共走了 240px，扣掉换位时补回的那两格，
        // 净跨过 240 - 200 = 40px，只够压过两格中心 → 下标 2。
        // 这条钉住"逐格推进、不跳格"：写成 3 就说明某一步多跳了一格
        assertEquals(2, index)
    }
}
