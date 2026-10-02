package com.melody.player.core

/**
 * 一个用户自建歌单。
 *
 * 刻意只存 [songKeys]（[Song.key]）而不存歌曲快照：
 *  - 歌名/歌手/专辑在 App 内可以被改（见 `SongEdits`），存快照就会让歌单里
 *    永远显示改之前的那份，与曲库对不上；
 *  - 文件被删、被移出 App 库，歌单里那一项就该自动消失，而不是留一条点不开的记录。
 *
 * **顺序即播放顺序**：歌单不是"一堆歌的集合"，用户手动排的次序就是他想要的听法，
 * 所以这里是 [List] 而不是 [Set]，也不额外存一个 sort 字段。
 */
data class Playlist(
    /** 稳定标识（`pl:…`）。改名不影响它，歌单里所有曲目的指向都靠它。 */
    val id: String,
    val name: String,
    val songKeys: List<String> = emptyList(),
    val createdAtSec: Long = 0L
)

/**
 * 歌单的增删改排。抽成纯函数，因为这里全是集合边界，出错的后果都很直观：
 * 丢歌、重复、顺序乱、或者歌单删了曲目的隐藏状态被误清。
 */
object Playlists {

    /** 歌单名最长多少个字符。 */
    const val MAX_NAME_LENGTH = 24

    /** 名字去空白、折叠中间多余空格、截断。空名字在这里就该判死。 */
    fun normalizeName(raw: String): String =
        raw.trim().replace(Regex("\\s+"), " ").take(MAX_NAME_LENGTH)

    /** 生成不重名的歌单名：撞了就加 ` (2)`、` (3)`。 */
    fun uniqueName(desired: String, taken: Set<String>): String {
        val base = normalizeName(desired).ifBlank { "新建歌单" }
        if (base !in taken) return base
        var i = 2
        while ("$base ($i)" in taken) i++
        return "$base ($i)"
    }

    /** 歌单名是否可用（空名 / 与别的歌单重名都不行）。新建与改名共用这一条判断。 */
    fun isNameAvailable(desired: String, taken: Set<String>): Boolean {
        val name = normalizeName(desired)
        return name.isNotBlank() && name !in taken
    }

    /** 生成不撞 id 的标识。同秒内建两个同名歌单也不会互相覆盖。 */
    fun uniqueId(desired: String, taken: Set<String>): String {
        if (desired !in taken) return desired
        var i = 2
        while ("$desired-$i" in taken) i++
        return "$desired-$i"
    }

    /**
     * 新建歌单，**排在最前**。
     *
     * 新建的东西要立刻看得见：排在最后的话，用户点完「新建」还得横向滚一段
     * 才能找到自己刚建的那个，会以为没建成功。
     */
    fun create(
        playlists: List<Playlist>,
        name: String,
        id: String,
        nowSec: Long
    ): List<Playlist> {
        val takenNames = playlists.mapTo(HashSet()) { it.name }
        val takenIds = playlists.mapTo(HashSet()) { it.id }
        val playlist = Playlist(
            id = uniqueId(id, takenIds),
            name = uniqueName(name, takenNames),
            songKeys = emptyList(),
            createdAtSec = nowSec
        )
        return listOf(playlist) + playlists
    }

    fun find(playlists: List<Playlist>, id: String?): Playlist? =
        if (id == null) null else playlists.firstOrNull { it.id == id }

    /** 改名。空名或与别的歌单重名时不动原名（返回原样列表），由界面提示用户。 */
    fun rename(playlists: List<Playlist>, id: String, rawName: String): List<Playlist> {
        val name = normalizeName(rawName)
        if (name.isBlank()) return playlists
        val conflict = playlists.any { it.id != id && it.name == name }
        if (conflict) return playlists
        return playlists.map { if (it.id == id) it.copy(name = name) else it }
    }

    fun delete(playlists: List<Playlist>, id: String): List<Playlist> =
        playlists.filterNot { it.id == id }

    /**
     * 往歌单里加歌。
     *
     * 已在歌单里的直接跳过（不是移到末尾）：用户从曲库批量勾选时很容易把
     * 已有的那几首也选上，让它们跳到队尾会让"再加两首"变成"重排整个歌单"。
     *
     * [keys] 内部也要去重：批量选择会一次性传进来一串 key，其中出现重复是很正常的
     * （选中的东西已经被别处加过一次、或上层两次拼进同一首）。不去重的话歌单里
     * 会出现同一个 key 好几行 —— 界面看着是同一首歌重复几次，播放队列也会重复播。
     */
    fun addSongs(playlist: Playlist, keys: List<String>): Playlist {
        if (keys.isEmpty()) return playlist
        val seen = playlist.songKeys.toMutableSet()
        val fresh = LinkedHashSet<String>()
        keys.forEach { key ->
            if (key.isBlank()) return@forEach
            if (seen.add(key)) fresh.add(key)
        }
        if (fresh.isEmpty()) return playlist
        return playlist.copy(songKeys = playlist.songKeys + fresh.toList())
    }

    fun removeSong(playlist: Playlist, key: String): Playlist =
        playlist.copy(songKeys = playlist.songKeys.filterNot { it == key })

    /** 上移一位。已经在第一位则原样返回（返回同一实例，调用方可以据此判断"没动"）。 */
    fun moveUp(playlist: Playlist, index: Int): Playlist {
        val target = index - 1
        if (index !in playlist.songKeys.indices || target < 0) return playlist
        return playlist.copy(songKeys = playlist.songKeys.toMutableList().apply {
            val tmp = this[index]
            this[index] = this[target]
            this[target] = tmp
        })
    }

    /** 下移一位。最后一位或越界时原样返回。 */
    fun moveDown(playlist: Playlist, index: Int): Playlist {
        val target = index + 1
        if (index !in playlist.songKeys.indices || target !in playlist.songKeys.indices) return playlist
        return playlist.copy(songKeys = playlist.songKeys.toMutableList().apply {
            val tmp = this[index]
            this[index] = this[target]
            this[target] = tmp
        })
    }

    /**
     * 把歌单解析成曲库里的真实曲目，**保持歌单里的顺序**。
     *
     * 查不到的 key 直接丢掉而不是留占位：文件被删了，歌单里那一行点开就是
     * "无法播放"，用户还得自己去猜是哪首没了。想知道的话由 [orphans] 单独给出。
     */
    fun resolve(playlist: Playlist, library: List<Song>): List<Song> {
        if (playlist.songKeys.isEmpty() || library.isEmpty()) return emptyList()
        val byKey = library.associateBy { it.key }
        return playlist.songKeys.mapNotNull { byKey[it] }
    }

    /** 歌单里已经不在曲库（文件被删、被移出 App 库）的条目。 */
    fun orphans(playlist: Playlist, library: List<Song>): List<String> {
        if (playlist.songKeys.isEmpty() || library.isEmpty()) return playlist.songKeys
        val present = library.mapTo(HashSet()) { it.key }
        return playlist.songKeys.filterNot { it in present }
    }

    /** 曲库里还没被任何歌单收录的曲目数（设置页提示用）。 */
    fun unlistedCount(playlists: List<Playlist>, library: List<Song>): Int {
        if (library.isEmpty()) return 0
        val listed = playlists.flatMapTo(HashSet()) { it.songKeys }
        return library.count { it.key !in listed }
    }
}