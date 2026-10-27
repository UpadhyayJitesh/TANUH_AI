package com.tanuh.edgesdk.diag

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Parity trace, format v1.
 *
 * THE SAME FILE LIVES IN BOTH CODEBASES (only the package line differs):
 *   avni-client  packages/openchs-android/android/app/src/main/java/com/openchsclient/diag/ParityTrace.kt
 *   TANUHDemo    edgesdk/src/main/java/com/tanuh/edgesdk/diag/ParityTrace.kt
 * Keep them identical, so both apps hash and format every intermediate value the same way.
 *
 * Each inference call writes one record of plain text lines to
 * `<external files>/parity-traces/<app>-<launch time>.txt` and to logcat (tag "ParityTrace"):
 *
 *   BEGIN app=avni call=1 at=...
 *   IMAGE name=1000233116.jpg
 *   INPUT bytes=3407160 sha256=...            source file bytes, proves both apps got the same file
 *   DECODE w=3072 h=3072 exif=6 rgba_sha256=...  upright decoded bitmap
 *   CANVAS w=256 h=256 bgr_sha256=... mean_b=... mean_g=... mean_r=...   after resize/letterbox (8-bit)
 *   GRAYWORLD mean_b=... scale_b=... ...       values the gray-world step actually used
 *   TENSOR shape=1x3x256x256 layout=CHW order=BGR f32_sha256=... p0_mean=... ...   model input
 *   MODEL sha256=<plaintext model sha256> logit=... prob=... label=...
 *   TIME stage=... ms=...                      timing only; ignored when comparing
 *   END
 *
 * Numbers use Locale.US and 6 decimals. Hashes cover exact bytes:
 *   rgba  = Bitmap.copyPixelsToBuffer (ARGB_8888 memory order R,G,B,A)
 *   bgr   = interleaved B,G,R bytes, row-major
 *   f32   = model input floats, little-endian IEEE-754
 * Compare two files with TANUHDemo/tools/parity/compare_traces.py.
 */
class ParityTrace private constructor(private val app: String, val file: File) {

    companion object {
        const val VERSION = 1
        private const val TAG = "ParityTrace"

        @Volatile private var instance: ParityTrace? = null
        private val current = ThreadLocal<Record?>()

        /** One trace file per app launch. */
        @Synchronized
        fun get(context: Context, app: String): ParityTrace = instance ?: run {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "parity-traces").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val f = File(dir, "$app-$stamp.txt")
            f.writeText("# parity-trace v$VERSION app=$app device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.SDK_INT} created=$stamp\n")
            Log.i(TAG, "Writing parity trace to ${f.absolutePath}")
            ParityTrace(app, f).also { instance = it }
        }

        /** Record being built on this thread, if tracing is active for the current call. */
        fun currentRecord(): Record? = current.get()

        fun fmt(v: Double): String = String.format(Locale.US, "%.6f", v)

        fun hex(d: ByteArray): String = d.joinToString("") { "%02x".format(it) }

        fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

        /** Streams the input (64 KB chunks); returns byte count and SHA-256. */
        fun sha256(input: InputStream): Pair<Long, String> {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            var total = 0L
            input.use { s -> while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n); total += n } }
            return total to hex(md.digest())
        }

        fun sha256(file: File): String = sha256(file.inputStream()).second

        /** SHA-256 of the bitmap's pixel memory, copied to a direct (native) buffer, not the Java heap. */
        fun sha256Rgba(bitmap: Bitmap): String {
            val buf = ByteBuffer.allocateDirect(bitmap.byteCount)
            bitmap.copyPixelsToBuffer(buf)
            buf.rewind()
            val md = MessageDigest.getInstance("SHA-256")
            md.update(buf)
            return hex(md.digest())
        }

        /** Packed ARGB ints (Bitmap.getPixels / avni ArgbImage) -> interleaved B,G,R bytes. */
        fun bgrFromArgb(pixels: IntArray): ByteArray {
            val out = ByteArray(pixels.size * 3)
            for (i in pixels.indices) {
                val p = pixels[i]
                out[i * 3] = (p and 0xFF).toByte()
                out[i * 3 + 1] = ((p shr 8) and 0xFF).toByte()
                out[i * 3 + 2] = ((p shr 16) and 0xFF).toByte()
            }
            return out
        }

        fun sha256Floats(data: FloatArray): String {
            val bb = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.asFloatBuffer().put(data)
            return sha256(bb.array())
        }
    }

    private var calls = 0

    /** Starts a record for this thread; every hook in the pipeline writes into it until [Record.end]. */
    @Synchronized
    fun begin(): Record = Record(++calls).also { current.set(it) }

    inner class Record(call: Int) {
        private val lines = ArrayList<String>()
        private var ended = false

        init {
            put("BEGIN", "app" to app, "call" to call,
                "at" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date()))
        }

        fun put(kind: String, vararg kv: Pair<String, Any?>) {
            lines += buildString {
                append(kind)
                for ((k, v) in kv) {
                    append(' ').append(k).append('=')
                    append(when (v) {
                        is Double -> fmt(v)
                        is Float -> fmt(v.toDouble())
                        null -> "-"
                        else -> v.toString().replace(' ', '_')
                    })
                }
            }
        }

        fun time(stage: String, ms: Double) = put("TIME", "stage" to stage, "ms" to String.format(Locale.US, "%.1f", ms))

        fun image(name: String) = put("IMAGE", "name" to name)

        fun input(bytes: Long, sha256: String) = put("INPUT", "bytes" to bytes, "sha256" to sha256)

        fun decode(bitmap: Bitmap, exifOrientation: Int) =
            put("DECODE", "w" to bitmap.width, "h" to bitmap.height, "exif" to exifOrientation, "rgba_sha256" to sha256Rgba(bitmap))

        /** The 8-bit canvas after resize/letterbox, as interleaved BGR bytes. */
        fun canvas(width: Int, height: Int, bgr: ByteArray) {
            val n = width * height
            var b = 0L; var g = 0L; var r = 0L
            for (i in 0 until n) {
                b += bgr[i * 3].toInt() and 0xFF
                g += bgr[i * 3 + 1].toInt() and 0xFF
                r += bgr[i * 3 + 2].toInt() and 0xFF
            }
            put("CANVAS", "w" to width, "h" to height, "bgr_sha256" to sha256(bgr),
                "mean_b" to b.toDouble() / n, "mean_g" to g.toDouble() / n, "mean_r" to r.toDouble() / n)
        }

        fun grayWorld(meanB: Double, meanG: Double, meanR: Double, scaleB: Double, scaleG: Double, scaleR: Double) =
            put("GRAYWORLD", "mean_b" to meanB, "mean_g" to meanG, "mean_r" to meanR,
                "scale_b" to scaleB, "scale_g" to scaleG, "scale_r" to scaleR)

        /** Model input tensor; per-plane stats when it is a 3-plane CHW tensor. */
        fun tensor(data: FloatArray, shape: LongArray, layout: String, channelOrder: String) {
            val kv = ArrayList<Pair<String, Any?>>()
            kv += "shape" to shape.joinToString("x")
            kv += "layout" to layout.uppercase()
            kv += "order" to channelOrder.uppercase()
            kv += "f32_sha256" to sha256Floats(data)
            if (layout.equals("CHW", true) && data.size % 3 == 0) {
                val plane = data.size / 3
                for (c in 0 until 3) {
                    var s = 0.0; var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
                    for (i in c * plane until (c + 1) * plane) { val v = data[i]; s += v; if (v < lo) lo = v; if (v > hi) hi = v }
                    kv += "p${c}_mean" to s / plane
                    kv += "p${c}_min" to lo.toDouble()
                    kv += "p${c}_max" to hi.toDouble()
                }
            }
            put("TENSOR", *kv.toTypedArray())
        }

        /** prob is recomputed here (double sigmoid) so both apps print it with the identical formula. */
        fun model(sha256: String, logit: Float, label: String, name: String? = null) {
            val kv = arrayListOf<Pair<String, Any?>>("sha256" to sha256.lowercase(), "logit" to logit.toDouble(),
                "prob" to 1.0 / (1.0 + Math.exp(-logit.toDouble())), "label" to label)
            if (name != null) kv += "name" to name      // display only; models are matched by sha256
            put("MODEL", *kv.toTypedArray())
        }

        fun error(message: String?) = put("ERROR", "message" to (message ?: "unknown"))

        /** Writes the record to the trace file and logcat. Idempotent. */
        fun end() {
            if (ended) return
            ended = true
            put("END")
            if (current.get() === this) current.remove()
            val text = lines.joinToString("\n", postfix = "\n")
            synchronized(this@ParityTrace) { file.appendText(text) }
            lines.forEach { Log.i(TAG, it) }
        }
    }
}
