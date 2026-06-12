# TANUH_AI

An Android voice-memo demo for the edge-AI candidate assignment. The app downloads
two versioned models at runtime, caches them in app-private storage, and runs the
complete pipeline on the device:

1. Vosk Small English transcribes microphone audio.
2. A MediaPipe MobileBERT sentiment classifier analyzes the transcript.

No model bytes or fallback model catalog are packaged in the APK.

Repository: [`UpadhyayJitesh/TANUH_AI`](https://github.com/UpadhyayJitesh/TANUH_AI)

The Git repository root is `TANUH_AI`. The Android Studio project and application
source are contained in the `TANUHDemo/` directory.

## Build and run

Requirements:

- Android Studio with JDK 17
- Android SDK 36
- Android device or emulator on API 24+
- Wi-Fi for the first model download (metered download is opt-in)

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
Set-Location .\TANUHDemo
.\gradlew.bat :app:assembleDebug
```

Install the APK, tap **Prepare models**, grant microphone permission, and record a
short English memo. Later launches reuse the cached models.

## Model delivery

The production manifest URL is:

`https://raw.githubusercontent.com/UpadhyayJitesh/edge-ai-models/main/model-manifest.json`

The GitHub-hosted manifest is authoritative. A fresh installation cannot prepare
models if that web resource is unavailable. Once models have been downloaded and
verified, the app can run from its private cache without a network connection.

Before running the fresh-install demo, publish the two artifacts in
[`UpadhyayJitesh/edge-ai-models`](https://github.com/UpadhyayJitesh/edge-ai-models),
under `releases/download/voice-memo-v1/`, then copy
[`TANUHDemo/manifests/model-manifest.json`](TANUHDemo/manifests/model-manifest.json) to that
repository's root. The checked-in catalog contains the exact expected sizes and
SHA-256 hashes. Missing or invalid integrity metadata is rejected.

## Why these models

- **Vosk Small English 0.15:** practical CPU-only streaming ASR on Android and
  substantially smaller than many Whisper distributions.
- **MobileBERT sentiment classifier:** a context-aware TFLite model loaded with
  MediaPipe Tasks. It analyzes the Vosk transcript and returns `positive` and
  `negative` sentiment scores, so the two models form one end-to-end workflow
  rather than independent demos.

The current MobileBERT model performs binary sentiment analysis; it does not
detect memo intents such as reminders, actions, ideas, or notes. A production
voice assistant could replace it with a fine-tuned intent classifier while
keeping the same OTA model-delivery and on-device inference architecture.

## Architecture

The candidate timebox favors a small, finished slice, so the app remains one
Gradle module. Packages already follow extraction boundaries:

- `models/ModelManager`: manifest resolution, download, checksum, staging,
  activation, cache registry, and previous-version metadata.
- `models/ConnectivityPolicy`: validated-network and metered-network policy.
- `inference/MemoSentimentClassifier`: MediaPipe TFLite sentiment-classification adapter.
- `MainActivity`: permission, Vosk streaming session, and workflow UI.

See [architecture.md](TANUHDemo/doc/architecture.md),
[code-flow.md](TANUHDemo/doc/code-flow.md), the detailed
[method-walkthrough.md](TANUHDemo/doc/method-walkthrough.md), and
[sdk-design.md](TANUHDemo/doc/sdk-design.md).
The executable acceptance checklist is in
[test-plan.md](TANUHDemo/doc/test-plan.md), and model hosting instructions are in
[model-publishing.md](TANUHDemo/doc/model-publishing.md).

## Requirement coverage

- Fresh install opens without models.
- Models are downloaded OTA and not bundled.
- Model metadata is fetched from the `edge-ai-models` web catalog.
- Downloads default to validated unmetered connectivity.
- Interrupted downloads remain temporary and are never activated.
- Models are cached in app-private storage and reused.
- Remote manifest versions permit updates without an app release.
- SHA-256 verification is supported when hashes are populated.
- Activation uses a staged directory rename and records the prior version/path.
- Both inference stages run on device.
- Pipeline latency is surfaced in the UI.
- Model delivery and inference transitions are available through structured
  Logcat tags.

## Logcat observability

Filter Logcat with:

```text
tag:ModelManager | tag:ConnectivityPolicy | tag:VoiceMemoActivity | tag:MemoSentimentClassifier
```

The logs cover network policy, manifest download/validation, cache decisions,
download duration, byte-size and SHA-256 verification, staging and activation,
ASR lifecycle, sentiment-model loading, classification latency, errors, and cleanup.
Spoken transcript content and complete checksums are intentionally not logged.

## No speech detected

Vosk emits completed phrases through `onResult` and only the remaining tail
through `onFinalResult`. The app accumulates both callback types before running
MobileBERT.

If a recording is still empty:

1. Confirm Android microphone permission is enabled for the TANUHDemo app.
2. Test on a physical device, or enable microphone input for the emulator.
3. Close other apps using the microphone.
4. Speak for several seconds after the status changes to
   `Recording and transcribing offline`.
5. Filter Logcat with `tag:VoiceMemoActivity`. Partial, intermediate, and final
   callback logs show whether Vosk received recognizable audio without logging
   the spoken text.

## Deliberately left out

- WorkManager foreground/resumable downloads. The demo uses a foreground
  activity-owned download to keep behavior easy to inspect.
- Automatic rollback after runtime load failure. Previous-version metadata is
  retained, but a rollback API and retry policy are not yet exposed.
- Cache eviction, delta updates, staged rollout, and hardware delegate selection.
- A task-specific fine-tuned memo classifier.
- Multi-module Gradle extraction. The proposed module shape is documented rather
  than represented by empty modules.

## What I would do next

1. Move downloads to WorkManager with resumable HTTP range requests.
2. Add load health checks and automatic rollback.
3. Fine-tune/evaluate the classifier on voice-memo intent labels.
4. Extract `model-manager` into the SDK described in the design document.
