# Vendored dependencies

## librespot-core

Copy of [`librespot-core` 0.8.0](https://github.com/librespot-org/librespot)
(MIT, licence retained), wired in through `[patch.crates-io]` in
`native/Cargo.toml` so that `librespot-playback` and `librespot-metadata`
resolve to this copy as well.

### The patch

One line, in `src/config.rs`:

```rust
-pub const OS: &str = std::env::consts::OS;
+pub const OS: &str = "linux";
```

### Why

`OS` decides three things at once: the default client id, the `platform` field
librespot sends to Spotify, and the HTTP user agent. On Android the real value
makes librespot present itself as the Spotify Android app, while this client
authenticates with the desktop "keymaster" client id — the one whose OAuth flow
accepts a loopback redirect, and therefore the only one usable here.

Spotify checks that the client id and the advertised platform agree. They do not,
so the access point handshake succeeds and then every client-token and login5
request is refused with a generic `BAD_REQUEST`:

```
rootlist failed: Invalid state { Login request was denied: BAD_REQUEST }
```

The comment above `OS` upstream warns about exactly this: mocking a platform
requires the rest of the identity to match it. Pinning `"linux"` presents a
consistent desktop identity, which is what the desktop client id expects.

The same fix exists in the librespot fork behind
[Outify](https://github.com/iTomKo/Outify) — commit *"Fixed login5 errors by
mocking linux"* — which is how it was found.

### Maintenance

Re-apply this patch when bumping `librespot-core`. If the whole file is replaced,
the marker to look for is `LOCAL PATCH` in `src/config.rs`.
