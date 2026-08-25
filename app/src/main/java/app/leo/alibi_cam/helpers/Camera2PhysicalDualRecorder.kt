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
import android.os.Process
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import app.leo.alibi_cam.db.AppSettings
import app.leo.alibi_cam.ui.utils.VideoStabilizationSupport
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.io.Closeable
import java.io.File
import kotlin.coroutines.resume

/**
 * Camera2-based Physical Dual Camera Recorder.
 *
 * Uses Android 9+ (API 28) Logical Multi-Camera physical streams:
 * - Opens the selected logical back camera
 * - Binds each MediaRecorder to a selected physical sensor
 * - Records simultaneously and saves into separate VideoBatchesFolders.
 * - Handles MediaStore IS_PENDING commitment so files appear in the gallery.
 */
@RequiresApi(Build.VERSION_CODES.P)
class Camera2PhysicalDualRecorder(
    private val context: Context,
    private val settings: AppSettings,
    private val logicalCameraId: String,
    private val primaryPhysicalId: String,
    private val secondaryPhysicalId: String?,
    private val primaryFolder: VideoBatchesFolder,
    private val secondaryFolder: VideoBatchesFolder?,
    private val enableAudio: Boolean = false,
) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val isSinglePhysicalStream = secondaryPhysicalId == null || secondaryFolder == null
    @Volatile
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var captureSessionReady: CompletableDeferred<Unit>? = null

    private var primaryRecorder: MediaRecorder? = null
    private var secondaryRecorder: MediaRecorder? = null
    private var primarySurface: Surface? = null
    private var secondarySurface: Surface? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    @Volatile
    private var isOutputPreparationFailed = false

    /** Called immediately before the logical camera open binder request. */
    var onLogicalCameraOpen: (() -> Unit)? = null

    private val pendingUris = mutableListOf<Uri>()
    private val outputDescriptors = mutableListOf<ParcelFileDescriptor>()
    private val segmentTargets = linkedMapOf<Long, SegmentTargets>()
    private var currentCounter: Long? = null
    private val supportedVideoSizes = ConcurrentHashMap<Pair<String, Boolean>, Pair<Int, Int>>()
    private val sensorOrientations = ConcurrentHashMap<String, Int>()
    @Volatile
    private var activeStabilizationRequests: Map<String, String> = emptyMap()

    init {
        if (secondaryPhysicalId != null && secondaryFolder == null) {
            val message =
                "Secondary physical=$secondaryPhysicalId has no output folder; forcing single stream"
            Log.w(TAG, "⚠️ $message")
        }
    }

    private data class OutputTarget(
        val uri: Uri? = null,
        val file: File? = null,
        val descriptor: ParcelFileDescriptor? = null,
        val label: String,
    )

    private data class FfmpegInput(
        val path: String,
        val fd: Int? = null,
        val descriptor: ParcelFileDescriptor? = null,
    ) : Closeable {
        override fun close() {
            runCatching { descriptor?.close() }
        }
    }

    private data class SegmentTargets(
        val primary: OutputTarget,
        val secondary: OutputTarget?,
    )

    private data class PreparedRecorder(
        val recorder: MediaRecorder,
        val target: OutputTarget,
    )

    private data class RetiredSegment(
        val primaryRecorder: MediaRecorder?,
        val secondaryRecorder: MediaRecorder?,
        val surfaces: List<Surface>,
        val pendingUris: List<Uri>,
        val outputDescriptors: List<ParcelFileDescriptor>,
    )

    var isRecording: Boolean = false
        private set

    private fun startBackgroundThread() {
        if (backgroundThread?.isAlive == true) return
        backgroundThread = object : HandlerThread("Camera2PhysicalRecorder") {
            override fun onLooperPrepared() {
                // Camera HAL callbacks can arrive on this thread during cold start.
                // Keep it above the default worker priority on OEMs that throttle
                // freshly created background threads.
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            }
        }.also { it.start() }
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
        currentCounter = counter
        val streamMode = if (isSinglePhysicalStream) "single" else "dual"
        Log.i(
            TAG,
            "🎬 Starting Camera2PhysicalDualRecorder mode=$streamMode: " +
                "primary=$primaryPhysicalId, secondary=$secondaryPhysicalId, " +
                "counter=$counter, audio=$enableAudio",
        )

        startBackgroundThread()

        try {
            if (cameraDevice != null) {
                Log.i(TAG, "♻️ Reusing open CameraDevice for next physical $streamMode segment")
                prepareOutputs(counter)
                createDualCaptureSession(onStarted, onError)
                return
            }

            // Submit the logical camera open before MediaRecorder/output setup. On some
            // OEM HALs the first cold open takes seconds; local preparation should overlap
            // with that wait instead of delaying the request.
            openCameraDevice(onStarted, onError)
            prepareOutputs(counter)

            cameraDevice?.let {
                createDualCaptureSession(onStarted, onError)
            }
        } catch (e: Exception) {
            isOutputPreparationFailed = true
            val msg = "Camera2PhysicalDualRecorder init failed: ${e.message}"
            Log.e(TAG, msg, e)
            runCatching { cameraDevice?.close() }
            cameraDevice = null
            onError(msg)
        }
    }

    private fun prepareOutputs(counter: Long) {
        try {
            // Primary gets audio if enabled, secondary is video-only to prevent microphone collision
            val primary = createMediaRecorder(primaryFolder, primaryPhysicalId, counter, includeAudio = enableAudio)
            primaryRecorder = primary.recorder
            primarySurface = primaryRecorder!!.surface

            var secondary: PreparedRecorder? = null
            if (!isSinglePhysicalStream) {
                secondary = createMediaRecorder(
                    secondaryFolder!!,
                    secondaryPhysicalId!!,
                    counter,
                    includeAudio = false,
                )
                secondaryRecorder = secondary.recorder
                secondarySurface = secondary.recorder.surface
            } else {
                secondaryRecorder = null
                secondarySurface = null
            }

            segmentTargets[counter] = SegmentTargets(primary.target, secondary?.target)
        } catch (error: Exception) {
            isOutputPreparationFailed = true
            throw error
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraDevice(
        onStarted: () -> Unit,
        onError: (String) -> Unit,
    ) {
        try {
            onLogicalCameraOpen?.invoke()
            val openStartedAtMs = SystemClock.elapsedRealtime()
            logOpenRequestPriority()
            // Device lifecycle callbacks can arrive after stopBackgroundThread().
            // Use the main executor so HAL onClosed cannot post into a dead
            // worker; capture result callbacks still use backgroundHandler.
            Log.d(TAG, "📸 Opening logical camera callbacks=main-executor id=$logicalCameraId")
            cameraManager.openCamera(
                logicalCameraId,
                ContextCompat.getMainExecutor(context),
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        val openDurationMs = SystemClock.elapsedRealtime() - openStartedAtMs
                        val streamMode = if (isSinglePhysicalStream) "single-stream" else "dual-stream"
                        Log.i(TAG, "📸 Camera $logicalCameraId opened for physical $streamMode streaming in ${openDurationMs}ms")

                        if (isOutputPreparationFailed) {
                            device.close()
                        } else {
                            cameraDevice = device
                            if (primarySurface != null &&
                                (isSinglePhysicalStream || secondarySurface != null)
                            ) {
                                createDualCaptureSession(onStarted, onError)
                            }
                        }
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        Log.w(TAG, "📸 Camera 0 disconnected")
                        device.close()
                        cameraDevice = null
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        val errMsg = "Camera $logicalCameraId open error: $error"
                        Log.e(TAG, "📸 ❌ $errMsg")
                        device.close()
                        cameraDevice = null
                        onError(errMsg)
                    }
                },
            )
        } catch (e: Exception) {
            val msg = "Camera2PhysicalDualRecorder init failed: ${e.message}"
            Log.e(TAG, msg, e)
            onError(msg)
        }
    }

    private fun logOpenRequestPriority() {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val importance = activityManager?.runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }?.importance
        Log.i(
            TAG,
            "📸 Submitting logical camera open; pid=${Process.myPid()}, importance=$importance",
        )
    }

    private fun createDualCaptureSession(
        onStarted: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val device = cameraDevice ?: return
        val pSurface = primarySurface ?: return
        val sSurface = secondarySurface
        if (!isSinglePhysicalStream && sSurface == null) return

        val primaryConfig = OutputConfiguration(pSurface).apply {
            setPhysicalCameraId(primaryPhysicalId)
        }

        val outputConfigs = buildList {
            add(primaryConfig)
            if (sSurface != null && secondaryPhysicalId != null) {
                add(
                    OutputConfiguration(sSurface).apply {
                        setPhysicalCameraId(secondaryPhysicalId)
                    },
                )
            }
        }

        Log.i(
            TAG,
            "📸 Creating SessionConfiguration physical streams=" +
                listOfNotNull(primaryPhysicalId, secondaryPhysicalId)
                    .joinToString(prefix = "[", postfix = "]"),
        )

        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputConfigs,
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
                            if (sSurface != null) addTarget(sSurface)
                            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                        }

                        applyStabilizationControls(requestBuilder)

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
                                    if (frameCount % 60 == 1) {
                                        Log.d(
                                            TAG,
                                            "📸 Physical ${
                                                if (isSinglePhysicalStream) "stream" else "streams"
                                            } capturing... frame #$frameCount",
                                        )
                                        VideoStabilizationSupport.logPhysicalEffective(
                                            TAG,
                                            result,
                                            primaryPhysicalId,
                                            secondaryPhysicalId,
                                            activeStabilizationRequests,
                                        )
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

                        val streamMode = if (isSinglePhysicalStream) "single stream" else "dual streams"
                        Log.i(TAG, "📸 ✅ Camera2 physical $streamMode recording started successfully!")

                        onStarted()
                    } catch (e: Exception) {
                        val msg = "Failed to start physical capture session: ${e.message}"
                        Log.e(TAG, msg, e)
                        onError(msg)
                    }
                }

                override fun onReady(session: CameraCaptureSession) {
                    Log.i(TAG, "📸 Capture session ready for recorder stop")
                    captureSessionReady?.complete(Unit)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    val msg =
                        "Physical ${
                            if (isSinglePhysicalStream) "single" else "dual"
                        } session configuration failed"
                    Log.e(TAG, msg)
                    onError(msg)
                }
            }
        )

        try {
            device.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            val msg = "Failed to call createCaptureSession: ${e.message}"
            Log.e(TAG, msg, e)
            onError(msg)
        }
    }

    private fun getSupportedVideoSize(cameraId: String, target16_9: Boolean): Pair<Int, Int> {
        return supportedVideoSizes.getOrPut(cameraId to target16_9) {
            querySupportedVideoSize(cameraId, target16_9)
        }
    }

    private fun applyStabilizationControls(requestBuilder: CaptureRequest.Builder): Map<String, String> {
        val enabled = settings.videoRecorderSettings.videoStabilizationEnabled
        val requestedCameras = buildList {
            add("primary" to primaryPhysicalId)
            if (!isSinglePhysicalStream && secondaryPhysicalId != null) {
                add("secondary" to secondaryPhysicalId)
            }
        }
        val capabilities = requestedCameras.associate { (label, cameraId) ->
            val capability = VideoStabilizationSupport.readCapability(cameraManager, cameraId)
            Log.i(
                TAG,
                "🛡 Physical stabilization request enabled=$enabled camera=$label/$cameraId " +
                "eis=${capability.electronic} ois=${capability.optical}",
            )

            cameraId to capability
        }

        val anyOis = capabilities.values.any { it.optical }
        val requestedModes = capabilities.mapValues { (_, capability) ->
            when {
                !enabled -> "none"
                capability.optical -> "ois-only"

                // In a dual session, prefer real OIS where it exists and leave the
                // other sensor untouched while probing the OIS-only stream.
                anyOis -> "untouched"
                capability.electronic -> "eis-only"
                else -> "none"
            }
        }
        val backend = if (isSinglePhysicalStream) "exact-physical" else "physical-per-stream"
        Log.i(
            TAG,
            "🛡 Physical stabilization strategy=$backend enabled=$enabled requests=" +
                requestedModes.entries.joinToString(prefix="[", postfix="]") { entry ->
                    "${entry.key}:${entry.value}"
                },
        )

        if (requestedModes.values.all { it == "none" }) {
            activeStabilizationRequests = requestedModes
            return requestedModes
        }

        var appliedPerStream = false
        var oisKeyAttempted = false
        var oisKeyAccepted = false
        requestedModes.forEach { (cameraId, mode) ->
            if (mode == "untouched") {
                Log.i(
                    TAG,
                    "🛡 Physical stabilization skipping camera=$cameraId; " +
                        "single-OIS dual probe leaves it unmanaged",
                )
                return@forEach
            }

            val keyResults = mutableListOf<String>()
            var cameraAcceptedAnyKey = false
            var cameraOisAccepted = false

            fun inject(label: String, key: CaptureRequest.Key<Int>, value: Int) {
                try {
                    requestBuilder.setPhysicalCameraKey(key, value, cameraId)
                    keyResults += "$label=accepted"
                    cameraAcceptedAnyKey = true
                } catch (error: IllegalArgumentException) {
                    keyResults += "$label=rejected"
                    Log.w(
                        TAG,
                        "🛡 Physical stabilization key rejected camera=$cameraId " +
                            "mode=$mode key=$label",
                        error,
                    )
                }
            }

            when (mode) {
                "ois-only" -> {
                    oisKeyAttempted = true
                    inject("ois-on", CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
                    cameraOisAccepted = cameraAcceptedAnyKey
                    oisKeyAccepted = oisKeyAccepted || cameraOisAccepted

                    // Disable EIS only after OIS is accepted, so a rejected OIS
                    // probe cannot leave an EIS-off override behind before the
                    // common-EIS fallback runs.
                    if (cameraOisAccepted && capabilities[cameraId]?.electronic == true) {
                        inject("eis-off", CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                    }
                }
                "eis-only" -> {
                    inject("eis-on", CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                }
            }

            if (cameraAcceptedAnyKey) {
                appliedPerStream = true
            }
            Log.i(
                TAG,
                "🛡 Physical stabilization injection camera=$cameraId mode=$mode " +
                    "keys=${keyResults.joinToString(prefix="[", postfix="]")}",
            )
        }

        if (appliedPerStream) {
            Log.i(
                TAG,
                "🛡 Physical stabilization using per-stream result; no common rewrite",
            )
            activeStabilizationRequests = requestedModes
            return requestedModes
        }

        // Some OEM multi-camera HALs reject every physical override. Only rewrite the
        // whole logical request when that same mode is valid on every physical stream;
        // otherwise a global value would contradict the selected per-lens strategy.
        val allOis = capabilities.values.isNotEmpty() && capabilities.values.all { it.optical }
        val allEis = capabilities.values.isNotEmpty() && capabilities.values.all { it.electronic }
        val commonMode = when {
            allOis -> "ois-only"
            !anyOis && allEis -> "eis-only"
            // The single-OIS probe failed, so use the only mode safe for every
            // stream instead of leaving the OIS-capable lens unstabilized.
            allEis -> "eis-only"
            else -> "none"
        }

        val commonModes = capabilities.keys.associateWith { commonMode }
        try {
            when (commonMode) {
                "ois-only" -> {
                    requestBuilder.set(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
                    )
                    requestBuilder.set(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON,
                    )
                }
                "eis-only" -> {
                    requestBuilder.set(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON,
                    )
                }
            }
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "🛡 Common stabilization request rejected by camera HAL", error)
        }

        val fallbackReason = when {
            allOis -> "common-ois"
            oisKeyAttempted && !oisKeyAccepted && allEis ->
                "single-ois-failed-common-eis"
            !anyOis && allEis -> "common-eis"
            else -> "common-eis"
        }
        Log.i(
            TAG,
            "🛡 Physical stabilization fallback mode=$commonMode reason=$fallbackReason",
        )
        activeStabilizationRequests = commonModes
        return commonModes
    }
    private fun querySupportedVideoSize(cameraId: String, target16_9: Boolean): Pair<Int, Int> {
        try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()

            Log.i(TAG, "🔍 Camera $cameraId supported MediaRecorder sizes: ${sizes.map { "${it.width}x${it.height}" }}")

            val selectedSize = if (target16_9) {
                sizes.firstOrNull { it.width == 1920 && it.height == 1080 }
                    ?: sizes.firstOrNull { it.width == 1280 && it.height == 720 }
                    ?: sizes.firstOrNull { it.width == 1920 && it.height == 1080 }
            } else {
                sizes.firstOrNull { it.width == 1440 && it.height == 1080 }
                    ?: sizes.firstOrNull { it.width == 1280 && it.height == 960 }
                    ?: sizes.firstOrNull { it.width == 1920 && it.height == 1080 }
            }

            return selectedSize?.let { Pair(it.width, it.height) }
                ?: if (target16_9) Pair(1920, 1080) else Pair(1440, 1080)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query supported sizes for $cameraId", e)
            return if (target16_9) Pair(1920, 1080) else Pair(1440, 1080)
        }
    }

    private fun createMediaRecorder(
        folder: VideoBatchesFolder,
        physicalCameraId: String,
        counter: Long,
        includeAudio: Boolean,
    ): PreparedRecorder {
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
            val outputTarget = setupOutputFile(this, folder, counter)

            val orientationHint = getOrientationHint(physicalCameraId)
            setOrientationHint(orientationHint)
            Log.i(TAG, "🧭 orientation physical=$physicalCameraId, hint=$orientationHint")

            prepare()

            return PreparedRecorder(this, outputTarget)
        }
    }

    private fun getOrientationHint(physicalCameraId: String): Int {
        val sensorOrientation = sensorOrientations.getOrPut(physicalCameraId) {
            runCatching {
                cameraManager.getCameraCharacteristics(physicalCameraId)
                    .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            }.getOrElse {
                Log.w(TAG, "🧭 Failed to query sensor orientation for $physicalCameraId", it)
                0
            }.also { orientation ->
                Log.i(TAG, "🧭 Cached sensor orientation for $physicalCameraId: $orientation")
            }
        }
        val displayRotation = runCatching {
            context.getSystemService(WindowManager::class.java)?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }.getOrDefault(Surface.ROTATION_0)
        val deviceRotation = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val hint = (sensorOrientation - deviceRotation + 360) % 360
        Log.i(TAG, "🧭 orientation details physical=$physicalCameraId sensor=$sensorOrientation displayRotation=$displayRotation deviceDegrees=$deviceRotation hint=$hint")
        return hint
    }

    private fun setupOutputFile(
        recorder: MediaRecorder,
        folder: VideoBatchesFolder,
        counter: Long,
    ): OutputTarget {
        val ext = settings.videoRecorderSettings.fileExtension
        val fileName = getOutputFileName(folder, counter, ext)
        val target = createOutputTarget(folder, fileName, counter, ext)
        setRecorderOutputFile(recorder, target)
        Log.i(TAG, "📁 outputFile: ${target.label}")
        return target
    }

    private fun getOutputFileName(folder: VideoBatchesFolder, counter: Long, ext: String): String {
        val sid = folder.sessionId ?: ""
        return "${folder.mediaPrefix}${sid}-%03d.%s".format(counter, ext)
    }

    private fun createOutputTarget(
        folder: VideoBatchesFolder,
        fileName: String,
        counter: Long,
        ext: String,
    ): OutputTarget {
        return when (folder.type) {
            BatchesFolder.BatchType.INTERNAL -> {
                OutputTarget(file = folder.asInternalGetOutputFile(fileName), label = fileName).also {
                    it.file!!.parentFile?.mkdirs()
                }
            }
            BatchesFolder.BatchType.CUSTOM -> {
                val parent = if (folder.taskFolderName != null) {
                    folder.getCustomDefinedFolder().findFile(folder.taskFolderName!!)
                        ?: folder.getCustomDefinedFolder().createDirectory(folder.taskFolderName!!)
                } else {
                    folder.getCustomDefinedFolder()
                } ?: error("Custom output folder unavailable")
                val actualName = if (folder.taskFolderName != null) {
                    "%03d.%s".format(counter, ext)
                } else {
                    fileName
                }
                val file = parent.createFile("video/$ext", actualName)
                    ?: error("Cannot create custom output file $actualName")
                val descriptor = context.contentResolver.openFileDescriptor(file.uri, "w")
                    ?: error("Cannot open custom output file $actualName")
                outputDescriptors += descriptor
                OutputTarget(uri = file.uri, descriptor = descriptor, label = file.uri.toString())
            }
            BatchesFolder.BatchType.MEDIA -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val uri = context.contentResolver.insert(
                        folder.scopedMediaContentUri,
                        folder.asMediaGetScopedStorageContentValues(fileName),
                    ) ?: error("Cannot insert MediaStore output $fileName")
                    synchronized(pendingUris) { pendingUris.add(uri) }
                    val descriptor = context.contentResolver.openFileDescriptor(uri, "w")
                        ?: error("Cannot open MediaStore output $uri")
                    outputDescriptors += descriptor
                    OutputTarget(uri = uri, descriptor = descriptor, label = uri.toString())
                } else {
                    OutputTarget(file = File(folder.legacyMediaFolder, fileName), label = fileName).also {
                        it.file!!.parentFile?.mkdirs()
                    }
                }
            }
        }
    }

    private fun setRecorderOutputFile(recorder: MediaRecorder, target: OutputTarget) {
        target.descriptor?.let { recorder.setOutputFile(it.fileDescriptor) }
            ?: target.file?.let { recorder.setOutputFile(it.absolutePath) }
            ?: error("Output target has no writable destination")
    }

    /**
     * Rotate to a new time-based segment. MediaRecorder.setNextOutputFile()
     * cannot force a time boundary; it only queues a file for size rollover.
     * We therefore finalize the current pair and recreate both recorders and
     * their physical Camera2 session.
     */
    suspend fun rotateNextChunk(counter: Long): Boolean {
        return rotateNextChunk(counter, onCaptureStarted = null)
    }

    suspend fun rotateNextChunk(
        counter: Long,
        onCaptureStarted: (() -> Unit)?,
    ): Boolean {
        if (counter <= (currentCounter ?: Long.MIN_VALUE)) {
            Log.d(TAG, "🔄 Skipping physical chunk rotation for already active counter=$counter")
            return true
        }

        val rotationStartMs = SystemClock.elapsedRealtime()
        Log.i(TAG, "🔄 Rotating physical segment: current=$currentCounter next=$counter")
        isRecording = false

        var started = false
        try {
            coroutineScope {
                val surfacesToRelease = listOfNotNull(primarySurface, secondarySurface)
                val ready = requestCaptureStop()
                if (ready != null) {
                    withTimeoutOrNull(1500L) {
                        ready.await()
                    } ?: Log.w(TAG, "📸 Timed out waiting for capture session ready")
                }
                finishCaptureStop()

                // Old MediaRecorder.stop() can take about one second on Vivo.
                // Submit the next camera session while those independent files
                // finalize; the caller gets a usable stream much sooner.
                val retired = detachRetiredSegment(surfacesToRelease)
                val retiredFinalizer = async(Dispatchers.IO) {
                    finalizeRetiredSegment(retired)
                }

                try {
                    started = startAndAwait(counter)
                    if (started) {
                        onCaptureStarted?.invoke()
                        Log.i(TAG, "🔄 ✅ Physical segment restarted at counter=$counter")
                    } else {
                        Log.e(TAG, "🔄 ❌ Physical segment restart failed at counter=$counter")
                    }
                } finally {
                    retiredFinalizer.join()
                }
            }
        } catch (error: Exception) {
            started = false
            Log.e(TAG, "🔄 ❌ Physical segment rotation threw an exception", error)
        }

        if (started) {
            Log.i(TAG, "🔄 Physical segment rotation completed in ${SystemClock.elapsedRealtime() - rotationStartMs}ms")
        }
        return started
    }

    private suspend fun startAndAwait(counter: Long): Boolean = suspendCancellableCoroutine { continuation ->
        try {
            start(
                counter = counter,
                onStarted = {
                    if (continuation.isActive) continuation.resume(true)
                },
                onError = {
                    if (continuation.isActive) continuation.resume(false)
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart physical segment", e)
            if (continuation.isActive) continuation.resume(false)
        }
    }

    /**
     * Commit all pending MediaStore URIs (sets IS_PENDING = 0)
     * so they immediately show up in the gallery and file manager.
     */
    private fun commitPendingUris(uris: List<Uri>? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            val urisToCommit = uris ?: synchronized(pendingUris) {
                pendingUris.toList().also { pendingUris.clear() }
            }

            if (urisToCommit.isNotEmpty()) {
                for (uri in urisToCommit) {
                    try {
                        val count = context.contentResolver.update(uri, values, null, null)
                        Log.i(TAG, "📁 Committed Scoped Storage URI: $uri (updated=$count)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to commit URI $uri", e)
                    }
                }
            }
        }
    }

    fun stop() {
        Log.i(TAG, "⏹️ Stopping Camera2PhysicalDualRecorder")

        isRecording = false

        // Error/fallback path is synchronous. Normal stop uses the suspend
        // variant below so CameraCaptureSession.onReady can be awaited.
        stopCurrentCaptureAndRecordersBlocking(stopBackground = true)
        clearOutputState()
        Log.i(TAG, "⏹️ Camera2PhysicalDualRecorder stopped completely (audio mux skipped)")
    }

    /**
     * Stops both physical streams, then copies primary AAC audio into each
     * secondary video. FFmpeg runs asynchronously and is awaited off the main
     * thread so the service can finish its stop lifecycle deterministically.
     */
    suspend fun stopAndMuxAudio() {
        Log.i(TAG, "⏹️ Stopping physical dual recorder with shared audio")
        isRecording = false

        stopCurrentCaptureAndRecorders(stopBackground = true)
        muxPrimaryAudioIntoSecondary()
        clearOutputState()

        Log.i(TAG, "⏹️ Camera2PhysicalDualRecorder stopped completely (audio mux finished)")
    }

    private suspend fun stopCurrentCaptureAndRecorders(stopBackground: Boolean) {
        val stopStartMs = SystemClock.elapsedRealtime()
        val surfacesToRelease = listOfNotNull(primarySurface, secondarySurface)
        val ready = requestCaptureStop()
        if (ready != null) {
            withTimeoutOrNull(1500L) {
                ready.await()
            } ?: Log.w(TAG, "📸 Timed out waiting for capture session ready")
        }
        finishCaptureStop()
        if (stopBackground) {
            finalizeRetiredSegment(detachRetiredSegment(surfacesToRelease))
            closeCameraDevice()
            stopBackgroundThread()
        } else {
            finalizeRetiredSegment(detachRetiredSegment(surfacesToRelease))
        }
        Log.i(
            TAG,
            "⏹️ Physical capture/recorders stopped in ${SystemClock.elapsedRealtime() - stopStartMs}ms " +
                "(keepCamera=${!stopBackground})",
        )
    }

    private fun stopCurrentCaptureAndRecordersBlocking(stopBackground: Boolean) {
        val surfacesToRelease = listOfNotNull(primarySurface, secondarySurface)
        requestCaptureStop()
        finishCaptureStop()
        finalizeRecorders(stopBackground)
        releaseSurfaces(surfacesToRelease)
    }

    private fun closeCameraDevice() {
        try {
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing camera device", e)
        }
    }

    private fun requestCaptureStop(): CompletableDeferred<Unit>? {
        val session = captureSession ?: return null
        val ready = CompletableDeferred<Unit>()
        captureSessionReady = ready
        try {
            // Stop camera requests before stopping MediaRecorder. Otherwise
            // Vivo may return MediaRecorder.stop() error -1004 and omit moov.
            session.stopRepeating()
            session.abortCaptures()
            Log.i(TAG, "📸 Capture requests stopped; waiting for session ready")
        } catch (e: Exception) {
            Log.w(TAG, "📸 Failed to stop/abort capture requests", e)
            ready.complete(Unit)
        }
        return ready
    }

    private fun finishCaptureStop() {
        try {
            captureSession?.close()
            captureSession = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing capture session", e)
        } finally {
            captureSessionReady = null
            primarySurface = null
            secondarySurface = null
        }
    }

    private fun releaseSurfaces(surfaces: List<Surface>) {
        surfaces.forEachIndexed { index, surface ->
            runCatching { surface.release() }
                .onFailure { Log.w(TAG, "Error releasing recorder surface $index", it) }
        }
    }

    private fun finalizeRecorders(stopBackground: Boolean) {
        val finalizeStartMs = SystemClock.elapsedRealtime()
        try {
            primaryRecorder?.setOnErrorListener(null)
            primaryRecorder?.setOnInfoListener(null)
            primaryRecorder?.stop()
            Log.i(TAG, "⏹️ Primary physical recorder finalized")
        } catch (e: Exception) {
            Log.w(TAG, "Primary physical recorder stop failed", e)
        }

        try {
            secondaryRecorder?.setOnErrorListener(null)
            secondaryRecorder?.setOnInfoListener(null)
            secondaryRecorder?.stop()
            Log.i(TAG, "⏹️ Secondary physical recorder finalized")
        } catch (e: Exception) {
            Log.w(TAG, "Secondary physical recorder stop failed", e)
        }

        releaseRecorder(primaryRecorder, "primary")
        releaseRecorder(secondaryRecorder, "secondary")

        if (stopBackground) {
            try {
                cameraDevice?.close()
                cameraDevice = null
            } catch (e: Exception) {
                Log.w(TAG, "Error closing camera device", e)
            }
        } else {
            Log.d(TAG, "♻️ Keeping CameraDevice open for the next physical dual segment")
        }

        commitPendingUris()
        closeOutputDescriptors()
        if (stopBackground) stopBackgroundThread()
        Log.i(TAG, "⏹️ Physical recorders finalized in ${SystemClock.elapsedRealtime() - finalizeStartMs}ms")
    }

    private fun releaseRecorder(recorder: MediaRecorder?, label: String) {
        if (recorder == null) return
        runCatching {
            recorder.reset()
            recorder.release()
            Log.i(TAG, "⏹️ Released $label physical recorder")
        }.onFailure { Log.w(TAG, "Error releasing $label physical recorder", it) }
        if (label == "primary") primaryRecorder = null else secondaryRecorder = null
    }

    private fun detachRetiredSegment(surfaces: List<Surface>): RetiredSegment {
        val primary = primaryRecorder
        val secondary = secondaryRecorder
        primaryRecorder = null
        secondaryRecorder = null

        val uris = synchronized(pendingUris) {
            pendingUris.toList().also { pendingUris.clear() }
        }
        val descriptors = synchronized(outputDescriptors) {
            outputDescriptors.toList().also { outputDescriptors.clear() }
        }

        return RetiredSegment(primary, secondary, surfaces, uris, descriptors)
    }

    private suspend fun finalizeRetiredSegment(segment: RetiredSegment) {
        val finalizeStartMs = SystemClock.elapsedRealtime()

        coroutineScope {
            val primaryJob = segment.primaryRecorder?.let { recorder ->
                async(Dispatchers.IO) { finalizeRecorder(recorder, "primary") }
            }
            val secondaryJob = segment.secondaryRecorder?.let { recorder ->
                async(Dispatchers.IO) { finalizeRecorder(recorder, "secondary") }
            }
            primaryJob?.join()
            secondaryJob?.join()
        }

        releaseSurfaces(segment.surfaces)
        commitPendingUris(segment.pendingUris)
        closeOutputDescriptors(segment.outputDescriptors)
        Log.i(
            TAG,
            "⏹️ Physical recorders finalized concurrently in " +
                "${SystemClock.elapsedRealtime() - finalizeStartMs}ms",
        )
    }

    private fun finalizeRecorder(recorder: MediaRecorder?, label: String) {
        if (recorder == null) return
        val finalizeStartMs = SystemClock.elapsedRealtime()

        try {
            recorder.setOnErrorListener(null)
            recorder.setOnInfoListener(null)
            recorder.stop()
            Log.i(TAG, "⏹️ $label physical recorder finalized")
        } catch (e: Exception) {
            Log.w(TAG, "$label physical recorder stop failed", e)
        } finally {
            runCatching {
                recorder.reset()
                recorder.release()
                Log.i(TAG, "⏹️ Released $label physical recorder")
            }.onFailure { Log.w(TAG, "Error releasing $label physical recorder", it) }
            Log.i(
                TAG,
                "⏹️ $label physical recorder finalized in " +
                    "${SystemClock.elapsedRealtime() - finalizeStartMs}ms",
            )
        }
    }

    private fun closeOutputDescriptors(descriptors: List<ParcelFileDescriptor>? = null) {
        val descriptorsToClose = descriptors ?: synchronized(outputDescriptors) {
            outputDescriptors.toList().also { outputDescriptors.clear() }
        }

        descriptorsToClose.forEach { descriptor ->
                runCatching { descriptor.close() }
                    .onFailure { Log.w(TAG, "Error closing output descriptor", it) }
            }

        if (descriptors == null) {
            outputDescriptors.clear()
        }
    }

    private fun clearOutputState() {
        synchronized(pendingUris) { pendingUris.clear() }
        segmentTargets.clear()
        currentCounter = null
    }

    private suspend fun muxPrimaryAudioIntoSecondary() = withContext(Dispatchers.IO) {
        if (isSinglePhysicalStream) {
            Log.i(TAG, "🔊 Single physical stream already carries audio; skip mux")
            return@withContext
        }
        if (!enableAudio) {
            Log.i(TAG, "🔊 Shared audio disabled; skip physical secondary mux")
            return@withContext
        }

        val targets = segmentTargets.toSortedMap()
        Log.i(TAG, "🔊 Muxing primary audio into ${targets.size} physical video segment(s)")

        for ((counter, segment) in targets) {
            val secondaryTarget = segment.secondary
            if (secondaryTarget == null) {
                Log.w(TAG, "🔊 Skip audio mux counter=$counter: secondary target unavailable")
                continue
            }
            val primaryInput = getFfmpegInput(segment.primary)
            val secondaryInput = getFfmpegInput(secondaryTarget)
            if (primaryInput == null || secondaryInput == null) {
                Log.w(TAG, "🔊 Skip audio mux counter=$counter: input unavailable")
                primaryInput?.close()
                secondaryInput?.close()
                continue
            }

            val temporaryOutput = File.createTempFile("dual-audio-$counter-", ".mp4", context.cacheDir)
            try {
                val command = "-protocol_whitelist file,fd,content,saf -y " +
                    "${ffmpegInputArguments(secondaryInput)} " +
                    "${ffmpegInputArguments(primaryInput)} " +
                    "-map 0:v:0 -map 1:a:0 -c:v copy -c:a copy -shortest " +
                    shellQuote(temporaryOutput.absolutePath)
                Log.i(
                    TAG,
                    "🔊 Mux start counter=$counter " +
                        "primary=${segment.primary.label}(fd=${primaryInput.fd ?: "path"}) " +
                        "secondary=${segment.secondary.label}(fd=${secondaryInput.fd ?: "path"}) " +
                        "command=$command",
                )

                val succeeded = runFfmpeg(command)
                if (succeeded && temporaryOutput.exists() && temporaryOutput.length() > 0L) {
                    val copied = copyFileToTarget(temporaryOutput, secondaryTarget)
                    if (copied) {
                        Log.i(TAG, "🔊 ✅ Mux success counter=$counter, secondary audio replaced")
                    } else {
                        Log.e(TAG, "🔊 ❌ Mux output copy failed counter=$counter; original secondary kept")
                    }
                } else {
                    Log.e(TAG, "🔊 ❌ FFmpeg mux failed counter=$counter; original secondary kept")
                }
            } finally {
                primaryInput.close()
                secondaryInput.close()
                runCatching { temporaryOutput.delete() }
            }
        }
    }

    private fun getFfmpegInput(target: OutputTarget): FfmpegInput? {
        return runCatching {
            target.file?.absolutePath?.let { FfmpegInput(it) }
                ?: target.uri?.let { uri ->
                    val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                        ?: return@let null
                    Log.i(TAG, "🔊 Opened fresh mux input fd=${descriptor.fd} target=${target.label}")
                    FfmpegInput("fd:", descriptor.fd, descriptor)
                }
        }.onFailure {
            Log.w(TAG, "🔊 Cannot create FFmpeg input for ${target.label}", it)
        }.getOrNull()
    }

    private fun ffmpegInputArguments(input: FfmpegInput): String {
        return if (input.fd != null) {
            // FFmpegKit n6 requires the descriptor number as -fd N; passing
            // fd:N directly to -i is rejected on some vendor builds.
            "-fd ${input.fd} -i ${shellQuote(input.path)}"
        } else {
            "-i ${shellQuote(input.path)}"
        }
    }

    private suspend fun runFfmpeg(command: String): Boolean = suspendCancellableCoroutine { continuation ->
        try {
            FFmpegKit.executeAsync(command) { session ->
                val success = session != null && ReturnCode.isSuccess(session.returnCode)
                Log.i(TAG, "🔊 FFmpeg finished success=$success rc=${session?.returnCode}")
                if (!success) {
                    Log.e(
                        TAG,
                        "🔊 FFmpeg mux failure logs=" +
                            session?.allLogsAsString?.takeLast(MAX_FFMPEG_FAILURE_LOG_CHARS),
                    )
                }
                if (continuation.isActive) continuation.resume(success)
            }
        } catch (e: Exception) {
            Log.e(TAG, "🔊 FFmpeg invocation failed", e)
            if (continuation.isActive) continuation.resume(false)
        }
    }

    private fun copyFileToTarget(source: File, target: OutputTarget): Boolean {
        return runCatching {
            val output = target.file?.outputStream()
                ?: target.uri?.let { context.contentResolver.openOutputStream(it, "wt") }
                ?: error("Target has no writable stream")
            source.inputStream().use { input ->
                output.use { destination -> input.copyTo(destination) }
            }
            true
        }.onFailure {
            Log.e(TAG, "🔊 Cannot copy muxed output to ${target.label}", it)
        }.getOrDefault(false)
    }

    private fun shellQuote(value: String): String {
        return "'${value.replace("'", "'\\''")}'"
    }

    companion object {
        private const val TAG = "Camera2PhysicalDual"
        private const val MAX_FFMPEG_FAILURE_LOG_CHARS = 4000
    }
}
