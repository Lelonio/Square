# Phase 5 — Search, Library & Discovery

## Architecture

Phase 5 keeps the existing direction:

`Compose UI → presentation → data/domain → MusicBackend → provider/local storage`

`MusicBackend` remains the provider boundary. Spotify and YouTube implementations continue to expose their own capabilities without leaking provider API models into Compose.

## Search

`SearchRepository` owns query normalization, provider-aware cache keys, bounded in-memory caching, and explicit page identity. The current backend contract exposes a complete first-page `SearchResults` response, so the repository deliberately does **not** invent cursor/page support that the providers do not currently expose. Unsupported pages fail explicitly.

Identical concurrent `(backend, normalized query, page)` requests are coalesced through an in-flight deferred. This prevents duplicate network work caused by navigation/recomposition or multiple presentation consumers. The deferred is completed from the structured caller that owns the backend request; failures are propagated instead of being swallowed.

Cache identity includes backend and normalized query, preventing Spotify results from being reused for YouTube. Cache size is bounded and can be invalidated per backend or globally.

## Search history and recent content

`SearchHistoryStore` remains local and bounded. Writes are serialized with a `Mutex` so concurrent playback/search events cannot overwrite each other with stale snapshots. Stored entries remain backend-neutral `CatalogTrack` values and contain no credentials or provider session material.

## Library and offline content

Existing library and offline stores remain authoritative for their respective data. Download availability is not copied into search state; consumers observe the Phase 3 offline subsystem. Missing local files are therefore reconciled by the download/local-library layer rather than by Compose flags.

## Discovery / recommendation contract

Discovery is represented by:

- `RecommendationRepository` — aggregates discovery sections.
- `RecommendationSource` — an independently replaceable source/strategy.
- `RecommendationSection` — a UI-consumable shelf with source, cache, and error metadata.
- `DiscoveryItem` — backend-independent track/context presentation data.

`CompositeRecommendationRepository` isolates source failures so one unavailable source does not blank Home. `BackendDiscoverySource` adapts the existing backend-provided Home shelves without adding ranking or personalization policy.

This is intentionally Phase-8 compatible: a future personalized, embedding, ranking, or experiment source can implement `RecommendationSource` and be composed by the repository without changing Home's section renderer. No Phase-8 recommendation engine is included in Phase 5.

## Offline discovery

Existing cached/local data remains usable when live providers are unavailable. Cached discovery is explicitly marked with `isCached`; unavailable sources return an error-bearing section instead of fabricated recommendations.

Live provider data is never represented as current merely because a local cache exists.

## Backend switching

Provider identity is part of search cache identity. Presentation code should create a new request generation when the active backend changes and cancel the obsolete presentation request. Because the repository never merges provider caches, a previous backend's result cannot be reused as the new backend's cache entry.

Playback ownership remains in `PlaybackService`; Phase 5 does not create a second queue or playback state machine.

## Performance and concurrency

The repository uses a bounded cache and in-flight request coalescing. The regression suite covers normalized-query cache hits, provider cache separation, and concurrent identical request suppression. Device-level latency, scrolling, and backend-switch measurements require a runnable Android environment and are not inferred from unit tests.

## Known backend limitations

The common search contract currently exposes the complete first-page `SearchResults` shape. Provider-native cursor pagination, suggestions, videos, and other categories should only be surfaced when the selected backend can legitimately provide them. The architecture keeps those capabilities behind the backend/repository boundary rather than fabricating parity.
