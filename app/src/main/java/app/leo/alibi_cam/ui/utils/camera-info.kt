package app.leo.alibi_cam.ui.utils

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import androidx.camera.core.CameraSelector

/**
 * Lightweight camera info — no physical-camera enumeration, no metadata-based UW detection.
 *
 * Ultra-wide is engaged through the logical back camera's zoom ratio
 * (cameraControl.setZoomRatio(minZoomRatio)), not by binding a physical camera ID.
 * This approach works on Xiaomi HyperOS and other OEMs that block direct physical-camera
 * access for third-party apps.
 */
data class CameraInfo(
    val cameraId: String,
    val lensFacing: Int,
    val focalLength: Float?,
) {
    enum class Lens(val androidValue: Int) {
        BACK(CameraSelector.LENS_FACING_BACK),
        FRONT(CameraSelector.LENS_FACING_FRONT),
        EXTERNAL(CameraSelector.LENS_FACING_EXTERNAL),
        ULTRA_WIDE(-1),   // Logical or physical UW (focal < 3.5mm)
        TELEPHOTO(-2),    // Physical telephoto (focal > 10.0mm)
        UNKNOWN(999),
    }

    val lens: Lens
        get() = when (lensFacing) {
            CameraSelector.LENS_FACING_FRONT -> Lens.FRONT
            CameraSelector.LENS_FACING_EXTERNAL -> Lens.EXTERNAL
            CameraSelector.LENS_FACING_BACK -> {
                val f = focalLength ?: 6.5f
                if (f < 3.5f) Lens.ULTRA_WIDE
                else if (f > 10.0f) Lens.TELEPHOTO
                else Lens.BACK
            }
            else -> Lens.UNKNOWN
        }

    companion object {
        private const val TAG = "CameraInfo"

        val CAMERA_INT_TO_LENS_MAP = mapOf(
            CameraSelector.LENS_FACING_BACK to Lens.BACK,
            CameraSelector.LENS_FACING_FRONT to Lens.FRONT,
            CameraSelector.LENS_FACING_EXTERNAL to Lens.EXTERNAL,
        )

        /**
         * Query available cameras via CameraManager.
         *
         * Only enumerates logical cameras (what CameraX can actually bind to).
         * Physical sub-camera enumeration is deliberately skipped:
         * Xiaomi HyperOS and similar OEM skins block direct physical-camera binding
         * for third-party apps. The only reliable path to ultra-wide is through
         * the logical back camera's zoom ratio.
         */
        fun queryAvailableCameras(context: Context): List<CameraInfo> {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val allCameras = mutableListOf<CameraInfo>()

            val rawIdList = cameraManager.cameraIdList.toList()
            Log.i(TAG, "═══════════════════════════════════════════")
            Log.i(TAG, "🔍 Camera enumeration (package: ${context.packageName})")
            Log.i(TAG, "📦 Raw cameraManager.cameraIdList = $rawIdList")
            Log.i(TAG, "═══════════════════════════════════════════")

            // Log concurrent camera combinations if supported by system
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val concurrentSets = cameraManager.concurrentCameraIds
                    Log.i(TAG, "⚡ CameraManager.getConcurrentCameraIds() = $concurrentSets")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Failed to get concurrent camera ids: ${e.message}")
                }
            }

            cameraManager.cameraIdList.forEach { cameraId ->
                try {
                    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                    val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                        ?: return@forEach

                    val focalLengths = characteristics
                        .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    val minFocalLength = focalLengths?.minOrNull()
                    val allFocals = focalLengths?.joinToString(", ") ?: "none"

                    val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        characteristics.physicalCameraIds.joinToString(", ")
                    } else "N/A"

                    val facingLabel = when (lensFacing) {
                        CameraSelector.LENS_FACING_BACK -> "BACK"
                        CameraSelector.LENS_FACING_FRONT -> "FRONT"
                        CameraSelector.LENS_FACING_EXTERNAL -> "EXTERNAL"
                        else -> "UNKNOWN($lensFacing)"
                    }

                    Log.i(TAG, "  Camera [$cameraId]: facing=$facingLabel, minFocal=$minFocalLength (focals: $allFocals), physicalIds=[$physicalIds]")

                    allCameras.add(
                        CameraInfo(
                            cameraId = cameraId,
                            lensFacing = lensFacing,
                            focalLength = minFocalLength,
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "  ⚠️ Error querying camera $cameraId: ${e.message}")
                }
            }

            // Deduplicate
            val uniqueCameras = allCameras.distinctBy { it.cameraId }

            Log.i(TAG, "───────────────────────────────────────────")
            Log.i(TAG, "📋 Final: ${uniqueCameras.size} cameras")
            uniqueCameras.forEach { cam ->
                Log.i(TAG, "  [${cam.cameraId}] lens=${cam.lens}, focal=${cam.focalLength}")
            }
            Log.i(TAG, "═══════════════════════════════════════════")

            return uniqueCameras
        }

        /**
         * Resolve the camera to use based on user preference.
         *
         * Returns Pair(lensFacing, cameraLensMode):
         * - cameraLensMode = "ultrawide" → bind back camera + set zoom to min
         * - cameraLensMode = "main"      → bind back camera + keep zoom at 1x
         * - cameraLensMode = null        → bind matching camera, no zoom adjustment
         *
         * @param preference User's camera lens preference ("ultrawide", "main", "front", "auto")
         */
        fun resolveCamera(
            preference: String?,
            cameras: List<CameraInfo>,
        ): Pair<Int, String?> {
            val hasBackCamera = cameras.any { it.lensFacing == CameraSelector.LENS_FACING_BACK }

            val result = when (preference) {
                "ultrawide" -> {
                    if (hasBackCamera) {
                        Log.i(TAG, "🎯 UW mode → back camera + zoom-to-min")
                        Pair(CameraSelector.LENS_FACING_BACK, "ultrawide")
                    } else {
                        Log.w(TAG, "🎯 UW requested but no back camera → front")
                        Pair(CameraSelector.LENS_FACING_FRONT, null)
                    }
                }
                "main" -> {
                    if (hasBackCamera) {
                        Log.i(TAG, "🎯 Main mode → back camera at 1x")
                        Pair(CameraSelector.LENS_FACING_BACK, "main")
                    } else {
                        Log.w(TAG, "🎯 Main requested but no back camera → front")
                        Pair(CameraSelector.LENS_FACING_FRONT, null)
                    }
                }
                "telephoto" -> {
                    if (hasBackCamera) {
                        Log.i(TAG, "🎯 Telephoto mode → back camera (physical telephoto ID 3 or focal > 10)")
                        Pair(CameraSelector.LENS_FACING_BACK, "telephoto")
                    } else {
                        Log.w(TAG, "🎯 Telephoto requested but no back camera → front")
                        Pair(CameraSelector.LENS_FACING_FRONT, null)
                    }
                }
                "front" -> {
                    Log.i(TAG, "🎯 Front selected")
                    Pair(CameraSelector.LENS_FACING_FRONT, null)
                }
                // "auto" or null: prefer UW via zoom, fall back gracefully
                else -> {
                    if (hasBackCamera) {
                        Log.i(TAG, "🎯 Auto → back camera (zoom-based UW)")
                        Pair(CameraSelector.LENS_FACING_BACK, "ultrawide")
                    } else {
                        Log.w(TAG, "🎯 Auto → no back camera, trying front")
                        Pair(CameraSelector.LENS_FACING_FRONT, null)
                    }
                }
            }

            Log.i(TAG, "🎯 resolveCamera(preference=$preference) → lensFacing=${
                when (result.first) {
                    CameraSelector.LENS_FACING_BACK -> "BACK"
                    CameraSelector.LENS_FACING_FRONT -> "FRONT"
                    else -> result.first.toString()
                }
            }, cameraLensMode=${result.second ?: "null"}")

            return result
        }

        /**
         * Build a CameraSelector for a given lens facing.
         * No camera-ID filtering — we always bind the logical camera
         * and use zoom ratio for UW engagement.
         */
        fun buildCameraSelector(lensFacing: Int): CameraSelector {
            Log.d(TAG, "🔧 buildCameraSelector: lensFacing=${
                when (lensFacing) {
                    CameraSelector.LENS_FACING_BACK -> "BACK"
                    CameraSelector.LENS_FACING_FRONT -> "FRONT"
                    else -> lensFacing.toString()
                }
            }")
            return CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()
        }

        // ── Convenience accessors (used by UI) ──

        /**
         * Get the first front-facing camera, or null if none.
         */
        fun getFrontCamera(cameras: List<CameraInfo>): CameraInfo? =
            cameras.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_FRONT }

        /**
         * Get the first back-facing camera, or null if none.
         */
        fun getAnyBackCamera(cameras: List<CameraInfo>): CameraInfo? =
            cameras.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK }

        /**
         * Check whether the device has "normal" cameras (one front, one back).
         * Used to decide whether to show the camera picker UI at all.
         */
        fun checkHasNormalCameras(cameras: Iterable<CameraInfo>) =
            cameras.count() == 2 &&
                    cameras.elementAt(0).cameraId == "0" &&
                    cameras.elementAt(1).cameraId == "1"
    }
}
