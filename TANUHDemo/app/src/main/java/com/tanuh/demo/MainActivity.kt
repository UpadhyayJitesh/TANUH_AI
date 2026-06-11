package com.tanuh.demo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.tanuh.demo.inference.MemoTextClassifier
import com.tanuh.demo.inference.TranscriptAccumulator
import com.tanuh.demo.models.InstalledModel
import com.tanuh.demo.models.ModelManager
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.util.concurrent.Executors

/**
 * Coordinates the demo workflow:
 * OTA preparation -> microphone capture -> Vosk ASR -> MobileBERT classification.
 *
 * Model delivery and inference run off the main thread. UI state changes are
 * marshalled back to the activity thread.
 */
class MainActivity : AppCompatActivity(), RecognitionListener {
    private lateinit var statusText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var classificationText: TextView
    private lateinit var prepareButton: Button
    private lateinit var recordButton: Button
    private lateinit var meteredCheck: CheckBox

    private lateinit var modelManager: ModelManager
    private val inferenceExecutor = Executors.newSingleThreadExecutor()
    private var installedModels: Map<String, InstalledModel> = emptyMap()
    private var speechService: SpeechService? = null
    private var voskModel: Model? = null
    private var textClassifier: MemoTextClassifier? = null
    private val transcriptAccumulator = TranscriptAccumulator()
    private var recording = false
    private var inferenceStartedAt = 0L

    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i(TAG, "Microphone permission result: granted=$granted")
            if (granted) {
                startRecording()
            } else {
                showStatus(getString(R.string.microphone_permission_required))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "Creating voice memo activity")
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        statusText = findViewById(R.id.statusText)
        transcriptText = findViewById(R.id.transcriptText)
        classificationText = findViewById(R.id.classificationText)
        prepareButton = findViewById(R.id.prepareButton)
        recordButton = findViewById(R.id.recordButton)
        meteredCheck = findViewById(R.id.meteredCheck)
        modelManager = ModelManager(applicationContext)

        installedModels = modelManager.installedModels()
        Log.d(TAG, "Initial installed models: ${installedModels.keys}")
        updateReadyState()

        prepareButton.setOnClickListener { prepareModels() }
        recordButton.setOnClickListener {
            if (recording) stopRecording() else requestRecording()
        }
    }

    private fun prepareModels() {
        prepareButton.isEnabled = false
        recordButton.isEnabled = false
        showStatus(getString(R.string.reading_model_manifest))
        Log.i(TAG, "Starting model preparation; allowMetered=${meteredCheck.isChecked}")
        modelManager.prepareModels(
            allowMetered = meteredCheck.isChecked,
            onProgress = { message -> runOnUiThread { showStatus(message) } },
            onComplete = { result ->
                runOnUiThread {
                    prepareButton.isEnabled = true
                    result.onSuccess {
                        Log.i(TAG, "Model preparation successful; models=${it.keys}")
                        installedModels = it
                        showStatus(getString(R.string.models_ready))
                        updateReadyState()
                    }.onFailure {
                        Log.e(TAG, "Model preparation failed", it)
                        showStatus(
                            getString(
                                R.string.model_preparation_failed,
                                it.message.orEmpty(),
                            ),
                        )
                        updateReadyState()
                    }
                }
            },
        )
    }

    private fun updateReadyState() {
        val voskReady = installedModels.containsKey(VOSK_MODEL_ID)
        val textReady = installedModels.containsKey(TEXT_MODEL_ID)
        val ready = voskReady && textReady
        
        Log.d(TAG, "Ready state: vosk=$voskReady, mobileBert=$textReady, overall=$ready")
        
        recordButton.isEnabled = ready
        if (ready) showStatus(getString(R.string.models_ready))
    }

    private fun requestRecording() {
        val permissionGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "Recording requested; microphonePermission=$permissionGranted")
        if (permissionGranted) {
            startRecording()
        } else {
            Log.i(TAG, "Requesting microphone permission")
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        val voskPath = installedModels[VOSK_MODEL_ID]?.path
        if (voskPath == null) {
            Log.e(TAG, "Cannot start recording: Vosk model path is missing")
            return
        }
        
        recordButton.isEnabled = false
        transcriptAccumulator.reset()
        showStatus(getString(R.string.loading_asr))
        Log.i(TAG, "Starting ASR initialization; modelPath=$voskPath")

        inferenceExecutor.execute {
            runCatching {
                if (voskModel == null) {
                    Log.i(TAG, "Loading Vosk model into memory")
                    voskModel = Model(voskPath)
                } else {
                    Log.d(TAG, "Reusing in-memory Vosk model")
                }
                val recognizer = Recognizer(voskModel, 16_000f)
                SpeechService(recognizer, 16_000f)
            }.onSuccess { service ->
                Log.i(TAG, "Vosk SpeechService initialized")
                speechService = service
                inferenceStartedAt = SystemClock.elapsedRealtime()
                service.startListening(this)
                runOnUiThread {
                    recording = true
                    transcriptText.text = getString(R.string.listening)
                    classificationText.text = getString(R.string.sentiment_after_transcription)
                    recordButton.text = getString(R.string.stop_recording)
                    recordButton.isEnabled = true
                    showStatus(getString(R.string.recording_offline))
                }
            }.onFailure {
                Log.e(TAG, "Failed to initialize Vosk ASR", it)
                runOnUiThread {
                    recordButton.isEnabled = true
                    showStatus(getString(R.string.asr_load_failed, it.message.orEmpty()))
                }
            }
        }
    }

    private fun stopRecording() {
        Log.i(TAG, "Stopping recording and requesting final ASR result")
        recordButton.isEnabled = false
        showStatus(getString(R.string.finishing_transcript))
        val stopped = speechService?.stop() == true
        Log.d(TAG, "Vosk stop request accepted=$stopped")
    }

    override fun onPartialResult(hypothesis: String?) {
        val partial = hypothesis?.jsonText("partial").orEmpty()
        if (partial.isNotBlank()) {
            Log.v(TAG, "Partial ASR result received; characters=${partial.length}")
            val preview = transcriptAccumulator.preview(partial)
            runOnUiThread { transcriptText.text = preview }
        }
    }

    override fun onResult(hypothesis: String?) {
        val text = hypothesis?.jsonText("text").orEmpty()
        if (text.isNotBlank()) {
            Log.d(TAG, "Intermediate ASR result received; characters=${text.length}")
            transcriptAccumulator.addCompleted(text)
            val transcript = transcriptAccumulator.preview("")
            runOnUiThread { transcriptText.text = transcript }
        }
    }

    override fun onFinalResult(hypothesis: String?) {
        val finalTail = hypothesis?.jsonText("text").orEmpty()
        val transcript = transcriptAccumulator.finish(finalTail)
        Log.i(TAG, "Final ASR result received; characters=${transcript.length}")
        releaseSpeechSession()
        runOnUiThread {
            recording = false
            recordButton.text = getString(R.string.record_memo)
            transcriptText.text = transcript.ifBlank {
                getString(R.string.no_speech_detected)
            }
        }
        classifyTranscript(transcript)
    }

    override fun onError(exception: Exception?) {
        Log.e(TAG, "ASR error", exception)
        releaseSpeechSession()
        runOnUiThread {
            recording = false
            recordButton.text = getString(R.string.record_memo)
            recordButton.isEnabled = true
            showStatus(getString(R.string.asr_error, exception?.message.orEmpty()))
        }
    }

    override fun onTimeout() {
        Log.w(TAG, "ASR timeout")
        stopRecording()
    }

    private fun classifyTranscript(transcript: String) {
        if (transcript.isBlank()) {
            Log.w(TAG, "Skipping classification for blank transcript")
            runOnUiThread {
                classificationText.text = getString(R.string.no_speech_detected)
                recordButton.isEnabled = true
            }
            return
        }

        val modelPath = installedModels[TEXT_MODEL_ID]?.path
        if (modelPath == null) {
            Log.e(TAG, "Cannot classify: MobileBERT model path is missing")
            runOnUiThread { recordButton.isEnabled = true }
            return
        }

        Log.i(TAG, "Starting MobileBERT classification; characters=${transcript.length}")
        inferenceExecutor.execute {
            val result = runCatching {
                if (textClassifier == null) {
                    Log.i(TAG, "Creating MobileBERT classifier adapter")
                    textClassifier = MemoTextClassifier(applicationContext, File(modelPath))
                } else {
                    Log.d(TAG, "Reusing in-memory MobileBERT classifier")
                }
                textClassifier!!.classify(transcript)
            }
            val latency = SystemClock.elapsedRealtime() - inferenceStartedAt
            runOnUiThread {
                result.onSuccess {
                    Log.i(TAG, "Pipeline completed; latencyMs=$latency")
                    classificationText.text = it
                    showStatus(getString(R.string.pipeline_finished, latency))
                }.onFailure {
                    Log.e(TAG, "MobileBERT classification failed", it)
                    classificationText.text = getString(R.string.sentiment_failed)
                    showStatus(getString(R.string.text_model_error, it.message.orEmpty()))
                }
                recordButton.isEnabled = true
            }
        }
    }

    private fun String.jsonText(key: String): String =
        runCatching { JSONObject(this).optString(key) }.getOrDefault("")

    private fun showStatus(message: String) {
        Log.d(TAG, "UI status: $message")
        statusText.text = message
    }

    private fun releaseSpeechSession() {
        Log.d(TAG, "Releasing microphone recording session")
        speechService?.shutdown()
        speechService = null
    }

    override fun onDestroy() {
        Log.i(TAG, "Destroying activity and closing inference resources")
        releaseSpeechSession()
        textClassifier?.close()
        voskModel?.close()
        modelManager.close()
        inferenceExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VoiceMemoActivity"
        private const val VOSK_MODEL_ID = "vosk-small-en-us"
        private const val TEXT_MODEL_ID = "mobilebert-text-classifier"
    }
}
