package com.tanuh.edgesdk.engine

import com.tanuh.edgesdk.config.DType
import com.tanuh.edgesdk.config.ModelSpec

/**
 * One model input: an optional name (null = bind by position), a dtype, a shape and the values.
 * Images produce one float32 tensor; text models (later) produce e.g. int64 input_ids + attention_mask.
 */
class InputTensor private constructor(
    val name: String?,
    val dtype: DType,
    val shape: LongArray,
    val floats: FloatArray?,
    val ints: IntArray?,
    val longs: LongArray?,
) {
    val elementCount: Int get() = floats?.size ?: ints?.size ?: longs!!.size

    init {
        require(shape.fold(1L) { a, b -> a * b } == elementCount.toLong()) {
            "Tensor ${name ?: "#?"}: shape ${shape.contentToString()} does not match $elementCount values"
        }
    }

    fun withName(n: String) = InputTensor(n, dtype, shape, floats, ints, longs)
    fun withFloats(data: FloatArray, newShape: LongArray) = InputTensor(name, DType.FLOAT32, newShape, data, null, null)

    override fun toString() = "${name ?: "#"} ${dtype.name.lowercase()}${shape.contentToString()}"

    companion object {
        fun float32(name: String?, data: FloatArray, shape: LongArray) = InputTensor(name, DType.FLOAT32, shape, data, null, null)
        fun int32(name: String?, data: IntArray, shape: LongArray) = InputTensor(name, DType.INT32, shape, null, data, null)
        fun int64(name: String?, data: LongArray, shape: LongArray) = InputTensor(name, DType.INT64, shape, null, null, data)
    }
}

/** What a runtime needs to expose to the SDK. One implementation per runtime (ONNX Runtime, LiteRT, ...). */
interface InferenceEngine {

    interface Handle : AutoCloseable {
        val model: ModelSpec
        /** Human-readable input/output signature as reported by the runtime. */
        val signature: String
        /** Runs one inference on the pipeline's tensors (matched by name, or by position when unnamed). */
        fun run(inputs: List<InputTensor>): Output
        override fun close()
    }

    /** First model output as floats (all current decoders read float outputs). */
    class Output(val values: FloatArray, val shape: LongArray)

    /** `strategy` is one of path / mmap / bytes (see LoadingSpec). Returns a loaded, ready handle. */
    fun load(model: ModelSpec, strategy: String): Handle

    val name: String
}
