package app.leo.alibi_cam.services

import android.content.Context
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.MediaRecorder.OnErrorListener
import android.util.Log
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.lifecycle.lifecycleScope
import app.leo.alibi_cam.NotificationHelper
import app.leo.alibi_cam.dataStore
import app.leo.alibi_cam.db.RecordingInformation
import app.leo.alibi_cam.enums.RecorderState
import app.leo.alibi_cam.helpers.AudioBatchesFolder
import app.leo.alibi_cam.helpers.BatchesFolder
import app.leo.alibi_cam.ui.RECORDER_INTERNAL_SELECTED_VALUE
import app.leo.alibi_cam.ui.utils.MicrophoneInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudioRecorderService :
    IntervalRecorderService<RecordingInformation, AudioBatchesFolder>() {
    override var batchesFolder = AudioBatchesFolder.viaInternalFolder(this)

    private val handler = Handler(Looper.getMainLooper())

    var amplitudes = mutableListOf<Int>()
        private set
    var amplitudesAmount = 1000

    var selectedMicrophone: MicrophoneInfo? = null

    var recorder: MediaRecorder? = null
        private set

    // Callbacks
    var onSelectedMicrophoneChange: (MicrophoneInfo?) -> Unit = {}
    var onMicrophoneDisconnected: () -> Unit = {}
    var onMicrophoneReconnected: () -> Unit = {}
    var onAmplitudeChange: ((List<Int>) -> Unit)? = null

    override fun startNewCycle() {
        super.startNewCycle()

        val newRecorder = createRecorder().also {
            it.prepare()
        }

        resetRecorder()
        startAudioDevice()

        try {
            recorder = newRecorder
            newRecorder.start()
        } catch (error: RuntimeException) {
            onError()
        }
    }

    override fun start() {
        super.start()

        createAmplitudesTimer()
        registerMicrophoneListener()
    }

    override fun pause() {
        super.pause()

        resetRecorder()
    }

    override suspend fun stop() {
        resetRecorder()
        unregisterMicrophoneListener()

        super.stop()
    }

    override fun resume() {
        super.resume()
        createAmplitudesTimer()
    }

    override fun handleStopFromNotification() {
        lifecycleScope.launch {
            try {
                Log.i(TAG, "Notification stop requested; finalizing audio recording")
                stopRecording()

                val info = getRecordingInformation()
                dataStore.updateData { it.setLastRecording(info) }
                Log.i(
                    TAG,
                    "Persisted audio recording metadata; batches=${info.batchesAmount} " +
                        "folder=${info.folderPath}",
                )

                val fullyMerged = mergeInBackground(info)
                if (fullyMerged && info.folderPath != RECORDER_INTERNAL_SELECTED_VALUE) {
                    dataStore.updateData { it.setLastRecording(null) }
                    Log.i(TAG, "Audio notification merge completed; recovery metadata cleared")
                } else if (fullyMerged) {
                    Log.i(TAG, "Audio notification merge completed; internal output retained")
                }
            } catch (error: Exception) {
                Log.e(TAG, "Audio notification stop/save failed", error)
            } finally {
                destroy()
            }
        }
    }

    override fun startForegroundService() {
        ServiceCompat.startForeground(
            this,
            NotificationHelper.RECORDER_CHANNEL_NOTIFICATION_ID,
            getNotificationHelper().buildStartingNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            },
        )
    }

    // ==== Amplitude related ====
    private fun getAmplitudeAmount(): Int = amplitudesAmount

    private fun getAmplitude(): Int {
        return try {
            recorder!!.maxAmplitude
        } catch (error: IllegalStateException) {
            0
        } catch (error: RuntimeException) {
            0
        }
    }

    private fun updateAmplitude() {
        if (state !== RecorderState.RECORDING) {
            return
        }

        amplitudes.add(getAmplitude())
        onAmplitudeChange?.invoke(amplitudes)

        // Delete old amplitudes
        if (amplitudes.size > getAmplitudeAmount()) {
            // Should be more efficient than dropping the elements, getting a new list
            // clearing old list and adding new elements to it
            repeat(amplitudes.size - getAmplitudeAmount()) {
                amplitudes.removeAt(0)
            }
        }

        handler.postDelayed(::updateAmplitude, 100)
    }

    private fun createAmplitudesTimer() {
        handler.postDelayed(::updateAmplitude, 100)
    }

    // ==== Audio device related ====

    /// Tell Android to use the correct bluetooth microphone, if any selected
    private fun startAudioDevice() {
        if (selectedMicrophone == null) {
            return
        }

        val audioManger = getSystemService(AUDIO_SERVICE)!! as AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManger.setCommunicationDevice(selectedMicrophone!!.deviceInfo)
        } else {
            audioManger.startBluetoothSco()
        }
    }

    private fun clearAudioDevice() {
        val audioManger = getSystemService(AUDIO_SERVICE)!! as AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManger.clearCommunicationDevice()
        } else {
            audioManger.stopBluetoothSco()
        }
    }

    private fun getNameForMediaFile() =
        "${batchesFolder.mediaPrefix}$counter.${settings.audioRecorderSettings.fileExtension}"

    // ==== Actual recording related ====
    private fun createRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            MediaRecorder()
        }.apply {
            val audioSettings = settings.audioRecorderSettings

            // Audio Source is kinda strange, here are my experimental findings using a Pixel 7 Pro
            // and Redmi Buds 3 Pro:
            // - MIC: Uses the bottom microphone of the phone (17)
            // - CAMCORDER: Uses the top microphone of the phone (2)
            // - VOICE_COMMUNICATION: Uses the bottom microphone of the phone (17)
            // - DEFAULT: Uses the bottom microphone of the phone (17)
            setAudioSource(MediaRecorder.AudioSource.MIC)

            when (batchesFolder.type) {
                BatchesFolder.BatchType.INTERNAL -> {
                    setOutputFile(
                        batchesFolder.asInternalGetFile(
                            counter,
                            audioSettings.fileExtension
                        ).absolutePath
                    )
                }

                BatchesFolder.BatchType.CUSTOM -> {
                    setOutputFile(
                        batchesFolder.asCustomGetFileDescriptor(
                            counter,
                            audioSettings.fileExtension
                        )
                    )
                }

                BatchesFolder.BatchType.MEDIA -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        setOutputFile(
                            batchesFolder.asMediaGetScopedStorageFileDescriptor(
                                getNameForMediaFile(),
                                "audio/${audioSettings.fileExtension}"
                            )
                        )
                    } else {
                        val name = getNameForMediaFile()
                        val file = batchesFolder.asMediaGetLegacyFile(name)

                        setOutputFile(file.absolutePath)
                    }
                }
            }

            setOutputFormat(audioSettings.getOutputFormat())

            setAudioEncoder(audioSettings.getEncoder())
            setAudioEncodingBitRate(audioSettings.bitRate)
            setAudioSamplingRate(audioSettings.getSamplingRate())
            setOnErrorListener(OnErrorListener { _, _, _ ->
                onError()
            })
        }
    }

    // ==== Microphone related ====
    private fun resetRecorder() {
        runCatching {
            recorder?.apply {
                stop()
                reset()
                release()
            }
            clearAudioDevice()
            batchesFolder.cleanup()
        }
    }

    fun changeMicrophone(microphone: MicrophoneInfo?) {
        selectedMicrophone = microphone
        onSelectedMicrophoneChange(microphone)

        if (state == RecorderState.RECORDING) {
            startNewCycle()
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            super.onAudioDevicesAdded(addedDevices)

            if (selectedMicrophone == null) {
                return
            }

            // We can't compare the ID, as it seems to be changing on each reconnect
            val newDevice = addedDevices?.find {
                it.productName == selectedMicrophone!!.deviceInfo.productName &&
                        it.isSink == selectedMicrophone!!.deviceInfo.isSink &&
                        it.type == selectedMicrophone!!.deviceInfo.type && (
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            it.address == selectedMicrophone!!.deviceInfo.address
                        } else true
                        )
            }
            if (newDevice != null) {
                changeMicrophone(MicrophoneInfo.fromDeviceInfo(newDevice))

                onMicrophoneReconnected()
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            super.onAudioDevicesRemoved(removedDevices)

            if (selectedMicrophone == null) {
                return
            }

            if (removedDevices?.find { it.id == selectedMicrophone!!.deviceInfo.id } != null) {
                onMicrophoneDisconnected()
            }
        }
    }

    private fun registerMicrophoneListener() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE)!! as AudioManager

        audioManager.registerAudioDeviceCallback(
            audioDeviceCallback,
            Handler(Looper.getMainLooper())
        )
    }

    private fun unregisterMicrophoneListener() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE)!! as AudioManager

        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
    }

    // ==== Settings ====
    override fun getRecordingInformation() =
        RecordingInformation(
            folderPath = batchesFolder.exportFolderForSettings(),
            recordingStart = recordingStart,
            maxDuration = settings.maxDuration,
            batchesAmount = batchesFolder.getBatchesForFFmpeg().size,
            fileExtension = settings.audioRecorderSettings.fileExtension,
            intervalDuration = settings.intervalDuration,
            type = RecordingInformation.Type.AUDIO,
        )

    private suspend fun mergeInBackground(info: RecordingInformation): Boolean {
        return withContext(Dispatchers.IO) {
            val folder = AudioBatchesFolder.importFromFolder(info.folderPath, this@AudioRecorderService)
            val sourceChunks = folder.getBatchesForFFmpeg()

            if (sourceChunks.isEmpty()) {
                Log.e(TAG, "No audio chunks available for notification merge")
                return@withContext false
            }

            val outputName = folder.getName(info.recordingStart, info.fileExtension)
            Log.i(
                TAG,
                "Merging audio notification recording; chunks=${sourceChunks.size} " +
                    "type=${folder.type} output=$outputName",
            )

            try {
                val output = folder.concatenate(
                    info,
                    filenameFormat = settings.filenameFormat,
                    fileName = outputName,
                )
                Log.i(TAG, "Merged audio notification recording output=$output")
            } catch (error: Exception) {
                Log.e(
                    TAG,
                    "Audio notification merge failed; source chunks preserved count=${sourceChunks.size}",
                    error,
                )
                return@withContext false
            }

            if (!settings.deleteRecordingsImmediately) {
                Log.i(
                    TAG,
                    "Preserved merged audio source chunks; immediateDelete=false count=${sourceChunks.size}",
                )
                return@withContext true
            }

            folder.permanentlyDeleteRecordings = settings.permanentlyDeleteRecordings
            val deleted = folder.deleteRecordings(0..counter)
            Log.i(TAG, "Deleted merged audio source chunks deleted=$deleted/${sourceChunks.size}")
            true
        }
    }

    companion object {
        private const val TAG = "AudioRecorderService"
    }
}
