package com.melody.player.ui.player

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.melody.player.core.ArchivedEntry
import com.melody.player.core.ArchivedSongs
import com.melody.player.core.ArtworkShape
import com.melody.player.core.BatchOps
import com.melody.player.core.BrandArtwork
import com.melody.player.core.HiddenSongs
import com.melody.player.core.LyricCopyEntry
import com.melody.player.core.LyricCopyGroup
import com.melody.player.core.LyricCopyGroups
import com.melody.player.core.LyricCopyKeys
import com.melody.player.core.LyricOrigin
import com.melody.player.core.LyricTextSize
import com.melody.player.core.Lyrics
import com.melody.player.core.PlayMode
import com.melody.player.core.Playlist
import com.melody.player.core.Playlists
import com.melody.player.core.Song
import com.melody.player.core.SongEdit
import com.melody.player.core.SongEdits
import com.melody.player.core.SongQuery
import com.melody.player.core.SortMode
import com.melody.player.core.kwm.KwmFile
import com.melody.player.core.kwm.KwmFiles
import com.melody.player.core.online.CoverRegion
import com.melody.player.core.online.ITunesApi
import com.melody.player.core.online.ITunesHit
import com.melody.player.core.online.LyricProvider
import com.melody.player.core.online.OnlineSong
import com.melody.player.core.online.TextMatch
import com.melody.player.data.AlbumArt
import com.melody.player.data.ArchivedLibrary
import com.melody.player.data.AudioLibrary
import com.melody.player.data.CoverResult
import com.melody.player.data.CoverStage
import com.melody.player.data.EmbeddedArtworkCache
import com.melody.player.data.KwmImporter
import com.melody.player.data.KwmScanner
import com.melody.player.data.LyricsRepository
import com.melody.player.data.LyricsStore
import com.melody.player.data.Prefs
import com.melody.player.data.TagEmbedder
import com.melody.player.playback.PlaybackService
import com.melody.player.ui.components.notifyCoverChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放器唯一的业务入口。
 *
 * 控制器连接策略：先用 SessionToken 把 MediaController 与 [PlaybackService] 绑定（异步），
 * 期间用户的操作会先挂进 [pendingCommand]，连上后立刻补执行 ——
 * 这样就不会出现「首屏点了歌却没反应」这种异步竞态问题。
 */
class PlayerViewModel(private val app: Application) : ViewModel() {

    private val prefs = Prefs(app)
    private val library = AudioLibrary(app)
    private val lyricsStore = LyricsStore(app)
    private val archived = ArchivedLibrary(app)
    private val lyricsRepository = LyricsRepository(app, prefs)
    private val kwmScanner = KwmScanner(app)
    private val kwmImporter = KwmImporter(app, archived, prefs)
    private val covers = AlbumArt.of(app)
    private val embedder = TagEmbedder(app)
private val embeddedArt = EmbeddedArtworkCache.of(app)

    /** 用户改过的歌曲信息（歌名/歌手/专辑），内存留一份，改名直接改这条。 */
    private var songEdits: Map<String, SongEdit> = prefs.songEdits

    private val _state = MutableStateFlow(
        PlayerUiState(
            sort = prefs.sortMode,
            hiddenSongs = prefs.hiddenSongs,
            playMode = prefs.playMode,
            autoFetchLyrics = prefs.autoFetchLyrics,
            lyricProviders = prefs.lyricProviders,
            autoFetchCovers = prefs.autoFetchCovers,
            coverMinScore = prefs.coverMinScore,
            coverRegionCustom = prefs.coverRegionCustom,
            coverRegions = prefs.coverRegions,
            swipeSwitchSong = prefs.swipeSwitchSong,
            lyricTextSize = prefs.lyricTextSize,
            artworkShape = prefs.artworkShape,
            coverCount = covers.stats().first,
            coverBytes = covers.stats().second,
            permissionGranted = library.hasAudioPermission(),
            kwmFolderName = prefs.kwmFolderName,
            playlists = prefs.playlists,
            libraryFolderOnly = prefs.libraryFolderOnly && prefs.libraryFolderUri != null,
            libraryFolderName = prefs.libraryFolderName
        )
    )
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /**
     * 本次会话里已经尝试过自动联网匹配的曲目。
     *
     * 自动匹配失败（比如没网）时不该每换一次歌就重试一遍 —— 那会在弱网下变成
     * 反复超时的卡顿。记住「试过了」，用户想重来可以手动点「联网获取歌词」。
     */
    private val autoFetchTried = mutableSetOf<String>()

    /** 归档是否被用户取消（一键归档中途停下，不留半成品）。 */
    @Volatile
    private var archiveCancelled = false

    /** KWM 批量解密是否被用户取消。 */
    @Volatile
    private var kwmCancelled = false

    /** 封面批量补齐是否被用户取消。 */
    @Volatile
    private var coverCancelled = false

    /** 正在取封面的曲目，避免同一首被并发拉两次（点两次菜单、或自动与手动撞上）。 */
    private val coverJobs = mutableMapOf<String, Job>()

    /**
     * 扫描到的全量曲目（含被隐藏的）。
     *
     * 不放进 [PlayerUiState]：界面永远只该看到过滤后的列表，留着全量只是为了让
     * 「恢复隐藏」不必重新扫一遍媒体库。
     */
    private var allSongs: List<Song> = emptyList()

    /**
     * 已被 App 库副本取代的来源 key。
     *
     * 与「隐藏」严格区分：隐藏是**用户**不想看到，会进设置里的恢复列表；
     * 这里只是「这份文件已经有 App 自己的副本了，原曲不必再占一行」，
     * 副本一旦不在（被删/目录读不到）就自动失效，原曲立刻回到列表。
     */
    private var supersededKeys: Set<String> = emptySet()

    /**
     * 已经交给播放器的曲目，按 key 记一份。
     *
     * 有了它，[deriveQueue] 才能在「曲库还没扫出来」的那一瞬间也认得出队列里的每一首；
     * 没有它的话，冷启动到首次扫描完成之间，队列会被推成 null 而退化成空列表。
     */
    private val songCache = LinkedHashMap<String, Song>()

    /** 播放进度单独一条流，250ms 更新一次，只有进度条会因此重组。 */
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    /** 一次性提示（Snackbar）。 */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var controller: MediaController? = null
    private var controllerPending = false
    private var pendingCommand: ((MediaController) -> Unit)? = null
    private var lyricsJob: Job? = null

    /**
     * 界面上生效的播放模式（App 侧记录的唯一事实源）。
     *
     * 不能再从 `repeatMode` 反推：顺序播放和列表循环现在都映射到同一个
     * `REPEAT_MODE_ALL`（见 [applyPlayMode] 的注释），反推会把顺序播放显示成列表循环。
     * 播放模式只有本 App 会改，所以记在内存 + Prefs 里足够。
     */
    private var appPlayMode: PlayMode = prefs.playMode

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncFromController()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // 换曲一律把进度归零。
            //
            // 为什么必须在这里做：进度唯一的更新源是下面那个轮询循环，而它认的是
            // `isPlaying`。切歌后新曲会先进 STATE_BUFFERING，这段时间 isPlaying=false，
            // 轮询一次都不写值 —— 上一首的位置就会一直挂在进度条上（用户报的
            // 「播到 2:26 上下滑切下一首，进度条还在 2:26」）。归零是唯一不依赖
            // 「播放器什么时候才算就绪」的写法。单曲循环（reason=REPEAT，key 没变）
            // 也走这里，所以放在回调里而不是 syncFromController 的 key 变化分支里。
            _position.value = 0L
            syncFromController()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncFromController()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            syncFromController()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            syncFromController()
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.update { it.copy(playbackError = error.message ?: error.errorCodeName) }
            _messages.tryEmit("播放失败：${error.errorCodeName}")
            syncFromController()
        }
    }

    init {
        // 进度轮询：播放中与缓冲中都跟，只有真正暂停时才保留最后一帧位置。
        //
        // 缓冲期必须一起跟：切歌/seek 之后会先进 STATE_BUFFERING，新曲在这段时间
        // 报出来的位置已经有效（通常就是 0），旧条件只认 isPlaying 会让进度条
        // 冻在上一首的值上。
        viewModelScope.launch {
            while (isActive) {
                val c = controller
                if (c != null && (c.isPlaying || c.playbackState == Player.STATE_BUFFERING)) {
                    _position.value = c.currentPosition.coerceAtLeast(0L)
                }
                delay(POSITION_POLL_MS)
            }
        }
        connectController()
    }

    // ------------------------------------------------------------------ 控制器

    private fun connectController() {
        if (controller != null || controllerPending) return
        controllerPending = true
        val context = app.applicationContext
        // 先把服务拉起来，保证 buildAsync 一次就能连上（避免双分支）
        runCatching { context.startService(PlaybackService.intent(context)) }

        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val connected = runCatching { future.get() }.getOrNull()
            controllerPending = false
            if (connected == null) {
                _messages.tryEmit("播放服务连接失败，请重试")
                return@addListener
            }
            controller = connected
            connected.addListener(playerListener)
            applyPersistedPlaybackModes(connected)
            pendingCommand?.invoke(connected)
            pendingCommand = null
            restoreQueueIfIdle()
            syncFromController()
        }, androidx.core.content.ContextCompat.getMainExecutor(context))
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) {
            block(c)
        } else {
            pendingCommand = block
            connectController()
        }
    }

    private fun applyPersistedPlaybackModes(c: MediaController) {
        appPlayMode = prefs.playMode
        applyPlayMode(c, prefs.playMode)
    }

    /**
     * 把界面上的单一播放模式翻译成 ExoPlayer 的两个底层开关。
     *
     * 随机播放**必须**同时打开列表循环：ExoPlayer 的 `shuffleModeEnabled` 只负责打乱
     * 顺序，放完最后一条仍然会停。用户按下「随机播放」期待的是「一直随机放下去」，
     * 这与循环不循环是两件事 —— 所以这里把二者绑在一起，界面侧再也不用管这个细节。
     *
     * 顺序播放**也**映射成 `REPEAT_MODE_ALL`：Media3 只有在「存在下一个窗口」时才认为
     * 下一首可用 —— 顺序播放的原生映射是 `REPEAT_MODE_OFF`，队列走到最后一首时
     * `hasNextMediaItem` 变 false，通知栏的「下一首」按钮会被系统直接撤掉、
     * App 内的下一首/上滑切歌也全部变成空操作，看起来就像切歌功能坏了。
     * 改成 ALL 之后 seek 到队尾自动回绕到第一首，「切歌永远可用」这个保证
     * 就不再依赖当前播放到第几首。代价是顺序播放放完一整圈会从第一首继续 ——
     * 与列表循环的差别只剩界面图标，行为上不再有「放完就停」。
     */
    private fun applyPlayMode(c: MediaController, mode: PlayMode) {
        c.repeatMode = when (mode) {
            PlayMode.SEQUENTIAL -> Player.REPEAT_MODE_ALL
            PlayMode.LIST_LOOP -> Player.REPEAT_MODE_ALL
            PlayMode.SINGLE_LOOP -> Player.REPEAT_MODE_ONE
            PlayMode.SHUFFLE -> Player.REPEAT_MODE_ALL
        }
        c.shuffleModeEnabled = mode == PlayMode.SHUFFLE
    }

    /**
     * 冷启动时把上次的队列恢复出来（不自动播放）。
     * 这样迷你播放条一进来就有内容，符合用户对音乐 App 的预期。
     */
    private fun restoreQueueIfIdle() {
        val c = controller ?: return
        if (c.mediaItemCount > 0) return
        val songs = _state.value.songs
        if (songs.isEmpty()) return
        val index = songs.indexOfFirst { it.key == prefs.lastSongKey }.takeIf { it >= 0 } ?: 0
        val song = songs[index]
        songs.forEach { songCache[it.key] = it }
        c.setMediaItems(songs.map(::mediaItemOf), index, 0L)
        c.prepare()
        _state.update {
            it.copy(
                queue = songs,
                currentIndex = index,
                currentSong = song,
                durationMs = song.durationMs,
                isPlaying = false
            )
        }
        loadLyrics(song, song.durationMs)
        // 冷启动恢复的这首也要有封面，否则迷你播放条上只有渐变块
        ensureCover(song)
    }

    /**
     * 从控制器时间线反推出队列（null = 推不出来，沿用旧值）。
     *
     * 队列**不再由 App 自己维护一份**。以前两边各记一份、靠事件对齐，出了两类说不通的现象：
     *
     *  1. 随机播放时，播放器放的是打乱后的顺序，界面列的却是原始顺序 ——
     *     「正在播放」高亮的那一行和实际听到的那首对不上；
     *  2. 从队列里删掉当前曲目前面的某一首，播放器下标已经往前挪了，界面还停在旧下标上，
     *     高亮整个错位，要等下一次切歌才自己对上。
     *
     * 控制器的 `mediaId` 就是 [Song.key]（见 `mediaItemOf`），所以反推是可靠的。
     * 只有一条 id 认不出来（曲库还没扫出来、或刚被删）才放弃推导 —— 那时宁可保留
     * 旧列表，也不要塞一个点开就播不了的假条目进去。
     */
    private fun deriveQueue(c: MediaController): List<Song>? {
        val lookup = songLookup()
        val out = ArrayList<Song>(c.mediaItemCount)
        for (i in 0 until c.mediaItemCount) {
            val song = lookup[c.getMediaItemAt(i).mediaId] ?: return null
            out.add(song)
        }
        return out
    }

    /**
     * key → 曲目。曲库里的版本优先（歌名/歌手被改过时要用新的），
     * 曲库还没扫出来时退回已经知道的那些，别让正在播的歌凭空消失。
     */
    private fun songLookup(): Map<String, Song> {
        val map = LinkedHashMap<String, Song>()
        _state.value.queue.forEach { map[it.key] = it }
        allSongs.forEach { map[it.key] = it }
        songCache.forEach { (key, song) -> map.putIfAbsent(key, song) }
        return map
    }

    private fun syncFromController() {
        val c = controller ?: return
        val index = c.currentMediaItemIndex
        val queue = deriveQueue(c) ?: _state.value.queue
        val song = queue.getOrNull(index)
        val previousKey = _state.value.currentSong?.key
        val duration = if (c.duration > 0L) c.duration else (song?.durationMs ?: 0L)

        _state.update {
            it.copy(
                queue = queue,
                currentIndex = index,
                currentSong = song ?: it.currentSong,
                isPlaying = c.isPlaying,
                buffering = c.playbackState == Player.STATE_BUFFERING,
                durationMs = duration,
                // 播放模式用 App 侧记录（顺序/列表循环在底层是同一个 REPEAT_MODE_ALL，
                // 从 repeatMode 反推会串）
                playMode = appPlayMode
            )
        }
        if (song != null && song.key != previousKey) {
            prefs.lastSongKey = song.key
            loadLyrics(song, duration)
            // 封面与歌词一个待遇：切到哪首就把哪首的封面准备好
            ensureCover(song)
        }
    }

    // ------------------------------------------------------------------ 曲库

    fun refresh() {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }

            // 限定文件夹模式下**不需要读取音频权限**：目录树授权已经够读那一个文件夹了。
            // 这里仍然拿媒体库权限当门槛的话，用户明明只想听 MP3 目录，却要先交出整机权限。
            val folderUri = prefs.libraryFolderUri?.takeIf { prefs.libraryFolderOnly }?.let(Uri::parse)
            val folderOnly = folderUri != null

            if (!folderOnly && !library.hasAudioPermission()) {
                _state.update {
                    it.copy(loading = false, permissionGranted = false, songs = emptyList(), filtered = emptyList())
                }
                return@launch
            }

            val device = when {
                folderOnly -> {
                    // 授权可能在系统设置里被撤销过，只看偏好里的 URI 字符串是不够的
                    if (!library.hasTreePermission(folderUri!!)) {
                        _messages.tryEmit("文件夹授权已失效，请重新选择")
                        prefs.libraryFolderOnly = false
                        _state.update { it.copy(libraryFolderOnly = false) }
                        runCatching { library.scanDevice() }.getOrDefault(emptyList())
                    } else {
                        _state.update { it.copy(scanLabel = "正在扫描「${prefs.libraryFolderName ?: "所选文件夹"}」…") }
                        runCatching { library.scanTreeAudio(folderUri) { found ->
                            _state.update { it.copy(scanLabel = "已在所选文件夹里找到 $found 首…") }
                        } }.getOrDefault(emptyList())
                    }
                }

                else -> runCatching { library.scanDevice() }.getOrDefault(emptyList())
            }
            val imported = runCatching { library.loadImported(prefs.importedAudioUris) }.getOrDefault(emptyList())
            val archivedSongs = syncArchived()
            // 副本真的在手上，来源曲目才让位；副本丢了就让原曲回到列表，绝不留下空库
            supersededKeys = ArchivedSongs.supersededSourceKeys(prefs.archivedSongs, archivedSongs)
            // 用户改过的歌名/歌手/专辑套在扫描结果上：搜索、排序、歌词匹配都吃新值
            allSongs = (device + imported + archivedSongs)
                .let { SongEdits.apply(it, songEdits) }
                .distinctBy { it.key }
            dropArchivedFromHidden()

            _state.update {
                it.copy(
                    loading = false,
                    // 限定文件夹模式下也算"有权限"：否则曲库页会一直停在「需要访问本地音乐」
                    permissionGranted = true,
                    scanLabel = "",
                    playlists = prefs.playlists,
                    archivedCount = prefs.archivedSongs.size,
                    archivedBytes = archived.totalBytes()
                )
            }
            applyLibrary()
            refreshLyricCopies()
            prefs.hasScannedOnce = true
            connectController()
            restoreQueueIfIdle()
            syncFromController()
        }
    }

    /**
     * 让归档记录与磁盘对齐：剔除文件已不在的记录，补上「有文件、没记录」的副本。
     *
     * 两个方向都可能不同步：用户在文件管理器里删掉副本会留下死记录；记录被清空
     * （历史版本在列目录失败时会把记录当垃圾清掉）或用户自己把歌拷进这个目录，
     * 则会留下「文件在磁盘上、列表里却没有」的孤儿 —— 后者以目录为准补回来。
     *
     * **读不到目录时什么都不改。** 把「一时读不到」当成「文件都没了」会把记录连带清空，
     * 副本还躺在磁盘上、App 却永久性地忘了它们，用户看到的就是「歌全没了」。
     */
    private suspend fun syncArchived(): List<Song> = withContext(Dispatchers.IO) {
        val entries = prefs.archivedSongs
        val existing = archived.existingNames()
        val alive = if (existing == null) entries else ArchivedSongs.orphans(entries, existing)
        val adopted = if (existing == null) emptyList() else archived.adoptOrphans(alive, existing)
        if (alive.size != entries.size || adopted.isNotEmpty()) {
            prefs.archivedSongs = alive + adopted
        }
        archived.loadSongs(alive + adopted)
    }

    /**
     * 把历史版本塞进「已隐藏」的归档来源记录摘掉。
     *
     * 旧版把「已归档的来源曲目」直接写进了隐藏列表，于是归档一次，整张曲库都进了
     * 「已隐藏的曲目」，看起来就是歌全被隐藏了。现在归档与隐藏彻底分开：
     * 来源曲目由 [supersededKeys] 在列表里让位，不进隐藏记录。
     */
    private fun dropArchivedFromHidden() {
        val before = prefs.hiddenSongs
        val after = ArchivedSongs.dropSupersededHidden(before, prefs.archivedSongs)
        if (after.size != before.size) prefs.hiddenSongs = after
    }

    /** 用「全量曲目 + 当前隐藏记录」重算界面上的列表。隐藏/恢复都走这里。 */
    private fun applyLibrary() {
        val hidden = prefs.hiddenSongs
        // 还没扫过盘时 allSongs 为空，此时不能把「查不到」误报成「文件已不在设备上」
        val missing = if (allSongs.isEmpty()) {
            emptySet()
        } else {
            HiddenSongs.orphans(hidden, allSongs).mapTo(HashSet()) { it.key }
        }
        _state.update { current ->
            val visible = HiddenSongs.visible(allSongs, hidden).filterNot { it.key in supersededKeys }
            val filtered = SongQuery.apply(visible, current.query, current.sort)
            current.copy(
                songs = visible,
                filtered = filtered,
                hiddenSongs = hidden,
                hiddenMissingKeys = missing,
                // 曲库一变（隐藏、归档让位、重新扫描）就顺手把选中收敛一遍：
                // 这里是所有会改曲库的操作的收口，不在这里做的话每个调用点都得自己记得，
                // 漏一个就会出现"已选 N 首"里混着几首列表上已经看不见的歌
                selection = if (current.selection.isEmpty()) {
                    emptySet()
                } else {
                    BatchOps.pruneSelection(current.selection, filtered.mapTo(HashSet()) { it.key })
                }
            )
        }
    }

    fun onPermissionResult(granted: Boolean) {
        _state.update { it.copy(permissionGranted = granted) }
        if (granted) refresh() else _messages.tryEmit("没有读取权限，无法扫描本地音乐")
    }

    /**
     * 搜索词变化。
     *
     * 多选时会把**搜不到的选中项丢掉**：搜索框每敲一个字都会走这里，不收敛的话
     * 搜"周杰伦"之后操作条还顶着"已选 5 首"，而其中 3 首已经不在结果里 ——
     * 用户点批量操作，作用对象和他在屏幕上看到的完全对不上。
     */
    fun setQuery(query: String) {
        _state.update { state ->
            val filtered = SongQuery.apply(state.songs, query, state.sort)
            state.copy(
                query = query,
                filtered = filtered,
                selection = if (state.selection.isEmpty()) {
                    emptySet()
                } else {
                    BatchOps.pruneSelection(state.selection, filtered.mapTo(HashSet()) { it.key })
                }
            )
        }
    }

    fun setSearchActive(active: Boolean) {
        _state.update {
            if (active) it.copy(searchActive = true)
            else it.copy(searchActive = false, query = "", filtered = SongQuery.apply(it.songs, "", it.sort))
        }
    }

    fun setSort(sort: SortMode) {
        prefs.sortMode = sort
        _state.update { it.copy(sort = sort, filtered = SongQuery.apply(it.songs, it.query, sort)) }
    }

    /** 用户通过系统文件选择器导入音频。 */
    fun importAudio(uris: List<Uri>) {
        if (uris.isEmpty()) return
        library.takePersistablePermission(uris)
        val existing = prefs.importedAudioUris.toMutableList()
        uris.forEach { uri -> if (existing.none { it == uri.toString() }) existing.add(uri.toString()) }
        prefs.importedAudioUris = existing
        _messages.tryEmit("已导入 ${uris.size} 个文件")
        refresh()
    }

    fun removeImportedSong(song: Song) {
        if (!song.imported) {
            _messages.tryEmit("系统媒体库中的曲目请在系统设置里移除")
            return
        }
        prefs.importedAudioUris = prefs.importedAudioUris.filterNot { it == song.uri }
        prefs.hiddenSongs = HiddenSongs.remove(prefs.hiddenSongs, song.key)
        lyricsRepository.invalidate(song)
        _messages.tryEmit("已移出「${song.title}」")
        refresh()
    }

    // ------------------------------------------------------------------ 隐藏不想听的曲目

    /**
     * 把一首曲目从曲库/搜索里隐藏。
     *
     * 只改「是否显示」，不碰文件本身；当前队列也不动 —— 队列是用户自己排的，
     * 正在放的那首被隐藏了就让它放完，符合预期。
     */
    fun hideSong(song: Song) {
        val before = prefs.hiddenSongs
        prefs.hiddenSongs = HiddenSongs.add(before, song)
        applyLibrary()
        _messages.tryEmit("已隐藏「${song.title}」，可在设置里恢复")
    }

    /** 恢复一首（[key] 是 [Song.key]，因为曲目此刻已不在曲库里）。 */
    fun unhideSong(key: String) {
        val entry = prefs.hiddenSongs.firstOrNull { it.key == key } ?: return
        prefs.hiddenSongs = HiddenSongs.remove(prefs.hiddenSongs, key)
        applyLibrary()
        _messages.tryEmit("已恢复「${entry.title}」")
    }

    fun clearHiddenSongs() {
        if (prefs.hiddenSongs.isEmpty()) return
        val count = prefs.hiddenSongs.size
        prefs.hiddenSongs = emptyList()
        applyLibrary()
        _messages.tryEmit("已恢复全部 $count 首")
    }

    // ------------------------------------------------------------------ 自定义歌单

    private fun persistPlaylists(next: List<Playlist>) {
        prefs.playlists = next
        _state.update { it.copy(playlists = next) }
    }

    /** 改一个歌单；[block] 返回同一个实例表示"没改动"，那就不写盘也不刷新界面。 */
    private fun mutatePlaylist(id: String, block: (Playlist) -> Playlist) {
        val current = prefs.playlists
        val target = current.firstOrNull { it.id == id } ?: return
        val updated = block(target)
        if (updated === target) return
        persistPlaylists(current.map { if (it.id == id) updated else it })
    }

    /**
     * 归档 / 取消归档会换掉这首歌的 key（`ms:<id>` ↔ `lib:<文件名>`），而歌单里存的正是 key。
     *
     * 不跟着换的话，这首歌会在用户眼皮底下**从歌单里消失** —— 文件明明还在、也还能播，
     * 歌单里就是少了一行，而且界面上没有任何地方解释为什么。
     * 用 [LinkedHashSet] 去重：万一目标 key 已经在歌单里，不能因此多出一行重复。
     */
    private fun remapPlaylistKeys(from: String, to: String) {
        val current = prefs.playlists
        if (current.isEmpty() || from == to) return
        var changed = false
        val next = current.map { playlist ->
            if (from !in playlist.songKeys) return@map playlist
            changed = true
            val mapped = LinkedHashSet<String>()
            playlist.songKeys.forEach { key -> mapped += if (key == from) to else key }
            playlist.copy(songKeys = mapped.toList())
        }
        if (changed) persistPlaylists(next)
    }

    /**
     * 新建歌单，返回它的 id（界面据此切过去）。
     *
     * 建完就切到新歌单：用户下一步一定是往里加歌，站在一个空歌单上等他操作最自然。
     */
    fun createPlaylist(name: String): String? {
        val nowSec = System.currentTimeMillis() / 1000L
        val next = Playlists.create(prefs.playlists, name, "pl:$nowSec", nowSec)
        persistPlaylists(next)
        val created = next.first()
        _state.update { it.copy(activePlaylistId = created.id) }
        _messages.tryEmit("已新建「${created.name}」")
        return created.id
    }

    /**
     * 批量「加入歌单」时现场新建一个歌单，并把当前选中的都放进去。
     *
     * 走的是 [createPlaylistThenNoSwitch] 而不是 [createPlaylist]：批量时用户正站在
     * 原来的列表上挑歌，弹窗一关就跳进一个空歌单，等于把刚选好的这批甩掉了。
     */
    fun createPlaylistThenBatchAdd(name: String) {
        val id = createPlaylistThenNoSwitch(name) ?: return
        batchAddToPlaylist(id)
    }

    /** 新建歌单但不切视图，返回它的 id。 */
    private fun createPlaylistThenNoSwitch(name: String): String? {
        val nowSec = System.currentTimeMillis() / 1000L
        val next = Playlists.create(prefs.playlists, name, "pl:$nowSec", nowSec)
        persistPlaylists(next)
        return next.first().id
    }

    fun renamePlaylist(id: String, name: String) {        val current = prefs.playlists
        val before = current.firstOrNull { it.id == id } ?: return
        val next = Playlists.rename(current, id, name)
        if (next === current) {
            _messages.tryEmit("名字不能为空，也不能和别的歌单重名")
            return
        }
        persistPlaylists(next)
        _messages.tryEmit("已改名为「${next.first { it.id == id }.name}」")
    }

    fun deletePlaylist(id: String) {
        val target = prefs.playlists.firstOrNull { it.id == id } ?: return
        persistPlaylists(Playlists.delete(prefs.playlists, id))
        // 正在看的歌单被删了就退回整库，否则曲库页会停在一个什么都不显示的视图上
        if (_state.value.activePlaylistId == id) {
            _state.update { it.copy(activePlaylistId = null) }
        }
        _messages.tryEmit("已删除歌单「${target.name}」")
    }

    /**
     * 切换正在看的歌单（或回到整库）。
     *
     * **顺手清掉多选**：选中是"在这份列表里选中的"，切了视图就是另一份列表了。
     * 不清的话操作条会顶着"已选 5 首"作用到一个新视图里一首都看不见的集合上 ——
     * 用户点的每一个批量操作都会得到"没有可处理的曲目"。
     */
    fun selectPlaylist(id: String?) {
        _state.update {
            it.copy(
                activePlaylistId = id,
                query = "",
                searchActive = false,
                selection = emptySet()
            )
        }
    }

    /** 「加入歌单」的选择列表：给哪首歌挑目标。 */
    fun beginAddToPlaylist(song: Song) {
        _state.update { it.copy(playlistAddTarget = song) }
    }

    fun dismissAddToPlaylist() {
        _state.update { it.copy(playlistAddTarget = null) }
    }

    fun addSongToPlaylist(song: Song, playlistId: String) {
        val before = prefs.playlists.firstOrNull { it.id == playlistId }
        if (before == null) {
            dismissAddToPlaylist()
            return
        }
        val already = song.key in before.songKeys
        mutatePlaylist(playlistId) { Playlists.addSongs(it, listOf(song.key)) }
        dismissAddToPlaylist()
        _messages.tryEmit(
            if (already) "「${song.title}」已经在「${before.name}」里了"
            else "已加入「${before.name}」"
        )
    }

    /** 从歌单里移除（只动歌单，不动曲库，也不隐藏这首）。 */
    fun removeSongFromPlaylist(playlistId: String, key: String) {
        mutatePlaylist(playlistId) { Playlists.removeSong(it, key) }
        _messages.tryEmit("已从歌单移除")
    }

    /** 歌单内上移（[delta] = -1）/ 下移（+1）。到边界时什么都不做，也不动界面。 */
    fun movePlaylistSong(playlistId: String, index: Int, delta: Int) {
        mutatePlaylist(playlistId) { playlist ->
            if (delta < 0) Playlists.moveUp(playlist, index) else Playlists.moveDown(playlist, index)
        }
    }

    /** 播放整个歌单：队列就是歌单里排好的顺序，从 [startIndex] 那首开始。 */
    fun playPlaylist(playlistId: String, startIndex: Int) {
        val playlist = prefs.playlists.firstOrNull { it.id == playlistId } ?: return
        val songs = Playlists.resolve(playlist, _state.value.songs)
        if (songs.isEmpty()) {
            _messages.tryEmit("「${playlist.name}」里还没有可播放的曲目")
            return
        }
        playAll(songs, startIndex.coerceIn(0, songs.lastIndex))
    }

    // ------------------------------------------------------------------ 批量选择

    /**
     * 进多选模式：以 [song] 为第一首。
     *
     * 从长按菜单进多选时用这个 —— 用户已经明确指着某一首了，直接把它选中，
     * 接着就能一路点下去，比"进了多选还要再点一次它"少一步也不容易空手退出。
     */
    fun beginSelection(song: Song) {
        _state.update { it.copy(selection = setOf(song.key)) }
    }

    fun toggleSelection(song: Song) {
        _state.update { state ->
            val next = if (song.key in state.selection) {
                state.selection - song.key
            } else {
                state.selection + song.key
            }
            // 取消到一首不剩时自动退出多选：留一个"已选 0 首"的空界面只会让人不知道下一步该点哪
            if (next.isEmpty()) state.copy(selection = emptySet()) else state.copy(selection = next)
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selection = emptySet(), batchLabel = "") }
    }

    /**
     * 全选 / 取消全选，按当前列表的实际可见项。
     *
     * 全选只选**眼前能看见的**：搜索「周杰伦」时按全选，选的是这 5 首而不是整库 ——
     * 用户看到的是这 5 行，认为自己选的就是这 5 首。
     */
    fun toggleSelectAll(visible: List<Song>) {
        if (visible.isEmpty()) return
        _state.update { state ->
            if (BatchOps.selectAllTogglesOff(state.selectedCount, visible.size)) {
                state.copy(selection = emptySet())
            } else {
                state.copy(selection = visible.mapTo(LinkedHashSet()) { it.key })
            }
        }
    }

    /**
     * 批量动作跑完之后清掉已经不成立的选中项，并收掉进度态。
     *
     * 隐藏、从歌单移除这类操作会让被处理的曲目从当前视图里消失，而选中集合里还留着
     * 它们 —— 界面上的"已选 N 首"和操作条真正会作用的对象就对不上了。
     * [visible] 传**当前视图可见**的曲目；传空集合表示"没有视图概念"（例如从设置页触发的批量），
     * 此时不清选择。
     */
    private fun finishBatch(visible: List<Song>, message: String) {
        _state.update { state ->
            val next = if (visible.isEmpty()) {
                emptySet()
            } else {
                BatchOps.pruneSelection(state.selection, visible.mapTo(HashSet()) { it.key })
            }
            state.copy(selection = next, batchWorking = false, batchLabel = "")
        }
        _messages.tryEmit(message)
    }

    // -------------------------------------------------- 批量：加入歌单 / 移出

    /** 批量「加入歌单」：弹出歌单选择，作用对象是当前选中的全部曲目。 */
    fun beginBatchAddToPlaylist() {
        val keys = _state.value.selection
        if (keys.isEmpty()) return
        _state.update { it.copy(batchPlaylistTargetKeys = keys) }
    }

    fun dismissBatchAddToPlaylist() {
        _state.update { it.copy(batchPlaylistTargetKeys = emptySet()) }
    }

    /**
     * 把当前选中的曲目全部加进 [playlistId]。
     *
     * 汇报里必须说清"已经在里面的有几首"：用户选了 10 首、只有 3 首是新加的，
     * 只报"已加入 3 首"会让他怀疑另外 7 首去哪了。
     */
    fun batchAddToPlaylist(playlistId: String) {
        val selected = _state.value.selection.toList()
        dismissBatchAddToPlaylist()
        if (selected.isEmpty()) return
        val playlist = prefs.playlists.firstOrNull { it.id == playlistId } ?: run {
            _messages.tryEmit("歌单已不存在")
            return
        }
        val (fresh, already) = BatchOps.splitForPlaylistAdd(selected, playlist)
        val added = BatchOps.addedCount(playlist, fresh)
        if (fresh.isNotEmpty()) {
            mutatePlaylist(playlistId) { Playlists.addSongs(it, fresh) }
        }
        // 汇报用的 key 取实际写进去的那几首：addedCount 已按去重后的结果算，
        // 数量与 key 列表必须一致，否则 [BatchOps.accountedCount] 的对账就失去意义
        val groups = buildList {
            if (added > 0) add(BatchOps.Group.Done(fresh.take(added)))
            if (already.isNotEmpty()) add(BatchOps.Group.Unchanged(already))
        }
        val message = BatchOps.summary(groups, "加入歌单")
        finishBatch(currentlyVisible(), message)
    }

    /** 批量从当前歌单移除。 */
    fun batchRemoveFromPlaylist() {
        val playlistId = _state.value.activePlaylistId ?: return
        val selected = _state.value.selection.toList()
        if (selected.isEmpty()) return
        val playlist = prefs.playlists.firstOrNull { it.id == playlistId } ?: return
        val present = playlist.songKeys.toHashSet()
        val (removing, absent) = selected.partition { it in present }
        if (removing.isNotEmpty()) {
            val drop = removing.toHashSet()
            mutatePlaylist(playlistId) { target ->
                target.copy(songKeys = target.songKeys.filterNot { it in drop })
            }
        }
        val groups = buildList {
            if (removing.isNotEmpty()) add(BatchOps.Group.Done(removing))
            if (absent.isNotEmpty()) add(BatchOps.Group.Absent(absent))
        }
        finishBatch(currentlyVisible(), BatchOps.summary(groups, "从歌单移除"))
    }

    // ---------------------------------------------------------- 批量：隐藏 / 恢复

    fun batchHideSongs() {
        val selected = _state.value.selection.toList()
        if (selected.isEmpty()) return
        val hidden = prefs.hiddenSongs.mapTo(HashSet()) { it.key }
        val byKey = allSongs.associateBy { it.key }
        val (changing, already) = BatchOps.splitForHidden(selected, hidden::contains, toHidden = true)
        // 分开记账：曲库里找不到的（文件刚被别的 App 删了）不能混进成功里
        val hiddenKeys = LinkedHashSet<String>()
        val missing = LinkedHashSet<String>()
        var entry = prefs.hiddenSongs
        changing.forEach { key ->
            val song = byKey[key]
            if (song == null) {
                missing.add(key)
            } else {
                entry = HiddenSongs.add(entry, song)
                hiddenKeys.add(key)
            }
        }
        if (entry !== prefs.hiddenSongs) {
            prefs.hiddenSongs = entry
            applyLibrary()
        }
        val groups = buildList {
            if (hiddenKeys.isNotEmpty()) add(BatchOps.Group.Done(hiddenKeys.toList()))
            if (missing.isNotEmpty()) add(BatchOps.Group.Failed(missing.toList(), "曲目已不在曲库"))
            if (already.isNotEmpty()) add(BatchOps.Group.Unchanged(already))
        }
        finishBatch(currentlyVisible(), BatchOps.summary(groups, "隐藏"))
    }

    // -------------------------------------------------------- 批量：封面相关

    /**
     * 批量「去除封面」。
     *
     * 这里删的**只是 App 这一层**：联网缓存的和用户从相册设的自定义封面。
     * 音频文件里内嵌的封面删不动、也不会被动 —— 汇报与确认框都按这个说法写，
     * 不让用户以为整首歌变回灰图。
     */
    fun batchRemoveCovers() {
        val selected = _state.value.selection.toList()
        if (selected.isEmpty()) return
        // 判据必须是 `matched`，和单曲的「移除封面」保持一致：
        // 搜不到时也会记一条**负结果**（trackId = 0），那个不等于"App 存了这张封面"，
        // 用 `entry != null` 判的话这类歌会被算成可删，删完什么都没发生还报一次成功。
        val (removable, nothing) = BatchOps.splitForCoverRemoval(selected) {
            covers.entry(it)?.matched == true
        }
        val byKey = allSongs.associateBy { it.key }
        val done = LinkedHashSet<String>()
        val missing = LinkedHashSet<String>()
        val failed = LinkedHashSet<String>()
        viewModelScope.launch {
            _state.update { it.copy(batchWorking = true, batchLabel = "正在去除封面…") }
            removable.forEach { key ->
                val song = byKey[key]
                if (song == null) {
                    // 文件在选中的那一刻还在、动手前被删了（别的清理工具干的）
                    missing.add(key)
                    return@forEach
                }
                val ok = runCatching { covers.remove(song) }.getOrDefault(false)
                if (ok) {
                    done.add(key)
                    // 删完立刻把这首在队列里的条目刷一遍，否则等它被切到时通知栏还是旧封面
                    republishSong(song)
                } else {
                    failed.add(key)
                }
            }
            // 通知封面变化**必须无条件发**，不能只顾当前播放的那首。
            // 界面靠 coverRevision 这一个信号触发所有行的重组（见 notifyCoverChanged）：
            // 之前只在"当前播放曲目被去掉了封面"时才发，于是批量去掉其它歌的封面后，
            // 那些行的 rememberCover 不会重跑，继续画着内存里那张旧图 ——
            // 表现就是"提示成功了但图还在，重进App 才刷新"。
            if (done.isNotEmpty()) notifyCoverChanged()
            refreshCoverStats()
            val groups = buildList {
                if (done.isNotEmpty()) add(BatchOps.Group.Done(done.toList()))
                if (failed.isNotEmpty()) add(BatchOps.Group.Failed(failed.toList(), "文件被占用或权限失效"))
                if (missing.isNotEmpty()) add(BatchOps.Group.Failed(missing.toList(), "曲目已不在曲库"))
                if (nothing.isNotEmpty()) add(BatchOps.Group.Unchanged(nothing))
            }
            finishBatch(
                visible = currentlyVisible(),
                message = BatchOps.summary(groups, "去除封面", unit = "张")
            )
        }
    }

    // ------------------------------------------------------ 批量：嵌入标签

    /** 批量「嵌入标签」：先让用户选要写什么，再动手。 */
    fun beginBatchEmbed() {
        val keys = _state.value.selection
        if (keys.isEmpty()) return
        _state.update { it.copy(batchEmbedTargetKeys = keys) }
    }

    fun dismissBatchEmbed() {
        _state.update { it.copy(batchEmbedTargetKeys = emptySet()) }
    }

    /**
     * 把选中的曲目的歌词 / 封面写进音频文件。
     *
     * 汇报必须区分四种结果而不是只报"成功"：文件不支持写、没权限、写失败、
     * 没有内容可写 —— 它们的处理建议完全不同（换格式 / 重新授权 / 重试 / 换个歌）。
     * 只报成功的话，用户会以为都写进去了，直到换台播放器才发现文件根本没变。
     */
    fun batchEmbedTags(writeLyrics: Boolean, writeArtwork: Boolean) {
        val keys = _state.value.batchEmbedTargetKeys.ifEmpty { _state.value.selection }.toList()
        dismissBatchEmbed()
        if (keys.isEmpty()) return
        if (!writeLyrics && !writeArtwork) {
            _messages.tryEmit("至少要选一项：写歌词或写封面")
            return
        }

        val byKey = allSongs.associateBy { it.key }
        viewModelScope.launch {
            _state.update { it.copy(batchWorking = true, batchLabel = "正在写入文件…") }
            val done = ArrayList<String>()
            val refused = LinkedHashSet<String>()
            val failed = LinkedHashSet<String>()
            val nothing = LinkedHashSet<String>()
            val missing = LinkedHashSet<String>()
            val doneContainers = LinkedHashSet<String>()

            for ((index, key) in keys.withIndex()) {
                val song = byKey[key]
                if (song == null) {
                    missing.add(key)
                    continue
                }
                _state.update {
                    it.copy(batchLabel = "正在写入 ${index + 1}/${keys.size}：${song.title}")
                }

                // 歌词取「当前这一首实际在用的那份」，而不是重新去联网匹配 ——
                // 用户看到界面上的词就是要写进文件的词，重新匹配可能给出另一份。
                val lyricsText = if (writeLyrics) {
                    lyricsRepository.lyricTextFor(song, song.durationMs)
                } else {
                    null
                }
                // 封面取 App 这一层已存的；没有就不写，绝不拿占位图去覆盖文件里的真封面
                val artwork = if (writeArtwork) covers.artworkData(song.key) else null

                if ((writeLyrics && lyricsText.isNullOrBlank()) && (writeArtwork && artwork == null)) {
                    nothing.add(key)
                    continue
                }

                when (val outcome = embedder.embed(song, lyricsText, artwork)) {
                    is TagEmbedder.Outcome.Done -> {
                        done.add(key)
                        doneContainers.add(outcome.container)
                    }
                    is TagEmbedder.Outcome.Refused -> refused.add(key)
                    is TagEmbedder.Outcome.Failed -> failed.add(key)
                }
            }

            // 写进文件之后，媒体库那边的内嵌封面/歌词可能变了：
            // 重新扫一遍让曲库显示跟上文件的新状态
            if (done.isNotEmpty()) {
                // 文件里的封面可能刚被换掉，缓存里还留着旧字节 —— 必须先丢掉再通知刷新，
                // 否则重组后 rememberCover 从缓存拿到的还是改之前那张
                if (writeArtwork) embeddedArt.evict(done)
                notifyCoverChanged()
                refresh()
            }

            val groups = buildList {
                if (done.isNotEmpty()) add(BatchOps.Group.Done(done))
                if (refused.isNotEmpty()) add(BatchOps.Group.Failed(refused.toList(), "格式或来源不支持写入"))
                if (failed.isNotEmpty()) add(BatchOps.Group.Failed(failed.toList(), "写入失败，文件未改动"))
                if (missing.isNotEmpty()) add(BatchOps.Group.Failed(missing.toList(), "曲目已不在曲库"))
                if (nothing.isNotEmpty()) add(BatchOps.Group.Unchanged(nothing.toList()))
            }
            val tail = if (doneContainers.isNotEmpty()) {
                "（${doneContainers.joinToString(" / ")}）"
            } else {
                ""
            }
            finishBatch(
                visible = currentlyVisible(),
                message = BatchOps.summary(groups, "写入") + tail
            )
        }
    }

    /**
     * 当前视图里实际可见的曲目。
     *
     * 歌单视图走歌单自己的顺序与搜索结果，其余走曲库的排序+搜索结果 ——
     * 和 [LibraryContent] 渲染的那份列表保持一致，否则清完选择会留下"看不见但还选着"的项。
     */
    private fun currentlyVisible(): List<Song> {
        val state = _state.value
        return if (state.isPlaylistView) {
            if (state.query.isBlank()) state.playlistSongs
            else SongQuery.filter(state.playlistSongs, state.query)
        } else {
            state.filtered
        }
    }

    // ------------------------------------------------------ 曲库只扫描指定文件夹

    /**
     * 记住用户选的目录树并立刻切过去。
     *
     * 授权要**当场**取持久化权限：只在偏好里记下 URI 是不够的，
     * 冷启动时系统并不会因为偏好里有这个字符串就把权限还给你。
     */
    fun pickLibraryFolder(treeUri: Uri, displayName: String?) {
        library.takePersistableTreePermission(treeUri)
        prefs.libraryFolderUri = treeUri.toString()
        prefs.libraryFolderName = displayName ?: "所选文件夹"
        prefs.libraryFolderOnly = true
        _state.update {
            it.copy(libraryFolderOnly = true, libraryFolderName = prefs.libraryFolderName)
        }
        _messages.tryEmit("曲库已限定为「${prefs.libraryFolderName}」")
        refresh()
    }

    fun setLibraryFolderOnly(enabled: Boolean) {
        if (enabled && prefs.libraryFolderUri == null) {
            _messages.tryEmit("先选一个音乐文件夹，才能限定扫描范围")
            return
        }
        prefs.libraryFolderOnly = enabled
        _state.update { it.copy(libraryFolderOnly = enabled) }
        refresh()
    }

    fun clearLibraryFolder() {
        prefs.libraryFolderOnly = false
        prefs.libraryFolderUri = null
        prefs.libraryFolderName = null
        _state.update { it.copy(libraryFolderOnly = false, libraryFolderName = null) }
        _messages.tryEmit("已恢复为扫描整机音乐")
        refresh()
    }

    // ------------------------------------------------------------------ App 音乐库（归档）

    /**
     * 把一首歌归档到 App 专属目录，并把歌词写进文件内嵌标签。
     *
     * 归档成功后**不会**把来源曲目写进「已隐藏」——那会让用户以为自己的曲库被隐藏了。
     * 同一首歌不出现两条靠 [supersededKeys]：只要副本在，原曲这一行就自动让位；
     * 副本哪天不在了，原曲原封不动地回到列表。
     */
    fun archiveSong(song: Song) {
        if (_state.value.archiving) {
            _messages.tryEmit("正在归档，请稍等")
            return
        }
        viewModelScope.launch { runArchive(listOf(song)) }
    }

    /** 一键归档：把曲库里所有还没归档的曲目逐个复制进 App 库。 */
    fun archiveAll() {
        if (_state.value.archiving) return
        val targets = _state.value.songs.filterNot { it.archived }
        if (targets.isEmpty()) {
            _messages.tryEmit("曲目都已经在 App 音乐库里了")
            return
        }
        viewModelScope.launch { runArchive(targets) }
    }

    fun cancelArchive() {
        if (!_state.value.archiving) return
        archiveCancelled = true
        _messages.tryEmit("正在停止…")
    }

    private suspend fun runArchive(targets: List<Song>) {
        archiveCancelled = false
        _state.update {
            it.copy(archiving = true, archiveDone = 0, archiveTotal = targets.size, archiveLabel = "")
        }

        val taken = archived.knownNames().toMutableSet()
        var ok = 0
        var failed = 0
        var embedded = 0

        for (song in targets) {
            if (archiveCancelled) break
            _state.update { it.copy(archiveLabel = song.title) }
            try {
                val lyricsText = runCatching {
                    lyricsRepository.lyricTextFor(song, song.durationMs)
                }.getOrNull()
                val entry = archived.archive(song, lyricsText, archived.suggestName(song, taken))
                taken.add(entry.name)
                // 每成功一首就落盘：中途被系统杀掉也不会留下「有文件没记录」的垃圾
                prefs.archivedSongs = listOf(entry) + prefs.archivedSongs
                // 归档不等于隐藏：这里只记「来源是谁」，原曲由 supersededKeys 在列表里让位
                if (!song.archived) {
                    supersededKeys = supersededKeys + song.key
                    // 副本的 key 变了，歌单里指向原曲的那一条必须跟着换，
                    // 否则这首歌会在用户眼皮底下从歌单里消失，而文件明明还在
                    remapPlaylistKeys(song.key, "lib:${entry.name}")
                }
                // 归档副本自己也要有歌词：M4A/Ogg/WAV 写不进内嵌标签，靠私有副本兜住
                lyricsText?.let {
                    lyricsStore.save(
                        songKey = "lib:${entry.name}",
                        text = it,
                        label = "${entry.title} · 归档快照",
                        origin = LyricOrigin.ARCHIVED
                    )
                }
                if (entry.lyricsEmbedded) embedded++
                ok++
            } catch (_: Throwable) {
                failed++
            }
            _state.update { it.copy(archiveDone = it.archiveDone + 1) }
        }

        _state.update { it.copy(archiving = false, archiveLabel = "") }
        _messages.tryEmit(
            buildString {
                append("已归档 $ok 首")
                if (embedded > 0) append("，其中 $embedded 首歌词已写进文件")
                if (failed > 0) append("；$failed 首失败")
                if (archiveCancelled) append("（已取消）")
            }
        )
        refresh()
    }

    /**
     * 从曲库那一行直接取消归档（行菜单 ⋮ →「取消归档」）。
     *
     * 手上只有 [Song]，没有归档记录 —— 靠 `managedName`（也就是副本的文件名）把记录找回来。
     * 找不到就如实说，不假装成功：那说明这条根本不是 App 库里的副本。
     */
    fun unarchiveSong(song: Song) {
        val name = song.managedName
        val entry = name?.let { target -> prefs.archivedSongs.firstOrNull { it.name == target } }
        if (entry == null) {
            _messages.tryEmit("这首不在 App 音乐库里，没有可取消的归档")
            return
        }
        unarchiveSong(entry)
    }

    /**
     * 取消**某一首**歌的归档：删掉 App 库里的那份副本，并摘掉它的记录。
     *
     * 顺序上有一条硬要求：**先删文件、成功了再删记录**。反过来的话，文件会留在
     * App 音乐库里变成「有文件没记录」的孤儿，下次启动被 [ArchivedLibrary.adoptOrphans]
     * 认回来 —— 用户看到的就是「取消了，它又自己回来了」。
     *
     * 归档时留下的歌词快照（槽位 `lib:<文件名>`）也一起删：它只服务于这份副本，
     * 副本没了就没有人会再读它，留着只会让「歌词副本管理」多一份看不懂的孤儿。
     *
     * 原文件不动 —— [refresh] 一算 supersededKeys，它自然回到曲库里那一行。
     */
    fun unarchiveSong(entry: ArchivedEntry) {
        if (_state.value.archiving) {
            _messages.tryEmit("正在归档，请先等待完成")
            return
        }
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) {
                val file = archived.fileOf(entry)
                runCatching { !file.exists() || file.delete() }.getOrDefault(false)
            }
            if (!removed) {
                _messages.tryEmit("删不掉这份副本，可能正被占用，稍后重试")
                return@launch
            }
            prefs.archivedSongs = prefs.archivedSongs.filterNot { it.name == entry.name }
            withContext(Dispatchers.IO) { lyricsStore.remove("lib:${entry.name}") }
            entry.sourceKey?.let { key ->
                // 历史版本把「已归档的来源曲目」写进过隐藏列表，不摘掉的话原曲回来了却看不见
                prefs.hiddenSongs = prefs.hiddenSongs.filterNot { it.key == key }
                // refresh() 里还会重算一遍；这里先减掉，是因为 refresh 正在跑时会被开头的
                // 「loading」判断挡掉，不补这一下，这一行要等到下次扫描才回来
                supersededKeys = supersededKeys - key
                // 副本没了，原曲那一行会回来，歌单里也要指回原曲
                remapPlaylistKeys("lib:${entry.name}", key)
            }
            _messages.tryEmit("已取消「${entry.title}」的归档，原文件仍在曲库里")
            refresh()
        }
    }

    /** 清空 App 音乐库。删掉副本之后对应的原曲自动回到列表，免得用户以为歌没了。 */
    fun clearArchive() {
        if (_state.value.archiving) {
            _messages.tryEmit("正在归档，请先等待完成")
            return
        }
        val entries = prefs.archivedSongs
        if (entries.isEmpty()) return
        viewModelScope.launch {
            val (count, bytes) = withContext(Dispatchers.IO) { archived.deleteAll() }
            entries.forEach { lyricsStore.remove("lib:${it.name}") }
            // 顺手把历史版本留下的隐藏记录也清掉，否则原曲会被「藏」在隐藏列表里
            val restored = entries.mapNotNull { it.sourceKey }.toSet()
            prefs.archivedSongs = emptyList()
            prefs.hiddenSongs = prefs.hiddenSongs.filterNot { it.key in restored }
            supersededKeys = emptySet()
            _messages.tryEmit("已清除 $count 个归档文件，释放 ${bytes / (1024 * 1024)} MB")
            refresh()
        }
    }

    // ------------------------------------------------------------ 歌词副本管理

    /**
     * 重新读一遍磁盘上的歌词副本。
     *
     * 目录一时读不出来时 [LyricsStore.list] 返回 null，这里**保持原状** ——
     * 跟归档那次事故是同一类错误：把「读不到」当成「都没有」，用户就会看到
     * 一份本不存在的"全没了"。
     */
    private fun refreshLyricCopies() {
        val titles = allSongs.associate { it.key to it.title }
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                // 自动匹配的缓存槽位没有偏好痕迹，得自己补上，否则它认不回歌曲
                val known = prefs.knownLyricKeys()
                val slots = known + known.map { LyricCopyKeys.AUTO_MATCH_PREFIX + it }
                val fallback: (String?) -> String? = { storedKey ->
                    storedKey?.let { slot ->
                        val base = LyricCopyKeys.baseKeyOf(slot)
                        titles[base] ?: prefs.lyricNameFor(base)
                    }
                }
                lyricsStore.list(slots, fallback)?.let { it to lyricsStore.totalBytes() }
            } ?: return@launch
            val copies = loaded.first
            _state.update {
                it.copy(
                    lyricCopies = copies,
                    // 界面按歌展开管理，归并规则是纯函数（core/LyricCopyGroups）
                    lyricCopyGroups = LyricCopyGroups.group(copies) { key -> titles[key] },
                    lyricCopiesBytes = loaded.second
                )
            }
        }
    }

    /** 打开一份副本的正文预览。 */
    fun previewLyricCopy(entry: LyricCopyEntry) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { lyricsStore.readByFileName(entry.fileName) }
            if (text == null) {
                _messages.tryEmit("这份歌词已经读不到了，可能刚被删掉")
                refreshLyricCopies()
                return@launch
            }
            _state.update {
                it.copy(lyricPreview = LyricCopyPreview(entry.fileName, entry.label, text))
            }
        }
    }

    fun dismissLyricPreview() {
        _state.update { it.copy(lyricPreview = null) }
    }

    /**
     * 删掉一份歌词副本。
     *
     * 删完必须补两步，否则用户会觉得「删了没反应」：
     * 1. 清偏好里的痕迹（`lyric_uri_` / `lyric_name_` / `lyric_origin_`），
     *    不然文件没了、界面上的来源标注还挂着；
     * 2. 正在播这首歌就重新解析歌词，否则页面上还显示着刚删掉的那份。
     *
     * 自动匹配的那份不动偏好 —— 它和「用户选定」是两个槽位，删缓存不该连
     * 用户亲手导入的记录一起抹掉。
     */
    fun deleteLyricCopy(entry: LyricCopyEntry) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { lyricsStore.deleteByFileName(entry.fileName) }
            if (!ok) {
                _messages.tryEmit("删除失败，稍后再试")
                refreshLyricCopies()
                return@launch
            }
            if (!entry.autoMatched) {
                entry.songKey?.let { key ->
                    prefs.setLyricUri(key, null)
                    prefs.setLyricName(key, null)
                    prefs.setLyricOrigin(key, null)
                }
            }
            _state.update {
                it.copy(lyricPreview = it.lyricPreview?.takeIf { p -> p.fileName != entry.fileName })
            }
            val affected = reloadLyricsIfCurrent(entry.songKey)
            refreshLyricCopies()
            _messages.tryEmit(
                if (affected) "已删除这份副本，本首歌改用文件里的歌词" else "已删除 1 份歌词副本"
            )
        }
    }

    /**
     * 删掉一首歌名下的**全部**副本（用户选定的那份 + 联网自动匹配的缓存）。
     *
     * 逐份走 [LyricsStore.deleteByFileName] 而不是按 key 调 remove：组里可能两份都在，
     * 而选定槽位与自动匹配槽位是两个不同的 key，按 key 删很容易漏掉一份，
     * 用户看到「删了这首歌的全部副本」却还剩一行，最是莫名。
     *
     * 偏好里的痕迹只清「确实删掉了的那份」对应的：自动匹配那份本来就不动偏好
     * （它和用户选定是两个槽位），删缓存不该连用户亲手导入的记录一起抹掉。
     */
    fun deleteLyricCopyGroup(group: LyricCopyGroup) {
        if (group.entries.isEmpty()) return
        viewModelScope.launch {
            val gone = withContext(Dispatchers.IO) {
                group.entries.filter { lyricsStore.deleteByFileName(it.fileName) }
            }
            if (gone.isEmpty()) {
                _messages.tryEmit("删除失败，稍后再试")
                refreshLyricCopies()
                return@launch
            }
            if (gone.any { !it.autoMatched }) {
                group.songKey?.let { key ->
                    prefs.setLyricUri(key, null)
                    prefs.setLyricName(key, null)
                    prefs.setLyricOrigin(key, null)
                }
            }
            val goneNames = gone.mapTo(HashSet()) { it.fileName }
            _state.update {
                it.copy(lyricPreview = it.lyricPreview?.takeIf { p -> p.fileName !in goneNames })
            }
            val affected = reloadLyricsIfCurrent(group.songKey)
            refreshLyricCopies()
            _messages.tryEmit(
                if (affected) {
                    "已删除「${group.title}」的 ${gone.size} 份副本，这首歌改用文件里的歌词"
                } else {
                    "已删除「${group.title}」的 ${gone.size} 份歌词副本"
                }
            )
        }
    }

    /** 清空全部副本。播放中的那首歌会跟着重新解析，退回文件内嵌歌词。 */
    fun deleteAllLyricCopies() {
        viewModelScope.launch {
            val known = withContext(Dispatchers.IO) { prefs.knownLyricKeys() }
            val (count, bytes) = withContext(Dispatchers.IO) { lyricsStore.deleteAll() }
            if (count == 0) {
                refreshLyricCopies()
                _messages.tryEmit("没有可删除的歌词副本")
                return@launch
            }
            // 副本都没了，偏好里那些"用户选定"的痕迹就指向空气，一并清干净
            known.forEach { key ->
                prefs.setLyricUri(key, null)
                prefs.setLyricName(key, null)
                prefs.setLyricOrigin(key, null)
            }
            _state.update { it.copy(lyricPreview = null) }
            reloadLyricsIfCurrent(_state.value.currentSong?.key)
            refreshLyricCopies()
            _messages.tryEmit("已删除 $count 份歌词副本，释放 ${bytes / 1024} KB")
        }
    }

    /**
     * 删掉的那份正好是当前播放这首的，就重新解析歌词。返回是否命中。
     *
     * 顺手记下「这首别再自动联网了」：用户刚亲手删掉，转头又给他下回来，很讨嫌。
     */
    private fun reloadLyricsIfCurrent(songKey: String?): Boolean {
        if (songKey == null) return false
        val song = _state.value.currentSong ?: return false
        if (song.key != songKey) return false
        autoFetchTried.add(song.key)
        lyricsRepository.invalidate(song)
        loadLyrics(song, _state.value.durationMs, force = true)
        return true
    }

    // -------------------------------------------------- 酷我加密文件（KWM）

    /**
     * 扫系统媒体库里的 `.kwm`。
     *
     * 这条路的成功率取决于 Android 版本：`.kwm` 不是系统认识的媒体类型，
     * 而 Android 11 起普通应用看不到「别人的非媒体文件」。所以扫不到时**照实说明**，
     * 并指向「指定文件夹」那条路 —— 让用户以为「设备上没有 KWM 文件」是最坏的结果。
     */
    fun scanKwmDevice() {
        if (_state.value.kwmScanning || _state.value.kwmWorking) return
        viewModelScope.launch {
            _state.update { it.copy(kwmScanning = true, kwmScanNote = null) }
            val result = runCatching { kwmScanner.scanMediaStore() }.getOrNull()
            val added = result?.files.orEmpty()
            _state.update { current ->
                current.copy(
                    kwmScanning = false,
                    kwmFiles = KwmFiles.merge(current.kwmFiles, added),
                    kwmDoneKeys = prefs.kwmDone,
                    kwmScanNote = result?.note ?: if (added.isEmpty()) "没有找到 .kwm 文件" else null
                )
            }
            if (added.isNotEmpty()) _messages.tryEmit("扫描到 ${added.size} 个 .kwm 文件")
        }
    }

    /** 记住用户授权的文件夹，并立刻递归扫一遍。 */
    fun scanKwmFolder(treeUri: Uri, displayName: String?) {
        if (_state.value.kwmScanning || _state.value.kwmWorking) return
        kwmScanner.takePersistablePermission(treeUri)
        prefs.kwmFolderUri = treeUri.toString()
        if (!displayName.isNullOrBlank()) prefs.kwmFolderName = displayName

        viewModelScope.launch {
            _state.update {
                it.copy(
                    kwmScanning = true,
                    kwmScanNote = null,
                    kwmFolderName = prefs.kwmFolderName ?: it.kwmFolderName
                )
            }
            val result = runCatching { kwmScanner.scanTree(treeUri) }.getOrNull()
            val added = result?.files.orEmpty()
            _state.update { current ->
                current.copy(
                    kwmScanning = false,
                    kwmFiles = KwmFiles.merge(current.kwmFiles, added),
                    kwmDoneKeys = prefs.kwmDone,
                    kwmScanNote = result?.note
                )
            }
            _messages.tryEmit(
                if (added.isEmpty()) "这个文件夹里没有找到 .kwm 文件" else "扫描到 ${added.size} 个 .kwm 文件"
            )
        }
    }

    /** 重扫上次授权的文件夹。没有授权过就什么都不做，界面会引导用户去授权。 */
    fun rescanKwmFolder() {
        val raw = prefs.kwmFolderUri ?: return
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return
        scanKwmFolder(uri, prefs.kwmFolderName)
    }

    /** 用户手动选中的文件；非 `.kwm` 会被忽略并如实告知。 */
    fun addKwmUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) {
                // 能拿多久拿多久：用户可能先加进列表、过一阵子才点解密
                uris.forEach { kwmScanner.takePersistablePermission(it) }
                uris.mapNotNull { runCatching { kwmScanner.inspect(it) }.getOrNull() }
            }
            val merged = KwmFiles.merge(_state.value.kwmFiles, added)
            _state.update { it.copy(kwmFiles = merged, kwmDoneKeys = prefs.kwmDone, kwmScanNote = null) }
            val skipped = uris.size - added.size
            _messages.tryEmit(
                buildString {
                    append("已加入 ${added.size} 个文件")
                    if (added.isEmpty() && skipped > 0) append("（这些不是 .kwm 文件）")
                }
            )
        }
    }

    /** 从列表里去掉一项。只动列表，不碰磁盘上的文件。 */
    fun removeKwmFile(key: String) {
        _state.update { it.copy(kwmFiles = KwmFiles.remove(it.kwmFiles, setOf(key))) }
    }

    fun clearKwmList() {
        _state.update { it.copy(kwmFiles = emptyList(), kwmDoneKeys = emptySet(), kwmResults = emptyList()) }
    }

    /** 解密单个文件。 */
    fun decryptKwm(file: KwmFile) {
        if (_state.value.kwmWorking) {
            _messages.tryEmit("正在解密，请稍等")
            return
        }
        viewModelScope.launch { runKwmImport(listOf(file)) }
    }

    /**
     * 一键解密全部。
     *
     * 已经解密过的默认跳过 —— 再解一次只会多出一份「歌名 (2).mp3」。
     */
    fun decryptAllKwm() {
        if (_state.value.kwmWorking) return
        val done = prefs.kwmDone
        val targets = _state.value.kwmFiles.filterNot { KwmFiles.doneKeyOf(it) in done }
        if (targets.isEmpty()) {
            _messages.tryEmit(
                if (_state.value.kwmFiles.isEmpty()) "先扫描或选择 .kwm 文件" else "这些文件都已经解密过了"
            )
            return
        }
        viewModelScope.launch { runKwmImport(targets) }
    }

    /** 重解一个已经处理过的文件（用户可能删过副本，想再来一份）。 */
    fun decryptKwmAgain(file: KwmFile) {
        if (_state.value.kwmWorking) return
        viewModelScope.launch { runKwmImport(listOf(file)) }
    }

    fun cancelKwm() {
        if (!_state.value.kwmWorking) return
        kwmCancelled = true
        _messages.tryEmit("正在停止…")
    }

    private suspend fun runKwmImport(targets: List<KwmFile>) {
        kwmCancelled = false
        // 解密出来的音频按**原格式**归档：本版本不再转 MP3 —— 曲库自己的播放器认得
        // FLAC / OGG / M4A，没必要再花一次转码时间去换一份有损副本。
        _state.update {
            it.copy(
                kwmWorking = true,
                kwmDone = 0,
                kwmTotal = targets.size,
                kwmLabel = "",
                kwmResults = emptyList()
            )
        }

        val results = ArrayList<String>()
        var ok = 0
        var failed = 0
        val done = prefs.kwmDone.toMutableSet()

        for (file in targets) {
            if (kwmCancelled) break
            _state.update { it.copy(kwmLabel = file.name) }
            val outcome = try {
                kwmImporter.import(file)
            } catch (_: Throwable) {
                KwmImporter.Outcome(file, false, "解密失败")
            }

            results.add("${file.name}：${outcome.message}")
            if (outcome.ok) {
                ok++
                // 每成功一个就落盘一次：中途被系统杀掉也不会重复解同一份
                done.add(KwmFiles.doneKeyOf(file))
                prefs.kwmDone = done
            } else {
                failed++
            }
            _state.update {
                it.copy(
                    kwmDone = it.kwmDone + 1,
                    kwmDoneKeys = done.toSet(),
                    kwmResults = results.toList()
                )
            }
        }

        _state.update { it.copy(kwmWorking = false, kwmLabel = "") }
        _messages.tryEmit(
            buildString {
                append("解密完成：成功 $ok 首")
                if (failed > 0) append("，失败 $failed 个")
                if (kwmCancelled) append("（已取消）")
                append(" · 已自动归档到 App 音乐库")
            }
        )
        // 归档记录变了，立刻重扫一遍，新解出来的歌马上出现在曲库
        refresh()
    }

    // ------------------------------------------------------------------ 播放控制

    fun playAll(songs: List<Song>, startIndex: Int) {
        if (songs.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, songs.lastIndex)
        songs.forEach { songCache[it.key] = it }
        withController { c ->
            c.setMediaItems(songs.map(::mediaItemOf), safeIndex, 0L)
            c.prepare()
            c.play()
        }
        val song = songs[safeIndex]
        _state.update {
            it.copy(
                queue = songs,
                currentIndex = safeIndex,
                currentSong = song,
                durationMs = song.durationMs,
                isPlaying = true,
                playbackError = null
            )
        }
        _position.value = 0L
        prefs.lastSongKey = song.key
        loadLyrics(song, song.durationMs)
    }

    /** 点击曲库中的某一首：把当前列表整体作为队列，从该首开始。 */
    fun playFromLibrary(song: Song) {
        val list = _state.value.filtered.ifEmpty { _state.value.songs }
        val index = list.indexOfFirst { it.key == song.key }
        if (index >= 0) playAll(list, index) else playAll(listOf(song), 0)
    }

    fun togglePlayPause() {
        withController { c ->
            if (c.isPlaying) c.pause() else c.play()
        }
        syncFromController()
    }

    /**
     * 下一首 / 上一首（上下滑切歌与两个切歌键都走这里）。
     *
     * 先把 `_position` 归零再下发命令：通知播放服务的 IPC 是异步的，
     * `onMediaItemTransition` 要等一个来回才回来。不等它，界面上的进度条
     * 立刻回到 0，用户看不到「上一首的位置」残留。
     */
    fun next() {
        _position.value = 0L
        withController { it.seekToNextMediaItem() }
    }

    fun previous() {
        _position.value = 0L
        withController { it.seekToPreviousMediaItem() }
    }

    fun seekTo(positionMs: Long) {
        val max = _state.value.durationMs
        val target = if (max > 0) positionMs.coerceIn(0L, max) else positionMs.coerceAtLeast(0L)
        withController { it.seekTo(target) }
        _position.value = target
    }

    /**
     * 切换播放模式：顺序播放 → 列表循环 → 单曲循环 → 随机播放 → 回到顺序。
     *
     * 只有一个按钮，`state.playMode` 永远等于「当前真实生效的模式」，
     * 图标即状态，不存在「看起来开着其实没生效」的组合。
     */
    fun cyclePlayMode() {
        val next = _state.value.playMode.next
        appPlayMode = next
        withController { applyPlayMode(it, next) }
        prefs.playMode = next
        _state.update { it.copy(playMode = next) }
        _messages.tryEmit(next.label)
    }

    /** 把一首歌插到当前曲目之后。 */
    fun playNext(song: Song) {
        songCache[song.key] = song
        withController { c ->
            val index = c.currentMediaItemIndex
            if (index >= 0 && index + 1 <= c.mediaItemCount) {
                c.addMediaItem(index + 1, mediaItemOf(song))
            } else {
                c.addMediaItem(mediaItemOf(song))
            }
        }
        // 队列由控制器时间线反推，这里不再自己往列表里插 —— 两份列表各插一次
        // 迟早会错位（随机播放时插进去的下标根本不是"下一首"在界面上的位置）
        syncFromController()
        _messages.tryEmit("已添加到下一首播放")
    }

    fun removeFromQueue(index: Int) {
        val queue = _state.value.queue
        if (index !in queue.indices) return
        val song = queue[index]
        withController { c ->
            // 用媒体 id 定位，避免下标在乱序播放时对不上
            val mediaIndex = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == song.key }
            if (mediaIndex != null) c.removeMediaItem(mediaIndex)
        }
        // 本地先挪一次让界面立刻跟上，然后从控制器重新对齐下标：
        // 删掉当前曲目前面的某一首时，播放器的下标已经往前挪了，不重算就会整片高亮错位
        _state.update { it.copy(queue = queue.toMutableList().also { q -> q.removeAt(index) }) }
        syncFromController()
        _messages.tryEmit("已从队列移除「${song.title}」")
    }

    /**
     * 把队列里的某一首挪到另一个位置（队列页的「上移 / 下移」）。
     *
     * 下标能直接用，是因为队列就是控制器的时间线（见 [deriveQueue]）。
     * Media3 的 `moveMediaItem` 与 Kotlin 的 `removeAt` + `add(to, …)` 语义一致：
     * 都是先摘出来再插到 `to`。
     */
    fun moveInQueue(from: Int, to: Int) {
        val queue = _state.value.queue
        if (from == to || from !in queue.indices || to !in queue.indices) return
        withController { c ->
            if (from < c.mediaItemCount && to < c.mediaItemCount) c.moveMediaItem(from, to)
        }
        _state.update {
            it.copy(queue = queue.toMutableList().apply { add(to, removeAt(from)) })
        }
        syncFromController()
    }

    fun clearQueue() {
        withController { c ->
            c.stop()
            c.clearMediaItems()
        }
        songCache.clear()
        _state.update { it.copy(queue = emptyList(), currentIndex = -1, currentSong = null, isPlaying = false) }
        _position.value = 0L
    }

    // ------------------------------------------------------------------ 歌词

    fun reloadLyrics() {
        val song = _state.value.currentSong ?: return
        // 手动重新解析时允许再试一次自动联网匹配
        autoFetchTried.remove(song.key)
        lyricsRepository.invalidate(song)
        loadLyrics(song, _state.value.durationMs, force = true)
    }

    fun importLyricsFile(uri: Uri) {
        val song = _state.value.currentSong
        if (song == null) {
            _messages.tryEmit("请先播放一首歌")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(lyricsLoading = true) }
            val lyrics = lyricsRepository.importLyricsFor(song, uri, _state.value.durationMs)
            if (lyrics == null) {
                _state.update { it.copy(lyricsLoading = false) }
                _messages.tryEmit("这个文件里没有解析到歌词")
            } else {
                publishLyrics(song, lyrics)
                _messages.tryEmit("已导入歌词，已保存到 App 内（重开也不会丢）")
            }
        }
    }

    // ------------------------------------------------------------------ 联网取歌词

    /**
     * 手动联网获取歌词：先搜候选，再让用户确认。
     *
     * 不直接取第一条 —— 本地文件的标题与歌手信息经常是不准的（网上下载的 mp3 尤其），
     * 自动匹配有猜错的可能。既然用户已经开口要了，就给他一次纠错的机会。
     */
    fun fetchLyricsOnline() {
        val song = _state.value.currentSong
        if (song == null) {
            _messages.tryEmit("请先播放一首歌")
            return
        }
        // 两家来源都被关掉时，搜索必然返回空列表，最后弹出的是"没搜到这首歌" ——
        // 那句话会把用户引到"是不是我的歌名不对"上去，而真正的原因在设置里
        if (_state.value.lyricProviders.isEmpty()) {
            _messages.tryEmit("在线歌词来源都关掉了，去「设置 → 歌词」里打开网易云或 LRCLIB")
            return
        }
        if (_state.value.onlineSearching) return
        viewModelScope.launch {
            _state.update { it.copy(onlineSearching = true, onlineCandidates = emptyList()) }
            val candidates = runCatching { lyricsRepository.searchOnline(song) }.getOrDefault(emptyList())
            _state.update { it.copy(onlineSearching = false, onlineCandidates = candidates) }
            if (candidates.isEmpty()) {
                _messages.tryEmit("没搜到「${song.title}」，检查一下网络或换个文件名")
            }
        }
    }

    /** 用户在候选列表里选定了某一首。 */
    fun applyOnlineCandidate(candidate: OnlineSong) {
        val song = _state.value.currentSong ?: return
        viewModelScope.launch {
            _state.update { it.copy(onlineSearching = true, onlineCandidates = emptyList()) }
            val lyrics = runCatching {
                lyricsRepository.applyOnlinePick(song, candidate, _state.value.durationMs)
            }.getOrNull()
            _state.update { it.copy(onlineSearching = false) }
            if (lyrics == null) {
                _messages.tryEmit("这首的歌词没取到，换一个候选试试")
            } else {
                autoFetchTried.add(song.key)
                publishLyrics(song, lyrics)
                _messages.tryEmit("已获取歌词：${candidate.title}")
            }
        }
    }

    fun dismissOnlinePicker() {
        _state.update { it.copy(onlineCandidates = emptyList()) }
    }

    fun setAutoFetchLyrics(enabled: Boolean) {
        prefs.autoFetchLyrics = enabled
        _state.update { it.copy(autoFetchLyrics = enabled) }
        _messages.tryEmit(if (enabled) "已开启：没有本地歌词时自动联网匹配" else "已关闭自动联网获取歌词")
    }

    /**
     * 歌词来源开关：只能关到"一家不剩"之外的任意组合。
     *
     * 两家都关掉是允许的（等于停用联网歌词），但要在提示里说清后果 ——
     * 用户关第二家时多半以为只是"少一个来源"，其实是整个功能没了。
     */
    fun setLyricProviderEnabled(provider: LyricProvider, enabled: Boolean) {
        val current = _state.value.lyricProviders
        val next = if (enabled) current + provider else current - provider
        if (next == current) return
        prefs.lyricProviders = next
        _state.update { it.copy(lyricProviders = next) }
        _messages.tryEmit(
            when {
                next.isEmpty() -> "两家来源都关了，联网获取歌词已停用"
                enabled -> "已开启：联网取词会用${provider.label}"
                else -> "已关闭：联网取词不再使用${provider.label}"
            }
        )
    }

    /** 封面匹配的评分门槛（拖动滑块时不要每条都发提示，会把提示条刷屏）。 */
    fun setCoverMinScore(score: Int) {
        val clamped = score.coerceIn(ITunesApi.MIN_SCORE_FLOOR, ITunesApi.MAX_SCORE)
        if (clamped == _state.value.coverMinScore) return
        prefs.coverMinScore = clamped
        _state.update { it.copy(coverMinScore = clamped) }
    }

    fun setCoverRegionCustom(enabled: Boolean) {
        if (enabled == _state.value.coverRegionCustom) return
        prefs.coverRegionCustom = enabled
        _state.update { it.copy(coverRegionCustom = enabled) }
        _messages.tryEmit(
            if (enabled) {
                "已开启自定义地区：按「${_state.value.coverRegions.joinToString("、") { it.label }}」依次搜"
            } else {
                "已改回默认地区顺序（中国台湾 → 中国香港 → 美国）"
            }
        )
    }

    /**
     * 勾选 / 取消一个搜索地区。
     *
     * 两个边界都要拦在界面之外（而不是让它静默变成别的行为）：
     * 一个都不选 → 哪个地区都不搜，等于把取封面关掉；选太多 → 每次搜索的请求数
     * 成倍增长，批量补齐会慢到不可用（接口还有限流）。
     */
    fun toggleCoverRegion(region: CoverRegion) {
        val current = _state.value.coverRegions
        val selecting = region !in current
        if (selecting && current.size >= CoverRegion.MAX_SELECTED) {
            _messages.tryEmit("最多同时选 ${CoverRegion.MAX_SELECTED} 个地区，先取消一个")
            return
        }
        val next = if (selecting) current + region else current - region
        if (next.isEmpty()) {
            _messages.tryEmit("至少保留一个地区，否则哪个区都搜不到")
            return
        }
        prefs.coverRegions = next
        _state.update { it.copy(coverRegions = next) }
    }

    /** 播放页封面上「上下滑动切歌」的总开关，立即生效。 */
    fun setSwipeSwitchSong(enabled: Boolean) {
        prefs.swipeSwitchSong = enabled
        _state.update { it.copy(swipeSwitchSong = enabled) }
    }

    /** 歌词字号走一挡（delta 只取 +1 / -1）。到端点就停住，界面据此把按钮变淡。 */
    fun setLyricTextSize(size: LyricTextSize) {
        if (_state.value.lyricTextSize == size) return
        prefs.lyricTextSize = size
        _state.update { it.copy(lyricTextSize = size) }
        // 回一条提示当作"当前是哪一挡"的确认：歌词页那个 A− / A＋ 上只放得下一个符号，
        // 放不下档位名，而正文的即时变化在小字号下未必一眼看得出差别
        _messages.tryEmit("歌词字号：${size.label}")
    }

    fun setArtworkShape(shape: ArtworkShape) {
        if (_state.value.artworkShape == shape) return
        prefs.artworkShape = shape
        _state.update { it.copy(artworkShape = shape) }
        _messages.tryEmit("封面形状：${shape.label}")
    }

    fun clearImportedLyrics() {
        val song = _state.value.currentSong ?: return
        autoFetchTried.remove(song.key)
        viewModelScope.launch {
            val lyrics = lyricsRepository.clearImportedLyrics(song, _state.value.durationMs)
            _state.update {
                it.copy(
                    lyrics = lyrics,
                    lyricsImported = false,
                    lyricsImportedName = null
                )
            }
            refreshLyricCopies()
            _messages.tryEmit("已清除选定的歌词，恢复使用文件里的那份")
        }
    }

    private fun loadLyrics(song: Song, durationMs: Long, force: Boolean = false) {
        lyricsJob?.cancel()
        val current = if (force) Lyrics.NONE else _state.value.lyrics
        _state.update { it.copy(lyrics = if (force) Lyrics.NONE else current, lyricsLoading = true) }
        lyricsJob = viewModelScope.launch {
            val lyrics = lyricsRepository.load(song, durationMs, force)
            if (_state.value.currentSong?.key != song.key) return@launch
            publishLyrics(song, lyrics)
            if (!lyrics.isUsable) autoFetchOnlineIfEnabled(song, durationMs)
        }
    }

    /** 本地一点歌词都没有时才联网 —— 有内嵌歌词就不该被网络结果顶掉。 */
    private suspend fun autoFetchOnlineIfEnabled(song: Song, durationMs: Long) {
        if (!_state.value.autoFetchLyrics) return
        if (!autoFetchTried.add(song.key)) return
        _state.update { if (it.currentSong?.key == song.key) it.copy(onlineSearching = true) else it }
        val fetched = runCatching { lyricsRepository.autoFetchOnline(song, durationMs) }.getOrNull()
        if (_state.value.currentSong?.key != song.key) return
        _state.update { it.copy(onlineSearching = false) }
        if (fetched != null) {
            publishLyrics(song, fetched)
            _messages.tryEmit("已从网络匹配到歌词")
        }
    }

    /** 歌词落地到界面状态的唯一出口，避免各处漏更新 `lyricsImported` 之类的伴生字段。 */
    private fun publishLyrics(song: Song, lyrics: Lyrics) {
        _state.update {
            it.copy(
                lyrics = lyrics,
                lyricsLoading = false,
                lyricsImported = lyricsRepository.hasImportedLyrics(song),
                lyricsImportedName = lyricsRepository.importedNameOf(song)
            )
        }
        // 导入/联网取词都会在这里留一份副本，列表要跟着更新
        refreshLyricCopies()
    }

    // ------------------------------------------------------------ 在线专辑封面

    /**
     * 让当前这首歌的封面就位（自动，跟着切歌走）。
     *
     * 已经取到过的不会联网，但**仍然要把封面补进当前的 MediaItem**：播放列表是在
     * "切到这首歌"之前就整体建好的，那时这首歌还没有封面；不补的话通知栏/锁屏
     * 会一直显示上一首的封面。
     */
    private fun ensureCover(song: Song, force: Boolean = false) {
        // 已经有记录（取到过或查过没有）就直接返回：这首歌的 artworkUri 在建队列时
        // 就已经带上了，通知的大图由 Media3 的 BitmapLoader **异步**加载，加载完自己重画 ——
        // 不需要也不应该对"正在播放的那一条"做 replaceMediaItem：它会触发一次重新准备，
        // 期间 isPlaying 短暂为 false，通知跟着重画，状态就可能停在错误的一帧上（真机实证）
        val existing = covers.entry(song.key)
        if (!force && existing != null) return
        fetchCoverInternal(song, force)
    }

    private fun fetchCoverInternal(song: Song, force: Boolean) {
        // 同一首可能被连着点两次（或自动 + 手动撞上），别并发拉同一张图
        if (coverJobs[song.key]?.isActive == true) {
            if (force) _messages.tryEmit("正在获取「${song.title}」的封面…")
            return
        }
        coverJobs[song.key] = viewModelScope.launch {
            val result = covers.ensure(song, force) { stage ->
                _state.update { it.copy(coverStage = stage.label) }
            }
            _state.update { it.copy(coverStage = "") }
            when (result) {
                CoverResult.Fetched -> {
                    // 只有"这次真的拿到了新封面"才值得 replace 当前条目 ——
                    // 用户主动动作，一次性代价；日常切歌绝不做这个动作
                    applyCoverToCurrent(song)
                    refreshCoverStats()
                    if (force) _messages.tryEmit("已获取封面：${song.title}${albumSuffix(song)}")
                }

                CoverResult.NoMatch -> {
                    refreshCoverStats()
                    if (force) _messages.tryEmit("没找到「${song.title}」的封面，可能曲库里没有这首")
                }

                CoverResult.Failed -> if (force) _messages.tryEmit("封面获取失败，检查网络后重试")

                // 关掉自动获取时手动点，是被 force 放行的，不该再提示"已关闭"
                CoverResult.Disabled -> Unit
                // 缓存命中：artworkUri 本来就在 MediaItem 上，通知自己会异步加载，什么都不用做
                CoverResult.Cached -> refreshCoverStats()
            }
            coverJobs.remove(song.key)
        }
    }

    /**
     * 封面变了之后，把这首歌在**队列里那条** MediaItem 的元数据刷一遍。
     *
     * 之前只刷"正在播放的那一条"，给曲库里**别的歌**换封面时，队列里那条的
     * artworkUri 还是旧地址 —— 等它哪天被切到，通知栏就回到旧封面。非当前条目
     * 的 replace 不会打扰播放；当前条目会触发一次重新准备，这是用户主动换封面
     * 的一次性代价（真机实证过：日常切歌绝不能做这个动作，会把通知状态顶错帧）。
     */
    private fun applyCoverToCurrent(song: Song) {
        republishSong(song)
        notifyCoverChanged()
    }

    private fun republishSong(song: Song) {
        val c = controller ?: return
        val index = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == song.key }
            ?: return
        // 每次都用**新构造**的 MediaItem：传回同一个实例可能被判成"没变化"而不发事件，
        // 那就等于什么都没刷新。artworkData 体积大，只塞给正在播的那条。
        val isCurrent = index == c.currentMediaItemIndex
        c.replaceMediaItem(index, mediaItemOf(song, withArtworkData = isCurrent))
    }

    /**
     * 为整个曲库补齐封面（设置页里手动触发）。
     *
     * 串行 + 客户端自己的限流（≈20 次/分钟）会让几百首歌跑很久，所以：
     *  - 已经有封面、或已经有"查过没匹配上"记录的直接跳过，不占额度；
     *  - 进度实时显示在处理状态里，用户看得到在动；
     *  - 可以中途取消（[cancelCoverBackfill]），已经取到的都保留。
     */
    fun backfillCovers() {
        if (_state.value.coverWorking) {
            _messages.tryEmit("正在补齐封面，请稍等")
            return
        }
        val targets = _state.value.songs.filter { covers.entry(it.key) == null }
        if (targets.isEmpty()) {
            _messages.tryEmit("曲库里的封面都已经处理过了")
            return
        }
        coverCancelled = false
        viewModelScope.launch {
            // 总数一开始就知道，所以进度条给确定值；分类计数让用户看得出"卡在哪一类"
            _state.update {
                it.copy(
                    coverWorking = true,
                    coverTotal = targets.size,
                    coverDone = 0,
                    coverFetched = 0,
                    coverNoMatch = 0,
                    coverFailed = 0,
                    coverLabel = "",
                    coverStage = CoverStage.SEARCH.label
                )
            }
            var fetched = 0
            var noMatch = 0
            var failed = 0
            for ((done, song) in targets.withIndex()) {
                if (coverCancelled) break
                _state.update { it.copy(coverLabel = song.title, coverDone = done) }
                // 批量是无人值守的，自动取候选里的第一张（相关性排序的头名），
                // 不再用打分自作主张；用户对哪张不满意可以在那首歌上自选覆盖
                val result = covers.applyFirstCandidate(song) { stage ->
                    // 回调来自 IO 线程，但 StateFlow 本身是线程安全的
                    _state.update { it.copy(coverStage = stage.label) }
                }
                when (result) {
                    CoverResult.Fetched -> fetched++
                    CoverResult.NoMatch -> noMatch++
                    CoverResult.Failed -> failed++
                    else -> Unit
                }
                _state.update {
                    it.copy(
                        coverDone = done + 1,
                        coverFetched = fetched,
                        coverNoMatch = noMatch,
                        coverFailed = failed
                    )
                }
                // 一首刚取到封面、正好是当前播放的这首，就把界面/通知也一起更新
                if (_state.value.currentSong?.key == song.key) applyCoverToCurrent(song)
            }
            _state.update {
                it.copy(
                    coverWorking = false,
                    coverLabel = "",
                    coverStage = "",
                    coverDone = if (coverCancelled) it.coverDone else targets.size
                )
            }
            refreshCoverStats()
            val prefix = if (coverCancelled) "已停止，本次" else "完成："
            _messages.tryEmit(
                "$prefix 新获取 $fetched 张" +
                    (if (noMatch > 0) "，$noMatch 首没找到" else "") +
                    (if (failed > 0) "，$failed 首获取失败" else "") +
                    "（共 ${targets.size} 首）"
            )
        }
    }

    fun cancelCoverBackfill() {
        if (!_state.value.coverWorking) return
        coverCancelled = true
        _messages.tryEmit("正在停止…")
    }

    fun clearCoverCache() {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { covers.clear() }
            refreshCoverStats()
            notifyCoverChanged()
            _messages.tryEmit("已清除封面缓存，释放 ${bytes / 1024} KB")
        }
    }

    fun setAutoFetchCovers(enabled: Boolean) {
        prefs.autoFetchCovers = enabled
        _state.update { it.copy(autoFetchCovers = enabled) }
        _messages.tryEmit(if (enabled) "已开启：没有封面时自动联网匹配" else "已关闭自动获取封面")
    }

    private fun refreshCoverStats() {
        val (count, bytes) = covers.stats()
        _state.update { it.copy(coverCount = count, coverBytes = bytes) }
    }

    // ------------------------------------------------------ 专辑封面：用户自选

    /** 曲库长按菜单的「选择专辑封面」：先确认给哪首歌挑，再选来源。 */
    fun beginCoverPick(song: Song) {
        _state.update {
            it.copy(
                coverPickTarget = song,
                coverPickHasExisting = covers.entry(song.key)?.matched == true,
                coverCandidates = emptyList()
            )
        }
    }

    /** 播放页菜单的「选择专辑封面」：作用在正在播放的这一首上。 */
    fun beginCoverPickForCurrent() {
        val song = _state.value.currentSong
        if (song == null) {
            _messages.tryEmit("请先播放一首歌")
            return
        }
        beginCoverPick(song)
    }

    fun dismissCoverPick() {
        _state.update { it.copy(coverPickTarget = null, coverCandidates = emptyList()) }
    }

    /**
     * 在线搜索封面候选。与歌词同一套交互：**不自动替用户做主**，
     * 把搜到的版本合并去重后全部列出来，用户自己挑 —— 自动匹配只该是兜底，
     * 人在场时选择权应该在人手里（贴错封面比没有封面更让人困惑）。
     */
    fun searchCoverCandidates() {
        val song = _state.value.coverPickTarget ?: return
        if (_state.value.coverSearching) return
        viewModelScope.launch {
            // 搜索这一路也会被限流/慢网卡住十几秒，把阶段写出来至少让用户知道不是死了
            _state.update { it.copy(coverSearching = true, coverStage = CoverStage.SEARCH.label) }
            val keyword = TextMatch.searchKeyword(song.title, song.artist)
            val candidates = if (keyword.isBlank()) {
                emptyList()
            } else {
                runCatching { covers.searchCandidates(keyword) }.getOrDefault(emptyList())
            }
            _state.update {
                it.copy(coverSearching = false, coverCandidates = candidates, coverStage = "")
            }
            if (candidates.isEmpty()) {
                _messages.tryEmit("没搜到「${song.title}」的封面，可以从相册选一张")
            }
        }
    }

    /** 用户在候选列表里选定了一张。 */
    fun applyCoverCandidate(hit: ITunesHit) {
        val song = _state.value.coverPickTarget ?: return
        viewModelScope.launch {
            _state.update { it.copy(coverSearching = true, coverStage = CoverStage.DOWNLOAD.label) }
            val ok = runCatching {
                covers.applyCandidate(song, hit) { stage ->
                    _state.update { it.copy(coverStage = stage.label) }
                }
            }.getOrDefault(false)
            _state.update { it.copy(coverSearching = false, coverStage = "") }
            if (ok) {
                dismissCoverPick()
                applyCoverToCurrent(song)
                refreshCoverStats()
                _messages.tryEmit("已设置封面：${hit.title}")
            } else {
                _messages.tryEmit("这张封面下载失败了，换一张试试")
            }
        }
    }

    /**
     * 用户从相册挑的自定义封面。
     *
     * 自定义封面没有 http 原地址（`artworkUri` 退回本地 `file://`），
     * 但通知/锁屏的大图是由**本进程**的 BitmapLoader 从媒体元数据解码后
     * 直接画进通知的，不依赖别的进程能读这个文件 —— 显示不受影响。
     */
    fun setCustomCover(uri: Uri) {
        val song = _state.value.coverPickTarget ?: return
        viewModelScope.launch {
            _state.update { it.copy(coverSearching = true) }
            val ok = runCatching { covers.saveCustom(song, uri) }.getOrDefault(false)
            _state.update { it.copy(coverSearching = false) }
            if (ok) {
                dismissCoverPick()
                applyCoverToCurrent(song)
                refreshCoverStats()
                _messages.tryEmit("已设置自定义封面：${song.title}")
            } else {
                _messages.tryEmit("这张图读不出来，换一张试试")
            }
        }
    }

    /** 移除这首歌的封面（联网取的和自定义的都算），退回内嵌封面或渐变占位。 */
    fun removeCover() {
        val song = _state.value.coverPickTarget ?: return
        covers.remove(song)
        dismissCoverPick()
        // 队列里那条 MediaItem 上的旧封面一并撤掉；用户主动动作，值得一次 replace
        republishSong(song)
        notifyCoverChanged()
        refreshCoverStats()
        _messages.tryEmit("已移除封面：${song.title}")
    }

    /** 候选列表的小图（100×100，图片 CDN 与 Search API 不是一套限流）。 */
    suspend fun loadCoverThumb(hit: ITunesHit): Bitmap? = covers.thumbFor(hit)

    /** 「已获取封面：《歌手》」里的那截歌手名，没有就不拼。 */
    private fun albumSuffix(song: Song): String =
        song.artist?.takeIf { it.isNotBlank() && it != "<unknown>" }?.let { " · $it" }.orEmpty()

    // ------------------------------------------------------ 编辑歌曲信息

    /**
     * 曲库长按菜单的「编辑歌曲信息」：改歌名 / 歌手 / 专辑。
     *
     * 只改 App 内的显示与匹配（MediaStore 不动）：文件名、MediaStore 记录都保持原样，
     * 所以编辑永远可以一键撤销 —— 撤销数据就存在编辑记录自己身上。
     */
    fun beginEditSong(song: Song) {
        _state.update {
            it.copy(editTarget = song, editHasOriginal = songEdits.containsKey(song.key))
        }
    }

    /** 播放页菜单的「编辑歌曲信息」：作用在正在播放的这一首上。 */
    fun beginEditSongForCurrent() {
        val song = _state.value.currentSong ?: run {
            _messages.tryEmit("请先播放一首歌")
            return
        }
        beginEditSong(song)
    }

    fun dismissEditSong() {
        _state.update { it.copy(editTarget = null) }
    }

    /** 保存编辑。歌名必填（空歌名没法在列表里辨认），歌手/专辑可以为空。 */
    fun applySongEdit(title: String, artist: String, album: String) {
        val song = _state.value.editTarget ?: return
        if (title.isBlank()) {
            _messages.tryEmit("歌名不能为空")
            return
        }
        viewModelScope.launch {
            val edit = SongEdit.of(song, title, artist, album)
            songEdits = songEdits + (song.key to edit)
            prefs.songEdits = songEdits
            updateSongEverywhere(song.key, edit.appliedTo(song))
            dismissEditSong()
            _messages.tryEmit("已更新歌曲信息")
        }
    }

    /** 撤销这首歌的编辑，恢复成文件标签里的原值。 */
    fun resetSongEdit() {
        val song = _state.value.editTarget ?: return
        val edit = songEdits[song.key] ?: return
        viewModelScope.launch {
            songEdits = SongEdits.without(songEdits, song.key)
            prefs.songEdits = songEdits
            updateSongEverywhere(song.key, edit.restored(song))
            dismissEditSong()
            _messages.tryEmit("已恢复原始信息")
        }
    }

    /**
     * 改完信息后把这首歌在**所有出现的地方**一起换掉：曲库列表、搜索结果、
     * 播放队列、迷你条/播放页的当前曲目，以及通知栏的元数据。
     *
     * 正在播的这首还要把歌词重解析一遍 —— 歌名/歌手往往正是为了修正联网匹配
     * 才改的，不改的话它还挂着用旧名字匹配到的那份（可能根本是错的歌）。
     */
    private fun updateSongEverywhere(key: String, edited: Song) {
        allSongs = allSongs.map { if (it.key == key) edited else it }
        applyLibrary()
        _state.update { current ->
            current.copy(
                queue = current.queue.map { if (it.key == key) edited else it },
                currentSong = current.currentSong?.takeIf { it.key == key }?.let { edited }
                    ?: current.currentSong
            )
        }
        // 通知栏/锁屏的标题、歌手、专辑跟着新元数据重画（replace 是用户主动动作，一次性代价）
        republishSong(edited)
        if (_state.value.currentSong?.key == key) {
            autoFetchTried.remove(key)
            lyricsRepository.invalidate(edited)
            loadLyrics(edited, _state.value.durationMs, force = true)
        }
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 构造播放用的 [MediaItem]。
     *
     * 封面分两个字段给，是有意的取舍：
     *  - `artworkUri` **每一首都给**（只是一个字符串）。它同时也是系统侧（锁屏、
     *    原子随身听）读封面的入口，所以优先给 iTunes 的 http 原地址 ——
     *    App 私有目录里的 `file://` 别的进程读不到。
     *  - `artworkData` **只给当前播放的那一首**（[withArtworkData]）。理由是体积：
     *    整个播放列表每人带一份几百 KB 的字节数组，两千首歌就是上百 MB 常驻内存，
     *    而且每次 `setMediaItems` 都要把这些数据过一次 Binder。
     *    Media3 会优先用 artworkData（同步、离线也能画），没有才回头去加载 artworkUri。
     */
    private fun mediaItemOf(song: Song, withArtworkData: Boolean = false): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artistOrUnknown)
            .setAlbumTitle(song.albumOrUnknown)
            .setIsBrowsable(false)
            .setIsPlayable(true)

        covers.artworkUri(song.key)?.let { metadata.setArtworkUri(it) }
            ?: BrandArtwork.ensure(app)?.let { metadata.setArtworkUri(it) }
        if (withArtworkData) {
            covers.artworkData(song.key)?.let {
                metadata.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            }
        }

        return MediaItem.Builder()
            .setUri(song.uri)
            .setMediaId(song.key)
            .setMediaMetadata(metadata.build())
            .build()
    }

    override fun onCleared() {
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        super.onCleared()
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PlayerViewModel(app) as T
    }

    private companion object {
        const val POSITION_POLL_MS = 250L
    }
}
