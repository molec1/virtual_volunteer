package com.virtualvolunteer.app.domain.face

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.face.Face
import kotlin.math.max

/**
 * Expands ML Kit face bounds by a margin on each side (relative to face width/height), then clamps
 * to the bitmap so crops stay valid.
 */
object FaceCropBounds {

    /** Fraction of face width/height added on each side (e.g. 0.15f ≈ 15% per side). */
    const val DEFAULT_MARGIN_PER_SIDE = 0.15f

    fun expandFaceRect(
        raw: Rect,
        bitmapWidth: Int,
        bitmapHeight: Int,
        marginPerSide: Float = DEFAULT_MARGIN_PER_SIDE,
    ): Rect {
        val padX = max(1, (raw.width() * marginPerSide).toInt())
        val padY = max(1, (raw.height() * marginPerSide).toInt())
        var left = raw.left - padX
        var top = raw.top - padY
        var right = raw.right + padX
        var bottom = raw.bottom + padY
        left = left.coerceIn(0, max(0, bitmapWidth - 1))
        top = top.coerceIn(0, max(0, bitmapHeight - 1))
        right = right.coerceIn(left + 1, bitmapWidth)
        bottom = bottom.coerceIn(top + 1, bitmapHeight)
        return Rect(left, top, right, bottom)
    }

    fun cropBitmap(bitmap: Bitmap, rect: Rect): Bitmap? {
        val w = rect.width()
        val h = rect.height()
        if (w <= 0 || h <= 0) return null
        if (rect.left < 0 || rect.top < 0 || rect.right > bitmap.width || rect.bottom > bitmap.height) {
            return null
        }
        return Bitmap.createBitmap(bitmap, rect.left, rect.top, w, h)
    }

    fun cropFromFace(
        bitmap: Bitmap,
        face: Face,
        marginPerSide: Float = DEFAULT_MARGIN_PER_SIDE,
    ): Bitmap? {
        val expanded = expandFaceRect(Rect(face.boundingBox), bitmap.width, bitmap.height, marginPerSide)
        return cropBitmap(bitmap, expanded)
    }
}
