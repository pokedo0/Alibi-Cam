package app.leo.alibi_cam.ui.components.RecorderScreen.molecules

import android.util.Log
import CameraSelectionButton
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalLensFacing
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.leo.alibi_cam.R
import app.leo.alibi_cam.ui.models.VideoRecorderModel
import app.leo.alibi_cam.ui.utils.CameraInfo

@Composable
fun CamerasSelection(
    cameras: Iterable<CameraInfo>,
    videoSettings: VideoRecorderModel,
) {
    val hasBackCamera = CameraInfo.getAnyBackCamera(cameras.toList()) != null
    val frontCamera = CameraInfo.getFrontCamera(cameras.toList())
    val isDualMode = videoSettings.settings?.videoRecorderSettings?.dualCameraEnabled == true

    // Selection state based on cameraLensMode and secondaryCameraLens
    val isUWSelected = (videoSettings.cameraID == CameraSelector.LENS_FACING_BACK && videoSettings.cameraLensMode == "ultrawide") ||
            (videoSettings.secondaryCameraLens == "ultrawide")
    val isMainSelected = (videoSettings.cameraID == CameraSelector.LENS_FACING_BACK && videoSettings.cameraLensMode != "ultrawide" && videoSettings.cameraLensMode != "telephoto") ||
            (videoSettings.secondaryCameraLens == "main")
    val isTelephotoSelected = (videoSettings.cameraID == CameraSelector.LENS_FACING_BACK && videoSettings.cameraLensMode == "telephoto") ||
            (videoSettings.secondaryCameraLens == "telephoto")
    val isFrontSelected = videoSettings.cameraID == CameraSelector.LENS_FACING_FRONT ||
            (videoSettings.secondaryCameraLens == "front")

    val hasTelephoto = cameras.any { it.lens == CameraInfo.Lens.TELEPHOTO || it.cameraId == "3" }

    Column {
        // Main back camera option
        if (hasBackCamera) {
            CameraSelectionButton(
                cameraID = CameraInfo.Lens.BACK,
                label = stringResource(R.string.ui_videoRecorder_action_start_settings_cameraLens_main_label),
                selected = isMainSelected,
                isMultiSelect = isDualMode,
                onSelected = {
                    if (isDualMode) {
                        if (isMainSelected) {
                            if (videoSettings.cameraID == CameraSelector.LENS_FACING_BACK && videoSettings.cameraLensMode != "ultrawide" && videoSettings.cameraLensMode != "telephoto") {
                                if (videoSettings.secondaryCameraLens == "front") {
                                    videoSettings.cameraID = CameraSelector.LENS_FACING_FRONT
                                    videoSettings.cameraLensMode = null
                                } else if (videoSettings.secondaryCameraLens == "ultrawide") {
                                    videoSettings.cameraID = CameraSelector.LENS_FACING_BACK
                                    videoSettings.cameraLensMode = "ultrawide"
                                } else if (videoSettings.secondaryCameraLens == "telephoto") {
                                    videoSettings.cameraID = CameraSelector.LENS_FACING_BACK
                                    videoSettings.cameraLensMode = "telephoto"
                                }
                                videoSettings.secondaryCameraLens = null
                            } else if (videoSettings.secondaryCameraLens == "main") {
                                videoSettings.secondaryCameraLens = null
                            }
                        } else {
                            if (videoSettings.secondaryCameraLens == null) {
                                videoSettings.secondaryCameraLens = "main"
                            }
                        }
                    } else {
                        videoSettings.cameraID = CameraSelector.LENS_FACING_BACK
                        videoSettings.cameraLensMode = "main"
                    }
                },
            )
        }

        // Ultra-wide option
        if (hasBackCamera) {
            CameraSelectionButton(
                cameraID = CameraInfo.Lens.ULTRA_WIDE,
                label = stringResource(R.string.ui_videoRecorder_action_start_settings_cameraLens_ultrawide_label),
                selected = isUWSelected,
                isMultiSelect = isDualMode,
                onSelected = {
                    if (isDualMode) {
                        if (isUWSelected) {
                            if (videoSettings.cameraLensMode == "ultrawide") {
                                if (videoSettings.secondaryCameraLens == "front") {
                                    videoSettings.cameraID = CameraSelector.LENS_FACING_FRONT
                                    videoSettings.cameraLensMode = null
                                } else {
                                    videoSettings.cameraID = CameraSelector.LENS_FACING_BACK
                                    videoSettings.cameraLensMode = "main"
                                }
                                videoSettings.secondaryCameraLens = null
                            } else if (videoSettings.secondaryCameraLens == "ultrawide") {
                                videoSettings.secondaryCameraLens = null
                            }
                        } else {
                            if (videoSettings.secondaryCameraLens == null) {
                                videoSettings.secondaryCameraLens = "ultrawide"
                            }
                        }
                    } else {
                        videoSettings.cameraID = CameraSelector.LENS_FACING_BACK
                        videoSettings.cameraLensMode = "ultrawide"
                    }
                },
            )
        }

        // Telephoto option (if available)
        if (hasTelephoto || hasBackCamera) {
            CameraSelectionButton(
                cameraID = CameraInfo.Lens.TELEPHOTO,
                label = stringResource(R.string.ui_videoRecorder_action_start_settings_cameraLens_telephoto_label),
                selected = isTelephotoSelected,
                isMultiSelect = isDualMode,
                onSelected = {
                    if (isDualMode) {
                        if (isTelephotoSelected) {
                            if (videoSettings.cameraLensMode == "telephoto") {
                                if (videoSettings.secondaryCameraLens == "front") {
                                    videoSettings.cameraID = CameraSelector.LENS_FACING_FRONT
                                    videoSettings.cameraLensMode = null
                                } else {
                                    videoSettings.cameraID = CameraSelector.LENS_FACING_BACK
                                    videoSettings.cameraLensMode = "main"
                                }
                                videoSettings.secondaryCameraLens = null
                            } else if (videoSettings.secondaryCameraLens == "telephoto") {
                                videoSettings.secondaryCameraLens = null
                            }
                        } else {
                            if (videoSettings.secondaryCameraLens == null) {
                                videoSettings.secondaryCameraLens = "telephoto"
                            }
                        }
                    } else {
                        videoSettings.cameraID = CameraSelector.LENS_FACING_BACK
                        videoSettings.cameraLensMode = "telephoto"
                    }
                },
            )
        }

        // Front camera option
        if (frontCamera != null) {
            CameraSelectionButton(
                cameraID = CameraInfo.Lens.FRONT,
                label = stringResource(R.string.ui_videoRecorder_action_start_settings_cameraLens_front_label),
                selected = isFrontSelected,
                isMultiSelect = isDualMode,
                onSelected = {
                    if (isDualMode) {
                        if (isFrontSelected) {
                            if (videoSettings.cameraID == CameraSelector.LENS_FACING_FRONT) {
                                if (videoSettings.secondaryCameraLens == "ultrawide") {
                                    videoSettings.cameraID = CameraSelector.LENS_FACING_BACK
                                    videoSettings.cameraLensMode = "ultrawide"
                                } else {
                                    videoSettings.cameraID = CameraSelector.LENS_FACING_BACK
                                    videoSettings.cameraLensMode = "main"
                                }
                                videoSettings.secondaryCameraLens = null
                            } else if (videoSettings.secondaryCameraLens == "front") {
                                videoSettings.secondaryCameraLens = null
                            }
                        } else {
                            if (videoSettings.secondaryCameraLens == null) {
                                videoSettings.secondaryCameraLens = "front"
                            }
                        }
                    } else {
                        videoSettings.cameraID = CameraSelector.LENS_FACING_FRONT
                        videoSettings.cameraLensMode = null
                    }
                },
            )
        }

        // External cameras (if any)
        cameras.forEach { camera ->
            if (camera.lensFacing == CameraSelector.LENS_FACING_EXTERNAL) {
                CameraSelectionButton(
                    cameraID = camera.lens,
                    selected = videoSettings.cameraID == CameraSelector.LENS_FACING_EXTERNAL,
                    isMultiSelect = isDualMode,
                    onSelected = {
                        videoSettings.cameraID = CameraSelector.LENS_FACING_EXTERNAL
                        videoSettings.cameraLensMode = null
                    },
                    label = stringResource(
                        R.string.ui_videoRecorder_action_start_settings_cameraLens_external_label
                    ),
                )
            }
        }
    }
}
