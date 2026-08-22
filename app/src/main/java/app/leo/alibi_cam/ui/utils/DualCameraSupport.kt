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
 * Helpers for simultaneous dual-camera recording.
 *
 * Supports two strategies:
 * 1. [Strategy.CONCURRENT_CAMERAX]: Standard Android 11+ (API 30) multi-device concurrent
 *    streaming (e.g. Front + Back).
 * 2. [Strategy.PHYSICAL_CAMERA2]: Android 9+ (API 28) Logical Multi-Camera physical stream
 *    separation (e.g. Back Main ID 2 + Back Telephoto ID 3, or Back UltraWide ID 4 + Back Telephoto ID 3).
 */
object DualCameraSupport {

    private const val TAG = "DualCameraSupport"

    enum class Strategy {
        CONCURRENT_CAMERAX,
        PHYSICAL_CAMERA2,
        NONE,
    }

    data class DualPlan(
        val strategy: Strategy,
        val cameraxPair: SupportedPair? = null,
        val primaryPhysicalId: String? = null,
        val secondaryPhysicalId: String? = null,
    )

    /** True if the device can run dual cameras (via either CameraX or Camera2 physical streams). */
    suspend fun isSupported(context: Context): Boolean {
        // 1. Check CameraX concurrent camera support (API 30+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val provider = context.getCameraProvider()
                val combos = provider.availableConcurrentCameraInfos
                Log.i(TAG, "🔍 DualCameraSupport.isSupported: combos count = ${combos.size}")
                CameraDebugLog.append("🔍 ConcurrentCamera combos count = ${combos.size}")
                combos.forEachIndexed { idx, combo ->
                    val descs = combo.map { camInfo ->
                        val facing = when (camInfo.lensFacing) {
                            CameraSelector.LENS_FACING_BACK -> "BACK"
                            CameraSelector.LENS_FACING_FRONT -> "FRONT"
                            else -> "OTHER(${camInfo.lensFacing})"
                        }
                        val id = runCatching {
                            androidx.camera.camera2.interop.Camera2CameraInfo.from(camInfo).cameraId
                        }.getOrDefault("?")
                        "ID=$id($facing)"
                    }.joinToString(", ")
                    Log.i(TAG, "  Combo [$idx]: $descs")
                    CameraDebugLog.append("  Combo [$idx]: $descs")
                }
                CameraDebugLog.flush()
                if (combos.any { it.size >= 2 }) return true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check concurrent camera support", e)
            }
        }

        // 2. Check Camera2 Logical Multi-Camera support (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val characteristics = cameraManager.getCameraCharacteristics("0")
                val physicalIds = characteristics.physicalCameraIds
                Log.i(TAG, "🔍 Camera 0 physical sub-cameras: $physicalIds")
                CameraDebugLog.append("🔍 Camera 0 physical sub-cameras: $physicalIds")
                CameraDebugLog.flush()
                if (physicalIds.size >= 2) return true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check physical camera support", e)
            }
        }

        return false
    }

    /**
     * Resolve the dual camera execution plan for the given lens combination.
     */
    fun resolveDualPlan(
        context: Context,
        provider: ProcessCameraProvider,
        primaryLens: String?,
        secondaryLens: String?,
    ): DualPlan {
        if (secondaryLens == null) return DualPlan(Strategy.NONE)

        Log.i(TAG, "🔍 Resolving dual camera plan for: $primaryLens + $secondaryLens")
        CameraDebugLog.append("🔍 Resolving dual plan: $primaryLens + $secondaryLens")

        // 1. Try CameraX ConcurrentCamera (for Front + Back)
        val cameraxPair = findPairForLenses(provider, primaryLens, secondaryLens)
        if (cameraxPair != null) {
            Log.i(TAG, "🎯 Selected Strategy: CONCURRENT_CAMERAX")
            CameraDebugLog.append("🎯 Plan: CONCURRENT_CAMERAX (Front/Back)")
            return DualPlan(
                strategy = Strategy.CONCURRENT_CAMERAX,
                cameraxPair = cameraxPair,
            )
        }

        // 2. Try Camera2 Physical Streams (for Back + Back combinations, e.g. Main + Telephoto)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val characteristics = cameraManager.getCameraCharacteristics("0")
                val physicalIds = characteristics.physicalCameraIds

                val primaryId = mapLensToPhysicalId(primaryLens, physicalIds, isPrimary = true)
                val secondaryId = mapLensToPhysicalId(secondaryLens, physicalIds, isPrimary = false)

                if (primaryId != null && secondaryId != null && primaryId != secondaryId) {
                    Log.i(TAG, "🎯 Selected Strategy: PHYSICAL_CAMERA2 ($primaryId + $secondaryId)")
                    CameraDebugLog.append("🎯 Plan: PHYSICAL_CAMERA2 ($primaryId + $secondaryId)")
                    CameraDebugLog.flush()
                    return DualPlan(
                        strategy = Strategy.PHYSICAL_CAMERA2,
                        primaryPhysicalId = primaryId,
                        secondaryPhysicalId = secondaryId,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking physical camera plan", e)
            }
        }

        Log.w(TAG, "❌ No supported dual camera plan for $primaryLens + $secondaryLens")
        CameraDebugLog.append("❌ No dual plan for $primaryLens + $secondaryLens")
        CameraDebugLog.flush()
        return DualPlan(Strategy.NONE)
    }

    private fun mapLensToPhysicalId(
        lens: String?,
        availablePhysicalIds: Set<String>,
        isPrimary: Boolean,
    ): String? {
        return when (lens) {
            "telephoto" -> if ("3" in availablePhysicalIds) "3" else null
            "ultrawide" -> if ("4" in availablePhysicalIds) "4" else null
            "main", "auto", null -> if ("2" in availablePhysicalIds) "2" else "0"
            else -> null
        }
    }

    /**
     * Find a concurrent camera pair that matches the requested lens combination.
     */
    fun findPairForLenses(
        provider: ProcessCameraProvider,
        primaryLens: String?,
        secondaryLens: String?,
    ): SupportedPair? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (secondaryLens == null) return null

        for (combo in provider.availableConcurrentCameraInfos) {
            if (combo.size < 2) continue

            // Exact match
            val primary = combo.firstOrNull { matchCameraToLens(it, primaryLens) }
            val remaining = combo.filter { it != primary }
            val secondary = remaining.firstOrNull { matchCameraToLens(it, secondaryLens) }

            if (primary != null && secondary != null) {
                return SupportedPair(
                    primary = primary,
                    secondary = secondary,
                    primarySelector = primary.cameraSelector,
                    secondarySelector = secondary.cameraSelector,
                )
            }

            // Reverse match
            val secondaryAlt = combo.firstOrNull { matchCameraToLens(it, secondaryLens) }
            val remainingAlt = combo.filter { it != secondaryAlt }
            val primaryAlt = remainingAlt.firstOrNull { matchCameraToLens(it, primaryLens) }

            if (primaryAlt != null && secondaryAlt != null) {
                return SupportedPair(
                    primary = primaryAlt,
                    secondary = secondaryAlt,
                    primarySelector = primaryAlt.cameraSelector,
                    secondarySelector = secondaryAlt.cameraSelector,
                )
            }
        }

        return null
    }

    private fun matchCameraToLens(camInfo: androidx.camera.core.CameraInfo, lens: String?): Boolean {
        val cameraId = runCatching {
            androidx.camera.camera2.interop.Camera2CameraInfo.from(camInfo).cameraId
        }.getOrDefault("")
        val facing = camInfo.lensFacing

        return when (lens) {
            "front" -> facing == CameraSelector.LENS_FACING_FRONT || cameraId == "1"
            "telephoto" -> cameraId == "3"
            "ultrawide" -> cameraId == "4" || (facing == CameraSelector.LENS_FACING_BACK && cameraId == "0")
            "main", "auto", null -> cameraId == "0" || cameraId == "2" || (facing == CameraSelector.LENS_FACING_BACK && cameraId != "3" && cameraId != "4")
            else -> false
        }
    }

    data class SupportedPair(
        val primary: androidx.camera.core.CameraInfo,
        val secondary: androidx.camera.core.CameraInfo,
        val primarySelector: CameraSelector,
        val secondarySelector: CameraSelector,
    )
}
