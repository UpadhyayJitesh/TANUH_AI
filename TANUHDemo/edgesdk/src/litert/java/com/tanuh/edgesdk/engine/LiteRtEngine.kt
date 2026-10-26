package com.tanuh.edgesdk.engine

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.tanuh.edgesdk.config.DType
import com.tanuh.edgesdk.config.LiteRtSpec
import com.tanuh.edgesdk.config.ModelSpec

/**
 * LiteRT (formerly TensorFlow Lite) backend using the modern CompiledModel API, created
 * straight from the absolute file path so the native runtime memory-maps the .tflite
 * flatbuffer - weights are paged in on demand and never copied onto the JVM heap.
 *
 * Only `path` is meaningful here: CompiledModel already mmaps. `mmap` is treated the same, and
 * `bytes` is rejected (the API has no byte-array entry point, which is rather the point).
 *
 * Needs a .tflite export of the model - the current MViT2 folds are .onnx, so this engine is idle
 * until a converted file is listed in the config with "engine": "litert".
 *
 * API note: written against com.google.ai.edge.litert:litert 2.3.0 and the file-path overload
 * CompiledModel.create(filePath, options, env). This could not be compiled in this environment;
 * if the signature differs in the version Gradle resolves, adjust [load] only.
 */
class LiteRtEngine(private val spec: LiteRtSpec) : InferenceEngine {

    override val name = "litert"

    override fun load(model: ModelSpec, strategy: String): InferenceEngine.Handle {
        require(model.file.isFile) { "Model file not found: ${model.path}" }
        require(strategy != "bytes") { "LiteRT CompiledModel loads from a path (mmap); 'bytes' is not supported" }
        val accel = when (spec.accelerator) {
            "cpu" -> Accelerator.CPU
            "gpu" -> Accelerator.GPU
            else -> throw IllegalArgumentException("litert.accelerator must be cpu | gpu")
        }
        val compiled = CompiledModel.create(model.file.absolutePath, CompiledModel.Options(accel), null)
        return LiteRtHandle(model, compiled)
    }

    private class LiteRtHandle(override val model: ModelSpec, private val compiled: CompiledModel) : InferenceEngine.Handle {
        private val inputs = compiled.createInputBuffers()
        private val outputs = compiled.createOutputBuffers()
        override val signature = "inputs=${inputs.size} outputs=${outputs.size} (LiteRT CompiledModel)"

        override fun run(inputs: List<InputTensor>): InferenceEngine.Output {
            require(inputs.size == this.inputs.size) {
                "${model.name} expects ${this.inputs.size} input(s), pipeline produced ${inputs.size}: $inputs"
            }
            // Bound by position. Only float32 inputs for now (enough for image models).
            inputs.forEachIndexed { i, t ->
                require(t.dtype == DType.FLOAT32) { "LiteRtEngine supports float32 inputs only so far, got $t" }
                // .tflite exports are usually NHWC; set "input_layout": "HWC" on the model to convert.
                val data = if (model.inputLayout != null && t.shape.size == 4) Layouts.adapt(t.floats!!, t.shape, model.inputLayout).first else t.floats!!
                this.inputs[i].writeFloat(data)
            }
            compiled.run(this.inputs, outputs)
            val out = outputs[0].readFloat()
            return InferenceEngine.Output(out, longArrayOf(out.size.toLong()))
        }

        override fun close() {
            inputs.forEach { it.close() }
            outputs.forEach { it.close() }
            compiled.close()
        }
    }
}
