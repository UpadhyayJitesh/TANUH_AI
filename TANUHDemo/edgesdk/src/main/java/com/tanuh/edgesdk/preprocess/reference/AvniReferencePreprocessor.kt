package com.tanuh.edgesdk.preprocess.reference

import android.graphics.Bitmap
import android.os.Build
import org.json.JSONObject
import java.nio.ByteBuffer

/**
 * avni-client's `mean-target-bgr-rounded` preprocessor (Preprocessors.kt, Oct 2026 QC version),
 * ported without React Native / BuildConfig dependencies. Same params JSON as avni's
 * tools/edge-model/tanuh-ensemble-override.json `input.params`.
 *
 * Used only as a parity oracle: the SDK's OpenCV step pipeline is compared against this
 * output element by element (diagnostics.compare_with_reference = true).
 *
 * Note: like avni, this path calls Bitmap.getPixels on the FULL-resolution photo, i.e. a
 * width*height IntArray on the JVM heap (~48 MB for a 12 MP photo). The memory report shows it.
 */
object AvniReferencePreprocessor {

    data class Result(val data: FloatArray, val shape: LongArray)

    fun preprocess(bitmap: Bitmap, params: JSONObject): Result {
        val size = params.optJSONArray("size")
        val w = size?.optInt(0, 256) ?: 256
        val h = size?.optInt(1, w) ?: 256
        val interpolation = params.optString("interpolation", "bilinear")
        val resizeMode = params.optString("resize_mode", "stretch")
        val padValue = params.optInt("pad_value", 0)
        val ignoreColorProfile = params.optBoolean("ignore_color_profile", false)
        val rounding = params.optString("rounding", "legacy")
        val p = MeanTargetMath.Params(
            meanTarget = params.optDouble("mean_target", 128.0),
            roundDecimals = params.optInt("round_decimals", 1),
            uint8Cast = params.optBoolean("uint8_cast", true),
            uint8Round = params.optBoolean("uint8_round", false),
            numpyRounding = rounding == "numpy",
            scale = params.optDouble("scale", 1.0 / 255.0).toFloat(),
            channelOrder = params.optString("channel_order", "BGR"),
            layout = params.optString("layout", "CHW"),
        )
        val canvas = resizeToCanvas(bitmap, w, h, interpolation, resizeMode, padValue, ignoreColorProfile)
        return Result(MeanTargetMath.toTensor(canvas, p), longArrayOf(1, 3, h.toLong(), w.toLong()))
    }

    /** avni [PE-138]: with ignoreColorProfile, a non-sRGB (e.g. Display P3) bitmap's raw stored values are used, like cv2.imread. */
    private fun Bitmap.toArgbImage(ignoreColorProfile: Boolean = false): ArgbImage {
        if (ignoreColorProfile && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && config == Bitmap.Config.ARGB_8888) {
            val cs = colorSpace
            if (cs != null && !cs.isSrgb) {
                val buf = ByteBuffer.allocate(rowBytes * height)
                copyPixelsToBuffer(buf)
                return ArgbImage(width, height, PixelOps.argbFromRgba8888(buf.array(), width, height, rowBytes))
            }
        }
        val px = IntArray(width * height)
        getPixels(px, 0, width, 0, 0, width, height)
        return ArgbImage(width, height, px)
    }

    private fun resize(src: Bitmap, w: Int, h: Int, interpolation: String, icp: Boolean): ArgbImage {
        if (src.width == w && src.height == h) return src.toArgbImage(icp)
        return when (interpolation) {
            "cubic" -> PixelOps.resizeCubicCv(src.toArgbImage(icp), w, h)
            "nearest" -> Bitmap.createScaledBitmap(src, w, h, false).let { s -> s.toArgbImage(icp).also { if (s !== src) s.recycle() } }
            else -> Bitmap.createScaledBitmap(src, w, h, true).let { s -> s.toArgbImage(icp).also { if (s !== src) s.recycle() } }
        }
    }

    private fun resizeToCanvas(src: Bitmap, w: Int, h: Int, interpolation: String, mode: String, padValue: Int, icp: Boolean): ArgbImage =
        when (mode) {
            "stretch" -> resize(src, w, h, interpolation, icp)
            "letterbox" -> {
                val g = PixelOps.letterboxGeometry(src.width, src.height, w, h)
                val fitted = resize(src, g.newW, g.newH, interpolation, icp)
                val pad = padValue.coerceIn(0, 255)
                PixelOps.pasteOnCanvas(fitted, w, h, g.offsetX, g.offsetY, (pad shl 16) or (pad shl 8) or pad)
            }
            else -> throw IllegalArgumentException("Unsupported resize_mode='$mode'")
        }
}
