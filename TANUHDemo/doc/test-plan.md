# Edge Voice Assistant Test Plan

This plan describes the behavior implemented by the Edge Voice Assistant demo.
MobileBERT performs sentiment classification (`positive` or `negative`); it
does not classify reminders, tasks, or action items.

## 1. Fresh install

1. Uninstall the app or clear its storage.
2. Install and launch the app.
3. Verify the app opens without local model files.
4. Verify recording is disabled until models are prepared.
5. Verify the UI asks the user to connect and prepare models.

Expected: the APK opens normally and does not contain either model.

## 2. OTA model download

1. Connect the device to validated Wi-Fi.
2. Tap **Prepare models**.
3. Observe status transitions for:
   - `vosk-small-en-us`
   - `mobilebert-text-classifier`
4. Filter Logcat using:

```text
tag:ModelManager | tag:ConnectivityPolicy
```

Expected: the app fetches the remote manifest, downloads both models, verifies
their byte sizes and SHA-256 hashes, activates them in app-private storage, and
enables recording.

Note: the UI shows the current stage, not byte-level percentage progress.

Open **Model info** after preparation. Expected: both models show their active
manifest versions and `SHA-256 verified at activation`. This is persisted
verification metadata; opening the panel does not rehash the files.

## 3. Connectivity policy

1. Clear app storage so no models are cached.
2. Disable Wi-Fi and keep mobile data enabled.
3. Leave **Allow downloads on metered networks** unchecked.
4. Tap **Prepare models**.
5. Enable the metered option and retry.

Expected: the first request is rejected; the opted-in request can proceed on a
validated mobile network.

## 4. No network

1. Clear app storage.
2. Enable airplane mode.
3. Launch the app and tap **Prepare models**.

Expected: the app remains open, reports that a validated network is required,
and does not enable recording.

## 5. Cached model reuse

1. Prepare both models successfully.
2. Close and reopen the app.
3. Confirm recording is enabled without downloading.
4. Tap **Prepare models** while offline.

Expected: the registry restores both active paths and the verified cache remains
usable without network access.

## 6. Version update

1. Publish a model artifact with a new URL, version, size, and SHA-256.
2. Update the remote `model-manifest.json`.
3. Launch the app and tap **Prepare models**.

Expected: the candidate version is downloaded and verified. The registry changes
to the new version only after staging and activation succeed.

## 7. SHA-256 rejection

1. Publish a test manifest version with an incorrect 64-character SHA-256.
2. Tap **Prepare models**.

Expected: verification fails, the candidate is not activated, and any previously
active version remains registered.

## 8. File-size rejection

1. Publish a test manifest version with an incorrect positive `size`.
2. Tap **Prepare models**.

Expected: the app reports a size mismatch and does not activate the candidate.

## 9. Interrupted download

1. Start model preparation on a fresh installation.
2. Disable the network during a download.
3. Restore the network and tap **Prepare models** again.

Expected: the first attempt fails without activating the partial file. The retry
deletes the old temporary download and starts that model again from byte zero.

Note: resumable/range downloads are not implemented.

## 10. Inference workflow

1. Ensure both models are ready.
2. Record: "The project presentation went very well and I am happy."
3. Stop recording after several seconds.

Expected:

- Vosk displays a transcript.
- MobileBERT displays positive and negative sentiment scores.
- The UI displays total pipeline latency.

For a negative example, record: "The release failed and the result was very
disappointing."

Neutral reminders may still receive a strong positive or negative score because
this model has no neutral, reminder, or task label.

## 11. Offline inference

1. Prepare both models.
2. Enable airplane mode.
3. Record and analyze another memo.

Expected: transcription and sentiment analysis run locally without network
access.

## 12. Process restart

1. Prepare both models.
2. Force-stop the app.
3. Relaunch it.

Expected: active paths are restored from private registry metadata and recording
works without another download.

## 13. Missing local model

1. Using Android Studio Device Explorer on a debug device, delete one active
   model file or directory.
2. Relaunch the app.

Expected: the missing path is excluded from the installed-model registry,
recording remains disabled, and **Prepare models** downloads it again.

Note: an existing file is not re-hashed on every launch. Silent corruption that
does not remove the file is detected when downloading a new version, not during
startup.

## 14. Last-known-good preservation

1. Keep a working version 1 installed.
2. Publish version 2 with an incorrect size or checksum.
3. Tap **Prepare models**.
4. Restart the app if desired.

Expected: version 2 is rejected before activation and version 1 remains the
registered active model.

Note: this demonstrates preservation after download/integrity failure. Automatic
rollback after a successfully verified model later fails to load is a documented
stretch goal and is not implemented.

## 15. APK packaging

Inspect the APK:

```powershell
jar.exe tf app\build\outputs\apk\debug\app-debug.apk |
  Select-String -Pattern '\.tflite$|vosk-model|\.zip$|model-manifest'
```

Expected: no model or fallback model-manifest artifact is present.
