package com.melody.player

import com.melody.player.core.online.ITunesApi
import com.melody.player.core.online.ITunesHit
import com.melody.player.core.online.TextMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iTunes 搜索接口的解析与匹配回归。
 *
 * 前四组用 **2026-09-19 实际抓取的真实响应**（`app/src/test/resources/itunes/`）。
 * 这一层的失败是**静默**的：匹配错了不会报错，只会给某首歌贴上一张别人的封面，
 * 而用户多半认不出来 —— 所以它比"能不能跑通"更值得被钉死。
 *
 * 三个真实响应各自挡一类问题：
 *  - `search_perfect_us`：同名多版本（对唱/木吉他/钢琴/大提琴改编），挡"选错版本"
 *  - `search_qingtian_tw`：华语歌 + 现场版混排，挡"给录音室版贴上演唱会封面"
 *  - `search_multi_version_us`：同分不同时长，挡"tie-break 挑错"
 *  - `search_qingtian_cn`：中国大陆区返回的空响应，挡"以为 CN 能用"
 */
class ITunesApiTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("itunes/$name")
            ?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("缺少测试夹具 itunes/$name")

    private fun perfect() = ITunesApi.parseSearch(fixture("search_perfect_us.json"))

    private fun qingtian() = ITunesApi.parseSearch(fixture("search_qingtian_tw.json"))

    private fun yesterday() = ITunesApi.parseSearch(fixture("search_multi_version_us.json"))

    private fun hit(
        trackId: Long,
        title: String,
        artist: String = "",
        album: String = "",
        artwork: String? = null,
        durationMs: Long = 0L,
        collectionId: Long = 0L
    ) = ITunesHit(
        trackId = trackId,
        collectionId = collectionId,
        title = title,
        artist = artist,
        album = album,
        artworkUrl100 = artwork,
        durationMs = durationMs,
        releaseDate = null,
        genre = null
    )

    // ------------------------------------------------------------------ 解析

    @Test
    fun `真实搜索响应解析出歌曲与其封面地址`() {
        val hits = perfect()
        assertEquals(5, hits.size)

        val first = hits[0]
        assertEquals(1193701400L, first.trackId)
        assertEquals("Perfect", first.title)
        assertEquals("Ed Sheeran", first.artist)
        assertEquals("÷ (Deluxe)", first.album)
        assertEquals(263_400L, first.durationMs)
        assertEquals("2017-03-03T08:00:00Z", first.releaseDate)
        // 接口只给 100×100；高清要靠替换尺寸段，所以这里必须留下原始地址
        assertTrue(first.artworkUrl100!!.endsWith("/100x100bb.jpg"))
    }

    @Test
    fun `华语歌的响应同样能解析出时长与专辑`() {
        val hits = qingtian()
        assertEquals(5, hits.size)
        val original = hits.first { it.trackId == 535824738L }
        assertEquals("晴天", original.title)
        assertEquals("周杰倫", original.artist)
        assertEquals("葉惠美", original.album)
        assertEquals(269_747L, original.durationMs)
    }

    @Test
    fun `大陆区返回的空响应解析成空列表`() {
        // 这不是"接口挂了"，是 CN 区真的没有音乐目录 —— 夹具就是这个 42 字节的空壳，
        // 它保证了"地区回退链不含 CN"这条规则有个可执行的证据
        assertEquals(0, ITunesApi.parseSearch(fixture("search_qingtian_cn.json")).size)
    }

    @Test
    fun `非歌曲条目被丢弃`() {
        // entity=song 时理论上只回歌曲，但 music-video 会混进来（封面是视频截图，贴上去是错的）；
        // 顺带验证缺 trackId 与缺 trackName 的脏条目不会把整批解析带崩
        val hits = ITunesApi.parseSearch(
            """
            {"resultCount":3,"results":[
              {"wrapperType":"track","kind":"music-video","trackId":1,"trackName":"Perfect",
               "artistName":"Ed Sheeran","artworkUrl100":"https://x/1.jpg/100x100bb.jpg"},
              {"wrapperType":"track","kind":"song","trackId":2,"trackName":"Perfect",
               "artistName":"Ed Sheeran","collectionId":9,"collectionName":"÷",
               "trackTimeMillis":263400,"primaryGenreName":"Pop"},
              {"wrapperType":"track","kind":"song","trackId":3,"artistName":"没歌名的脏条目"}
            ]}
            """.trimIndent()
        )
        assertEquals(1, hits.size)
        assertEquals(2L, hits[0].trackId)
        assertEquals(9L, hits[0].collectionId)
        assertEquals("Pop", hits[0].genre)
        // 没有 artworkUrl100 的条目必须给 null 而不是空串，否则调用方会去请求一个空 URL
        assertNull(hits[0].artworkUrl100)
    }

    @Test
    fun `解析不了的响应返回空列表而不抛异常`() {
        // 封面是锦上添花，一份脏响应不能把播放页搞崩
        assertEquals(0, ITunesApi.parseSearch("").size)
        assertEquals(0, ITunesApi.parseSearch("<html>503 Service Unavailable</html>").size)
        assertEquals(0, ITunesApi.parseSearch("""{"resultCount":"五"}""").size)
        assertEquals(0, ITunesApi.parseSearch("""{"results":{}}""").size)
    }

    // ------------------------------------------------------------------ 封面地址

    @Test
    fun `封面尺寸段被替换成请求的边长`() {
        val raw = "https://is1-ssl.mzstatic.com/image/thumb/Music128/v4/8/19/02/190295851286.jpg/100x100bb.jpg"
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music128/v4/8/19/02/190295851286.jpg/1000x1000bb.jpg",
            ITunesApi.artworkUrl(raw, 1000)
        )
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music128/v4/8/19/02/190295851286.jpg/600x600bb.jpg",
            ITunesApi.artworkUrl(raw, 600)
        )
        // 默认 1000
        assertTrue(ITunesApi.artworkUrl(raw)!!.endsWith("/1000x1000bb.jpg"))
    }

    @Test
    fun `尺寸段不认识时原样返回而不是猜一个地址`() {
        // 注意 `.png` **是**能处理的：这正是用正则而不是官方示例那句
        // `replace("100x100bb.jpg", …)` 的原因 —— 后者遇到非 jpg 会静默替换不到，
        // 表现为「封面能显示但特别糊」，很难查到根因
        val png = "https://is1-ssl.mzstatic.com/image/thumb/Music/v4/ab/cd.jpg/100x100bb.png"
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music/v4/ab/cd.jpg/1000x1000bb.png",
            ITunesApi.artworkUrl(png, 1000)
        )

        // 真正认不出来的两种：完全没有尺寸段、以及尺寸段后面还跟着别的东西。
        // 这时宁可返回原图（一定是能打开的）也不拼一个自己猜的地址（可能 404）
        val noSegment = "https://example.com/cover.jpg"
        assertEquals(noSegment, ITunesApi.artworkUrl(noSegment, 1000))
        val withQuery = "https://x/y.jpg/100x100bb.jpg?token=abc"
        assertEquals(withQuery, ITunesApi.artworkUrl(withQuery, 1000))
    }

    @Test
    fun `缺封面返回 null 且请求边长被夹在上限内`() {
        assertNull(ITunesApi.artworkUrl(null))
        assertNull(ITunesApi.artworkUrl("   "))
        // 实测请求 5000 也只回 3000×3000，再往上要就是白要
        val raw = "https://x/y.jpg/100x100bb.jpg"
        assertTrue(ITunesApi.artworkUrl(raw, 5000)!!.endsWith("/3000x3000bb.jpg"))
        assertTrue(ITunesApi.artworkUrl(raw, 0)!!.endsWith("/1x1bb.jpg"))
    }

    // ------------------------------------------------------------------ 查询串

    @Test
    fun `搜索查询串里的中文与空格都被编码`() {
        val query = ITunesApi.searchQuery("周杰倫 晴天", "TW")
        // 空格必须变 + ，中文必须 percent-encoding —— 不编码会直接返回 0 条
        assertTrue(query.startsWith("term=%E5%91%A8%E6%9D%B0%E5%80%AB+%E6%99%B4%E5%A4%A9"))
        assertTrue(query.contains("&media=music"))
        assertTrue(query.contains("&entity=song"))
        assertTrue(query.contains("&attribute=songTerm"))
        assertTrue(query.contains("&country=TW"))
        assertTrue(query.contains("&limit=5"))
    }

    @Test
    fun `搜索条数被夹在接口允许的范围内`() {
        // 整串比对，而不是只 endsWith("&limit=1")：参数顺序也是协议的一部分
        // （country 拼在最后），哪天有人调整顺序或漏掉某个参数，这里会立刻红
        assertEquals(
            "term=a&media=music&entity=song&attribute=songTerm&limit=1&country=TW",
            ITunesApi.searchQuery("a", "TW", limit = 0)
        )
        assertEquals(
            "term=a&media=music&entity=song&attribute=songTerm&limit=200&country=TW",
            ITunesApi.searchQuery("a", "TW", limit = 9999)
        )
    }

    @Test
    fun `地区回退链不含中国大陆且华语优先`() {
        // 这是整个功能生效的前提：country=CN 返回 resultCount 0。
        // 哪天有人"顺手把 CN 加回去"（比如为了合规），这条会红
        assertFalse("CN 区没有音乐目录，不能放进回退链", ITunesApi.COUNTRIES.contains("CN"))
        assertEquals("TW", ITunesApi.COUNTRIES.first())
        assertTrue(ITunesApi.COUNTRIES.contains("US"))
    }

    @Test
    fun `lookup 查询串按 id 精确取一条`() {
        assertEquals("id=535824738&country=TW&entity=song&limit=1", ITunesApi.lookupQuery(535824738L, "TW"))
    }

    // ------------------------------------------------------------------ 匹配

    /**
     * [ITunesApi.pickBest] 的断言版。
     *
     * 不用 `assertNotNull` + `!!`：那样失败信息是 "expected not null"，看不出是哪首歌没匹配上；
     * 也避免了在断言后面到处写 `!!`（`assertNotNull` 不做智能转换，编译器不认）。
     */
    private fun pick(
        candidates: List<ITunesHit>,
        title: String,
        artist: String?,
        durationMs: Long
    ): ITunesHit = ITunesApi.pickBest(candidates, title, artist, durationMs)
        ?: error("「$title」应当能匹配到候选，但 pickBest 返回了 null")

    @Test
    fun `同名多版本里选中专辑原版而不是对唱或改编`() {
        val best = pick(perfect(), "Perfect", "Ed Sheeran", 263_400L)
        // ÷ (Deluxe) 里的原版；对唱版、木吉他版、钢琴/大提琴改编版都不能中
        assertEquals(1193701400L, best.trackId)
        assertEquals("÷ (Deluxe)", best.album)
    }

    @Test
    fun `华语歌选中录音室版而不是现场版`() {
        val best = pick(qingtian(), "晴天", "周杰倫", 269_747L)
        // 两条 Live 版的歌名里只有"晴天 (Live)"，时长也对不上；拿到它们就是贴错封面
        assertEquals(535824738L, best.trackId)
        assertEquals("葉惠美", best.album)
        assertFalse(best.title.contains("Live"))
    }

    @Test
    fun `用户自己要的现场版不会被版本词罚下去`() {
        // 版本惩罚必须看**用户原始标题**：他要的就是 Live，扣它分就永远选不到
        val best = pick(qingtian(), "晴天 (Live)", "周杰倫", 299_360L)
        assertEquals(535913714L, best.trackId)
    }

    @Test
    fun `同分数时优先时长更接近的那一版`() {
        // The Beatles 的两条 Yesterday 分数完全相同（歌名、歌手都一致，时长都在 3.5 秒内），
        // 只能靠时长差 tie-break：125667 是 Help! 专辑版，125320 是 1 精选集里的同一录音。
        // 这条钉的是**排序方向**：写成"取差值大的"也能跑通、且永远看不出错
        val best = pick(yesterday(), "Yesterday", "The Beatles", 125_667L)
        assertEquals(1441164805L, best.trackId)
        assertEquals(125_667L, best.durationMs)
    }

    @Test
    fun `歌手未知时仍能靠时长选中原唱`() {
        // 标签里没写歌手（<unknown> → 传 null）是很常见的情况，不能因此全盘放弃
        val best = pick(perfect(), "Perfect", null, 263_400L)
        assertEquals(1193701400L, best.trackId)
    }

    @Test
    fun `同名不同歌手时靠歌手字段排除张冠李戴`() {
        // 没有歌手信息时，木吉他版（260867ms，时长更接近）会压过原版；
        // 有歌手信息时必须把非 Ed Sheeran 的改编版按下去
        val hits = perfect()
        val pianoGuys = hits.first { it.trackId == 1258279931L }
        assertTrue(ITunesApi.score(pianoGuys, "Perfect", "Ed Sheeran", 263_400L) < ITunesApi.MIN_SCORE)
        assertTrue(ITunesApi.score(pianoGuys, "Perfect", null, 0L) > ITunesApi.MIN_SCORE)
    }

    @Test
    fun `完全对不上的歌宁可不给封面`() {
        // MIN_SCORE 的意义：返回 null 用户会以为"这首歌没有封面"，
        // 返回错的用户会以为"我标签写错了"，然后去翻自己的文件 —— 后者麻烦得多
        assertNull(ITunesApi.pickBest(perfect(), "Lemon", "米津玄師", 256_000L))
        assertNull(ITunesApi.pickBest(emptyList(), "Perfect", "Ed Sheeran", 263_400L))
        assertNull(ITunesApi.pickBest(perfect(), "", "Ed Sheeran", 263_400L))
    }

    @Test
    fun `伴奏与翻唱被扣到阈值以下`() {
        // 这几类"最不想拿到"，扣分要足以压过歌名全对（100）带来的分
        val karaoke = hit(1L, "Perfect (Originally Performed by Ed Sheeran) [Karaoke Version]",
            artist = "Karaoke Band", durationMs = 263_400L)
        val cover = hit(2L, "Perfect (Cover)", artist = "Some Cover Band", durationMs = 263_400L)
        assertTrue(ITunesApi.score(karaoke, "Perfect", "Ed Sheeran", 263_400L) < ITunesApi.MIN_SCORE)
        assertTrue(ITunesApi.score(cover, "Perfect", "Ed Sheeran", 263_400L) < ITunesApi.MIN_SCORE)

        // 重制/豪华版是同一版录音，只轻微偏好，不该被罚到不可用
        val deluxe = hit(3L, "Perfect (Deluxe Edition)", artist = "Ed Sheeran", durationMs = 263_400L)
        assertTrue(ITunesApi.score(deluxe, "Perfect", "Ed Sheeran", 263_400L) >= ITunesApi.MIN_SCORE)
    }

    @Test
    fun `长歌名包含关系比逐字重合更可信`() {
        // 分数层次：完全相等 > 包含 > 逐字重合 > 无关。有人把 100 和 55 换个个儿，
        // 就会变成"越像的越选不上"。
        //
        // 候选里**刻意不放版本词**、时长也传 0（时长项不参与），
        // 这样测的就只有歌名这一档，不被现场版惩罚之类的分项干扰
        val exact = hit(1L, "晴天", artist = "周杰倫")
        val contains = hit(2L, "晴天的故事", artist = "周杰倫")
        val overlap = hit(3L, "天上晴", artist = "周杰倫")
        val unrelated = hit(4L, "稻香", artist = "周杰倫")
        val s = { h: ITunesHit -> ITunesApi.score(h, "晴天", "周杰倫", 0L) }
        assertTrue("完全相等(${s(exact)}) 应高于包含(${s(contains)})", s(exact) > s(contains))
        assertTrue("包含(${s(contains)}) 应高于逐字重合(${s(overlap)})", s(contains) > s(overlap))
        assertTrue("逐字重合(${s(overlap)}) 应高于无关(${s(unrelated)})", s(overlap) > s(unrelated))
    }

    @Test
    fun `同一张专辑的封面只认一次`() {
        val a = hit(1L, "晴天", collectionId = 535_824_738L)
        val b = hit(2L, "晴天 (Live)", collectionId = 535_824_738L)
        val c = hit(3L, "稻香", collectionId = 999L)
        val noAlbum = hit(4L, "稻香", collectionId = 0L)
        assertTrue(ITunesApi.sameArtwork(a, b))
        assertFalse(ITunesApi.sameArtwork(a, c))
        // collectionId=0 是"没解析到专辑"，不能让两首不同的歌互相认成同一张
        assertFalse(ITunesApi.sameArtwork(noAlbum, noAlbum))
        assertFalse(ITunesApi.sameArtwork(a, null))
        assertFalse(ITunesApi.sameArtwork(null, b))
    }

    @Test
    fun `搜索关键词剥掉版本后缀后仍能与接口返回匹配`() {
        // 歌名里带 (Live)/(Remastered)/feat. 时，搜索引擎和打分器必须对"主标题"
        // 有同一个理解，否则会出现"能搜到、却匹配不上"的怪现象
        assertEquals("晴天 周杰倫", TextMatch.searchKeyword("晴天 (Live)", "周杰倫"))
        assertEquals("Perfect Ed Sheeran", TextMatch.searchKeyword("Perfect (Acoustic)", "Ed Sheeran"))
        assertEquals("晴天", TextMatch.searchKeyword("晴天", "未知歌手"))
        // 剥完什么都不剩的歌名不能拿去搜（会搜出一堆无关结果）
        assertEquals("", TextMatch.searchKeyword("(Live)", "周杰倫"))
    }
}
