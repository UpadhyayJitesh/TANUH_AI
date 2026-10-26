package com.tanuh.edgesdk

import com.tanuh.edgesdk.config.DType
import com.tanuh.edgesdk.config.EdgeConfig
import com.tanuh.edgesdk.config.EngineType
import com.tanuh.edgesdk.config.ImageInputSpec
import com.tanuh.edgesdk.config.PathVariables
import com.tanuh.edgesdk.postprocess.Decoders
import com.tanuh.edgesdk.preprocess.OpenCvPreprocessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EdgeConfigTest {

    private val vars = PathVariables(mapOf("EXTERNAL_FILES" to "/sdcard/Android/data/x/files", "FILES" to "/data/user/0/x/files"))

    private fun defaultJson(): String {
        // The app's default asset doubles as the fixture, so the shipped config is always parseable.
        val candidates = listOf("../app/src/main/assets/sdk-config.default.json", "app/src/main/assets/sdk-config.default.json")
        return candidates.map(::File).first { it.isFile }.readText()
    }

    private fun withRunModels(vararg names: String) =
        defaultJson().replace("\"schema_version\": 2,", "\"schema_version\": 2, \"run_models\": [${names.joinToString { "\"$it\"" }}],")

    @Test
    fun parsesShippedDefaultConfig() {
        val c = EdgeConfig.parse(defaultJson(), vars)
        assertEquals(2, c.schemaVersion)
        assertEquals(listOf("model6", "model8", "model8-2"), c.runModels)
        assertEquals("/data/user/0/x/files/models/mvit2_fold1_8_traced_(model8).onnx", c.models[1].path)
        assertEquals(EngineType.ONNX, c.models[0].engine)
        assertEquals("oral-image", c.models[0].pipeline)
        assertEquals("path", c.loading.strategy)

        val p = c.pipelines.getValue("oral-image")
        val input = p.input as ImageInputSpec
        assertEquals(listOf("resize", "gray_world", "clip", "round", "to_uint8", "scale"), input.steps.map { it.op })
        assertEquals("input", input.tensor.name)
        assertEquals(DType.FLOAT32, input.tensor.dtype)
        assertEquals("CHW", input.layout)
        assertEquals("BGR", input.channelOrder)
        assertTrue(input.decode.applyExif)
        assertEquals("unanimous-and", p.combine)
        assertEquals("sigmoid-binary", p.output.decoder)
        assertTrue(c.diagnostics.compareWithReference)
        assertEquals(listOf("oral-image"), c.selectedByPipeline().keys.map { it.name })
    }

    @Test
    fun schema1ConfigBecomesDefaultPipeline() {
        val v1 = """
            {
              "models": [ { "name": "m", "path": "${'$'}{FILES}/m.onnx" } ],
              "image": { "apply_exif": false },
              "preprocessing": {
                "steps": [ { "op": "resize", "size": [224, 224] }, { "op": "scale", "divide_by": 255 } ],
                "tensor": { "layout": "CHW", "channel_order": "RGB", "shape": [1, 3, 224, 224] }
              },
              "postprocessing": { "decoder": "softmax-argmax", "labels": ["a", "b"], "combine": "any-or" }
            }
        """.trimIndent()
        val c = EdgeConfig.parse(v1, vars)
        assertEquals(1, c.schemaVersion)
        val p = c.pipelines.getValue(EdgeConfig.V1_PIPELINE_NAME)
        val input = p.input as ImageInputSpec
        assertEquals("default", c.models[0].pipeline)
        assertFalse(input.decode.applyExif)
        assertEquals("RGB", input.channelOrder)
        assertArrayEquals(longArrayOf(1, 3, 224, 224), input.tensor.shape)
        assertEquals("softmax-argmax", p.output.decoder)
        assertEquals("any-or", p.combine)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownRunModel() { EdgeConfig.parse(withRunModels("nope"), vars) }

    @Test
    fun runModelsOverridesEnabledFlags() {
        assertEquals(listOf("model8-tfl", "model8"), EdgeConfig.parse(withRunModels("model8-tfl", "model8"), vars).runModels)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownStrategy() {
        EdgeConfig.parse(defaultJson().replace("\"strategy\": \"path\"", "\"strategy\": \"telepathy\""), vars)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownPipelineReference() {
        EdgeConfig.parse(defaultJson().replaceFirst("\"pipeline\": \"oral-image\"", "\"pipeline\": \"nope\""), vars)
    }

    @Test
    fun reservedInputTypesFailWithClearMessage() {
        val json = defaultJson().replace("\"type\": \"image\"", "\"type\": \"text\"")
        val e = runCatching { EdgeConfig.parse(json, vars) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException && e.message!!.contains("not implemented"))
    }

    @Test
    fun engineAutoDetectsTflite() {
        assertEquals(EngineType.LITERT, EngineType.parse("auto", "/x/m.tflite"))
        assertEquals(EngineType.ONNX, EngineType.parse(null, "/x/m.onnx"))
    }

    @Test
    fun tensorLayoutChwBgrAndRgb() {
        // 1x2 image, interleaved BGR: px0 = (b=1,g=2,r=3), px1 = (b=4,g=5,r=6)
        val hwc = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)
        assertArrayEquals(floatArrayOf(1f, 4f, 2f, 5f, 3f, 6f), OpenCvPreprocessor.toTensorLayout(hwc, 1, 2, "CHW", "BGR"), 0f)
        assertArrayEquals(floatArrayOf(3f, 6f, 2f, 5f, 1f, 4f), OpenCvPreprocessor.toTensorLayout(hwc, 1, 2, "CHW", "RGB"), 0f)
        assertArrayEquals(floatArrayOf(3f, 2f, 1f, 6f, 5f, 4f), OpenCvPreprocessor.toTensorLayout(hwc, 1, 2, "HWC", "RGB"), 0f)
    }

    @Test
    fun sigmoidThresholdInclusiveAndUnanimousAnd() {
        val p = EdgeConfig.parse(defaultJson(), vars).pipelines.getValue("oral-image")
        val atHalf = Decoders.decode("m", floatArrayOf(0f), p.output)          // p = 0.5 exactly
        assertTrue(atHalf.positive!!)
        val neg = Decoders.decode("n", floatArrayOf(-2f), p.output)
        assertFalse(neg.positive!!)
        assertEquals("Negative", Decoders.combine(listOf(atHalf, neg), p.output, p.combine)!!.label)
        assertEquals("Positive", Decoders.combine(listOf(atHalf, atHalf), p.output, p.combine)!!.label)
    }
}
