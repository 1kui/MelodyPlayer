package com.melody.player.core

/**
 * 一条播放记录。
 *
 * 和 [HiddenSongEntry] 一样，除了稳定的 [key] 还要留一份标题/歌手快照：
 * 列表里要显示这首歌，而那时它可能已经从曲库（甚至磁盘）上消失了。
 */
data class PlayRecord(
    val key: String,
    val title: String,
    val artist: String?,
    val lastPlayedAtSec: Long,
    val playCount: Int
)

/**
 * 播放历史的增删与两个榜单的排序。
 *
 * 这里刻意是**一条记录两个字段**，而不是"最近"和"最常听"各存一份：
 * 两件事记的是同一首歌的同一段历史，拆成两份就会出现"最近里有、常听里没有"
 * 这种自相矛盾的状态。
 *
 * - 「最近播放」看 [PlayRecord.lastPlayedAtSec]，**播放即记**（划过也算）——
 *   用户找的是"我刚才听的那首"，哪怕只听了十秒。
 * - 「最常听」看 [PlayRecord.playCount]，**累计停留满 [COUNT_THRESHOLD_MS] 才 +1** ——
 *   否则一路快切会把整张曲库刷成"常听"，这个榜单就没意义了。
 */
object PlayHistory {
    /** 最多留多少条。整表存成一个 JSON 串，200 条足够覆盖"翻得到"的范围。 */
    const val MAX_RECORDS = 200

    /** 计入"常听"的停留门槛。 */
    const val COUNT_THRESHOLD_MS = 30_000L

    /**
     * 播放开始：更新时间为"现在"并提到最前，**次数不变**。
     * 已在表里则保留原有 [PlayRecord.playCount]，否则新记录从 0 开始。
     */
    fun markPlayed(records: List<PlayRecord>, song: Song, nowSec: Long): List<PlayRecord> {
        val existing = records.firstOrNull { it.key == song.key }
        val updated = PlayRecord(
            key = song.key,
            title = song.title,
            artist = song.artistOrUnknown,
            lastPlayedAtSec = nowSec,
            playCount = existing?.playCount ?: 0
        )
        return (listOf(updated) + records.filterNot { it.key == song.key }).take(MAX_RECORDS)
    }

    /**
     * 停留达标：次数 +1。
     *
     * key 不在表里时**忽略**而不是补一条 —— 能走到这里说明刚播过，
     * 记录必然存在；凭空插一条只会把"最近播放"的顺序弄乱。
     */
    fun markListened(records: List<PlayRecord>, key: String): List<PlayRecord> =
        records.map { if (it.key == key) it.copy(playCount = it.playCount + 1) else it }

    /** 是否够格计入"常听"。时长为未知（<= 0）时不因此被排除，宁可算上。 */
    fun countsAsListen(durationMs: Long): Boolean =
        durationMs <= 0L || durationMs >= COUNT_THRESHOLD_MS

    /** 最近播放：按时间从新到旧。 */
    fun recent(records: List<PlayRecord>, n: Int): List<PlayRecord> =
        records.sortedByDescending { it.lastPlayedAtSec }.take(n)

    /**
     * 最常听：次数从多到少；**次数相同时按最近播放时间**（并列时新听的排前面，
     * 否则同次数的几首歌每次刷新都在换位置）。
     * 次数为 0 的（只划过没听满）不进这个榜。
     */
    fun mostPlayed(records: List<PlayRecord>, n: Int): List<PlayRecord> =
        records.filter { it.playCount > 0 }
            .sortedWith(
                compareByDescending<PlayRecord> { it.playCount }
                    .thenByDescending { it.lastPlayedAtSec }
            )
            .take(n)

    /**
     * 把记录解析回曲目（用于"以这个榜单为队列播放"）。
     *
     * **保持记录本身的顺序**，并丢掉曲库里已经没有的那些 —— 文件被删后
     * 榜单上那条还在（有快照可以显示），但点不下去。
     */
    fun resolve(records: List<PlayRecord>, songs: List<Song>): List<Song> {
        if (records.isEmpty() || songs.isEmpty()) return emptyList()
        val byKey = songs.associateBy { it.key }
        return records.mapNotNull { byKey[it.key] }
    }
}
