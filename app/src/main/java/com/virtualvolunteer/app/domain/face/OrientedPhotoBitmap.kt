package com.virtualvolunteer.app.domain.face

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * Decodes a JPEG/PNG file and applies EXIF orientation so pixel data matches how the photo is viewed.
 * ML Kit must run on this upright bitmap so bounding boxes align with crops (same as using
 * [com.google.mlkit.vision.common.InputImage.fromFilePath], which respects EXIF internally).
 */
object OrientedPhotoBitmap {

    /**
     * Decode full-resolution bitmap with EXIF orientation applied. Caller must [Bitmap.recycle].
     */
    fun decodeApplyingExifOrientation(file: File): Bitmap? {
        val decoded = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        return applyExifOrientation(decoded, file.absolutePath)
    }

    fun applyExifOrientation(bitmap: Bitmap, imagePath: String): Bitmap {
        val exif = try {
            ExifInterface(imagePath)
        } catch (_: Throwable) {
            return bitmap
        }
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_UNDEFINED,
        )
        if (orientation == ExifInterface.ORIENTATION_UNDEFINED ||
            orientation == ExifInterface.ORIENTATION_NORMAL
        ) {
            return bitmap
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postScale(-1f, 1f)
                matrix.postRotate(90f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postScale(-1f, 1f)
                matrix.postRotate(270f)
            }
            else -> return bitmap
        }

        return try {
            val transformed = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                true,
            )
            if (transformed != bitmap) {
                bitmap.recycle()
            }
            transformed
        } catch (_: OutOfMemoryError) {
            bitmap
        }
    }

    /** Short human-readable EXIF orientation for pipeline logs (before upright correction). */
    fun describeExifOrientation(file: File): String {
        val exif = try {
            ExifInterface(file.absolutePath)
        } catch (_: Throwable) {
            return "exif=unreadable"
        }
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_UNDEFINED,
        )
        val label = when (orientation) {
            ExifInterface.ORIENTATION_UNDEFINED -> "UNDEFINED"
            ExifInterface.ORIENTATION_NORMAL -> "NORMAL"
            ExifInterface.ORIENTATION_ROTATE_90 -> "ROTATE_90"
            ExifInterface.ORIENTATION_ROTATE_180 -> "ROTATE_180"
            ExifInterface.ORIENTATION_ROTATE_270 -> "ROTATE_270"
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> "FLIP_H"
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> "FLIP_V"
            ExifInterface.ORIENTATION_TRANSPOSE -> "TRANSPOSE"
            ExifInterface.ORIENTATION_TRANSVERSE -> "TRANSVERSE"
            else -> "OTHER($orientation)"
        }
        return "exifOrientationTag=$orientation ($label)"
    }
}
