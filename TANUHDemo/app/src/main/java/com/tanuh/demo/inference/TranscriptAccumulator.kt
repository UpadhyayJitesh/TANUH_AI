package com.tanuh.demo.inference

/**
 * Reassembles Vosk's segmented callbacks into one memo.
 *
 * `onResult` contains completed utterance segments. `onFinalResult` contains
 * only the uncommitted tail, so replacing earlier results with the final value
 * can incorrectly produce an empty transcript.
 */
class TranscriptAccumulator {
    private val completedSegments = mutableListOf<String>()

    fun reset() {
        completedSegments.clear()
    }

    fun addCompleted(text: String) {
        val normalized = text.trim()
        if (normalized.isNotEmpty()) completedSegments += normalized
    }

    fun preview(partial: String): String =
        joinSegments(completedSegments + partial.trim())

    fun finish(finalTail: String): String {
        addCompleted(finalTail)
        return joinSegments(completedSegments)
    }

    private fun joinSegments(segments: List<String>): String =
        segments.filter { it.isNotBlank() }.joinToString(" ")
}
