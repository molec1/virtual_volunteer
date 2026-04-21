package com.virtualvolunteer.app.ui.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.virtualvolunteer.app.domain.face.OrientedPhotoBitmap
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Loads a downscaled bitmap for small dashboard previews (memory-safe).
 */
object PreviewImageLoader {

    fun loadThumbnail(path: String, maxSidePx: Int = 720): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / sample > maxSidePx || h / sample > maxSidePx) {
            sample *= 2
        }
        val load = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(path, load)
    }

    /**
     * Decode with EXIF orientation applied, then scale so the longest side is at most [maxSidePx].
     * Use for full-frame previews so thumbnails match how the photo is viewed.
     */
    fun loadThumbnailOriented(path: String, maxSidePx: Int = 720): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        val decoded = OrientedPhotoBitmap.decodeApplyingExifOrientation(file) ?: return null
        try {
            val w = decoded.width
            val h = decoded.height
            if (w <= 0 || h <= 0) {
                decoded.recycle()
                return null
            }
            val longest = max(w, h)
            if (longest <= maxSidePx) return decoded
            val scale = maxSidePx.toFloat() / longest
            val nw = (w * scale).roundToInt().coerceAtLeast(1)
            val nh = (h * scale).roundToInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(decoded, nw, nh, true)
            if (scaled != decoded) decoded.recycle()
            return scaled
        } catch (_: OutOfMemoryError) {
            decoded.recycle()
            return null
        }
    }

    /**
     * Like [loadThumbnailOriented], then crops a small margin from each edge before scaling
     * so face thumbnails do not touch the view edge.
     */
    fun loadThumbnailOrientedInset(
        path: String,
        maxSidePx: Int = 720,
        edgeInsetFraction: Float = 0.035f,
    ): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        val decoded = OrientedPhotoBitmap.decodeApplyingExifOrientation(file) ?: return null
        try {
            val w = decoded.width
            val h = decoded.height
            if (w <= 0 || h <= 0) {
                decoded.recycle()
                return null
            }
            val insetX = (w * edgeInsetFraction).roundToInt().coerceIn(0, w / 4)
            val insetY = (h * edgeInsetFraction).roundToInt().coerceIn(0, h / 4)
            val cw = (w - 2 * insetX).coerceAtLeast(1)
            val ch = (h - 2 * insetY).coerceAtLeast(1)
            val cropped = Bitmap.createBitmap(decoded, insetX, insetY, cw, ch)
            if (cropped != decoded) decoded.recycle()
            val longest = max(cropped.width, cropped.height)
            if (longest <= maxSidePx) return cropped
            val scale = maxSidePx.toFloat() / longest
            val nw = (cropped.width * scale).roundToInt().coerceAtLeast(1)
            val nh = (cropped.height * scale).roundToInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(cropped, nw, nh, true)
            if (scaled != cropped) cropped.recycle()
            return scaled
        } catch (_: OutOfMemoryError) {
            decoded.recycle()
            return null
        }
    }
}
