# ADR-003: Keep download capability separate from MusicBackend

**Status:** Accepted  
**Date:** 2026-08-31

## Context

`MusicBackend` owns catalogue/authentication/playback construction. The inspected Spotify implementation plays through librespot/native infrastructure and does not expose an offline-safe downloadable media reference. The YouTube implementation resolves short-lived audio URLs through NewPipe at playback time.

Adding download methods directly to `MusicBackend` would force every provider to pretend it has the same offline semantics and would couple the playback abstraction to storage/background execution.

## Decision

Create a separate `DownloadSourceResolver` capability. A resolver is implemented only for providers that can expose a legitimate downloadable media source.

- YouTube uses the existing NewPipe stream-resolution path.
- Spotify remains unsupported and reports a controlled `UNAVAILABLE` download state.
- Download metadata stores only stable provider references and logical track metadata, never resolved URLs or credentials.
- Playback remains authoritative in `PlaybackService`; offline files are only a media-source resolution detail.

## Consequences

**Positive**

- Spotify and YouTube remain independently replaceable through `MusicBackend`.
- Download policy, persistence, storage, and background execution are isolated from playback ownership.
- Provider-specific limitations are explicit instead of hidden behind a false common API.
- Expiring stream URLs are resolved just in time.

**Negative**

- The offline feature is provider-capability dependent.
- Spotify does not gain offline downloads until a legitimate offline-safe source is exposed.
- Pause/resume is restart-from-zero rather than byte-range resume until a provider proves safe range semantics.
