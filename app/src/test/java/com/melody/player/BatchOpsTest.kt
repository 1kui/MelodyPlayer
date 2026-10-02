package com.melody.player

import com.melody.player.core.BatchOps
import com.melody.player.core.BatchOps.Group
import com.melody.player.core.Playlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量操作的分组与汇报。
 *
 * 这里盯的是**汇报的诚实性**，不是功能能不能跑：批量最典型的翻车方式不是崩溃，
 * 而是"部分成功却只报一句『完成』"，用户只好自己回头逐首核对。
 * 所以断言集中在"条数对得上"和"没做成的必须被说出来"这两件事上。
 */
class BatchOpsTest {

    // ------------------------------------------------------------------ 汇报文案

    @Test
    fun `各桶条数加起来必须等于处理条数`() {
        val groups = listOf(
            Group.Done(listOf("a", "b", "c")),
            Group.Unchanged(listOf("d")),
            Group.Absent(listOf("e")),
            Group.Rejected(listOf("f"), "撞名"),
            Group.Failed(listOf("g", "h"), "写盘失败")
        )
        assertEquals(8, BatchOps.accountedCount(groups))
    }

    @Test
    fun `全成功时只说做了几首 不啰嗦`() {
        val groups = listOf(Group.Done(listOf("a", "b", "c")))
        assertEquals("已加入歌单 3 首", BatchOps.summary(groups, "加入歌单"))
    }

    @Test
    fun `没改动时要说清是没改动 而不是已处理 0 首`() {
        val groups = listOf(Group.Unchanged(listOf("a", "b")))
        assertEquals("没有改动；原本就那样 2 首", BatchOps.summary(groups, "加入歌单"))
    }

    @Test
    fun `全空时不谎报成功`() {
        assertEquals("没有可处理的曲目", BatchOps.summary(emptyList(), "加入歌单"))
    }

    @Test
    fun `部分成功也要把没做成的逐类说出来`() {
        val groups = listOf(
            Group.Done(listOf("a", "b")),
            Group.Unchanged(listOf("c")),
            Group.Rejected(listOf("d"), "歌单已满"),
            Group.Failed(listOf("e", "f"), "写盘失败")
        )
        val text = BatchOps.summary(groups, "加入歌单")
        assertTrue(text, text.startsWith("已加入歌单 2 首"))
        assertTrue(text, text.contains("原本就那样 1 首"))
        assertTrue(text, text.contains("被拒绝 1 首（歌单已满）"))
        assertTrue(text, text.contains("失败 2 首（写盘失败）"))
    }

    @Test
    fun `单位可换 去封面时说的是张`() {
        val groups = listOf(Group.Done(listOf("a")), Group.Unchanged(listOf("b", "c")))
        val text = BatchOps.summary(groups, "去除封面", unit = "张")
        assertEquals("已去除封面 1 张；原本就那样 2 张", text)
    }

    @Test
    fun `已不在目标里与原本就那样分开报 两者的处置方式不同`() {
        val groups = listOf(Group.Unchanged(listOf("a")), Group.Absent(listOf("b")))
        val text = BatchOps.summary(groups, "从歌单移除")
        assertTrue(text, text.contains("原本就那样 1 首"))
        assertTrue(text, text.contains("已不在目标里 1 首"))
    }

    // --------------------------------------------------------- 加入歌单：分组

    @Test
    fun `加歌单时把已经在里面的和要新增的分开`() {
        val pl = Playlist("pl:1", "通勤", listOf("a", "b"))
        val (fresh, already) = BatchOps.splitForPlaylistAdd(listOf("a", "c", "b", "d"), pl)
        assertEquals(listOf("c", "d"), fresh)
        assertEquals(listOf("a", "b"), already)
    }

    @Test
    fun `加歌单时选中列表里的重复 key 只算一次`() {
        val pl = Playlist("pl:1", "通勤", emptyList())
        val (fresh, already) = BatchOps.splitForPlaylistAdd(listOf("a", "a", "a"), pl)
        assertEquals(listOf("a"), fresh)
        assertTrue(already.isEmpty())
    }

    @Test
    fun `空串 key 直接扔掉 不进任何一桶`() {
        val pl = Playlist("pl:1", "通勤", listOf("a"))
        val (fresh, already) = BatchOps.splitForPlaylistAdd(listOf("", "  ", "b"), pl)
        // 选中的只有 ""、"  "、"b" 三项：空串被丢掉，b 是新的，a 根本没被选中
        assertEquals(listOf("b"), fresh)
        assertTrue(already.isEmpty())
    }

    @Test
    fun `实际新增数按去重后的结果算 不按选中数减已有数`() {
        val pl = Playlist("pl:1", "通勤", listOf("a"))
        // 选中 a、b、b：a 已存在，b 重复，按"选中-已有"会算成 2，实际只加 1
        assertEquals(1, BatchOps.addedCount(pl, listOf("a", "b", "b")))
        assertEquals(0, BatchOps.addedCount(pl, listOf("a")))
    }

    // --------------------------------------------------------- 去除封面：分组

    @Test
    fun `去封面时把能删的和本来就没有的分开`() {
        val removable = setOf("a", "c")
        val (yes, none) = BatchOps.splitForCoverRemoval(listOf("a", "b", "c", "d"), removable::contains)
        assertEquals(listOf("a", "c"), yes)
        assertEquals(listOf("b", "d"), none)
    }

    @Test
    fun `去封面的判定依据只是 App 这一层`() {
        // 内嵌封面存在与否不在这个判定里：那个删不掉，也不该在汇报里被算成"没删掉"
        val hasApp = mapOf("x" to true)
        val (yes, none) = BatchOps.splitForCoverRemoval(listOf("x", "y"), { hasApp[it] == true })
        assertEquals(listOf("x"), yes)
        assertEquals(listOf("y"), none)
    }

    // ------------------------------------------------------------ 隐藏 / 恢复

    @Test
    fun `隐藏时已经在隐藏列表里的不重复报`() {
        val hidden = setOf("a")
        val (changing, noop) = BatchOps.splitForHidden(listOf("a", "b", "c"), hidden::contains, toHidden = true)
        assertEquals(listOf("b", "c"), changing)
        assertEquals(listOf("a"), noop)
    }

    @Test
    fun `恢复时方向反过来`() {
        val hidden = setOf("a", "b")
        // a、b 现在是隐藏的，恢复它们**就是**变化；只有 c（本来就没隐藏）才不用动
        val (changing, noop) = BatchOps.splitForHidden(listOf("a", "b", "c"), hidden::contains, toHidden = false)
        assertEquals(listOf("a", "b"), changing)
        assertEquals(listOf("c"), noop)
    }

    // -------------------------------------------------------------- 选择收敛

    @Test
    fun `隐藏之后要清掉已经看不见的那些选中`() {
        val selection = setOf("a", "b", "c")
        // a、c 被隐藏掉了，只剩 b 还在列表里
        assertEquals(setOf("b"), BatchOps.pruneSelection(selection, setOf("b")))
    }

    @Test
    fun `全部都还在时不动选择`() {
        assertEquals(setOf("a", "b"), BatchOps.pruneSelection(setOf("a", "b"), setOf("a", "b", "c")))
    }

    @Test
    fun `列表空时收敛成空集 而不是保留幽灵选中`() {
        assertTrue(BatchOps.pruneSelection(setOf("a", "b"), emptySet()).isEmpty())
    }

    @Test
    fun `空选择恒等于空集合`() {
        assertTrue(BatchOps.pruneSelection(emptySet(), setOf("a")).isEmpty())
    }

    // -------------------------------------------------------------- 全选按钮

    @Test
    fun `已经全选时再点是清空`() {
        assertTrue(BatchOps.selectAllTogglesOff(selectedCount = 10, visibleCount = 10))
    }

    @Test
    fun `曲目很少时全选按钮改为清空 更顺手`() {
        // 三首以内一首首点也不费事，这个按钮当"清空"用更省一次点击
        assertTrue(BatchOps.selectAllTogglesOff(selectedCount = 2, visibleCount = 2))
    }

    @Test
    fun `曲库大且没选满时是全选`() {
        assertTrue(!BatchOps.selectAllTogglesOff(selectedCount = 3, visibleCount = 200))
    }

    @Test
    fun `列表空时全选按钮不做事`() {
        assertTrue(!BatchOps.selectAllTogglesOff(selectedCount = 0, visibleCount = 0))
    }
}
