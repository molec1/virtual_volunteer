package com.virtualvolunteer.app.domain.face

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/**
 * Persists a face crop as a JPEG thumbnail under the race faces directory.
 */
object FaceThumbnailSaver {

    fun saveJpeg(faceCrop: Bitmap, destFile: File, quality: Int = 88) {
        destFile.parentFile?.mkdirs()
        FileOutputStream(destFile).use { out ->
            faceCrop.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
    }

    /**
     * Deterministic file name per source image and face index within that image.
     */
    fun thumbnailFile(facesDir: File, sourcePhotoFile: File, faceIndexOneBased: Int): File {
        facesDir.mkdirs()
        val base = sourcePhotoFile.nameWithoutExtension.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(facesDir, "face_${base}_${faceIndexOneBased}.jpg")
    }
}
