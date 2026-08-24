package app.leo.alibi_cam.quickrecording

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.Manifest
import app.leo.alibi_cam.dataStore
import app.leo.alibi_cam.db.AppSettings
import app.leo.alibi_cam.enums.RecorderState
import app.leo.alibi_cam.services.AudioRecorderService
import app.leo.alibi_cam.services.RecorderService
import app.leo.alibi_cam.services.VideoRecorderService
import app.leo.alibi_cam.ui.models.AudioRecorderModel
import app.leo.alibi_cam.ui.models.VideoRecorderModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

enum class QuickRecordingAction {
    AUDIO,
    VIDEO;

    companion object {
        const val AUDIO_ACTION = "app.leo.alibi_cam.action.START_AUDIO_RECORDING"
        const val VIDEO_ACTION = "app.leo.alibi_cam.action.START_VIDEO_RECORDING"

        fun fromIntentAction(intentAction: String?): QuickRecordingAction? = when (intentAction) {
            AUDIO_ACTION -> AUDIO
            VIDEO_ACTION -> VIDEO
            else -> null
        }
    }
}

sealed class QuickRecordingResult {
    data object Started : QuickRecordingResult()
    data object DuplicateIgnored : QuickRecordingResult()
    data class Failed(
        val reason: QuickRecordingFailureReason,
        val cause: Throwable? = null,
    ) : QuickRecordingResult()
}

enum class QuickRecordingFailureReason {
    PERMISSIONS,
    SETTINGS,
    SERVICE,
    UNEXPECTED,
}

sealed class RecorderServiceInspection {
    data class Available(val state: RecorderState) : RecorderServiceInspection()
    data object Absent : RecorderServiceInspection()
    data class Unavailable(val cause: Throwable? = null) : RecorderServiceInspection()
}

object QuickRecordingStarter {
    private const val TAG = "QuickRecordingStarter"
    private const val START_CONFIRMATION_TIMEOUT_MS = 10_000L
    private const val START_DISPATCH_WAIT_TIMEOUT_MS = 500L
    private val requestMutex = Mutex()
    private val entryPointScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun startInBackground(
        context: Context,
        action: QuickRecordingAction,
        onComplete: ((QuickRecordingResult) -> Unit)? = null,
    ) {
        entryPointScope.launch {
            val result = start(context, action)
            onComplete?.invoke(result)
        }
    }

    /**
     * Dispatch a foreground-service start before a [android.app.Activity] finishes.
     * Android requires Theme.NoDisplay activities to complete finish() before resume;
     * waiting for camera readiness there would either crash or keep a visible window.
     */
    fun startAndDispatch(context: Context, action: QuickRecordingAction): QuickRecordingResult {
        val appContext = context.applicationContext

        if (!requestMutex.tryLock()) {
            Log.i(TAG, "Ignoring $action shortcut start while another quick-start request is active")
            return QuickRecordingResult.Failed(QuickRecordingFailureReason.SERVICE)
        }

        var waitOnConfirmation = false
        return try {
            runBlocking {
                try {
                    val target = target(action)
                    val result = performStart(
                        action = action,
                        loadSettings = { appContext.dataStore.data.first() },
                        inspectServiceState = {
                            inspectRecorderService(appContext, target.serviceClass)
                        },
                        requiredPermissions = { settings ->
                            target.requiredPermissions(appContext, settings).map { permission ->
                                permission to hasGranted(appContext, permission)
                            }
                        },
                        startRecorder = { settings -> target.start(appContext, settings) },
                        infoLogger = { message -> Log.i(TAG, message) },
                        errorLogger = { message, error -> Log.e(TAG, message, error) },
                    )

                    if (result is QuickRecordingResult.Started) {
                        if (action == QuickRecordingAction.VIDEO) {
                            val dispatchStartedAtMs = SystemClock.elapsedRealtime()
                            val dispatched = pumpMainThreadUntil(
                                START_DISPATCH_WAIT_TIMEOUT_MS,
                            ) {
                                RecorderService.activeService(target.serviceClass)
                                    ?.let(target::isStartRequestDispatched) == true
                            }

                            Log.i(
                                TAG,
                                "Shortcut camera dispatch ${if (dispatched) "confirmed" else "timed out"} " +
                                    "before NoDisplay exit in " +
                                    "${SystemClock.elapsedRealtime() - dispatchStartedAtMs}ms",
                            )
                        }

                        waitOnConfirmation = true
                        entryPointScope.launch {
                            try {
                                if (!confirmStarted(appContext, target)) {
                                    Log.e(
                                        TAG,
                                        "Recorder pipeline did not become ready after $action start request",
                                    )
                                }
                            } finally {
                                // Keep duplicate requests out until the first capture
                                // pipeline has either become ready or failed to appear.
                                requestMutex.unlock()
                            }
                        }
                    }

                    result
                } catch (error: Throwable) {
                    Log.e(TAG, "Unexpected failure starting $action recording", error)
                    QuickRecordingResult.Failed(QuickRecordingFailureReason.UNEXPECTED, error)
                }
            }
        } finally {
            if (!waitOnConfirmation) {
                requestMutex.unlock()
            }
        }
    }

    internal suspend fun <T> locked(block: suspend () -> T): T {
        return requestMutex.withLock { block() }
    }

    fun inspectInBackground(
        context: Context,
        action: QuickRecordingAction,
        onComplete: (RecorderServiceInspection) -> Unit,
    ) {
        entryPointScope.launch {
            onComplete(inspect(context, action))
        }
    }

    suspend fun start(context: Context, action: QuickRecordingAction): QuickRecordingResult {
        val appContext = context.applicationContext

        // Shortcut and tile callbacks can overlap. Keep inspection plus start atomic.
        return requestMutex.withLock {
            try {
                val target = target(action)
                val result = performStart(
                    action = action,
                    loadSettings = { appContext.dataStore.data.first() },
                    inspectServiceState = { inspectRecorderService(appContext, target.serviceClass) },
                    requiredPermissions = { settings ->
                        target.requiredPermissions(appContext, settings).map { permission ->
                            permission to hasGranted(appContext, permission)
                        }
                    },
                    startRecorder = { settings -> target.start(appContext, settings) },
                    infoLogger = { message -> Log.i(TAG, message) },
                    errorLogger = { message, error -> Log.e(TAG, message, error) },
                )

                if (result is QuickRecordingResult.Started) {
                    val confirmed = confirmStarted(appContext, target)

                    if (!confirmed) {
                        Log.e(TAG, "Recorder pipeline did not become ready after $action start request")

                        return@withLock QuickRecordingResult.Failed(
                            QuickRecordingFailureReason.SERVICE,
                        )
                    }

                        Log.i(
                            TAG,
                            "Confirmed $action start with capture pipeline ready; " +
                                "target=${target.serviceClass.simpleName}",
                        )
                }

                result
            } catch (error: Throwable) {
                Log.e(TAG, "Unexpected failure starting $action recording", error)
                QuickRecordingResult.Failed(QuickRecordingFailureReason.UNEXPECTED, error)
            }
        }
    }

    suspend fun inspect(context: Context, action: QuickRecordingAction): RecorderServiceInspection {
        val target = target(action)
        return inspectRecorderService(context.applicationContext, target.serviceClass)
    }

    internal suspend fun performStart(
        action: QuickRecordingAction,
        loadSettings: suspend () -> AppSettings,
        inspectServiceState: suspend () -> RecorderServiceInspection,
        requiredPermissions: suspend (AppSettings) -> List<Pair<String, Boolean>>,
        startRecorder: (AppSettings) -> Unit,
        infoLogger: (String) -> Unit,
        errorLogger: (String, Throwable?) -> Unit,
    ): QuickRecordingResult {
        val settings = try {
            loadSettings()
        } catch (error: Throwable) {
            errorLogger("Failed to read DataStore settings before $action recording", error)
            return QuickRecordingResult.Failed(QuickRecordingFailureReason.SETTINGS, error)
        }

        val permissions = try {
            requiredPermissions(settings)
        } catch (error: Throwable) {
            errorLogger("Failed to check permissions for $action recording", error)
            return QuickRecordingResult.Failed(QuickRecordingFailureReason.PERMISSIONS, error)
        }
        val missingPermissions = permissions.filterNot { it.second }.map { it.first }
        if (missingPermissions.isNotEmpty()) {
            errorLogger("Cannot start $action recording; missing runtime permissions", null)
            return QuickRecordingResult.Failed(QuickRecordingFailureReason.PERMISSIONS)
        }

        val serviceState = try {
            inspectServiceState()
        } catch (error: Throwable) {
            errorLogger("Failed to inspect recorder Service for $action", error)
            return QuickRecordingResult.Failed(QuickRecordingFailureReason.SERVICE, error)
        }

        if (serviceState is RecorderServiceInspection.Available &&
            (serviceState.state == RecorderState.RECORDING || serviceState.state == RecorderState.PAUSED)
        ) {
            infoLogger("Ignoring duplicate $action request; Service is ${serviceState.state}")
            return QuickRecordingResult.DuplicateIgnored
        }

        if (serviceState is RecorderServiceInspection.Unavailable) {
            errorLogger("Recorder Service unavailable before $action start", serviceState.cause)
            return QuickRecordingResult.Failed(QuickRecordingFailureReason.SERVICE, serviceState.cause)
        }

        return try {
            infoLogger("Starting $action recording from DataStore settings")
            startRecorder(settings)
            infoLogger("Accepted $action start request for target Service")
            QuickRecordingResult.Started
        } catch (error: Throwable) {
            errorLogger("Failed to start $action recorder model", error)
            QuickRecordingResult.Failed(QuickRecordingFailureReason.SERVICE, error)
        }
    }

    /**
     * Let queued Service lifecycle messages run while the invisible shortcut
     * Activity is still launching. Some OEM HALs inspect process visibility when
     * the first CameraManager.openCamera binder call arrives; waiting for that
     * submission (not camera readiness) keeps it out of the finished-trampoline gap.
     */
    private fun pumpMainThreadUntil(
        timeoutMs: Long,
        condition: () -> Boolean,
    ): Boolean {
        val looper = Looper.myLooper() ?: return condition()
        if (condition()) return true

        val handler = Handler(looper)
        val deadlineMs = SystemClock.elapsedRealtime() + timeoutMs
        // Never quit the process-main Looper: it would also unwind Android's outer
        // loop. A private throwable unwinds only this nested pump.
        val pumpExit = StartDispatchPumpExit()
        val checker = object : Runnable {
            override fun run() {
                if (condition() || SystemClock.elapsedRealtime() >= deadlineMs) {
                    throw pumpExit
                } else {
                    handler.postDelayed(this, 5)
                }
            }
        }

        handler.postDelayed(checker, 1)
        try {
            Looper.loop()
        } catch (error: StartDispatchPumpExit) {
            if (error !== pumpExit) throw error
        } finally {
            handler.removeCallbacks(checker)
        }

        return condition()
    }

    private class StartDispatchPumpExit : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private suspend fun confirmStarted(
        context: Context,
        target: QuickRecordingTarget,
    ): Boolean {
        var confirmed = false
        val waitStartedAtMs = android.os.SystemClock.elapsedRealtime()

        withTimeoutOrNull(START_CONFIRMATION_TIMEOUT_MS) {
            while (!confirmed) {
                val service = RecorderService.activeService(target.serviceClass)
                if (service != null && target.isPipelineReady(service)) {
                    confirmed = true
                } else {
                    delay(50)
                }
            }
        }

        if (confirmed) {
            Log.i(
                TAG,
                "Capture pipeline became ready in " +
                    "${android.os.SystemClock.elapsedRealtime() - waitStartedAtMs}ms",
            )
        }

        return confirmed
    }

    private suspend fun inspectRecorderService(
        context: Context,
        serviceClass: Class<out RecorderService>,
    ): RecorderServiceInspection {
        val state = try {
            RecorderService.activeState(serviceClass)
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to inspect active recorder Service", error)
            return RecorderServiceInspection.Unavailable(error)
        }

        return state?.let(RecorderServiceInspection::Available) ?: RecorderServiceInspection.Absent
    }

    private fun hasGranted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun target(action: QuickRecordingAction) = when (action) {
        QuickRecordingAction.AUDIO -> AudioTarget
        QuickRecordingAction.VIDEO -> VideoTarget
    }
}

private interface QuickRecordingTarget {
    val serviceClass: Class<out RecorderService>

    fun isPipelineReady(service: RecorderService): Boolean
    fun isStartRequestDispatched(service: RecorderService): Boolean

    fun requiredPermissions(context: Context, settings: AppSettings): List<String>
    fun start(context: Context, settings: AppSettings)
}

private object AudioTarget : QuickRecordingTarget {
    override val serviceClass = AudioRecorderService::class.java

    override fun isPipelineReady(service: RecorderService): Boolean =
        service.state == RecorderState.RECORDING || service.state == RecorderState.PAUSED

    override fun isStartRequestDispatched(service: RecorderService): Boolean =
        isPipelineReady(service)

    override fun requiredPermissions(context: Context, settings: AppSettings): List<String> {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
            return listOf("android.hardware.microphone")
        }

        return buildList {
            add(Manifest.permission.RECORD_AUDIO)

            if (settings.requiresExternalStoragePermission(context)) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    override fun start(context: Context, settings: AppSettings) {
        AudioRecorderModel().apply {
            releasesBindingAfterStart = true
        }.startRecording(context, settings)
    }
}

private object VideoTarget : QuickRecordingTarget {
    override val serviceClass = VideoRecorderService::class.java

    override fun isPipelineReady(service: RecorderService): Boolean =
        service is VideoRecorderService && service.isCapturePipelineReady

    override fun isStartRequestDispatched(service: RecorderService): Boolean =
        service is VideoRecorderService &&
            (service.isCaptureStartRequested || service.isCapturePipelineReady)

    override fun requiredPermissions(context: Context, settings: AppSettings): List<String> {
        val packageManager = context.packageManager

        if (
            !packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) &&
            !packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)
        ) {
            return listOf("android.hardware.camera")
        }

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
            return listOf("android.hardware.microphone")
        }

        return buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)

            if (settings.requiresExternalStoragePermission(context)) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    override fun start(context: Context, settings: AppSettings) {
        VideoRecorderModel().apply {
            releasesBindingAfterStart = true
        }.startRecording(context, settings)
    }
}
