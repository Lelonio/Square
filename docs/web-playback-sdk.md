# Official Spotify Web Playback SDK recovery

Branch: `web-playback`.

Square offers additional setup when the native Spotify engine explicitly reports an account-level audio-key refusal. Generic network failures and individual unavailable tracks do not activate this path. Accounts that work with native playback keep using it.

The recovery player uses the official SDK at `https://sdk.scdn.co/spotify-player.js`, public Spotify Web API commands and app-owned OAuth credentials. It does not intercept open.spotify.com tokens, request DRM licences directly or expose audio samples or keys. The earlier direct-stream experiment remains in the repository but is not used by this fallback.

## Setup and normal playback

1. When Square detects the refusal, it pauses the failed player and opens **Complete Spotify playback setup** when the app is visible. Setup is also available under **Settings → Playback → Alternative Spotify playback** for affected/configured accounts.
2. Register a Spotify developer application with Web Playback SDK enabled and redirect URI `http://127.0.0.1:5588/login`. Ensure the Premium account has access to that application, including developer-mode access rules.
3. Enter its Client ID and authorize the same Premium account in the external browser. An existing search application Client ID is offered as a starting value; its tokens are not shared.
4. **Check compatibility** optionally probes secure-context EME and Widevine availability without Spotify login. **Enable playback** checks DRM and connects the official SDK.
5. When ready, Square enables recovery, closes setup and resumes the failed selection with its position and queue. Subsequent Spotify selections use the SDK while the account refusal is remembered. Local files and YouTube playback retain their own engines.

The Media3 adapter exposes SDK playback to Square’s normal mini player, full player, notification and lock-screen controls. Square retains item metadata, queue, manual next/previous, seek, repeat and shuffle. It sends up to 100 upcoming tracks to Spotify at a time and handles remaining chunks locally. The SDK device is recognized as playback on this phone, rather than mirrored as an external Connect device.

OAuth uses PKCE. Tokens are kept in a dedicated encrypted TokenStore with renewal; no client secret is embedded. Scopes: `streaming`, `user-read-private`, `user-read-email`, `user-read-playback-state`, `user-modify-playback-state`. Disconnecting the SDK or signing out of Square clears SDK tokens and stops its output. A new Client ID requires authorization again.

## Compatibility and limits

Android WebView compatibility depends on secure-context EME/Widevine support. Unsupported DRM, renderer failure, initialization timeout and sanitized SDK/API errors are reported. A foreground service does not add missing browser DRM features. Crossfade, native PCM effects and Spotify offline downloads are unavailable through this player.

The runtime is owned by the normal playback service; its hidden WebView survives closing the activity. A separate non-exported service handles the setup check and the developer test screen, then releases its runtime before normal playback connects. Chromium owns audio focus: a duplicate native focus request caused immediate pauses on the test phone and is deliberately avoided.

The bridge is limited to the locally supplied HTTPS-origin document and the official SDK embedded frame. Only protected-media permission is granted. Logs exclude access tokens and server response bodies. Initialization times out after 45 seconds. Playback requests are cancellable and stale responses are ignored when the selection changes.

## Validation (2026-10-04)

- Compiled and installed on A059 (Android 17), Android WebView 153.0.8010.39.
- On-device DRM probe and app-owned OAuth succeeded. The user confirmed audible official SDK playback.
- The account-refusal diagnostic hook exercised additional setup, activation and recovery into the normal player, preserving the queue. The user confirmed audio also after choosing another playlist track. This simulates the refusal; it is not a test with a genuinely refused Premium account.
- The standalone SDK smoke test continued while the device was Dozing (30,992 → 74,063 ms), and native pause, seek, resume and stop worked.
- `node scripts/check-web-playback-sdk.mjs` passes mocked bridge checks for DRM gating, login-free compatibility probes, SDK loading, renewed token callbacks, URI/state events, natural end versus paused seeks, playback commands and sanitized errors.
- The final installed build preserved a 1,196-item queue. Native media controls advanced Hollywood → Caos; pause and a full-player seek reported PAUSED at 89,826 ms, followed by resumed PLAYING. The artificial refusal flag was removed after testing and playback left paused.
- These checks do not prove every Premium account, long background sessions, all OEM power policies or server-side expired-token behavior.

## References

- https://developer.spotify.com/documentation/web-playback-sdk
- https://developer.spotify.com/documentation/web-playback-sdk/reference
- https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow
- https://developer.spotify.com/documentation/web-api/reference/start-a-users-playback
