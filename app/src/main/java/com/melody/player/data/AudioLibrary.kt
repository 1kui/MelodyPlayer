package com.melody.player.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import com.melody.player.core.AudioFiles
import com.melody.player.core.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地音乐来源：
 *  1. MediaStore 扫描 —— 系统媒体库里的音频（主要来源）
 *  2. SAF 导入 —— 用户通过系统文件选择器手动挑的文件，URI 记在偏好里
 */
class AudioLibrary(private val context: Context) {

    fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, requiredPermission()) == PackageManager.PERMISSION_GRANTED

    /** 扫描系统媒体库。整个过程在 IO 线程，且对 SecurityException 做了兜底。 */
    suspend fun scanDevice(): List<Song> = withContext(Dispatchers.IO) {
        if (!hasAudioPermission()) return@withContext emptyList()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATA
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val songs = ArrayList<Song>()

        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val duration = if (durationCol >= 0) cursor.getLong(durationCol) else 0L
                    // 过滤掉短提示音与明显无效的条目
                    if (duration in 1..1500) continue
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    songs.add(
                        Song(
                            id = id,
                            uri = uri.toString(),
                            title = cursor.stringOr(titleCol)?.takeIf { it.isNotBlank() } ?: "未命名曲目",
                            artist = cursor.stringOr(artistCol),
                            album = cursor.stringOr(albumCol),
                            durationMs = duration,
                            sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L,
                            dateAddedSec = if (dateCol >= 0) cursor.getLong(dateCol) else 0L,
                            displayName = cursor.stringOr(nameCol),
                            mimeType = cursor.stringOr(mimeCol),
                            filePath = cursor.stringOr(dataCol)
                        )
                    )
                }
            }
        }.onFailure { /* 权限被撤销或媒体库不可用：返回已收集到的部分 */ }

        songs
    }

    /** 读取用户在偏好里保存的 SAF 音频 URI。 */
    suspend fun loadImported(uris: List<String>): List<Song> = withContext(Dispatchers.IO) {
        uris.mapNotNull { raw -> runCatching { inspectSafUri(Uri.parse(raw)) }.getOrNull() }
    }

    fun inspectSafUri(uri: Uri): Song? {
        val resolver = context.contentResolver

        var displayName: String? = null
        var size = 0L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val nameCol = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeCol = c.getColumnIndex(OpenableColumns.SIZE)
                        if (nameCol >= 0) displayName = c.getString(nameCol)
                        if (sizeCol >= 0) size = c.getLong(sizeCol)
                    }
                }
        }

        // 时长与标签只能靠 MediaMetadataRetriever 读，SAF 的 query 给不了
        var duration = 0L
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var mime: String? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
        } catch (_: Throwable) {
            // 读不出来也继续，用文件名兜底
        } finally {
            runCatching { retriever.release() }
        }

        val name = displayName ?: uri.lastPathSegment ?: return null
        return Song(
            id = -1L,
            uri = uri.toString(),
            title = title?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.'),
            artist = artist,
            album = album,
            durationMs = duration,
            sizeBytes = size,
            dateAddedSec = System.currentTimeMillis() / 1000L,
            displayName = name,
            mimeType = mime,
            filePath = null,
            imported = true
        )
    }

    /** 让 SAF 授权在重启后依然有效。 */
    fun takePersistablePermission(uris: List<Uri>) {
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    /** 让「限定扫描文件夹」的目录树授权在重启后依然有效。 */
    fun takePersistableTreePermission(treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    /** 目录树授权是否还在（用户可能通过系统设置撤销了）。 */
    fun hasTreePermission(treeUri: Uri): Boolean =
        runCatching {
            context.contentResolver.persistedUriPermissions.any {
                it.uri == treeUri && it.isReadPermission
            }
        }.getOrDefault(false)

    /**
     * 只扫用户指定的那一个文件夹（SAF 目录树），递归到子目录。
     *
     * 为什么不用 MediaStore 过滤而是整棵树自己走：MediaStore 的 `DATA` 列
     * 在 Android 10 之后被收紧，路径经常读不到，按前缀筛会**静悄悄地扫不全** ——
     * 用户看到的是"我的歌怎么少了一半"，而界面上没有任何地方能解释这件事。
     * 走目录树是唯一能确定"就是这一个文件夹里的东西"的做法。
     *
     * 代价是每个文件都要开一次 [MediaMetadataRetriever] 读标签，比查一次数据库慢得多；
     * 但限定文件夹本身就是用户挑过的小集合，这个代价可以接受。
     */
    suspend fun scanTreeAudio(treeUri: Uri, onProgress: (Int) -> Unit = {}): List<Song> =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val songs = ArrayList<Song>()
            // 目录树理论上可能成环（软链接 / 提供器实现异常），记一遍走过的 docId
            val visited = HashSet<String>()

            fun walk(parentDocId: String, depth: Int) {
                if (depth > MAX_TREE_DEPTH) return
                if (!visited.add(parentDocId)) return
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
                val cursor = runCatching {
                    resolver.query(
                        childrenUri,
                        TREE_PROJECTION,
                        null,
                        null,
                        null
                    )
                }.getOrNull() ?: return

                cursor.use { c ->
                    val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    if (idCol < 0) return@use

                    while (c.moveToNext()) {
                        val docId = c.getString(idCol) ?: continue
                        val name = (if (nameCol >= 0) c.getString(nameCol) else null).orEmpty()
                        val mime = if (mimeCol >= 0) c.getString(mimeCol) else null
                        val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR

                        if (isDir) {
                            if (!AudioFiles.shouldSkipDirectory(name)) walk(docId, depth + 1)
                            continue
                        }
                        if (!AudioFiles.isAudio(mime, name)) continue

                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        // 读不出就跳过这一首，绝不让一个坏文件把整次扫描带崩
                        val song = runCatching { inspectSafUri(docUri) }.getOrNull() ?: continue
                        // 与 MediaStore 扫描同一把尺：1~1.5 秒的是提示音不是歌
                        if (song.durationMs in 1..1500L) continue
                        songs.add(song)
                        onProgress(songs.size)
                    }
                }
            }

            val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            if (rootId != null) walk(rootId, 0)
            songs
        }

    private fun android.database.Cursor.stringOr(index: Int): String? =
        if (index >= 0 && !isNull(index)) getString(index) else null

    companion object {
        /** 目录树扫描最多下钻多少层。够用了，且能挡住提供器返回的异常深结构。 */
        private const val MAX_TREE_DEPTH = 12

        private val TREE_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        /** API 33 起用 READ_MEDIA_AUDIO，之前用 READ_EXTERNAL_STORAGE。 */
        fun requiredPermission(): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

        /** 通知权限，API 33 起需要运行时申请。 */
        fun notificationPermission(): String? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
    }
}
