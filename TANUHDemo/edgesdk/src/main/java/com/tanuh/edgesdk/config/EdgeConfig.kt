package com.tanuh.edgesdk.config

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Everything the SDK needs, supplied by the host app as one JSON document.
 *
 * Schema 2 separates *what runs* from *how its input is prepared*:
 *
 *  - `models`      every model the SDK knows: file path, engine, and the `pipeline` it uses
 *  - `run_models`  optional: which models run, by name and order (absent = every `enabled` model)
 *  - `pipelines`   named recipes. Each one declares an `input` (its `type`, the type-specific
 *                  `steps`, and the named, typed `tensors` it produces), an `output` decoder and a
 *                  `combine` rule for the models that share it
 *  - `loading`, `onnx`, `litert`, `diagnostics`   runtime and test-bench settings
 *
 * Input types: `image` is implemented. `text`, `audio` and `tensor` are reserved names: they
 * parse into an error that says so, and adding one means a new [InputSpec] + processor, without
 * touching the rest of the schema.
 *
 * Schema 1 configs (top-level `image` / `preprocessing` / `postprocessing`) are still accepted
 * and become a single pipeline named "default".
 *
 * Keys starting with `_` and unknown keys are ignored.
 *
 * Path placeholders resolved at parse time (see [PathVariables]):
 *   ${FILES} -> context.filesDir, ${EXTERNAL_FILES} -> getExternalFilesDir(null), ${CACHE} -> cacheDir
 */
data class EdgeConfig(
    val schemaVersion: Int,
    val models: List<ModelSpec>,
    val runModels: List<String>,
    val pipelines: Map<String, PipelineSpec>,
    val loading: LoadingSpec,
    val onnx: OnnxSpec,
    val litert: LiteRtSpec,
    val diagnostics: DiagnosticsSpec,
    val raw: JSONObject,
) {
    /** Models selected to run, in run order. */
    fun selectedModels(): List<ModelSpec> {
        val byName = models.associateBy { it.name }
        return runModels.map { name ->
            byName[name] ?: throw IllegalArgumentException(
                "run_models refers to '$name' but no model with that name is declared. Known: ${byName.keys}",
            )
        }
    }

    /** Selected models grouped by pipeline, pipelines in order of first use. */
    fun selectedByPipeline(): Map<PipelineSpec, List<ModelSpec>> =
        selectedModels().groupBy { it.pipeline }.mapKeys { (name, _) -> pipelines.getValue(name) }

    companion object {
        const val LATEST_SCHEMA = 2
        const val V1_PIPELINE_NAME = "default"

        fun parse(json: String, vars: PathVariables): EdgeConfig {
            val root = JSONObject(json)
            val version = root.optInt("schema_version", if (root.has("pipelines")) 2 else 1)
            require(version in 1..LATEST_SCHEMA) { "Unsupported schema_version $version (this SDK reads 1..$LATEST_SCHEMA)" }

            val pipelines: Map<String, PipelineSpec> =
                if (version >= 2) parsePipelines(root.requireObject("pipelines"))
                else mapOf(V1_PIPELINE_NAME to v1Pipeline(root))

            val models = root.requireArray("models").objects().map { ModelSpec.parse(it, vars, pipelines.keys) }
            require(models.isNotEmpty()) { "'models' must declare at least one model" }
            val dupes = models.groupBy { it.name }.filterValues { it.size > 1 }.keys
            require(dupes.isEmpty()) { "Duplicate model names: $dupes" }

            // run_models is optional. Without it, every model with "enabled": true (the default) runs,
            // in declaration order. With it, it decides exactly which models run and in what order.
            val runModels = root.optJSONArray("run_models")?.strings() ?: models.filter { it.enabled }.map { it.name }
            require(runModels.isNotEmpty()) { "Nothing to run: no 'run_models' given and no model has \"enabled\": true" }

            val diagnostics = DiagnosticsSpec.parse(root.optJSONObject("diagnostics") ?: JSONObject())
            diagnostics.referencePipeline?.let {
                require(it in pipelines) { "diagnostics.reference_pipeline '$it' is not a declared pipeline (${pipelines.keys})" }
            }

            return EdgeConfig(
                schemaVersion = version,
                models = models,
                runModels = runModels,
                pipelines = pipelines,
                loading = LoadingSpec.parse(root.optJSONObject("loading") ?: JSONObject()),
                onnx = OnnxSpec.parse(root.optJSONObject("onnx") ?: JSONObject()),
                litert = LiteRtSpec.parse(root.optJSONObject("litert") ?: JSONObject()),
                diagnostics = diagnostics,
                raw = root,
            ).also { it.selectedModels() }
        }

        private fun parsePipelines(o: JSONObject): Map<String, PipelineSpec> {
            val names = o.keys().asSequence().filterNot { it.startsWith("_") }.toList()
            require(names.isNotEmpty()) { "'pipelines' is empty" }
            return names.associateWith { PipelineSpec.parse(it, o.getJSONObject(it)) }
        }

        /** Schema 1 -> one image pipeline, so old configs keep working unchanged. */
        private fun v1Pipeline(root: JSONObject): PipelineSpec {
            val pre = root.requireObject("preprocessing")
            val post = root.optJSONObject("postprocessing") ?: JSONObject()
            val input = JSONObject()
                .put("type", "image")
                .put("decode", root.optJSONObject("image") ?: JSONObject())
                .put("steps", pre.requireArray("steps"))
                .put("tensors", JSONArray().put(pre.optJSONObject("tensor") ?: JSONObject()))
            return PipelineSpec.parse(V1_PIPELINE_NAME, JSONObject().put("input", input).put("output", post)
                .put("combine", post.optString("combine", "none")))
        }
    }
}

/** Placeholder -> absolute directory, supplied by the host (keeps this file free of Context). */
class PathVariables(private val vars: Map<String, String>) {
    fun resolve(path: String): String {
        var out = path
        for ((k, v) in vars) out = out.replace("\${$k}", v.trimEnd('/'))
        require(!out.contains("\${")) { "Unresolved placeholder in path '$path'. Known: ${vars.keys}" }
        return out
    }
    val values: Map<String, String> get() = vars
}

/* ── Models ───────────────────────────────────────────────────────────────────── */

enum class EngineType { ONNX, LITERT;
    companion object {
        fun parse(s: String?, path: String): EngineType = when (s?.lowercase()) {
            "onnx", "ort", "onnxruntime" -> ONNX
            "litert", "tflite" -> LITERT
            null, "", "auto" -> if (path.endsWith(".tflite", true)) LITERT else ONNX
            else -> throw IllegalArgumentException("Unknown engine '$s' (use onnx | litert | auto)")
        }
    }
}

data class ModelSpec(
    val name: String,
    val path: String,
    val engine: EngineType,
    /** Name of the pipeline that prepares this model's input and reads its output. */
    val pipeline: String,
    /** Optional per-model override of a 4-D float tensor's layout (e.g. a LiteRT export that wants NHWC). */
    val inputLayout: String?,
    /** Optional expected SHA-256 (lowercase hex). Checked only when diagnostics.verify_sha256 = true. */
    val sha256: String?,
    /** Used only when run_models is absent: false keeps the model declared but not run. Default true. */
    val enabled: Boolean,
) {
    val file: File get() = File(path)

    companion object {
        fun parse(o: JSONObject, vars: PathVariables, pipelines: Set<String>): ModelSpec {
            val name = o.optString("name").ifBlank { throw IllegalArgumentException("model entry without 'name': $o") }
            val path = vars.resolve(o.optString("path").ifBlank { throw IllegalArgumentException("model '$name' has no 'path'") })
            val pipeline = o.optString("pipeline").ifBlank {
                pipelines.singleOrNull() ?: throw IllegalArgumentException(
                    "model '$name' has no 'pipeline' and there are several: $pipelines")
            }
            require(pipeline in pipelines) { "model '$name' uses unknown pipeline '$pipeline'. Declared: $pipelines" }
            return ModelSpec(
                name = name,
                path = path,
                engine = EngineType.parse(o.optString("engine", "auto"), path),
                pipeline = pipeline,
                inputLayout = o.optString("input_layout").ifBlank { null }?.uppercase(),
                sha256 = o.optString("sha256").ifBlank { null }?.lowercase(),
                enabled = o.optBoolean("enabled", true),
            )
        }
    }
}

/* ── Pipelines ────────────────────────────────────────────────────────────────── */

/** A named recipe: how to turn one kind of input into model tensors, and how to read the outputs. */
data class PipelineSpec(
    val name: String,
    val input: InputSpec,
    val output: OutputSpec,
    /** How the verdicts of all models in this pipeline are combined (see Decoders.combine). */
    val combine: String,
) {
    companion object {
        val COMBINERS = setOf("unanimous-and", "any-or", "majority", "mean-probability", "none")

        fun parse(name: String, o: JSONObject): PipelineSpec {
            val input = InputSpec.parse(name, o.requireObject("input"))
            val output = OutputSpec.parse(o.optJSONObject("output") ?: JSONObject())
            val combine = o.optString("combine", "none").lowercase()
            require(combine in COMBINERS) { "pipeline '$name': combine must be one of $COMBINERS" }
            return PipelineSpec(name, input, output, combine)
        }
    }
}

enum class InputType { IMAGE, TEXT, AUDIO, TENSOR }

/** Common shape of every input kind: it ends in one or more named, typed tensors. */
sealed interface InputSpec {
    val type: InputType
    val tensors: List<TensorSpec>

    companion object {
        fun parse(pipeline: String, o: JSONObject): InputSpec {
            val type = when (val t = o.optString("type", "image").lowercase()) {
                "image" -> InputType.IMAGE
                "text" -> InputType.TEXT
                "audio" -> InputType.AUDIO
                "tensor" -> InputType.TENSOR
                else -> throw IllegalArgumentException("pipeline '$pipeline': unknown input.type '$t' (image | text | audio | tensor)")
            }
            return when (type) {
                InputType.IMAGE -> ImageInputSpec.parse(pipeline, o)
                else -> throw IllegalArgumentException(
                    "pipeline '$pipeline': input.type '${type.name.lowercase()}' is reserved but not implemented in this SDK build yet (only 'image')")
            }
        }
    }
}

/** One preprocessing op: its name plus its own params object (each op owns its param shape). */
data class StepSpec(val op: String, val params: JSONObject)

enum class DType { FLOAT32, INT32, INT64;
    companion object {
        fun parse(s: String?): DType = when (s?.lowercase()) {
            null, "", "float32", "float" -> FLOAT32
            "int32" -> INT32
            "int64", "long" -> INT64
            else -> throw IllegalArgumentException("Unknown dtype '$s' (float32 | int32 | int64)")
        }
    }
}

/**
 * One model input tensor produced by a pipeline.
 *  - `name`  : the model's input name. Absent = bind by position (fine for single-input models).
 *  - `dtype` : float32 | int32 | int64.
 *  - `layout` / `channel_order` : image tensors only (CHW|HWC, RGB|BGR).
 *  - `shape` : expected shape; checked against what the pipeline produced.
 */
data class TensorSpec(
    val name: String?,
    val dtype: DType,
    val layout: String?,
    val channelOrder: String?,
    val shape: LongArray?,
) {
    companion object {
        fun parse(o: JSONObject) = TensorSpec(
            name = o.optString("name").ifBlank { null },
            dtype = DType.parse(o.optString("dtype")),
            layout = o.optString("layout").ifBlank { null }?.uppercase(),
            channelOrder = o.optString("channel_order").ifBlank { null }?.uppercase(),
            shape = o.optJSONArray("shape")?.let { a -> LongArray(a.length()) { a.getLong(it) } },
        )
    }
}

/** `input.type = image`: decode settings, ordered OpenCV steps, and exactly one float32 tensor. */
data class ImageInputSpec(
    val decode: ImageSpec,
    val steps: List<StepSpec>,
    override val tensors: List<TensorSpec>,
) : InputSpec {
    override val type get() = InputType.IMAGE

    /** The single output tensor of an image pipeline. */
    val tensor: TensorSpec get() = tensors.first()
    val layout: String get() = tensor.layout ?: "CHW"
    val channelOrder: String get() = tensor.channelOrder ?: "RGB"

    companion object {
        fun parse(pipeline: String, o: JSONObject): ImageInputSpec {
            val steps = o.requireArray("steps").objects().map {
                StepSpec(it.optString("op").ifBlank { throw IllegalArgumentException("pipeline '$pipeline': step without 'op': $it") }.lowercase(), it)
            }
            val tensors = (o.optJSONArray("tensors")?.objects() ?: listOf(JSONObject())).map { TensorSpec.parse(it) }
            require(tensors.size == 1) { "pipeline '$pipeline': an image input produces exactly one tensor, got ${tensors.size}" }
            val t = tensors[0]
            require(t.dtype == DType.FLOAT32) { "pipeline '$pipeline': image tensors are float32" }
            require(t.layout == null || t.layout == "CHW" || t.layout == "HWC") { "pipeline '$pipeline': layout must be CHW or HWC" }
            require(t.channelOrder == null || t.channelOrder == "RGB" || t.channelOrder == "BGR") { "pipeline '$pipeline': channel_order must be RGB or BGR" }
            return ImageInputSpec(ImageSpec.parse(o.optJSONObject("decode") ?: JSONObject()), steps, tensors)
        }
    }
}

data class ImageSpec(val applyExif: Boolean, val maxDecodeSide: Int) {
    companion object {
        fun parse(o: JSONObject) = ImageSpec(
            applyExif = o.optBoolean("apply_exif", true),
            // 0 = full resolution (needed for parity with the QC reference). A positive value lets
            // BitmapFactory subsample huge photos to save memory, at the cost of parity.
            maxDecodeSide = o.optInt("max_decode_side", 0).coerceAtLeast(0),
        )
    }
}

/** How one model's raw output becomes a label. */
data class OutputSpec(
    val decoder: String,
    val threshold: Double,
    val thresholdInclusive: Boolean,
    val labels: List<String>,
) {
    companion object {
        val DECODERS = setOf("sigmoid-binary", "softmax-argmax", "raw")
        fun parse(o: JSONObject): OutputSpec {
            val d = o.optString("decoder", "sigmoid-binary").lowercase()
            require(d in DECODERS) { "output.decoder must be one of $DECODERS" }
            return OutputSpec(
                decoder = d,
                threshold = o.optDouble("threshold", 0.5),
                thresholdInclusive = o.optBoolean("threshold_inclusive", true),
                labels = o.optJSONArray("labels")?.strings() ?: listOf("Negative", "Positive"),
            )
        }
    }
}

/* ── Runtime + test-bench settings ────────────────────────────────────────────── */

/**
 * How model weights reach the runtime.
 *  - `path`  : hand the absolute path to the native runtime. Nothing touches the JVM heap.
 *  - `mmap`  : FileChannel.map() read-only -> direct buffer -> runtime.
 *  - `bytes` : File.readBytes() into a JVM ByteArray first. Baseline only.
 */
data class LoadingSpec(val strategy: String, val keepLoaded: Boolean, val warmupRuns: Int) {
    companion object {
        val STRATEGIES = setOf("path", "mmap", "bytes")
        fun parse(o: JSONObject): LoadingSpec {
            val s = o.optString("strategy", "path").lowercase()
            require(s in STRATEGIES) { "loading.strategy must be one of $STRATEGIES, got '$s'" }
            return LoadingSpec(s, o.optBoolean("keep_loaded", true), o.optInt("warmup_runs", 0).coerceIn(0, 10))
        }
    }
}

data class OnnxSpec(
    val intraOpThreads: Int,
    val cpuArena: Boolean,
    val memoryPattern: Boolean,
    val optimizationLevel: String,
    val executionProvider: String,
    val configEntries: Map<String, String>,
) {
    companion object {
        fun parse(o: JSONObject) = OnnxSpec(
            intraOpThreads = o.optInt("intra_op_threads", Runtime.getRuntime().availableProcessors().coerceIn(1, 4)),
            cpuArena = o.optBoolean("cpu_arena", false),
            memoryPattern = o.optBoolean("memory_pattern", false),
            optimizationLevel = o.optString("optimization_level", "all").lowercase(),
            executionProvider = o.optString("execution_provider", "cpu").lowercase(),
            configEntries = o.optJSONObject("config_entries")?.let { e ->
                e.keys().asSequence().filterNot { it.startsWith("_") }.associateWith { e.getString(it) }
            } ?: emptyMap(),
        )
    }
}

data class LiteRtSpec(val accelerator: String) {
    companion object {
        fun parse(o: JSONObject) = LiteRtSpec(o.optString("accelerator", "cpu").lowercase())
    }
}

data class DiagnosticsSpec(
    val compareWithReference: Boolean,
    val referenceParams: JSONObject?,
    /** Which image pipeline the avni comparison applies to. Default: the first image pipeline that runs. */
    val referencePipeline: String?,
    val verifySha256: Boolean,
    val measurePss: Boolean,
    /** Write a parity trace (diag/ParityTrace.kt) per image, comparable line by line with avni's. */
    val parityTrace: Boolean,
) {
    companion object {
        fun parse(o: JSONObject) = DiagnosticsSpec(
            compareWithReference = o.optBoolean("compare_with_reference", false),
            referenceParams = o.optJSONObject("reference_params"),
            referencePipeline = o.optString("reference_pipeline").ifBlank { null },
            verifySha256 = o.optBoolean("verify_sha256", false),
            measurePss = o.optBoolean("measure_pss", true),
            parityTrace = o.optBoolean("parity_trace", false),
        )
    }
}

/* ── JSON helpers ─────────────────────────────────────────────────────────────── */

internal fun JSONObject.requireArray(key: String): JSONArray =
    optJSONArray(key) ?: throw IllegalArgumentException("Missing array '$key'")

internal fun JSONObject.requireObject(key: String): JSONObject =
    optJSONObject(key) ?: throw IllegalArgumentException("Missing object '$key'")

internal fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

internal fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

fun JSONObject.optDoubleArray(key: String): DoubleArray? =
    optJSONArray(key)?.let { a -> DoubleArray(a.length()) { a.getDouble(it) } }

fun JSONObject.optIntPair(key: String, defW: Int, defH: Int): Pair<Int, Int> {
    val a = optJSONArray(key) ?: return defW to defH
    if (a.length() == 1) return a.getInt(0) to a.getInt(0)
    return (if (a.length() > 0) a.getInt(0) else defW) to (if (a.length() > 1) a.getInt(1) else defH)
}
