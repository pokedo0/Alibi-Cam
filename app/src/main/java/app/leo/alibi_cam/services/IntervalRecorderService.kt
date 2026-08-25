package app.leo.alibi_cam.services

import android.util.Log
import app.leo.alibi_cam.db.AppSettings
import app.leo.alibi_cam.helpers.BatchesFolder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

abstract class IntervalRecorderService<I, B : BatchesFolder> :
    RecorderService() {
    protected var counter = 0L

    // Tracks the index of the currently locked file
    private var lockedIndex: Long? = null

    lateinit var settings: AppSettings

    val isSettingsInitialized: Boolean
        get() = ::settings.isInitialized

    private var cycleTimer: ScheduledExecutorService? = null

    abstract var batchesFolder: B

    var onBatchesFolderNotAccessible: () -> Unit = {}

    abstract fun getRecordingInformation(): I

    // When saving the recording, the files should be locked.
    // This prevents the service from deleting the currently available files, so that
    // they can be safely used to save the recording.
    // Once finished, make sure to unlock the files using `unlockFiles`.
    fun lockFiles() {
        lockedIndex = counter
    }

    // Unlocks and deletes the files that were locked using `lockFiles`.
    fun unlockFiles(cleanupFiles: Boolean = false) {
        if (cleanupFiles) {
            batchesFolder.deleteRecordings(0..<lockedIndex!!)
        }

        lockedIndex = null
    }

    // Make overrideable
    open fun startNewCycle() {
        counter += 1
        deleteOldRecordings()
    }

    private fun createTimer() {
        cycleTimer?.let { oldTimer ->
            Log.w("IntervalRecorderService", "Replacing an existing recording-cycle timer")
            oldTimer.shutdownNow()
        }

        cycleTimer = Executors.newSingleThreadScheduledExecutor().also {
            // A long camera-open or segment-restart operation must shift the
            // next boundary. A fixed rate would keep firing during setup and
            // truncate the newly started segment (especially physical dual).
            it.scheduleWithFixedDelay(
                ::startNewCycle,
                0,
                settings.intervalDuration,
                TimeUnit.MILLISECONDS
            )
        }
    }

    override fun start() {
        super.start()

        batchesFolder.initFolders()

        if (!batchesFolder.checkIfFolderIsAccessible()) {
            onBatchesFolderNotAccessible()

            throw AvoidErrorDialogError()
        }

        createTimer()
    }

    override fun pause() {
        super.pause()
        cycleTimer?.shutdown()
    }

    override fun resume() {
        super.resume()
        createTimer()
    }

    override suspend fun stop() {
        cycleTimer?.shutdown()
        batchesFolder.cleanup()
        super.stop()
    }

    // clearAllRecordings() removed — each startNewCycle session should NOT wipe
    // prior recordings. Rolling-window pruning is handled by deleteOldRecordings().

    protected open fun deleteOldRecordings() {
        // 同步永久删除标志到 BatchesFolder
        batchesFolder.permanentlyDeleteRecordings = settings.permanentlyDeleteRecordings

        val timeMultiplier = settings.maxDuration / settings.intervalDuration
        val earliestCounter = Math.max(counter - timeMultiplier, lockedIndex ?: 0)

        if (earliestCounter <= 0) {
            return
        }

        batchesFolder.deleteRecordings(0..earliestCounter)
    }
}
