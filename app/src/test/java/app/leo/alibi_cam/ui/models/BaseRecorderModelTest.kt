package app.leo.alibi_cam.ui.models

import app.leo.alibi_cam.enums.RecorderState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseRecorderModelTest {
    @Test
    fun `app open auto record waits for an idle service binding`() {
        assertTrue(shouldAutoRecordOnAppOpen(RecorderState.IDLE, true))
        assertTrue(shouldAutoRecordOnAppOpen(RecorderState.STOPPED, true))
    }

    @Test
    fun `active background recording is not restarted on app open`() {
        assertFalse(shouldAutoRecordOnAppOpen(RecorderState.RECORDING, true))
        assertFalse(shouldAutoRecordOnAppOpen(RecorderState.PAUSED, true))

        assertTrue(shouldIgnoreDuplicateStartRequest(RecorderState.RECORDING))
        assertTrue(shouldIgnoreDuplicateStartRequest(RecorderState.PAUSED))
    }

    @Test
    fun `idle stopped and absent services allow a direct start`() {
        assertFalse(shouldIgnoreDuplicateStartRequest(RecorderState.IDLE))
        assertFalse(shouldIgnoreDuplicateStartRequest(RecorderState.STOPPED))
        assertFalse(shouldIgnoreDuplicateStartRequest(null))
    }

    @Test
    fun `absent service or disabled setting controls auto record`() {
        assertTrue(shouldAutoRecordOnAppOpen(null, true))
        assertFalse(shouldAutoRecordOnAppOpen(RecorderState.IDLE, false))
    }
}
