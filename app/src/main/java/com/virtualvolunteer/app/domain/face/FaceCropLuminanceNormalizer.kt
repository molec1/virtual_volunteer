package com.virtualvolunteer.app.domain.face

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Optional mild luminance adjustment on expanded face crops **before** model resize/input.
 * Uses per-channel gamma only (no histogram equalization).
 */
object FaceCropLuminanceNormalizer {

    /** BT.601 luma; mean over pixels in approximately [0, 255]. */
    fun meanLuminance(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return 0f
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var sum = 0.0
        for (p in pixels) {
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            sum += 0.299 * r + 0.587 * g + 0.114 * b
        }
        return (sum / pixels.size).toFloat()
    }

    /**
     * @return [bitmap] if mean luminance is in a neutral band; otherwise a new ARGB bitmap with mild gamma.
     */
    fun normalizeIfNeeded(bitmap: Bitmap): Bitmap {
        val mean = meanLuminance(bitmap)
        return when {
            mean < DARK_MEAN_THRESHOLD -> applyPerChannelGamma(bitmap, DARK_GAMMA_EXPONENT)
            mean > BRIGHT_MEAN_THRESHOLD -> applyPerChannelGamma(bitmap, BRIGHT_GAMMA_EXPONENT)
            else -> bitmap
        }
    }

    private fun applyPerChannelGamma(bitmap: Bitmap, exponent: Float): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val inv = 1f / 255f
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = Color.alpha(p)
            fun ch(c: Int): Int =
                ((c * inv).pow(exponent) * 255f).roundToInt().coerceIn(0, 255)
            val r = ch(Color.red(p))
            val g = ch(Color.green(p))
            val b = ch(Color.blue(p))
            pixels[i] = Color.argb(a, r, g, b)
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private const val DARK_MEAN_THRESHOLD = 58f
    private const val BRIGHT_MEAN_THRESHOLD = 198f
    /** Exponent below 1.0 brightens (mild lift for very dark crops). */
    private const val DARK_GAMMA_EXPONENT = 0.82f
    /** Exponent above 1.0 darkens (mild compression for very bright crops). */
    private const val BRIGHT_GAMMA_EXPONENT = 1.12f
}
