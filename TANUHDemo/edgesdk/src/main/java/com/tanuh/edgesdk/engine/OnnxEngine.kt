package com.tanuh.edgesdk.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.tanuh.edgesdk.config.DType
import com.tanuh.edgesdk.config.ModelSpec
import com.tanuh.edgesdk.config.OnnxSpec
import java.io.RandomAccessFile
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.nio.channels.FileChannel

/**
 * ONNX Runtime (Android) backend, with three ways of getting the model to the runtime:
 *
 *  - path  : OrtEnvironment.createSession(String). ORT's C++ code opens and parses the file;
 *            the JVM heap never holds the weights.
 *  - mmap  : FileChannel.map(READ_ONLY) -> direct MappedByteBuffer -> createSession(ByteBuffer).
 *            This is the "file descriptor / memory-map" route; the kernel pages the file in.
 *  - bytes : File.readBytes() -> createSession(ByteArray). The weights are copied onto the
 *            JVM heap first (and then again into native memory). Baseline only.
 *
 * Important for interpreting the numbers: for the classic .onnx (protobuf) format ORT parses the
 * model and copies the initializers (weights) into its own native buffers during session creation,
 * so with path and mmap the weights still end up resident in *native* RAM once (~model size).
 * What those two strategies avoid is the extra JVM-heap copy - which is what causes
 * OutOfMemoryError, because the Java heap limit (getMemoryClass, often 192-512 MB) is far
 * smaller than device RAM. True zero-copy weights need the ORT format (.ort) with
 * session.use_ort_model_bytes_directly + session.use_ort_model_bytes_for_initializers, or a LiteRT
 * .tflite (flatbuffer, mmapped in place). Both can be tried through `onnx.config_entries` /
 * LiteRtEngine without code changes.
 *
 * Session options follow avni's OnnxEngine (arena + memory pattern off by default for the
 * 3-session ensemble on low-RAM devices).
 */
class OnnxEngine(private val spec: OnnxSpec) : InferenceEngine {

    override val name = "onnxruntime"
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    private fun options(): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(spec.intraOpThreads)
        setInterOpNumThreads(1)
        setCPUArenaAllocator(spec.cpuArena)
        setMemoryPatternOptimization(spec.memoryPattern)
        setOptimizationLevel(
            when (spec.optimizationLevel) {
                "none" -> OrtSession.SessionOptions.OptLevel.NO_OPT
                "basic" -> OrtSession.SessionOptions.OptLevel.BASIC_OPT
                "extended" -> OrtSession.SessionOptions.OptLevel.EXTENDED_OPT
                else -> OrtSession.SessionOptions.OptLevel.ALL_OPT
            },
        )
        when (spec.executionProvider) {
            "cpu" -> Unit
            "nnapi" -> addNnapi()
            "xnnpack" -> addXnnpack(mapOf("intra_op_num_threads" to spec.intraOpThreads.toString()))
            else -> throw IllegalArgumentException("onnx.execution_provider must be cpu | nnapi | xnnpack")
        }
        for ((k, v) in spec.configEntries) addConfigEntry(k, v)
    }

    override fun load(model: ModelSpec, strategy: String): InferenceEngine.Handle {
        val file = model.file
        require(file.isFile) { "Model file not found: ${file.absolutePath}" }
        val session = options().use { opts ->
            when (strategy) {
                "path" -> env.createSession(file.absolutePath, opts)
                "mmap" -> RandomAccessFile(file, "r").use { raf ->
                    // The mapping stays valid after the channel/FD is closed; ORT consumes it during createSession.
                    val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                    env.createSession(buf, opts)
                }
                "bytes" -> env.createSession(file.readBytes(), opts)
                else -> throw IllegalArgumentException("Unknown loading strategy '$strategy'")
            }
        }
        return OnnxHandle(model, session, env)
    }

    private class OnnxHandle(
        override val model: ModelSpec,
        private val session: OrtSession,
        private val env: OrtEnvironment,
    ) : InferenceEngine.Handle {
        private val inputNames: List<String> = session.inputNames.toList()
        @Volatile private var closed = false

        override val signature: String = buildString {
            session.inputInfo.forEach { (n, i) -> append("in  $n ${(i.info as? TensorInfo)?.let { it.shape.contentToString() + " " + it.type } ?: i.info}\n") }
            session.outputInfo.forEach { (n, i) -> append("out $n ${(i.info as? TensorInfo)?.let { it.shape.contentToString() + " " + it.type } ?: i.info}\n") }
        }.trimEnd()

        override fun run(inputs: List<InputTensor>): InferenceEngine.Output {
            check(!closed) { "Session for ${model.name} is closed" }
            require(inputs.size == inputNames.size) {
                "${model.name} expects ${inputNames.size} input(s) $inputNames, pipeline produced ${inputs.size}: $inputs"
            }
            val created = ArrayList<OnnxTensor>(inputs.size)
            try {
                val feed = LinkedHashMap<String, OnnxTensor>()
                inputs.forEachIndexed { i, raw ->
                    val name = raw.name ?: inputNames[i]
                    require(name in inputNames) { "${model.name} has no input '$name'. Inputs: $inputNames" }
                    val t = if (model.inputLayout != null && raw.dtype == DType.FLOAT32 && raw.shape.size == 4) {
                        val (d, sh) = Layouts.adapt(raw.floats!!, raw.shape, model.inputLayout)
                        raw.withFloats(d, sh)
                    } else raw
                    val ort = when (t.dtype) {
                        DType.FLOAT32 -> OnnxTensor.createTensor(env, FloatBuffer.wrap(t.floats!!), t.shape)
                        DType.INT32 -> OnnxTensor.createTensor(env, IntBuffer.wrap(t.ints!!), t.shape)
                        DType.INT64 -> OnnxTensor.createTensor(env, LongBuffer.wrap(t.longs!!), t.shape)
                    }
                    created += ort
                    feed[name] = ort
                }
                session.run(feed).use { result ->
                    val out = result.get(0) as OnnxTensor
                    val fb = out.floatBuffer ?: error("First output of ${model.name} is not a float tensor")
                    val arr = FloatArray(fb.remaining()).also { fb.get(it) }
                    return InferenceEngine.Output(arr, out.info.shape)
                }
            } finally {
                created.forEach { it.close() }
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            session.close()
        }
    }
}

/** CHW <-> HWC adaptation when one model's export disagrees with the shared preprocessing layout. */
internal object Layouts {
    fun adapt(data: FloatArray, shape: LongArray, target: String): Pair<FloatArray, LongArray> {
        require(shape.size == 4) { "Layout adaptation needs a 4-D tensor, got ${shape.contentToString()}" }
        val t = target.uppercase()
        val isChw = shape[1] == 3L && shape[3] != 3L
        return when {
            t == "CHW" && isChw || t == "HWC" && !isChw -> data to shape
            t == "HWC" -> { // [1,3,H,W] -> [1,H,W,3]
                val h = shape[2].toInt(); val w = shape[3].toInt(); val plane = h * w
                val out = FloatArray(data.size)
                for (i in 0 until plane) for (c in 0 until 3) out[i * 3 + c] = data[c * plane + i]
                out to longArrayOf(1, shape[2], shape[3], 3)
            }
            else -> { // [1,H,W,3] -> [1,3,H,W]
                val h = shape[1].toInt(); val w = shape[2].toInt(); val plane = h * w
                val out = FloatArray(data.size)
                for (i in 0 until plane) for (c in 0 until 3) out[c * plane + i] = data[i * 3 + c]
                out to longArrayOf(1, 3, shape[1], shape[2])
            }
        }
    }
}
