package app.leo.alibi_cam.quickrecording

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.leo.alibi_cam.enums.RecorderState

class VideoRecordingTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()

        QuickRecordingStarter.inspectInBackground(
            applicationContext,
            QuickRecordingAction.VIDEO,
        ) { inspection ->
            updateTileFromCurrentState(inspection)
        }
    }

    override fun onClick() {
        super.onClick()

        startThroughShortcutActivity(QuickRecordingAction.VIDEO)
    }

    private fun updateTileFromCurrentState(inspection: RecorderServiceInspection) {
        val active = inspection is RecorderServiceInspection.Available &&
            (inspection.state == RecorderState.RECORDING || inspection.state == RecorderState.PAUSED)
        setTileState(if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE)
    }

    private fun setTileState(state: Int) {
        getQsTile()?.let { tile ->
            tile.state = state
            tile.updateTile()
        }
    }

    private fun startThroughShortcutActivity(action: QuickRecordingAction) {
        setTileState(Tile.STATE_INACTIVE)
        QuickRecordingEntryCallbacks.register(action) { result ->
            setTileState(result.toTileState())
        }

        if (!launchNoDisplayEntry(action)) {
            QuickRecordingEntryCallbacks.unregister(action)
            setTileState(Tile.STATE_UNAVAILABLE)
        }
    }
}
