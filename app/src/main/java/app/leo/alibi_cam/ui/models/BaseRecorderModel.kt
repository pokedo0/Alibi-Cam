package app.leo.alibi_cam.ui.models

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import app.leo.alibi_cam.db.AppSettings
import app.leo.alibi_cam.enums.RecorderState
import app.leo.alibi_cam.helpers.BatchesFolder
import app.leo.alibi_cam.services.IntervalRecorderService
import app.leo.alibi_cam.services.RecorderNotificationHelper
import app.leo.alibi_cam.services.RecorderService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

internal fun shouldAutoRecordOnAppOpen(
    serviceState: RecorderState?,
    settingEnabled: Boolean,
): Boolean {
    // The process-wide registry reports absence explicitly as `null`.
    return settingEnabled &&
        (serviceState == null ||
            (serviceState != RecorderState.RECORDING &&
                serviceState != RecorderState.PAUSED))
}

internal fun shouldIgnoreDuplicateStartRequest(serviceState: RecorderState?): Boolean {
    return serviceState == RecorderState.RECORDING ||
        serviceState == RecorderState.PAUSED
}

abstract class BaseRecorderModel<I, B : BatchesFolder, T : IntervalRecorderService<I, B>> :
    ViewModel() {
    protected abstract val intentClass: Class<T>

    private companion object {
        const val TAG = "BaseRecorderModel"
        const val SERVICE_BIND_TIMEOUT_MS = 3_000L
    }

    var recorderState by mutableStateOf(RecorderState.IDLE)
        protected set
    var recordingTime by mutableLongStateOf(0)
        protected set

    open val isInRecording: Boolean
        get() = recorderService != null || shouldIgnoreDuplicateStartRequest(
            RecorderService.activeState(intentClass)
        )

    open val isCurrentlyActivelyRecording
        get() = recorderState === RecorderState.RECORDING

    val isPaused: Boolean
        get() = recorderState === RecorderState.PAUSED

    val progress: Float
        get() = recordingTime.toFloat() / (recorderService!!.settings.maxDuration / 1000)

    var recorderService by mutableStateOf<T?>(null)
        protected set

    val recordingStart
        get() = recorderService!!.recordingStart

    // If `isSavingAsOldRecording` is true, the user is saving an old recording,
    // thus the service is not running and thus doesn't need to be stopped or destroyed
    var onRecordingSave: (cleanupOldFiles: Boolean) -> CompletableDeferred<Unit> = {
        throw NotImplementedError("onRecordingSave not implemented")
    }
    var onRecordingStart: () -> Unit = {}
    var onError: () -> Unit = {}
    var onBatchesFolderNotAccessible: () -> Unit = {}
    abstract var batchesFolder: B?

    private var notificationDetails: RecorderNotificationHelper.NotificationDetails? = null

    var settings: AppSettings? = null
        protected set

    private var boundContext: Context? = null

    // Background starters own a short-lived model. Release its binder after the Service
    // starts; the foreground Service lifetime keeps the recording alive.
    var releasesBindingAfterStart: Boolean = false

    protected abstract fun onServiceConnected(service: T)

    suspend fun awaitInitialServiceState(
        timeoutMs: Long = SERVICE_BIND_TIMEOUT_MS,
    ): RecorderState? {
        recorderService?.let { service ->
            return service.state
        }

        return withTimeoutOrNull(timeoutMs) {
            snapshotFlow { recorderService }
                .filterNotNull()
                .first()
                .state
        }
    }

    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val recorder = (service as RecorderService.RecorderBinder).getService() as T
            Log.i(
                TAG,
                "Bound to ${intentClass.simpleName}; state=${recorder.state}, " +
                    "time=${recorder.recordingTime}",
            )
            attachRecorderService(recorder)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            Log.i(TAG, "Disconnected unexpectedly from ${intentClass.simpleName}")
            // `onServiceDisconnected` is called when the connection is unexpectedly lost,
            // so we need to make sure to manually call `reset` to clean up in other places
            reset()
        }
    }

    private fun attachRecorderService(recorder: T) {
        recorderService = recorder

        // Init variables from us to the service
        recorder.onStateChange = { state ->
            recorderState = state
        }
        recorder.onRecordingTimeChange = { time ->
            recordingTime = time
        }
        recorder.onError = {
            onError()
        }
        recorder.onBatchesFolderNotAccessible = {
            onBatchesFolderNotAccessible()
        }

        val serviceIsActive =
            recorder.state == RecorderState.RECORDING || recorder.state == RecorderState.PAUSED

        if (serviceIsActive && recorder.batchesFolder != null) {
            // A shortcut-started service can still be recording when the UI model
            // reconnects. The capture pipeline owns the folder at that point.
            if (batchesFolder !== recorder.batchesFolder) {
                Log.i(
                    TAG,
                    "Adopting authoritative batches folder from active " +
                        "${intentClass.simpleName}; model=${System.identityHashCode(batchesFolder)}, " +
                        "service=${System.identityHashCode(recorder.batchesFolder)}",
                )
            }
            batchesFolder = recorder.batchesFolder
        } else if (batchesFolder != null) {
            recorder.batchesFolder = batchesFolder!!
        } else {
            batchesFolder = recorder.batchesFolder
        }

        val serviceSettingsInitialized = recorder.isSettingsInitialized
        if (!serviceSettingsInitialized) {
            Log.i(
                TAG,
                "Connected to ${intentClass.simpleName} before settings initialization",
            )
        }

        if (serviceIsActive && serviceSettingsInitialized) {
            // Keep settings and the captured chunks on the same recording session.
            if (settings !== recorder.settings) {
                Log.i(TAG, "Adopting authoritative settings from active ${intentClass.simpleName}")
            }
            settings = recorder.settings
        } else if (settings != null) {
            // If `settings` is set, it means we started the recording, so it should be
            // properly set on the service
            recorder.settings = settings!!
        } else if (serviceSettingsInitialized) {
            settings = recorder.settings
        } else {
            Log.i(TAG, "Connected to ${intentClass.simpleName} before settings initialization")
        }

        // Rest should be initialized from the child class
        onServiceConnected(recorder)

        if (releasesBindingAfterStart) {
            Log.i(TAG, "Releasing background starter binding after recording start")
            runCatching<Unit> {
                boundContext?.unbindService(connection)
            }.onFailure { error ->
                Log.w(TAG, "Failed to release background starter binding", error)
            }
            releasesBindingAfterStart = false
            boundContext = null
        }
    }

    fun restoreActiveService(): Boolean {
        val active = RecorderService.activeService(intentClass) as? T
        val state = active?.state

        if (active != null && (state == RecorderState.RECORDING || state == RecorderState.PAUSED)) {
            Log.d(
                TAG,
                "Restoring ${intentClass.simpleName}; state=$state, time=${active.recordingTime}",
            )
            attachRecorderService(active)
            return true
        }

        if (active == null && recorderService != null) {
            Log.i(TAG, "Active ${intentClass.simpleName} missing; resetting restored model")
            reset()
        }

        return false
    }

    open fun reset() {
        recorderService = null
        recorderState = RecorderState.IDLE
        recordingTime = 0
    }

    protected open fun handleIntent(intent: Intent) = intent

    private fun stopOldServices(context: Context) {
        runCatching {
            context.unbindService(connection)
        }

        val intent = Intent(context, intentClass)
        runCatching {
            context.stopService(intent)
        }
    }

    // If override, call `super` AFTER setting the settings
    open fun startRecording(
        context: Context,
        settings: AppSettings,
    ) {
        val activeServiceState = RecorderService.activeState(intentClass)
        if (shouldIgnoreDuplicateStartRequest(activeServiceState)) {
            Log.i(
                TAG,
                "Ignoring duplicate start request; ${intentClass.simpleName} " +
                    "is $activeServiceState",
            )
            return
        }

        boundContext = context
        this.settings = settings

        // Clean up
        stopOldServices(context)

        notificationDetails = settings.notificationSettings.let {
            if (it == null)
                null
            else
                RecorderNotificationHelper.NotificationDetails.fromNotificationSettings(
                    context,
                    it
                )
        }

        val intent = Intent(context, intentClass).apply {
            action = "init"

            if (notificationDetails != null) {
                putExtra(
                    "notificationDetails",
                    Json.encodeToString(
                        RecorderNotificationHelper.NotificationDetails.serializer(),
                        notificationDetails!!,
                    ),
                )
            }
        }.let(::handleIntent)
        ContextCompat.startForegroundService(context, intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    suspend fun stopRecording(context: Context) {
        recorderService!!.stopRecording()
    }

    fun pauseRecording() {
        recorderService!!.pauseRecording()
    }

    fun resumeRecording() {
        recorderService!!.resumeRecording()
    }

    fun destroyService(context: Context) {
        recorderService!!.destroy()

        stopOldServices(context)
        reset()
    }

    // Bind functions used to manually bind to the service if the app
    // is closed and reopened for example
    fun bindToService(context: Context) {
        bindToService(context, Context.BIND_AUTO_CREATE)
    }

    fun bindToService(context: Context, flags: Int) {
        if (restoreActiveService()) {
            return
        }

        val bound = runCatching {
            context.bindService(Intent(context, intentClass), connection, flags)
        }.getOrElse { error ->
            Log.e(TAG, "Failed to bind ${intentClass.simpleName}", error)
            false
        }
        Log.i(TAG, "Requested bind to ${intentClass.simpleName}; flags=$flags, bound=$bound")
    }

    fun unbindFromService(context: Context) {
        runCatching {
            context.unbindService(connection)
        }
    }
}
