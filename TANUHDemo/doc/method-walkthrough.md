# TANUHDemo Method Walkthrough

This guide explains the important methods in execution order. It is intended as
a code handoff: what calls each method, what it does, why it exists, what thread
it runs on, and how success or failure continues through the system.

## End-to-end call chain

```text
MainActivity.onCreate()
  -> ModelManager.installedModels()
  -> MainActivity.updateReadyState()

User taps Prepare AI Models
  -> MainActivity.prepareModels()
  -> ModelManager.prepareModels()
       -> ModelManager.installedModels()
       -> ConnectivityPolicy.canDownload()
       -> ModelManager.fetchManifest()
            -> ModelManager.openConnection()
            -> ModelManifest.parse()
       -> ModelManager.validateSpec()
       -> ModelManager.ensureInstalled()
            -> ModelManager.download()
            -> ModelManager.openConnection()
            -> ModelManager.verifyChecksum()
            -> ModelManager.unzipSafely() or TFLite staging
            -> atomic activation and registry update
  -> MainActivity.updateReadyState()

User taps Record Memo
  -> MainActivity.requestRecording()
  -> MainActivity.startRecording()
  -> Vosk RecognitionListener callbacks
       -> onPartialResult()
       -> onResult()
       -> onFinalResult()
            -> TranscriptAccumulator.finish()
            -> MainActivity.classifyTranscript()
                 -> MemoSentimentClassifier.classify()
```

# Part 1: Application startup

## `MainActivity.onCreate()`

### Purpose

`onCreate()` is the entry point for the screen. It prepares the UI, restores
cached model state, and connects user actions to the model and inference flows.

### Step-by-step

1. **Create the activity**

   Android invokes `onCreate()` when the screen starts.

2. **Enable edge-to-edge layout**

   `enableEdgeToEdge()` allows content to draw behind system bars.
   The inset listener then applies safe padding so content is not hidden.

3. **Load and bind the UI**

   `setContentView()` inflates `activity_main.xml`. `findViewById()` stores
   references to the status, transcript, sentiment, checkbox, and buttons.

4. **Create the model manager**

   `ModelManager(applicationContext)` receives a context that outlives the
   activity and can safely access private files and preferences.

5. **Restore active models**

   `modelManager.installedModels()` checks the private registry and filesystem.
   It returns only model entries that are complete enough to use.

6. **Update readiness**

   `updateReadyState()` enables recording only if both required models are
   available.

7. **Register click actions**

   - Prepare button -> `prepareModels()`
   - Record button -> `requestRecording()` or `stopRecording()`

### Thread

Main/UI thread.

### Output

The screen is ready, cached models are restored, and buttons reflect current
availability.

---

## `ModelManager.installedModels()`

### Purpose

Reconstructs the set of active models from app-private metadata.

### Step-by-step

1. Iterate over `REQUIRED_MODEL_IDS`.
2. Read each model's version, active path, runtime, format, URL, SHA-256, and
   size from `SharedPreferences`.
3. Reject the entry if any required metadata is absent.
4. Reject the entry if the active path no longer exists.
5. Rebuild an `InstalledModel` for each valid entry.
6. Return a map keyed by model ID.

### Why this exists

Models are much larger than normal preferences. Their bytes live in private
files, while the lightweight registry tells the app which version/path is
active.

### Important limitation

This method checks file presence but does not calculate SHA-256 on every launch.
Integrity is verified during download and activation.

### Thread

Called on the UI thread during startup and on the model-manager executor during
preparation. Its work is small preference and filesystem metadata access.

---

## `MainActivity.updateReadyState()`

### Purpose

Converts model availability into UI state.

### Step-by-step

1. Check for `vosk-small-en-us`.
2. Check for `mobilebert-text-classifier`.
3. Enable **Record Memo** only when both exist.
4. Show the ready message when the pipeline is usable.

### Why both are required

The assignment requires one combined workflow. Enabling recording with only one
model would allow a partial pipeline that cannot complete.

# Part 2: OTA model preparation

## `MainActivity.prepareModels()`

### Purpose

Acts as the UI-side entry point for model preparation.

### Step-by-step

1. Disable **Prepare AI Models** to prevent duplicate requests.
2. Disable **Record Memo** while model state may be changing.
3. Show `Reading model manifest`.
4. Read the metered-download checkbox.
5. Call `ModelManager.prepareModels()`.
6. Forward progress messages to the UI using `runOnUiThread`.
7. Handle the final `Result`:
   - Success: store returned models and update readiness.
   - Failure: show the error and leave recording disabled unless a complete
     cache is still available.

### Thread

Starts on the UI thread. Actual model work moves to the model-manager executor.
Callbacks explicitly switch back to the UI thread.

---

## `ModelManager.prepareModels()`

### Purpose

This is the core orchestrator for the Over-The-Air model lifecycle. It ensures
that all required models are cached, trusted, version-correct, and ready for
inference.

### Inputs

- `allowMetered`: whether mobile/metered downloads are allowed.
- `onProgress`: emits human-readable stage updates.
- `onComplete`: returns either the ready model map or an error.

### Step-by-step

1. **Background execution**

   The method submits all work to a single-thread `ExecutorService`. Network,
   hashing, copying, and ZIP extraction cannot freeze the UI.

2. **Start timing and error capture**

   `SystemClock.elapsedRealtime()` records duration. `runCatching` converts any
   thrown exception into a `Result`.

3. **Read the current cache**

   `installedModels()` determines whether complete active models already exist.

4. **Evaluate connectivity**

   `ConnectivityPolicy.canDownload()` checks validated and metered status.

5. **Offline cache fallback**

   If downloads are not allowed but both models exist, preparation returns the
   cached models immediately. This enables offline inference after first setup.

6. **Reject an unusable fresh install**

   Without suitable connectivity or a complete cache, the method throws a clear
   error instead of attempting a doomed download.

7. **Create private model storage**

   `files/edge-models` is created when necessary.

8. **Fetch the authoritative manifest**

   `fetchManifest()` downloads and parses the Git-hosted JSON catalog.

9. **Validate catalog compatibility**

   The method requires schema version `1` and confirms that every required model
   ID is present.

10. **Validate every model specification**

    `validateSpec()` checks HTTPS, SHA-256 shape, and positive byte size.

11. **Prepare required models**

    The manifest is filtered to required IDs. `ensureInstalled()` handles cache
    reuse, downloading, verification, and activation for each model.

12. **Return one ready map**

    `associate` produces `Map<String, InstalledModel>`, ensuring callers receive
    paths for both models.

13. **Report completion**

    Success/failure and total duration are logged. `onComplete(result)` returns
    control to `MainActivity`.

### Failure behavior

Any network, JSON, schema, download, size, checksum, extraction, or activation
error ends preparation with `Result.failure`. A candidate is not marked active
unless every trust and staging step succeeds.

### Thread

Dedicated single model-manager background thread.

---

## `ConnectivityPolicy.canDownload()`

### Purpose

Applies the app's model-download network policy.

### Step-by-step

1. Ask `ConnectivityManager` for the active network.
2. Read its `NetworkCapabilities`.
3. Require `NET_CAPABILITY_VALIDATED`.
4. Check `NET_CAPABILITY_NOT_METERED`.
5. Allow the request when:

```text
validated AND (unmetered OR user allowed metered)
```

### Output

`true` when model downloads are permitted; otherwise `false`.

---

## `ModelManager.fetchManifest()`

### Purpose

Downloads the authoritative model catalog and converts it into Kotlin objects.

### Step-by-step

1. Call `openConnection(manifestUrl)`.
2. Read the response body as text.
3. Pass the JSON to `ModelManifest.parse()`.
4. Return the parsed `ModelManifest`.
5. Log download/parse duration.

### Failure behavior

HTTP failures, timeouts, invalid JSON, or missing required JSON fields propagate
to `prepareModels()` as a failed `Result`.

---

## `ModelManifest.parse()`

### Purpose

Transforms the remote JSON wire format into strongly typed model metadata.

### Step-by-step

1. Parse the root `JSONObject`.
2. Read the `models` array.
3. For every entry, require:
   - `id`
   - `version`
   - `runtime`
   - `format`
   - `url`
   - `sha256`
   - `size`
4. Create a `ModelSpec`.
5. Return `ModelManifest(schemaVersion, models)`.

### Why required getters are used

`getString()` and `getLong()` fail immediately when integrity metadata is
missing. Silently accepting absent values would weaken the OTA trust contract.

---

## `ModelManager.validateSpec()`

### Purpose

Rejects structurally unsafe catalog entries before downloading bytes.

### Checks

1. URL begins with HTTPS.
2. SHA-256 contains exactly 64 hexadecimal characters.
3. Declared size is positive.

### Note

This validates metadata shape. `verifyChecksum()` later validates the actual
downloaded bytes.

# Part 3: Per-model installation

## `ModelManager.ensureInstalled()`

### Purpose

Ensures one `ModelSpec` has a trusted active local installation.

### Step-by-step

1. **Read active metadata**

   Load the currently active version and path from preferences.

2. **Use a valid cache hit**

   If the active version equals the remote version and the path exists, return
   it without downloading.

3. **Create versioned paths**

```text
<version>.download   temporary network output
<version>.staged/    verified model being prepared
<version>/           active version
```

4. **Remove an old temporary download**

   An interrupted previous attempt is not resumed; it is restarted cleanly.

5. **Download candidate bytes**

   `download()` writes to the temporary file.

6. **Verify exact file size**

   Local bytes must equal `spec.size`.

7. **Verify SHA-256**

   `verifyChecksum()` proves that the bytes match the manifest.

8. **Create a clean staging directory**

   Existing staging content is deleted before use.

9. **Prepare by format**

   - `zip`: safely extract Vosk and select its root directory.
   - `tflite`: copy MobileBERT to a named `.tflite` file.

10. **Delete the temporary download**

    The verified staged representation is now sufficient.

11. **Activate**

    Delete any incomplete directory for the candidate version and rename the
    staged directory to the final version directory.

12. **Calculate the final inference path**

    Vosk points to a directory. MobileBERT points to a `.tflite` file.

13. **Commit registry metadata**

    Store previous version/path, then the new active metadata.

14. **Return `InstalledModel`**

    Inference code receives only the final active path.

### Safety property

The registry update occurs last. A failed download, checksum, extraction, or
rename cannot make the candidate appear active.

---

## `ModelManager.download()`

### Purpose

Streams a remote artifact into a temporary file.

### Step-by-step

1. Open an HTTP connection.
2. Wrap the response in `BufferedInputStream`.
3. Open `FileOutputStream` for the temporary destination.
4. Copy bytes until end of stream.
5. Call `output.fd.sync()` to request disk persistence.
6. Close streams automatically with `use`.
7. Log byte count and duration.

### Current limitation

There is no HTTP range resume or byte-level UI progress. A retry starts again.

---

## `ModelManager.openConnection()`

### Purpose

Provides one consistent HTTP configuration for manifest and model requests.

### Configuration

- 15-second connect timeout.
- 60-second read timeout.
- Redirect following enabled.
- `TANUHDemo/1.0` user agent.
- Only HTTP 2xx responses accepted.

### Output

An already connected `HttpURLConnection`.

---

## `ModelManager.verifyChecksum()`

### Purpose

Proves that downloaded bytes match the trusted manifest.

### Step-by-step

1. Create a SHA-256 `MessageDigest`.
2. Read the temporary file in buffers.
3. Update the digest for every byte.
4. Convert the resulting digest to lowercase hexadecimal.
5. Compare it with the manifest value, ignoring case.
6. Throw if the values differ.

### Why size and checksum are both checked

Size catches truncation quickly. SHA-256 verifies content identity and detects
same-size corruption or substitution.

---

## `ModelManager.unzipSafely()`

### Purpose

Extracts Vosk while preventing ZIP path traversal.

### Step-by-step

1. Calculate the canonical destination path.
2. Iterate over ZIP entries.
3. Resolve each output file.
4. Require its canonical path to remain inside the staging directory.
5. Create directories or copy file bytes.
6. Count and log extracted entries.

### Security reason

A malicious entry such as `../../outside-file` must not escape app-controlled
staging storage.

---

## Registry activation

The final `SharedPreferences` edit records:

```text
previousVersion
previousPath
version
path
runtime
format
url
sha256
size
```

Previous metadata supports last-known-good preservation and future rollback
work. The current implementation does not automatically switch back after a
runtime load failure.

# Part 4: Recording and transcription

## `MainActivity.requestRecording()`

### Purpose

Ensures Android microphone permission exists before creating `AudioRecord`.

### Step-by-step

1. Check `RECORD_AUDIO`.
2. If granted, call `startRecording()`.
3. Otherwise launch Android's permission dialog.
4. The registered permission callback either starts recording or shows an error.

---

## `MainActivity.startRecording()`

### Purpose

Creates the on-device Vosk recording session.

### Step-by-step

1. Read the active Vosk path.
2. Disable the button during initialization.
3. Reset `TranscriptAccumulator`.
4. Move initialization to `inferenceExecutor`.
5. Load `org.vosk.Model` once, or reuse it.
6. Create a 16 kHz `Recognizer`.
7. Create `SpeechService`.
8. Start listening with `MainActivity` as `RecognitionListener`.
9. Save the pipeline start time.
10. Update UI state on the main thread.

### Why the model is reused

Vosk model loading is comparatively expensive. Keeping it in memory makes later
recordings start faster.

---

## `MainActivity.stopRecording()`

### Purpose

Stops microphone capture and asks Vosk to emit its final uncommitted result.

### Step-by-step

1. Disable the button to prevent a second stop request.
2. Show `Finishing transcript`.
3. Call `SpeechService.stop()`.
4. Vosk eventually invokes `onFinalResult()`.

---

## `onPartialResult()`

### Purpose

Displays a live, unstable preview while the user speaks.

### Flow

1. Parse the `partial` field from Vosk JSON.
2. Combine it with already completed segments using
   `TranscriptAccumulator.preview()`.
3. Update the transcript view.

The partial text is not permanently committed because Vosk may revise it.

---

## `onResult()`

### Purpose

Commits a completed speech segment.

### Flow

1. Parse the `text` field.
2. Store it with `TranscriptAccumulator.addCompleted()`.
3. Render all committed segments.

Vosk may call this multiple times during one memo.

---

## `onFinalResult()`

### Purpose

Finalizes the transcript and starts sentiment analysis.

### Step-by-step

1. Parse the remaining `text` tail.
2. Call `TranscriptAccumulator.finish(finalTail)`.
3. Release the microphone session.
4. Reset the record button and show the full transcript.
5. Call `classifyTranscript(transcript)`.

### Important Vosk behavior

The final callback contains only the remaining tail, not necessarily the full
memo. `TranscriptAccumulator` prevents earlier completed segments from being
lost when the tail is empty.

---

## `TranscriptAccumulator`

### `reset()`

Clears all segments before a new recording.

### `addCompleted(text)`

Trims and permanently stores a completed Vosk segment.

### `preview(partial)`

Returns completed segments plus the current partial text without committing the
partial.

### `finish(finalTail)`

Commits the final tail and returns the complete memo.

# Part 5: MobileBERT sentiment

## `MainActivity.classifyTranscript()`

### Purpose

Runs the second model on the Vosk transcript and updates the UI.

### Step-by-step

1. Reject a blank transcript.
2. Read the active MobileBERT path.
3. Move model work to `inferenceExecutor`.
4. Create `MemoSentimentClassifier` once, or reuse it.
5. Call `classify(transcript)`.
6. Calculate total latency from recording start.
7. Return to the UI thread.
8. Display sentiment scores or an error.
9. Re-enable recording.

### Failure behavior

The transcript remains visible even if MobileBERT fails. This makes the first
model's successful output available for diagnosis.

---

## `MemoSentimentClassifier` initialization

### Purpose

Loads the OTA TFLite model into MediaPipe Tasks.

### Step-by-step

1. Open the model file.
2. Memory-map it as a read-only buffer.
3. Build MediaPipe `BaseOptions` with the buffer.
4. Configure a maximum of three results.
5. Create `TextClassifier`.

### Why memory mapping is used

It avoids manually copying the whole model into an additional byte array and is
appropriate for a read-only local model file.

---

## `MemoSentimentClassifier.classify()`

### Purpose

Converts transcript text into human-readable sentiment scores.

### Step-by-step

1. Reject blank input.
2. Call MediaPipe `classifier.classify(text)`.
3. Flatten categories from the classification result.
4. Sort categories by descending score.
5. Convert scores into percentages.
6. Return lines such as:

```text
negative: 90.1%
positive: 9.9%
```

### Model limitation

This MobileBERT model has positive and negative labels only. Neutral reminders
are forced into one of those classes.

# Part 6: Cleanup

## `releaseSpeechSession()`

Releases Vosk's `SpeechService` and Android `AudioRecord` after a final result or
error. The Vosk model remains loaded for reuse.

## `MainActivity.onDestroy()`

Closes:

1. Active microphone service.
2. MediaPipe sentiment classifier.
3. Vosk model.
4. Model-manager executor.
5. Inference executor.

This prevents microphone, native model, and thread leaks when the activity is
destroyed.

## `ModelManager.close()`

Stops pending model-manager work with `shutdownNow()`.

# Part 7: Thread summary

| Thread | Methods/work |
| --- | --- |
| Main thread | Lifecycle, button clicks, permission result, UI updates, Vosk callbacks |
| Model-manager executor | Manifest, downloads, hashing, extraction, activation |
| Inference executor | Vosk setup, MobileBERT loading, sentiment inference |

# Part 8: Suggested debugging path

When a flow fails, follow these methods in order.

## Models do not prepare

1. `MainActivity.prepareModels()`
2. `ModelManager.prepareModels()`
3. `ConnectivityPolicy.canDownload()`
4. `fetchManifest()`
5. `validateSpec()`
6. `ensureInstalled()`

## No transcript

1. `requestRecording()`
2. `startRecording()`
3. `onPartialResult()`
4. `onResult()`
5. `onFinalResult()`
6. `TranscriptAccumulator`

## No sentiment

1. `classifyTranscript()`
2. `MemoSentimentClassifier` initialization
3. `MemoSentimentClassifier.classify()`

Use:

```text
tag:ModelManager | tag:ConnectivityPolicy | tag:VoiceMemoActivity | tag:MemoSentimentClassifier
```
