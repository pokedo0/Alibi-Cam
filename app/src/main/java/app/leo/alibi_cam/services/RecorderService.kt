package app.leo.alibi_cam.services

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Intent
import android.util.Log
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.leo.alibi_cam.NotificationHelper
import app.leo.alibi_cam.enums.RecorderState
import app.leo.alibi_cam.ui.utils.PermissionHelper
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit


abstract class RecorderService : LifecycleService() {
    private val binder = RecorderBinder()

    private var isPaused: Boolean = false
    lateinit var recordingStart: LocalDateTime
        private set
    private lateinit var recordingTimeTimer: ScheduledExecutorService
    private var recordingTimeTimerStarted = false
    private var notificationDetails: RecorderNotificationHelper.NotificationDetails? = null

    var state = RecorderState.IDLE
        private set

    companion object {
        private const val TAG = "RecorderService"

        private val activeServices =
            ConcurrentHashMap<Class<out RecorderService>, RecorderService>()

        fun activeState(serviceClass: Class<out RecorderService>): RecorderState? {
            return activeServices[serviceClass]?.state
        }

        fun activeService(serviceClass: Class<out RecorderService>): RecorderService? {
            return activeServices[serviceClass]
        }
    }

    var onStateChange: ((RecorderState) -> Unit)? = null
    var onError: () -> Unit = {}
    var onRecordingTimeChange: ((Long) -> Unit)? = null

    var recordingTime = 0L
        private set

    protected open fun start() {
        if (!shouldDelayRecordingTimeUntilReady()) {
            startRecordingTimeTimerIfNeeded()
        }
    }

    protected open fun shouldDelayRecordingTimeUntilReady(): Boolean = false

    protected open fun pause() {
        isPaused = true

        stopRecordingTimeTimer()
    }

    protected open fun resume() {
        startRecordingTimeTimerIfNeeded()
    }

    protected open suspend fun stop() {
        stopRecordingTimeTimer()
    }

    protected abstract fun startForegroundService()

    fun startRecording() {
        recordingStart = LocalDateTime.now()

        try {
            startForegroundService()
        } catch (error: RuntimeException) {
            Log.e(TAG, "Failed to promote ${javaClass.simpleName} to foreground", error)
            state = RecorderState.STOPPED
            onError()
            destroy()
            return
        }

        changeState(RecorderState.RECORDING)

        try {
            start()
        } catch (error: RuntimeException) {
            error.printStackTrace()

            if (error !is AvoidErrorDialogError) {
                onError()
            }
        }
    }

    suspend fun stopRecording() {
        changeState(RecorderState.STOPPED)
        stop()
    }

    fun pauseRecording() {
        changeState(RecorderState.PAUSED)
    }

    fun resumeRecording() {
        changeState(RecorderState.RECORDING)
    }

    fun destroy() {
        NotificationManagerCompat.from(this)
            .cancel(NotificationHelper.RECORDER_CHANNEL_NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        activeServices[javaClass] = this
        android.util.Log.d("RecorderService", "Registered ${javaClass.simpleName}; state=$state")
    }

    override fun onDestroy() {
        activeServices.remove(javaClass, this)
        android.util.Log.d("RecorderService", "Unregistered ${javaClass.simpleName}; state=$state")
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "init" -> {
                notificationDetails = intent.getStringExtra("notificationDetails")?.let {
                    Json.decodeFromString(
                        RecorderNotificationHelper.NotificationDetails.serializer(),
                        it
                    )
                }

                // Promote the process before a no-display shortcut trampoline finishes.
                // Waiting for the recorder model's binder callback leaves a short
                // background gap that some OEM camera HALs treat as a cold request.
                try {
                    Log.i(TAG, "Early foreground promotion for ${javaClass.simpleName}")
                    startForegroundService()
                } catch (error: Exception) {
                    Log.e(TAG, "Early foreground promotion failed", error)
                    state = RecorderState.STOPPED
                    destroy()
                }
            }

            "changeState" -> {
                val newState = intent.getStringExtra("newState")?.let {
                    RecorderState.valueOf(it)
                } ?: RecorderState.STOPPED
                changeState(newState)
            }

            "stopRecording" -> {
                handleStopFromNotification()
            }
        }

        return super.onStartCommand(intent, flags, startId)
    }

    inner class RecorderBinder : Binder() {
        fun getService(): RecorderService = this@RecorderService
    }

    private fun createRecordingTimeTimer() {
        recordingTimeTimerStarted = true
        recordingTimeTimer = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleAtFixedRate(
                {
                    recordingTime += 1
                    onRecordingTimeChange?.invoke(recordingTime)
                },
                0,
                1,
                TimeUnit.SECONDS
            )
        }
    }

    protected fun startRecordingTimeTimerIfNeeded() {
        if (recordingTimeTimerStarted) return

        Log.i(TAG, "⏱️ Starting recording-time timer after recorder readiness")
        createRecordingTimeTimer()
    }

    protected fun stopRecordingTimeTimer() {
        if (!recordingTimeTimerStarted) return

        recordingTimeTimer.shutdown()
        recordingTimeTimerStarted = false
    }

    // Used to change the state of the service
    // will internally call start() / pause() / resume() / stop()
    // Immediately after creating the service make sure to call `changeState(RecorderState.RECORDING)`
    @SuppressLint("MissingPermission")
    fun changeState(newState: RecorderState) {
        if (state == newState) {
            return
        }

        state = newState
        when (newState) {
            RecorderState.RECORDING -> {
                if (isPaused) {
                    resume()
                    isPaused = false
                }
                // `start` is handled by `startRecording`
            }

            RecorderState.PAUSED -> pause()

            else -> {}
        }

        // Update notification
        if (
            arrayOf(
                RecorderState.RECORDING,
                RecorderState.PAUSED
            ).contains(newState) &&
            PermissionHelper.hasGranted(this, android.Manifest.permission.POST_NOTIFICATIONS)
        ) {
            val notification = buildNotification()
            NotificationManagerCompat.from(this).notify(
                NotificationHelper.RECORDER_CHANNEL_NOTIFICATION_ID,
                notification
            )
        }

        onStateChange?.invoke(newState)
    }

    protected fun getNotificationHelper(): RecorderNotificationHelper {
        return RecorderNotificationHelper(this, notificationDetails)
    }

    private fun buildNotification(): Notification {
        val notificationHelper = getNotificationHelper()

        return when (state) {
            RecorderState.RECORDING -> {
                notificationHelper.buildRecordingNotification(recordingTime)
            }

            RecorderState.PAUSED -> {
                notificationHelper.buildPausedNotification(recordingStart)
            }

            else -> {
                throw IllegalStateException("Notification can't be built in state $state")
            }
        }
    }


    /**
     * Called when the user taps "Stop & Save" in the notification.
     *
     * Default implementation stops recording and destroys the service.
     * Subclasses that need to persist recording info before destroying
     * (e.g. [VideoRecorderService]) should override this.
     */
    protected open fun handleStopFromNotification() {
        lifecycleScope.launch {
            stopRecording()
            destroy()
        }
    }

    // Throw this error if you show a dialog yourself.
    // This will prevent the service from showing their generic error dialog.
    class AvoidErrorDialogError : RuntimeException()
}
