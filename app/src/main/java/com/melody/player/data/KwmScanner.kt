package com.melody.player.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.melody.player.core.kwm.KwmFile
import com.melody.player.core.kwm.KwmFiles
import com.melody.player.core.kwm.KwmSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一次扫描的结果。[note] 是给用户看的说明（没找到时的原因、被平台限制等）。 */
data class KwmScanResult(val files: List<KwmFile>, val note: String? = null)

/**
 * 找出设备上的 `.kwm` 文件。
 *
 * 三条路，因为**没有哪一条能在所有 Android 版本上都管用**，这一点必须对用户讲清楚：
 *
 *  1. [scanMediaStore] —— 查系统媒体库。最省事，但 `.kwm` 不是系统认识的媒体类型，
 *     Android 11 起普通应用查不到「别人的非媒体文件」，所以在新系统上很可能一个都扫不到。
 *  2. [scanTree] —— 用户授权一个文件夹，我们按目录树递归找。
 *     这是在新系统上唯一可靠、且不需要「所有文件访问权限」的做法，授权还能跨重启保留。
 *  3. 用户直接用系统文件选择器多选（UI 那条路）—— 一定可用。
 *
 * 三条结果按 URI 去重后合并，用户不必关心是哪条路找到的。
 */
class KwmScanner(private val context: Context) {

    /** 查系统媒体库里的 `.kwm`。尽力而为，失败时把原因带回去。 */
    suspend fun scanMediaStore(): KwmScanResult = withContext(Dispatchers.IO) {
        val collection = MediaStore.Files.getContentUri(VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )
        // SQLite 的 LIKE 对 ASCII 默认不区分大小写，`.KWM` 也能命中
        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%${KwmFiles.SUFFIX}")

        val files = ArrayList<KwmFile>()
        val failure = runCatching {
            context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
                while (cursor.moveToNext() && files.size < MAX_FILES) {
                    val name = cursor.str(nameCol) ?: continue
                    if (!KwmFiles.isKwmName(name)) continue
                    val id = cursor.getLong(idCol)
                    files.add(
                        KwmFile(
                            name = name,
                            uri = ContentUris.withAppendedId(collection, id).toString(),
                            sizeBytes = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L,
                            modifiedSec = if (dateCol >= 0 && !cursor.isNull(dateCol)) cursor.getLong(dateCol) else 0L,
                            source = KwmSource.DEVICE
                        )
                    )
                }
            }
        }.exceptionOrNull()

        when {
            failure != null -> KwmScanResult(
                emptyList(),
                "系统不允许读取媒体库里的非音频文件（${failure.javaClass.simpleName}）。" +
                    "请改用「指定文件夹」授权音乐所在目录。"
            )
            files.isEmpty() -> KwmScanResult(
                emptyList(),
                "媒体库里有 0 个 .kwm。新版本 Android 默认不让 App 看到非媒体文件，" +
                    "请用「指定文件夹」授权音乐所在目录，或「手动选择文件」。"
            )
            else -> KwmScanResult(files)
        }
    }

    /**
     * 递归扫描用户授权的文件夹（SAF 目录树）。
     *
     * 迭代而不是递归：音乐目录动辄十几层，递归容易爆栈，而且深度还得防着点。
     * 单个子目录读失败就跳过 —— 一个没权限的子目录不该让整次扫描白跑。
     */
    suspend fun scanTree(treeUri: Uri): KwmScanResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val files = ArrayList<KwmFile>()
        val pending = ArrayDeque<Pair<String, Int>>()

        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return@withContext KwmScanResult(emptyList(), "这个文件夹的授权不可用，请重新选择")

        pending.addLast(rootId to 0)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        while (pending.isNotEmpty() && files.size < MAX_FILES) {
            val (docId, depth) = pending.removeFirst()
            if (depth > MAX_DEPTH) continue

            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            var cursor: Cursor? = null
            try {
                cursor = resolver.query(children, projection, null, null, null)
                while (cursor != null && cursor.moveToNext() && files.size < MAX_FILES) {
                    val id = cursor.str(0) ?: continue
                    val name = cursor.str(1) ?: continue
                    val mime = cursor.str(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pending.addLast(id to depth + 1)
                        continue
                    }
                    if (!KwmFiles.isKwmName(name)) continue
                    files.add(
                        KwmFile(
                            name = name,
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(),
                            sizeBytes = if (cursor.isNull(3)) 0L else cursor.getLong(3),
                            modifiedSec = if (cursor.isNull(4)) 0L else cursor.getLong(4) / 1000L,
                            source = KwmSource.FOLDER
                        )
                    )
                }
            } catch (_: Throwable) {
                // 子目录读不到就跳过
            } finally {
                runCatching { cursor?.close() }
            }
        }

        KwmScanResult(
            files,
            if (files.isEmpty()) "这个文件夹里没有 .kwm 文件（已递归查找子目录）" else null
        )
    }

    /** 把用户手动选中的 URI 认成一个待解密项；认不出来返回 null。 */
    fun inspect(uri: Uri): KwmFile? {
        var name: String? = null
        var size = 0L
        runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        name = cursor.str(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                        val sizeCol = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeCol >= 0 && !cursor.isNull(sizeCol)) size = cursor.getLong(sizeCol)
                    }
                }
        }
        val fileName = name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: return null
        // 手动选择的可能是任意文件，只接受 .kwm
        if (!KwmFiles.isKwmName(fileName)) return null
        return KwmFile(
            name = fileName,
            uri = uri.toString(),
            sizeBytes = size,
            modifiedSec = System.currentTimeMillis() / 1000L,
            source = KwmSource.PICKED
        )
    }

    /** 让文件夹授权跨重启依然有效。 */
    fun takePersistablePermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun Cursor.str(index: Int): String? =
        if (index >= 0 && !isNull(index)) getString(index) else null

    private companion object {
        const val VOLUME_EXTERNAL = "external"

        /** 一次最多列这么多，避免超大目录把内存和界面拖死。 */
        const val MAX_FILES = 500

        /** 目录树最多往下走这么多层。 */
        const val MAX_DEPTH = 8
    }
}
