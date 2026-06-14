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

The assignment's 6-8 hour timebox favors a small, complete implementation, so
the app remains one Gradle module. Packages follow boundaries that can later be
extracted into independent modules:

- `models/ModelManager`: manifest resolution, download, checksum, staging,
  activation, cache registry, and previous-version metadata.
- `models/ConnectivityPolicy`: validated-network and metered-network policy.
- `inference/MemoSentimentClassifier`: MediaPipe TFLite sentiment-classification adapter.
- `MainActivity`: permission, Vosk streaming session, and workflow UI.

See [architecture.md](TANUHDemo/doc/architecture.md),
[code-flow.md](TANUHDemo/doc/code-flow.md), the detailed
[method-walkthrough.md](TANUHDemo/doc/method-walkthrough.md),
[sdk-design.md](TANUHDemo/doc/sdk-design.md) for the initial SDK proposal, and
[sdk-design_V2.md](TANUHDemo/doc/sdk-design_V2.md) for the implemented
`TANUH_SDK 0.1.0` design and public API.

The standalone SDK implementation is available in
[`UpadhyayJitesh/TANUH_SDK`](https://github.com/UpadhyayJitesh/TANUH_SDK).
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
- An expandable model-info panel shows active Vosk and MobileBERT versions and
  their integrity-verification state.
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
- Automatic rollback is not wired into this demo application. The standalone
  `TANUH_SDK 0.1.0` implements runtime-load reporting and restoration of a valid
  previous version.
- Cache eviction, delta updates, staged rollout, and hardware delegate selection.
- A task-specific fine-tuned memo classifier.
- Direct consumption of `TANUH_SDK` by this demo. The demo retains its original
  in-app model manager so the submitted implementation remains independently
  inspectable.

## What I would do next

1. Move downloads to WorkManager with resumable HTTP range requests.
2. Migrate the demo from its in-app model manager to the standalone `TANUH_SDK`.
3. Fine-tune/evaluate the classifier on voice-memo intent labels.
4. Add signed manifests, staged rollout, and automatic cache retention to the SDK.
