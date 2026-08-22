package app.leo.alibi_cam.ui.models

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import app.leo.alibi_cam.db.AppSettings
import app.leo.alibi_cam.db.RecordingInformation
import app.leo.alibi_cam.enums.RecorderState
import app.leo.alibi_cam.helpers.CameraPosition
import app.leo.alibi_cam.helpers.Doctor
import app.leo.alibi_cam.helpers.VideoBatchesFolder
import app.leo.alibi_cam.services.VideoRecorderService
import app.leo.alibi_cam.ui.RECORDER_MEDIA_SELECTED_VALUE
import app.leo.alibi_cam.ui.utils.CameraInfo
import app.leo.alibi_cam.ui.utils.PermissionHelper

class VideoRecorderModel :
    BaseRecorderModel<RecordingInformation, VideoBatchesFolder, VideoRecorderService>() {
    override var batchesFolder: VideoBatchesFolder? = null
    override val intentClass = VideoRecorderService::class.java

    private companion object {
        const val TAG = "VideoRecorderModel"
    }

    var enableAudio by mutableStateOf(true)
    var cameraID by mutableIntStateOf(CameraSelector.LENS_FACING_BACK)
    var cameraLensMode: String? = null  // "ultrawide" | "main" | null
    var secondaryCameraLens: String? = null

    override val isInRecording: Boolean
        get() = super.isInRecording

    val isDualCameraActive: Boolean
        get() = recorderService?.isDualCameraActive() == true

    var isStartingRecording by mutableStateOf(true)
        private set

    val cameraSelector: CameraSelector
        get() = CameraInfo.buildCameraSelector(cameraID)

    private fun createBatchesFolder(
        context: Context,
        settings: AppSettings,
        position: CameraPosition = CameraPosition.SINGLE,
    ): VideoBatchesFolder = when (settings.saveFolder) {
        null -> VideoBatchesFolder.viaInternalFolder(context, position)
        RECORDER_MEDIA_SELECTED_VALUE -> VideoBatchesFolder.viaMediaFolder(context, position)
        else -> VideoBatchesFolder.viaCustomFolder(
            context,
            DocumentFile.fromTreeUri(
                context,
                Uri.parse(settings.saveFolder)
            )!!,
            position,
        )
    }

    /**
     * Initialize camera selection. Prefers zoom-based ultra-wide if available,
     * respects user's camera lens preference from settings.
     */
    fun init(context: Context, settings: AppSettings? = null) {
        enableAudio = PermissionHelper.hasGranted(context, Manifest.permission.RECORD_AUDIO)

        val cameras = CameraInfo.queryAvailableCameras(context)
        val preference = settings?.videoRecorderSettings?.cameraLens ?: "auto"
        secondaryCameraLens = settings?.videoRecorderSettings?.secondaryCameraLens

        Log.i(TAG, "🎬 init: preference=$preference, secondary=$secondaryCameraLens, cameras found=${cameras.size}")

        val (lensFacing, lensMode) = CameraInfo.resolveCamera(preference, cameras)
        cameraID = lensFacing
        cameraLensMode = lensMode

        Log.i(TAG, "🎬 init result: cameraID=${
            when(cameraID) {
                CameraSelector.LENS_FACING_BACK -> "BACK"
                CameraSelector.LENS_FACING_FRONT -> "FRONT"
                else -> cameraID.toString()
            }
        }, cameraLensMode=${cameraLensMode ?: "null"}")
    }

    override fun startRecording(context: Context, settings: AppSettings) {
        // Apply camera preference from settings if not already initialized via sheet
        val cameras = CameraInfo.queryAvailableCameras(context)
        val preference = settings.videoRecorderSettings.cameraLens ?: "auto"
        val (lensFacing, lensMode) = CameraInfo.resolveCamera(preference, cameras)
        cameraID = lensFacing
        cameraLensMode = lensMode
        secondaryCameraLens = settings.videoRecorderSettings.secondaryCameraLens

        Log.i(TAG, "🎬 startRecording: preference=$preference, secondary=$secondaryCameraLens, cameraID=${
            when(cameraID) {
                CameraSelector.LENS_FACING_BACK -> "BACK"
                CameraSelector.LENS_FACING_FRONT -> "FRONT"
                else -> cameraID.toString()
            }
        }, cameraLensMode=${cameraLensMode ?: "null"}")

        val dualMode = settings.videoRecorderSettings.dualCameraEnabled
        val primaryPosition = if (dualMode) {
            CameraPosition.fromLensString(settings.videoRecorderSettings.cameraLens)
        } else {
            CameraPosition.SINGLE
        }

        batchesFolder = createBatchesFolder(context, settings, primaryPosition)

        super.startRecording(context, settings)
    }

    override fun onServiceConnected(service: VideoRecorderService) {
        // `onServiceConnected` may be called when reconnecting to the service,
        // so we only want to actually start the recording if the service is idle and thus
        // not already recording
        if (service.state == RecorderState.IDLE) {
            isStartingRecording = true

            // For dual camera mode, create and attach the secondary folder
            settings?.let { appSettings ->
                if (appSettings.videoRecorderSettings.dualCameraEnabled &&
                    appSettings.videoRecorderSettings.secondaryCameraLens != null) {
                    val secPosition = CameraPosition.fromLensString(appSettings.videoRecorderSettings.secondaryCameraLens)
                    service.secondaryBatchesFolder = createBatchesFolder(
                        context = service,
                        settings = appSettings,
                        position = secPosition,
                    ).also { it.initFolders() }
                    Log.i(TAG, "🎬 Attached secondary batches folder for position: $secPosition")
                } else {
                    service.secondaryBatchesFolder = null
                }
            }

            // Do NOT wipe prior recordings — rolling-window pruning
            // (deleteOldRecordings) handles storage limits across sessions.
            service.startRecording()
            onRecordingStart()
        } else {
            isStartingRecording = false
        }

        service.onCameraControlAvailable = {
            isStartingRecording = false
        }

        recorderState = service.state
        recordingTime = service.recordingTime
    }

    override fun handleIntent(intent: Intent) =
        intent.apply {
            putExtra("cameraID", cameraID)
            putExtra("cameraLensMode", cameraLensMode)
            putExtra("enableAudio", enableAudio)
        }
}
