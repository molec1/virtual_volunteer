package com.virtualvolunteer.regression.jvm

import ai.djl.tflite.engine.LibUtils
import com.virtualvolunteer.app.domain.face.EmbeddingMath
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * JVM TFLite face embedder mirroring production [com.virtualvolunteer.app.domain.face.TfliteFaceEmbedder]
 * tensor layout and NHWC preprocessing (FLOAT32 vs UINT8).
 *
 * **EXIF:** [JvmBufferedImageExif.readApplyingExifOrientation] is used for file paths so JPEG orientation
 * matches production [com.virtualvolunteer.app.domain.face.OrientedPhotoBitmap].
 *
 * **Resize:** uses [Graphics2D] bilinear scaling on a [BufferedImage]. This may differ slightly from
 * Android [android.graphics.Bitmap.createScaledBitmap] (filter=true); add an on-device parity check later
 * if pixel-level alignment becomes important.
 */
class LocalFaceCropEmbedder(
    modelFile: File,
) : AutoCloseable {

    private val modelRaf = RandomAccessFile(modelFile, "r")
    private val modelBuffer =
        modelRaf.channel.map(FileChannel.MapMode.READ_ONLY, 0, modelRaf.length())
    private val interpreter: Interpreter
    private val interpreterLock = Any()
    private var closed = false
    private val inputH: Int
    private val inputW: Int
    private val inputChannels: Int
    private val inputFloat: Boolean
    private val outputShape: IntArray

    init {
        // Required on JVM: loads libtensorflowlite_jni from the DJL tflite-native-cpu classified JAR on the classpath.
        LibUtils.loadLibrary()
        val options = Interpreter.Options().apply { setNumThreads(1) }
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

    fun embed(faceCropFile: File): FloatArray {
        val upright = JvmBufferedImageExif.readApplyingExifOrientation(faceCropFile)
        return embed(upright)
    }

    fun embed(faceCrop: BufferedImage): FloatArray {
        val scaled = resizeBilinear(faceCrop, inputW, inputH)
        try {
            val outputBuffer = allocateOutputBuffer()
            if (inputFloat) {
                val input = imageToFloatBufferNhwc(scaled)
                synchronized(interpreterLock) {
                    check(!closed) { "LocalFaceCropEmbedder is closed" }
                    interpreter.run(input, outputBuffer)
                }
            } else {
                val input = imageToUint8BufferNhwc(scaled)
                synchronized(interpreterLock) {
                    check(!closed) { "LocalFaceCropEmbedder is closed" }
                    interpreter.run(input, outputBuffer)
                }
            }
            val flat = flattenEmbeddingOutput(outputBuffer)
            return EmbeddingMath.l2Normalize(flat)
        } finally {
            if (scaled !== faceCrop) {
                scaled.flush()
            }
        }
    }

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

    override fun close() {
        synchronized(interpreterLock) {
            if (closed) return
            closed = true
            interpreter.close()
        }
        modelRaf.close()
    }

    private fun resizeBilinear(src: BufferedImage, w: Int, h: Int): BufferedImage {
        if (src.width == w && src.height == h) return src
        val dst = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g2 = dst.createGraphics() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g2.drawImage(src, 0, 0, w, h, null)
        g2.dispose()
        return dst
    }

    private fun imageToFloatBufferNhwc(img: BufferedImage): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(4 * inputH * inputW * inputChannels)
        buf.order(ByteOrder.nativeOrder())
        for (y in 0 until inputH) {
            for (x in 0 until inputW) {
                val p = img.getRGB(x, y)
                val r = ((p shr 16) and 0xFF) / 255f
                val g = ((p shr 8) and 0xFF) / 255f
                val b = (p and 0xFF) / 255f
                buf.putFloat((r - 0.5f) * 2f)
                buf.putFloat((g - 0.5f) * 2f)
                buf.putFloat((b - 0.5f) * 2f)
            }
        }
        buf.rewind()
        return buf
    }

    private fun imageToUint8BufferNhwc(img: BufferedImage): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(inputH * inputW * inputChannels)
        buf.order(ByteOrder.nativeOrder())
        for (y in 0 until inputH) {
            for (x in 0 until inputW) {
                val p = img.getRGB(x, y)
                buf.put(((p shr 16) and 0xFF).toByte())
                buf.put(((p shr 8) and 0xFF).toByte())
                buf.put((p and 0xFF).toByte())
            }
        }
        buf.rewind()
        return buf
    }
}
