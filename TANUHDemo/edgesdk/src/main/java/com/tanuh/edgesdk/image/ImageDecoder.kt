package com.tanuh.edgesdk.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.tanuh.edgesdk.config.ImageSpec
import java.io.File
import java.io.InputStream

/**
 * Image source -> upright ARGB_8888 Bitmap.
 *
 * Mirrors avni-client's `ImageDecoding.decodeUpright` (BitmapFactory + all 8 EXIF orientations),
 * so pixels match what the avni pipeline sees. Works for gallery `content://` URIs (photo picker)
 * and plain file paths. On API 26+ Bitmap pixels live in native memory, not the Java heap.
 */
object ImageDecoder {

    data class Decoded(val bitmap: Bitmap, val exifOrientation: Int, val sourceWidth: Int, val sourceHeight: Int, val sampleSize: Int)

    fun decode(context: Context, uri: Uri, spec: ImageSpec): Decoded =
        decode({ context.contentResolver.openInputStream(uri) ?: error("Cannot open $uri") }, spec, uri.toString())

    fun decode(file: File, spec: ImageSpec): Decoded = decode({ file.inputStream() }, spec, file.path)

    private fun decode(open: () -> InputStream, spec: ImageSpec, label: String): Decoded {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Not a decodable image: $label" }

        var sample = 1
        if (spec.maxDecodeSide > 0) {
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= spec.maxDecodeSide) sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sample
        }
        val decoded = open().use { BitmapFactory.decodeStream(it, null, opts) } ?: error("Decode failed: $label")

        val orientation = if (spec.applyExif) {
            runCatching { open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } else ExifInterface.ORIENTATION_NORMAL

        return Decoded(applyOrientation(decoded, orientation), orientation, bounds.outWidth, bounds.outHeight, sample)
    }

    /** Same matrix table as avni's ImageDecoding.applyExifOrientation. */
    fun applyOrientation(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return src
        }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (rotated !== src) src.recycle()
        return rotated
    }
}
