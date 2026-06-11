package com.tanuh.demo.models

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * Owns the OTA model lifecycle.
 *
 * A model becomes active only after its byte size and SHA-256 digest match the
 * remote manifest. Downloads are staged separately and committed with a rename,
 * so interrupted or untrusted files are never returned to inference code.
 */
class ModelManager(
    private val context: Context,
    private val manifestUrl: String = DEFAULT_MANIFEST_URL,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    private val connectivityPolicy = ConnectivityPolicy(context)
    private val preferences = context.getSharedPreferences("model_registry", Context.MODE_PRIVATE)
    private val modelsDirectory = File(context.filesDir, "edge-models")

    /** Returns only registry entries whose active file still exists on disk. */
    fun installedModels(): Map<String, InstalledModel> {
        Log.d(TAG, "Reading installed model registry")
        return REQUIRED_MODEL_IDS.mapNotNull { id ->
            val installedVersion = preferences.getString("$id.version", null)
            val installedPath = preferences.getString("$id.path", null)
            val runtime = preferences.getString("$id.runtime", null)
            val format = preferences.getString("$id.format", null)
            val url = preferences.getString("$id.url", null)
            val sha256 = preferences.getString("$id.sha256", null)
            val size = preferences.getLong("$id.size", -1)

            if (
                installedVersion != null &&
                installedPath != null &&
                runtime != null &&
                format != null &&
                url != null &&
                sha256 != null &&
                size >= 0 &&
                File(installedPath).exists()
            ) {
                Log.i(TAG, "Cache entry ready: id=$id, version=$installedVersion, bytes=$size")
                id to InstalledModel(
                    ModelSpec(id, installedVersion, runtime, format, url, sha256, size),
                    installedPath,
                )
            } else {
                Log.i(TAG, "Cache miss: id=$id")
                null
            }
        }.toMap()
    }

    fun prepareModels(
        allowMetered: Boolean,
        onProgress: (String) -> Unit,
        onComplete: (Result<Map<String, InstalledModel>>) -> Unit,
    ) {
        Log.i(TAG, "Preparing required models; allowMetered=$allowMetered")
        executor.execute {
            val startedAt = SystemClock.elapsedRealtime()
            val result = runCatching {
                val cachedModels = installedModels()
                if (!connectivityPolicy.canDownload(allowMetered)) {
                    if (cachedModels.keys.containsAll(REQUIRED_MODEL_IDS)) {
                        Log.i(TAG, "Network unavailable or disallowed; using complete verified cache")
                        onProgress("Offline: using the verified model cache")
                        return@runCatching cachedModels
                    }
                    error(
                        "A validated ${if (allowMetered) "network" else "unmetered network"} " +
                            "is required to fetch the model catalog",
                    )
                }

                modelsDirectory.mkdirs()
                val manifest = fetchManifest()

                Log.d(
                    TAG,
                    "Validating catalog schema=${manifest.schemaVersion}, models=${manifest.models.size}",
                )
                require(manifest.schemaVersion == 1) {
                    "Unsupported manifest schema ${manifest.schemaVersion}"
                }
                require(manifest.models.map { it.id }.containsAll(REQUIRED_MODEL_IDS)) {
                    "Remote manifest does not contain every required model"
                }
                manifest.models.forEach(::validateSpec)

                manifest.models
                    .filter { it.id in REQUIRED_MODEL_IDS }
                    .associate { spec ->
                        onProgress("Checking ${spec.id} ${spec.version}")
                        spec.id to ensureInstalled(spec, onProgress)
                    }
            }
            result.onSuccess {
                Log.i(
                    TAG,
                    "Model preparation completed in ${SystemClock.elapsedRealtime() - startedAt} ms; " +
                        "ready=${it.keys}",
                )
            }.onFailure {
                Log.e(TAG, "Model preparation failed after ${SystemClock.elapsedRealtime() - startedAt} ms", it)
            }
            onComplete(result)
        }
    }

    fun close() {
        Log.d(TAG, "Shutting down model-manager executor")
        executor.shutdownNow()
    }

    private fun fetchManifest(): ModelManifest {
        Log.i(TAG, "Fetching model catalog: $manifestUrl")
        val startedAt = SystemClock.elapsedRealtime()
        val connection = openConnection(manifestUrl)
        val manifest = connection.inputStream.bufferedReader().use {
            val body = it.readText()
            Log.d(TAG, "Catalog downloaded: characters=${body.length}")
            ModelManifest.parse(body)
        }
        Log.i(TAG, "Catalog parsed in ${SystemClock.elapsedRealtime() - startedAt} ms")
        return manifest
    }

    private fun validateSpec(spec: ModelSpec) {
        Log.d(
            TAG,
            "Validating model entry: id=${spec.id}, version=${spec.version}, " +
                "runtime=${spec.runtime}, format=${spec.format}, bytes=${spec.size}",
        )
        require(spec.url.startsWith("https://")) { "${spec.id} must use HTTPS" }
        require(spec.sha256.matches(Regex("[a-fA-F0-9]{64}"))) {
            "${spec.id} has an invalid SHA-256 checksum"
        }
        require(spec.size > 0) { "${spec.id} has an invalid byte size" }
        Log.d(TAG, "Model entry accepted: id=${spec.id}, version=${spec.version}")
    }

    private fun ensureInstalled(
        spec: ModelSpec,
        onProgress: (String) -> Unit,
    ): InstalledModel {
        val activeVersion = preferences.getString("${spec.id}.version", null)
        val activePath = preferences.getString("${spec.id}.path", null)
        if (activeVersion == spec.version && activePath != null && File(activePath).exists()) {
            Log.i(TAG, "Using cached model: id=${spec.id}, version=${spec.version}")
            onProgress("${spec.id} is cached")
            return InstalledModel(spec, activePath)
        }

        Log.i(
            TAG,
            "Installing model: id=${spec.id}, candidate=${spec.version}, active=$activeVersion",
        )
        val modelRoot = File(modelsDirectory, spec.id)
        val versionDirectory = File(modelRoot, spec.version)
        val temporaryFile = File(modelRoot, "${spec.version}.download")
        modelRoot.mkdirs()
        temporaryFile.delete()

        onProgress("Downloading ${spec.id}")
        download(spec.url, temporaryFile)
        Log.d(TAG, "Checking downloaded size: id=${spec.id}, bytes=${temporaryFile.length()}")
        check(temporaryFile.length() == spec.size) {
            "Size mismatch for ${spec.id}: expected ${spec.size}, received ${temporaryFile.length()}"
        }
        verifyChecksum(temporaryFile, spec.sha256)

        Log.d(TAG, "Preparing staging directory: ${stagedDirectoryPath(modelRoot, spec.version)}")
        val stagedDirectory = File(modelRoot, "${spec.version}.staged")
        stagedDirectory.deleteRecursively()
        stagedDirectory.mkdirs()

        val activeFile = when (spec.format) {
            "zip" -> {
                Log.i(TAG, "Extracting ZIP model: id=${spec.id}")
                unzipSafely(temporaryFile, stagedDirectory)
                stagedDirectory.singleRootDirectory()
            }
            "file", "tflite" -> {
                Log.i(TAG, "Staging TFLite model: id=${spec.id}")
                val target = File(stagedDirectory, "${spec.id}.tflite")
                temporaryFile.copyTo(target, overwrite = true)
                target
            }
            else -> error("Unsupported model format ${spec.format}")
        }
        temporaryFile.delete()

        versionDirectory.deleteRecursively()
        Log.i(TAG, "Activating staged model: id=${spec.id}, version=${spec.version}")
        check(stagedDirectory.renameTo(versionDirectory)) {
            "Could not activate ${spec.id}"
        }

        val finalPath = if (activeFile == stagedDirectory) {
            versionDirectory.absolutePath
        } else {
            File(versionDirectory, activeFile.relativeTo(stagedDirectory).path).absolutePath
        }

        preferences.edit()
            .putString("${spec.id}.previousVersion", activeVersion)
            .putString("${spec.id}.previousPath", activePath)
            .putString("${spec.id}.version", spec.version)
            .putString("${spec.id}.path", finalPath)
            .putString("${spec.id}.runtime", spec.runtime)
            .putString("${spec.id}.format", spec.format)
            .putString("${spec.id}.url", spec.url)
            .putString("${spec.id}.sha256", spec.sha256)
            .putLong("${spec.id}.size", spec.size)
            .apply()

        Log.i(TAG, "Registry committed: id=${spec.id}, version=${spec.version}, path=$finalPath")
        onProgress("${spec.id} ${spec.version} is ready")
        return InstalledModel(spec, finalPath)
    }

    private fun download(url: String, destination: File) {
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "Download started: destination=${destination.name}")
        val connection = openConnection(url)
        BufferedInputStream(connection.inputStream).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        Log.i(
            TAG,
            "Download completed: destination=${destination.name}, bytes=${destination.length()}, " +
                "durationMs=${SystemClock.elapsedRealtime() - startedAt}",
        )
    }

    private fun openConnection(url: String): HttpURLConnection {
        Log.d(TAG, "Opening HTTPS connection: host=${URL(url).host}")
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "TANUHDemo/1.0")
            connect()
            check(responseCode in 200..299) { "HTTP $responseCode for $url" }
            Log.d(TAG, "HTTP response accepted: code=$responseCode")
        }
    }

    private fun verifyChecksum(file: File, expected: String) {
        Log.i(TAG, "SHA-256 verification started: file=${file.name}")
        val startedAt = SystemClock.elapsedRealtime()
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual.equals(expected, ignoreCase = true)) {
            "Checksum mismatch for ${file.name}"
        }
        Log.i(
            TAG,
            "SHA-256 verification passed: file=${file.name}, " +
                "durationMs=${SystemClock.elapsedRealtime() - startedAt}",
        )
    }

    private fun unzipSafely(archive: File, destination: File) {
        var entries = 0
        val destinationPath = destination.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val output = File(destination, entry.name)
                check(output.canonicalPath.startsWith(destinationPath)) {
                    "Unsafe ZIP entry ${entry.name}"
                }
                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    FileOutputStream(output).use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entries++
            }
        }
        Log.i(TAG, "ZIP extraction completed: archive=${archive.name}, entries=$entries")
    }

    private fun File.singleRootDirectory(): File {
        val children = listFiles().orEmpty()
        return if (children.size == 1 && children.first().isDirectory) children.first() else this
    }

    private fun stagedDirectoryPath(modelRoot: File, version: String): String =
        File(modelRoot, "$version.staged").absolutePath

    companion object {
        private const val TAG = "ModelManager"
        const val DEFAULT_MANIFEST_URL =
            "https://raw.githubusercontent.com/UpadhyayJitesh/edge-ai-models/main/model-manifest.json"
        private val REQUIRED_MODEL_IDS =
            setOf("vosk-small-en-us", "mobilebert-text-classifier")
    }
}
