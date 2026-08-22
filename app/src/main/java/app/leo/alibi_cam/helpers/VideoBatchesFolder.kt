package app.leo.alibi_cam.helpers

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import app.leo.alibi_cam.helpers.MediaConverter.Companion.concatenateVideoFiles
import app.leo.alibi_cam.ui.MEDIA_SUBFOLDER_NAME
import app.leo.alibi_cam.ui.RECORDER_INTERNAL_SELECTED_VALUE
import app.leo.alibi_cam.ui.RECORDER_MEDIA_SELECTED_VALUE
import app.leo.alibi_cam.ui.VIDEO_RECORDING_BATCHES_SUBFOLDER_NAME
import com.arthenica.ffmpegkit.FFmpegKitConfig
import java.io.File
import java.time.LocalDateTime

class VideoBatchesFolder(
    override val context: Context,
    override val type: BatchType,
    override val customFolder: DocumentFile? = null,
    val cameraPosition: CameraPosition = CameraPosition.SINGLE,
    override val subfolderName: String = VIDEO_RECORDING_BATCHES_SUBFOLDER_NAME + cameraPosition.folderSuffix,
) : BatchesFolder(
    context,
    type,
    customFolder,
    subfolderName,
) {
    override val concatenationFunction = ::concatenateVideoFiles
    override val ffmpegParameters = FFMPEG_PARAMETERS
    override val scopedMediaContentUri: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    override val legacyMediaFolder = File(
        Environment.getExternalStoragePublicDirectory(BASE_LEGACY_STORAGE_FOLDER),
        MEDIA_RECORDINGS_SUBFOLDER,
    )

    private var customParcelFileDescriptor: ParcelFileDescriptor? = null

    /**
     * Session ID for flat storage mode. When set, chunks are stored directly in
     * the base folder (no task subfolder) with names like:
     *   alibi-video_recordings-{sessionId}-{counter}.mp4
     *
     * This replaces the older per-session subfolder approach. Setting this to
     * non-null also scopes [getBatchesForFFmpeg] to only return chunks from
     * this session.
     */
    var sessionId: String? = null

    override fun getOutputFileForFFmpeg(
        date: LocalDateTime,
        extension: String,
        fileName: String,
    ): String {
        return when (type) {
            BatchType.INTERNAL -> asInternalGetOutputFile(fileName).absolutePath

            BatchType.CUSTOM -> {
                FFmpegKitConfig.getSafParameterForWrite(
                    context,
                    (customFolder!!.findFile(fileName) ?: customFolder.createFile(
                        "video/${extension}",
                        fileName,
                    )!!).uri
                )!!
            }

            BatchType.MEDIA -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val mediaUri = getOrCreateMediaFile(
                        name = fileName,
                        mimeType = "video/$extension",
                        relativePath = BASE_SCOPED_STORAGE_RELATIVE_PATH + "/" + MEDIA_SUBFOLDER_NAME,
                    )

                    return FFmpegKitConfig.getSafParameterForWrite(
                        context,
                        mediaUri
                    )!!
                } else {
                    val path = arrayOf(
                        Environment.getExternalStoragePublicDirectory(BASE_LEGACY_STORAGE_FOLDER),
                        MEDIA_SUBFOLDER_NAME,
                        fileName,
                    ).joinToString("/")
                    return File(path)
                        .apply {
                            createNewFile()
                        }.absolutePath
                }
            }
        }
    }

    override fun cleanup() {
        runCatching {
            customParcelFileDescriptor?.close()
        }
    }

    // ── Task-folder scanning overrides for MEDIA / CUSTOM ─────────────────

    override fun listTaskFolderNames(): List<String> {
        return when (type) {
            BatchType.INTERNAL -> super.listTaskFolderNames()
            BatchType.CUSTOM -> {
                getCustomDefinedFolder().listFiles()
                    .filter { it.isDirectory && it.name?.toLongOrNull() != null }
                    .map { it.name!! }
                    .sorted()
            }
            BatchType.MEDIA -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val folders = mutableListOf<String>()
                    // Query all video files under our scoped directory to discover task subfolders
                    context.contentResolver.query(
                        scopedMediaContentUri,
                        arrayOf(MediaStore.Video.Media.RELATIVE_PATH),
                        "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
                        arrayOf("$SCOPED_STORAGE_RELATIVE_PATH/%"),
                        null,
                    )?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val path = cursor.getString(0)
                            // Extract task folder name: DCIM/alibi/.video_recordings/20260703174830 → 20260703174830
                            val subPath = path.removePrefix("$SCOPED_STORAGE_RELATIVE_PATH/")
                            val folderName = subPath.substringBefore("/")
                            if (folderName.toLongOrNull() != null && folderName !in folders) {
                                folders.add(folderName)
                            }
                        }
                    }
                    folders.sorted()
                } else {
                    legacyMediaFolder.listFiles()
                        ?.filter { it.isDirectory && it.name?.toLongOrNull() != null }
                        ?.map { it.name!! }
                        ?.sorted()
                        ?: emptyList()
                }
            }
        }
    }

    override fun listChunksInTaskFolder(taskFolderName: String): List<String> {
        return when (type) {
            BatchType.INTERNAL -> super.listChunksInTaskFolder(taskFolderName)
            BatchType.CUSTOM -> {
                val taskDir = getCustomDefinedFolder().findFile(taskFolderName) ?: return emptyList()
                taskDir.listFiles()
                    .filter { it.isFile && it.name?.substringBeforeLast(".")?.toIntOrNull() != null }
                    .map { it.name!! }
                    .sorted()
            }
            BatchType.MEDIA -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val chunks = mutableListOf<String>()
                    val relPath = "$SCOPED_STORAGE_RELATIVE_PATH/$taskFolderName"
                    context.contentResolver.query(
                        scopedMediaContentUri,
                        arrayOf(MediaStore.Video.Media.DISPLAY_NAME),
                        "${MediaStore.Video.Media.RELATIVE_PATH} = ?",
                        arrayOf(relPath),
                        null,
                    )?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(0) ?: continue
                            if (name.substringBeforeLast(".").toIntOrNull() != null) {
                                chunks.add(name)
                            }
                        }
                    }
                    chunks.sorted()
                } else {
                    File(legacyMediaFolder, taskFolderName).listFiles()
                        ?.filter { it.isFile && it.nameWithoutExtension.toIntOrNull() != null }
                        ?.map { it.name!! }
                        ?.sorted()
                        ?: emptyList()
                }
            }
        }
    }

    override fun deleteChunk(taskFolderName: String, chunkName: String): Boolean {
        return when (type) {
            BatchType.INTERNAL -> super.deleteChunk(taskFolderName, chunkName)
            BatchType.CUSTOM -> {
                val taskDir = getCustomDefinedFolder().findFile(taskFolderName) ?: return false
                val file = taskDir.findFile(chunkName) ?: return false
                file.delete()
            }
            BatchType.MEDIA -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val relPath = "$SCOPED_STORAGE_RELATIVE_PATH/$taskFolderName"
                    val deleted = context.contentResolver.delete(
                        scopedMediaContentUri,
                        "${MediaStore.Video.Media.RELATIVE_PATH} = ? AND ${MediaStore.Video.Media.DISPLAY_NAME} = ?",
                        arrayOf(relPath, chunkName),
                    )
                    deleted > 0
                } else {
                    File(legacyMediaFolder, "$taskFolderName/$chunkName").delete()
                }
            }
        }
    }

    override fun deleteTaskFolderIfEmpty(taskFolderName: String): Boolean {
        return when (type) {
            BatchType.INTERNAL -> super.deleteTaskFolderIfEmpty(taskFolderName)
            BatchType.CUSTOM -> {
                val taskDir = getCustomDefinedFolder().findFile(taskFolderName) ?: return true
                if (taskDir.listFiles().isEmpty()) { taskDir.delete() } else false
            }
            BatchType.MEDIA -> {
                // Check if any chunks remain in the task folder
                val remaining = listChunksInTaskFolder(taskFolderName)
                if (remaining.isEmpty()) {
                    // Nothing to delete — MediaStore removes the virtual folder when empty
                    true
                } else {
                    false
                }
            }
        }
    }

    /**
     * Lists ALL chunk (display name → duration millis) across all sessions,
     * sorted lexicographically (= chronologically for timestamp-based names).
     *
     * For MEDIA type the duration comes from MediaStore.DURATION (free, fast).
     * For INTERNAL/CUSTOM we estimate from file size ÷ typical bitrate,
     * since pulling true duration would require MediaMetadataRetriever (slow).
     */
    fun listFlatChunksWithDuration(): List<Pair<String, Long>> {
        return when (type) {
            BatchType.INTERNAL -> {
                getInternalFolder().listFiles()
                    ?.filter { it.isFile && it.name?.startsWith(mediaPrefix) == true }
                    ?.map { it.name!! to (it.length() * 8 / TYPICAL_BITRATE_BPS) }
                    ?.sortedBy { it.first }
                    ?: emptyList()
            }
            BatchType.CUSTOM -> {
                getCustomDefinedFolder().listFiles()
                    .filter { it.isFile && it.name?.startsWith(mediaPrefix) == true }
                    .map { f -> f.name!! to (f.length() * 8 / TYPICAL_BITRATE_BPS) }
                    .sortedBy { it.first }
            }
            BatchType.MEDIA -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val result = mutableListOf<Pair<String, Long>>()
                    context.contentResolver.query(
                        scopedMediaContentUri,
                        arrayOf(
                            MediaStore.Video.Media.DISPLAY_NAME,
                            MediaStore.Video.Media.DURATION,
                        ),
                        "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
                        arrayOf("$mediaPrefix%"),
                        null,
                    )?.use { cursor ->
                        val nameIdx = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                        val durIdx = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(nameIdx) ?: continue
                            if (!name.startsWith(mediaPrefix)) continue
                            val durationMs = cursor.getLong(durIdx)
                            result.add(name to durationMs)
                        }
                    }
                    result.sortedBy { it.first }
                } else {
                    legacyMediaFolder.listFiles()
                        ?.filter { it.isFile && it.name?.startsWith(mediaPrefix) == true }
                        ?.map { it.name!! to (it.length() * 8 / TYPICAL_BITRATE_BPS) }
                        ?.sortedBy { it.first }
                        ?: emptyList()
                }
            }
        }
    }

    // ── Flat storage (no task subfolders) ──────────────────────────────────

    /**
     * Deletes a single chunk by its display name (flat storage).
     * When [permanentlyDeleteRecordings] is true, physically deletes the file
     * before removing the MediaStore entry (bypassing Xiaomi/HyperOS recycle bin).
     */
    fun deleteFlatChunk(name: String): Boolean {
        return when (type) {
            BatchType.INTERNAL -> File(getInternalFolder(), name).delete()
            BatchType.CUSTOM -> {
                val file = getCustomDefinedFolder().findFile(name) ?: return false
                file.delete()
            }
            BatchType.MEDIA -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (permanentlyDeleteRecordings) {
                        // 永久删除（绕过小米回收站）：
                        // Xiaomi HyperOS 会拦截 contentResolver.delete()，自动把媒体文件
                        // 移入回收站。经实测，File.delete() 和 openFileDescriptor("w") 截断
                        // 都不可靠。
                        //
                        // 策略：先把文件改名（非媒体扩展名 .tmp），再标记为 IS_PENDING，
                        // 最后从 MediaStore 删除。对小米而言，一个未完成的 .tmp 文件不值得回收。
                        var deleted = false
                        context.contentResolver.query(
                            scopedMediaContentUri,
                            arrayOf(MediaStore.Video.Media._ID),
                            "${MediaStore.Video.Media.DISPLAY_NAME} = ?",
                            arrayOf(name),
                            null,
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val id = cursor.getLong(0)
                                val uri = ContentUris.withAppendedId(
                                    scopedMediaContentUri, id
                                )

                                // Step 1: 改名 + 标为 pending（告诉系统"这文件不要了"）
                                try {
                                    val cv = ContentValues().apply {
                                        put(MediaStore.Video.Media.IS_PENDING, 1)
                                        put(MediaStore.Video.Media.DISPLAY_NAME,
                                            ".trash-${System.currentTimeMillis()}.tmp")
                                    }
                                    context.contentResolver.update(uri, cv, null, null)
                                    Log.i(TAG, "perm-del: renamed $name → pending .tmp")
                                    CameraDebugLog.append("perm-del: renamed $name → pending .tmp")
                                } catch (e: Exception) {
                                    Log.w(TAG, "perm-del: rename $name FAILED: ${e.message}")
                                    CameraDebugLog.append("perm-del: rename $name FAILED: ${e.message}")
                                }

                                // Step 2: 从 MediaStore 删除（此时已是 .tmp pending 文件）
                                val rows = context.contentResolver.delete(uri, null, null)
                                deleted = rows > 0
                                Log.i(TAG, "perm-del: $name deleted=$deleted (rows=$rows)")
                                CameraDebugLog.append("perm-del: $name deleted=$deleted (rows=$rows)")
                            }
                        }
                        deleted
                    } else {
                        val deleted = context.contentResolver.delete(
                            scopedMediaContentUri,
                            "${MediaStore.Video.Media.DISPLAY_NAME} = ?",
                            arrayOf(name),
                        )
                        deleted > 0
                    }
                } else {
                    File(legacyMediaFolder, name).delete()
                }
            }
        }
    }

    /**
     * Lists chunk display names for the current session only.
     * Requires [sessionId] to be set; returns empty list otherwise.
     */
    fun listSessionChunkNames(): List<String> {
        val sid = sessionId ?: return emptyList()
        val prefix = "${mediaPrefix}${sid}-"
        return when (type) {
            BatchType.INTERNAL -> {
                getInternalFolder().listFiles()
                    ?.filter { it.isFile && it.name?.startsWith(prefix) == true }
                    ?.map { it.name!! }
                    ?.sorted()
                    ?: emptyList()
            }
            BatchType.CUSTOM -> {
                getCustomDefinedFolder().listFiles()
                    .filter { it.isFile && it.name?.startsWith(prefix) == true }
                    .map { it.name!! }
                    .sorted()
            }
            BatchType.MEDIA -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val names = mutableListOf<String>()
                    context.contentResolver.query(
                        scopedMediaContentUri,
                        arrayOf(MediaStore.Video.Media.DISPLAY_NAME),
                        "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
                        arrayOf("$prefix%"),
                        null,
                    )?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(0) ?: continue
                            if (name.startsWith(prefix)) names.add(name)
                        }
                    }
                    names.sorted()
                } else {
                    legacyMediaFolder.listFiles()
                        ?.filter { it.isFile && it.name?.startsWith(prefix) == true }
                        ?.map { it.name!! }
                        ?.sorted()
                        ?: emptyList()
                }
            }
        }
    }

    /**
     * Returns FFmpeg-compatible file paths for chunks belonging to the current
     * session (when [sessionId] is set) or all chunks (fallback).
     */
    override fun getBatchesForFFmpeg(): List<String> {
        val names = if (sessionId != null) {
            listSessionChunkNames()
        } else {
            // Fallback: use the old task-folder-based approach
            return super.getBatchesForFFmpeg()
        }

        if (names.isEmpty()) return emptyList()

        return when (type) {
            BatchType.INTERNAL -> {
                names.map { File(getInternalFolder(), it).absolutePath }
            }
            BatchType.CUSTOM -> {
                names.mapNotNull { name ->
                    val file = getCustomDefinedFolder().findFile(name) ?: return@mapNotNull null
                    FFmpegKitConfig.getSafParameterForRead(context, file.uri)
                }
            }
            BatchType.MEDIA -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val uris = mutableListOf<Uri>()
                    val prefix = "${mediaPrefix}${sessionId}-"
                    context.contentResolver.query(
                        scopedMediaContentUri,
                        arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME),
                        "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
                        arrayOf("$prefix%"),
                        null,
                    )?.use { cursor ->
                        val nameIdx = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                        val idIdx = cursor.getColumnIndex(MediaStore.Video.Media._ID)
                        while (cursor.moveToNext()) {
                            if (cursor.getString(nameIdx) == null) continue
                            val id = cursor.getLong(idIdx)
                            uris.add(ContentUris.withAppendedId(scopedMediaContentUri, id))
                        }
                    }
                    uris.sortedBy { it.toString() }
                        .map { uri -> FFmpegKitConfig.getSafParameterForRead(context, uri)!! }
                } else {
                    names.map { name ->
                        FFmpegKitConfig.getSafParameterForRead(
                            context,
                            File(legacyMediaFolder, name).toUri(),
                        )!!
                    }
                }
            }
        }
    }

    fun asCustomGetParcelFileDescriptor(
        counter: Long,
        fileExtension: String,
    ): ParcelFileDescriptor {
        runCatching {
            customParcelFileDescriptor?.close()
        }

        val parentFolder = if (taskFolderName != null) {
            var f = getCustomDefinedFolder().findFile(taskFolderName!!)
            if (f == null) f = getCustomDefinedFolder().createDirectory(taskFolderName!!)
            f!!
        } else {
            getCustomDefinedFolder()
        }

        val fileName = if (taskFolderName != null) {
            "%03d.%s".format(counter, fileExtension)
        } else {
            "$counter.$fileExtension"
        }

        val file = parentFolder.createFile("video/$fileExtension", fileName)!!
        val resolver = context.contentResolver.acquireContentProviderClient(file.uri)!!

        resolver.use {
            customParcelFileDescriptor = it.openFile(file.uri, "w")!!

            return customParcelFileDescriptor!!
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun asMediaGetScopedStorageContentValues(name: String) = ContentValues().apply {
        put(
            MediaStore.Video.Media.IS_PENDING,
            1
        )
        put(
            MediaStore.Video.Media.RELATIVE_PATH,
            if (taskFolderName != null) "$SCOPED_STORAGE_RELATIVE_PATH/$taskFolderName"
            else SCOPED_STORAGE_RELATIVE_PATH,
        )

        put(
            MediaStore.Video.Media.DISPLAY_NAME,
            name
        )
    }

    companion object {
        private const val TAG = "VideoBatchesFolder"

        // Typical video bitrate for duration estimation (bps).
        // Default is 2 Mbps; used only for INTERNAL/CUSTOM types
        // where MediaStore.DURATION is not available.
        private const val TYPICAL_BITRATE_BPS = 2_000_000L

        fun viaInternalFolder(
            context: Context,
            cameraPosition: CameraPosition = CameraPosition.SINGLE,
        ) = VideoBatchesFolder(context, BatchType.INTERNAL, cameraPosition = cameraPosition)

        fun viaCustomFolder(
            context: Context,
            folder: DocumentFile,
            cameraPosition: CameraPosition = CameraPosition.SINGLE,
        ) = VideoBatchesFolder(context, BatchType.CUSTOM, folder, cameraPosition = cameraPosition)

        fun viaMediaFolder(
            context: Context,
            cameraPosition: CameraPosition = CameraPosition.SINGLE,
        ) = VideoBatchesFolder(context, BatchType.MEDIA, cameraPosition = cameraPosition)

        fun importFromFolder(
            folder: String?,
            context: Context,
            cameraPosition: CameraPosition = CameraPosition.SINGLE,
        ) = when (folder) {
            null -> viaInternalFolder(context, cameraPosition)
            RECORDER_INTERNAL_SELECTED_VALUE -> viaInternalFolder(context, cameraPosition)
            RECORDER_MEDIA_SELECTED_VALUE -> viaMediaFolder(context, cameraPosition)
            else -> viaCustomFolder(
                context,
                DocumentFile.fromTreeUri(context, Uri.parse(folder))!!,
                cameraPosition,
            )
        }

        val BASE_LEGACY_STORAGE_FOLDER = Environment.DIRECTORY_DCIM
        val MEDIA_RECORDINGS_SUBFOLDER = MEDIA_SUBFOLDER_NAME + "/.video_recordings"
        val BASE_SCOPED_STORAGE_RELATIVE_PATH = Environment.DIRECTORY_DCIM
        val SCOPED_STORAGE_RELATIVE_PATH =
            BASE_SCOPED_STORAGE_RELATIVE_PATH + "/" + MEDIA_RECORDINGS_SUBFOLDER

        // Parameters to be passed in descending order
        // Those parameters first try to concatenate without re-encoding
        // if that fails, it'll try several fallback methods
        val FFMPEG_PARAMETERS = arrayOf(
            " -c copy",
            " -c:v copy",
            " -c:v copy -c:a aac",
            " -c:v copy -c:a libmp3lame",
            " -c:v copy -c:a libopus",
            " -c:v copy -c:a libvorbis",
            " -c:a copy",
            // There's nothing else we can do to avoid re-encoding,
            // so we'll just have to re-encode the whole thing
            " -c:v libx264 -c:a copy",
            " -c:v libx264 -c:a aac",
            " -c:v libx265 -c:a aac",
            " -c:v libx264 -c:a libmp3lame",
            " -c:v libx264 -c:a libopus",
            " -c:v libx264 -c:a libvorbis",
            " -c:v libx265 -c:a copy",
            " -c:v libx265 -c:a aac",
            " -c:v libx265 -c:a libmp3lame",
            " -c:v libx265 -c:a libopus",
            " -c:v libx265 -c:a libvorbis",
        )
    }
}