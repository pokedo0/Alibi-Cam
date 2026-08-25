package app.leo.alibi_cam.ui.components.SettingsScreen.Tiles

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.leo.alibi_cam.BuildConfig
import app.leo.alibi_cam.R
import app.leo.alibi_cam.dataStore
import app.leo.alibi_cam.db.AppSettings
import app.leo.alibi_cam.helpers.UpdateHelper
import app.leo.alibi_cam.helpers.UpdateInfo
import kotlinx.coroutines.launch

/**
 * 设置页"检查更新"按钮（手动检查，不限频率）
 *
 * @param settings 当前设置
 * @param onUpdateAvailable 有更新时的回调
 * @param onNoUpdate 无更新时的回调
 * @param onCheckFailed 检查失败时的回调
 */
@Composable
fun CheckUpdateTile(
    settings: AppSettings,
    onUpdateAvailable: (UpdateInfo, suspend () -> Unit) -> Unit,
    onNoUpdate: () -> Unit,
    onCheckFailed: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val dataStore = context.dataStore
    val label = stringResource(R.string.ui_settings_option_checkUpdate_title)

    Row(
        modifier = Modifier
            .padding(horizontal = 32.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .semantics { contentDescription = label }
            .clickable {
                scope.launch {
                    val info = UpdateHelper.checkForUpdate(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
                    when {
                        info != null -> {
                            onUpdateAvailable(info) {
                                scope.launch {
                                    dataStore.updateData {
                                        it.setDismissedUpdateVersionCode(info.versionCode)
                                    }
                                }
                            }
                        }
                        else -> {
                            onNoUpdate()
                        }
                    }
                }
            }
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.SystemUpdateAlt,
                contentDescription = null,
            )
            Text(
                text = stringResource(
                    R.string.ui_settings_option_checkUpdate_description,
                    BuildConfig.VERSION_NAME
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
            )
        }
    }
}
