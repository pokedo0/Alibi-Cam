package app.leo.alibi_cam.services

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.util.Range
import androidx.annotation.RequiresApi
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.TorchState
import androidx.camera.core.UseCaseGroup
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileDescriptorOutputOptions
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.leo.alibi_cam.NotificationHelper
import app.leo.alibi_cam.R
import app.leo.alibi_cam.dataStore
import app.leo.alibi_cam.db.RecordingInformation
import app.leo.alibi_cam.enums.RecorderState
import app.leo.alibi_cam.helpers.BatchesFolder
import app.leo.alibi_cam.helpers.Camera2PhysicalDualRecorder
import app.leo.alibi_cam.helpers.VideoBatchesFolder
import app.leo.alibi_cam.ui.RECORDER_INTERNAL_SELECTED_VALUE
import app.leo.alibi_cam.ui.SUPPORTS_SAVING_VIDEOS_IN_CUSTOM_FOLDERS
import app.leo.alibi_cam.ui.SUPPORTS_SCOPED_STORAGE
import app.leo.alibi_cam.helpers.CameraDebugLog
import app.leo.alibi_cam.helpers.CameraPosition
import app.leo.alibi_cam.helpers.SensorDebugLog
import app.leo.alibi_cam.ui.utils.DualCameraSupport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.properties.Delegates
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class VideoRecorderService :
    IntervalRecorderService<RecordingInformation, VideoBatchesFolder>() {
    override var batchesFolder = VideoBatchesFolder.viaInternalFolder(this)

    /**
     * Session created by [start] and used by the actual capture pipeline.
     * Folder objects can be replaced while a shortcut-started Service is being
     * rebound to the UI; export must never fall back to a stale session id.
     */
    private var activeRecordingSessionId: String? = null

    @Volatile
    private var activePrimaryBatchesFolder: VideoBatchesFolder? = null

    @Volatile
    private var activeSecondaryBatchesFolder: VideoBatchesFolder? = null

    private val primaryCaptureFolder: VideoBatchesFolder
        get() = activePrimaryBatchesFolder ?: batchesFolder

    private val secondaryCaptureFolder: VideoBatchesFolder?
        get() = activeSecondaryBatchesFolder ?: secondaryBatchesFolder

    // Secondary batches folder for dual-camera output (null in single-camera mode)
    var secondaryBatchesFolder: VideoBatchesFolder? = null

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    /**
     * Per-camera recording state. Holds everything needed to manage one
     * concurrent video stream (camera, video capture, active recording,
     * finalizer handles).
     */
    private class RecordingStream(val position: CameraPosition) {
        var camera: Camera? = null
        var videoCapture: VideoCapture<Recorder>? = null
        var activeRecording: Recording? = null

        @Volatile
        var activeRecordingFinalizer: CompletableDeferred<Unit>? = null

        @Volatile
        var activeRecordingStart: CompletableDeferred<Unit>? = null

        @Volatile
        var previousRecordingFinalizer: CompletableDeferred<Unit>? = null
    }

    private var primary = RecordingStream(CameraPosition.SINGLE)
    private var secondary: RecordingStream? = null

    private var physicalDualRecorder: Camera2PhysicalDualRecorder? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var isDualMode = false

    // Used to listen and check if the camera is available
    private var _cameraAvailableListener = CompletableDeferred<Unit>()

    // Absolute last completer that can be awaited to ensure that the camera is closed
    private var _cameraCloserListener = CompletableDeferred<Unit>()

    private lateinit var selectedCamera: CameraSelector
    private var cameraLensMode: String? = null  // "ultrawide" | "main" | null → zoom strategy
    private var enableAudio by Delegates.notNull<Boolean>()

    var onCameraControlAvailable = {}

    /** Whether dual camera is currently active (both streams recording). */
    fun isDualCameraActive(): Boolean = isDualMode

    /**
     * True after the selected camera path has reached its framework open request.
     * The shortcut entry waits only for this local dispatch, not for HAL readiness.
     */
    @Volatile
    var isCaptureStartRequested = false
        private set

    /**
     * Some OEM camera HALs defer background openCamera calls until they decide
     * that the caller is still the top app. Expose actual pipeline readiness so
     * the invisible shortcut Activity can stay foreground until capture starts.
     */
    val isCapturePipelineReady: Boolean
        get() = cameraControl != null || physicalDualRecorder?.isRecording == true

    // ── 传感器自动停止 ──
    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    private var recordingWakeLock: PowerManager.WakeLock? = null
    private var referenceQuaternion: FloatArray? = null
    private var deviationStartTime: Long = 0L
    private var autoStopWarningActive = false
    private var cancelledAutoStop = false
    private var cooldownUntil: Long = 0L
    private var smoothedAngle: Float = 0f  // EMA 低通滤波后的角度（滤除减速带/坑洞振动尖峰）
    // A: 参考四元数多采平均（避免单次快照被振动污染）
    private var referenceSamples = mutableListOf<FloatArray>()
    private var referenceSampleCount = 0
    // D: 自适应参考更新计时（姿态长时间稳定后悄悄修正参考值）
    private var stableStartTime: Long = 0L

    var cameraControl: CameraControl? = null
        private set

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ── Handle auto-stop cancel action ──
        if (intent?.action == ACTION_CANCEL_AUTO_STOP) {
            SensorDebugLog.logEvent("WARNING_CANCELLED_BY_USER")
            cancelledAutoStop = true
            NotificationManagerCompat.from(this)
                .cancel(AUTO_STOP_WARNING_NOTIFICATION_ID)
            autoStopWarningActive = false
            deviationStartTime = 0L
            Log.i(TAG, "🔄 Auto-stop cancelled by user")
            return START_NOT_STICKY
        }

        if (intent?.action == "init") {
            val lensFacing = intent.getIntExtra("cameraID", CameraSelector.LENS_FACING_BACK)
            cameraLensMode = intent.getStringExtra("cameraLensMode")

            Log.i(TAG, "🎥 Service init: lensFacing=${
                when(lensFacing) {
                    CameraSelector.LENS_FACING_BACK -> "BACK"
                    CameraSelector.LENS_FACING_FRONT -> "FRONT"
                    else -> lensFacing.toString()
                }
            }, cameraLensMode=${cameraLensMode ?: "null (default)"}")
            CameraDebugLog.append("")
            CameraDebugLog.append("🎥 Service init: lensFacing=${
                when(lensFacing) {
                    CameraSelector.LENS_FACING_BACK -> "BACK"
                    CameraSelector.LENS_FACING_FRONT -> "FRONT"
                    else -> lensFacing
                }
            }, cameraLensMode=${cameraLensMode ?: "null"}")

            // Simple selector — always bind the logical camera.
            // UW is engaged through zoom ratio, not physical camera ID.
            selectedCamera = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            enableAudio = intent.getBooleanExtra("enableAudio", true)
        }

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        releaseRecordingWakeLock("destroy")
        super.onDestroy()
    }

    override fun start() {
        // Flat storage: all chunks go directly into the base folder
        // (DCIM/alibi/.video_recordings/) with session-scoped names like
        // alibi-video_recordings-{sessionId}-{counter}.mp4.
        // No per-session subfolder needed — avoids RELATIVE_PATH query
        // issues on OEM firmware (Xiaomi HyperOS etc.).
        val sessionId = LocalDateTime.now().format(
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
        )
        activeRecordingSessionId = sessionId
        activePrimaryBatchesFolder = batchesFolder.also { it.sessionId = sessionId }
        activeSecondaryBatchesFolder = secondaryBatchesFolder?.also { it.sessionId = sessionId }
        Log.i(
            TAG,
            "📁 Session: $sessionId (flat storage), " +
                "primary=${System.identityHashCode(activePrimaryBatchesFolder)}, " +
                "secondary=${activeSecondaryBatchesFolder?.let { System.identityHashCode(it) } ?: "none"}",
        )
        CameraDebugLog.append("📁 Session: $sessionId (flat)")

        acquireRecordingWakeLock()
        super.start()

        scope.launch {
            openCamera()
        }

        startOrientationMonitoring()
    }

    override fun shouldDelayRecordingTimeUntilReady(): Boolean = true

    override suspend fun stop() {
        try {
            stopOrientationMonitoring()
            activeRecordingSessionId?.let { sessionId ->
                Log.i(TAG, "💾 Restoring authoritative recording session before export: $sessionId")
                primaryCaptureFolder.sessionId = sessionId
                secondaryCaptureFolder?.sessionId = sessionId
            }
            super.stop()

            stopActiveRecording()

            // Camera can only be closed after the recording has been finalized
            withTimeoutOrNull(CAMERA_CLOSE_TIMEOUT) {
                primary.activeRecordingFinalizer?.await()
                secondary?.activeRecordingFinalizer?.await()
            }

            closeCamera()

            withTimeoutOrNull(CAMERA_CLOSE_TIMEOUT) {
                _cameraCloserListener.await()
            }
        } finally {
            releaseRecordingWakeLock("stop")
        }
    }

    override fun pause() {
        super.pause()

        stopActiveRecording()
        releaseRecordingWakeLock("pause")
    }

    override fun resume() {
        super.resume()
        acquireRecordingWakeLock()
    }

    private fun acquireRecordingWakeLock() {
        if (recordingWakeLock?.isHeld == true) return

        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            recordingWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AlibiCam:VideoRecording",
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "🔋 Acquired video recording WakeLock")
        } catch (error: Exception) {
            Log.e(TAG, "Failed to acquire video recording WakeLock", error)
        }
    }

    private fun releaseRecordingWakeLock(reason: String) {
        recordingWakeLock?.let { wakeLock ->
            if (wakeLock.isHeld) {
                wakeLock.release()
                Log.i(TAG, "🔋 Released video recording WakeLock ($reason)")
            }
        }
        recordingWakeLock = null
    }

    override fun startForegroundService() {
        ServiceCompat.startForeground(
            this,
            NotificationHelper.RECORDER_CHANNEL_NOTIFICATION_ID,
            getNotificationHelper().buildStartingNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (enableAudio)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                0
            },
        )
    }

    @SuppressLint("MissingPermission")
    override fun startNewCycle() {
        try {
            // Increment counter immediately so getNameForMediaFile() picks the
            // right chunk number (001, 002, …). We do NOT call super.startNewCycle()
            // because we need deleteOldRecordings() to run AFTER action() — the
            // just-finalized chunk must be on disk/MediaStore when we count.
            counter += 1

            Log.i(TAG, "🔄 startNewCycle: counter=$counter, interval=${settings.intervalDuration}ms, isDualMode=$isDualMode")
            CameraDebugLog.append("🔄 startNewCycle: counter=$counter, interval=${settings.intervalDuration}ms, dual=$isDualMode")

            val isRestartBoundary = _cameraAvailableListener.isCompleted
            if (isRestartBoundary) {
                // Segment finalization can take seconds on physical-dual devices.
                // Keep the user-facing timer aligned with captured video instead
                // of counting the camera restart gap.
                Log.d(TAG, "⏱️ Pausing recording-time timer during segment rotation")
                stopRecordingTimeTimer()
            }

            fun action() {
                if (physicalDualRecorder?.isRecording == true) {
                    // The interval timer runs on its own scheduler thread. Wait
                    // for both physical recorders to finalize and for the new
                    // Camera2 session to be ready before pruning old chunks or
                    // allowing the next interval to overlap this rotation.
                    runBlocking(Dispatchers.IO) {
                        physicalDualRecorder?.rotateNextChunk(counter) {
                            startRecordingTimeTimerIfNeeded()
                        }
                    }
                } else {
                    stopActiveRecording()
                    startCycleForStream(primary, primaryCaptureFolder)
                    secondary?.let { stream ->
                        secondaryCaptureFolder?.let { folder ->
                            startCycleForStream(stream, folder)
                        }
                    }
                }
            }

            if (!_cameraAvailableListener.isCompleted) {
                Log.i(TAG, "⏳ Waiting for camera/recorder readiness before first interval boundary")

                // The interval scheduler thread may block here. Combined with
                // scheduleWithFixedDelay, the next segment starts intervalDuration
                // after this segment is actually able to record.
                val cameraReady = runBlocking {
                    withTimeoutOrNull(CAMERA_OPEN_TIMEOUT_MS) {
                        _cameraAvailableListener.await()
                    }
                }

                if (cameraReady == null) {
                    Log.e(TAG, "❌ Camera/recorder did not become ready before interval start")
                    CameraDebugLog.append("❌ Camera/recorder readiness timeout before interval start")
                    CameraDebugLog.flush()
                    return
                }
            }

            action()

            if (physicalDualRecorder?.isRecording != true) {
                awaitActualRecordingStart()
            }

            // The notification timer must measure captured video time, not
            // shortcut/camera initialization or segment-restart time. CameraX
            // only reports Start after its encoder receives the first frame;
            // physical dual's MediaRecorder path is already ready above.
            startRecordingTimeTimerIfNeeded()

            // ── Prune AFTER action() ──
            // At this point the previous chunk has been finalized by stopActiveRecording()
            // and its MediaStore entry is committed (IS_PENDING=0). Counting now is accurate.
            deleteOldRecordings()
        } catch (e: Exception) {
            Log.e(TAG, "❌ startNewCycle FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            CameraDebugLog.append("❌ startNewCycle FAILED: ${e.javaClass.simpleName}: ${e.message}")
            CameraDebugLog.flush()
        }
    }

    /**
     * Start a new recording cycle for a single stream.
     * This is called for both primary and secondary in dual mode.
     */
    @SuppressLint("MissingPermission")
    private fun startCycleForStream(stream: RecordingStream, folder: VideoBatchesFolder) {
        val videoCapture = stream.videoCapture ?: return

        // The currently-active finalizer belongs to the recording we're
        // about to stop. Save it so save flows can await it.
        val previousFinalizer = stream.activeRecordingFinalizer
        stream.previousRecordingFinalizer = previousFinalizer

        previousFinalizer?.let { finalizer ->
            try {
                runBlocking {
                    withTimeoutOrNull(CHUNK_FINALIZE_TIMEOUT_MS) { finalizer.await() }
                        ?: Log.w(TAG, "Timed out waiting for previous chunk finalization")
                }
            } catch (error: Exception) {
                Log.w(TAG, "Previous chunk finalization await failed", error)
            }
        }
        stream.activeRecordingFinalizer = CompletableDeferred()
        val activeFinalizer = stream.activeRecordingFinalizer
        stream.activeRecordingStart = CompletableDeferred()
        val activeStart = stream.activeRecordingStart

        val newRecording = prepareVideoRecording(videoCapture, folder)
        stream.activeRecording = newRecording.start(ContextCompat.getMainExecutor(this)) { event ->
            if (event is VideoRecordEvent.Start) {
                Log.i(TAG, "🎬 First frame captured for stream ${stream.position}")
                CameraDebugLog.append("🎬 First frame captured: ${stream.position}")
                activeStart?.complete(Unit)
            }
            if (event is VideoRecordEvent.Finalize) {
                val stats = event.recordingStats
                if (event.error == 0) {
                    Log.i(
                        TAG,
                        "🎞️ Chunk finalized stream=${stream.position} " +
                            "bytes=${stats.numBytesRecorded} durationNs=${stats.recordedDurationNanos}",
                    )
                } else {
                    Log.e(
                        TAG,
                        "🎞️ Chunk finalized with error=${event.error} stream=${stream.position} " +
                            "bytes=${stats.numBytesRecorded} durationNs=${stats.recordedDurationNanos}",
                    )
                }
                activeFinalizer?.complete(Unit)
            }
        }
        Log.i(TAG, "🎬 Recording started for stream ${stream.position}")
        CameraDebugLog.append("🎬 Recording started: ${stream.position}")
    }

    /**
     * CameraX can return from Recording.start() several seconds before the
     * encoder emits Start on OEM devices. Waiting here also shifts the next
     * fixed-delay boundary so a configured 10s chunk contains ~10s of video.
     */
    private fun awaitActualRecordingStart() {
        val startSignals = listOfNotNull(
            primary.activeRecordingStart,
            secondary?.activeRecordingStart,
        )

        if (startSignals.isEmpty()) return

        val waitStartedAtMs = android.os.SystemClock.elapsedRealtime()
        val ready = runBlocking {
            withTimeoutOrNull(CAMERA_OPEN_TIMEOUT_MS) {
                startSignals.map { signal -> signal.await() }
            }
        }

        if (ready == null) {
            Log.w(TAG, "Timed out waiting for actual CameraX recording start")
            CameraDebugLog.append("⚠️ Actual CameraX recording-start timeout")
            startSignals.forEach { signal -> signal.complete(Unit) }
        } else {
            Log.i(
                TAG,
                "⏱️ Actual CameraX capture confirmed in " +
                    "${android.os.SystemClock.elapsedRealtime() - waitStartedAtMs}ms",
            )
        }
    }

    /**
     * Global rolling-window pruning — flat storage version.
     *
     * All chunks live in a single flat folder with names like:
     *   alibi-video_recordings-{sessionId}-{counter}.mp4
     *
     * Lexicographic sort of display names = chronological order, so we simply
     * query all matching files, sort them, and delete the oldest if the total
     * exceeds `maxDuration / intervalDuration`.
     *
     * This avoids RELATIVE_PATH-based MediaStore queries which are unreliable
     * on some OEM firmware (e.g. Xiaomi HyperOS).
     */
    override fun deleteOldRecordings() {
        try {
            // 同步永久删除标志（基类 deleteOldRecordings() 也做了，但本覆写不走 super）
            primaryCaptureFolder.permanentlyDeleteRecordings = settings.permanentlyDeleteRecordings

            val maxDurationMs = settings.maxDuration // 已是毫秒

            Log.i(TAG, "🧹 Duration pruning: maxDurationMs=$maxDurationMs")
            CameraDebugLog.append("🧹 Duration pruning: maxDurationMs=$maxDurationMs")

            if (maxDurationMs <= 0) return

            val chunks = primaryCaptureFolder.listFlatChunksWithDuration()
            var totalMs = chunks.sumOf { it.second }

            Log.i(TAG, "🧹 ${chunks.size} chunks, totalMs=$totalMs")
            CameraDebugLog.append("🧹 ${chunks.size} chunks, totalMs=$totalMs")

            if (totalMs <= maxDurationMs) return

            var deleted = 0
            for ((name, durationMs) in chunks) {
                if (totalMs <= maxDurationMs) break
                val ok = primaryCaptureFolder.deleteFlatChunk(name)
                if (ok) {
                    totalMs -= durationMs
                    deleted++
                }
                Log.i(TAG, "🧹 deleteFlatChunk($name) dur=${durationMs}ms → $ok, remaining=$totalMs")
                CameraDebugLog.append("🧹 deleteFlatChunk($name) dur=${durationMs}ms → $ok, remaining=$totalMs")
            }

            Log.i(TAG, "🧹 Duration pruning complete: deleted $deleted chunks, final=$totalMs ms")
            CameraDebugLog.append("🧹 Duration pruning: deleted $deleted chunks, final=$totalMs ms")
            CameraDebugLog.flush()
            // Also prune secondary batches folder in dual-camera mode
            secondaryCaptureFolder?.let { secFolder ->
                secFolder.permanentlyDeleteRecordings = settings.permanentlyDeleteRecordings
                val secChunks = secFolder.listFlatChunksWithDuration()
                var secTotalMs = secChunks.sumOf { it.second }
                if (secTotalMs > maxDurationMs) {
                    var secDeleted = 0
                    for ((name, durationMs) in secChunks) {
                        if (secTotalMs <= maxDurationMs) break
                        val ok = secFolder.deleteFlatChunk(name)
                        if (ok) {
                            secTotalMs -= durationMs
                            secDeleted++
                        }
                    }
                    Log.i(TAG, "🧹 Dual-cam secondary pruning complete: deleted $secDeleted chunks")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "🧹 deleteOldRecordings FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            CameraDebugLog.append("🧹 ❌ deleteOldRecordings FAILED: ${e.javaClass.simpleName}: ${e.message}")
            CameraDebugLog.flush()
        }
    }

    // Runs a function in the main thread
    private fun runOnMain(callback: () -> Unit) {
        val mainHandler = ContextCompat.getMainExecutor(this)

        mainHandler.execute(callback)
    }

    /**
     * Build a Recorder with the specified aspect ratio.
     * @param aspectRatioOverride if non-null, overrides the user's aspect ratio setting.
     *        "4:3" → 0, "16:9" → 1, null → use user setting (default 4:3)
     */
    private fun buildRecorder(aspectRatioOverride: String? = null) = Recorder.Builder()
        .setQualitySelector(
            settings.videoRecorderSettings.getQualitySelector()
                ?: QualitySelector.from(Quality.HIGHEST)
        )
        .apply {
            val aspectRatio = aspectRatioOverride ?: settings.videoRecorderSettings.videoAspectRatio
            setAspectRatio(
                when (aspectRatio) {
                    "16:9" -> 1  // ASPECT_RATIO_16_9
                    else -> 0    // ASPECT_RATIO_4_3 (default)
                }
            )
        }
        .apply {
            if (settings.videoRecorderSettings.targetedVideoBitRate != null) {
                setTargetVideoEncodingBitRate(settings.videoRecorderSettings.targetedVideoBitRate!!)
            }
        }
        .build()

    private fun buildVideoCapture(recorder: Recorder) = VideoCapture.Builder(recorder)
        .apply {
            val frameRate = settings.videoRecorderSettings.targetFrameRate
            if (frameRate != null) {
                setTargetFrameRate(Range(frameRate, frameRate))
            }
        }
        .build()

    /**
     * Open the camera(s) and optionally engage ultra-wide via zoom ratio.
     *
      * In dual-camera mode, the capability plan chooses CameraX ConcurrentCamera
      * first and Camera2 physical streams when the OEM exposes rear sensors only
      * through one logical multi-camera:
      * - Binds two cameras through the selected backend
     * - Each camera records to its own stream and folder (separated by CameraPosition)
     * - If concurrent binding fails, gracefully falls back to single camera.
     */
    private suspend fun openCamera() {
        val dualRequested = settings.videoRecorderSettings.dualCameraEnabled
        val primaryLens = settings.videoRecorderSettings.cameraLens
        val secondaryLens = settings.videoRecorderSettings.secondaryCameraLens

        val cameraStartupStartedAt = android.os.SystemClock.elapsedRealtime()
        val plan = if (dualRequested && secondaryLens != null) {
            val physicalPlan = DualCameraSupport.resolvePhysicalDualPlan(
                this,
                primaryLens,
                secondaryLens,
            )

            if (physicalPlan.strategy == DualCameraSupport.Strategy.PHYSICAL_CAMERA2) {
                Log.i(
                    TAG,
                    "⚡ Skipping CameraX provider init for physical dual startup " +
                        "after ${android.os.SystemClock.elapsedRealtime() - cameraStartupStartedAt}ms",
                )
                physicalPlan
            } else {
                ensureCameraProvider()
                DualCameraSupport.resolveDualPlan(this, cameraProvider!!, primaryLens, secondaryLens)
            }
        } else {
            ensureCameraProvider()
            DualCameraSupport.DualPlan(DualCameraSupport.Strategy.NONE)
        }

        Log.i(TAG, "📸 openCamera: dualRequested=$dualRequested, strategy=${plan.strategy}")
        CameraDebugLog.append("📸 openCamera: dualReq=$dualRequested, plan=${plan.strategy}")

        when (plan.strategy) {
            DualCameraSupport.Strategy.CONCURRENT_CAMERAX -> {
                isDualMode = true
                openDualCamera(plan.cameraxPair!!)
            }
            DualCameraSupport.Strategy.PHYSICAL_CAMERA2 -> {
                isDualMode = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    openPhysicalDualCamera(plan.physicalPair!!)
                } else {
                    isDualMode = false
                    openSingleCamera()
                }
            }
            DualCameraSupport.Strategy.NONE -> {
                isDualMode = false
                openSingleCamera()
            }
        }
    }

    private suspend fun ensureCameraProvider() {
        if (cameraProvider != null) return

        val startedAt = android.os.SystemClock.elapsedRealtime()
        cameraProvider = withContext(Dispatchers.IO) {
            ProcessCameraProvider.getInstance(this@VideoRecorderService).get()
        }
        Log.i(
            TAG,
            "📷 CameraX provider initialized in " +
                "${android.os.SystemClock.elapsedRealtime() - startedAt}ms",
        )
    }

    /** Bind two physical rear sensors through their common logical camera. */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun openPhysicalDualCamera(pair: DualCameraSupport.PhysicalCameraPair) {
        val primaryPosition = CameraPosition.fromLensString(settings.videoRecorderSettings.cameraLens)
        val secondaryPosition = CameraPosition.fromLensString(settings.videoRecorderSettings.secondaryCameraLens)

        Log.i(
            TAG,
            "📸 openPhysicalDualCamera: logical=${pair.logicalCameraId}, " +
                "primary=${pair.primaryPhysicalId}, secondary=${pair.secondaryPhysicalId}",
        )
        CameraDebugLog.append(
            "📸 Physical dual: logical=${pair.logicalCameraId}, " +
                "primary=${pair.primaryPhysicalId}($primaryPosition), " +
                "secondary=${pair.secondaryPhysicalId}($secondaryPosition)",
        )

        // Keep both streams in the user's selected storage type. The position
        // suffix isolates secondary chunks and merged output from the primary.
        secondaryBatchesFolder = createSecondaryBatchesFolder(secondaryPosition).also {
            it.sessionId = primaryCaptureFolder.sessionId
            it.initFolders()
        }
        activeSecondaryBatchesFolder = secondaryBatchesFolder

        physicalDualRecorder = Camera2PhysicalDualRecorder(
            context = this,
            settings = settings,
            logicalCameraId = pair.logicalCameraId,
            primaryPhysicalId = pair.primaryPhysicalId,
            secondaryPhysicalId = pair.secondaryPhysicalId,
            primaryFolder = primaryCaptureFolder,
            secondaryFolder = secondaryBatchesFolder!!,
            enableAudio = enableAudio,
        )
        physicalDualRecorder!!.onLogicalCameraOpen = {
            isCaptureStartRequested = true
        }

        physicalDualRecorder!!.start(
            counter = counter,
            onStarted = {
                Log.i(TAG, "📸 ✅ Physical dual camera recorder ready")
                CameraDebugLog.append("📸 ✅ Physical dual recorder READY")
                runOnMain {
                    _cameraAvailableListener.complete(Unit)
                    onCameraControlAvailable()
                }
            },
            onError = { error ->
                Log.e(TAG, "📸 ❌ Physical dual camera failed: $error")
                CameraDebugLog.append("📸 ❌ Physical dual failed: $error")
                runOnMain {
                    physicalDualRecorder?.stop()
                    physicalDualRecorder = null
                    isDualMode = false
                    scope.launch {
                        ensureCameraProvider()
                        openSingleCamera()
                    }
                }
            },
        )
    }

    private fun createSecondaryBatchesFolder(position: CameraPosition): VideoBatchesFolder {
        return when (primaryCaptureFolder.type) {
            BatchesFolder.BatchType.INTERNAL -> VideoBatchesFolder.viaInternalFolder(this, position)
            BatchesFolder.BatchType.CUSTOM -> VideoBatchesFolder.viaCustomFolder(
                this,
                primaryCaptureFolder.customFolder
                    ?: error("Custom folder unavailable for dual recording"),
                position,
            )
            BatchesFolder.BatchType.MEDIA -> VideoBatchesFolder.viaMediaFolder(this, position)
        }
    }

    /**
     * Bind two cameras concurrently using CameraX ConcurrentCamera API.
     */
    private fun openDualCamera(pair: DualCameraSupport.SupportedPair) {
        val userAspectRatio = settings.videoRecorderSettings.videoAspectRatio ?: "4:3"
        val primaryRecorder = buildRecorder(userAspectRatio)
        val primaryVideoCapture = buildVideoCapture(primaryRecorder)
        val primaryUseCaseGroup = UseCaseGroup.Builder()
            .addUseCase(primaryVideoCapture)
            .build()

        val secondaryRecorder = buildRecorder(userAspectRatio)
        val secondaryVideoCapture = buildVideoCapture(secondaryRecorder)
        val secondaryUseCaseGroup = UseCaseGroup.Builder()
            .addUseCase(secondaryVideoCapture)
            .build()

        val primaryConfig = ConcurrentCamera.SingleCameraConfig(
            pair.primarySelector,
            primaryUseCaseGroup,
            this,
        )
        val secondaryConfig = ConcurrentCamera.SingleCameraConfig(
            pair.secondarySelector,
            secondaryUseCaseGroup,
            this,
        )

        Log.i(TAG, "📸 Binding concurrent cameras: primary=${pair.primarySelector}, secondary=${pair.secondarySelector}")
        CameraDebugLog.append("📸 Binding concurrent cameras")

        runOnMain {
            try {
                val concurrent = cameraProvider!!.bindToLifecycle(
                    listOf(primaryConfig, secondaryConfig)
                )
                isCaptureStartRequested = true

                primary = RecordingStream(CameraPosition.fromLensString(settings.videoRecorderSettings.cameraLens)).apply {
                    this.videoCapture = primaryVideoCapture
                    this.camera = concurrent.cameras.getOrNull(0)
                }
                secondary = RecordingStream(CameraPosition.fromLensString(settings.videoRecorderSettings.secondaryCameraLens)).apply {
                    this.videoCapture = secondaryVideoCapture
                    this.camera = concurrent.cameras.getOrNull(1)
                }

                // Keep secondary output in same storage type as primary.
                secondaryBatchesFolder = createSecondaryBatchesFolder(secondary!!.position).also {
                    it.sessionId = primaryCaptureFolder.sessionId
                    it.initFolders()
                }
                activeSecondaryBatchesFolder = secondaryBatchesFolder

                if (cameraLensMode == "ultrawide") {
                    engageUltraWide(primary.camera)
                }

                primary.camera?.let {
                    cameraControl = CameraControl(it).also { ctrl -> ctrl.init() }
                }
                onCameraControlAvailable()
                _cameraAvailableListener.complete(Unit)
                Log.i(TAG, "📸 ✅ Concurrent cameras bound successfully!")
                CameraDebugLog.append("📸 ✅ Concurrent cameras BOUND!")
            } catch (e: Exception) {
                Log.e(TAG, "📸 ❌ Dual camera binding failed: ${e.message}", e)
                CameraDebugLog.append("📸 ❌ Dual camera bind failed: ${e.message}")
                CameraDebugLog.flush()
                // Fallback to single camera
                isDualMode = false
                openSingleCamera()
            }
        }
    }

    private fun openSingleCamera() {
        val userAspectRatio = settings.videoRecorderSettings.videoAspectRatio ?: "4:3"

        // Step 1: Try with user's preferred aspect ratio
        tryBindCamera(
            aspectRatio = userAspectRatio,
        ) { success ->
            if (!success) {
                // Step 2: Retry with 16:9 (many sensors only support 16:9 natively)
                Log.i(TAG, "📸 Retrying with 16:9 (sensor may not support $userAspectRatio)...")
                CameraDebugLog.append("📸 Retry with 16:9")
                tryBindCamera(
                    aspectRatio = "16:9",
                ) { secondSuccess ->
                    if (!secondSuccess) {
                        // Step 3: Retry with 4:3 (opposite fallback)
                        if (userAspectRatio != "4:3") {
                            Log.i(TAG, "📸 Retrying with 4:3 as last resort...")
                            CameraDebugLog.append("📸 Retry with 4:3")
                            tryBindCamera(
                                aspectRatio = "4:3",
                            ) { thirdSuccess ->
                                if (!thirdSuccess) {
                                    Log.e(TAG, "📸 ❌ All binding attempts failed")
                                    CameraDebugLog.append("📸 ❌ All binding attempts failed")
                                    CameraDebugLog.flush()
                                    runOnMain { onError() }
                                }
                            }
                        } else {
                            Log.e(TAG, "📸 ❌ Binding failed with both 4:3 and 16:9")
                            CameraDebugLog.append("📸 ❌ Binding failed")
                            CameraDebugLog.flush()
                            runOnMain { onError() }
                        }
                    }
                }
            }
        }
    }

    /**
     * Attempt to bind the single camera with the given aspect ratio.
     * On success, engages ultra-wide via zoom ratio if cameraLensMode == "ultrawide".
     * Calls [onResult] with true if successful, false otherwise.
     */
    private fun tryBindCamera(
        aspectRatio: String?,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        val recorder = buildRecorder(aspectRatio)
        val videoCap = buildVideoCapture(recorder)

        primary = RecordingStream(CameraPosition.SINGLE).apply {
            this.videoCapture = videoCap
        }

        Log.i(TAG, "📸 Trying to bind camera [ratio=${aspectRatio ?: "4:3"}]...")
        CameraDebugLog.append("📸 Binding camera [ratio=${aspectRatio ?: "4:3"}]")

        runOnMain {
            try {
                val bindStartedAtMs = android.os.SystemClock.elapsedRealtime()
                primary.camera = cameraProvider!!.bindToLifecycle(
                    this,
                    selectedCamera,
                    primary.videoCapture
                )
                isCaptureStartRequested = true
                Log.i(
                    TAG,
                    "📸 CameraX bind/open request submitted in " +
                        "${android.os.SystemClock.elapsedRealtime() - bindStartedAtMs}ms",
                )

                val boundCameraId = primary.camera?.cameraInfo?.let {
                    Camera2CameraInfo.from(it).cameraId
                }
                Log.i(TAG, "📸 ✅ Camera bound! ID=$boundCameraId, ratio=${aspectRatio ?: "4:3"}")
                CameraDebugLog.append("📸 ✅ Camera BOUND! ID=$boundCameraId, ratio=${aspectRatio ?: "4:3"}")

                // ── Ultra-wide engagement via zoom ratio ──
                if (cameraLensMode == "ultrawide") {
                    engageUltraWide(primary.camera)
                }

                primary.camera?.let {
                    cameraControl = CameraControl(it).also { ctrl ->
                        ctrl.init()
                    }
                }
                onCameraControlAvailable()
                _cameraAvailableListener.complete(Unit)
                onResult?.invoke(true)
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "📸 ❌ Bind failed [ratio=${aspectRatio ?: "4:3"}]: ${error.message}")
                CameraDebugLog.append("📸 ❌ Bind failed: ${error.message}")
                onResult?.invoke(false)
            }
        }
    }

    /**
     * Engage ultra-wide by setting the zoom ratio to the camera's minimum.
     */
    private fun engageUltraWide(cam: Camera? = primary.camera) {
        val cameraInfo = cam?.cameraInfo ?: return
        val zoomState = cameraInfo.zoomState.value
        val minZoom = zoomState?.minZoomRatio ?: 1f
        val maxZoom = zoomState?.maxZoomRatio ?: 1f

        Log.i(TAG, "📸 Zoom state: min=$minZoom, max=$maxZoom")
        CameraDebugLog.append("📸 Zoom state: min=$minZoom, max=$maxZoom")

        if (minZoom < 1.0f) {
            cam.cameraControl.setZoomRatio(minZoom)
            Log.i(TAG, "📸 ✅ Ultra-wide ENGAGED via zoom: ${"%.2f".format(minZoom)}x")
            CameraDebugLog.append("📸 ✅ UW ENGAGED via zoom: ${"%.2f".format(minZoom)}x")
        } else {
            Log.i(TAG, "📸 ℹ️ minZoom=$minZoom — no ultra-wide hardware available, staying at 1x")
            CameraDebugLog.append("📸 ⚠️ minZoom=$minZoom, no UW hardware (zoom stays at 1x)")
        }
        CameraDebugLog.flush()
    }

    // ────────────────────────────────────────────────────────────
    //  Orientation-based auto-stop (sensor monitoring)
    // ────────────────────────────────────────────────────────────

    private fun startOrientationMonitoring() {
        if (!settings.autoStopSensorEnabled) return

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        if (rotationSensor == null) {
            Log.w(TAG, "⚠️ TYPE_ROTATION_VECTOR not available on this device")
            return
        }

        sensorManager?.registerListener(
            sensorListener,
            rotationSensor,
            SensorManager.SENSOR_DELAY_NORMAL  // ~200ms / ~5 Hz
        )

        cooldownUntil = System.currentTimeMillis() + AUTO_STOP_COOLDOWN_MS
        referenceQuaternion = null  // A: 等攒够15个采样再定
        referenceSamples.clear()
        referenceSampleCount = 0
        deviationStartTime = 0L
        autoStopWarningActive = false
        cancelledAutoStop = false
        smoothedAngle = 0f  // 重置低通滤波
        stableStartTime = 0L  // D: 重置自适应计时
        SensorDebugLog.init(this)  // 初始化传感器调试日志
        Log.i(TAG, "🔄 Orientation monitoring started (cooldown 10s)")
    }

    private fun stopOrientationMonitoring() {
        sensorManager?.unregisterListener(sensorListener)
        sensorManager = null
        rotationSensor = null
        referenceQuaternion = null
        referenceSamples.clear()
        referenceSampleCount = 0
        deviationStartTime = 0L
        autoStopWarningActive = false
        cancelledAutoStop = false
        stableStartTime = 0L
        SensorDebugLog.close()  // 关闭传感器调试日志
        Log.i(TAG, "🔄 Orientation monitoring stopped")
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

            val currentQ = event.values.clone()  // [x*sin(θ/2), y*sin(θ/2), z*sin(θ/2), cos(θ/2)]

            // ── A: 参考四元数多采平均（15个采样 ≈ 3秒，避免单次快照被振动污染）──
            if (referenceQuaternion == null) {
                if (referenceSamples.isEmpty()) {
                    referenceSamples.add(currentQ.copyOf())
                } else {
                    // 确保与第一个采样在同一半球（四元数 q 和 -q 表示相同旋转）
                    val first = referenceSamples[0]
                    val dot = first[0] * currentQ[0] + first[1] * currentQ[1] +
                            first[2] * currentQ[2] + first[3] * currentQ[3]
                    if (dot < 0) {
                        referenceSamples.add(floatArrayOf(-currentQ[0], -currentQ[1], -currentQ[2], -currentQ[3]))
                    } else {
                        referenceSamples.add(currentQ.copyOf())
                    }
                }
                referenceSampleCount++
                if (referenceSampleCount >= REFERENCE_SAMPLE_COUNT) {
                    // 取平均并归一化
                    val avg = floatArrayOf(0f, 0f, 0f, 0f)
                    for (q in referenceSamples) {
                        avg[0] += q[0]; avg[1] += q[1]; avg[2] += q[2]; avg[3] += q[3]
                    }
                    val norm = sqrt(avg[0] * avg[0] + avg[1] * avg[1] + avg[2] * avg[2] + avg[3] * avg[3])
                    referenceQuaternion = floatArrayOf(avg[0] / norm, avg[1] / norm, avg[2] / norm, avg[3] / norm)
                    referenceSamples.clear()
                    SensorDebugLog.logEvent("REF_CAPTURED samples=$REFERENCE_SAMPLE_COUNT q=[${"%.4f".format(referenceQuaternion!![0])},${"%.4f".format(referenceQuaternion!![1])},${"%.4f".format(referenceQuaternion!![2])},${"%.4f".format(referenceQuaternion!![3])}]")
                    Log.i(TAG, "📐 Reference orientation captured (avg of $REFERENCE_SAMPLE_COUNT samples)")
                }
                return
            }

            // Cooldown — don't trigger right after recording starts
            if (System.currentTimeMillis() < cooldownUntil) return

            val rawAngle = computeGravityTilt(referenceQuaternion!!, currentQ)
            // B: EMA 低通滤波 α=0.1（厚毛巾级，滤除减速带/坑洞瞬时尖峰）
            smoothedAngle = EMA_ALPHA * rawAngle + (1f - EMA_ALPHA) * smoothedAngle
            val threshold = getAngleThreshold()

            // ── 传感器调试日志：记录每个采样点 ──
            val isDeviating = smoothedAngle > threshold
            val stableElapsed = if (stableStartTime != 0L) System.currentTimeMillis() - stableStartTime else 0L
            val devElapsed = if (deviationStartTime != 0L) System.currentTimeMillis() - deviationStartTime else 0L
            SensorDebugLog.logSensorEvent(
                rawAngle = rawAngle,
                smoothedAngle = smoothedAngle,
                threshold = threshold,
                isDeviating = isDeviating,
                deviationElapsedMs = devElapsed,
                stableElapsedMs = stableElapsed,
                refQ = referenceQuaternion,
                curQ = currentQ,
            )

            if (smoothedAngle > threshold) {
                // D: 一旦超出阈值，重置自适应计时器
                stableStartTime = 0L

                // Screen-off gate
                if (settings.autoStopRequireScreenOff) {
                    val pm = getSystemService(POWER_SERVICE) as PowerManager
                    if (pm.isInteractive) {
                        deviationStartTime = 0L
                        return
                    }
                }

                // C: 累计偏离时间（需持续 8 秒才触发）
                if (deviationStartTime == 0L) {
                    deviationStartTime = System.currentTimeMillis()
                    SensorDebugLog.logEvent("DEVIATION_START smoothedAngle=${"%.2f".format(smoothedAngle)} threshold=$threshold")
                }

                val elapsed = System.currentTimeMillis() - deviationStartTime
                if (elapsed >= AUTO_STOP_DEVIATION_DURATION_MS && !autoStopWarningActive) {
                    SensorDebugLog.logEvent("WARNING_TRIGGERED elapsed=${elapsed}ms smoothedAngle=${"%.2f".format(smoothedAngle)}")
                    triggerAutoStopWarning()
                }
            } else {
                // Back within threshold — reset deviation timer
                if (deviationStartTime != 0L) {
                    SensorDebugLog.logEvent("DEVIATION_END smoothedAngle=${"%.2f".format(smoothedAngle)} back under threshold=$threshold")
                }
                deviationStartTime = 0L

                // ── D: 自适应参考更新（长期稳定时悄悄修正参考值，消除漂移）──
                if (smoothedAngle < STABLE_UPDATE_THRESHOLD) {
                    if (stableStartTime == 0L) {
                        stableStartTime = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - stableStartTime >= STABLE_UPDATE_DURATION_MS) {
                        // 极慢速地把参考值往当前值挪一点点（α=0.02），然后重新归一化
                        val ref = referenceQuaternion!!
                        val newRef = floatArrayOf(
                            ref[0] + ADAPTIVE_UPDATE_ALPHA * (currentQ[0] - ref[0]),
                            ref[1] + ADAPTIVE_UPDATE_ALPHA * (currentQ[1] - ref[1]),
                            ref[2] + ADAPTIVE_UPDATE_ALPHA * (currentQ[2] - ref[2]),
                            ref[3] + ADAPTIVE_UPDATE_ALPHA * (currentQ[3] - ref[3]),
                        )
                        val norm = sqrt(newRef[0] * newRef[0] + newRef[1] * newRef[1] +
                                newRef[2] * newRef[2] + newRef[3] * newRef[3])
                        referenceQuaternion = floatArrayOf(newRef[0] / norm, newRef[1] / norm,
                            newRef[2] / norm, newRef[3] / norm)
                        SensorDebugLog.logEvent("REF_ADAPTIVE_UPDATE smoothedAngle=${"%.2f".format(smoothedAngle)}")
                        stableStartTime = System.currentTimeMillis()
                    }
                } else {
                    stableStartTime = 0L
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /**
     * Compute the gravity tilt angle (degrees) between two orientations.
     *
     * Instead of full 3D rotation (which includes yaw/turning), we extract the
     * gravity direction from each quaternion and compare those vectors.
     * This ignores horizontal turns — only pitch + roll tilt is measured.
     *
     * Gravity vector from rotation quaternion [x, y, z, w]:
     *   g = [ 2(xz - yw),  2(yz + xw),  1 - 2(x² + y²) ]
     * This projects the world "up" direction into device coordinates.
     */
    private fun computeGravityTilt(q1: FloatArray, q2: FloatArray): Float {
        fun gravityVector(q: FloatArray): FloatArray {
            val x = q[0]; val y = q[1]; val z = q[2]
            val w = if (q.size >= 4) q[3] else {
                sqrt(max(0f, 1f - x*x - y*y - z*z))
            }
            return floatArrayOf(
                2 * (x*z - y*w),
                2 * (y*z + x*w),
                1 - 2 * (x*x + y*y)
            )
        }

        val g1 = gravityVector(q1)
        val g2 = gravityVector(q2)
        val dot = g1[0]*g2[0] + g1[1]*g2[1] + g1[2]*g2[2]
        val clampedDot = min(1f, max(-1f, dot))

        return Math.toDegrees(acos(clampedDot.toDouble())).toFloat()
    }

    // 重力倾斜阈值：strict=30° / standard=50° / relaxed=70°（仅俯仰+侧倾，不含水平转向）
    private fun getAngleThreshold(): Float = when (settings.autoStopSensitivity) {
        "strict"   -> 30f
        "relaxed"  -> 70f
        else       -> 50f  // "standard"
    }

    private fun triggerAutoStopWarning() {
        autoStopWarningActive = true
        cancelledAutoStop = false

        // Vibrate 500ms
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(
            VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)
        )

        // Show warning notification
        showAutoStopWarningNotification()

        // 3-second countdown
        lifecycleScope.launch {
            delay(AUTO_STOP_WARNING_MS)
            if (!cancelledAutoStop) {
                executeAutoStop()
            }
            NotificationManagerCompat.from(this@VideoRecorderService)
                .cancel(AUTO_STOP_WARNING_NOTIFICATION_ID)
            autoStopWarningActive = false
        }
    }

    private fun showAutoStopWarningNotification() {
        val cancelIntent = PendingIntent.getService(
            this,
            AUTO_STOP_CANCEL_REQUEST_CODE,
            Intent(this, VideoRecorderService::class.java).apply {
                action = ACTION_CANCEL_AUTO_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NotificationHelper.RECORDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stop)
            .setContentTitle(getString(R.string.ui_autoStop_warning_title))
            .setContentText(getString(R.string.ui_autoStop_warning_message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .addAction(
                R.drawable.ic_cancel,
                getString(R.string.ui_autoStop_warning_cancel),
                cancelIntent
            )
            .build()

        NotificationManagerCompat.from(this).notify(
            AUTO_STOP_WARNING_NOTIFICATION_ID,
            notification
        )
    }

    private fun executeAutoStop() {
        SensorDebugLog.logEvent("AUTO_STOP_EXECUTED")
        lifecycleScope.launch {
            Log.i(TAG, "🛑 Auto-stop triggered — stopping recording")
            stopRecording()
            val info = getRecordingInformation()
            dataStore.updateData { it.setLastRecording(info) }
            destroy()
            // Exit the app so the next NFC tap triggers a fresh launch
            // (which re-runs autoRecordOnAppOpen)
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            am.appTasks.forEach { it.finishAndRemoveTask() }
        }
    }

    // ────────────────────────────────────────────────────────────
    // Used to close it finally, shouldn't be called when pausing / resuming.
    // This should only be called after recording has finished.
    private suspend fun closeCamera() {
        physicalDualRecorder?.let { recorder ->
            try {
                // Physical dual streams need both MP4 files finalized before
                // the primary AAC track can be copied into the secondary file.
                recorder.stopAndMuxAudio()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping/muxing physical dual recorder", e)
                CameraDebugLog.append("⚠️ Physical dual stop/mux failed: ${e.message}")
                CameraDebugLog.flush()
            }
        }
        physicalDualRecorder = null

        runOnMain {
            runCatching {
                cameraProvider?.unbindAll()
            }
            _cameraCloserListener.complete(Unit)

            cameraProvider = null
            primary = RecordingStream(CameraPosition.SINGLE)
            secondary = null
            isDualMode = false
        }
    }

    // `resume` override not needed as `startNewCycle` is called by `IntervalRecorderService`

    private fun stopActiveRecording() {
        runCatching {
            primary.activeRecording?.stop()
        }
        runCatching {
            secondary?.activeRecording?.stop()
        }
    }

    private fun getNameForMediaFile(folder: VideoBatchesFolder = primaryCaptureFolder): String {
        // Flat naming: alibi-video_recordings-{sessionId}-{counter}.mp4
        // Lexicographic sort = chronological (sessionId is a timestamp).
        val sid = folder.sessionId ?: "00000000000000"
        return "${folder.mediaPrefix}${sid}-%03d.%s".format(
            counter, settings.videoRecorderSettings.fileExtension
        )
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun prepareVideoRecording(
        videoCapture: VideoCapture<Recorder>,
        folder: VideoBatchesFolder = primaryCaptureFolder,
    ) =
        videoCapture.output
            .let {
                if (folder.type == BatchesFolder.BatchType.CUSTOM && SUPPORTS_SAVING_VIDEOS_IN_CUSTOM_FOLDERS) {
                    it.prepareRecording(
                        this,
                        FileDescriptorOutputOptions.Builder(
                            folder.asCustomGetParcelFileDescriptor(
                                counter,
                                settings.videoRecorderSettings.fileExtension,
                                fileName = getNameForMediaFile(folder),
                            )
                        ).build()
                    )
                } else if (folder.type == BatchesFolder.BatchType.MEDIA) {
                    if (SUPPORTS_SCOPED_STORAGE) {
                        val name = getNameForMediaFile(folder)

                        it.prepareRecording(
                            this,
                            MediaStoreOutputOptions
                                .Builder(
                                    contentResolver,
                                    folder.scopedMediaContentUri,
                                )
                                .setContentValues(
                                    folder.asMediaGetScopedStorageContentValues(
                                        name
                                    )
                                )
                                .build()
                        )
                    } else {
                        val name = getNameForMediaFile(folder)

                        it.prepareRecording(
                            this,
                            FileOutputOptions
                                .Builder(folder.asMediaGetLegacyFile(name))
                                .build()
                        )
                    }
                } else {
                    it.prepareRecording(
                        this,
                        FileOutputOptions.Builder(
                            folder.asInternalGetOutputFile(getNameForMediaFile(folder)).apply {
                                createNewFile()
                            }
                        ).build()
                    )
                }
            }
            .run {
                if (enableAudio) {
                    return@run withAudioEnabled()
                }

                this
            }

    override fun getRecordingInformation(): RecordingInformation {
        val primaryFolder = primaryCaptureFolder
        val secondaryFolder = secondaryCaptureFolder
        val sessionId = activeRecordingSessionId ?: primaryFolder.sessionId
        sessionId?.let {
            // Keep the query path and metadata on the same session even if a
            // rebinding model replaced the public folder object.
            primaryFolder.sessionId = it
            secondaryFolder?.sessionId = it
        }
        val information = RecordingInformation(
            folderPath = primaryFolder.exportFolderForSettings(),
            recordingStart = recordingStart,
            maxDuration = settings.maxDuration,
            batchesAmount = primaryFolder.getBatchesForFFmpeg().size,
            fileExtension = settings.videoRecorderSettings.fileExtension,
            intervalDuration = settings.intervalDuration,
            type = RecordingInformation.Type.VIDEO,
            sessionId = sessionId,
            primaryCameraPositionName = primaryFolder.cameraPosition.name,
            secondaryFolderPath = secondaryFolder?.exportFolderForSettings(),
            secondarySessionId = secondaryFolder?.sessionId ?: sessionId,
            secondaryCameraPositionName = secondaryFolder?.cameraPosition?.name,
        )

        Log.i(
            TAG,
            "💾 Recording information: session=${information.sessionId} chunks=${information.batchesAmount} " +
                "primary=${primaryFolder.cameraPosition} " +
                "secondary=${secondaryFolder?.cameraPosition ?: "none"} " +
                "folder=${System.identityHashCode(primaryFolder)}",
        )
        return information
    }

    /**
     * Handles "Stop & Save" from the notification: stops recording, persists
     * [RecordingInformation] to DataStore, and destroys the service.
     *
     * The user can then save the recording via the "Save last recording" dialog
     * on the next app open.
     */
    override fun handleStopFromNotification() {
        lifecycleScope.launch {
            try {
                stopRecording()
                // Capture after the final chunk has finalized, otherwise the last
                // partial segment and dual audio-mux output can be omitted.
                val info = getRecordingInformation()
                Log.i(TAG, "💾 Notification save captured session=${info.sessionId}")

                // Persist first: if FFmpeg fails, the existing app-open recovery
                // flow can still locate and export every source chunk.
                dataStore.updateData { it.setLastRecording(info) }
                val fullyMerged = mergeInBackground(info)

                val isInternalStorage = info.folderPath == RECORDER_INTERNAL_SELECTED_VALUE
                if (fullyMerged && !isInternalStorage) {
                    dataStore.updateData { it.setLastRecording(null) }
                    Log.i(TAG, "💾 Notification merge completed; last recording cleared")
                } else if (fullyMerged) {
                    Log.i(
                        TAG,
                        "💾 Notification merge completed; retained recovery state " +
                            "type=${info.folderPath} immediateDelete=${settings.deleteRecordingsImmediately}",
                    )
                }
            } catch (error: Exception) {
                Log.e(TAG, "💾 Notification stop/save failed", error)
            } finally {
                destroy()
            }
        }
    }

    /**
     * Merges all video streams directly from persisted metadata. Dual camera
     * produces one output per physical stream; the streams are never combined.
     */
    private suspend fun mergeInBackground(info: RecordingInformation): Boolean {
        return withContext(Dispatchers.IO) {
            val primaryPosition = info.primaryCameraPositionName
                ?.let(CameraPosition::valueOf)
                ?: CameraPosition.SINGLE
            val primaryFolder = VideoBatchesFolder.importFromFolder(
                info.folderPath,
                this@VideoRecorderService,
                primaryPosition,
            ).also { folder ->
                folder.sessionId = info.sessionId
            }

            val secondaryPosition = info.secondaryCameraPositionName
                ?.let(CameraPosition::valueOf)
                ?: CameraPosition.SINGLE
            val secondaryFolder = info.secondarySessionId?.let { sessionId ->
                VideoBatchesFolder.importFromFolder(
                    info.secondaryFolderPath,
                    this@VideoRecorderService,
                    secondaryPosition,
                ).also { folder ->
                    folder.sessionId = sessionId
                }
            }

            val folders = listOfNotNull(primaryFolder, secondaryFolder)
            val baseName = primaryFolder.getName(info.recordingStart, info.fileExtension)
                .substringBeforeLast('.')

            data class PendingCleanup(
                val folder: VideoBatchesFolder,
                val chunkNames: List<String>,
            )

            val cleanupCandidates = mutableListOf<PendingCleanup>()
            var streamIndex = 0

            for (folder in folders) {
                val chunkNames = folder.listSessionChunkNames()
                if (chunkNames.isEmpty()) {
                    Log.w(
                        TAG,
                        "💾 Skipping empty notification merge stream=$streamIndex " +
                            "position=${folder.cameraPosition} session=${folder.sessionId}",
                    )
                    if (streamIndex == 0) {
                        Log.e(TAG, "💾 Primary video stream has no chunks to merge")
                        return@withContext false
                    }
                    streamIndex += 1
                    continue
                }

                val outputName = if (streamIndex == 0) {
                    "$baseName.${info.fileExtension}"
                } else {
                    val suffix = folder.cameraPosition.fileTag.ifBlank { "secondary" }
                    "$baseName-$suffix.${info.fileExtension}"
                }

                Log.i(
                    TAG,
                    "🎞️ Notification merging stream=$streamIndex type=${folder.type} " +
                        "session=${folder.sessionId} chunks=${chunkNames.size} " +
                        "position=${folder.cameraPosition} output=$outputName",
                )

                try {
                    val output = folder.concatenate(
                        info,
                        filenameFormat = settings.filenameFormat,
                        fileName = outputName,
                    )
                    Log.i(TAG, "🎞️ Notification merged stream=$streamIndex output=$output")
                    cleanupCandidates.add(PendingCleanup(folder, chunkNames))
                } catch (error: Exception) {
                    Log.e(
                        TAG,
                        "🎞️ Notification merge failed stream=$streamIndex; " +
                            "source chunks preserved count=${chunkNames.size}",
                        error,
                    )
                    return@withContext false
                }

                streamIndex += 1
            }

            if (!settings.deleteRecordingsImmediately) {
                Log.i(TAG, "🧹 Notification merge preserved source chunks by setting")
                return@withContext true
            }

            var deletedCount = 0
            var totalCount = 0
            cleanupCandidates.forEach { candidate ->
                candidate.folder.permanentlyDeleteRecordings =
                    settings.permanentlyDeleteRecordings
                deletedCount += candidate.folder.deleteFlatChunks(candidate.chunkNames)
                totalCount += candidate.chunkNames.size
            }
            Log.i(
                TAG,
                "🧹 Deleted merged notification source chunks " +
                    "deleted=$deletedCount/$totalCount",
            )
            true
        }
    }

    companion object {
        const val CAMERA_CLOSE_TIMEOUT = 20000L
        private const val CAMERA_OPEN_TIMEOUT_MS = 30000L
        private const val CHUNK_FINALIZE_TIMEOUT_MS = 2000L
        private const val TAG = "VideoRecorderService"

        // ── 传感器自动停止常量 ──
        const val ACTION_CANCEL_AUTO_STOP = "cancelAutoStop"
        const val AUTO_STOP_WARNING_NOTIFICATION_ID = 99
        const val AUTO_STOP_CANCEL_REQUEST_CODE = 999
        const val AUTO_STOP_COOLDOWN_MS = 10_000L           // 启动后冷却 10 秒
        const val AUTO_STOP_DEVIATION_DURATION_MS = 5_000L  // 持续偏离 5 秒触发警告（重力倾斜抗干扰强，无需 8s）
        const val AUTO_STOP_WARNING_MS = 5_000L              // 警告倒计时 5 秒
        const val EMA_ALPHA = 0.1f                           // B: 低通滤波 α=0.1（厚毛巾级过滤）
        const val REFERENCE_SAMPLE_COUNT = 15                // A: 参考四元数采样数
        const val ADAPTIVE_UPDATE_ALPHA = 0.02f              // D: 自适应参考更新速率（极慢）
        const val STABLE_UPDATE_THRESHOLD = 8f               // D: 重力倾斜 < 8° 视为稳定（重力倾斜波动更小）
        const val STABLE_UPDATE_DURATION_MS = 30_000L        // D: 稳定 30 秒后开始修正
    }

    class CameraControl(
        val camera: Camera,
        // Save state for optimistic updates
        var torchEnabled: Boolean = false,
    ) {
        fun init() {
            torchEnabled = camera.cameraInfo.torchState.value == TorchState.ON
        }

        fun enableTorch() {
            torchEnabled = true
            camera.cameraControl.enableTorch(true)
        }

        fun disableTorch() {
            torchEnabled = false
            camera.cameraControl.enableTorch(false)
        }

        fun isHardwareTorchReallyEnabled(): Boolean {
            return camera.cameraInfo.torchState.value == TorchState.ON
        }

        fun hasTorchAvailable() = camera.cameraInfo.hasFlashUnit()
    }
}
