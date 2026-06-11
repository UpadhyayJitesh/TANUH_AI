package com.tanuh.demo.inference

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptAccumulatorTest {
    @Test
    fun preservesCompletedSegmentsWhenFinalTailIsEmpty() {
        val accumulator = TranscriptAccumulator()

        accumulator.addCompleted("send the build")

        assertEquals("send the build", accumulator.finish(""))
    }

    @Test
    fun joinsCompletedSegmentsWithFinalTail() {
        val accumulator = TranscriptAccumulator()

        accumulator.addCompleted("the release meeting")
        accumulator.addCompleted("is tomorrow")

        assertEquals(
            "the release meeting is tomorrow send the build",
            accumulator.finish("send the build"),
        )
    }

    @Test
    fun previewIncludesCommittedSpeechAndCurrentPartial() {
        val accumulator = TranscriptAccumulator()

        accumulator.addCompleted("remember")

        assertEquals("remember to call", accumulator.preview("to call"))
    }
}
