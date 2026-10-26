package com.tanuh.edgesdk.preprocess

import android.graphics.Bitmap
import com.tanuh.edgesdk.config.ImageInputSpec
import com.tanuh.edgesdk.config.StepSpec
import com.tanuh.edgesdk.config.optDoubleArray
import com.tanuh.edgesdk.config.optIntPair
import com.tanuh.edgesdk.engine.InputTensor
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Loads the trimmed OpenCV native library once (same library name avni's OpenCVUtils loads). */
object OpenCvRuntime {
    @Volatile private var loaded = false
    @Synchronized fun ensureLoaded() {
        if (loaded) return
        try {
            System.loadLibrary("opencv_java4")
        } catch (e: UnsatisfiedLinkError) {
            // The trimmed AAR links libc++_shared.so dynamically but only bundles it for x86. The host
            // app must ship it for the other ABIs (TANUHDemo: app/src/main/jniLibs/<abi>/libc++_shared.so).
            throw IllegalStateException("OpenCV native library failed to load: ${e.message}. " +
                "Is libc++_shared.so packaged for this ABI?", e)
        }
        loaded = true
    }
}

/** The model-ready tensor plus what it took to make it. */
class TensorResult(
    /** Model input name from the pipeline's tensor spec (null = bind by position). */
    val name: String?,
    val data: FloatArray,
    val shape: LongArray,
    val layout: String,
    val channelOrder: String,
    /** Last 8-bit state of the image inside the pipeline (what the model "sees"), for display. */
    val preview: Bitmap?,
    val stepTimingsMs: List<Pair<String, Double>>,
    val notes: List<String>,
) {
    fun toInput(): InputTensor = InputTensor.float32(name, data, shape)
}

/**
 * The `image` input processor: JSON-driven preprocessing on OpenCV (trimmed 4.14 AAR: core + imgproc + imgcodecs).
 *
 * The image travels through the steps as a 3-channel OpenCV Mat in **BGR** order (OpenCV's
 * native order, same as cv2.imread in the QC notebook). Steps run in the order listed in
 * the pipeline's `input.steps`; each step reads only its own keys. Supported ops:
 *
 * | op              | params                                                                 |
 * |-----------------|------------------------------------------------------------------------|
 * | `resize`        | size [w,h]; mode stretch / letterbox / short_side / long_side;          |
 * |                 | interpolation nearest / linear / cubic / area / lanczos; pad_value      |
 * | `center_crop`   | size [w,h]                                                             |
 * | `gaussian_blur` | ksize (odd int), sigma                                                 |
 * | `gray_world`    | mean_target (per-channel mean -> target; mean taken over whole image)   |
 * | `clip`          | min, max                                                               |
 * | `round`         | decimals (round-half-to-even, float32, like np.round)                   |
 * | `to_uint8`      | (none) - rint + saturate, like cv2.convertScaleAbs                     |
 * | `scale`         | divide_by  or  multiply  (converts to float32 first)                    |
 * | `normalize`     | mean [..], std [..], order RGB/BGR (order the mean/std are listed in)    |
 *
 * After the steps, the pipeline's single `tensors[0]` entry decides layout (CHW/HWC), channel order
 * (RGB/BGR) and the expected shape.
 *
 * Adding an op = one more branch in [applyStep]. Native Mats are released eagerly; nothing
 * full-resolution is copied onto the JVM heap (unlike Bitmap.getPixels).
 */
class OpenCvPreprocessor(private val spec: ImageInputSpec) {

    init {
        OpenCvRuntime.ensureLoaded()
        val unknown = spec.steps.map { it.op }.filterNot { it in SUPPORTED_OPS }
        require(unknown.isEmpty()) { "Unsupported preprocessing op(s) $unknown. Supported: $SUPPORTED_OPS" }
    }

    fun run(bitmap: Bitmap, wantPreview: Boolean = true): TensorResult {
        val timings = ArrayList<Pair<String, Double>>()
        val notes = ArrayList<String>()
        var t0 = System.nanoTime()

        var cur = Mat()
        Mat().also { rgba ->
            Utils.bitmapToMat(bitmap, rgba)                     // CV_8UC4, RGBA
            Imgproc.cvtColor(rgba, cur, Imgproc.COLOR_RGBA2BGR)
            rgba.release()
        }
        timings += "bitmap->Mat ${cur.cols()}x${cur.rows()}" to ms(t0)

        var preview: Mat? = null
        try {
            for ((index, step) in spec.steps.withIndex()) {
                t0 = System.nanoTime()
                val next = applyStep(cur, step, notes)
                if (next !== cur) cur.release()
                cur = next
                timings += "${index + 1}. ${step.op} -> ${describe(cur)}" to ms(t0)
                if (wantPreview && cur.type() == CvType.CV_8UC3 && cur.cols() * cur.rows() <= MAX_PREVIEW_PIXELS) {
                    preview?.release(); preview = cur.clone()
                }
            }

            t0 = System.nanoTime()
            if (cur.depth() != CvType.CV_32F) {
                notes += "Pipeline ended on ${describe(cur)}; converted to float32 without scaling. Add a 'scale' step if the model expects [0,1]."
                val f = Mat(); cur.convertTo(f, CvType.CV_32FC3); cur.release(); cur = f
            }
            if (!cur.isContinuous) { val c = cur.clone(); cur.release(); cur = c }
            val h = cur.rows(); val w = cur.cols()
            val hwcBgr = FloatArray(h * w * 3)
            cur.get(0, 0, hwcBgr)
            val data = toTensorLayout(hwcBgr, h, w, spec.layout, spec.channelOrder)
            val inferred = if (spec.layout == "CHW") longArrayOf(1, 3, h.toLong(), w.toLong()) else longArrayOf(1, h.toLong(), w.toLong(), 3)
            val shape = spec.tensor.shape?.also {
                require(it.fold(1L) { a, b -> a * b } == data.size.toLong()) {
                    "tensors[0].shape ${it.contentToString()} does not match pipeline output ${inferred.contentToString()}"
                }
            } ?: inferred
            timings += "tensor ${spec.layout}/${spec.channelOrder} ${shape.contentToString()}" to ms(t0)

            val previewBitmap = preview?.let { toBitmap(it) }
            return TensorResult(spec.tensor.name, data, shape, spec.layout, spec.channelOrder, previewBitmap, timings, notes)
        } finally {
            cur.release()
            preview?.release()
        }
    }

    private fun applyStep(src: Mat, step: StepSpec, notes: MutableList<String>): Mat {
        val p = step.params
        return when (step.op) {
            "resize" -> resize(src, p.optIntPair("size", 256, 256), p.optString("mode", "stretch").lowercase(),
                interpolation(p.optString("interpolation", "linear")), p.optDouble("pad_value", 0.0))

            "center_crop" -> {
                val (w, h) = p.optIntPair("size", 224, 224)
                require(w <= src.cols() && h <= src.rows()) { "center_crop ${w}x$h larger than image ${src.cols()}x${src.rows()}" }
                src.submat(Rect((src.cols() - w) / 2, (src.rows() - h) / 2, w, h)).clone()
            }

            "gaussian_blur" -> {
                val k = p.optInt("ksize", 3).let { if (it % 2 == 0) it + 1 else it }
                Mat().also { Imgproc.GaussianBlur(src, it, Size(k.toDouble(), k.toDouble()), p.optDouble("sigma", 0.0)) }
            }

            "gray_world" -> {
                val target = p.optDouble("mean_target", 128.0)
                val m = Core.mean(src)                           // per-channel mean over the whole image (padding included)
                val f = toFloat(src)
                val s = Scalar(
                    if (m.`val`[0] != 0.0) target / m.`val`[0] else 1.0,
                    if (m.`val`[1] != 0.0) target / m.`val`[1] else 1.0,
                    if (m.`val`[2] != 0.0) target / m.`val`[2] else 1.0,
                )
                notes += "gray_world mean B=%.3f G=%.3f R=%.3f scale B=%.5f G=%.5f R=%.5f".format(
                    m.`val`[0], m.`val`[1], m.`val`[2], s.`val`[0], s.`val`[1], s.`val`[2])
                Core.multiply(f, s, f)                           // float32 * float32, as numpy does with a float32 array
                f
            }

            "clip" -> {
                val out = src.clone()
                Core.min(out, Scalar.all(p.optDouble("max", 255.0)), out)
                Core.max(out, Scalar.all(p.optDouble("min", 0.0)), out)
                out
            }

            "round" -> {
                val factor = Math.pow(10.0, p.optInt("decimals", 1).toDouble())
                val f = toFloat(src)
                if (factor != 1.0) Core.multiply(f, Scalar.all(factor), f)
                val i32 = Mat(); f.convertTo(i32, CvType.CV_32SC3)   // cvRound = round-half-to-even
                i32.convertTo(f, CvType.CV_32FC3); i32.release()
                if (factor != 1.0) Core.divide(f, Scalar.all(factor), f)
                f
            }

            "to_uint8" -> Mat().also { src.convertTo(it, CvType.CV_8UC3) }   // rint + saturate to [0,255]

            "scale" -> {
                val f = toFloat(src)
                when {
                    p.has("divide_by") -> Core.divide(f, Scalar.all(p.getDouble("divide_by")), f)
                    p.has("multiply") -> Core.multiply(f, Scalar.all(p.getDouble("multiply")), f)
                    else -> throw IllegalArgumentException("scale step needs 'divide_by' or 'multiply'")
                }
                f
            }

            "normalize" -> {
                val f = toFloat(src)
                val order = p.optString("order", "RGB").uppercase()
                fun bgr(a: DoubleArray?, def: Double): Scalar {
                    val v = a ?: doubleArrayOf(def, def, def)
                    require(v.size == 3) { "normalize mean/std need 3 values" }
                    return if (order == "RGB") Scalar(v[2], v[1], v[0]) else Scalar(v[0], v[1], v[2])
                }
                Core.subtract(f, bgr(p.optDoubleArray("mean"), 0.0), f)
                Core.divide(f, bgr(p.optDoubleArray("std"), 1.0), f)
                f
            }

            else -> throw IllegalArgumentException("Unsupported op '${step.op}'")
        }
    }

    private fun resize(src: Mat, size: Pair<Int, Int>, mode: String, interp: Int, pad: Double): Mat {
        val (tw, th) = size
        val sw = src.cols(); val sh = src.rows()
        fun scaled(w: Int, h: Int): Mat =
            if (w == sw && h == sh) src.clone() else Mat().also { Imgproc.resize(src, it, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, interp) }
        return when (mode) {
            "stretch" -> scaled(tw, th)
            "letterbox" -> {
                // Same geometry as avni PixelOps.letterboxGeometry / the QC notebook (Python round = rint).
                val s = minOf(tw.toDouble() / sw, th.toDouble() / sh)
                val nw = Math.rint(sw * s).toInt().coerceIn(1, tw)
                val nh = Math.rint(sh * s).toInt().coerceIn(1, th)
                val fitted = scaled(nw, nh)
                val canvas = Mat(th, tw, src.type(), Scalar.all(pad))
                val roi = canvas.submat(Rect((tw - nw) / 2, (th - nh) / 2, nw, nh))
                fitted.copyTo(roi); roi.release(); fitted.release()
                canvas
            }
            "short_side", "long_side" -> {
                val target = tw.toDouble()
                val s = if (mode == "short_side") target / minOf(sw, sh) else target / maxOf(sw, sh)
                scaled(Math.rint(sw * s).toInt().coerceAtLeast(1), Math.rint(sh * s).toInt().coerceAtLeast(1))
            }
            else -> throw IllegalArgumentException("resize.mode must be stretch | letterbox | short_side | long_side, got '$mode'")
        }
    }

    private fun toFloat(src: Mat): Mat =
        if (src.depth() == CvType.CV_32F) src.clone() else Mat().also { src.convertTo(it, CvType.CV_32FC3) }

    private fun interpolation(name: String): Int = when (name.lowercase()) {
        "nearest" -> Imgproc.INTER_NEAREST
        "linear", "bilinear" -> Imgproc.INTER_LINEAR
        "cubic", "bicubic" -> Imgproc.INTER_CUBIC
        "area" -> Imgproc.INTER_AREA
        "lanczos", "lanczos4" -> Imgproc.INTER_LANCZOS4
        else -> throw IllegalArgumentException("Unknown interpolation '$name'")
    }

    private fun toBitmap(bgr8: Mat): Bitmap {
        val rgba = Mat()
        Imgproc.cvtColor(bgr8, rgba, Imgproc.COLOR_BGR2RGBA)
        val bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bmp)
        rgba.release()
        return bmp
    }

    private fun describe(m: Mat): String {
        val depth = when (m.depth()) { CvType.CV_8U -> "u8"; CvType.CV_32F -> "f32"; CvType.CV_32S -> "i32"; else -> "d${m.depth()}" }
        return "${m.cols()}x${m.rows()} $depth"
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1e6

    companion object {
        val SUPPORTED_OPS = setOf("resize", "center_crop", "gaussian_blur", "gray_world", "clip", "round", "to_uint8", "scale", "normalize")
        private const val MAX_PREVIEW_PIXELS = 1024 * 1024

        /** Interleaved HWC/BGR floats -> requested layout + channel order. Pure Kotlin (unit-tested). */
        fun toTensorLayout(hwcBgr: FloatArray, h: Int, w: Int, layout: String, channelOrder: String): FloatArray {
            val plane = h * w
            val srcIdx = if (channelOrder == "BGR") intArrayOf(0, 1, 2) else intArrayOf(2, 1, 0)
            val out = FloatArray(plane * 3)
            if (layout == "CHW") {
                for (c in 0 until 3) { val s = srcIdx[c]; val base = c * plane; for (i in 0 until plane) out[base + i] = hwcBgr[i * 3 + s] }
            } else {
                for (i in 0 until plane) for (c in 0 until 3) out[i * 3 + c] = hwcBgr[i * 3 + srcIdx[c]]
            }
            return out
        }
    }
}
