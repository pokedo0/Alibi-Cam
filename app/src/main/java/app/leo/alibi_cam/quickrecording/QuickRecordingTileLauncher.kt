package app.leo.alibi_cam.quickrecording

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

internal object QuickRecordingEntryCallbacks {
    private val callbacks = ConcurrentHashMap<QuickRecordingAction, (QuickRecordingResult) -> Unit>()

    fun register(action: QuickRecordingAction, callback: (QuickRecordingResult) -> Unit) {
        callbacks[action] = callback
    }

    fun unregister(action: QuickRecordingAction) {
        callbacks.remove(action)
    }

    fun dispatch(action: QuickRecordingAction, result: QuickRecordingResult) {
        callbacks.remove(action)?.invoke(result)
    }
}

internal fun TileService.launchNoDisplayEntry(action: QuickRecordingAction): Boolean {
    val intent = Intent(applicationContext, QuickRecordingShortcutActivity::class.java)
        .setAction(when (action) {
            QuickRecordingAction.AUDIO -> QuickRecordingAction.AUDIO_ACTION
            QuickRecordingAction.VIDEO -> QuickRecordingAction.VIDEO_ACTION
        })
    val pendingIntent = PendingIntent.getActivity(
        this,
        action.ordinal,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
        Log.i("QuickRecordingTile", "Launched NoDisplay entry for $action")
        true
    } catch (error: Throwable) {
        Log.e("QuickRecordingTile", "Failed to launch NoDisplay entry for $action", error)
        false
    }
}
