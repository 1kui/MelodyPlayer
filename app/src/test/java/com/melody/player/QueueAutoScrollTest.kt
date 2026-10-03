package com.melody.player

import com.melody.player.ui.screens.queueAutoScrollDir
import com.melody.player.ui.screens.queueAutoScrollSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 拖动排序时"什么时候该自动滚动、滚多快"的判算。
 *
 * 这一组断言来自用户报的三个具体现象：
 *  - **往上滚超过几首就断触** —— 那是手势挂在了会被回收的行上（见 `QueueScreen` 的注释），
 *    不是这里的判算；这里只钉住方向算法本身。
 *  - **往下要拖到被底栏盖住才开始滚** —— 这就是 [queueAutoScrollDir] 的输入错了：
 *    调用方如果直接把 `viewportEndOffset` 当可视底边传进来，它含着的
 *    `contentPadding`（迷你条 + 导航栏）会把热区推到屏幕之外。
 *    所以这里两条都断言：正确的输入会滚，错误的输入**不滚**（后者是"如果谁改回去"的哨兵）。
 *  - **滚动不够平滑** —— 原来的实现是"每帧推进固定 22px"，且速度与手指进热区的深度无关；
 *    现在改成"速度 × 真实帧间隔"，速度随深度线性上升，见 [queueAutoScrollSpeed] 的那几条。
 */
class QueueAutoScrollTest {

    /** 常见情形：可视区 0..2000，热区 150。 */
    private fun dir(center: Float, bottom: Float = 2000f, zone: Float = 150f): Int =
        queueAutoScrollDir(center = center, viewportTop = 0f, viewportBottom = bottom, edgeZone = zone)

    private fun speed(center: Float, bottom: Float = 2000f, zone: Float = 150f): Float =
        queueAutoScrollSpeed(center = center, viewportTop = 0f, viewportBottom = bottom, edgeZone = zone)

    @Test
    fun `中心在上热区里就往上滚`() {
        assertEquals(-1, dir(center = 0f))
        assertEquals(-1, dir(center = 100f))
        assertEquals(-1, dir(center = 149f))
    }

    @Test
    fun `中心在下热区里就往下滚`() {
        assertEquals(1, dir(center = 2000f))
        assertEquals(1, dir(center = 1900f))
        assertEquals(1, dir(center = 1851f))
    }

    @Test
    fun `中心在中间时不动`() {
        assertEquals(0, dir(center = 150f))
        assertEquals(0, dir(center = 1000f))
        assertEquals(0, dir(center = 1850f))
    }

    @Test
    fun `下边界要减掉被底栏盖住的那一段`() {
        // 可视区 2000 里，最下面 400 被迷你条 + 导航栏盖着，真正看得见的底边是 1600。
        // 手指停在 1500（离看得见的底边只有 100）时就该开始往下滚
        assertEquals(1, dir(center = 1500f, bottom = 2000f - 400f))
        // 若把没减底栏的边界（甚至 viewportEndOffset 那种"含 padding"的更大值）传进来，
        // 1500 就落到中间去了 —— 那正是"要拖到底栏下面才滚"的表现
        assertEquals(0, dir(center = 1500f, bottom = 2000f))
        assertEquals(0, dir(center = 1500f, bottom = 2000f + 400f))
    }

    @Test
    fun `列表很短时上下热区不会重叠`() {
        // 可视区只有 200px。热区若不收敛到可视区的三分之一以内，
        // 200 这个位置会同时命中"上热区"和"下热区"，行就在两点之间来回抖
        assertEquals(-1, dir(center = 10f, bottom = 200f, zone = 150f))
        assertEquals(0, dir(center = 100f, bottom = 200f, zone = 150f))
        assertEquals(1, dir(center = 190f, bottom = 200f, zone = 150f))
    }

    @Test
    fun `可视区高度为零时不滚也不会除零`() {
        assertEquals(0, dir(center = 10f, bottom = 0f))
        assertEquals(0, queueAutoScrollDir(10f, viewportTop = 500f, viewportBottom = 500f, edgeZone = 150f))
    }

    @Test
    fun `可视区不从零开始时也按相对位置判断`() {
        // 列表顶部有 8dp 的 contentPadding 时 viewportStartOffset 不是 0
        assertEquals(-1, queueAutoScrollDir(10f, viewportTop = 20f, viewportBottom = 2020f, edgeZone = 150f))
        assertEquals(1, queueAutoScrollDir(1980f, viewportTop = 20f, viewportBottom = 2020f, edgeZone = 150f))
        assertEquals(0, queueAutoScrollDir(1000f, viewportTop = 20f, viewportBottom = 2020f, edgeZone = 150f))
    }

    // ---------------------------------------------------------------- 速度

    @Test
    fun `刚进热区就有速度且方向与边一致`() {
        // 一进热区就"稍微动起来"，用户才知道这个方向是通的；
        // 若从 0 起步，手指在热区边界上抖动时会在"滚一点点"和"完全不滚"之间跳
        assertTrue(speed(center = 149f) < 0f)
        assertTrue(speed(center = 1851f) > 0f)
        assertEquals(0f, speed(center = 150f), 0.001f)
        assertEquals(0f, speed(center = 1850f), 0.001f)
        assertEquals(0f, speed(center = 1000f), 0.001f)
    }

    @Test
    fun `越靠边滚得越快`() {
        // 平滑的关键：速度不是"进热区就拉满"，而是随深度线性爬升。
        // 台阶式（进热区 = 立刻最快）正是用户说的"一顿一顿"。
        // 比的是**速率**（绝对值）：上侧的速度是负数，直接比大小会得到相反的结果。
        val justInside = abs(speed(center = 145f))
        val middle = abs(speed(center = 75f))
        val atEdge = abs(speed(center = 0f))
        assertTrue("中段应快于刚进热区：$middle vs $justInside", middle > justInside)
        assertTrue("贴边应快于中段：$atEdge vs $middle", atEdge > middle)
        // 贴边至少要比刚进热区快 3 倍，否则"线性爬升"等于没做
        assertTrue("贴边与刚进热区的差距太小：$atEdge vs $justInside", atEdge > justInside * 3f)
    }

    @Test
    fun `上下两侧的速度对称`() {
        val up = speed(center = 30f)
        val down = speed(center = 1970f)
        assertEquals(up, -down, 0.001f)
    }

    @Test
    fun `拖出可视区外速度不再继续变大`() {
        // 手指被夹在行边缘之后仍可能算出 >1 的深度，必须收敛到上限，
        // 否则"越拖越快地滚"会一路加速到失控
        val beyond = speed(center = -400f)
        assertEquals(speed(center = 0f), beyond, 0.001f)
    }

    @Test
    fun `列表很短时速度也只在三分之一的可视区内出现`() {
        // 与方向那一条同源：上下热区不能重叠，否则速度会同时有正负两个来源
        assertTrue(speed(center = 10f, bottom = 200f, zone = 150f) < 0f)
        assertEquals(0f, speed(center = 100f, bottom = 200f, zone = 150f), 0.001f)
        assertTrue(speed(center = 190f, bottom = 200f, zone = 150f) > 0f)
    }
}
