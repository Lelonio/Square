# Phase 3 Offline Library & Download Manager Implementation Plan

**Goal:** Add a persistent, secure offline-download subsystem that accepts only provider media references the existing backend legitimately exposes, reconciles state with app-private storage, schedules work through WorkManager, and lets supported authorized local assets play without creating a second playback-state authority.

**Architecture:** `DownloadManager` owns orchestration and exposes immutable `StateFlow<List<DownloadRecord>>`. `DownloadStateStore` owns transactional JSON persistence; `LocalMediaStorage` owns app-private files and atomic finalization; `DownloadWorker` performs bounded network work under WorkManager constraints. A separate `DownloadSourceResolver` capability keeps provider-specific download rules outside `MusicBackend`. The inspected Spotify and YouTube contracts do not expose an authorized offline source, so both provider download resolvers fail closed; the generic subsystem remains ready for a future authorized source.

## Global Constraints

- Preserve online Spotify/librespot playback and YouTube playback.
- Preserve `MusicBackend`; do not add download responsibilities to that interface.
- Do not create a second playback-state authority or offline queue.
- Do not persist raw stream URLs, cookies, access tokens, or authorization headers in download metadata.
- Never expose partial files as completed media.
- Never derive local paths from remote filenames or URLs.
- Use WorkManager network constraints; Wi-Fi-only means `NetworkType.UNMETERED`.
- WorkManager long-running downloads use the existing `dataSync` foreground-service permission/type.
- Spotify and YouTube provider downloads remain unsupported until an authorized offline reference is exposed.
- No Phase 4 UI or unrelated playback refactor.

---

### Task 1: Offline domain, persistence, settings, and storage boundary

- Add immutable download models/status/error/quality values.
- Add transactional JSON state persistence using temp-file write + atomic move/replace.
- Add deterministic SHA-256 storage naming under `filesDir/offline-media`.
- Add root-containment checks, partial-file isolation, media validation, orphan reconciliation, and storage accounting.
- Add persistent Wi-Fi-only and quality settings.
- Add JVM tests for retry/identity policy; Android/device storage tests remain environment-dependent.

### Task 2: Provider capability gate and safe downloader

- Keep the existing YouTube NewPipe stream resolution exclusively for online playback.
- Do **not** convert short-lived YouTube stream URLs into permanent downloads because the current provider terms do not authorize that operation and the repository exposes no authorized offline reference.
- Keep Spotify/librespot downloads unsupported; never capture native playback.
- Implement the generic downloader boundary for a future authorized provider source with strict HTTPS, response/MIME validation, cancellation, temporary files, atomic finalization, and bounded retry classification.

### Task 3: Persistent queue manager, WorkManager execution, reconciliation, and batches

- Add WorkManager dependency and `SystemForegroundService`/`dataSync` declaration.
- Implement deterministic unique work per logical track/version/quality and a bounded concurrency semaphore.
- Implement worker state transitions, progress persistence, atomic finalization, and controlled retry/failure classification.
- Apply `UNMETERED` when Wi-Fi-only is enabled and keep jobs queued when the constraint is unmet.
- Reconcile completed records against real files; requeue interrupted jobs; remove partial/orphan files safely.
- Implement single/album/playlist/all enqueue APIs, deduplication, aggregate progress, partial-batch isolation, deletion, and storage accounting.

### Task 4: Offline playback resolution without changing playback authority

- Add a local-file lookup in the existing Media3 `ResolvingDataSource` path.
- Preserve the original logical URI/queue identity while resolving a supported local file.
- Fall through to the existing online YouTube resolver if no valid local file exists.
- Do not change Spotify/librespot playback ownership or native code.

### Task 5: Documentation, security audit, and verification

- Document architecture, state machine, persistence schema, storage layout, scheduling, security, retry policy, offline resolution, Wi-Fi-only, quality, lifecycle, deletion, and provider limitations.
- Record the decision to keep download capability separate from `MusicBackend` and to fail closed when provider authorization is absent.
- Run `./gradlew test`, `./gradlew lint`, and `./gradlew assembleDebug` where a local checkout/toolchain is available.
- Run focused instrumentation/device tests where an Android environment and authorized provider source are available; otherwise mark them unverified.
- Re-review path traversal, partial-file exposure, duplicate jobs, retry storms, lifecycle leaks, and playback-state duplication.
- Verify no Rust/native files changed; Rust checks are not required for this phase.

## Coverage Check

- Domain/persistence/storage: Task 1.
- Provider/download safety: Task 2.
- Queue, concurrency, batch, background execution, recovery: Task 3.
- Offline playback integration and queue identity: Task 4.
- Security, lifecycle, provider limitations, tests, documentation: Task 5.
- Provider-backed downloads are intentionally blocked until an authorized offline source is available.
