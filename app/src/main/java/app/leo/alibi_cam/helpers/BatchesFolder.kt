package app.leo.alibi_cam.helpers

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.MediaStore.Video.Media
import android.system.Os
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import app.leo.alibi_cam.db.AppSettings
import app.leo.alibi_cam.db.RecordingInformation
import app.leo.alibi_cam.ui.MEDIA_RECORDINGS_PREFIX
import app.leo.alibi_cam.ui.RECORDER_INTERNAL_SELECTED_VALUE
import app.leo.alibi_cam.ui.RECORDER_MEDIA_SELECTED_VALUE
import app.leo.alibi_cam.ui.SUPPORTS_SCOPED_STORAGE
import app.leo.alibi_cam.ui.utils.PermissionHelper
import com.arthenica.ffmpegkit.FFmpegKitConfig
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


abstract class BatchesFolder(
    open val context: Context,
    open val type: BatchType,
    open val customFolder: DocumentFile? = null,
    open val subfolderName: String = ".recordings",
) {
    abstract val concatenationFunction: ConcatenationFunction
    abstract val ffmpegParameters: Array<String>
    abstract val scopedMediaContentUri: Uri
    abstract val legacyMediaFolder: File

    /**
     * If non-null, chunks are written into a task-specific subfolder (e.g. "20260703174830/").
     * Audio recorder does not set this — only video uses per-task subfolders.
     */
    var taskFolderName: String? = null

    /**
     * 是否物理删除文件（不进系统回收站）。
     * 由 IntervalRecorderService 从 AppSettings 同步。
     * 仅对 BatchType.MEDIA + API 29+ 生效。
     */
    var permanentlyDeleteRecordings: Boolean = false

    val mediaPrefix
        get() = MEDIA_RECORDINGS_PREFIX + subfolderName.substring(1) + "-"

    fun initFolders() {
        when (type) {
            BatchType.INTERNAL -> getInternalFolder().mkdirs()

            BatchType.CUSTOM -> {
                if (customFolder!!.findFile(subfolderName) == null) {
                    customFolder!!.createDirectory(subfolderName)
                }
            }

            BatchType.MEDIA -> {
                // Scoped storage works fine on new Android versions,
                // we need to manually manage the folder on older versions
                if (!SUPPORTS_SCOPED_STORAGE) {
                    legacyMediaFolder.mkdirs()
                }
            }
        }
    }

    fun getInternalFolder(): File {
        return File(context.filesDir, subfolderName)
    }

    fun getCustomDefinedFolder(): DocumentFile {
        return customFolder!!.findFile(subfolderName)!!
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    protected fun queryMediaContent(
        callback: (rawName: String, counter: Int, uri: Uri, cursor: Cursor) -> Any?,
    ) {
        context.contentResolver.query(
            scopedMediaContentUri,
            null,
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '$mediaPrefix%'",
            null,
            null,
        )!!.use { cursor ->
            while (cursor.moveToNext()) {
                val rawName = cursor.getColumnIndex(Media.DISPLAY_NAME).let { id ->
                    if (id == -1) null else cursor.getString(id)
                }

                if (rawName.isNullOrBlank() || !rawName.startsWith(mediaPrefix)) {
                    continue
                }

                val counter =
                    rawName.substringAfter(mediaPrefix).substringBeforeLast(".").toIntOrNull()
                        ?: continue

                val id = cursor.getColumnIndex(Media._ID).let { id ->
                    if (id == -1) null else cursor.getString(id)
                }

                if (id.isNullOrBlank()) {
                    continue
                }

                val uri = Uri.withAppendedPath(scopedMediaContentUri, id)

                val result = callback(rawName, counter, uri, cursor)

                if (result == false) {
                    return
                }
            }
        }
    }

    open fun getBatchesForFFmpeg(): List<String> {
        return when (type) {
            BatchType.INTERNAL -> {
                val searchDir = if (taskFolderName != null) {
                    File(getInternalFolder(), taskFolderName!!)
                } else {
                    getInternalFolder()
                }
                (searchDir.listFiles()
                    ?.filter {
                        it.nameWithoutExtension.toIntOrNull() != null
                    }
                    ?.toList()
                    ?: emptyList<File>())
                    .sortedBy {
                        it.nameWithoutExtension.toInt()
                    }
                    .map { it.absolutePath }
            }

            BatchType.CUSTOM -> getCustomDefinedFolder()
                .listFiles()
                .filter {
                    it.name?.substringBeforeLast(".")?.toIntOrNull() != null
                }
                .sortedBy {
                    it.name!!.substringBeforeLast(".").toInt()
                }
                .map {
                    FFmpegKitConfig.getSafParameterForRead(
                        context,
                        it.uri,
                    )!!
                }

            BatchType.MEDIA -> {
                val fileUris = mutableListOf<Pair<String, Uri>>()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    queryMediaContent { rawName, _, uri, _ ->
                        fileUris.add(Pair(rawName, uri))
                    }
                } else {
                    legacyMediaFolder.listFiles()?.forEach {
                        fileUris.add(Pair(it.name, it.toUri()))
                    }
                }

                fileUris
                    .sortedBy {
                        val name = it.first

                        return@sortedBy name
                            .substring(mediaPrefix.length)
                            .substringBeforeLast(".")
                            .toInt()
                    }
                    .map { pair ->
                        val uri = pair.second

                        FFmpegKitConfig.getSafParameterForRead(
                            context,
                            uri,
                        )!!
                    }
            }
        }
    }

    fun getName(date: LocalDateTime, extension: String): String {
        val name = date
            .format(DateTimeFormatter.ISO_DATE_TIME)
            .toString()
            .replace(":", "-")
            .replace(".", "_")

        return "$name.$extension"
    }

    fun asInternalGetOutputFile(fileName: String): File {
        return File(getInternalFolder(), fileName)
    }

    fun asMediaGetLegacyFile(name: String): File = File(
        legacyMediaFolder,
        name
    ).apply {
        createNewFile()
    }

    fun checkIfOutputAlreadyExists(
        fileName: String,
    ): Boolean {
        return when (type) {
            BatchType.INTERNAL -> File(getInternalFolder(), fileName).exists()

            BatchType.CUSTOM ->
                getCustomDefinedFolder().findFile(fileName)?.exists() ?: false

            BatchType.MEDIA -> {
                var exists = false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    queryMediaContent { rawName, _, _, _ ->
                        if (rawName == fileName) {
                            exists = true
                            return@queryMediaContent true
                        } else {
                        }
                    }

                    return exists
                } else {
                    return File(
                        legacyMediaFolder,
                        fileName,
                    ).exists()
                }
            }
        }
    }

    abstract fun getOutputFileForFFmpeg(
        date: LocalDateTime,
        extension: String,
        fileName: String,
    ): String

    abstract fun cleanup()

    /**
     * Opens a fresh set of FFmpeg inputs for each retry.
     * SAF-backed implementations keep their descriptors alive until FFmpeg
     * has completed, instead of passing stale `saf:` handles between retries.
     */
    open fun prepareInputPathsForFFmpeg(extension: String): FFmpegInputPaths {
        return when (type) {
            BatchType.INTERNAL -> FFmpegInputPaths(getBatchesForFFmpeg())
            BatchType.CUSTOM -> {
                val uris = getCustomDefinedFolder()
                    .listFiles()
                    .filter { it.isFile && it.name?.substringBeforeLast(".")?.toIntOrNull() != null }
                    .sortedBy { it.name!!.substringBeforeLast(".").toInt() }
                    .map { it.uri }
                openSafInputPaths(uris)
            }
            BatchType.MEDIA -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    FFmpegInputPaths(getBatchesForFFmpeg())
                } else {
                    val uris = mutableListOf<Pair<Int, Uri>>()
                    queryMediaContent { _, counter, uri, _ ->
                        uris.add(counter to uri)
                    }
                    openSafInputPaths(uris.sortedBy { it.first }.map { it.second })
                }
            }
        }
    }

    private fun openSafInputPaths(uris: List<Uri>): FFmpegInputPaths {
        val descriptors = mutableListOf<ParcelFileDescriptor>()
        try {
            uris.forEach { uri ->
                descriptors += context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw MediaConverter.FFmpegException("Unable to open FFmpeg input $uri")
            }
        } catch (error: Exception) {
            descriptors.forEach { runCatching { it.close() } }
            throw error
        }

        if (descriptors.isEmpty()) {
            throw MediaConverter.FFmpegException("No valid media batches available")
        }
        Log.i("Concatenation", "Prepared ${descriptors.size} FFmpeg fd inputs for type=$type")
        return FFmpegInputPaths(
            paths = descriptors.map { "fd:${it.fd}" },
            closeables = descriptors,
        )
    }

    open fun prepareOutputTargetForFFmpeg(
        date: LocalDateTime,
        extension: String,
        fileName: String,
    ): FFmpegOutputTarget {
        return FFmpegOutputTarget(
            path = getOutputFileForFFmpeg(
                date = date,
                extension = extension,
                fileName = fileName,
            ),
            format = ffmpegFormatForExtension(extension),
        )
    }

    suspend fun concatenate(
        recording: RecordingInformation,
        filenameFormat: AppSettings.FilenameFormat,
        disableCache: Boolean? = null,
        onNextParameterTry: (String) -> Unit = {},
        onProgress: (Float?) -> Unit = {},
        fileName: String,
    ): String {
        val disableCache = disableCache ?: (type != BatchType.INTERNAL)
        val date = recording.getStartDateForFilename(filenameFormat)

        if (!disableCache && checkIfOutputAlreadyExists(fileName)
        ) {
            return getOutputFileForFFmpeg(
                date = recording.recordingStart,
                extension = recording.fileExtension,
                fileName = fileName,
            )
        }

        for (parameter in ffmpegParameters) {
            Log.i("Concatenation", "Trying parameter $parameter")
            onNextParameterTry(parameter)
            onProgress(null)

            var inputPaths: FFmpegInputPaths? = null
            var outputTarget: FFmpegOutputTarget? = null
            try {
                val fullTime = recording.getFullDuration().toFloat().coerceAtLeast(1f)
                inputPaths = prepareInputPathsForFFmpeg(recording.fileExtension)
                if (inputPaths.paths.isEmpty()) {
                    throw MediaConverter.FFmpegException("No valid media batches available")
                }

                outputTarget = prepareOutputTargetForFFmpeg(
                    date = date,
                    extension = recording.fileExtension,
                    fileName = fileName,
                )

                concatenationFunction(
                    inputPaths.paths,
                    outputTarget,
                    parameter,
                    { time ->
                        // The progressbar for the conversion is calculated based on the
                        // current time of the conversion and the total time of the batches.
                        onProgress(time / fullTime)
                    },
                    inputPaths,
                ).await()

                // Close descriptors before publishing/renaming the output URI.
                inputPaths.close()
                outputTarget.close()
                val publishedOutput = outputTarget.onSuccess()
                Log.i("Concatenation", "FFmpeg succeeded output=$publishedOutput")
                return publishedOutput
            } catch (e: MediaConverter.FFmpegException) {
                Log.w("Concatenation", "FFmpeg attempt failed: ${e.message}")
                inputPaths?.close()
                outputTarget?.close()
                runCatching { outputTarget?.onFailure?.invoke() }
                continue
            } finally {
                inputPaths?.close()
                outputTarget?.close()
            }
        }

        throw MediaConverter.FFmpegException("Failed to concatenate")
    }

    fun exportFolderForSettings(): String {
        return when (type) {
            BatchType.INTERNAL -> RECORDER_INTERNAL_SELECTED_VALUE
            BatchType.MEDIA -> RECORDER_MEDIA_SELECTED_VALUE
            BatchType.CUSTOM -> customFolder!!.uri.toString()
        }
    }

    fun deleteRecordings() {
        // Currently deletes all recordings.
        // This is fine, because we are saving the recordings
        // in a dedicated subfolder
        when (type) {
            BatchType.INTERNAL -> getInternalFolder().deleteRecursively()

            BatchType.CUSTOM -> customFolder?.findFile(subfolderName)?.delete()
                ?: customFolder?.findFile(subfolderName)?.listFiles()?.forEach {
                    it.delete()
                }

            BatchType.MEDIA -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (permanentlyDeleteRecordings) {
                        // 永久删除：先物理删文件，再清 MediaStore 条目
                        val deletableUris = mutableListOf<Uri>()
                        queryMediaContent { _, _, uri, _ ->
                            deletableUris.add(uri)
                        }

                        for (uri in deletableUris) {
                            try {
                                context.contentResolver.query(
                                    uri,
                                    arrayOf(
                                        MediaStore.MediaColumns.RELATIVE_PATH,
                                        MediaStore.MediaColumns.DISPLAY_NAME
                                    ),
                                    null, null, null
                                )?.use { cursor ->
                                    if (cursor.moveToFirst()) {
                                        val relPathIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                                        val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                                        if (relPathIdx != -1 && nameIdx != -1) {
                                            val relPath = cursor.getString(relPathIdx) ?: ""
                                            val name = cursor.getString(nameIdx) ?: ""
                                            val physicalFile = java.io.File(
                                                Environment.getExternalStorageDirectory(),
                                                relPath + name
                                            )
                                            physicalFile.delete()
                                        }
                                    }
                                }
                                context.contentResolver.delete(uri, null, null)
                            } catch (e: Exception) {
                                Log.w(TAG, "永久删除失败: $uri", e)
                            }
                        }
                    } else {
                        context.contentResolver.delete(
                            scopedMediaContentUri,
                            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '$mediaPrefix%'",
                            null,
                        )
                    }
                } else {
                    legacyMediaFolder.deleteRecursively()
                }
            }
        }
    }

    fun hasRecordingsAvailable(): Boolean {
        return when (type) {
            BatchType.INTERNAL -> {
                val dir = if (taskFolderName != null) {
                    File(getInternalFolder(), taskFolderName!!)
                } else {
                    getInternalFolder()
                }
                dir.listFiles()?.isNotEmpty() ?: false
            }

            BatchType.CUSTOM -> customFolder?.findFile(subfolderName)?.listFiles()?.isNotEmpty()
                ?: false

            BatchType.MEDIA -> {
                var hasRecordings = false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.query(
                        scopedMediaContentUri,
                        arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '$mediaPrefix%'",
                        null,
                        null,
                    )!!.use { cursor ->
                        if (cursor.moveToFirst()) {
                            hasRecordings = true
                        }
                    }

                    return hasRecordings
                } else {
                    return legacyMediaFolder.listFiles()?.isNotEmpty() ?: false
                }
            }
        }
    }

    fun deleteRecordings(range: LongRange) {
        when (type) {
            BatchType.INTERNAL -> getInternalFolder().listFiles()?.forEach {
                val fileCounter = it.nameWithoutExtension.toIntOrNull() ?: return@forEach

                if (fileCounter in range) {
                    it.delete()
                }
            }

            BatchType.CUSTOM -> getCustomDefinedFolder().listFiles().forEach {
                val fileCounter = it.name?.substringBeforeLast(".")?.toIntOrNull() ?: return@forEach

                if (fileCounter in range) {
                    it.delete()
                }
            }

            BatchType.MEDIA -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (permanentlyDeleteRecordings) {
                        // ── 永久删除：先物理删除文件，再清理 MediaStore 条目 ──
                        // 部分厂商（小米/OPPO/vivo等）会拦截 contentResolver.delete()
                        // 放进系统回收站。物理删除文件可以绕过回收站。
                        val deletableUris = mutableListOf<Uri>()
                        queryMediaContent { _, counter, uri, _ ->
                            if (counter in range) {
                                deletableUris.add(uri)
                            }
                        }

                        for (uri in deletableUris) {
                            try {
                                // 从 MediaStore 查询 RELATIVE_PATH + DISPLAY_NAME 构建物理路径
                                context.contentResolver.query(
                                    uri,
                                    arrayOf(
                                        MediaStore.MediaColumns.RELATIVE_PATH,
                                        MediaStore.MediaColumns.DISPLAY_NAME
                                    ),
                                    null, null, null
                                )?.use { cursor ->
                                    if (cursor.moveToFirst()) {
                                        val relPathIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                                        val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                                        if (relPathIdx != -1 && nameIdx != -1) {
                                            val relPath = cursor.getString(relPathIdx) ?: ""
                                            val name = cursor.getString(nameIdx) ?: ""
                                            val physicalFile = java.io.File(
                                                Environment.getExternalStorageDirectory(),
                                                relPath + name
                                            )
                                            physicalFile.delete()  // 物理删除（绕过回收站）
                                        }
                                    }
                                }

                                // 清理 MediaStore 条目
                                context.contentResolver.delete(uri, null, null)
                            } catch (e: Exception) {
                                // 单个文件删除失败不影响其他文件
                                Log.w(TAG, "永久删除失败: $uri", e)
                            }
                        }
                    } else {
                        // ── 普通删除：走 MediaStore API（可能进回收站）──
                        val deletableNames = mutableListOf<String>()

                        queryMediaContent { rawName, counter, _, _ ->
                            if (counter in range) {
                                deletableNames.add(rawName)
                            }
                        }

                        try {
                            context.contentResolver.delete(
                                scopedMediaContentUri,
                                "${MediaStore.MediaColumns.DISPLAY_NAME} IN (${
                                    deletableNames.joinToString(
                                        ","
                                    ) { "'$it'" }
                                })",
                                null,
                            )
                        } catch (e: RuntimeException) {
                            e.printStackTrace()
                        } catch (e: IllegalArgumentException) {
                            e.printStackTrace()
                        }
                    }
                } else {
                    // TODO: Fix "would you like to try saving" -> Save button
                    legacyMediaFolder.listFiles()?.forEach {
                        val fileCounter =
                            it.nameWithoutExtension.substring(mediaPrefix.length).toIntOrNull()
                                ?: return@forEach

                        if (fileCounter in range) {
                            it.delete()
                        }
                    }
                }
            }
        }
    }

    fun checkIfFolderIsAccessible(): Boolean {
        try {
            return when (type) {
                BatchType.INTERNAL -> true
                BatchType.CUSTOM -> getCustomDefinedFolder().canWrite() && getCustomDefinedFolder().canRead()
                BatchType.MEDIA -> {
                    if (SUPPORTS_SCOPED_STORAGE) {
                        return true
                    }

                    return PermissionHelper.hasGranted(
                        context,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    ) &&
                            PermissionHelper.hasGranted(
                                context,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            )
                }
            }
        } catch (error: NullPointerException) {
            error.printStackTrace()
            return false
        }
    }

    fun asInternalGetFile(counter: Long, fileExtension: String): File {
        val dir = if (taskFolderName != null) {
            File(getInternalFolder(), taskFolderName!!).also { it.mkdirs() }
        } else {
            getInternalFolder()
        }
        val name = if (taskFolderName != null) {
            "%03d.%s".format(counter, fileExtension)
        } else {
            "$counter.$fileExtension"
        }
        return File(dir, name)
    }

    /**
     * Resolves the directory for a given task folder name (INTERNAL type only).
     */
    fun getTaskFolderDir(taskFolderName: String): File {
        return File(getInternalFolder(), taskFolderName)
    }

    /**
     * Lists all task folder names (timestamp-based directory names) sorted ascending.
     * The default implementation handles INTERNAL type; MEDIA/CUSTOM override in subclasses.
     */
    open fun listTaskFolderNames(): List<String> {
        return getInternalFolder().listFiles()
            ?.filter { it.isDirectory && it.name?.toLongOrNull() != null }
            ?.map { it.name!! }
            ?.sorted()
            ?: emptyList()
    }

    /**
     * Lists chunk filenames in a task folder, sorted ascending.
     */
    open fun listChunksInTaskFolder(taskFolderName: String): List<String> {
        return getTaskFolderDir(taskFolderName).listFiles()
            ?.filter { it.isFile && it.nameWithoutExtension.toIntOrNull() != null }
            ?.map { it.name!! }
            ?.sorted()
            ?: emptyList()
    }

    /**
     * Deletes a single chunk file from a task folder.
     */
    open fun deleteChunk(taskFolderName: String, chunkName: String): Boolean {
        return File(getTaskFolderDir(taskFolderName), chunkName).delete()
    }

    /**
     * Deletes a task folder if it is empty.
     */
    open fun deleteTaskFolderIfEmpty(taskFolderName: String): Boolean {
        val dir = getTaskFolderDir(taskFolderName)
        return if (dir.isDirectory && dir.listFiles()?.isEmpty() == true) {
            dir.delete()
        } else {
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun getOrCreateMediaFile(
        name: String,
        mimeType: String,
        relativePath: String,
    ): Uri {
        // Check if already exists
        var uri: Uri? = null

        context.contentResolver.query(
            scopedMediaContentUri,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = '$name'",
            null,
            null,
        )!!.use { cursor ->
            if (cursor.moveToFirst()) {
                // No need to check for the name since the query already did that
                val id = cursor.getColumnIndex(MediaStore.MediaColumns._ID)

                if (id == -1) {
                    return@use
                }

                uri = ContentUris.withAppendedId(
                    scopedMediaContentUri,
                    cursor.getLong(id)
                )
            }
        }

        if (uri == null) {
            try {
                // Create empty output file to be able to write to it
                uri = context.contentResolver.insert(
                    scopedMediaContentUri,
                    ContentValues().apply {
                        put(
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            name
                        )
                        put(
                            MediaStore.MediaColumns.MIME_TYPE,
                            mimeType
                        )

                        put(
                            Media.RELATIVE_PATH,
                            relativePath,
                        )
                    }
                )!!
            } catch (e: Exception) {
                Log.e("Media", "Failed to create file", e)
            }
        }

        return uri!!
    }

    fun getAvailableBytes(): Long? {
        if (type == BatchType.CUSTOM) {
            var fileDescriptor: ParcelFileDescriptor? = null

            try {
                fileDescriptor =
                    context.contentResolver.openFileDescriptor(customFolder!!.uri, "r")!!
                val stats = Os.fstatvfs(fileDescriptor.fileDescriptor)

                val available = stats.f_bavail * stats.f_bsize

                runCatching {
                    fileDescriptor.close()
                }

                return available
            } catch (e: Exception) {
                runCatching {
                    fileDescriptor?.close();
                }

                return null
            }
        }

        val storageManager = context.getSystemService(StorageManager::class.java) ?: return null
        val file = when (type) {
            BatchType.INTERNAL -> context.filesDir
            BatchType.MEDIA ->
                if (SUPPORTS_SCOPED_STORAGE)
                    File(
                        Environment.getExternalStoragePublicDirectory(VideoBatchesFolder.BASE_SCOPED_STORAGE_RELATIVE_PATH),
                        Media.EXTERNAL_CONTENT_URI.toString(),
                    )
                else
                    File(
                        Environment.getExternalStoragePublicDirectory(VideoBatchesFolder.BASE_LEGACY_STORAGE_FOLDER),
                        VideoBatchesFolder.MEDIA_RECORDINGS_SUBFOLDER,
                    )

            BatchType.CUSTOM -> throw IllegalArgumentException("This code should not be reachable")
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            storageManager.getAllocatableBytes(storageManager.getUuidForPath(file))
        } else {
            file.usableSpace;
        }
    }

    enum class BatchType {
        INTERNAL,
        CUSTOM,
        MEDIA,
    }

    companion object {
        private const val TAG = "BatchesFolder"

        fun requiredBytesForOneMinuteOfRecording(appSettings: AppSettings): Long {
            // 350 MiB sounds like a good default
            return 350 * 1024 * 1024
        }

        fun canAccessFolder(context: Context, uri: Uri): Boolean {
            // This always returns false for some reason, let's just assume it's true
            return true
            /*
            return try {
                // Create temp file
                val docFile = DocumentFile.fromSingleUri(context, uri)!!

                return docFile.canWrite().also {
                    println("Can write? ${it}")
                } && docFile.canRead().also {
                    println("Can read? ${it}")
                }
            } catch (error: RuntimeException) {
                error.printStackTrace()
                false
            }
             */
        }
    }
}

