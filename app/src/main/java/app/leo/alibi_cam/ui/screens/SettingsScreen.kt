package app.leo.alibi_cam.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.leo.alibi_cam.BuildConfig
import app.leo.alibi_cam.R
import app.leo.alibi_cam.dataStore
import app.leo.alibi_cam.helpers.UpdateHelper
import app.leo.alibi_cam.helpers.UpdateInfo
import app.leo.alibi_cam.ui.SUPPORTS_DARK_MODE_NATIVELY
import app.leo.alibi_cam.ui.components.SettingsScreen.Tiles.AboutTile
import app.leo.alibi_cam.ui.components.SettingsScreen.Tiles.AudioRecorderEncoderTile
import app.leo.alibi_cam.ui.components.SettingsScreen.Tiles.CheckUpdateTile
import app.leo.alibi_cam.ui.components.SettingsScreen.Tiles.AudioRecorderOutputFormatTile
import app.leo.alibi_cam.ui.components.SettingsScreen.Tiles.AudioRecorderSamplingRateTile
import app.leo.alibi_cam.ui.components.SettingsScreen.Tiles.AudioRecorderShowAllMicrophonesTile
import app.leo.alibi_cam.ui.components.SettingsScreen.Tiles.CustomNotificationTile
import app.leo.alibi_cam.ui.components.SettingsScreen.Tiles.DeleteRecordingsImmediatelyTile
import app.leo.alibi_cam.ui.components.SettingsScreen.Tiles.DividerTitle
import app.leo.alibi_cam.ui.components.SettingsScreen.Tiles.EnableAppLockTile
import app.leo.alibi_cam.ui.components.SettingsScreen.Tiles.ImportExport
import app.leo.alibi_cam.ui.components.SettingsScreen.Tiles.IntervalDurationTile
import app.leo.alibi_cam.ui.components.SettingsScreen.Tiles.PermanentlyDeleteRecordingsTile
import app.leo.alibi_cam.ui.components.SettingsScreen.Tiles.MaxDurationTile
import app.leo.alibi_cam.ui.components.SettingsScreen.Tiles.SaveFolderTile
import app.leo.alibi_cam.ui.components.SettingsScreen.Tiles.CameraLensTile
import app.leo.alibi_cam.ui.components.SettingsScreen.Tiles.DualCameraTile
import app.leo.alibi_cam.ui.components.SettingsScreen.Tiles.VideoAspectRatioTile
import app.leo.alibi_cam.ui.components.SettingsScreen.Tiles.AutoRecordOnAppOpenTile
import app.leo.alibi_cam.ui.components.SettingsScreen.Tiles.AutoStopSensorTile
import app.leo.alibi_cam.ui.components.SettingsScreen.Tiles.VideoRecorderBitrateTile
import app.leo.alibi_cam.ui.components.SettingsScreen.Tiles.VideoRecorderFrameRateTile
import app.leo.alibi_cam.ui.components.SettingsScreen.Tiles.VideoRecorderQualityTile
import app.leo.alibi_cam.ui.components.SettingsScreen.atoms.InAppLanguagePicker
import app.leo.alibi_cam.ui.components.SettingsScreen.atoms.ThemeSelector
import app.leo.alibi_cam.ui.components.atoms.GlobalSwitch
import app.leo.alibi_cam.ui.components.atoms.MessageBox
import app.leo.alibi_cam.ui.components.atoms.MessageType
import app.leo.alibi_cam.ui.effects.rememberSettings
import app.leo.alibi_cam.ui.models.AudioRecorderModel
import app.leo.alibi_cam.ui.models.VideoRecorderModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackNavigate: () -> Unit,
    onNavigateToCustomRecordingNotifications: () -> Unit,
    onNavigateToAboutScreen: () -> Unit,
    audioRecorder: AudioRecorderModel,
    videoRecorder: VideoRecorderModel,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingUpdateInfo by remember { mutableStateOf<UpdateInfo?>(null) }  // 更新对话框状态
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState()
    )

    Scaffold(
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                snackbar = {
                    Snackbar(
                        snackbarData = it,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        actionColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        actionContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        dismissActionContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            )
        },
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(stringResource(R.string.ui_settings_title))
                },
                navigationIcon = {
                    IconButton(onClick = onBackNavigate) {
                        val label = stringResource(R.string.goBack)
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = label,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val scope = rememberCoroutineScope()
            val context = LocalContext.current
            val dataStore = context.dataStore
            val settings = rememberSettings()

            // Show alert
            if (audioRecorder.isInRecording || videoRecorder.isInRecording) {
                Box(
                    modifier = Modifier
                        .padding(16.dp)
                ) {
                    MessageBox(
                        type = MessageType.WARNING,
                        title = stringResource(R.string.ui_settings_hint_recordingActive_title),
                        message = stringResource(R.string.ui_settings_hint_recordingActive_message),
                    )
                }
            }
            if (!SUPPORTS_DARK_MODE_NATIVELY) {
                ThemeSelector()
            }
            MaxDurationTile(settings = settings)
            IntervalDurationTile(settings = settings)
            PermanentlyDeleteRecordingsTile(settings = settings)
            InAppLanguagePicker()
            AutoRecordOnAppOpenTile(settings = settings)
            AutoStopSensorTile(settings = settings)
            DeleteRecordingsImmediatelyTile(settings = settings)
            CustomNotificationTile(onNavigateToCustomRecordingNotifications, settings = settings)
            EnableAppLockTile(settings = settings)
            SaveFolderTile(
                settings = settings,
                snackbarHostState = snackbarHostState,
            )
            GlobalSwitch(
                label = stringResource(R.string.ui_settings_advancedSettings_label),
                checked = settings.showAdvancedSettings,
                onCheckedChange = {
                    scope.launch {
                        dataStore.updateData {
                            it.setShowAdvancedSettings(it.showAdvancedSettings.not())
                        }
                    }
                }
            )
            AnimatedVisibility(visible = settings.showAdvancedSettings) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(32.dp),
                ) {
                    Column {
                        DividerTitle(
                            title = stringResource(R.string.ui_settings_sections_audio_title),
                            description = stringResource(R.string.ui_settings_sections_audio_description),
                        )
                        AudioRecorderShowAllMicrophonesTile(settings = settings)
                        AudioRecorderSamplingRateTile(settings = settings)
                        AudioRecorderEncoderTile(
                            snackbarHostState = snackbarHostState,
                            settings = settings
                        )
                        AudioRecorderOutputFormatTile(settings = settings)

                        DividerTitle(
                            title = stringResource(R.string.ui_settings_sections_video_title),
                            description = stringResource(R.string.ui_settings_sections_video_description),
                        )
                        VideoRecorderQualityTile(settings = settings)
                        VideoRecorderBitrateTile(settings = settings)
                        VideoRecorderFrameRateTile(settings = settings)
                        DualCameraTile(settings = settings)
                        CameraLensTile(settings = settings)
                        VideoAspectRatioTile(settings = settings)
                    }
                    HorizontalDivider(
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                    )
                    ImportExport(snackbarHostState = snackbarHostState)
                }
            }
            CheckUpdateTile(
                settings = settings,
                onUpdateAvailable = { info, dismissAction ->
                    pendingUpdateInfo = info
                },
                onNoUpdate = {
                    scope.launch {
                        snackbarHostState.showSnackbar(message = context.getString(R.string.ui_update_snackbar_no_update))
                    }
                },
                onCheckFailed = {
                    scope.launch {
                        snackbarHostState.showSnackbar(message = context.getString(R.string.ui_update_snackbar_check_failed))
                    }
                },
            )
            AboutTile(onNavigateToAboutScreen)

            // ── 更新对话框 ──
            pendingUpdateInfo?.let { info ->
                val uriHandler = LocalUriHandler.current
                AlertDialog(
                    onDismissRequest = {
                        scope.launch {
                            dataStore.updateData {
                                it.setDismissedUpdateVersionCode(info.versionCode)
                            }
                        }
                        pendingUpdateInfo = null
                    },
                    icon = {
                        Icon(
                            Icons.Default.SystemUpdateAlt,
                            contentDescription = null,
                        )
                    },
                    title = {
                        Text(stringResource(R.string.ui_update_dialog_title))
                    },
                    text = {
                        Text(
                            stringResource(
                                R.string.ui_update_dialog_message,
                                info.versionName,
                                info.message
                            )
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                uriHandler.openUri(info.downloadUrl)
                                scope.launch {
                                    dataStore.updateData {
                                        it.setDismissedUpdateVersionCode(info.versionCode)
                                    }
                                }
                                pendingUpdateInfo = null
                            }
                        ) {
                            Text(stringResource(R.string.ui_update_dialog_download))
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    dataStore.updateData {
                                        it.setDismissedUpdateVersionCode(info.versionCode)
                                    }
                                }
                                pendingUpdateInfo = null
                            }
                        ) {
                            Text(stringResource(R.string.ui_update_dialog_dismiss))
                        }
                    }
                )
            }
        }
    }
}
