package app.leo.alibi_cam.helpers

/**
 * Identifies which camera position a [VideoBatchesFolder] belongs to.
 * Used to keep dual-camera recordings in separate subfolders / file names.
 *
 * Unlike Alibi's original (front+back only), our CameraPosition supports
 * arbitrary lens combinations:
 *   SINGLE   – normal single-camera mode (no suffix)
 *   BACK     – back/main camera in dual mode
 *   FRONT    – front camera in dual mode
 *   ULTRAWIDE – ultra-wide camera in dual mode
 *   EXTERNAL – external USB camera in dual mode
 */
enum class CameraPosition(val folderSuffix: String, val fileTag: String) {
    SINGLE("", ""),
    BACK("_back", "back"),
    FRONT("_front", "front"),
    ULTRAWIDE("_ultrawide", "ultrawide"),
    TELEPHOTO("_telephoto", "telephoto"),
    EXTERNAL("_external", "external"),
    ;

    companion object {
        /**
         * Map a camera lens setting string (from VideoRecorderSettings.cameraLens)
         * to a CameraPosition for dual-camera folder naming.
         */
        fun fromLensString(lens: String?): CameraPosition = when (lens) {
            "front" -> FRONT
            "ultrawide" -> ULTRAWIDE
            "telephoto" -> TELEPHOTO
            "main", "auto", null -> BACK
            else -> EXTERNAL
        }
    }
}
