package app.leo.alibi_cam.helpers

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CompletableDeferred
import java.io.Closeable
import java.io.File
import java.util.UUID

data class FFmpegInputPaths(
    val paths: List<String>,
    private val closeables: List<Closeable> = emptyList(),
) : Closeable {
    override fun close() {
        closeables.asReversed().forEach { closeable ->
            runCatching { closeable.close() }
        }
    }
}

data class FFmpegOutputTarget(
    val path: String,
    val format: String? = null,
    private val closeables: List<Closeable> = emptyList(),
    val onSuccess: () -> String = { path },
    val onFailure: () -> Unit = {},
) : Closeable {
    override fun close() {
        closeables.asReversed().forEach { closeable ->
            runCatching { closeable.close() }
        }
    }
}

typealias ConcatenationFunction = (
    Iterable<String>,
    FFmpegOutputTarget,
    String,
    (Int) -> Unit,
    FFmpegInputPaths,
) -> CompletableDeferred<Unit>

fun ffmpegFormatForExtension(extension: String): String? = when (extension.lowercase()) {
    "aac" -> "adts"
    "3gp" -> "3gp"
    "mp4" -> "mp4"
    "ts" -> "mpegts"
    "webm" -> "webm"
    "amr" -> "amr"
    "awb" -> "amr_wb"
    "ogg" -> "ogg"
    "raw" -> "3gp"
    else -> null
}

// Abstract class for concatenating audio and video files
// The concatenator runs in its own thread to avoid unresponsiveness.
// You may be wondering why we simply not iterate over the FFMPEG_PARAMETERS
// in this thread and then call each FFmpeg initiation just right after it?
// The answer: It's easier; We don't have to deal with the `getBatchesForFFmpeg` function, because
// the batches are only usable once and we if iterate in this thread over the FFMPEG_PARAMETERS
// we would need to refetch the batches here, which is more messy.
// This is okay, because in 99% of the time the first or second parameter will work,
// and so there is no real performance loss.
abstract class Concatenator(
    private val inputFiles: Iterable<String>,
    private val outputFile: String,
    private val extraCommand: String
) : Thread() {
    abstract fun concatenate(): CompletableDeferred<Unit>

    class FFmpegException(message: String) : Exception(message)
}

data class AudioConcatenator(
    private val inputFiles: Iterable<String>,
    private val outputFile: String,
    private val extraCommand: String
) : Concatenator(
    inputFiles,
    outputFile,
    extraCommand
) {
    override fun concatenate(): CompletableDeferred<Unit> {
        val completer = CompletableDeferred<Unit>()

        val filePathsConcatenated = inputFiles.joinToString("|")
        val command =
            "-protocol_whitelist saf,concat,content,file,subfile" +
                    " -i 'concat:$filePathsConcatenated'" +
                    " -y" +
                    extraCommand +
                    " $outputFile"

        FFmpegKit.executeAsync(
            command
        ) { session ->
            if (!ReturnCode.isSuccess(session!!.returnCode)) {
                Log.i(
                    "Audio Concatenation",
                    String.format(
                        "Command failed with state %s and rc %s.%s",
                        session.state,
                        session.returnCode,
                        session.failStackTrace,
                    )
                )

                completer.completeExceptionally(Exception("Failed to concatenate audios"))
            } else {
                completer.complete(Unit)
            }
        }

        return completer
    }
}


class MediaConverter {
    companion object {
        fun concatenateAudioFiles(
            inputFiles: Iterable<String>,
            outputTarget: FFmpegOutputTarget,
            extraCommand: String = "",
            onProgress: (Int) -> Unit = { },
            _inputPaths: FFmpegInputPaths = FFmpegInputPaths(emptyList()),
        ): CompletableDeferred<Unit> {
            val completer = CompletableDeferred<Unit>()

            val listFile = createTempFile(inputFiles.joinToString("\n") { asConcatFileEntry(it) })
            val command =
                "-protocol_whitelist saf,concat,content,file,subfile,fd" +
                        " -strict normal" +
                        " -safe 0" +
                        " -f concat" +
                        " -i ${listFile.absolutePath}" +
                        extraCommand +
                        " -y" +
                        " ${asFFmpegOutputFile(outputTarget)}"

            FFmpegKit.executeAsync(
                command,
                { session ->
                    runCatching { listFile.delete() }
                    if (session == null || !ReturnCode.isSuccess(session.returnCode)) {
                        Log.i(
                            "Audio Concatenation",
                            "Command failed with rc=${session?.returnCode} " +
                                "logs=${session?.allLogsAsString?.takeLast(MAX_FFMPEG_FAILURE_LOG_CHARS)}",
                        )

                        completer.completeExceptionally(FFmpegException("Failed to concatenate audios"))
                    } else {
                        completer.complete(Unit)
                    }
                },
                {},
                { statistics ->
                    onProgress(statistics.time.toInt())
                }
            )

            return completer
        }

        private fun createTempFile(content: String): File {
            val id = UUID.randomUUID().toString()

            return File.createTempFile(".temp-ffmpeg-files-$id", ".txt").apply {
                writeText(content)
            }
        }

        private fun String.asFileDescriptorNumber(): Int? {
            if (!startsWith(FD_INPUT_PREFIX)) return null
            return removePrefix(FD_INPUT_PREFIX).toIntOrNull()
        }

        private fun asConcatFileEntry(inputFile: String): String {
            val descriptor = inputFile.asFileDescriptorNumber()
            return if (descriptor == null) {
                "file '$inputFile'"
            } else {
                // The concat demuxer must be told which inherited fd to read.
                "file 'fd:'\noption fd $descriptor"
            }
        }

        private fun asFFmpegOutputFile(outputTarget: FFmpegOutputTarget): String {
            val descriptor = outputTarget.path.asFileDescriptorNumber()
            return if (descriptor == null) {
                outputTarget.path
            } else {
                val format = outputTarget.format?.let { "-f $it " } ?: ""
                "$format-fd $descriptor fd:"
            }
        }

        fun concatenateVideoFiles(
            inputFiles: Iterable<String>,
            outputTarget: FFmpegOutputTarget,
            extraCommand: String = "",
            onProgress: (Int) -> Unit = { },
            _inputPaths: FFmpegInputPaths = FFmpegInputPaths(emptyList()),
        ): CompletableDeferred<Unit> {
            val completer = CompletableDeferred<Unit>()

            val listFile = createTempFile(inputFiles.joinToString("\n") { asConcatFileEntry(it) })

            val command =
                "-protocol_whitelist saf,concat,content,file,subfile,fd" +
                        " -safe 0" +
                        " -strict normal" +
                        " -f concat" +
                        " -i ${listFile.absolutePath}" +
                        extraCommand +
                        " -y" +
                        " ${asFFmpegOutputFile(outputTarget)}"

            FFmpegKit.executeAsync(
                command,
                { session ->
                    runCatching {
                        listFile.delete()
                    }

                    if (session != null && ReturnCode.isSuccess(session.returnCode)) {
                        completer.complete(Unit)
                    } else {
                        Log.i(
                            "Video Concatenation",
                            "Command failed with rc=${session?.returnCode} " +
                                "logs=${session?.allLogsAsString?.takeLast(MAX_FFMPEG_FAILURE_LOG_CHARS)}",
                        )

                        completer.completeExceptionally(FFmpegException("Failed to concatenate videos"))
                    }
                },
                {},
                { statistics ->
                    onProgress(statistics.time.toInt())
                }
            )

            return completer
        }

        private const val FD_INPUT_PREFIX = "fd:"
        private const val MAX_FFMPEG_FAILURE_LOG_CHARS = 8000
    }

    class FFmpegException(message: String) : Exception(message)
}
