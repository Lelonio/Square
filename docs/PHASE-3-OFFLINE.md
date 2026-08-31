# Phase 3 — Offline Library & Download Manager

## Scope

Phase 3 adds a persistent offline-download subsystem without moving playback authority out of `PlaybackService`. The logical queue and `CatalogTrack.uri` remain the same whether Media3 resolves a track to an online URL or an app-private downloaded file.

```mermaid
flowchart TD
    UI[Download UI / future callers] --> DM[DownloadManager]
    DM --> Q[Persistent download queue]
    Q --> W[WorkManager DownloadWorker]
    W --> R[Provider DownloadSourceResolver]
    R --> CAP[Provider-authorized offline capability]
    CAP --> D[Downloader / OkHttp]
    D --> T[.partial temp file]
    T --> V[Media validation]
    V --> A[Atomic finalization]
    A --> S[filesDir/offline-media]
    S --> L[OfflineLibrary]
    L --> P[Media3 source resolution]
    P --> PB[Existing PlaybackService]
```

## Provider boundary and legal/contract limitation

`MusicBackend` is intentionally unchanged. Download capability is a separate capability because a catalogue/playback backend does not necessarily expose an authorized offline-media reference.

- **Spotify/librespot:** unsupported. The inspected Spotify path plays through the native librespot engine and does not expose an authorized downloadable media reference. Spotify's developer terms also prohibit stream ripping/capturing streamed content; this subsystem does not attempt to capture native playback.
- **YouTube Music:** unsupported by the current repository backend. The existing player resolves short-lived stream URLs for online playback, but those URLs are not treated as an authorization to create permanent copies. YouTube's current Terms prohibit downloading Content except where expressly authorized by the Service or with permission. The public backend in this repository exposes no authorized offline-media reference, so `YouTubeDownloadSourceResolver` fails closed with `UNSUPPORTED` rather than turning NewPipe stream URLs into downloads.

This means the download manager, persistence, storage, security, scheduling, reconciliation, and offline-resolution infrastructure is implemented and ready for a provider that supplies an explicitly authorized offline source. **Actual provider-backed downloads are not claimed as working in this phase because the inspected provider contracts do not support the required operation.**

## Persistent state

State is stored in `filesDir/downloads/state.json` and written using a temporary file followed by an atomic move when supported. Records contain the logical track identity, provider, requested quality, status, progress, byte counters, timestamps, error classification, and collection membership. Raw stream URLs, cookies, access tokens, and authorization headers are not persisted.

The download identity is `backend + logical track URI + quality`, preventing duplicate downloads while allowing different quality artifacts to coexist.

## State machine

```text
QUEUED -> PREPARING -> DOWNLOADING -> COMPLETED
   |          |              |
   |          |              +--> FAILED (permanent)
   |          +-----------------> QUEUED (bounded transient retry)
   +--> PAUSED -> QUEUED
   +--> CANCELLED -> QUEUED (manual resume/retry)

UNAVAILABLE is used for unsupported provider capability.
```

Partial downloads are never promoted to the final media root. Pause/cancel/restart safely discard partial data because the current downloader deliberately restarts from byte zero instead of pretending to support resumable range downloads.

## Scheduling and concurrency

WorkManager owns persistent execution. Each logical download uses unique work keyed by its stable job id, and constraints are applied at scheduling time. Wi-Fi-only uses `NetworkType.UNMETERED`; otherwise the work requires a connected network. Storage-not-low is also required.

Long-running workers use WorkManager foreground execution with the Android `dataSync` foreground-service type. The implementation does not assume an unbounded background coroutine or foreground-service lifetime.

A process-local semaphore limits active downloader execution to two by default. WorkManager can persist more queued jobs, but only the configured number enters the network/file-transfer section at once.

## Storage layout and security

```text
filesDir/
  downloads/
    state.json
    state.json.tmp
  offline-media/
    .partial/
      <job-id>.part
    <sha256(logical-key)>.<validated-extension>
```

Remote filenames are never used. Final names are generated from SHA-256 of the stable logical key. Extensions come from an allowlist derived from an authorized provider/media format or HTTP content type. Stored paths are relative to the managed root and are canonicalized before access or deletion.

A final file is only considered available after the download completes, the byte count is valid when supplied, `MediaMetadataRetriever` can parse a positive duration, and the file is atomically moved out of `.partial`.

Startup reconciliation revalidates completed records against the actual file. Missing/corrupt files are downgraded to `FAILED` and are not used for offline playback. Orphaned final files are removed during reconciliation.

The application already has `android:allowBackup="false"`, so local download state and media are not included in Android's normal application backup path.

## Offline playback resolution

The logical media id does not change. For a future provider with an authorized source, Media3's existing `ResolvingDataSource` can first check authoritative download records for a valid local file. If one exists, the `DataSpec` is resolved to that app-private file. If the file is missing or fails validation, the resolver falls through to the existing online resolver.

There is no separate offline queue and no second playback state. Shuffle, repeat, queue position, metadata, and MediaSession identity therefore continue to belong to the existing playback layer.

The current repository cannot populate this local source for Spotify or YouTube because neither exposes an authorized offline download reference through the inspected backend contracts.

## Retry and recovery

Retryable HTTP failures are 408, 425, 429, and 5xx. Socket timeouts and other IO failures are transient. Permanent HTTP failures, unsupported providers, malformed responses, invalid media, and unsupported content types do not retry forever. Automatic retries are capped at four attempts with WorkManager exponential backoff.

A temporary network loss does not delete completed offline media. A running worker is stopped by WorkManager when its network constraint becomes false and is eligible to run again when the constraint is restored.

## Wi-Fi-only and quality

`DownloadPreferences` persists both settings. Changing Wi-Fi-only triggers reconciliation/rescheduling. Quality is part of the download identity so changing quality does not overwrite a different completed artifact. Provider resolvers are responsible for mapping quality to an authorized provider capability; unsupported quality levels are not invented.

## Deletion and playlist membership

A download can belong to multiple collection ids. Removing a playlist removes only that playlist membership. The file is deleted only when no collection membership remains. Individual deletion cancels its work, removes the managed file if present, and removes the persistent record.

## Lifecycle and reboot

WorkManager persists unique work and reconstructs it after process death/device reboot subject to Android scheduling constraints. On application startup, `DownloadManager` reconciles persistent records with the filesystem before requeueing interrupted jobs. The current implementation restarts interrupted partial downloads from zero rather than resuming byte ranges.

## Testing strategy

JVM tests cover retry classification, bounded retry count, controlled extension mapping, and stable download identity. Android/device verification is required for the full matrix of WorkManager execution, foreground notification behavior, network interruption, reboot, storage pressure, and actual authorized-provider downloads.

The repository environment used for this change does not provide an Android emulator/device or a local Gradle checkout, so device-level and Gradle execution are reported as unverified rather than inferred.

## Known limitations

- No Spotify or YouTube provider-backed download is enabled because the inspected provider contracts do not expose an authorized offline source for this application.
- Provider-generated online stream URLs are not persisted or converted into offline copies.
- Pause/resume currently means safe restart from byte zero for a future authorized source; HTTP range resume is not claimed.
- Cross-provider download capability is intentionally not added to `MusicBackend`.
- Long-running `dataSync` foreground work is subject to Android platform execution limits.
- Full download/play/delete/re-download/reboot stress testing requires a real Android environment and an authorized provider source.
