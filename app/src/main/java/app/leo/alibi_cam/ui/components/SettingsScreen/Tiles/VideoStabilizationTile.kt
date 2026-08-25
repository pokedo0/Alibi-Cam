package app.leo.alibi_cam.ui.components.SettingsScreen.Tiles

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import app.leo.alibi_cam.ui.utils.VideoStabilizationSupport
import kotlinx.coroutines.launch

@Composable
fun VideoStabilizationTile(
    settings: AppSettings,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dataStore = context.dataStore

    var inspection by remember {
        mutableStateOf<VideoStabilizationSupport.Inspection?>(null)
    }

    LaunchedEffect(settings.videoRecorderSettings) {
        inspection = runCatching {
            VideoStabilizationSupport.inspect(context, settings.videoRecorderSettings)
        }.getOrNull()
        if (inspection == null) {
            android.util.Log.w("VideoStabilizationTile", "🛡 Camera stabilization inspection failed")
        }
    }

    val supported = inspection?.supported == true

    SettingsTile(
        title = stringResource(R.string.ui_settings_option_videoStabilization_title),
        description = stringResource(
            R.string.ui_settings_option_videoStabilization_description,
        ),
        tertiaryLine = {
            Text(
                text = stringResource(
                    when {
                        inspection == null ->
                            R.string.ui_settings_option_videoStabilization_checking
                        supported && settings.videoRecorderSettings.dualCameraEnabled ->
                            R.string.ui_settings_option_videoStabilization_dualSupported
                        supported ->
                            R.string.ui_settings_option_videoStabilization_singleSupported
                        else ->
                            R.string.ui_settings_option_videoStabilization_unsupported
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (supported) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        },
        leading = {
            Icon(
                Icons.Default.Videocam,
                contentDescription = null,
            )
        },
        trailing = {
            Switch(
                checked = settings.videoRecorderSettings.videoStabilizationEnabled && supported,
                enabled = supported,
                onCheckedChange = { enabled ->
                    scope.launch {
                        runCatching {
                            dataStore.updateData {
                                it.setVideoRecorderSettings(
                                    it.videoRecorderSettings.setVideoStabilizationEnabled(enabled)
                                )
                            }
                        }.onFailure { error ->
                            android.util.Log.e(
                                "VideoStabilizationTile",
                                "Failed to update video stabilization setting",
                                error,
                            )
                        }
                    }
                },
            )
        },
    )
}
