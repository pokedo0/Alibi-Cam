package app.leo.alibi_cam.quickrecording

import android.os.Bundle
import android.util.Log

class QuickRecordingShortcutActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = QuickRecordingAction.fromIntentAction(intent?.action)
        if (action == null) {
            Log.w("QuickRecordingShortcut", "Shortcut entry received an unknown action")
            finish()
        } else {
            val result = QuickRecordingStarter.startAndDispatch(applicationContext, action)
            Log.i(
                "QuickRecordingShortcut",
                "Shortcut entry dispatched; result=${result.javaClass.simpleName}",
            )
            QuickRecordingEntryCallbacks.dispatch(action, result)
            // Theme.NoDisplay must finish before onResume() completes. The recorder
            // Service has already received the foreground start request above.
            finish()
        }
    }
}
