package com.virtualvolunteer.app.domain.face

import android.graphics.Bitmap

/**
 * On-device face embedding extraction (typically from a TFLite recognition model).
 */
fun interface FaceEmbedder {
    /** Returns an L2-normalized embedding vector. */
    fun embed(faceCrop: Bitmap): FloatArray
}
