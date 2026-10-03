package com.melody.player.core.online

/**
 * 一个地区的连接延迟探测结果。
 *
 * 做成密封类型而不是 `Int?`：三种"没有数字"的情形含义完全不同 —— 还没测过、
 * 正在测、连不上。用一个 null 表示全部的话，界面只能统一画一条横线，
 * 用户分不出"还没轮到"和"试过了，不通"。
 */
sealed interface RegionPing {

    /** 正在测。这是"进行中"，不是结果。 */
    data object Testing : RegionPing

    /** 通了，一次搜索请求的往返耗时。 */
    data class Ok(val ms: Long) : RegionPing

    /** 连不上：超时 / DNS 失败 / 非 2xx / 正在被接口限流冷却。 */
    data object Unreachable : RegionPing
}

/** 延迟的展示文案；`null` 表示这一项还没测过。 */
fun latencyLabel(ping: RegionPing?): String = when (ping) {
    null -> "未测速"
    RegionPing.Testing -> "测速中…"
    is RegionPing.Ok -> "${ping.ms} ms"
    RegionPing.Unreachable -> "无法连接"
}

/**
 * 把各地区拼成一行摘要，顺序**固定**为 [CoverRegion.entries]。
 *
 * 顺序不能跟着 Map 的遍历走：那会按"谁先测完"排，同一屏内容的排列每次都不一样，
 * 读数的人得一行一行重新找。地区顺序是稳定的东西，展示顺序也该稳定。
 */
fun regionPingSummary(pings: Map<String, RegionPing>): String =
    CoverRegion.entries.joinToString(" · ") { "${it.label} ${latencyLabel(pings[it.code])}" }

/**
 * iTunes 搜索用的地区（接口的 `country` 参数）。
 *
 * 为什么需要它：同一个歌名在不同地区的曲库里是**不同的条目** —— 华语老歌在港台区
 * 有录音室原版，欧美歌在美区才齐；某地区搜不到换个地区能搜到是很常见的。
 * 默认链（[DEFAULT]）就是按这个规律排的：华语优先，美区兜底。
 *
 * **刻意不含中国大陆（CN）**：中国大陆区 iTunes Store 没有音乐目录，
 * 带 `country=CN` 一律返回 `resultCount: 0`（夹具 `search_qingtian_cn.json` 就是那个
 * 42 字节的空响应）。所以这里连"可选"都不提供 —— 一个永远搜不到东西的选项，
 * 摆在设置页里只会让人以为功能坏了。
 *
 * 地区数量有上限（[MAX_SELECTED]）：每多一个地区就多一次 Search API 调用，
 * 而那个接口是有限流的（实测高频调用会被挡 403）。
 */
enum class CoverRegion(val code: String, val label: String) {
    TW("TW", "中国台湾"),
    HK("HK", "中国香港"),
    US("US", "美国"),
    JP("JP", "日本"),
    KR("KR", "韩国"),
    SG("SG", "新加坡"),
    GB("GB", "英国"),
    DE("DE", "德国");

    companion object {

        /** 默认回退链：华语优先、美区兜底。与 [ITunesApi.COUNTRIES] 是同一份数据。 */
        val DEFAULT: List<CoverRegion> = listOf(TW, HK, US)

        val DEFAULT_CODES: List<String> = DEFAULT.map { it.code }

        /**
         * 最多能同时选几个地区。
         *
         * 4 是「够用」与「别把限流额度吃光」之间的折中：一次搜索最多发 4 个请求，
         * 批量补齐几百首时才不至于慢到不可用。
         */
        const val MAX_SELECTED = 4

        /** 按 code 找地区（大小写不敏感）；不认识返回 null。 */
        fun fromCode(code: String?): CoverRegion? {
            val want = code?.trim().orEmpty()
            if (want.isEmpty()) return null
            return entries.firstOrNull { it.code.equals(want, ignoreCase = true) }
        }

        /**
         * 把存储里读到的 code 列表收敛成合法地区列表。
         *
         * 三种脏数据都要能吞下：不认识的 code（旧版本/手改）、重复项、超出上限的项。
         * 结果为空时**退回默认链**而不是留空 —— 空列表意味着「哪个地区都不搜」，
         * 那不是用户会主动想要的状态，多半是数据坏了。
         */
        fun sanitize(codes: List<String>): List<CoverRegion> {
            val out = codes.mapNotNull { fromCode(it) }.distinct().take(MAX_SELECTED)
            return out.ifEmpty { DEFAULT }
        }
    }
}
