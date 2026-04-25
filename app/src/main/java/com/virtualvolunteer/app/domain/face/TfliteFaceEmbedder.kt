package com.virtualvolunteer.app.domain.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Loads a TFLite face-recognition model from assets ([ASSET_PATH]) and outputs an L2-normalized
 * embedding. Input/output tensor shapes are read at runtime.
 *
 * Preprocessing: resize to model input H×W, RGB scaled to approximately [-1, 1] (FaceNet-style).
 * UINT8-input models receive packed RGB bytes per pixel.
 */
class TfliteFaceEmbedder(
    context: Context,
    private val assetPath: String = ASSET_PATH,
) : FaceEmbedder {

    private val interpreter: Interpreter
    private val interpreterLock = Any()
    private var closed: Boolean = false
    private val inputH: Int
    private val inputW: Int
    private val inputChannels: Int
    private val inputFloat: Boolean
    /** Shape of output tensor 0 (e.g. [128] or [1, 128]); TFLite requires matching Java container rank. */
    private val outputShape: IntArray

    init {
        val modelBuffer = FileUtil.loadMappedFile(context.applicationContext, assetPath)
        val options = Interpreter.Options().apply {
            setNumThreads(1)
            setUseNNAPI(false)
        }
        interpreter = Interpreter(modelBuffer, options)

        val inTensor: Tensor = interpreter.getInputTensor(0)
        val inShape = inTensor.shape()
        require(inShape.size == 4) { "Expected NHWC input, got ${inShape.contentToString()}" }
        inputH = inShape[1]
        inputW = inShape[2]
        inputChannels = inShape[3]
        inputFloat = inTensor.dataType() == DataType.FLOAT32
        require(inputChannels == 3) { "Expected 3 input channels, got $inputChannels" }

        val outTensor: Tensor = interpreter.getOutputTensor(0)
        outputShape = outTensor.shape().copyOf()
    }

    override fun embed(faceCrop: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(faceCrop, inputW, inputH, true)
        try {
            val outputBuffer = allocateOutputBuffer()
            if (inputFloat) {
                val input = bitmapToFloatBufferNhwc(scaled)
                synchronized(interpreterLock) {
                    check(!closed) { "TfliteFaceEmbedder is closed" }
                    interpreter.run(input, outputBuffer)
                }
            } else {
                val input = bitmapToUint8BufferNhwc(scaled)
                synchronized(interpreterLock) {
                    check(!closed) { "TfliteFaceEmbedder is closed" }
                    interpreter.run(input, outputBuffer)
                }
            }
            val flat = flattenEmbeddingOutput(outputBuffer)
            return EmbeddingMath.l2Normalize(flat)
        } finally {
            if (scaled !== faceCrop) scaled.recycle()
        }
    }

    /** [1, D] and [D] outputs need different Java types for [Interpreter.run]. */
    private fun allocateOutputBuffer(): Any =
        when (outputShape.size) {
            1 -> FloatArray(outputShape[0])
            2 -> Array(outputShape[0]) { FloatArray(outputShape[1]) }
            else -> error(
                "Unsupported embedding output rank ${outputShape.size}: ${outputShape.contentToString()}",
            )
        }

    private fun flattenEmbeddingOutput(buffer: Any): FloatArray =
        when (buffer) {
            is FloatArray -> buffer
            is Array<*> -> {
                val row0 = buffer[0]
                require(row0 is FloatArray) { "Expected FloatArray row, got $row0" }
                row0.copyOf()
            }
            else -> error("Unexpected output buffer type ${buffer.javaClass}")
        }

    fun close() {
        synchronized(interpreterLock) {
            if (closed) return
            closed = true
            interpreter.close()
        }
    }

    private fun bitmapToFloatBufferNhwc(bitmap: Bitmap): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(4 * inputH * inputW * inputChannels)
        buf.order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputW * inputH)
        bitmap.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)
        for (p in pixels) {
            val r = Color.red(p) / 255f
            val g = Color.green(p) / 255f
            val b = Color.blue(p) / 255f
            buf.putFloat((r - 0.5f) * 2f)
            buf.putFloat((g - 0.5f) * 2f)
            buf.putFloat((b - 0.5f) * 2f)
        }
        buf.rewind()
        return buf
    }

    private fun bitmapToUint8BufferNhwc(bitmap: Bitmap): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(inputH * inputW * inputChannels)
        buf.order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputW * inputH)
        bitmap.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)
        for (p in pixels) {
            buf.put((Color.red(p) and 0xFF).toByte())
            buf.put((Color.green(p) and 0xFF).toByte())
            buf.put((Color.blue(p) and 0xFF).toByte())
        }
        buf.rewind()
        return buf
    }

    companion object {
        const val ASSET_PATH = "models/face_embedding.tflite"
    }
}
