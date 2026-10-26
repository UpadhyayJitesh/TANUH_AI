package com.tanuh.demo.sdktest

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.tanuh.demo.R
import com.tanuh.edgesdk.EdgeSession
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Test bench for the candidate SDK (:edgesdk).
 *
 *  1. Edit / apply the JSON config (models + paths, run_models, loading strategy, preprocessing steps).
 *  2. Validate paths, load the selected models, watch load time and Java/native/PSS deltas.
 *  3. Pick suspicious images from the gallery (system photo picker, no storage permission) and run.
 *
 * Config lookup order: <external files>/sdk-config.json if present, else assets/sdk-config.default.json.
 * Each run's report is also written to <external files>/reports/ for `adb pull`.
 */
class SdkTestActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var configSource: TextView
    private lateinit var configEdit: EditText
    private lateinit var pickedText: TextView
    private lateinit var previewStrip: LinearLayout
    private lateinit var reportText: TextView
    private lateinit var runButton: Button

    private val worker = Executors.newSingleThreadExecutor()
    private var session: EdgeSession? = null
    private var pickedUris: List<Uri> = emptyList()
    private val report = StringBuilder()

    private val externalDir: File by lazy { getExternalFilesDir(null) ?: filesDir }
    private val configFile: File get() = File(externalDir, CONFIG_FILE_NAME)

    /** Copies picked model files into <filesDir>/models in 64 KB chunks (never the whole file in memory). */
    private val importModels = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        showStatus("Importing ${uris.size} file(s)…")
        worker.execute {
            val dir = File(filesDir, "models").apply { mkdirs() }
            val text = buildString {
                appendLine("Import into ${dir.absolutePath}")
                for (uri in uris) {
                    val name = displayName(uri)
                    val t0 = System.nanoTime()
                    val result = runCatching {
                        val target = File(dir, name)
                        val tmp = File(dir, "$name.part")
                        contentResolver.openInputStream(uri)!!.use { input ->
                            tmp.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
                        }
                        check(tmp.renameTo(target)) { "rename failed" }
                        target.length()
                    }
                    appendLine(result.fold(
                        { "  $name: %.1f MB in %.0f ms".format(it / 1048576.0, (System.nanoTime() - t0) / 1e6) },
                        { "  $name: FAILED ${it.message}" },
                    ))
                }
            }
            runOnUiThread { appendReport(text); showStatus("Import done. Tap Validate.") }
        }
    }

    private val pickImages = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES)) { uris ->
        pickedUris = uris
        pickedText.text = if (uris.isEmpty()) getString(R.string.sdk_no_images) else "${uris.size} image(s) picked"
        runButton.isEnabled = uris.isNotEmpty()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_sdk_test)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.sdkRoot)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        status = findViewById(R.id.sdkStatus)
        configSource = findViewById(R.id.configSource)
        configEdit = findViewById(R.id.configEdit)
        pickedText = findViewById(R.id.pickedText)
        previewStrip = findViewById(R.id.previewStrip)
        reportText = findViewById(R.id.reportText)
        runButton = findViewById(R.id.runButton)

        loadInitialConfig()

        findViewById<Button>(R.id.applyConfigButton).setOnClickListener { applyConfig() }
        findViewById<Button>(R.id.resetConfigButton).setOnClickListener {
            configEdit.setText(assets.open(DEFAULT_ASSET).bufferedReader().use { it.readText() })
            configSource.text = "Source: assets/$DEFAULT_ASSET (not applied yet)"
        }
        findViewById<Button>(R.id.saveConfigButton).setOnClickListener { saveConfig() }
        findViewById<Button>(R.id.validateButton).setOnClickListener { withSession("Validating") { it.validate() } }
        findViewById<Button>(R.id.loadButton).setOnClickListener { withSession("Loading models") { it.loadSelected() } }
        findViewById<Button>(R.id.unloadButton).setOnClickListener { withSession("Unloading") { it.unloadAll() } }
        findViewById<Button>(R.id.importModelsButton).setOnClickListener { importModels.launch(arrayOf("*/*")) }
        findViewById<Button>(R.id.pickButton).setOnClickListener {
            pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        runButton.setOnClickListener { runPicked() }
        findViewById<Button>(R.id.copyReportButton).setOnClickListener {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("sdk report", report.toString()))
            Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
        }

        showStatus("Models dir: ${File(filesDir, "models").absolutePath}\nImport models (once) → Apply → Validate → Load → Pick images → Run.")
        applyConfig()
    }

    private fun loadInitialConfig() {
        val (text, source) = if (configFile.isFile) {
            configFile.readText() to "Source: ${configFile.absolutePath}"
        } else {
            assets.open(DEFAULT_ASSET).bufferedReader().use { it.readText() } to
                "Source: assets/$DEFAULT_ASSET (push your own to ${configFile.absolutePath})"
        }
        configEdit.setText(text)
        configSource.text = source
    }

    private fun applyConfig() {
        val json = configEdit.text.toString()
        worker.execute {
            val result = runCatching {
                session?.close()
                session = null
                EdgeSession.create(applicationContext, json).also { session = it }
            }
            runOnUiThread {
                result.onSuccess {
                    val byPipe = it.config.selectedModels().groupBy { m -> m.pipeline }.entries.joinToString { (p, ms) -> "$p: ${ms.size}" }
                    showStatus("Config applied (schema ${it.config.schemaVersion}): ${it.config.runModels.size} model(s) selected [$byPipe], strategy=${it.config.loading.strategy}. Models are not loaded yet.")
                }.onFailure {
                    Log.e(TAG, "Invalid config", it)
                    showStatus("Config error: ${it.message}")
                }
            }
        }
    }

    private fun saveConfig() {
        runCatching {
            JSONObject(configEdit.text.toString())   // refuse to save something that is not JSON
            configFile.parentFile?.mkdirs()
            configFile.writeText(configEdit.text.toString())
        }.onSuccess {
            configSource.text = "Source: ${configFile.absolutePath}"
            Toast.makeText(this, "Saved to ${configFile.absolutePath}", Toast.LENGTH_LONG).show()
        }.onFailure { showStatus("Save failed: ${it.message}") }
    }

    /** Runs [block] on the worker thread against the current session and appends its text to the report. */
    private fun withSession(what: String, block: (EdgeSession) -> String) {
        val s = session ?: return showStatus("Apply a valid config first.")
        showStatus("$what…")
        worker.execute {
            val text = try { block(s) } catch (t: Throwable) { Log.e(TAG, what, t); "$what failed: ${t.javaClass.simpleName}: ${t.message}" }
            runOnUiThread { appendReport(text); showStatus("$what: done") }
        }
    }

    private fun runPicked() {
        val s = session ?: return showStatus("Apply a valid config first.")
        val uris = pickedUris
        if (uris.isEmpty()) return showStatus("Pick images first.")
        runButton.isEnabled = false
        previewStrip.removeAllViews()
        showStatus("Running ${uris.size} image(s)…")
        worker.execute {
            val runLog = StringBuilder()
            for ((i, uri) in uris.withIndex()) {
                runOnUiThread { showStatus("Image ${i + 1}/${uris.size}…") }
                val text = try {
                    val r = s.inferImage(uri, displayName(uri))   // SDK decodes per pipeline settings, runs, combines
                    r.preview?.let { p -> runOnUiThread { addPreview(p, r.headline) } }
                    r.text
                } catch (t: Throwable) {        // includes OutOfMemoryError, which is part of what we're testing
                    Log.e(TAG, "Inference failed for $uri", t)
                    "━━ ${displayName(uri)}: FAILED ${t.javaClass.simpleName}: ${t.message}\n"
                }
                runLog.append(text)
                runOnUiThread { appendReport(text) }
            }
            val saved = saveReport(runLog.toString())
            runOnUiThread {
                runButton.isEnabled = true
                showStatus("Done. Report saved to $saved")
            }
        }
    }

    private fun addPreview(bitmap: Bitmap, label: String) {
        val size = (resources.displayMetrics.density * 112).toInt()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 12, 0) }
        column.addView(ImageView(this).apply { setImageBitmap(bitmap); layoutParams = LinearLayout.LayoutParams(size, size) })
        column.addView(TextView(this).apply { text = label; textSize = 11f })
        previewStrip.addView(column)
    }

    private fun appendReport(text: String) {
        report.append(text).append('\n')
        reportText.text = report
        text.lines().forEach { Log.i(TAG, it) }
    }

    private fun saveReport(text: String): String = runCatching {
        val dir = File(externalDir, "reports").apply { mkdirs() }
        val f = File(dir, "report-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt")
        f.writeText(text)
        f.absolutePath
    }.getOrElse { "(not saved: ${it.message})" }

    private fun displayName(uri: Uri): String =
        runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: uri.toString()

    private fun showStatus(msg: String) { status.text = msg }

    override fun onDestroy() {
        worker.execute { session?.close(); session = null }
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SdkTest"
        private const val DEFAULT_ASSET = "sdk-config.default.json"
        private const val CONFIG_FILE_NAME = "sdk-config.json"
        private const val MAX_IMAGES = 20
    }
}
