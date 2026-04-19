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
}
