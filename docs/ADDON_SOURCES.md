# Addon music sources

Square's addon support uses the same broad model as BitChord: an addon is an HTTP service, not executable code. Addon endpoints are user-configured and use this contract:

- `GET /manifest.json` — optional descriptive manifest (`id`, `name`, `version`, `resources`).
- `GET /search?q=<query>&limit=<n>` — JSON object containing a `tracks` array. Track rows use `id`, `title`, `artist`, optional `album`, `duration` (seconds), `artworkURL`, and optional `streamURL`.
- `GET /stream/<track-id>` — JSON object containing `url`, and optionally `headers` and `mimeType`. A track's `streamURL` can avoid the stream request.

The settings component is `AddonSourcesSection`. It validates the manifest before saving an endpoint, supports enabling/disabling and removing configured endpoints, and persists the configuration locally. HTTPS is required except for loopback/private-LAN hosts. Do not include passwords or tokens in addon URLs.

The addon response parser tolerates unknown fields and stream metadata. Matching is conservative: normalized title and artist plus a duration tolerance, so alternate edits are not silently substituted. Addon lookup should run before the existing YouTube stream resolver and fall back to YouTube when there is no suitable addon result. Spotify's native librespot playback is a separate engine and is not changed by addon playback.
