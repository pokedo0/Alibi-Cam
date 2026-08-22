package app.leo.alibi_cam.helpers

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import app.leo.alibi_cam.db.AppSettings
import java.io.File

/**
 * Camera2-based Physical Dual Camera Recorder.
 *
 * Uses Android 9+ (API 28) Logical Multi-Camera physical streams:
 * - Opens logical back camera ("0")
 * - Binds Primary MediaRecorder to physical sensor (e.g. ID "2" for main, "4" for ultrawide)
 * - Binds Secondary MediaRecorder to physical sensor (e.g. ID "3" for telephoto)
 * - Records simultaneously and saves into separate VideoBatchesFolders.
 * - Handles MediaStore IS_PENDING commitment so files appear in the gallery.
 */
@RequiresApi(Build.VERSION_CODES.P)
class Camera2PhysicalDualRecorder(
    private val context: Context,
    private val settings: AppSettings,
    private val primaryPhysicalId: String,
    private val secondaryPhysicalId: String,
    private val primaryFolder: VideoBatchesFolder,
    private val secondaryFolder: VideoBatchesFolder,
    private val enableAudio: Boolean = false,
) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var primaryRecorder: MediaRecorder? = null
    private var secondaryRecorder: MediaRecorder? = null
    private var primarySurface: Surface? = null
    private var secondarySurface: Surface? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val pendingUris = mutableListOf<Uri>()

    var isRecording: Boolean = false
        private set

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("Camera2PhysicalRecorder").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Background thread stop interrupted", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun start(
        counter: Long,
        onStarted: () -> Unit,
        onError: (String) -> Unit,
    ) {
        Log.i(TAG, "🎬 Starting Camera2PhysicalDualRecorder: primary=$primaryPhysicalId, secondary=$secondaryPhysicalId, counter=$counter, audio=$enableAudio")
        CameraDebugLog.append("🎬 Start Camera2Physical: prim=$primaryPhysicalId, sec=$secondaryPhysicalId, count=$counter, audio=$enableAudio")

        startBackgroundThread()

        try {
            // Primary gets audio if enabled, secondary is video-only to prevent microphone collision
            primaryRecorder = createMediaRecorder(primaryFolder, primaryPhysicalId, counter, includeAudio = enableAudio)
            secondaryRecorder = createMediaRecorder(secondaryFolder, secondaryPhysicalId, counter, includeAudio = false)

            primarySurface = primaryRecorder!!.surface
            secondarySurface = secondaryRecorder!!.surface

            cameraManager.openCamera(
                "0",
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        cameraDevice = device
                        Log.i(TAG, "📸 Camera 0 opened for physical dual streaming")
                        CameraDebugLog.append("📸 Camera 0 opened for physical dual streaming")

                        createDualCaptureSession(onStarted, onError)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        Log.w(TAG, "📸 Camera 0 disconnected")
                        CameraDebugLog.append("📸 Camera 0 disconnected")
                        device.close()
                        cameraDevice = null
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        val errMsg = "Camera 0 open error: $error"
                        Log.e(TAG, "📸 ❌ $errMsg")
                        CameraDebugLog.append("📸 ❌ $errMsg")
                        CameraDebugLog.flush()
                        device.close()
                        cameraDevice = null
                        onError(errMsg)
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            val msg = "Camera2PhysicalDualRecorder init failed: ${e.message}"
            Log.e(TAG, msg, e)
            CameraDebugLog.append("📸 ❌ $msg")
            CameraDebugLog.flush()
            onError(msg)
        }
    }

    private fun createDualCaptureSession(
        onStarted: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val device = cameraDevice ?: return
        val pSurface = primarySurface ?: return
        val sSurface = secondarySurface ?: return

        val primaryConfig = OutputConfiguration(pSurface).apply {
            setPhysicalCameraId(primaryPhysicalId)
        }
        val secondaryConfig = OutputConfiguration(sSurface).apply {
            setPhysicalCameraId(secondaryPhysicalId)
        }

        Log.i(TAG, "📸 Creating SessionConfiguration with physical streams: [$primaryPhysicalId, $secondaryPhysicalId]")
        CameraDebugLog.append("📸 Creating Session: prim physical=$primaryPhysicalId, sec physical=$secondaryPhysicalId")

        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(primaryConfig, secondaryConfig),
            ContextCompat.getMainExecutor(context),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        // 1. Start MediaRecorders FIRST so they are ready to capture initial SPS/PPS/IDR frames
                        primaryRecorder?.start()
                        secondaryRecorder?.start()
                        isRecording = true

                        // 2. Build CaptureRequest with standard auto controls
                        val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(pSurface)
                            addTarget(sSurface)
                            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                        }

                        // 3. Start Repeating Request with logging callback
                        session.setRepeatingRequest(
                            requestBuilder.build(),
                            object : CameraCaptureSession.CaptureCallback() {
                                private var frameCount = 0
                                override fun onCaptureCompleted(
                                    session: CameraCaptureSession,
                                    request: CaptureRequest,
                                    result: TotalCaptureResult,
                                ) {
                                    frameCount++
                                    if (frameCount % 60 == 0) {
                                        Log.d(TAG, "📸 Physical streams capturing... frame #$frameCount")
                                    }
                                }

                                override fun onCaptureFailed(
                                    session: CameraCaptureSession,
                                    request: CaptureRequest,
                                    failure: CaptureFailure,
                                ) {
                                    Log.w(TAG, "📸 Capture frame failed: reason=${failure.reason}")
                                }
                            },
                            backgroundHandler
                        )

                        Log.i(TAG, "📸 ✅ Camera2 physical dual recording started successfully!")
                        CameraDebugLog.append("📸 ✅ Camera2 physical dual streams STARTED!")
                        CameraDebugLog.flush()

                        onStarted()
                    } catch (e: Exception) {
                        val msg = "Failed to start physical capture session: ${e.message}"
                        Log.e(TAG, msg, e)
                        CameraDebugLog.append("📸 ❌ $msg")
                        CameraDebugLog.flush()
                        onError(msg)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    val msg = "Physical dual session configuration failed"
                    Log.e(TAG, msg)
                    CameraDebugLog.append("📸 ❌ $msg")
                    CameraDebugLog.flush()
                    onError(msg)
                }
            }
        )

        try {
            device.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            val msg = "Failed to call createCaptureSession: ${e.message}"
            Log.e(TAG, msg, e)
            CameraDebugLog.append("📸 ❌ $msg")
            CameraDebugLog.flush()
            onError(msg)
        }
    }

    private fun getSupportedVideoSize(cameraId: String, target16_9: Boolean): Pair<Int, Int> {
        try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()

            Log.i(TAG, "🔍 Camera $cameraId supported MediaRecorder sizes: ${sizes.map { "${it.width}x${it.height}" }}")

            if (target16_9) {
                sizes.firstOrNull { it.width == 1920 && it.height == 1080 }?.let { return Pair(1920, 1080) }
                sizes.firstOrNull { it.width == 1280 && it.height == 720 }?.let { return Pair(1280, 720) }
            } else {
                sizes.firstOrNull { it.width == 1440 && it.height == 1080 }?.let { return Pair(1440, 1080) }
                sizes.firstOrNull { it.width == 1280 && it.height == 960 }?.let { return Pair(1280, 960) }
            }

            // Fallbacks
            sizes.firstOrNull { it.width == 1920 && it.height == 1080 }?.let { return Pair(1920, 1080) }
            sizes.firstOrNull { it.width == 1280 && it.height == 720 }?.let { return Pair(1280, 720) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query supported sizes for $cameraId", e)
        }
        return if (target16_9) Pair(1920, 1080) else Pair(1440, 1080)
    }

    private fun createMediaRecorder(
        folder: VideoBatchesFolder,
        physicalCameraId: String,
        counter: Long,
        includeAudio: Boolean,
    ): MediaRecorder {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        recorder.apply {
            if (includeAudio) {
                setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            }
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

            // Video encoding params
            val bitrate = settings.videoRecorderSettings.targetedVideoBitRate ?: 8_000_000
            val fps = settings.videoRecorderSettings.targetFrameRate ?: 30
            val is16_9 = settings.videoRecorderSettings.videoAspectRatio == "16:9"

            val (width, height) = getSupportedVideoSize(physicalCameraId, is16_9)
            Log.i(TAG, "📐 Selected video size for camera $physicalCameraId: ${width}x${height} @ ${fps}fps")

            setVideoSize(width, height)
            setVideoFrameRate(fps)
            setVideoEncodingBitRate(bitrate)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)

            if (includeAudio) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(48000)
                setAudioEncodingBitRate(128000)
            }

            // Set output file target
            setupOutputFile(this, folder, counter)

            prepare()
        }

        return recorder
    }

    private fun setupOutputFile(recorder: MediaRecorder, folder: VideoBatchesFolder, counter: Long) {
        val ext = settings.videoRecorderSettings.fileExtension
        val sid = folder.sessionId ?: ""
        val fileName = "${folder.mediaPrefix}${sid}-%03d.%s".format(counter, ext)

        when (folder.type) {
            BatchesFolder.BatchType.INTERNAL -> {
                val file = folder.asInternalGetFile(counter, ext)
                recorder.setOutputFile(file.absolutePath)
                Log.i(TAG, "📁 outputFile (INTERNAL): ${file.absolutePath}")
            }
            BatchesFolder.BatchType.CUSTOM -> {
                val pfd = folder.asCustomGetParcelFileDescriptor(counter, ext)
                recorder.setOutputFile(pfd.fileDescriptor)
                Log.i(TAG, "📁 outputFile (CUSTOM): fd=${pfd.fd}")
            }
            BatchesFolder.BatchType.MEDIA -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = folder.asMediaGetScopedStorageContentValues(fileName)
                    val uri = context.contentResolver.insert(folder.scopedMediaContentUri, values)
                    if (uri != null) {
                        synchronized(pendingUris) { pendingUris.add(uri) }
                        val pfd = context.contentResolver.openFileDescriptor(uri, "w")!!
                        recorder.setOutputFile(pfd.fileDescriptor)
                        Log.i(TAG, "📁 outputFile (MEDIA URI): $uri")
                    }
                } else {
                    val file = File(folder.legacyMediaFolder, fileName)
                    recorder.setOutputFile(file.absolutePath)
                    Log.i(TAG, "📁 outputFile (LEGACY): ${file.absolutePath}")
                }
            }
        }
    }

    /**
     * Rotate chunk output files for the next cycle without interrupting capture.
     */
    fun rotateNextChunk(counter: Long) {
        try {
            val ext = settings.videoRecorderSettings.fileExtension

            // First commit current pending URIs so existing chunks become visible
            commitPendingUris()

            // Primary next chunk
            setupNextOutputFile(primaryRecorder, primaryFolder, counter, ext)
            // Secondary next chunk
            setupNextOutputFile(secondaryRecorder, secondaryFolder, counter, ext)

            Log.i(TAG, "🔄 Rotated to chunk #$counter for physical streams [$primaryPhysicalId, $secondaryPhysicalId]")
            CameraDebugLog.append("🔄 Physical dual rotated to chunk #$counter")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate next chunk for physical streams", e)
            CameraDebugLog.append("⚠️ Physical dual chunk rotation err: ${e.message}")
        }
    }

    private fun setupNextOutputFile(recorder: MediaRecorder?, folder: VideoBatchesFolder, counter: Long, ext: String) {
        if (recorder == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val sid = folder.sessionId ?: ""
        val fileName = "${folder.mediaPrefix}${sid}-%03d.%s".format(counter, ext)

        when (folder.type) {
            BatchesFolder.BatchType.INTERNAL -> {
                val file = folder.asInternalGetFile(counter, ext)
                recorder.setNextOutputFile(file)
            }
            BatchesFolder.BatchType.CUSTOM -> {
                val pfd = folder.asCustomGetParcelFileDescriptor(counter, ext)
                recorder.setNextOutputFile(pfd.fileDescriptor)
            }
            BatchesFolder.BatchType.MEDIA -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = folder.asMediaGetScopedStorageContentValues(fileName)
                    val uri = context.contentResolver.insert(folder.scopedMediaContentUri, values)
                    if (uri != null) {
                        synchronized(pendingUris) { pendingUris.add(uri) }
                        val pfd = context.contentResolver.openFileDescriptor(uri, "w")!!
                        recorder.setNextOutputFile(pfd.fileDescriptor)
                    }
                } else {
                    val file = File(folder.legacyMediaFolder, fileName)
                    recorder.setNextOutputFile(file)
                }
            }
        }
    }

    /**
     * Commit all pending MediaStore URIs (sets IS_PENDING = 0)
     * so they immediately show up in the gallery and file manager.
     */
    private fun commitPendingUris() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            synchronized(pendingUris) {
                for (uri in pendingUris) {
                    try {
                        val count = context.contentResolver.update(uri, values, null, null)
                        Log.i(TAG, "📁 Committed Scoped Storage URI: $uri (updated=$count)")
                        CameraDebugLog.append("📁 Committed URI (pending=0): $uri")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to commit URI $uri", e)
                        CameraDebugLog.append("⚠️ Failed to commit URI: ${e.message}")
                    }
                }
                pendingUris.clear()
            }
            CameraDebugLog.flush()
        }
    }

    fun stop() {
        Log.i(TAG, "⏹️ Stopping Camera2PhysicalDualRecorder")
        CameraDebugLog.append("⏹️ Stopping Camera2PhysicalDualRecorder")

        isRecording = false

        // Stop recorders FIRST before stopping camera stream to ensure proper MP4 moov finalization
        try {
            primaryRecorder?.setOnErrorListener(null)
            primaryRecorder?.setOnInfoListener(null)
            primaryRecorder?.stop()
        } catch (e: Exception) {
            Log.d(TAG, "Primary recorder stopped (note: ${e.message})")
        }

        try {
            secondaryRecorder?.setOnErrorListener(null)
            secondaryRecorder?.setOnInfoListener(null)
            secondaryRecorder?.stop()
        } catch (e: Exception) {
            Log.d(TAG, "Secondary recorder stopped (note: ${e.message})")
        }

        try {
            captureSession?.stopRepeating()
            captureSession?.close()
            captureSession = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping capture session", e)
        }

        try {
            primaryRecorder?.reset()
            primaryRecorder?.release()
            primaryRecorder = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing primary recorder", e)
        }

        try {
            secondaryRecorder?.reset()
            secondaryRecorder?.release()
            secondaryRecorder = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing secondary recorder", e)
        }

        try {
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing camera device", e)
        }

        // Commit all written chunks so they immediately appear in storage
        commitPendingUris()

        stopBackgroundThread()
        Log.i(TAG, "⏹️ Camera2PhysicalDualRecorder stopped completely")
        CameraDebugLog.append("⏹️ Camera2PhysicalDualRecorder stopped")
        CameraDebugLog.flush()
    }

    companion object {
        private const val TAG = "Camera2PhysicalDual"
    }
}
