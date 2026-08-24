package app.leo.alibi_cam.ui.components.RecorderScreen.organisms

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.leo.alibi_cam.R
import app.leo.alibi_cam.dataStore
import app.leo.alibi_cam.db.AppSettings
import app.leo.alibi_cam.db.RecordingInformation
import app.leo.alibi_cam.helpers.AudioBatchesFolder
import app.leo.alibi_cam.helpers.BatchesFolder
import app.leo.alibi_cam.helpers.CameraPosition
import app.leo.alibi_cam.helpers.VideoBatchesFolder
import app.leo.alibi_cam.services.IntervalRecorderService
import app.leo.alibi_cam.services.VideoRecorderService
import app.leo.alibi_cam.ui.components.RecorderScreen.atoms.BatchesInaccessibleDialog
import app.leo.alibi_cam.ui.components.RecorderScreen.atoms.RecorderErrorDialog
import app.leo.alibi_cam.ui.components.RecorderScreen.atoms.RecorderProcessingDialog
import app.leo.alibi_cam.ui.effects.rememberOpenUri
import app.leo.alibi_cam.ui.models.AudioRecorderModel
import app.leo.alibi_cam.ui.models.BaseRecorderModel
import app.leo.alibi_cam.ui.models.VideoRecorderModel
import app.leo.alibi_cam.ui.utils.rememberFileSaverDialog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Timer
import kotlin.concurrent.schedule
import kotlin.concurrent.thread

typealias RecorderModel = BaseRecorderModel<
        RecordingInformation,
        BatchesFolder,
        IntervalRecorderService<RecordingInformation, BatchesFolder>,
        >

@Composable
fun RecorderEventsHandler(
    settings: AppSettings,
    snackbarHostState: SnackbarHostState,
    audioRecorder: AudioRecorderModel,
    videoRecorder: VideoRecorderModel,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val dataStore = context.dataStore

    var isProcessing by remember { mutableStateOf(false) }
    var showRecorderError by remember { mutableStateOf(false) }
    var showBatchesInaccessibleError by remember { mutableStateOf(false) }

    var processingProgress by remember { mutableStateOf<Float?>(null) }

    val saveAudioFile = rememberFileSaverDialog(settings.audioRecorderSettings.getMimeType()) {
        if (settings.deleteRecordingsImmediately) {
            runCatching {
                audioRecorder.batchesFolder?.deleteRecordings()
            }
        }

        if (audioRecorder.batchesFolder?.hasRecordingsAvailable() == false) {
            scope.launch {
                dataStore.updateData {
                    it.setLastRecording(null)
                }
            }
        }
    }
    val saveVideoFile = rememberFileSaverDialog(settings.videoRecorderSettings.getMimeType()) {
        if (videoRecorder.batchesFolder?.hasRecordingsAvailable() == false) {
            scope.launch {
                dataStore.updateData {
                    it.setLastRecording(null)
                }
            }
        }
    }

    suspend fun saveAsLastRecording(
        recorder: RecorderModel
    ) {
        if (!settings.deleteRecordingsImmediately) {
            val information = recorder.recorderService?.getRecordingInformation()

            if (information == null) {
                Log.e("RecorderEventsHandler", "Recording information is null")
                return
            }

            dataStore.updateData {
                it.setLastRecording(
                    information
                )
            }
        }
    }

    val successMessage = stringResource(R.string.ui_recorder_action_save_success)
    val openMessage = stringResource(R.string.ui_recorder_action_save_openFolder)

    val openFolder = rememberOpenUri()

    fun showSnackbar() {
        scope.launch {
            snackbarHostState.showSnackbar(
                message = successMessage,
                duration = SnackbarDuration.Short,
            )
        }
    }

    fun showSnackbar(uri: Uri) {
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = successMessage,
                actionLabel = openMessage,
                duration = SnackbarDuration.Short,
            )

            if (result == SnackbarResult.ActionPerformed) {
                openFolder(uri)
            }
        }
    }

    fun saveRecording(
        recorder: RecorderModel,
        cleanupOldFiles: Boolean = false
    ): CompletableDeferred<Unit> {
        val completer = CompletableDeferred<Unit>()

        // If processing takes this short, don't show the processing dialog
        val timer = Timer().schedule(250L) {
            isProcessing = true
        }

        thread {
            runBlocking {
                try {
                    if (recorder.isCurrentlyActivelyRecording) {
                        recorder.recorderService?.lockFiles()
                    }

                    val activeRecording = recorder.recorderService?.getRecordingInformation()
                    val recording =
                        // When new recording created
                        activeRecording
                        // When recording is loaded from lastRecording
                            ?: settings.lastRecording
                            ?: throw Exception("No recording information available")

                    val batchesFolder = when (recorder.javaClass) {
                        AudioRecorderModel::class.java -> AudioBatchesFolder.importFromFolder(
                            recording.folderPath,
                            context
                        )

                        VideoRecorderModel::class.java -> {
                            val videoModel = recorder as VideoRecorderModel
                            val primaryPosition = when {
                                activeRecording != null -> videoModel.recorderService
                                    ?.batchesFolder
                                    ?.cameraPosition
                                    ?: CameraPosition.SINGLE

                                recording.primaryCameraPositionName != null -> CameraPosition.valueOf(
                                    recording.primaryCameraPositionName
                                )

                                recording.sessionId != null &&
                                    settings.videoRecorderSettings.dualCameraEnabled
                                -> CameraPosition.fromLensString(
                                    settings.videoRecorderSettings.cameraLens
                                )

                                else -> CameraPosition.SINGLE
                            }
                            Log.i(
                                "RecorderEventsHandler",
                                "🎞️ Import primary video folder: position=$primaryPosition " +
                                    "suffix=${primaryPosition.folderSuffix} session=${recording.sessionId} " +
                                    "folder=${recording.folderPath}",
                            )
                            VideoBatchesFolder.importFromFolder(
                                recording.folderPath,
                                context,
                                primaryPosition,
                            ).also {
                            // Restore sessionId so getBatchesForFFmpeg() filters
                            // only this session's chunks.
                            it.sessionId = recording.sessionId
                                Log.i(
                                    "RecorderEventsHandler",
                                    "🎞️ Primary folder ready: position=${it.cameraPosition} " +
                                        "subfolder=${it.subfolderName} session=${it.sessionId}",
                                )
                            }
                        }

                        else -> throw Exception("Unknown recorder type")
                    }

                    val fileName = batchesFolder.getName(
                        recording.recordingStart,
                        recording.fileExtension,
                    )

                    var primaryMergedInternalFile: java.io.File? = null
                    val videoRecorderModel = (recorder as Any) as? VideoRecorderModel
                    if (videoRecorderModel != null) {
                        val primaryVideoFolder = batchesFolder as? VideoBatchesFolder
                            ?: throw Exception("Video recorder has non-video batches folder")
                        val persistedSecondaryPosition = recording.secondaryCameraPositionName
                            ?.let(CameraPosition::valueOf)
                        val videoFolders = buildList {
                            add(primaryVideoFolder)
                            val liveSecondaryFolder = (videoRecorderModel.recorderService as? VideoRecorderService)
                                ?.secondaryBatchesFolder
                            when {
                                liveSecondaryFolder != null -> add(liveSecondaryFolder)

                                recording.secondarySessionId != null -> add(
                                    VideoBatchesFolder.importFromFolder(
                                        recording.secondaryFolderPath,
                                        context,
                                        persistedSecondaryPosition ?: CameraPosition.SINGLE,
                                    ).also { folder ->
                                        folder.sessionId = recording.secondarySessionId
                                        Log.i(
                                            "RecorderEventsHandler",
                                            "🎞️ Restored secondary video folder: position=${folder.cameraPosition} " +
                                                "session=${folder.sessionId} folder=${recording.secondaryFolderPath}",
                                        )
                                    }
                                )
                            }
                        }

                        videoFolders.forEachIndexed { index, folder ->
                            val sourceChunkNames = folder.listSessionChunkNames()
                            if (sourceChunkNames.isEmpty()) {
                                Log.w(
                                    "RecorderEventsHandler",
                                    "🎞️ Skipping empty video stream=$index position=${folder.cameraPosition} " +
                                        "session=${folder.sessionId}",
                                )
                                if (index == 0) {
                                    throw Exception("Primary video stream has no source chunks")
                                }
                                return@forEachIndexed
                            }
                            val outputFileName = if (index == 0) {
                                fileName
                            } else {
                                val baseName = fileName.substringBeforeLast('.')
                                val suffix = folder.cameraPosition.fileTag.ifBlank { "secondary" }
                                "$baseName-$suffix.${recording.fileExtension}"
                            }

                            Log.i(
                                "RecorderEventsHandler",
                                "🎞️ Merge video stream=$index type=${folder.type} " +
                                    "session=${folder.sessionId} chunks=${sourceChunkNames.size} " +
                                    "position=${folder.cameraPosition} subfolder=${folder.subfolderName} " +
                                    "output=$outputFileName",
                            )

                            val output = try {
                                folder.concatenate(
                                    recording,
                                    filenameFormat = settings.filenameFormat,
                                    fileName = outputFileName,
                                    onProgress = { percentage ->
                                        processingProgress = percentage
                                    }
                                )
                            } catch (error: Exception) {
                                Log.e(
                                    "RecorderEventsHandler",
                                    "🎞️ Merge failed stream=$index; source chunks preserved " +
                                        "count=${sourceChunkNames.size}",
                                    error,
                                )
                                throw error
                            }

                            if (index == 0 && folder.type == BatchesFolder.BatchType.INTERNAL) {
                                primaryMergedInternalFile = folder.asInternalGetOutputFile(outputFileName)
                            }

                            if (settings.deleteRecordingsImmediately && sourceChunkNames.isNotEmpty()) {
                                folder.permanentlyDeleteRecordings = settings.permanentlyDeleteRecordings
                                val deleted = folder.deleteFlatChunks(sourceChunkNames)
                                Log.i(
                                    "RecorderEventsHandler",
                                    "🧹 Deleted merged source chunks stream=$index " +
                                        "deleted=$deleted/${sourceChunkNames.size} output=$output",
                                )
                            } else {
                                Log.i(
                                    "RecorderEventsHandler",
                                    "🧹 Preserved merged source chunks stream=$index " +
                                        "count=${sourceChunkNames.size} immediateDelete=" +
                                        settings.deleteRecordingsImmediately,
                                )
                            }
                    }
					} else {
                        val audioFolder = batchesFolder as? AudioBatchesFolder
                            ?: throw Exception("Audio recorder has non-audio batches folder")
                        val sourceChunkNames = audioFolder.listChunkNames()
                        if (sourceChunkNames.isEmpty()) {
                            throw Exception("Primary audio stream has no source chunks")
                        }

                        Log.i(
                            "RecorderEventsHandler",
                            "🎧 Merge audio type=${audioFolder.type} chunks=${sourceChunkNames.size} " +
                                "output=$fileName",
                        )

                        try {
                            batchesFolder.concatenate(
                                recording,
                                filenameFormat = settings.filenameFormat,
                                fileName = fileName,
                                onProgress = { percentage ->
                                    processingProgress = percentage
                                }
                            )
                        } catch (error: Exception) {
                            Log.e(
                                "RecorderEventsHandler",
                                "🎧 Merge failed; source chunks preserved count=${sourceChunkNames.size}",
                                error,
                            )
                            throw error
                        }

                        if (settings.deleteRecordingsImmediately) {
                            audioFolder.permanentlyDeleteRecordings =
                                settings.permanentlyDeleteRecordings
                            val deleted = audioFolder.deleteFlatChunks(sourceChunkNames)
                            Log.i(
                                "RecorderEventsHandler",
                                "🧹 Deleted merged source audio chunks deleted=$deleted/" +
                                    "${sourceChunkNames.size}",
                            )
                        } else {
                            Log.i(
                                "RecorderEventsHandler",
                                "🧹 Preserved merged source audio chunks count=${sourceChunkNames.size} " +
                                    "immediateDelete=false",
                            )
                        }
                    }

                    // Save file
                    when (batchesFolder.type) {
                        BatchesFolder.BatchType.INTERNAL -> {
                            // Export the merged primary file. Secondary dual output
                            // remains in its separate position folder.
                            // SAF launch MUST be on the main thread — we are inside
                            // thread { runBlocking { } } which is a background thread.
                            val mainHandler = Handler(Looper.getMainLooper())
                            val mergedFile = when (batchesFolder) {
                                is AudioBatchesFolder -> batchesFolder.asInternalGetOutputFile(fileName)
                                is VideoBatchesFolder -> primaryMergedInternalFile
                                else -> null
                            }
                            if (mergedFile?.isFile == true) {
                                mainHandler.post {
                                    when (batchesFolder) {
                                        is AudioBatchesFolder -> saveAudioFile(mergedFile, fileName)
                                        is VideoBatchesFolder -> saveVideoFile(mergedFile, fileName)
                                    }
                                }
                            } else {
                                Log.w(
                                    "RecorderEventsHandler",
                                    "🎞️ Merged internal video output unavailable: $fileName",
                                )
                            }
                        }

                        BatchesFolder.BatchType.CUSTOM -> {
                            showSnackbar(batchesFolder.customFolder!!.uri)
                        }

                        BatchesFolder.BatchType.MEDIA -> {
                            showSnackbar()
                        }
                    }
                } catch (error: Exception) {
                    Log.e("RecorderEventsHandler", "Recording save/export failed", error)
                } finally {
                    if (recorder.isCurrentlyActivelyRecording) {
                        recorder.recorderService?.unlockFiles(cleanupOldFiles)
                    }
                    timer.cancel()
                    isProcessing = false
                    processingProgress = null
                    completer.complete(Unit)
                }
            }
        }

        return completer
    }

    // Register audio recorder events
    // Absolutely no idea, but somehow on some devices the `DisposableEffect`
    // is registered twice, and THEN disposed once (AFTER being called twice),
    // which then causes the `onRecordingSave` to be in a weird state.
    // This variable is a workaround to prevent this from happening.
    var previousAudioSettings: AppSettings? = null
    DisposableEffect(settings) {
        if (previousAudioSettings == settings) {
            onDispose { }
        } else {
            previousAudioSettings = settings
            audioRecorder.onRecordingSave = { cleanupOldFiles ->
                saveRecording(audioRecorder as RecorderModel, cleanupOldFiles)
            }
            audioRecorder.onRecordingStart = {
                snackbarHostState.currentSnackbarData?.dismiss()
            }
            audioRecorder.onError = {
                scope.launch {
                    saveAsLastRecording(audioRecorder as RecorderModel)

                    runCatching {
                        audioRecorder.stopRecording(context)
                    }
                    runCatching {
                        audioRecorder.destroyService(context)
                    }

                    showRecorderError = true
                }
            }
            audioRecorder.onBatchesFolderNotAccessible = {
                scope.launch {
                    showBatchesInaccessibleError = true

                    runCatching {
                        audioRecorder.stopRecording(context)
                    }
                    runCatching {
                        audioRecorder.destroyService(context)
                    }
                }
            }

            onDispose {
                audioRecorder.onRecordingSave = {
                    throw NotImplementedError("onRecordingSave should not be called now")
                }
                audioRecorder.onError = {}
            }
        }
    }

    // Register video recorder events
    var previousVideoSettings: AppSettings? = null
    DisposableEffect(settings) {
        if (previousVideoSettings == settings) {
            onDispose { }
        } else {
            previousVideoSettings = settings
            Log.i("Alibi", "===== Registering videoRecorder events $videoRecorder")
            videoRecorder.onRecordingSave = { cleanupOldFiles ->
                saveRecording(videoRecorder as RecorderModel, cleanupOldFiles)
            }
            videoRecorder.onRecordingStart = {
                snackbarHostState.currentSnackbarData?.dismiss()
            }
            videoRecorder.onError = {
                scope.launch {
                    saveAsLastRecording(videoRecorder as RecorderModel)

                    runCatching {
                        videoRecorder.stopRecording(context)
                    }
                    runCatching {
                        videoRecorder.destroyService(context)
                    }

                    showRecorderError = true
                }
            }
            videoRecorder.onBatchesFolderNotAccessible = {
                scope.launch {
                    showBatchesInaccessibleError = true

                    runCatching {
                        videoRecorder.stopRecording(context)
                    }
                    runCatching {
                        videoRecorder.destroyService(context)
                    }
                }
            }

            onDispose {
                Log.i("Alibi", "===== Disposing videoRecorder events")
                videoRecorder.onRecordingSave = {
                    throw NotImplementedError("onRecordingSave should not be called now")
                }
                videoRecorder.onError = {}
            }
        }
    }

    if (isProcessing)
        RecorderProcessingDialog(
            progress = processingProgress,
        )

    if (showBatchesInaccessibleError)
        BatchesInaccessibleDialog(
            onClose = {
                showBatchesInaccessibleError = false
            },
        )
    else if (showRecorderError)
        RecorderErrorDialog(
            onClose = {
                showRecorderError = false
            },
        )
}
