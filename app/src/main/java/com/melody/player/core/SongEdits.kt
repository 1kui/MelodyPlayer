package com.melody.player.core

/**
 * 一条「用户改过的歌曲信息」记录：新值 + 当时的原值。
 *
 * **原值必须一起存**：曲库里的 [Song] 是不可变的扫描结果，改过之后内存里那条
 * 已经是新值了，「恢复原始信息」如果只删记录、手里却没有原值，就回不去了。
 * 把原值随记录一起落盘，恢复就是一个纯函数的事。
 *
 * 字段语义：
 *  - [title] / [artist] / [album] 是**编辑后的值**（title 保证非空；歌手/专辑可以
 *    为空串，界面上按「未知歌手 / 未知专辑」显示）；
 *  - [originalTitle] / [originalArtist] / [originalAlbum] 是**编辑前的值**，仅供恢复。
 *
 * 记录本身不带歌曲 key —— 存储按 [Song.key] 索引（见 [SongEdits]），key 天然是 map 的键。
 */
data class SongEdit(
    val title: String,
    val artist: String?,
    val album: String?,
    val originalTitle: String,
    val originalArtist: String?,
    val originalAlbum: String?
) {
    /** 用编辑值覆盖 [song]。歌手/专辑空串按 null 存，与扫描结果的「无此标签」同形。 */
    fun appliedTo(song: Song): Song = song.copy(
        title = title,
        artist = artist?.takeIf { it.isNotBlank() },
        album = album?.takeIf { it.isNotBlank() }
    )

    /** 用原值把 [song] 还原回编辑前的样子。 */
    fun restored(song: Song): Song = song.copy(
        title = originalTitle,
        artist = originalArtist,
        album = originalAlbum
    )

    companion object {
        /** 为 [song] 建一条编辑记录。 */
        fun of(song: Song, title: String, artist: String, album: String): SongEdit = SongEdit(
            title = title.trim(),
            artist = artist.trim(),
            album = album.trim(),
            originalTitle = song.title,
            originalArtist = song.artist,
            originalAlbum = song.album
        )
    }
}

/**
 * 歌曲信息编辑的集合操作。
 *
 * 抽成纯函数是为了单测：改完之后搜索/排序/歌词匹配都吃**新值**，而 [Song.key]
 * 保持不变 —— 封面、歌词、归档记录全挂在 key 上，改名不能把这些弄丢。
 */
object SongEdits {

    /**
     * 把编辑记录套到曲库上。没有记录的曲目原样返回（同一个对象），
     * 一次改名不会让两千首的列表全部换新对象。
     */
    fun apply(songs: List<Song>, edits: Map<String, SongEdit>): List<Song> {
        if (edits.isEmpty()) return songs
        return songs.map { song -> edits[song.key]?.appliedTo(song) ?: song }
    }

    /** 撤掉某一首的编辑记录（恢复原值时配合 [SongEdit.restored] 用）。 */
    fun without(edits: Map<String, SongEdit>, key: String): Map<String, SongEdit> = edits - key
}
