package com.virtualvolunteer.app.domain.face

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.google.mlkit.vision.face.Face
import java.io.File
import java.io.FileOutputStream

/**
 * Saves a copy of the vision bitmap with detector boxes (red) and expanded crop boxes (green).
 */
object FaceDebugOverlay {

    fun saveAnnotatedCopy(
        source: Bitmap,
        faces: List<Face>,
        marginPerSide: Float,
        destFile: File,
        jpegQuality: Int = 88,
    ): Boolean {
        return try {
            destFile.parentFile?.mkdirs()
            val copy = source.copy(Bitmap.Config.ARGB_8888, true) ?: return false
            val canvas = Canvas(copy)
            val rawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                color = Color.RED
            }
            val expandedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                color = Color.GREEN
            }
            for (face in faces) {
                val raw = Rect(face.boundingBox)
                canvas.drawRect(raw, rawPaint)
                val expanded = FaceCropBounds.expandFaceRect(
                    raw,
                    source.width,
                    source.height,
                    marginPerSide,
                )
                canvas.drawRect(expanded, expandedPaint)
            }
            FileOutputStream(destFile).use { out ->
                copy.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            }
            copy.recycle()
            destFile.exists() && destFile.length() > 0L
        } catch (_: Throwable) {
            false
        }
    }
}
