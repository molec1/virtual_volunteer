package com.virtualvolunteer.app.ui.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.SizeF
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A single selectable rear camera option.
 *
 * Two kinds exist:
 * - **Real** (`zoomRatio == null`): backed by a distinct Camera2 camera ID; switching requires
 *   rebinding CameraX use-cases via [selector].
 * - **Synthetic** (`zoomRatio != null`): all share the same [selector] (the only logical rear
 *   camera); switching applies a different zoom ratio to the already-bound camera without rebinding.
 *   Used as a last resort when the device does not expose multiple rear logical cameras to CameraX
 *   and CameraManager yields nothing extra.
 */
internal data class RearCameraOption(
    val equivMm: Int,              // >0 = computed 35mm-equiv; -1 = synthetic zoom option
    val label: String,             // "23mm" or "1×" / "2×" / "5×"
    val selector: CameraSelector,
    val zoomRatio: Float? = null,  // non-null → apply zoom, do not rebind
)

/**
 * Builds a list of [RearCameraOption]s using two successive enumeration strategies:
 *
 * 1. **CameraX** (`getAvailableCameraInfos`): works when all camera logical IDs are in the
 *    CameraX registry (most devices).
 * 2. **`CameraManager` direct enumeration**: supplementary path for OEMs (e.g. Xiaomi/MIUI) where
 *    auxiliary camera IDs exist in Camera2 but are absent from CameraX's list, or where Camera2
 *    characteristic queries throw security exceptions through the CameraX interop path.
 *
 * Cameras with a 35 mm–equivalent focal length below [MIN_EQUIV_MM] (ultrawide) are excluded.
 * [defaultIndex] returns the option closest to [BASE_EQUIV_MM] ("1×" reference ≈ main lens).
 * [buildZoomOptions] provides a synthetic fallback when no multi-camera path works.
 */
internal object RearCameraSelector {

    private const val TAG = "RearCameraSelector"
    private const val MIN_EQUIV_MM = 20
    private const val BASE_EQUIV_MM = 23
    private const val FULL_FRAME_DIAGONAL_MM = 43.27f

    // ── Public API ─────────────────────────────────────────────────────────────────────────────

    /**
     * @param pipelineLog optional sink that receives human-readable enumeration lines; used to
     *   surface results in the in-app "Pipeline debug" log in addition to Logcat.
     */
    fun buildOptions(
        context: Context,
        cameraProvider: ProcessCameraProvider,
        pipelineLog: ((String) -> Unit)? = null,
    ): List<RearCameraOption> {
        val fromCameraX = fromCameraXList(cameraProvider, pipelineLog)
        if (fromCameraX.size >= 2) {
            val msg = "CAMERA_ENUM CameraX: ${fromCameraX.size} rear lens(es): ${fromCameraX.map { it.label }}"
            Log.i(TAG, msg); pipelineLog?.invoke(msg)
            return fromCameraX
        }

        val msg1 = "CAMERA_ENUM CameraX found ${fromCameraX.size} rear lens(es) — trying CameraManager"
        Log.i(TAG, msg1); pipelineLog?.invoke(msg1)

        val fromManager = fromCameraManager(context, cameraProvider, pipelineLog)
        if (fromManager.size >= 2) {
            val msg = "CAMERA_ENUM CameraManager: ${fromManager.size} rear lens(es): ${fromManager.map { it.label }}"
            Log.i(TAG, msg); pipelineLog?.invoke(msg)
            return fromManager
        }

        val msg2 = "CAMERA_ENUM CameraManager: ${fromManager.size} rear lens(es) — zoom fallback will apply"
        Log.i(TAG, msg2); pipelineLog?.invoke(msg2)

        return fromCameraX.ifEmpty {
            listOf(RearCameraOption(0, "—", CameraSelector.DEFAULT_BACK_CAMERA))
        }
    }

    /**
     * When both real-camera paths found only one camera, call this after binding to check if the
     * device's zoom range supports a second synthetic option (1×/2×/5×).
     * Returns an empty list when no synthesis is possible or real options already exist.
     */
    fun buildZoomOptions(
        existingOptions: List<RearCameraOption>,
        maxZoomRatio: Float,
    ): List<RearCameraOption> {
        if (existingOptions.size >= 2) return emptyList()
        if (maxZoomRatio < 2.0f) {
            Log.d(TAG, "buildZoomOptions: maxZoom=$maxZoomRatio < 2× — skipping")
            return emptyList()
        }
        val selector = existingOptions.firstOrNull()?.selector ?: CameraSelector.DEFAULT_BACK_CAMERA
        val result = buildList {
            add(RearCameraOption(-1, "1×", selector, zoomRatio = 1.0f))
            add(RearCameraOption(-1, "2×", selector, zoomRatio = 2.0f))
            if (maxZoomRatio >= 5.0f) add(RearCameraOption(-1, "5×", selector, zoomRatio = 5.0f))
        }
        Log.d(TAG, "buildZoomOptions: maxZoom=$maxZoomRatio → ${result.map { it.label }}")
        return result
    }

    /** Index of the option whose equivalent focal length is closest to [BASE_EQUIV_MM]. */
    fun defaultIndex(options: List<RearCameraOption>): Int {
        if (options.size <= 1) return 0
        return options.indices.minByOrNull { abs(options[it].equivMm - BASE_EQUIV_MM) } ?: 0
    }

    // ── Private helpers ────────────────────────────────────────────────────────────────────────

    /** Strategy 1: enumerate via CameraX's getAvailableCameraInfos(). */
    private fun fromCameraXList(
        cameraProvider: ProcessCameraProvider,
        pipelineLog: ((String) -> Unit)?,
    ): List<RearCameraOption> {
        val all = cameraProvider.getAvailableCameraInfos()
        val msg = "CAMERA_ENUM getAvailableCameraInfos: ${all.size} total"
        Log.i(TAG, msg); pipelineLog?.invoke(msg)
        return all.mapNotNull { info ->
            try {
                val c2 = Camera2CameraInfo.from(info)
                val id = c2.cameraId
                val facing = c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
                if (facing != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null
                makeOption(id = id,
                    focalLengths = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS),
                    sensorSize = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE),
                    pipelineLog = pipelineLog)
            } catch (e: Exception) {
                val w = "CAMERA_ENUM CameraX skip: ${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, w); pipelineLog?.invoke(w)
                null
            }
        }.sortedBy { it.equivMm }
    }

    /**
     * Strategy 2: enumerate all IDs from [CameraManager] and read characteristics directly,
     * bypassing the CameraX interop layer which may throw on some OEM firmware.
     */
    private fun fromCameraManager(
        context: Context,
        cameraProvider: ProcessCameraProvider,
        pipelineLog: ((String) -> Unit)?,
    ): List<RearCameraOption> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val allIds = manager.cameraIdList.toList()
        val knownIds = cameraProvider.getAvailableCameraInfos()
            .mapNotNull { runCatching { Camera2CameraInfo.from(it).cameraId }.getOrNull() }
            .toSet()
        val msg = "CAMERA_ENUM CameraManager ids=$allIds knownByCameraX=$knownIds"
        Log.i(TAG, msg); pipelineLog?.invoke(msg)

        return allIds.mapNotNull { id ->
            try {
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null
                makeOption(id = id,
                    focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS),
                    sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE),
                    pipelineLog = pipelineLog)
            } catch (e: Exception) {
                val w = "CAMERA_ENUM CameraManager id=$id skip: ${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, w); pipelineLog?.invoke(w)
                null
            }
        }.sortedBy { it.equivMm }
    }

    private fun makeOption(
        id: String,
        focalLengths: FloatArray?,
        sensorSize: SizeF?,
        pipelineLog: ((String) -> Unit)?,
    ): RearCameraOption? {
        if (focalLengths == null || focalLengths.isEmpty()) {
            val m = "  id=$id: no focal length — skip"; Log.i(TAG, m); pipelineLog?.invoke(m)
            return null
        }
        if (sensorSize == null) {
            val m = "  id=$id: no sensor size — skip"; Log.i(TAG, m); pipelineLog?.invoke(m)
            return null
        }
        val diagonal = sqrt(sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height)
        if (diagonal <= 0f) return null
        val equivMm = (focalLengths[0] * FULL_FRAME_DIAGONAL_MM / diagonal).roundToInt()
        val m = "  id=$id focal=${focalLengths[0]}mm diag=${String.format("%.2f", diagonal)}mm → ${equivMm}mm equiv"
        Log.i(TAG, m); pipelineLog?.invoke(m)
        if (equivMm < MIN_EQUIV_MM) {
            val skip = "  id=$id: ${equivMm}mm < ${MIN_EQUIV_MM}mm — skip (ultrawide)"
            Log.i(TAG, skip); pipelineLog?.invoke(skip)
            return null
        }
        val selector = CameraSelector.Builder()
            .addCameraFilter { cams ->
                // No .ifEmpty fallback: empty result → bindToLifecycle throws → caught in bindCamera.
                cams.filter { cam -> Camera2CameraInfo.from(cam).cameraId == id }
            }
            .build()
        return RearCameraOption(equivMm = equivMm, label = "${equivMm}mm", selector = selector)
    }
}
