package com.virtualvolunteer.app.domain.face

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await

/**
 * ML Kit face detection on full-resolution upright bitmaps; cropping is a separate step.
 *
 * Uses accurate mode (better recall on typical race photos than fast mode). Detection output is
 * the only signal for "face present"; embedding runs later on crops only.
 */
class MlKitFaceDetector {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .build(),
    )

    /** Stage 1: run ML Kit on the full photo (must already be rotation-corrected). */
    suspend fun detectFaces(bitmap: Bitmap): List<Face> {
        val image = InputImage.fromBitmap(bitmap, 0)
        return try {
            detector.process(image).await()
        } catch (e: Exception) {
            Log.e(TAG, "ML Kit face detection failed", e)
            emptyList()
        }
    }

    /**
     * Stage 2: crop a single face region from the same bitmap used for [detectFaces].
     * Returns null if bounds are degenerate after padding clamp.
     */
    fun cropFace(bitmap: Bitmap, face: Face, marginPerSide: Float = FaceCropBounds.DEFAULT_MARGIN_PER_SIDE): Bitmap? {
        val raw = Rect(face.boundingBox)
        val expanded = FaceCropBounds.expandFaceRect(raw, bitmap.width, bitmap.height, marginPerSide)
        val crop = FaceCropBounds.cropBitmap(bitmap, expanded)
        if (crop == null) {
            Log.w(TAG, "cropFace: failed raw=$raw expanded=$expanded bitmap=${bitmap.width}x${bitmap.height}")
        }
        return crop
    }

    /** Convenience: detect then crop (same semantics as the original pipeline). */
    suspend fun detectFaceCrops(bitmap: Bitmap, marginPerSide: Float = FaceCropBounds.DEFAULT_MARGIN_PER_SIDE): List<Bitmap> {
        val faces = detectFaces(bitmap)
        val out = ArrayList<Bitmap>(faces.size)
        for (face in faces) {
            val crop = cropFace(bitmap, face, marginPerSide) ?: continue
            out.add(crop)
        }
        return out
    }

    fun close() {
        detector.close()
    }

    companion object {
        private const val TAG = "MlKitFaceDetector"
    }
}
