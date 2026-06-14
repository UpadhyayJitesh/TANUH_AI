# Stretch Goals Status

This document maps assignment section **3.4 Stretch goals** to the current
TANUHDemo and standalone
[`TANUH_SDK`](https://github.com/UpadhyayJitesh/TANUH_SDK) implementations.

Status meanings:

- **Achieved:** implemented and demonstrable.
- **Partial:** supporting behavior exists, but the complete production feature
  is not implemented.
- **Not implemented:** intentionally left as future work.

## Summary

| Stretch goal | Status | Where |
| --- | --- | --- |
| Size and SHA-256 verification | Achieved | Demo and SDK |
| Manifest signature verification | Not implemented | Future SDK trust enhancement |
| Atomic model activation | Achieved | Demo and SDK |
| Rollback after runtime-load failure | Achieved in SDK | Not integrated into demo |
| Delta/patch updates | Not implemented | Future work |
| Resumable downloads | Not implemented | Future work |
| Cache-size cap | Not implemented | Future work |
| LRU eviction | Not implemented | Future work |
| Model leases | Partial | SDK tracks active leases |
| GPU/NNAPI/NPU selection | Not implemented | Future inference enhancement |
| Graceful CPU fallback | Partial | Runtime defaults only |
| Backend reporting | Not implemented | Future inference enhancement |
| Structured lifecycle logging | Achieved | Demo and SDK |
| Download progress and metrics | Partial | Local state/logging, no exporter |
| Inference latency | Achieved | Demo |
| Injectable SDK logging | Achieved | SDK |
| Telemetry backend integration | Not implemented | Future adapter |

## Integrity and Safety

### Checksum verification: achieved

The manifest declares an exact artifact `size` and `sha256`. A downloaded model
is rejected when either value differs.

The model is not marked active until verification succeeds.

### Manifest signature: not implemented

SHA-256 proves that the downloaded bytes match the manifest, but it does not
authenticate the manifest publisher.

The manifest is currently fetched over HTTPS. A production trust enhancement
would sign the manifest and verify it using pinned, rotatable public keys.

### Atomic activation: achieved

The lifecycle is:

```text
temporary download
    -> size and SHA-256 verification
    -> isolated staging or safe ZIP extraction
    -> move into versioned model storage
    -> persist active metadata last
```

An interrupted or invalid download cannot overwrite the active model metadata.

### Rollback: achieved in the SDK

`TANUH_SDK 0.1.0` retains previous-version metadata when activating a new
candidate. The client tries to load the candidate and calls:

```kotlin
modelSdk.reportLoadResult(
    lease,
    ModelLoadResult.Success,
)
```

or:

```kotlin
modelSdk.reportLoadResult(
    lease,
    ModelLoadResult.Failure(error),
)
```

On failure, the SDK revalidates and restores the previous model when available.
It returns `LoadReportResult.RolledBack` or
`LoadReportResult.NoRollbackAvailable`.

TANUHDemo still uses its original in-app model manager, so runtime-triggered
rollback is not wired into the demo UI.

## Download Efficiency

### Delta/patch updates: not implemented

A changed model version currently downloads the complete artifact. Binary
patching would require patch metadata, patch verification, reconstruction, and
final artifact verification.

### Resumable downloads: not implemented

Downloads use temporary files, so partial content is never activated. However,
an interrupted download restarts instead of resuming from its previous byte
offset.

A production implementation would use HTTP range requests, persist download
metadata, verify server range support, and always validate the complete
reconstructed artifact.

## Storage Management

### Cache-size cap and LRU eviction: not implemented

The app-private model cache does not currently enforce a byte quota or
automatically evict old versions.

### Model leases: partial foundation

The SDK returns a `ModelLease` and maintains an in-process reference count. This
defines which model version is currently in use and provides the protection
needed by a future cleanup policy.

Automatic cleanup does not yet consume those lease counts. A production policy
would preserve:

- Active models
- Previous last-known-good models
- Models with open leases

It could then evict the least recently used unleased versions until the cache
returns below its configured quota.

## Hardware Acceleration

### GPU/NNAPI/NPU delegate selection: not implemented

The model-delivery SDK is intentionally inference-runtime independent. Hardware
delegate configuration belongs in the client inference adapter.

The current demo does not explicitly select GPU, NNAPI, or an NPU delegate.

### Graceful CPU fallback: partial

The demo relies on the normal/default behavior of Vosk and MediaPipe rather than
implementing an explicit sequence such as:

```text
preferred accelerator
    -> initialization or compatibility check
    -> CPU fallback
    -> report selected backend
```

The UI and logs do not currently surface the selected inference backend.

## Observability

### Structured lifecycle logging: achieved

The demo logs model and inference transitions with dedicated Logcat tags:

```text
ModelManager
ConnectivityPolicy
VoiceMemoActivity
MemoSentimentClassifier
```

The SDK exposes `EdgeModelLogger`, allowing a client to connect model-lifecycle
events to its own logging or telemetry system.

### Download metrics: partial

The implementation exposes or logs:

- Checking, downloading, ready, and failed states
- Downloaded and total byte counts
- Model version and cache decisions
- Size and SHA-256 validation outcomes
- Activation and rollback events

It does not currently aggregate or upload these measurements to a platform
metrics backend.

### Inference latency: achieved in the demo

TANUHDemo records and surfaces inference/pipeline timing, including sentiment
classification latency.

### Backend reporting and telemetry export: not implemented

The implementation does not report whether inference used CPU, GPU, NNAPI, or
NPU. It also does not include a Firebase, OpenTelemetry, or other telemetry
export adapter.

## Interview Summary

The strongest completed stretch goal is **integrity and safety**:

- Exact size and SHA-256 verification
- Temporary download and isolated staging
- Activation only after validation
- Previous-version retention
- Runtime-load rollback in `TANUH_SDK`

The demo also provides useful local observability and inference latency.

The largest remaining production areas are:

1. Signed manifests
2. Resumable and delta downloads
3. Cache quota and LRU eviction
4. Explicit accelerator selection with CPU fallback
5. Backend reporting and production telemetry export

These omissions are deliberate scope decisions rather than hidden claims. The
current code provides boundaries for extending each area without coupling model
delivery to a specific inference framework.
