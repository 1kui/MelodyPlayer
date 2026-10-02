package com.melody.player.core

/**
 * 批量操作的分组与结果汇报。
 *
 * 抽成纯函数是因为这一层最需要单测：批量最典型的失败方式不是崩溃，而是
 * **"部分成功但界面只报一句『完成』"** —— 用户以为全好了，回头发现还有几首没进去，
 * 也无从知道是哪几首、为什么。所以这里的规矩是：
 *
 *  - 每首歌的结局必须能落到 [Group] 里的某一桶，不允许"不知道去哪了"；
 *  - [summary] 生成的文案只在**有东西没做成**时才提它，成功时不啰嗦；
 *  - 汇报条数**永远**等于处理条数，把两者对不上的路径（比如遍历时抛异常）留给调用方，
 *    但纯函数这一层不允许出现丢项。
 */
object BatchOps {

    /**
     * 一批歌在同一件事上的结局。
     *
     * 刻意用 sealed 而不是"成功列表 + 失败列表"：多一种结局就多一种可能漏报的写法，
     * 编译器会逼着每个 `when` 补齐，比两个 List 互相漏判安全。
     */
    sealed interface Group {
        val keys: List<String>

        /** 本来就已经在目标里（加歌单）或本来就没有封面（去封面）—— 不算失败，也不用重试。 */
        data class Unchanged(override val keys: List<String>) : Group

        /** 这次真的做成了。 */
        data class Done(override val keys: List<String>) : Group

        /** 目标里已经没有它了（批量从歌单移除时曲目被别处同步走了）。 */
        data class Absent(override val keys: List<String>) : Group

        /** 目标冲突：加歌单时撞名、匹配门槛太高等，用户改个条件就能重试。 */
        data class Rejected(override val keys: List<String>, val reason: String) : Group

        /** 真失败：写盘失败、SAF 授权失效、网络错误等。 */
        data class Failed(override val keys: List<String>, val reason: String) : Group
    }

    /**
     * 把逐首结局汇总成一条中文文案。
     *
     * @param noun 事情的名字，比如「加入歌单」「去除封面」，出现在句子里。
     * @param unit 复数时的量词，用于失败清单，如「首」「张」。默认「首」。
     */
    fun summary(groups: List<Group>, noun: String, unit: String = "首"): String {
        val done = groups.filterIsInstance<Group.Done>()
            .sumOf { it.keys.size }
        val unchanged = groups.filterIsInstance<Group.Unchanged>()
            .sumOf { it.keys.size }
        val absent = groups.filterIsInstance<Group.Absent>()
            .sumOf { it.keys.size }
        val rejected = groups.filterIsInstance<Group.Rejected>()
        val failed = groups.filterIsInstance<Group.Failed>()

        // 主句只说真正动过的那些；一个都没动就直说，别让用户以为白按了一次
        val head = when {
            done == 0 && unchanged == 0 && absent == 0 && rejected.isEmpty() && failed.isEmpty() ->
                "没有可处理的曲目"
            done == 0 -> "没有改动"
            else -> "已${noun} $done $unit"
        }

        val tail = buildList {
            if (unchanged > 0) add("原本就那样 $unchanged $unit")
            if (absent > 0) add("已不在目标里 $absent $unit")
            rejected.forEach { add("被拒绝 ${it.keys.size} $unit（${it.reason}）") }
            failed.forEach { add("失败 ${it.keys.size} $unit（${it.reason}）") }
        }
        return if (tail.isEmpty()) head else "$head；${tail.joinToString("，")}"
    }

    /**
     * 汇总的 sanity check：各桶的条数加总必须等于处理条数。
     *
     * 这是"不许丢项"这条规矩的可执行版本。批量处理里最隐蔽的 bug 就是某条分支
     * 忘了归类，导致 [summary] 报的数字比实际少几首 —— 而界面不会报错，用户只能自己发现。
     * 断言它比事后查 bug 便宜得多。
     */
    fun accountedCount(groups: List<Group>): Int =
        groups.sumOf { it.keys.size }

    /**
     * 批量「加入歌单」：按"哪些已经在这个歌单里"把选中项分两桶。
     *
     * 重复的归 [Group.Unchanged] 而不是跳过不提 —— 用户选了 10 首、只有 3 首是新加的，
     * "已加入 3 首，原本就在里面 7 首"这句话他要能直接看到。
     */
    fun splitForPlaylistAdd(
        selected: List<String>,
        playlist: Playlist
    ): Pair<List<String>, List<String>> {
        if (selected.isEmpty()) return emptyList<String>() to emptyList()
        val present = playlist.songKeys.toHashSet()
        val fresh = LinkedHashSet<String>()
        val already = LinkedHashSet<String>()
        selected.forEach { key ->
            if (key.isBlank()) return@forEach
            if (key in present) already.add(key) else fresh.add(key)
        }
        return fresh.toList() to already.toList()
    }

    /**
     * 批量「加入歌单」加完之后实际会新增几首。
     *
     * 单独给一个是因为 [Playlist.addSongs] 内部还会去重（用户可能在同一批里
     * 塞进两个一样的 key），只信"选中数 - 已在里面数"会多算。
     */
    fun addedCount(playlist: Playlist, selected: List<String>): Int {
        val before = playlist.songKeys.size
        val after = Playlists.addSongs(playlist, selected).songKeys.size
        return (after - before).coerceAtLeast(0)
    }

    /**
     * 批量「去除封面」：分出"本来就没有 App 封面"和"真的有、这次删掉了"。
     *
     * @param hasAppCover 这首歌是否有 App 缓存或自定义封面（用 [hasAppCoverFor] 逐首判断）。
     *   注意这里的语义**只是 App 这一层**：音频文件内嵌的封面删不掉，也不会被删。
     */
    fun splitForCoverRemoval(
        selected: List<String>,
        hasAppCover: (String) -> Boolean
    ): Pair<List<String>, List<String>> {
        val removable = LinkedHashSet<String>()
        val nothing = LinkedHashSet<String>()
        selected.forEach { key ->
            if (key.isBlank()) return@forEach
            if (hasAppCover(key)) removable.add(key) else nothing.add(key)
        }
        return removable.toList() to nothing.toList()
    }

    /**
     * 批量「隐藏 / 恢复」的预检：已经在那个状态里的不该重复报成功。
     *
     * @param currentState 这首歌当前的隐藏状态。
     * @param toHidden 目标状态：true = 隐藏，false = 恢复。
     */
    fun splitForHidden(
        selected: List<String>,
        currentState: (String) -> Boolean,
        toHidden: Boolean
    ): Pair<List<String>, List<String>> {
        val changing = LinkedHashSet<String>()
        val noop = LinkedHashSet<String>()
        selected.forEach { key ->
            if (key.isBlank()) return@forEach
            if (currentState(key) == toHidden) noop.add(key) else changing.add(key)
        }
        return changing.toList() to noop.toList()
    }

    /**
     * 批量处理前的选择收敛。
     *
     * 一次批量动作之后，用户选中的曲目可能有一部分已经不该再被选中：
     * 隐藏之后它在列表里没了，从歌单移除之后它不在当前视图里了。
     * 不清掉的话，界面上的"已选 5 首"和操作条上真正能作用的对象就对不上，
     * 再点一次批量操作就会作用在看不见的曲目上。
     *
     * @param stillVisible 这些 key 在当前视图里还在（一般由界面按"当前渲染的列表"给出）。
     */
    fun pruneSelection(selection: Set<String>, stillVisible: Set<String>): Set<String> =
        if (selection.isEmpty()) emptySet()
        else selection.intersect(stillVisible)

    /**
     * 「全选」要不要把"已选中的都取消"（点一下全清）。
     *
     * 行为随曲库规模变：几百首时用户是想全选，一首两首时全选按钮就是多余动作。
     * 阈值取得比较小，因为这个按钮的意义就是"我不想一首首点"。
     */
    fun selectAllTogglesOff(selectedCount: Int, visibleCount: Int): Boolean {
        if (visibleCount <= 0) return false
        return selectedCount >= visibleCount || visibleCount <= 3
    }
}
