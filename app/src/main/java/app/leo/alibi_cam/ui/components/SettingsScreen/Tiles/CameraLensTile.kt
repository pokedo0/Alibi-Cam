package app.leo.alibi_cam.ui.components.SettingsScreen.Tiles

import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
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
fun CameraLensTile(
    settings: AppSettings,
) {
    val scope = rememberCoroutineScope()
    val showDialog = rememberUseCaseState()
    val dataStore = LocalContext.current.dataStore

    val isDualMode = settings.videoRecorderSettings.dualCameraEnabled

    val options = listOf(
        "auto" to stringResource(R.string.ui_settings_value_cameraLens_auto),
        "main" to stringResource(R.string.ui_settings_value_cameraLens_main),
        "ultrawide" to stringResource(R.string.ui_settings_value_cameraLens_ultrawide),
        "telephoto" to stringResource(R.string.ui_settings_value_cameraLens_telephoto),
        "front" to stringResource(R.string.ui_settings_value_cameraLens_front),
    )

    val currentPrimary = settings.videoRecorderSettings.cameraLens ?: "auto"
    val currentSecondary = settings.videoRecorderSettings.secondaryCameraLens

    // Display label
    val currentLabel = if (isDualMode && currentSecondary != null) {
        val primaryLabel = options.firstOrNull { it.first == currentPrimary }?.second ?: currentPrimary
        val secondaryLabel = options.firstOrNull { it.first == currentSecondary }?.second ?: currentSecondary
        "$primaryLabel + $secondaryLabel"
    } else {
        options.firstOrNull { it.first == currentPrimary }?.second
            ?: stringResource(R.string.ui_settings_value_auto_label)
    }

    fun updateSingleValue(value: String?) {
        scope.launch {
            dataStore.updateData {
                it.setVideoRecorderSettings(
                    it.videoRecorderSettings.setCameraLens(value)
                )
            }
        }
    }

    fun updateDualValues(primaryIndex: Int, secondaryIndex: Int?) {
        scope.launch {
            val primaryValue = options[primaryIndex].first
            val secondaryValue = secondaryIndex?.let { options[it].first }
            Log.i("CameraLensTile", "🎬 Dual camera: primary=$primaryValue, secondary=$secondaryValue")
            dataStore.updateData {
                it.setVideoRecorderSettings(
                    it.videoRecorderSettings
                        .setCameraLens(if (primaryValue == "auto") null else primaryValue)
                        .setSecondaryCameraLens(secondaryValue)
                )
            }
        }
    }

    if (isDualMode) {
        // ── Dual Camera Mode: Multi-select (max 2) ──
        // Compute which indices are currently selected
        val selectedIndices = mutableListOf<Int>()
        val primaryIdx = options.indexOfFirst { it.first == currentPrimary }
        if (primaryIdx >= 0) selectedIndices.add(primaryIdx)
        if (currentSecondary != null) {
            val secondaryIdx = options.indexOfFirst { it.first == currentSecondary }
            if (secondaryIdx >= 0 && secondaryIdx != primaryIdx) {
                selectedIndices.add(secondaryIdx)
            }
        }

        ListDialog(
            state = showDialog,
            header = Header.Default(
                title = stringResource(R.string.ui_settings_option_cameraLensTile_title) +
                    " (" + stringResource(R.string.ui_settings_value_cameraLens_selectTwo) + ")",
                icon = IconSource(
                    painter = IconResource.fromImageVector(Icons.Default.CameraAlt)
                        .asPainterResource(),
                    contentDescription = null,
                ),
            ),
            selection = ListSelection.Multiple(
                showCheckBoxes = true,
                maxChoices = 2,
                minChoices = 1,
                options = options.mapIndexed { index, (_, label) ->
                    ListOption(
                        titleText = label,
                        selected = index in selectedIndices,
                    )
                }
            ) { indices, _ ->
                val sortedIndices = indices.sorted()
                val primary = sortedIndices.firstOrNull() ?: 0
                val secondary = sortedIndices.getOrNull(1)
                updateDualValues(primary, secondary)
            },
        )
    } else {
        // ── Single Camera Mode: Radio button selection ──
        ListDialog(
            state = showDialog,
            header = Header.Default(
                title = stringResource(R.string.ui_settings_option_cameraLensTile_title),
                icon = IconSource(
                    painter = IconResource.fromImageVector(Icons.Default.CameraAlt)
                        .asPainterResource(),
                    contentDescription = null,
                ),
            ),
            selection = ListSelection.Single(
                showRadioButtons = true,
                options = options.map { (value, label) ->
                    ListOption(
                        titleText = label,
                        selected = (settings.videoRecorderSettings.cameraLens ?: "auto") == value,
                    )
                }
            ) { index, _ ->
                val selectedValue = options[index].first
                updateSingleValue(if (selectedValue == "auto") null else selectedValue)
            },
        )
    }

    SettingsTile(
        title = stringResource(R.string.ui_settings_option_cameraLensTile_title),
        description = if (isDualMode) stringResource(R.string.ui_settings_value_cameraLens_selectTwo) else null,
        leading = {
            Icon(
                Icons.Default.CameraAlt,
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
