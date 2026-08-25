package app.leo.alibi_cam.ui.utils

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import app.leo.alibi_cam.db.VideoRecorderSettings

object VideoStabilizationSupport {
    private const val TAG = "VideoStabilization"

    data class Capability(
        val electronic: Boolean,
        val optical: Boolean,
    ) {
        val any: Boolean
            get() = electronic || optical
    }

    data class Target(
        val cameraId: String,
        val label: String,
        val capability: Capability,
    )

    data class Inspection(
        val isDualMode: Boolean,
        val targets: List<Target>,
    ) {
        val common: Capability
            get() = Capability(
                electronic = targets.isNotEmpty() && targets.all { it.capability.electronic },
                optical = targets.isNotEmpty() && targets.all { it.capability.optical },
            )

        val supported: Boolean
            // A mixed dual pair is usable when either stream can be stabilized.
            get() = targets.any { it.capability.any }
    }

    suspend fun inspect(context: Context, settings: VideoRecorderSettings): Inspection {
        val manager = cameraManager(context)
        val targets = if (manager == null) {
            emptyList()
        } else {
            resolveInspectionTargetIds(context, settings).map { id ->
                val capability = readCapability(manager, id)
                Log.i(TAG, "🛡 Support id=$id eis=${capability.electronic} ois=${capability.optical}")
                Target(id, id, capability)
            }
        }
        val inspection = Inspection(settings.dualCameraEnabled, targets)
        Log.i(
            TAG,
            "🛡 Inspected mode=${if (inspection.isDualMode) "dual" else "single"} " +
                "targets=${targets.size} supported=${inspection.supported} " +
                "anySupported=${targets.any { it.capability.any }} " +
                "commonEis=${inspection.common.electronic} commonOis=${inspection.common.optical}",
        )
        return inspection
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun configureVideoCapture(
        context: Context,
        builder: VideoCapture.Builder<Recorder>,
        targetIds: List<String>,
        enabled: Boolean,
        sourceLabel: String,
    ): Boolean {
        val manager = cameraManager(context) ?: return false
        val capabilities = targetIds.map { it to readCapability(manager, it) }
        val common = Capability(
            electronic = capabilities.isNotEmpty() && capabilities.all { it.second.electronic },
            optical = capabilities.isNotEmpty() && capabilities.all { it.second.optical },
        )

        val useOis = enabled && common.optical
        val useEis = enabled && !common.optical && common.electronic
        val mode = when {
            useOis -> "ois-only"
            useEis -> "eis-only"
            else -> "none"
        }

        Log.i(
            TAG,
            "🛡 CameraX request stream=$sourceLabel mode=$mode enabled=$enabled targets=$targetIds " +
                "commonEis=${common.electronic} commonOis=${common.optical} " +
                capabilities.joinToString(prefix = "details=[", postfix = "]") { (id, capability) ->
                    "$id:eis=${capability.electronic},ois=${capability.optical}"
                },
        )

        if (!useOis && !useEis) return false

        var completedFrames = 0
        val captureCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                completedFrames++
                if (completedFrames % 120 == 1) {
                    logEffective(TAG, "CameraX/$sourceLabel", result)
                }
            }
        }

        Camera2Interop.Extender(builder).apply {
            if (useOis) {
                setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
                )
                setCaptureRequestOption(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON,
                )
            }
            if (useEis) {
                setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON,
                )
            }
            setSessionCaptureCallback(captureCallback)
        }
        return true
    }

    fun readCapability(manager: CameraManager, cameraId: String): Capability {
        val characteristics = runCatching {
            manager.getCameraCharacteristics(cameraId)
        }.getOrElse {
            Log.w(TAG, "🛡 Failed to query stabilization support for camera $cameraId", it)
            return Capability(electronic = false, optical = false)
        }

        val electronicModes = characteristics.get(
            CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
        )
        val opticalModes = characteristics.get(
            CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
        )

        return Capability(
            electronic = electronicModes?.contains(
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            ) == true,
            optical = opticalModes?.contains(
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
            ) == true,
        )
    }

    fun logEffective(tag: String, source: String, result: CaptureResult) {
        val electronic = result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
        val optical = result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
        Log.i(
            tag,
            "🛡 Effective stabilization source=$source eis=${describeMode(electronic)} " +
                "ois=${describeMode(optical)}",
        )
    }

    fun logPhysicalEffective(
        tag: String,
        result: TotalCaptureResult,
        primaryId: String,
        secondaryId: String?,
        requestedModes: Map<String, String> = emptyMap(),
    ) {
        val physicalResults = runCatching {
            result.physicalCameraResults
        }.getOrNull()

        listOfNotNull(primaryId, secondaryId).forEach { id ->
            val physical = physicalResults?.get(id)
            if (physical == null) {
                Log.w(tag, "🛡 Effective stabilization unavailable physical=$id")
            } else {
                val electronic = physical.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
                val optical = physical.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
                Log.i(
                    tag,
                    "🛡 Effective stabilization physical=$id " +
                        "requested=${requestedModes[id] ?: "unknown"} " +
                        "eis=${describeMode(electronic)} ois=${describeMode(optical)}",
                )
            }
        }
    }

    fun resolveSingleTargetId(context: Context, lensPreference: String?): String? {
        val manager = cameraManager(context) ?: return null
        val wantsFront = lensPreference == "front"
        val wantedFacing = if (wantsFront) {
            android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT
        } else {
            android.hardware.camera2.CameraMetadata.LENS_FACING_BACK
        }

        val matches = manager.cameraIdList.filter { id ->
            runCatching {
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            }.getOrNull() == wantedFacing
        }
        val fallback = manager.cameraIdList.firstOrNull()
        val selected = matches.firstOrNull() ?: fallback
        Log.i(TAG, "🛡 Resolved single target preference=$lensPreference id=$selected")
        return selected
    }

    /**
     * Settings inspection must match the runtime backend: named rear lenses use
     * a physical sensor through Camera2, while CameraX fallback binds logical 0.
     */
    private suspend fun resolveInspectionTargetIds(
        context: Context,
        settings: VideoRecorderSettings,
    ): List<String> {
        if (!settings.dualCameraEnabled || settings.secondaryCameraLens == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                DualCameraSupport.resolveStrictPhysicalCamera(
                    context,
                    settings.cameraLens,
                )?.let { binding ->
                    Log.i(
                        TAG,
                        "🛡 Resolved single target preference=${settings.cameraLens} " +
                            "physical=${binding.physicalId} via logical=${binding.logicalCameraId}",
                    )
                    return listOf(binding.physicalId)
                }
            }
        }

        return resolveTargetIds(context, settings)
    }

    private suspend fun resolveTargetIds(
        context: Context,
        settings: VideoRecorderSettings,
    ): List<String> {
        if (!settings.dualCameraEnabled || settings.secondaryCameraLens == null) {
            return listOfNotNull(resolveSingleTargetId(context, settings.cameraLens))
        }

        DualCameraSupport.resolvePhysicalDualPlan(
            context,
            settings.cameraLens,
            settings.secondaryCameraLens,
        ).physicalPair?.let { pair ->
            return listOf(pair.primaryPhysicalId, pair.secondaryPhysicalId)
        }

        val provider = context.getCameraProvider()
        val plan = DualCameraSupport.resolveDualPlan(
            context,
            provider,
            settings.cameraLens,
            settings.secondaryCameraLens,
        )

        return when (plan.strategy) {
            DualCameraSupport.Strategy.CONCURRENT_CAMERAX -> listOfNotNull(
                cameraInfoId(plan.cameraxPair?.primary),
                cameraInfoId(plan.cameraxPair?.secondary),
            )
            DualCameraSupport.Strategy.PHYSICAL_CAMERA2 -> listOfNotNull(
                plan.physicalPair?.primaryPhysicalId,
                plan.physicalPair?.secondaryPhysicalId,
            )
            DualCameraSupport.Strategy.NONE -> emptyList()
        }
    }

    private fun cameraManager(context: Context): CameraManager? {
        return context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    }

    private fun cameraInfoId(info: androidx.camera.core.CameraInfo?): String? =
        info?.let {
            runCatching { Camera2CameraInfo.from(it).cameraId }.getOrNull()
        }

    private fun describeMode(value: Int?): String = when (value) {
        1 -> "on"
        0 -> "off"
        else -> "unknown(${value ?: -1})"
    }
}
