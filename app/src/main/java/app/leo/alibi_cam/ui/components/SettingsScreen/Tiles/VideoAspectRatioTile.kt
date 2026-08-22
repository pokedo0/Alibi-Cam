package app.leo.alibi_cam.ui.components.SettingsScreen.Tiles

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.leo.alibi_cam.R
import app.leo.alibi_cam.dataStore
import app.leo.alibi_cam.db.AppSettings
import app.leo.alibi_cam.db.VideoRecorderSettings
import app.leo.alibi_cam.ui.components.atoms.SettingsTile
import app.leo.alibi_cam.ui.utils.IconResource
import com.maxkeppeker.sheets.core.models.base.Header
import com.maxkeppeker.sheets.core.models.base.IconSource
import com.maxkeppeker.sheets.core.models.base.rememberUseCaseState
import com.maxkeppeler.sheets.list.ListDialog
import com.maxkeppeler.sheets.list.models.ListOption
import com.maxkeppeler.sheets.list.models.ListSelection
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoAspectRatioTile(
    settings: AppSettings,
) {
    val scope = rememberCoroutineScope()
    val showDialog = rememberUseCaseState()
    val dataStore = LocalContext.current.dataStore

    val options = listOf(
        "4:3" to stringResource(R.string.ui_settings_value_videoAspectRatio_4_3),
        "16:9" to stringResource(R.string.ui_settings_value_videoAspectRatio_16_9),
    )

    val currentValue = settings.videoRecorderSettings.videoAspectRatio ?: "4:3"
    val currentLabel = options.firstOrNull { it.first == currentValue }?.second
        ?: "4:3"

    fun updateValue(value: String?) {
        scope.launch {
            dataStore.updateData {
                it.setVideoRecorderSettings(
                    it.videoRecorderSettings.setVideoAspectRatio(value)
                )
            }
        }
    }

    ListDialog(
        state = showDialog,
        header = Header.Default(
            title = stringResource(R.string.ui_settings_option_videoAspectRatioTile_title),
            icon = IconSource(
                painter = IconResource.fromImageVector(Icons.Default.AspectRatio)
                    .asPainterResource(),
                contentDescription = null,
            ),
        ),
        selection = ListSelection.Single(
            showRadioButtons = true,
            options = options.map { (value, label) ->
                ListOption(
                    titleText = label,
                    selected = (settings.videoRecorderSettings.videoAspectRatio ?: "4:3") == value,
                )
            }
        ) { index, _ ->
            val selectedValue = options[index].first
            // Store null for default 4:3, "16:9" for 16:9
            updateValue(if (selectedValue == "4:3") null else selectedValue)
        },
    )
    SettingsTile(
        title = stringResource(R.string.ui_settings_option_videoAspectRatioTile_title),
        leading = {
            Icon(
                Icons.Default.AspectRatio,
                contentDescription = null,
            )
        },
        trailing = {
            Button(
                onClick = showDialog::show,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(currentLabel)
            }
        },
    )
}
