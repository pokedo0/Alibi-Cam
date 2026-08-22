package app.leo.alibi_cam.ui.components.SettingsScreen.Tiles

import android.os.Build
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.leo.alibi_cam.R
import app.leo.alibi_cam.dataStore
import app.leo.alibi_cam.db.AppSettings
import app.leo.alibi_cam.ui.components.atoms.SettingsTile
import app.leo.alibi_cam.ui.utils.DualCameraSupport
import kotlinx.coroutines.launch

/**
 * Settings tile for enabling/disabling Dual Camera Recording.
 *
 * Only shown on Android 11+ devices that support ConcurrentCamera.
 * When enabled, the Camera Lens setting switches to multi-select mode
 * (up to 2 cameras).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DualCameraTile(
    settings: AppSettings,
) {
    // Hard cutoff: CameraX ConcurrentCamera requires Android 11+
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dataStore = context.dataStore

    var isSupported by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        isSupported = runCatching {
            DualCameraSupport.isSupported(context)
        }.getOrDefault(false)
        Log.i("DualCameraTile", "🎬 Dual camera support: $isSupported")
    }

    // Hide the tile entirely on unsupported devices
    if (isSupported != true) return

    fun updateValue(enabled: Boolean) {
        scope.launch {
            runCatching {
                dataStore.updateData {
                    it.setVideoRecorderSettings(
                        it.videoRecorderSettings.setDualCameraEnabled(enabled)
                    )
                }
            }.onFailure {
                Log.e("DualCameraTile", "Failed to update dual camera setting", it)
            }
        }
    }

    SettingsTile(
        title = stringResource(R.string.ui_settings_option_dualCamera_title),
        description = stringResource(R.string.ui_settings_option_dualCamera_description),
        leading = {
            Icon(
                Icons.Default.Cameraswitch,
                contentDescription = null,
            )
        },
        trailing = {
            Switch(
                checked = settings.videoRecorderSettings.dualCameraEnabled,
                onCheckedChange = ::updateValue,
            )
        },
    )
}
