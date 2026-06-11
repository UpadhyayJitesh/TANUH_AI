# Publish to edge-ai-models

The model files are currently stored in this repository path:

`https://github.com/UpadhyayJitesh/edge-ai-models`

`releases/download/voice-memo-v1/`

Keep these files at that path:

| Asset | Bytes | SHA-256 |
| --- | ---: | --- |
| `vosk-model-small-en-us-0.15.zip` | 41205931 | `30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498` |
| `mobilebert-text-classifier-v1.tflite` | 25707538 | `9b45012ab143d88d61e10ea501d6c8763f7202b86fa987711519d89bfa2a88b1` |

Place `manifests/model-manifest.json` at the root of the
`edge-ai-models` default branch. Verify this URL returns JSON:

`https://raw.githubusercontent.com/UpadhyayJitesh/edge-ai-models/main/model-manifest.json`

The Android app must use `raw.githubusercontent.com` links. GitHub `/blob/`
links return HTML pages and are not model download URLs.
