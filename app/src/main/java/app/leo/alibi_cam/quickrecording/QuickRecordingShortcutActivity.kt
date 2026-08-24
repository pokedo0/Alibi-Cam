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
            // Stay resumed behind this invisible window until the recorder Service is
            // ready. OEM camera services often throttle a freshly finished NoDisplay
            // trampoline even when its foreground Service has already been requested.
            QuickRecordingStarter.startInBackground(applicationContext, action) { result ->
                Log.i(
                    "QuickRecordingShortcut",
                    "Shortcut entry finished; result=${result.javaClass.simpleName}",
                )
                finish()
            }
        }
    }
}
