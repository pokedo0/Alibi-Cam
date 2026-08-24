package app.leo.alibi_cam.quickrecording

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.leo.alibi_cam.enums.RecorderState

class AudioRecordingTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()

        QuickRecordingStarter.inspectInBackground(
            applicationContext,
            QuickRecordingAction.AUDIO,
        ) { inspection ->
            updateTileFromCurrentState(inspection)
        }
    }

    override fun onClick() {
        super.onClick()

        setTileState(Tile.STATE_ACTIVE)
        QuickRecordingStarter.startInBackground(
            applicationContext,
            QuickRecordingAction.AUDIO,
        ) { result ->
            setTileState(result.toTileState())
        }
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
}
