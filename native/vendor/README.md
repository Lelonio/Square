# Vendored dependencies

## librespot-core

Copy of [`librespot-core` 0.8.0](https://github.com/librespot-org/librespot)
(MIT, licence retained), wired in through `[patch.crates-io]` in
`native/Cargo.toml` so that `librespot-playback` and `librespot-metadata`
resolve to this copy as well.

### The patches

#### 1. `src/mercury` — POST with header fields

Spotify builds the account's listening history from events posted to
`hm://event-service/v1/events`, and that request is a POST carrying
`Accept-Language` and `X-ClientTimeStamp` as Mercury header fields. Upstream's
Mercury client has neither: no POST method, and `MercuryRequest` has nowhere to
put a header field. Both were added — a `MercuryMethod::Post`, a `user_fields`
list on the request, and a `post()` on the manager.

The alternative was building the packet by hand and calling `send_packet`
directly, which works but throws the reply away: the sequence number would not
be in Mercury's pending table, so there is no way to see whether Spotify
accepted the event. On a format this app reverse-engineered, that answer is
worth the patch.

#### 2. `src/config.rs` — the advertised OS

One line:

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
