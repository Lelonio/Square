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

#### Why

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

#### 3. `src/spclient.rs` and `src/config.rs` — the language to answer in

Spotify localises the artwork of its generated playlists: the cover of "Your
All-Time Top Songs" is served from a URL ending in the language, and the
Italian one is a different picture from the English one. librespot sends no
`Accept-Language` at all, so everything came back English regardless of what
the app was set to.

A `language` field was added to `SessionConfig` (default `"en"`, so behaviour
for anyone not setting it is unchanged), and the spclient request builder now
sends it as `Accept-Language`. The app fills it from the device locale.

Not everything follows it: the charts, daylist, blend and seed-mix covers are
picked by the server from the account's own language and stay English.

#### 4. `src/session.rs` — a non-premium account is not a reason to exit

Upstream refuses to run on a free account, and does it by calling
`std::process::exit(1)` from inside `check_catalogue`, with a TODO of its own
saying it should log out instead:

```rust
-                // TODO: logout instead of exiting
-                exit(1);
```

That ends a command-line daemon tidily. Inside an Android app it takes the whole
process down: the service dies mid-login, Android restarts it, the same account
authenticates again, and the loop repeats with a growing backoff and no message
anywhere. From the outside the app simply vanishes.

The check now only logs, and `engine::start` reads the `type` attribute itself
and returns `premium account required`, which the service turns into a message
and a return to the login screen. The refusal is unchanged; what changed is that
it is an error the caller can handle rather than a process exit.

### Maintenance

Re-apply this patch when bumping `librespot-core`. If the whole file is replaced,
the marker to look for is `LOCAL PATCH` in `src/config.rs`.

## librespot-playback

Copy of [`librespot-playback` 0.8.0](https://github.com/librespot-org/librespot)
(MIT, licence retained), wired in the same way.

### The patch: crossfade

Upstream plays one track at a time, start to finish, and the next one begins
where the last one stopped. Overlapping them cannot be done from outside the
crate: the player owns the decoder and the sink, and nothing it exposes lets a
caller reach the samples of two tracks at once.

`PlayerConfig` gains `crossfade_duration_ms`, zero by default, which leaves
upstream behaviour exactly as it was.

When it is set, the player announces `EndOfTrack` a fade's length before the
track really ends. Whoever owns the queue answers with a load, as it always
does, and that load is what starts the fade: instead of dropping the decoder of
the track being replaced, `start_playback` hands it to `fade_out`, where it is
read for the length of the fade and then dropped. Nothing in the player decides
what plays next, so the Connect device stays the only thing that does.

The mix is equal power, `cos` out and `sin` in, one gain per frame. Two straight
ramps summed dip in the middle, where both tracks are at half; squared cosine
and sine sum to one, so the loudness holds across the overlap. Both sides carry
their own normalisation factor, since normalisation exists to make two masters
sound alike and a fade is the one moment both are heard. The result is clamped
to `32767/32768`, because full scale is asymmetric and a mix that reaches 1.0
wraps to the bottom and is heard as a click.

A track shorter than the fade simply drains early: the incoming track goes on
rising, over silence rather than over music.

### Maintenance

The markers are `LOCAL PATCH` in `src/player.rs` and `src/config.rs`.

## librespot-connect

Copy of [`librespot-connect` 0.8.0](https://github.com/librespot-org/librespot)
(MIT, licence retained), wired in the same way.

### The patch: is this device the one playing?

`ConnectState` knows, and nothing outside the event loop could ask. `Spirc` now
carries an `AtomicBool` that the loop republishes every turn, and a
`Spirc::is_active()` that reads it.

The alternative was reading the cluster, which is a different question wearing
the same clothes. When another client hands playback to this device, Spotify
sends the transfer and this device starts playing immediately, but the account
goes on naming the previous device as active for as long as that device takes to
let go. A web player holds on for tens of seconds. Every guard in the engine that
asked the cluster therefore got "somebody else is playing" while this phone was
the one making the sound, and refused to do its job.

go-librespot keeps the same answer as a single boolean it owns and gates
everything on it; this is that boolean, borrowed back out of librespot.
