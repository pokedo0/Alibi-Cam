package app.leo.alibi_cam.ui.models

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import app.leo.alibi_cam.db.AppSettings
import app.leo.alibi_cam.db.RecordingInformation
import app.leo.alibi_cam.enums.RecorderState
import app.leo.alibi_cam.helpers.AudioBatchesFolder
import app.leo.alibi_cam.helpers.VideoBatchesFolder
import app.leo.alibi_cam.services.AudioRecorderService
import app.leo.alibi_cam.ui.RECORDER_MEDIA_SELECTED_VALUE
import app.leo.alibi_cam.ui.utils.MicrophoneInfo

class AudioRecorderModel :
    BaseRecorderModel<RecordingInformation, AudioBatchesFolder, AudioRecorderService>() {
    override var batchesFolder: AudioBatchesFolder? = null
    override val intentClass = AudioRecorderService::class.java

    var amplitudes by mutableStateOf<List<Int>>(emptyList())
        private set
    var selectedMicrophone by mutableStateOf<MicrophoneInfo?>(null)
        private set

    var onAmplitudeChange: () -> Unit = {}

    var microphoneStatus: MicrophoneConnectivityStatus = MicrophoneConnectivityStatus.CONNECTED
        private set

    enum class MicrophoneConnectivityStatus {
        CONNECTED,
        DISCONNECTED
    }

    override fun onServiceConnected(service: AudioRecorderService) {
        service.onSelectedMicrophoneChange = { microphone ->
            selectedMicrophone = microphone
        }
        service.onMicrophoneDisconnected = {
            microphoneStatus = MicrophoneConnectivityStatus.DISCONNECTED
        }
        service.onMicrophoneReconnected = {
            microphoneStatus = MicrophoneConnectivityStatus.CONNECTED
        }
        service.onAmplitudeChange = { amps ->
            amplitudes = amps
            onAmplitudeChange()
        }

        // `onServiceConnected` may be called when reconnecting to the service,
        // so we only want to actually start the recording if the service is idle and thus
        // not already recording
        if (service.state == RecorderState.IDLE && settings != null) {
            // Do NOT wipe prior recordings — rolling-window pruning
            // (deleteOldRecordings) handles storage limits across sessions.
            service.startRecording()
            onRecordingStart()
        } else if (service.state == RecorderState.IDLE && settings == null) {
            Log.i("AudioRecorderModel", "Skipping idle audio start; model has no caller-supplied settings")
        }

        recorderState = service.state
        recordingTime = service.recordingTime
        amplitudes = service.amplitudes
        selectedMicrophone = service.selectedMicrophone
    }

    override fun startRecording(context: Context, settings: AppSettings) {
        batchesFolder = when (settings.saveFolder) {
            null -> AudioBatchesFolder.viaInternalFolder(context)
            RECORDER_MEDIA_SELECTED_VALUE -> AudioBatchesFolder.viaMediaFolder(context)
            else -> AudioBatchesFolder.viaCustomFolder(
                context,
                DocumentFile.fromTreeUri(
                    context,
                    Uri.parse(settings.saveFolder)
                )!!
            )
        }

        super.startRecording(context, settings)
    }

    override fun reset() {
        super.reset()
        amplitudes = emptyList()
        selectedMicrophone = null
        microphoneStatus = MicrophoneConnectivityStatus.CONNECTED
    }

    fun setMaxAmplitudesAmount(amount: Int) {
        recorderService?.amplitudesAmount = amount
    }

    fun changeMicrophone(microphone: MicrophoneInfo?) {
        recorderService!!.changeMicrophone(microphone)

        if (microphone == null) {
            // Microphone was reset to default,
            // default is always assumed to be connected
            microphoneStatus = MicrophoneConnectivityStatus.CONNECTED
        }
    }
}
