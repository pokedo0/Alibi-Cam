package app.leo.alibi_cam.quickrecording

import android.service.quicksettings.Tile

internal fun QuickRecordingResult.toTileState(): Int = when (this) {
    is QuickRecordingResult.Started -> Tile.STATE_ACTIVE
    is QuickRecordingResult.DuplicateIgnored -> Tile.STATE_ACTIVE
    is QuickRecordingResult.Failed -> when (reason) {
        QuickRecordingFailureReason.SERVICE, QuickRecordingFailureReason.UNEXPECTED ->
            Tile.STATE_UNAVAILABLE

        else -> Tile.STATE_INACTIVE
    }
}
