# Addon music sources

Square's addon support follows BitChord's broad model: addons are HTTP services, not executable plugins. The foundation currently provides source configuration, validation, enable/disable and removal UI, plus an HTTP client for this contract:

- `GET /manifest.json` — descriptive manifest (`id`, `name`, `version`, `resources`).
- `GET /search?q=<query>&limit=<n>` — JSON object containing a `tracks` array. Track rows use `id`, `title`, `artist`, optional `album`, `duration` (seconds), `artworkURL`, and optional `streamURL`.
- `GET /stream/<track-id>` — JSON object containing `url`, and optionally `headers` and `mimeType`. A track's `streamURL` can avoid the stream request.

`AddonSourcesSection` is a Compose settings component. It validates a manifest before saving an endpoint, supports enabling/disabling and removing configured endpoints, and persists the configuration locally. The app Settings screen still needs to host this component. HTTPS is required except for loopback/private-LAN hosts. Do not put credentials in addon URLs.

Playback integration is a separate next step: add-on search results must be matched conservatively by normalized title, artist and runtime, resolved to a fresh URL at play time, then passed through the existing Media3 path; if no addon can serve a suitable match, Square should use its existing YouTube Music stream resolver. Spotify's native librespot path is a separate engine and is not changed by addon playback.
