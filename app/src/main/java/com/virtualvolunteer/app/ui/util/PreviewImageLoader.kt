package com.virtualvolunteer.app.ui.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

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
}
