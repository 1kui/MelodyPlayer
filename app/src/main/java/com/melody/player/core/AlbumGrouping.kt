package com.melody.player.core

/**
 * 一张专辑（按 `专辑名 + 歌手` 聚合出来的一批曲目）。
 *
 * 为什么要带上歌手：不同歌手可能有同名专辑（"Greatest Hits" 满天飞），
 * 只按专辑名聚合会把它们并成一张，点进去发现作者都换了人。
 *
 * 代价是**合辑会被拆开**（一张合辑里每首歌的 artist 不同）——
 * 修它需要 `albumArtist` 标签，而 [Song] 里没有这个字段，补它要回头改
 * `core/tags` 的解析。这是一处**已知的降级**，换来的是不动标签解析层。
 */
data class AlbumGroup(val album: String, val artist: String, val songs: List<Song>) {
    /** 聚合键。用 `\u0000` 分隔：专辑名里可能出现任何可打印字符，包括 `|` 和 `-`。 */
    val key: String get() = "$album\u0000$artist"

    val count: Int get() = songs.size

    val durationMs: Long get() = songs.sumOf { it.durationMs }

    /** 封面占位用的稳定色调索引，取组内第一首的即可（同专辑的歌算出来本来就一样）。 */
    val coverSeed: Int get() = songs.first().artworkSeed
}

/** 一位歌手聚合出来的一批曲目。 */
data class ArtistGroup(val artist: String, val songs: List<Song>) {
    val key: String get() = artist

    val count: Int get() = songs.size

    val durationMs: Long get() = songs.sumOf { it.durationMs }
}

/**
 * 曲库的专辑 / 歌手维度。
 *
 * 纯函数，无 Android 依赖，能在 JVM 单测里直接跑 —— 聚合错一个 key 的后果
 * 是用户看到两张本该是一张的专辑，或者一首歌从专辑里凭空消失。
 */
object Albums {

    /**
     * 按 `(专辑名, 歌手)` 归并。
     *
     * 组内**保持传入顺序**（曲库那层已经按用户选的排序排好了，这里再排一次
     * 就是两套顺序打架）；组间按专辑名、歌手名排序，**未知专辑沉到最后**。
     */
    fun group(songs: List<Song>): List<AlbumGroup> {
        if (songs.isEmpty()) return emptyList()
        val buckets = LinkedHashMap<String, MutableList<Song>>()
        songs.forEach { song ->
            buckets.getOrPut(AlbumGroup(song.albumOrUnknown, song.artistOrUnknown, emptyList()).key) {
                mutableListOf()
            }.add(song)
        }
        return buckets.map { (key, list) ->
            val first = list.first()
            AlbumGroup(album = first.albumOrUnknown, artist = first.artistOrUnknown, songs = list)
        }.sortedWith(
            compareBy<AlbumGroup> { it.album == UNKNOWN_ALBUM }
                .thenBy { it.album.lowercase() }
                .thenBy { it.artist == UNKNOWN_ARTIST }
                .thenBy { it.artist.lowercase() }
        )
    }

    private const val UNKNOWN_ALBUM = "未知专辑"
    private const val UNKNOWN_ARTIST = "未知歌手"
}

object Artists {

    /** 按歌手归并；组内保持传入顺序，组间按名字排序，未知歌手沉底。 */
    fun group(songs: List<Song>): List<ArtistGroup> {
        if (songs.isEmpty()) return emptyList()
        val buckets = LinkedHashMap<String, MutableList<Song>>()
        songs.forEach { song ->
            buckets.getOrPut(song.artistOrUnknown) { mutableListOf() }.add(song)
        }
        return buckets.map { (artist, list) -> ArtistGroup(artist = artist, songs = list) }
            .sortedWith(
                compareBy<ArtistGroup> { it.artist == "未知歌手" }
                    .thenBy { it.artist.lowercase() }
            )
    }
}
