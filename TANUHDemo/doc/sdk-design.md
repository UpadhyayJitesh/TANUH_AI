# Reusable model-delivery SDK design

## Public API

```kotlin
interface EdgeModelSdk {
    fun observe(id: ModelId): Flow<ModelState>
    suspend fun sync(request: SyncRequest): SyncResult
    suspend fun acquire(id: ModelId): ModelLease
    suspend fun reportLoadResult(lease: ModelLease, result: LoadResult)
}

data class SyncRequest(
    val modelIds: Set<ModelId>,
    val networkPolicy: NetworkPolicy = NetworkPolicy.Unmetered,
)

interface ModelLease : Closeable {
    val id: ModelId
    val version: String
    val file: File
}
```

A consumer observes readiness, requests synchronization, acquires a lease, loads
the file with its chosen inference runtime, and reports whether loading worked.
A lease prevents eviction while a runtime is using the artifact.

## Responsibilities

The SDK owns:

- Manifest parsing and schema compatibility.
- Version comparison and update selection.
- Network constraints, retries, resumable transfer, and progress.
- SHA-256 and optional signature verification.
- Staging, atomic activation, last-known-good tracking, and rollback.
- Cache quotas, leases, and safe eviction.
- Download/load metrics with a pluggable event sink.

The consumer owns:

- Which model IDs a feature needs.
- User consent for large or metered transfers.
- Runtime-specific loading and inference.
- Product rollout rules supplied as SDK configuration.
- Domain behavior when a model is unavailable.

## Trust and rollout

Manifests should be signed and fetched over HTTPS. Each artifact must have a
digest, byte size, minimum SDK/runtime version, and rollout metadata. The SDK
verifies the manifest signature against pinned rotating public keys, then verifies
artifact bytes before activation.

Rollout eligibility is deterministic from an installation ID and rollout salt.
The SDK keeps the last-known-good version until the consumer reports successful
loading. Failed loads quarantine the candidate and restore the prior version.

## Modules and testing

```text
edge-model-api       stable public types, no Android runtime dependency
edge-model-manager   orchestration and state machine
edge-model-network   HTTP transport and resumable downloads
edge-model-storage   registry, files, leases, and eviction
edge-model-crypto    digest and signature verification
edge-model-work      WorkManager integration
```

Internal interfaces such as `ManifestSource`, `ArtifactTransport`,
`ModelStorage`, `IntegrityVerifier`, `Clock`, and `EventSink` allow deterministic
unit tests with fakes. Contract tests cover filesystem atomicity and HTTP resume.
Instrumentation tests cover WorkManager constraints and process recreation.

Public API types use semantic versioning. Implementation modules remain internal,
transitive dependencies are minimized, and binary compatibility is checked in CI.
