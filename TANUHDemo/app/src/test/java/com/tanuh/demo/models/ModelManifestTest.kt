package com.tanuh.demo.models

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelManifestTest {
    @Test
    fun parsesRequiredOtaFields() {
        val manifest = ModelManifest.parse(
            """
            {
              "models": [
                {
                  "id": "whisper-tiny",
                  "version": "1.0.0",
                  "runtime": "whisper",
                  "format": "tflite",
                  "url": "https://example.test/whisper.ggml",
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "size": 150000000
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(1, manifest.schemaVersion)
        assertEquals("whisper-tiny", manifest.models.single().id)
        assertEquals(150000000L, manifest.models.single().size)
    }
}
