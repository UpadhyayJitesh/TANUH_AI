package com.tanuh.demo.inference

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textclassifier.TextClassifier
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel

/**
 * Thin adapter around MediaPipe Tasks Text Classifier.
 *
 * The model is supplied as an OTA-downloaded file instead of an APK asset.
 */
class MemoTextClassifier(
    context: Context,
    modelFile: File,
) : AutoCloseable {
    private val classifier: TextClassifier

    init {
        Log.i(TAG, "Loading text classifier: path=${modelFile.absolutePath}, bytes=${modelFile.length()}")
        val startedAt = SystemClock.elapsedRealtime()
        val modelBuffer = FileInputStream(modelFile).channel.use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        }
        val baseOptions = BaseOptions.builder()
            .setModelAssetBuffer(modelBuffer)
            .build()
        val options = TextClassifier.TextClassifierOptions.builder()
            .setBaseOptions(baseOptions)
            .setMaxResults(3)
            .build()
        classifier = TextClassifier.createFromOptions(context, options)
        Log.i(TAG, "Text classifier loaded in ${SystemClock.elapsedRealtime() - startedAt} ms")
    }

    fun classify(text: String): String {
        if (text.isBlank()) {
            Log.w(TAG, "Classification skipped because transcript is blank")
            return "No speech detected"
        }
        Log.d(TAG, "Classifying transcript: characters=${text.length}")
        val startedAt = SystemClock.elapsedRealtime()
        val categories = classifier.classify(text)
            .classificationResult()
            .classifications()
            .flatMap { it.categories() }
            .sortedByDescending { it.score() }

        Log.i(
            TAG,
            "Classification completed in ${SystemClock.elapsedRealtime() - startedAt} ms; " +
                "categories=${categories.size}",
        )
        return categories.joinToString(separator = "\n") { category ->
            "${category.categoryName()}: ${"%.1f".format(category.score() * 100)}%"
        }.ifBlank { "Model returned no classification" }
    }

    override fun close() {
        Log.i(TAG, "Closing text classifier")
        classifier.close()
    }

    companion object {
        private const val TAG = "MemoTextClassifier"
    }
}
