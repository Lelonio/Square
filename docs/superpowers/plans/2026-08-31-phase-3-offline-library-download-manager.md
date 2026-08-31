# Phase 3 Offline Library & Download Manager Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans or superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Add a persistent, secure offline-download subsystem that can download only provider media references the existing backend legitimately exposes, reconcile state with app-private storage, schedule work through WorkManager, and let supported downloaded YouTube tracks play locally without creating a second playback-state authority.

**Architecture:** `DownloadManager` owns orchestration and exposes immutable `StateFlow<List<DownloadRecord>>`. `DownloadStateStore` owns transactional JSON persistence; `LocalMediaStorage` owns app-private files and atomic finalization; `DownloadWorker` performs bounded network work under WorkManager constraints. A separate `DownloadSourceResolver` capability keeps provider-specific download rules outside `MusicBackend`; Spotify remains unsupported because the existing librespot path does not expose an offline-safe downloadable media reference. YouTube uses its existing NewPipe stream-resolution path.

**Tech Stack:** Kotlin, coroutines, kotlinx.serialization, OkHttp, Android WorkManager, Media3 `ResolvingDataSource`, app-private files, MediaStore-independent offline storage.

**Spec:** User-provided Phase 3 Offline Library & Download Manager requirements in this conversation.

## Global Constraints

- Preserve online Spotify/librespot playback and YouTube playback.
- Preserve `MusicBackend`; do not add download responsibilities to that interface.
- Do not create a second playback-state authority or offline queue.
- Do not persist raw stream URLs, cookies, access tokens, or authorization headers in download metadata.
- Never expose partial files as completed media.
- Never derive local paths from remote filenames or URLs.
- Use WorkManager network constraints; Wi-Fi-only means `NetworkType.UNMETERED`.
- WorkManager long-running downloads use the existing `dataSync` foreground-service permission/type.
- Spotify downloads remain explicitly unsupported unless the backend later exposes a legitimate offline-safe source.
- No Phase 4 UI or unrelated playback refactor.

---

### Task 1: Offline domain, persistence, settings, and storage boundary

**Files:**
- Create: `app/src/main/java/dev/lelonio/square/offline/DownloadModels.kt`
- Create: `app/src/main/java/dev/lelonio/square/offline/DownloadStateStore.kt`
- Create: `app/src/main/java/dev/lelonio/square/offline/LocalMediaStorage.kt`
- Create: `app/src/main/java/dev/lelonio/square/offline/DownloadPreferences.kt`
- Create: `app/src/test/java/dev/lelonio/square/offline/LocalMediaStorageTest.kt`
- Create: `app/src/test/java/dev/lelonio/square/offline/DownloadStateStoreTest.kt`

**Interfaces:**
- `DownloadRecord` is the persisted immutable record.
- `DownloadStateStore.records: StateFlow<List<DownloadRecord>>` and suspend `upsert`, `remove`, `replaceAll`, `load`.
- `LocalMediaStorage.finalPath(trackKey, extension)`, `tempPath(jobId)`, `finalize(temp, final)`, `delete`, `exists`, `validateBasic`, `usageBytes`, `freeBytes`.
- `DownloadPreferences.wifiOnly`, `quality`, and setters; values survive process restart.

- [ ] **Step 1: Add immutable download models and status/error enums.**
- [ ] **Step 2: Add transactional JSON state persistence using temp-file write + atomic move/replace.**
- [ ] **Step 3: Add deterministic SHA-256 storage naming under `filesDir/offline-media`.**
- [ ] **Step 4: Add root-containment checks, partial-file isolation, and storage accounting.**
- [ ] **Step 5: Add persistent Wi-Fi-only and quality settings.**
- [ ] **Step 6: Test traversal, root escape, atomic finalization, reconciliation metadata, and duplicate identity.**

### Task 2: Provider download capability and safe downloader

**Files:**
- Create: `app/src/main/java/dev/lelonio/square/offline/DownloadSourceResolver.kt`
- Create: `app/src/main/java/dev/lelonio/square/offline/YouTubeDownloadSourceResolver.kt`
- Create: `app/src/main/java/dev/lelonio/square/offline/Downloader.kt`
- Modify: `app/src/main/java/dev/lelonio/square/backend/youtube/YouTubePlayerFactory.kt`
- Create: `app/src/test/java/dev/lelonio/square/offline/DownloadRetryPolicyTest.kt`

**Interfaces:**
- `DownloadSourceResolver.resolve(track, quality): ResolvedDownloadSource`.
- `ResolvedDownloadSource` contains a freshly resolved URL, expected content type, extension, and optional content length; no credentials are persisted.
- `Downloader.download(...)` streams into a supplied temp file and reports progress through a callback.

- [ ] **Step 1: Extract the existing YouTube NewPipe stream resolution into a reusable resolver while preserving the player behavior.**
- [ ] **Step 2: Implement a YouTube-only `DownloadSourceResolver` that selects an available audio stream for the requested quality.**
- [ ] **Step 3: Implement OkHttp streaming with strict 2xx handling, HTTPS-only URLs, bounded read timeouts, cancellation propagation, and response validation.**
- [ ] **Step 4: Accept only audio MIME types from the response and derive extension from an allowlist; never trust remote filenames.**
- [ ] **Step 5: Classify transient vs permanent failures and cap automatic retries.**
- [ ] **Step 6: Test retry classification and malformed response handling without network-dependent tests.**

### Task 3: Persistent queue manager, WorkManager execution, reconciliation, and batches

**Files:**
- Create: `app/src/main/java/dev/lelonio/square/offline/DownloadManager.kt`
- Create: `app/src/main/java/dev/lelonio/square/offline/DownloadWorker.kt`
- Create: `app/src/main/java/dev/lelonio/square/offline/OfflineLibrary.kt`
- Modify: `app/src/main/java/dev/lelonio/square/SquareApplication.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/test/java/dev/lelonio/square/offline/DownloadManagerTest.kt`

**Interfaces:**
- `DownloadManager.enqueue`, `enqueueAll`, `enqueueAlbum`, `enqueuePlaylist`, `pause`, `resume`, `cancel`, `retry`, `delete`, `deletePlaylist`, `reconcile`, `storageUsage`.
- `OfflineLibrary.available(trackUri): OfflineMedia?`, `observe()`, `delete`, `storageUsage`.
- `DownloadWorker` receives only a stable job id and reads all provider data from persisted metadata; it never accepts a raw download URL from UI.

- [ ] **Step 1: Add WorkManager dependency and manifest declaration for `SystemForegroundService` with `dataSync`.**
- [ ] **Step 2: Implement deterministic unique work per logical track/version/quality and a bounded concurrency semaphore inside the application-scoped manager.**
- [ ] **Step 3: Implement worker state transitions, progress persistence, atomic finalize, and controlled retry/failure classification.**
- [ ] **Step 4: Apply `UNMETERED` when Wi-Fi-only is enabled and keep jobs queued when the constraint is unmet.**
- [ ] **Step 5: Implement startup reconciliation: completed records require a valid final file; partial/orphan files never become playable.**
- [ ] **Step 6: Implement single/album/playlist/all enqueue operations with deduplication and partial-batch failure tolerance.**
- [ ] **Step 7: Implement safe deletion and storage accounting.**
- [ ] **Step 8: Add manager tests for deduplication, queue state transitions, retry bounds, cancellation, batch isolation, and concurrent enqueue.**

### Task 4: Offline playback resolution without changing playback authority

**Files:**
- Modify: `app/src/main/java/dev/lelonio/square/backend/youtube/YouTubePlayerFactory.kt`
- Modify: `app/src/main/java/dev/lelonio/square/playback/PlaybackService.kt`
- Create: `app/src/test/java/dev/lelonio/square/offline/OfflinePlaybackResolutionTest.kt`

**Interfaces:**
- Offline resolution is a source-resolution concern only; logical queue/media identity remains the existing `CatalogTrack.uri`.
- `YouTubeStreamResolver` first checks `OfflineLibrary` for a valid local file and otherwise resolves the online stream exactly as before.

- [ ] **Step 1: Add a local-file lookup before YouTube URL resolution.**
- [ ] **Step 2: Preserve the original logical URI in Media3 metadata while only changing the resolved `DataSpec` URI.**
- [ ] **Step 3: If the local file is missing or invalid, fall through to the existing online resolver.**
- [ ] **Step 4: Avoid changing Spotify/librespot playback because no offline-safe source is exposed.**
- [ ] **Step 5: Test local-preferred, missing-local-fallback, and queue-identity behavior with pure resolver tests.**

### Task 5: Documentation, security audit, and final verification

**Files:**
- Create: `docs/PHASE-3-OFFLINE.md`
- Create: `docs/adr/ADR-003-offline-download-boundaries.md`

- [ ] **Step 1: Document architecture, state machine, persistence schema, storage layout, scheduling, security, retry policy, offline resolution, Wi-Fi-only, quality, lifecycle, deletion, and provider limitations.**
- [ ] **Step 2: Record the decision to keep download capability separate from `MusicBackend` and to keep Spotify unsupported until a legitimate offline-safe reference exists.**
- [ ] **Step 3: Run `./gradlew test`, `./gradlew lint`, and `./gradlew assembleDebug` in an environment with repository dependencies available.**
- [ ] **Step 4: Run focused instrumentation/device tests if an Android device/emulator is available; otherwise mark them unverified.**
- [ ] **Step 5: Re-review for path traversal, partial-file exposure, duplicate jobs, retry storms, lifecycle leaks, and playback-state duplication.**
- [ ] **Step 6: Verify no Rust/native files changed and therefore no Rust checks are required.**

## Coverage Check

- Domain/persistence/storage: Task 1.
- Provider/download safety: Task 2.
- Queue, concurrency, batch, background execution, recovery: Task 3.
- Offline playback integration and queue identity: Task 4.
- Security, lifecycle, provider limitations, tests, documentation: Task 5.
- Spotify/librespot is intentionally preserved and not made downloadable because the inspected backend exposes no offline-safe media URL/reference.
