package com.virtualvolunteer.regression.jvm

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Applies TIFF EXIF orientation (tags 1–8) to a [BufferedImage], matching the intent of
 * [com.virtualvolunteer.app.domain.face.OrientedPhotoBitmap] on Android so JVM regression sees the same
 * upright pixels as production when decoding JPEG/PNG files.
 */
object JvmBufferedImageExif {

    fun readApplyingExifOrientation(file: File): BufferedImage {
        val raw = ImageIO.read(file)
            ?: throw IllegalStateException("ImageIO.read returned null for ${file.absolutePath}")
        val tag = readExifOrientationTag(file) ?: return raw
        return applyOrientation(raw, tag)
    }

    private fun readExifOrientationTag(file: File): Int? {
        return try {
            val meta = ImageMetadataReader.readMetadata(file)
            val dir = meta.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
            if (dir == null || !dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                null
            } else {
                dir.getInt(ExifIFD0Directory.TAG_ORIENTATION)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * [orientation] uses TIFF/EXIF values 1–8 (same as Android [androidx.exifinterface.media.ExifInterface]).
     */
    fun applyOrientation(src: BufferedImage, orientation: Int): BufferedImage {
        if (orientation < 2 || orientation > 8) return src
        val w = src.width
        val h = src.height
        val (dw, dh) = when (orientation) {
            5, 6, 7, 8 -> h to w
            else -> w to h
        }
        val dst = BufferedImage(dw, dh, BufferedImage.TYPE_INT_ARGB)
        val g = dst.createGraphics() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        val at = AffineTransform()
        when (orientation) {
            2 -> {
                at.scale(-1.0, 1.0)
                at.translate(-w.toDouble(), 0.0)
            }
            3 -> {
                at.translate(w.toDouble(), h.toDouble())
                at.rotate(Math.PI)
            }
            4 -> {
                at.scale(1.0, -1.0)
                at.translate(0.0, -h.toDouble())
            }
            5 -> {
                at.translate(h.toDouble(), 0.0)
                at.rotate(Math.PI / 2)
                at.scale(-1.0, 1.0)
            }
            6 -> {
                at.translate(h.toDouble(), 0.0)
                at.rotate(Math.PI / 2)
            }
            7 -> {
                at.translate(h.toDouble(), 0.0)
                at.rotate(Math.PI / 2)
                at.translate(w.toDouble(), h.toDouble())
                at.rotate(Math.PI)
                at.scale(-1.0, 1.0)
            }
            8 -> {
                at.translate(0.0, w.toDouble())
                at.rotate(-Math.PI / 2)
            }
            else -> return src
        }
        g.drawImage(src, at, null)
        g.dispose()
        return dst
    }
}
