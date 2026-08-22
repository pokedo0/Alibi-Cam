package app.leo.alibi_cam.ui.utils

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import app.leo.alibi_cam.helpers.CameraDebugLog

/**
 * Resolves dual-camera capabilities in hardware terms instead of assuming
 * that every OEM uses the same camera IDs.
 *
 * CameraX ConcurrentCamera is preferred. Some devices expose a logical
 * multi-camera (for example, rear main/tele/ultra-wide) only through Camera2
 * physical output streams, so that path is returned as a fallback plan.
 */
object DualCameraSupport {

    private const val TAG = "DualCameraSupport"
    private const val ULTRA_WIDE_MAX_MM = 3.5f
    private const val TELEPHOTO_MIN_MM = 10.0f

    enum class Strategy {
        CONCURRENT_CAMERAX,
        PHYSICAL_CAMERA2,
        NONE,
    }

    data class PhysicalCameraPair(
        val logicalCameraId: String,
        val primaryPhysicalId: String,
        val secondaryPhysicalId: String,
    )

    data class SupportedPair(
        val primary: androidx.camera.core.CameraInfo,
        val secondary: androidx.camera.core.CameraInfo,
        val primarySelector: CameraSelector,
        val secondarySelector: CameraSelector,
    )

    data class DualPlan(
        val strategy: Strategy,
        val cameraxPair: SupportedPair? = null,
        val physicalPair: PhysicalCameraPair? = null,
    )

    /** True when either CameraX or Camera2 can provide a dual pair. */
    suspend fun isSupported(context: Context): Boolean {
        return try {
            val provider = context.getCameraProvider()
            val physicalAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                findAnyPhysicalPair(context) != null
            val cameraxAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                provider.availableConcurrentCameraInfos.any { it.size >= 2 }
            Log.i(
                TAG,
                "🔍 Dual support: camerax=$cameraxAvailable, physical=$physicalAvailable",
            )
            CameraDebugLog.append(
                "🔍 Dual support: camerax=$cameraxAvailable, physical=$physicalAvailable",
            )
            cameraxAvailable || physicalAvailable
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve dual-camera support", e)
            CameraDebugLog.append("⚠️ Dual support check failed: ${e.message}")
            CameraDebugLog.flush()
            false
        }
    }

    /**
     * Prefer a CameraX concurrent pair, then try physical streams within one
     * logical rear camera. A physical plan is only returned for two rear lens
     * labels; front-camera combinations must use CameraX.
     */
    fun resolveDualPlan(
        context: Context,
        provider: ProcessCameraProvider,
        primaryLens: String?,
        secondaryLens: String?,
    ): DualPlan {
        if (secondaryLens == null) return DualPlan(Strategy.NONE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            findPairForLenses(context, provider, primaryLens, secondaryLens)?.let {
                Log.i(TAG, "✅ Using CameraX concurrent pair for $primaryLens + $secondaryLens")
                CameraDebugLog.append("✅ Plan=CAMERAX for $primaryLens + $secondaryLens")
                return DualPlan(Strategy.CONCURRENT_CAMERAX, cameraxPair = it)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            isRearLens(primaryLens) && isRearLens(secondaryLens)
        ) {
            findPhysicalPair(context, primaryLens, secondaryLens)?.let {
                Log.i(
                    TAG,
                    "✅ Using Camera2 physical pair: logical=${it.logicalCameraId}, " +
                        "primary=${it.primaryPhysicalId}, secondary=${it.secondaryPhysicalId}",
                )
                CameraDebugLog.append(
                    "✅ Plan=PHYSICAL_CAMERA2 logical=${it.logicalCameraId} " +
                        "primary=${it.primaryPhysicalId} secondary=${it.secondaryPhysicalId}",
                )
                return DualPlan(Strategy.PHYSICAL_CAMERA2, physicalPair = it)
            }
        }

        Log.w(TAG, "❌ No dual plan for $primaryLens + $secondaryLens")
        CameraDebugLog.append("❌ Plan=NONE for $primaryLens + $secondaryLens")
        return DualPlan(Strategy.NONE)
    }

    private fun isRearLens(lens: String?): Boolean =
        lens == null || lens == "auto" || lens == "main" ||
            lens == "ultrawide" || lens == "telephoto"

    private fun findPairForLenses(
        context: Context,
        provider: ProcessCameraProvider,
        primaryLens: String?,
        secondaryLens: String?,
    ): SupportedPair? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || secondaryLens == null) return null

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        for (combo in provider.availableConcurrentCameraInfos) {
            if (combo.size < 2) continue

            val primary = combo.firstOrNull {
                cameraMatchesLens(manager, cameraIdOf(it), primaryLens)
            }
            val secondary = combo.firstOrNull {
                it != primary && cameraMatchesLens(manager, cameraIdOf(it), secondaryLens)
            }
            if (primary != null && secondary != null) {
                val primaryId = cameraIdOf(primary)
                val secondaryId = cameraIdOf(secondary)
                Log.i(TAG, "✅ CameraX pair IDs: $primaryId + $secondaryId")
                CameraDebugLog.append(
                    "✅ CameraX pair IDs: $primaryId + $secondaryId " +
                        "($primaryLens + $secondaryLens)",
                )
                return SupportedPair(
                    primary = primary,
                    secondary = secondary,
                    primarySelector = primary.cameraSelector,
                    secondarySelector = secondary.cameraSelector,
                )
            }
        }
        return null
    }

    private fun cameraIdOf(cameraInfo: androidx.camera.core.CameraInfo): String =
        runCatching {
            androidx.camera.camera2.interop.Camera2CameraInfo.from(cameraInfo).cameraId
        }.getOrDefault("")

    private fun cameraMatchesLens(
        manager: CameraManager,
        cameraId: String,
        requestedLens: String?,
    ): Boolean {
        if (cameraId.isBlank()) return false
        val facing = runCatching {
            manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.LENS_FACING)
        }.getOrNull()

        if (requestedLens == "front") {
            return facing == CameraSelector.LENS_FACING_FRONT
        }
        if (facing != CameraSelector.LENS_FACING_BACK) return false

        return lensKind(readFocalLength(manager, cameraId)).matches(requestedLens)
    }

    private fun findPhysicalPair(
        context: Context,
        primaryLens: String?,
        secondaryLens: String?,
    ): PhysicalCameraPair? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        for (logicalId in manager.cameraIdList) {
            val characteristics = runCatching {
                manager.getCameraCharacteristics(logicalId)
            }.getOrNull() ?: continue
            if (characteristics.get(CameraCharacteristics.LENS_FACING) != CameraSelector.LENS_FACING_BACK) {
                continue
            }

            val physicalIds = characteristics.physicalCameraIds
            if (physicalIds.size < 2) continue

            val primaryId = physicalIds.firstOrNull {
                lensKind(readFocalLength(manager, it)).matches(primaryLens)
            }
            val secondaryId = physicalIds.firstOrNull {
                it != primaryId && lensKind(readFocalLength(manager, it)).matches(secondaryLens)
            }
            if (primaryId != null && secondaryId != null) {
                return PhysicalCameraPair(logicalId, primaryId, secondaryId)
            }
        }
        return null
    }

    private fun findAnyPhysicalPair(context: Context): PhysicalCameraPair? {
        return findPhysicalPair(context, "main", "telephoto")
            ?: findPhysicalPair(context, "main", "ultrawide")
            ?: findPhysicalPair(context, "telephoto", "ultrawide")
    }

    private fun readFocalLength(manager: CameraManager, cameraId: String): Float? =
        runCatching {
            manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.minOrNull()
        }.getOrNull()

    private fun lensKind(focalLength: Float?): LensKind = when {
        focalLength == null -> LensKind.MAIN
        focalLength < ULTRA_WIDE_MAX_MM -> LensKind.ULTRA_WIDE
        focalLength > TELEPHOTO_MIN_MM -> LensKind.TELEPHOTO
        else -> LensKind.MAIN
    }

    private enum class LensKind {
        MAIN,
        ULTRA_WIDE,
        TELEPHOTO;

        fun matches(requestedLens: String?): Boolean = when (requestedLens) {
            null, "auto", "main" -> this == MAIN
            "ultrawide" -> this == ULTRA_WIDE
            "telephoto" -> this == TELEPHOTO
            else -> false
        }
    }

}
