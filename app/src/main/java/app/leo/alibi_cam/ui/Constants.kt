package app.leo.alibi_cam.ui

import android.os.Build
import androidx.compose.ui.unit.dp
import java.util.Base64

val BIG_PRIMARY_BUTTON_SIZE = 64.dp
val BIG_PRIMARY_BUTTON_MAX_WIDTH = 450.dp

val SHEET_BOTTOM_OFFSET = 24.dp
val MAX_AMPLITUDE = 20000
val SUPPORTS_DARK_MODE_NATIVELY = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

val MEDIA_SUBFOLDER_NAME = "alibi"

val SUPPORTS_SCOPED_STORAGE = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
val SUPPORTS_SAVING_VIDEOS_IN_CUSTOM_FOLDERS = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
val MEDIA_RECORDINGS_PREFIX = "alibi-recording-"
const val RECORDER_MEDIA_SELECTED_VALUE = "_'media"
const val RECORDER_INTERNAL_SELECTED_VALUE = "_'internal"

val VIDEO_RECORDING_BATCHES_SUBFOLDER_NAME = ".video_recordings"
val AUDIO_RECORDING_BATCHES_SUBFOLDER_NAME = ".audio_recordings"

const val REPO_URL = "https://github.com/pokedo0/Alibi-Cam"
const val GITHUB_ISSUES_URL = "https://github.com/pokedo0/Alibi-Cam/issues"
const val TRANSLATION_HELP_URL = "https://crowdin.com/project/alibi"

