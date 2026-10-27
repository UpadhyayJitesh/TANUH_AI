package com.tanuh.edgesdk

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.tanuh.edgesdk.config.EdgeConfig
import com.tanuh.edgesdk.config.EngineType
import com.tanuh.edgesdk.config.ImageInputSpec
import com.tanuh.edgesdk.config.ImageSpec
import com.tanuh.edgesdk.config.ModelSpec
import com.tanuh.edgesdk.config.PathVariables
import com.tanuh.edgesdk.config.PipelineSpec
import com.tanuh.edgesdk.diag.MemoryProbe
import com.tanuh.edgesdk.diag.ParityTrace
import com.tanuh.edgesdk.engine.InferenceEngine
import com.tanuh.edgesdk.engine.InputTensor
import com.tanuh.edgesdk.engine.OnnxEngine
import com.tanuh.edgesdk.image.ImageDecoder
import com.tanuh.edgesdk.postprocess.CombinedVerdict
import com.tanuh.edgesdk.postprocess.Decoders
import com.tanuh.edgesdk.postprocess.ModelVerdict
import com.tanuh.edgesdk.preprocess.OpenCvPreprocessor
import com.tanuh.edgesdk.preprocess.TensorResult
import com.tanuh.edgesdk.preprocess.reference.AvniReferencePreprocessor
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.math.abs

/**
 * The candidate SDK surface: build from a JSON config, load the named models from their device
 * paths, and run an input through every pipeline that has selected models:
 * decode -> pipeline input steps -> tensors -> models -> output decoder -> combine.
 *
 * Images are the only input type implemented; [inferImage] runs every selected model whose
 * pipeline has `input.type = image`. Other input types get their own `infer*` entry point later.
 *
 * Not thread-safe; call from one background thread (the demo uses a single-thread executor).
 */
class EdgeSession private constructor(private val context: Context, val config: EdgeConfig) : AutoCloseable {

    private val engines = HashMap<EngineType, InferenceEngine>()
    private val handles = LinkedHashMap<String, InferenceEngine.Handle>()
    private val warmedUp = HashSet<String>()
    /** Plaintext model SHA-256 per model name (only computed when parity_trace is on). avni keys models by it. */
    private val modelSha = HashMap<String, String>()
    /** One image processor per image pipeline, built (and validated) eagerly at create time. */
    private val imageProcessors: Map<String, OpenCvPreprocessor> = config.pipelines
        .filterValues { it.input is ImageInputSpec }
        .mapValues { (_, p) -> OpenCvPreprocessor(p.input as ImageInputSpec) }
    private val withPss get() = config.diagnostics.measurePss

    val loadedModelNames: Set<String> get() = handles.keys

    /** One pipeline's outcome for one input. */
    class PipelineResult(val pipeline: String, val verdicts: List<ModelVerdict>, val combined: CombinedVerdict?) {
        /** Combined label if the pipeline combines, else the single model's label. */
        val label: String? get() = combined?.label ?: verdicts.singleOrNull()?.label
    }

    class ImageReport(
        val text: String,
        /** What the model saw (last 8-bit image state) for the first image pipeline. */
        val preview: Bitmap?,
        val results: List<PipelineResult>,
    ) {
        val headline: String get() = results.joinToString(" | ") { r -> r.label ?: r.verdicts.joinToString("/") { it.label } }
    }

    /** Checks every configured model file without loading it. */
    fun validate(): String = buildString {
        appendLine("Path variables:")
        defaultVariables(context).values.forEach { (k, v) -> appendLine("  \${$k} = $v") }
        appendLine("Config schema ${config.schemaVersion}. Models (▶ = will run):")
        val selected = config.runModels.toSet()
        for (m in config.models) {
            val f = m.file
            val mark = if (m.name in selected) "▶" else " "
            val state = when {
                !f.exists() -> "MISSING"
                !f.canRead() -> "NOT READABLE"
                else -> "%.1f MB".format(f.length() / 1048576.0)
            }
            appendLine(" $mark ${m.name} [${m.engine.name.lowercase()}, pipeline ${m.pipeline}] $state")
            appendLine("     ${m.path}")
            if (!f.exists()) {
                val dir = f.parentFile
                val listing = dir?.listFiles()?.joinToString { "${it.name} (${it.length()} B)" }
                appendLine("     folder ${dir?.path}: " + when {
                    dir == null || !dir.exists() -> "does not exist"
                    listing == null -> "exists but cannot be listed"
                    listing.isEmpty() -> "empty"
                    else -> listing
                })
            }
            if (config.diagnostics.verifySha256 && f.canRead()) {
                val t0 = System.nanoTime()
                val actual = sha256(f)
                val verdict = when (m.sha256) { null -> "(no expected hash)"; actual -> "OK"; else -> "MISMATCH, expected ${m.sha256}" }
                appendLine("     sha256 $actual $verdict  (%.0f ms, streamed)".format((System.nanoTime() - t0) / 1e6))
            }
        }
        appendLine("Loading strategy: ${config.loading.strategy}, keep_loaded=${config.loading.keepLoaded}")
        appendLine("Pipelines:")
        val running = config.selectedModels().groupBy { it.pipeline }
        for (p in config.pipelines.values) {
            val models = running[p.name]?.joinToString { it.name } ?: "(no model selected)"
            appendLine("  ${p.name} [${p.input.type.name.lowercase()}] → $models")
            val inp = p.input
            if (inp is ImageInputSpec) {
                appendLine("     decode exif=${inp.decode.applyExif} max_side=${inp.decode.maxDecodeSide}")
                appendLine("     steps ${inp.steps.joinToString(" → ") { it.op }}")
            }
            appendLine("     tensors ${p.input.tensors.joinToString { t -> "${t.name ?: "#0"} ${t.dtype.name.lowercase()} ${t.layout ?: ""}/${t.channelOrder ?: ""} ${t.shape?.contentToString() ?: ""}" }}")
            appendLine("     output ${p.output.decoder} (threshold ${p.output.threshold}${if (p.output.thresholdInclusive) ", inclusive" else ""}) combine ${p.combine}")
        }
        appendLine(MemoryProbe.deviceSummary(context))
    }

    /** Loads every model in run_models that is not loaded yet. Returns a timing/memory report. */
    fun loadSelected(): String = buildString {
        val strategy = config.loading.strategy
        appendLine("Loading with strategy '$strategy'")
        val start = MemoryProbe.snapshot(withPss)
        appendLine("  before: $start")
        for (m in config.selectedModels()) {
            if (m.name in handles) { appendLine("  ${m.name}: already loaded"); continue }
            appendLine(loadOne(m, strategy))
        }
        val end = MemoryProbe.snapshot(withPss)
        appendLine("  after:  $end")
        appendLine("  total:  ${end.deltaFrom(start)}")
    }

    private fun loadOne(m: ModelSpec, strategy: String): String {
        System.gc()
        val before = MemoryProbe.snapshot(withPss)
        val t0 = System.nanoTime()
        val handle = engineFor(m.engine).load(m, strategy)
        val loadMs = (System.nanoTime() - t0) / 1e6
        if (config.diagnostics.parityTrace) modelSha.getOrPut(m.name) { ParityTrace.sha256(m.file) }   // after timing
        val after = MemoryProbe.snapshot(withPss)
        handles[m.name] = handle
        return buildString {
            append("  ${m.name}: loaded in %.0f ms via ${engineFor(m.engine).name}/$strategy  ".format(loadMs))
            append(after.deltaFrom(before))
            handle.signature.lines().forEach { append("\n      $it") }
        }
    }

    fun unloadAll(): String {
        val names = handles.keys.toList()
        handles.values.forEach { runCatching { it.close() } }
        handles.clear()
        warmedUp.clear()
        System.gc()
        return "Unloaded ${names.ifEmpty { listOf("nothing") }.joinToString()}  now: ${MemoryProbe.snapshot(withPss)}"
    }

    /** Runs every selected image-pipeline model on a gallery/content URI. */
    fun inferImage(uri: Uri, label: String): ImageReport =
        inferImage(label, { context.contentResolver.openInputStream(uri) ?: error("Cannot open $uri") }) { spec ->
            ImageDecoder.decode(context, uri, spec)
        }

    /** Runs every selected image-pipeline model on an image file. */
    fun inferImage(file: File, label: String = file.name): ImageReport =
        inferImage(label, { file.inputStream() }) { spec -> ImageDecoder.decode(file, spec) }

    private fun inferImage(label: String, openSource: () -> InputStream, decode: (ImageSpec) -> ImageDecoder.Decoded): ImageReport {
        val trace = if (config.diagnostics.parityTrace) ParityTrace.get(context, "sdk").begin() else null
        try {
            trace?.image(label)
            trace?.let { t -> val (n, sha) = ParityTrace.sha256(openSource()); t.input(n, sha) }
            return inferImageTraced(label, decode, trace)
        } catch (t: Throwable) {
            trace?.error(t.message)
            throw t
        } finally {
            trace?.end()
        }
    }

    private fun inferImageTraced(label: String, decode: (ImageSpec) -> ImageDecoder.Decoded, trace: ParityTrace.Record?): ImageReport {
        val sb = StringBuilder("━━ $label\n")
        val results = ArrayList<PipelineResult>()
        var preview: Bitmap? = null
        val decodedBySpec = HashMap<ImageSpec, ImageDecoder.Decoded>()   // pipelines with identical decode settings share one bitmap
        try {
            for ((pipeline, models) in config.selectedByPipeline()) {
                val input = pipeline.input
                if (input !is ImageInputSpec) {
                    sb.appendLine("[${pipeline.name}] skipped: ${input.type.name.lowercase()} input is not an image")
                    continue
                }
                val decoded = decodedBySpec.getOrPut(input.decode) {
                    val t0 = System.nanoTime()
                    decode(input.decode).also { d ->
                        trace?.time("decode", (System.nanoTime() - t0) / 1e6)
                        trace?.decode(d.bitmap, d.exifOrientation)
                        sb.appendLine("Decode %.0f ms: %dx%d source, EXIF orientation %d, sample %d → %dx%d".format(
                            (System.nanoTime() - t0) / 1e6, d.sourceWidth, d.sourceHeight, d.exifOrientation, d.sampleSize,
                            d.bitmap.width, d.bitmap.height))
                    }
                }
                trace?.put("PIPELINE", "name" to pipeline.name)
                trace?.put("PARAMS", "preprocessor" to "opencv-steps",
                    "json" to org.json.JSONArray(input.steps.map { it.params }).toString())
                val r = runImagePipeline(pipeline, models, decoded.bitmap, sb, trace)
                results += r.first
                if (preview == null) preview = r.second
            }
        } finally {
            decodedBySpec.values.forEach { it.bitmap.recycle() }
        }
        sb.appendLine("Memory now: ${MemoryProbe.snapshot(withPss)}")
        return ImageReport(sb.toString(), preview, results)
    }

    /** One image pipeline: preprocess once, run its models, decode, combine, optional avni parity. */
    private fun runImagePipeline(pipeline: PipelineSpec, models: List<ModelSpec>, bitmap: Bitmap, sb: StringBuilder,
                                 trace: ParityTrace.Record?): Pair<PipelineResult, Bitmap?> {
        sb.appendLine("[${pipeline.name}] ${models.joinToString { it.name }}")
        val m0 = MemoryProbe.snapshot(withPss)
        val t0 = System.nanoTime()
        val tensor = imageProcessors.getValue(pipeline.name).run(bitmap)
        val preMs = (System.nanoTime() - t0) / 1e6
        trace?.time("preprocess", preMs)
        trace?.tensor(tensor.data, tensor.shape, tensor.layout, tensor.channelOrder)
        sb.appendLine("Preprocessing %.1f ms  ${MemoryProbe.snapshot(withPss).deltaFrom(m0)}".format(preMs))
        tensor.stepTimingsMs.forEach { (s, ms) -> sb.appendLine("   %-44s %7.1f ms".format(s, ms)) }
        tensor.notes.forEach { sb.appendLine("   · $it") }
        sb.appendLine("   tensor stats: ${tensorStats(tensor)}")

        val inputs = listOf(tensor.toInput())
        val verdicts = ArrayList<ModelVerdict>()
        val outputs = LinkedHashMap<String, FloatArray>()
        for (m in models) {
            val out = runModel(m, inputs, sb)
            outputs[m.name] = out.first.values
            val v = Decoders.decode(m.name, out.first.values, pipeline.output)
            verdicts += v
            trace?.let { t ->
                t.time("inference_${m.name}", out.second)
                t.model(modelSha.getOrPut(m.name) { ParityTrace.sha256(m.file) }, out.first.values.firstOrNull() ?: Float.NaN, v.label, m.name)
            }
            sb.appendLine("%-10s %-9s p=%s  logit=%s  %.0f ms".format(
                m.name, v.label, v.probability?.let { "%.4f".format(it) } ?: "-",
                out.first.values.take(4).joinToString { "%.4f".format(it) }, out.second))
        }
        val combined = Decoders.combine(verdicts, pipeline.output, pipeline.combine)
        combined?.let { sb.appendLine("RESULT [${pipeline.name} · ${it.rule}]: ${it.label}  (${it.detail})") }
        combined?.let { trace?.put("COMBINED", "rule" to it.rule, "label" to it.label) }

        val refPipeline = config.diagnostics.referencePipeline
            ?: config.selectedByPipeline().keys.firstOrNull { it.input is ImageInputSpec }?.name
        if (config.diagnostics.compareWithReference && pipeline.name == refPipeline) {
            sb.append(compareWithReference(bitmap, tensor, outputs))
        }
        return PipelineResult(pipeline.name, verdicts, combined) to tensor.preview
    }

    /** Loads if needed, warms up once per load, runs one timed inference. Returns output + ms. */
    private fun runModel(m: ModelSpec, inputs: List<InputTensor>, sb: StringBuilder): Pair<InferenceEngine.Output, Double> {
        val transient = !config.loading.keepLoaded && m.name !in handles
        if (m.name !in handles) sb.appendLine(loadOne(m, config.loading.strategy))
        val h = handles.getValue(m.name)
        // Warm-up: untimed, discarded runs the first time a loaded model is used (not on every input),
        // so the timed run below reflects steady-state latency.
        if (config.loading.warmupRuns > 0 && warmedUp.add(m.name)) {
            val tw = System.nanoTime()
            repeat(config.loading.warmupRuns) { h.run(inputs) }
            sb.appendLine("  ${m.name}: ${config.loading.warmupRuns} warm-up run(s) %.0f ms (not counted)".format((System.nanoTime() - tw) / 1e6))
        }
        val ti = System.nanoTime()
        val out = h.run(inputs)
        val runMs = (System.nanoTime() - ti) / 1e6
        if (transient) { h.close(); handles.remove(m.name); warmedUp.remove(m.name) }
        return out to runMs
    }

    /** Runs avni's Kotlin reference preprocessor on the same bitmap and diffs the tensors (and logits). */
    private fun compareWithReference(bitmap: Bitmap, tensor: TensorResult, outputs: Map<String, FloatArray>): String = buildString {
        val params = config.diagnostics.referenceParams ?: run {
            appendLine("Reference: diagnostics.reference_params missing, skipped"); return@buildString
        }
        System.gc()
        val m0 = MemoryProbe.snapshot(false)
        val t0 = System.nanoTime()
        val ref = AvniReferencePreprocessor.preprocess(bitmap, params)
        val ms = (System.nanoTime() - t0) / 1e6
        val m1 = MemoryProbe.snapshot(false)
        appendLine("Reference (avni Kotlin) preprocessing %.1f ms  ${m1.deltaFrom(m0)} (after GC may hide the peak)".format(ms))
        if (!ref.shape.contentEquals(tensor.shape)) {
            appendLine("   shape differs: ref ${ref.shape.contentToString()} vs sdk ${tensor.shape.contentToString()}"); return@buildString
        }
        var maxDiff = 0f; var sum = 0.0; var count = 0
        for (i in ref.data.indices) {
            val d = abs(ref.data[i] - tensor.data[i])
            if (d > 0f) { count++; sum += d; if (d > maxDiff) maxDiff = d }
        }
        appendLine("   tensor diff vs avni: %d / %d elements differ, max |Δ| = %.6g (%.2f/255), mean |Δ| over all = %.3g".format(
            count, ref.data.size, maxDiff, maxDiff * 255, sum / ref.data.size))
        if (count > 0) {
            for ((name, sdkOut) in outputs) {
                val h = handles[name] ?: continue
                val refOut = h.run(listOf(InputTensor.float32(tensor.name, ref.data, ref.shape))).values
                appendLine("   %-10s logit sdk=%.5f  avni=%.5f  Δ=%.2e".format(name, sdkOut[0], refOut[0], sdkOut[0] - refOut[0]))
            }
        }
    }

    private fun tensorStats(t: TensorResult): String {
        if (t.layout != "CHW") return "shape ${t.shape.contentToString()}"
        val plane = t.data.size / 3
        return (0 until 3).joinToString("  ") { c ->
            var s = 0.0; var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            for (i in c * plane until (c + 1) * plane) { val v = t.data[i]; s += v; if (v < lo) lo = v; if (v > hi) hi = v }
            "${t.channelOrder[c]}: mean=%.4f [%.3f,%.3f]".format(s / plane, lo, hi)
        }
    }

    private fun engineFor(type: EngineType): InferenceEngine = engines.getOrPut(type) {
        when (type) {
            EngineType.ONNX -> OnnxEngine(config.onnx)
            EngineType.LITERT -> loadLiteRtEngine()
        }
    }

    /** LiteRT is an optional source set (gradle property edgesdk.litert), so look it up reflectively. */
    private fun loadLiteRtEngine(): InferenceEngine = try {
        Class.forName("com.tanuh.edgesdk.engine.LiteRtEngine")
            .getConstructor(com.tanuh.edgesdk.config.LiteRtSpec::class.java)
            .newInstance(config.litert) as InferenceEngine
    } catch (e: ClassNotFoundException) {
        throw IllegalStateException("This build has no LiteRT engine. Set edgesdk.litert=true in gradle.properties and rebuild.", e)
    }

    override fun close() { unloadAll() }

    companion object {
        /** Placeholders usable in model paths. */
        fun defaultVariables(context: Context) = PathVariables(
            linkedMapOf(
                "EXTERNAL_FILES" to (context.getExternalFilesDir(null) ?: context.filesDir).absolutePath,
                "FILES" to context.filesDir.absolutePath,
                "CACHE" to context.cacheDir.absolutePath,
            ),
        )

        /** Parses and validates the JSON, then builds a session. No model is loaded yet. */
        fun create(context: Context, configJson: String): EdgeSession =
            EdgeSession(context.applicationContext, EdgeConfig.parse(configJson, defaultVariables(context)))

        private fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            DigestInputStream(f.inputStream().buffered(1 shl 16), md).use { s -> val buf = ByteArray(1 shl 16); while (s.read(buf) != -1) Unit }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
