package app.leo.alibi_cam.quickrecording

import android.Manifest
import app.leo.alibi_cam.db.AppSettings
import app.leo.alibi_cam.enums.RecorderState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickRecordingStarterTest {
    @Test
    fun `action intent routing accepts only known actions`() {
        assertEquals(
            QuickRecordingAction.AUDIO,
            QuickRecordingAction.fromIntentAction(QuickRecordingAction.AUDIO_ACTION),
        )
        assertEquals(
            QuickRecordingAction.VIDEO,
            QuickRecordingAction.fromIntentAction(QuickRecordingAction.VIDEO_ACTION),
        )
        assertFalse(
            QuickRecordingAction.fromIntentAction("unknown-action") != null,
        )
    }

    @Test
    fun `active service state ignores duplicate request`() = runBlocking {
        var started = false

        val result = QuickRecordingStarter.performStart(
            action = QuickRecordingAction.AUDIO,
            loadSettings = { AppSettings() },
            inspectServiceState = {
                RecorderServiceInspection.Available(RecorderState.RECORDING)
            },
            requiredPermissions = { emptyList() },
            startRecorder = { started = true },
            infoLogger = {},
            errorLogger = { _, _ -> },
        )

        assertEquals(QuickRecordingResult.DuplicateIgnored, result)
        assertFalse(started)
    }

    @Test
    fun `idle absent service starts through recorder flow`() = runBlocking {
        var inspectedSettings: AppSettings? = null

        val result = QuickRecordingStarter.performStart(
            action = QuickRecordingAction.VIDEO,
            loadSettings = { AppSettings() },
            inspectServiceState = { RecorderServiceInspection.Absent },
            requiredPermissions = { settings ->
                inspectedSettings = settings
                listOf(Manifest.permission.CAMERA to true)
            },
            startRecorder = { settings -> assertEquals(inspectedSettings, settings) },
            infoLogger = {},
            errorLogger = { _, _ -> },
        )

        assertTrue(result is QuickRecordingResult.Started)
    }

    @Test
    fun `settings read failure does not start recording`() = runBlocking {
        val result = QuickRecordingStarter.performStart(
            action = QuickRecordingAction.AUDIO,
            loadSettings = { error("DataStore unavailable") },
            inspectServiceState = { RecorderServiceInspection.Absent },
            requiredPermissions = { emptyList() },
            startRecorder = {},
            infoLogger = {},
            errorLogger = { _, _ -> },
        )

        assertTrue(
            result is QuickRecordingResult.Failed &&
                result.reason == QuickRecordingFailureReason.SETTINGS,
        )
    }
}
