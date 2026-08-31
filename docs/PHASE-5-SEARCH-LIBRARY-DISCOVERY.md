# Phase 5 — Search, Library and Discovery

## Baseline audit

Phase 4 already routes catalogue access through `MusicBackend`. `SpotifyBackend` prefers the Spotify gateway and falls back to the Web API; `YouTubeBackend` performs independent InnerTube searches. `SearchScreen` consumes `MainViewModel.SearchState` and does not call providers directly.

Existing Spotify gateway search is a single first-page operation. Existing YouTube search runs the independent song/album/artist/playlist requests concurrently. Therefore Phase 5 does not pretend that both providers expose the same pagination contract.

## Search boundary

`SearchRepository` normalizes query whitespace/case, keys its bounded cache by backend/query/page, and delegates provider-specific work to `MusicBackend`. The current backend contract exposes only first-page search, so requesting a later page is rejected rather than fabricated. This leaves a clean seam for a backend-specific cursor/page contract later.

`MainViewModel.onSearchQuery` cancels the previous job before waiting through the existing debounce. Cancellation is rethrown instead of being rendered as an error. Successful work checks coroutine activity before publishing, preventing obsolete requests from replacing newer state.

## Search history

`SearchHistoryStore` remains a local, bounded store of tracks that were actually played from search. It de-duplicates by URI and persists across restarts. No credentials or provider payloads are stored.

## Library

`MusicBackend.playlists()` and `tracksOf()` remain the provider-independent library boundary. Spotify Liked Songs and YouTube account playlists stay behind their respective backends. `LocalLibrary` and Phase 3 `DownloadManager` remain separate local/offline sources; downloaded state is not copied into backend models.

## Offline

Spotify catalogue/context reads already have gateway/cache fallbacks, while YouTube search/playback remains independently available without Spotify credentials. `ContextCacheStore` is bounded and expires entries rather than presenting them as permanently authoritative.

## Discovery contract

`RecommendationRepository`, `RecommendationSource`, and `RecommendationSection` are backend-independent. `BackendDiscoverySource` adapts existing `MusicBackend.homeRows()` into generic sections. `CompositeRecommendationRepository` isolates source failures so one source can fail without taking down the complete discovery surface.

No ranking, embeddings, listening-profile model, personalization algorithm, or experiment system is implemented in Phase 5. Phase 8 can add a new `RecommendationSource` or repository implementation without changing the Home section rendering contract.

## Performance notes

The codebase already confines native catalogue calls to `Dispatchers.IO`, disables artwork crossfades in scrolling lists, sizes Coil requests for rendered artwork, and runs YouTube's four search filters concurrently. No Android benchmark/device was available in this environment, so no latency or frame-time number is claimed as measured.

## Verification limitations

GitHub repository operations were available, but a local Android/Gradle/emulator environment was not. Consequently `./gradlew test`, `./gradlew lint`, `./gradlew assembleDebug`, instrumentation tests, offline device tests, backend-switch tests, and actual network/cache latency measurements could not be executed here.

The existing Phase 4 branch remains the baseline; no Media3, PlaybackService, queue, or DownloadManager authority was changed by the Phase 5 boundary additions.

## Phase 8 contract

A Phase 8 source only needs to implement `RecommendationSource.sections()` and return generic `RecommendationSection` values. Ranking/scoring may be internal to that source. Compose should consume sections, never invoke ranking logic.
