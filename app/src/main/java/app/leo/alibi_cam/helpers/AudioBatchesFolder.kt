package app.leo.alibi_cam.helpers

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import app.leo.alibi_cam.helpers.MediaConverter.Companion.concatenateAudioFiles
import app.leo.alibi_cam.ui.AUDIO_RECORDING_BATCHES_SUBFOLDER_NAME
import app.leo.alibi_cam.ui.MEDIA_SUBFOLDER_NAME
import app.leo.alibi_cam.ui.RECORDER_INTERNAL_SELECTED_VALUE
import app.leo.alibi_cam.ui.RECORDER_MEDIA_SELECTED_VALUE
import com.arthenica.ffmpegkit.FFmpegKitConfig
import java.io.File
import java.io.FileDescriptor
import java.time.LocalDateTime
import java.util.UUID

class AudioBatchesFolder(
    override val context: Context,
    override val type: BatchType,
    override val customFolder: DocumentFile? = null,
    override val subfolderName: String = AUDIO_RECORDING_BATCHES_SUBFOLDER_NAME,
) : BatchesFolder(
    context,
    type,
    customFolder,
    subfolderName,
) {
    override val concatenationFunction = ::concatenateAudioFiles
    override val ffmpegParameters = FFMPEG_PARAMETERS
    override val scopedMediaContentUri: Uri = SCOPED_MEDIA_CONTENT_URI
    override val legacyMediaFolder = LEGACY_MEDIA_FOLDER

    private var customFileFileDescriptor: ParcelFileDescriptor? = null
    private var mediaFileFileDescriptor: ParcelFileDescriptor? = null

    override fun prepareOutputTargetForFFmpeg(
        date: LocalDateTime,
        extension: String,
        fileName: String,
    ): FFmpegOutputTarget {
        return when (type) {
            BatchType.INTERNAL -> super.prepareOutputTargetForFFmpeg(date, extension, fileName)
            BatchType.CUSTOM -> prepareCustomOutputTarget(extension, fileName)
            BatchType.MEDIA -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    super.prepareOutputTargetForFFmpeg(date, extension, fileName)
                } else {
                    prepareMediaOutputTarget(extension, fileName)
                }
            }
        }
    }

    private fun prepareCustomOutputTarget(
        extension: String,
        fileName: String,
    ): FFmpegOutputTarget {
        val parent = customFolder ?: throw MediaConverter.FFmpegException("Custom folder unavailable")
        val tempName = ".tmp-${UUID.randomUUID()}-$fileName"
        val tempFile = parent.createFile("audio/$extension", tempName)
            ?: throw MediaConverter.FFmpegException("Unable to create custom FFmpeg output")
        val descriptor = try {
            context.contentResolver.openFileDescriptor(tempFile.uri, "rwt")
                ?: throw MediaConverter.FFmpegException("Unable to open custom FFmpeg output")
        } catch (error: Exception) {
            tempFile.delete()
            throw error
        }

        return FFmpegOutputTarget(
            path = "fd:${descriptor.fd}",
            format = ffmpegFormatForExtension(extension),
            closeables = listOf(descriptor),
            onSuccess = {
                parent.findFile(fileName)?.delete()
                if (!tempFile.renameTo(fileName)) {
                    throw MediaConverter.FFmpegException("Unable to publish custom FFmpeg output")
                }
                (parent.findFile(fileName) ?: tempFile).uri.toString()
            },
            onFailure = { tempFile.delete() },
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun prepareMediaOutputTarget(
        extension: String,
        fileName: String,
    ): FFmpegOutputTarget {
        val relativePath = BASE_SCOPED_STORAGE_RELATIVE_PATH + "/" + MEDIA_SUBFOLDER_NAME
        val tempName = "tmp-${UUID.randomUUID()}-$fileName"
        val uri = context.contentResolver.insert(
            scopedMediaContentUri,
            ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, tempName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/$extension")
                put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            },
        ) ?: throw MediaConverter.FFmpegException("Unable to create MediaStore FFmpeg output")
        val descriptor = try {
            context.contentResolver.openFileDescriptor(uri, "rwt")
                ?: throw MediaConverter.FFmpegException("Unable to open MediaStore FFmpeg output")
        } catch (error: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }

        return FFmpegOutputTarget(
            path = "fd:${descriptor.fd}",
            format = ffmpegFormatForExtension(extension),
            closeables = listOf(descriptor),
            onSuccess = {
                context.contentResolver.delete(
                    scopedMediaContentUri,
                    "${MediaStore.Audio.Media.RELATIVE_PATH} = ? AND " +
                        "${MediaStore.Audio.Media.DISPLAY_NAME} = ?",
                    arrayOf(relativePath, fileName),
                )
                val updated = context.contentResolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Audio.Media.IS_PENDING, 0)
                    },
                    null,
                    null,
                )
                if (updated <= 0) {
                    throw MediaConverter.FFmpegException("Unable to publish MediaStore FFmpeg output")
                }
                uri.toString()
            },
            onFailure = { context.contentResolver.delete(uri, null, null) },
        )
    }

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
                        "audio/${extension}",
                        fileName,
                    )!!).uri
                )!!
            }

            BatchType.MEDIA -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val mediaUri = getOrCreateMediaFile(
                        name = fileName,
                        mimeType = "audio/$extension",
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
            customFileFileDescriptor?.close()
        }
        runCatching {
            mediaFileFileDescriptor?.close()
        }
    }

    fun asCustomGetFileDescriptor(
        counter: Long,
        fileExtension: String,
    ): FileDescriptor {
        runCatching {
            customFileFileDescriptor?.close()
        }

        val file =
            getCustomDefinedFolder().createFile("audio/$fileExtension", "$counter.$fileExtension")!!

        customFileFileDescriptor = context.contentResolver.openFileDescriptor(file.uri, "w")!!

        return customFileFileDescriptor!!.fileDescriptor
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun asMediaGetScopedStorageFileDescriptor(
        name: String,
        mimeType: String
    ): FileDescriptor {
        runCatching {
            mediaFileFileDescriptor?.close()
        }

        val mediaUri = getOrCreateMediaFile(
            name = name,
            mimeType = mimeType,
            relativePath = SCOPED_STORAGE_RELATIVE_PATH,
        )

        mediaFileFileDescriptor = context.contentResolver.openFileDescriptor(mediaUri, "w")!!

        return mediaFileFileDescriptor!!.fileDescriptor
    }

    companion object {
        fun viaInternalFolder(context: Context) = AudioBatchesFolder(context, BatchType.INTERNAL)

        fun viaCustomFolder(context: Context, folder: DocumentFile) =
            AudioBatchesFolder(context, BatchType.CUSTOM, folder)

        fun viaMediaFolder(context: Context) = AudioBatchesFolder(context, BatchType.MEDIA)

        fun importFromFolder(folder: String, context: Context) = when (folder) {
            RECORDER_INTERNAL_SELECTED_VALUE -> viaInternalFolder(context)
            RECORDER_MEDIA_SELECTED_VALUE -> viaMediaFolder(context)
            else -> viaCustomFolder(context, DocumentFile.fromTreeUri(context, Uri.parse(folder))!!)
        }

        val BASE_LEGACY_STORAGE_FOLDER = Environment.DIRECTORY_PODCASTS
        val MEDIA_RECORDINGS_SUBFOLDER = MEDIA_SUBFOLDER_NAME + "/.audio_recordings"
        val BASE_SCOPED_STORAGE_RELATIVE_PATH =
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Environment.DIRECTORY_RECORDINGS
            else
                Environment.DIRECTORY_PODCASTS)
        val SCOPED_STORAGE_RELATIVE_PATH =
            BASE_SCOPED_STORAGE_RELATIVE_PATH + "/" + MEDIA_RECORDINGS_SUBFOLDER

        // Don't use those values directly, use the constants from the instance.
        // Those values are only used inside the `SaveFolderTile`
        val SCOPED_MEDIA_CONTENT_URI = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val LEGACY_MEDIA_FOLDER = File(
            Environment.getExternalStoragePublicDirectory(BASE_LEGACY_STORAGE_FOLDER),
            MEDIA_RECORDINGS_SUBFOLDER,
        )


        // Parameters to be passed in descending order
        // Those parameters first try to concatenate without re-encoding
        // if that fails, it'll try several fallback methods
        // this is audio only
        val FFMPEG_PARAMETERS = arrayOf(
            " -c copy",
            " -acodec copy",
            " -c:a aac",
            " -c:a libmp3lame",
            " -c:a libopus",
            " -c:a libvorbis",
        )
    }
}
