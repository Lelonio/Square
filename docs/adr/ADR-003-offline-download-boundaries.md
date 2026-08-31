# ADR-003: Keep download capability separate from MusicBackend

**Status:** Accepted  
**Date:** 2026-08-31

## Context

`MusicBackend` owns catalogue/authentication/playback construction. The inspected Spotify implementation plays through librespot/native infrastructure and does not expose an authorized downloadable media reference. Spotify's developer terms prohibit stream ripping or functionality that makes streamed content permanent.

The YouTube implementation resolves short-lived audio URLs through NewPipe for online playback. Those URLs are not treated as an offline-download authorization. YouTube's current Terms restrict downloading Content except where expressly authorized by the Service or with permission, and the current backend exposes no authorized offline-media reference.

Adding download methods directly to `MusicBackend` would force every provider to pretend it has the same offline semantics and would couple the playback abstraction to storage/background execution.

## Decision

Create a separate `DownloadSourceResolver` capability. A resolver is implemented only for providers that can expose an authorized downloadable media source.

- Spotify remains unsupported and reports a controlled `UNAVAILABLE` download state.
- YouTube remains unsupported for provider-backed downloads and fails closed rather than turning online NewPipe stream URLs into permanent copies.
- The generic downloader/storage/queue infrastructure remains available for a future provider-authorized source.
- Download metadata stores only stable provider references and logical track metadata, never resolved URLs or credentials.
- Playback remains authoritative in `PlaybackService`; offline files are only a media-source resolution detail.

## Consequences

**Positive**

- Spotify and YouTube remain independently replaceable through `MusicBackend`.
- Download policy, persistence, storage, and background execution are isolated from playback ownership.
- Provider-specific limitations are explicit instead of hidden behind a false common API.
- Expiring stream URLs are never persisted.
- The implementation does not add a stream-ripping path to either provider.

**Negative**

- Provider-backed offline downloads are not available until a legitimate provider capability is exposed.
- The Phase 3 infrastructure cannot truthfully claim completed Spotify/YouTube downloads in the current repository.
- Pause/resume is restart-from-zero rather than byte-range resume until an authorized source proves safe range semantics.
