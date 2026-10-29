//! Catalogue lookups over the Spotify access point.
//!
//! Everything here deliberately avoids `api.spotify.com`. The Web API meters
//! requests per application, and this client authenticates with librespot's
//! shared client id, so its quota is consumed by every other user of that id and
//! runs out no matter how careful we are. The access point carries the same
//! catalogue data for the logged-in user and is not subject to that budget.
//!
//! Results cross the JNI boundary as JSON strings: the shapes are small and
//! read-only, so a serialiser on each side is far less machinery than mapping
//! protobufs into Java objects field by field.

use crate::engine::{with_session, EngineResult};
use futures_util::stream::{self, StreamExt};
use http::Method;
use librespot_core::{session::Session, spotify_uri::SpotifyUri};
use librespot_metadata::{image::Images, Metadata, Track};
use librespot_protocol::playlist4_external::SelectedListContent;
use protobuf::Message;
use serde_json::{json, Value};

/// Cover art is addressed by file id under a fixed CDN prefix.
const IMAGE_CDN: &str = "https://i.scdn.co/image/";

/// The pseudo-playlist holding "Liked Songs" for the current user.
pub fn collection_uri() -> EngineResult<String> {
    with_session(|session| format!("spotify:user:{}:collection", session.username()))
}

pub fn username() -> EngineResult<String> {
    with_session(|session| session.username())
}

/// The account's own playlists, as a JSON array of `{uri, name}`.
///
/// The endpoint answers with a `SelectedListContent` protobuf, not JSON. Names
/// live in `meta_items`, parallel to `items`, and are absent for folders and for
/// playlists whose metadata was not decorated, so they are matched by position
/// and defaulted rather than assumed present.
pub fn rootlist() -> EngineResult<String> {
    let session = with_session(|s| s.clone())?;
    block_on(async move {
        let bytes = session
            .spclient()
            .get_rootlist(0, None)
            .await
            .map_err(|e| format!("rootlist failed: {e}"))?;

        let list = SelectedListContent::parse_from_bytes(&bytes)
            .map_err(|e| format!("rootlist was not a SelectedListContent: {e}"))?;

        let me = session.username();
        let contents = list.contents;
        let playlists: Vec<Value> = contents
            .items
            .iter()
            .enumerate()
            .filter(|(_, item)| item.uri().starts_with("spotify:playlist:"))
            .map(|(index, item)| {
                let meta = contents.meta_items.get(index);
                let name = meta
                    .map(|meta| meta.attributes.name())
                    .filter(|name| !name.is_empty())
                    .unwrap_or("Senza nome");
                let artwork = meta.and_then(|meta| image_url(meta.attributes.picture()));
                // Whose it is, from the index itself: the Web API answers 404
                // for Spotify's own lists to an application in development
                // mode, which left saved editorial playlists looking editable.
                let mine = meta
                    .map(|meta| meta.owner_username())
                    .filter(|owner| !owner.is_empty())
                    .map(|owner| owner == me);
                json!({ "uri": item.uri(), "name": name, "artworkUrl": artwork, "mine": mine })
            })
            .collect();

        log::info!("rootlist returned {} playlists", playlists.len());
        Ok(Value::from(playlists).to_string())
    })
}

/// Lyrics for a track, as the access point's own JSON.
///
/// Returns `null` rather than an error when Spotify has no lyrics for the
/// track: that is the common case, not a failure, and the caller should show an
/// empty state instead of an error.
pub fn lyrics(track_uri: &str) -> EngineResult<String> {
    let parsed = SpotifyUri::from_uri(track_uri).map_err(|e| format!("bad uri: {e}"))?;
    let SpotifyUri::Track { id } = parsed else {
        return Ok("null".into());
    };

    let session = with_session(|s| s.clone())?;
    block_on(async move {
        match session.spclient().get_lyrics(&id).await {
            Ok(bytes) => Ok(String::from_utf8(bytes.to_vec())
                .unwrap_or_else(|_| "null".into())),
            Err(e) => {
                log::info!("no lyrics for {track_uri}: {e}");
                Ok("null".into())
            }
        }
    })
}

/// What the people this account follows are listening to, as the access point's
/// own JSON.
///
/// The endpoint behind Spotify's "Friend Activity": one entry per friend, each
/// with the user, the track, and when they were last heard from.
pub fn friend_activity() -> EngineResult<String> {
    let session = with_session(|s| s.clone())?;
    block_on(async move {
        let bytes = session
            .spclient()
            .request_as_json(&Method::GET, "/presence-view/v1/buddylist", None, None)
            .await
            .map_err(|e| format!("buddylist failed: {e}"))?;

        Ok(String::from_utf8(bytes.to_vec()).unwrap_or_else(|_| "null".into()))
    })
}

/// Track URIs belonging to a context — a playlist, album, or the collection.
///
/// A context can arrive with its pages already filled or as a set of links to
/// be resolved, and Spotify chooses per context, so both paths are handled.
pub fn context_tracks(uri: &str) -> EngineResult<String> {
    let session = with_session(|s| s.clone())?;
    let uri = uri.to_string();

    block_on(async move {
        let context = session
            .spclient()
            .get_context(&uri)
            .await
            .map_err(|e| format!("context {uri} failed: {e}"))?;

        let mut uris: Vec<String> = Vec::new();
        for page in &context.pages {
            for track in &page.tracks {
                if let Some(track_uri) = track.uri.as_ref() {
                    uris.push(track_uri.clone());
                }
            }

            // An empty page carries a link instead of its contents, and a
            // long playlist is a chain of those: each resolved page names the
            // next one. Following only the first is how a 2000-track playlist
            // used to arrive with a few hundred in it.
            if page.tracks.is_empty() {
                let mut next = page.page_url.as_ref().filter(|u| !u.is_empty()).cloned();
                while let Some(page_url) = next {
                    match resolve_page(&session, &page_url).await {
                        Ok((mut resolved, following)) => {
                            uris.append(&mut resolved);
                            next = following.filter(|u| !u.is_empty() && *u != page_url);
                        }
                        Err(e) => {
                            log::warn!("could not resolve page: {e}");
                            next = None;
                        }
                    }
                }
            }
        }

        log::info!("context {uri} yielded {} tracks", uris.len());
        Ok(Value::from(uris).to_string())
    })
}

/// Fetch a lazy context page: its track URIs, and the page after it if any.
async fn resolve_page(
    session: &Session,
    page_url: &str,
) -> Result<(Vec<String>, Option<String>), String> {
    let bytes = session
        .spclient()
        .get_next_page(page_url)
        .await
        .map_err(|e| e.to_string())?;

    let parsed: Value =
        serde_json::from_slice(&bytes).map_err(|e| format!("page was not JSON: {e}"))?;

    let tracks = parsed
        .get("tracks")
        .and_then(Value::as_array)
        .ok_or_else(|| {
            // The exact shape is not documented; log enough to fix this quickly
            // if Spotify returns something else. Key names only — the values are
            // the user's own library.
            format!(
                "no `tracks` array; keys were {:?}",
                parsed.as_object().map(|o| o.keys().collect::<Vec<_>>())
            )
        })?;

    // Spelling varies by endpoint, and an unknown one simply ends the walk.
    let next = ["next_page_url", "nextPageUrl", "next"]
        .iter()
        .find_map(|key| parsed.get(*key).and_then(Value::as_str))
        .map(str::to_owned);

    let uris = tracks
        .iter()
        .filter_map(|t| t.get("uri").and_then(Value::as_str).map(str::to_owned))
        .collect();

    Ok((uris, next))
}

/// Resolve display metadata for the given track URIs, in the order supplied.
///
/// `uris_json` is a JSON array of Spotify track URIs. Lookups run concurrently
/// because each one is a separate access-point round trip and a playlist screen
/// needs dozens of them; doing it sequentially is the difference between a
/// screen that appears and one that fills in line by line.
///
/// Concurrency is capped, though, and that cap is the whole point of this
/// function's shape. Firing a batch off at once made a long playlist load a
/// different number of tracks every time it was opened — a few hundred, then
/// thirty — because the access point drops requests once enough are in flight
/// and a dropped lookup was silently skipped. A bounded window plus one retry
/// turns that into a wait instead of a hole in the list.
pub fn tracks_metadata(uris_json: &str) -> EngineResult<String> {
    let uris: Vec<String> =
        serde_json::from_str(uris_json).map_err(|e| format!("invalid uri list: {e}"))?;
    let session = with_session(|s| s.clone())?;
    let requested = uris.len();

    block_on(async move {
        let lookups = stream::iter(uris.iter().map(|uri| {
            let session = session.clone();
            async move {
                let parsed = SpotifyUri::from_uri(uri).ok()?;
                for attempt in 0..RESOLVE_ATTEMPTS {
                    match Track::get(&session, &parsed).await {
                        Ok(track) => return Some(track_json(&track)),
                        Err(e) => {
                            // Last attempt, or nothing to gain from waiting.
                            if attempt + 1 == RESOLVE_ATTEMPTS {
                                log::debug!("track lookup failed after retries: {e}");
                            } else {
                                tokio::time::sleep(RETRY_DELAY).await;
                            }
                        }
                    }
                }
                None
            }
        }))
        // `buffered`, not `buffer_unordered`: the caller relies on the order it
        // asked in, which is the order the playlist is in.
        .buffered(RESOLVE_CONCURRENCY);

        // Unresolvable tracks are dropped rather than failing the whole batch:
        // one region-locked or delisted item should not empty a playlist.
        let tracks: Vec<Value> = lookups.filter_map(|t| async move { t }).collect().await;

        // Counts only — these are the user's own library.
        if tracks.len() != requested {
            log::warn!("resolved {} of {} tracks", tracks.len(), requested);
        }
        Ok(Value::from(tracks).to_string())
    })
}

/// How many track lookups may be in flight at once.
///
/// Empirical: the access point starts dropping them well before a hundred, and
/// this is comfortably under whatever that threshold actually is while still
/// being far quicker than resolving one at a time.
const RESOLVE_CONCURRENCY: usize = 12;

/// Attempts per track, the first one included.
const RESOLVE_ATTEMPTS: usize = 3;

const RETRY_DELAY: std::time::Duration = std::time::Duration::from_millis(220);

fn track_json(track: &Track) -> Value {
    json!({
        "uri": track.id.to_uri().unwrap_or_default(),
        "name": track.name,
        "artist": track
            .artists
            .iter()
            .map(|a| a.name.as_str())
            .collect::<Vec<_>>()
            .join(", "),
        // The first artist's own uri, kept for everything that shows one name.
        "artistUri": track
            .artists
            .first()
            .and_then(|a| a.id.to_uri().ok()),
        // And all of them, named and addressed, because a track credited to two
        // people has two pages and the listener presses the one they mean.
        "artists": track
            .artists
            .iter()
            .map(|a| json!({ "name": a.name, "uri": a.id.to_uri().ok() }))
            .collect::<Vec<_>>(),
        "album": track.album.name,
        // And its address, so the title in the player is a way onto the record
        // rather than a caption. The Web API side fills the same field.
        "albumUri": track.album.id.to_uri().ok(),
        "durationMs": track.duration,
        "explicit": track.is_explicit,
        "artworkUrl": largest_cover(&track.album.covers),
    })
}

/// The cover of one playlist, as a URL, or `null`.
///
/// The rootlist carries a picture for playlists the user made and nothing for
/// the ones Spotify makes — the editorial and algorithmic lists keep their art
/// on the playlist itself rather than in the account's index of it, so the tiles
/// for those came out blank.
///
/// Two places to look. `attributes.picture` is the ordinary cover. Algorithmic
/// lists — Discover Weekly, the daily mixes — instead carry theirs among the
/// format attributes as a key/value pair, sometimes already a URL and sometimes
/// a `spotify:image:` URI, so both spellings are accepted.
pub fn playlist_cover(uri: &str) -> EngineResult<String> {
    let parsed = SpotifyUri::from_uri(uri).map_err(|e| format!("bad uri: {e}"))?;
    let SpotifyUri::Playlist { id, .. } = parsed else {
        return Ok("null".into());
    };

    let session = with_session(|s| s.clone())?;
    block_on(async move {
        let bytes = session
            .spclient()
            .get_playlist(&id)
            .await
            .map_err(|e| format!("playlist read failed: {e}"))?;

        let list = SelectedListContent::parse_from_bytes(&bytes)
            .map_err(|e| format!("playlist was not a SelectedListContent: {e}"))?;

        let attributes = &list.attributes;

        // Order matters, and the obvious order is wrong.
        //
        // `picture` is the ordinary cover, and comes first when there is one.
        // Failing that, the sized variants: those are the square art the web
        // client shows, largest first.
        //
        // The `image_url` among the format attributes is *not* the cover. On the
        // generated lists it is the header or the phone wallpaper —
        // `atts-desktop`, `yts-mobile`, `..._MOBILE_WALLPAPER.jpg` — a wide
        // image where a square one belongs, and often of a different year than
        // the playlist carrying it. Last resort, for lists with nothing else.
        let sized = |target: &str| {
            attributes
                .picture_size
                .iter()
                .find(|size| size.target_name() == target)
                .and_then(|size| cdn_url(size.url()))
        };

        let cover = image_url(attributes.picture())
            .or_else(|| sized("xlarge"))
            .or_else(|| sized("large"))
            .or_else(|| sized("default"))
            .or_else(|| {
                attributes
                    .picture_size
                    .last()
                    .and_then(|size| cdn_url(size.url()))
            })
            .or_else(|| {
                attributes
                    .format_attributes
                    .iter()
                    .find(|attribute| attribute.key() == "image_url")
                    .and_then(|attribute| cdn_url(attribute.value()))
            });

        Ok(json!(cover).to_string())
    })
}

/// A playlist's Spotify Mix, as JSON `{mixed, transitions}`, or null.
///
/// A playlist mixed in Spotify's own app is the same playlist seen through a
/// lens named `mix`, and the lens has to be asked for: the desktop client sends
/// `spotify-apply-lenses: mix,auto`, and without it the answer is the plain
/// list, with nothing about the mix in it. Through the lens, the row a
/// transition starts from carries it as the format attribute
/// `automix.auto_transition_recipe`, base64 of a protobuf; see
/// [transition_in]. `mixed` says whether the `mix` lens was applied at all.
pub fn playlist_mix(uri: &str) -> EngineResult<String> {
    let parsed = SpotifyUri::from_uri(uri).map_err(|e| format!("bad uri: {e}"))?;
    let SpotifyUri::Playlist { id, .. } = parsed else {
        return Ok("null".into());
    };

    let session = with_session(|s| s.clone())?;
    block_on(async move {
        let endpoint = format!("/playlist/v2/playlist/{}", id.to_base62().map_err(|e| e.to_string())?);
        let mut headers = http::HeaderMap::new();
        headers.insert("spotify-apply-lenses", http::HeaderValue::from_static("mix,auto"));
        let bytes = session
            .spclient()
            .request(&Method::GET, &endpoint, Some(headers), None)
            .await
            .map_err(|e| format!("playlist read failed: {e}"))?;
        let list = SelectedListContent::parse_from_bytes(&bytes)
            .map_err(|e| format!("playlist was not a SelectedListContent: {e}"))?;

        // Whether it is mixed is the playlist's MixState, not the lens: asked
        // for, the `mix` lens is applied to any playlist at all, mixed or not.
        let playlist_uri = format!("spotify:playlist:{}", id.to_base62().map_err(|e| e.to_string())?);
        let mixed = match extension(&session, &playlist_uri, MIX_STATE).await {
            Ok(Some(bytes)) => mix_state_in(&bytes) == Some(1),
            _ => false,
        };
        let transitions: Vec<Value> = list
            .contents
            .items
            .iter()
            .enumerate()
            .filter_map(|(index, item)| {
                let recipe = item
                    .attributes
                    .format_attributes
                    .iter()
                    .find(|a| a.key() == "automix.auto_transition_recipe")?;
                let mut transition = transition_in(recipe.value())?;
                transition["index"] = json!(index);
                Some(transition)
            })
            .collect();

        Ok(json!({ "mixed": mixed, "transitions": transitions }).to_string())
    })
}

/// `MIX_STATE`, a `spotify.playlistmixing.extensions.mixstate.MixState`:
/// `{ 1: 1 }` on a playlist mixed in Spotify's app, empty on any other.
const MIX_STATE: u64 = 225;

/// The state in a MixState reply: the `Any`'s value, its field 1.
fn mix_state_in(bytes: &[u8]) -> Option<u64> {
    for array in message_fields(bytes, 2) {
        for data in message_fields(array, 3) {
            let any = read_first_message_field(data, 3)?;
            let value = read_first_message_field(any, 2)?;
            let (tag, next) = read_varint(value, 0)?;
            if tag == 1 << 3 {
                return read_varint(value, next).map(|(state, _)| state);
            }
        }
    }
    None
}

/// One transition recipe, as JSON, from its base64.
///
/// What each field is was worked out from transitions made in the official
/// app and read back here, not from a published schema:
///
///   1 { 3 out_ms, 4 in_ms, 5 fade_ms, 6 speed of A, 7 speed of B, 8 kind,
///       10 from uri, 11 to uri, 16 bpm of A, 17 bpm of B }
///   2 { 1 preset id, … the preset's own settings }
///
/// `in_ms` is left out of the message when the next song starts from its
/// beginning, which is what a protobuf does with a zero.
fn transition_in(recipe: &str) -> Option<Value> {
    use base64::Engine as _;
    let bytes = base64::engine::general_purpose::STANDARD.decode(recipe.trim()).ok()?;
    let timing = read_first_message_field(&bytes, 1)?;
    let preset = read_first_message_field(&bytes, 2);

    let mut out_ms = None;
    let mut in_ms = 0u64;
    let mut fade_ms = None;
    let mut speed_a = 1.0f32;
    let mut speed_b = 1.0f32;
    let mut kind = 0u64;
    let mut from = None;
    let mut to = None;
    let mut bpm_a = 0.0f32;
    let mut bpm_b = 0.0f32;
    let mut cursor = 0usize;
    while cursor < timing.len() {
        let (tag, next) = read_varint(timing, cursor)?;
        cursor = next;
        match (tag >> 3, tag & 0x7) {
            (field @ (3 | 4 | 5 | 8), 0) => {
                let (value, next) = read_varint(timing, cursor)?;
                cursor = next;
                match field {
                    3 => out_ms = Some(value),
                    4 => in_ms = value,
                    5 => fade_ms = Some(value),
                    _ => kind = value,
                }
            }
            (field @ (6 | 7 | 16 | 17), 5) => {
                let raw = timing.get(cursor..cursor + 4)?;
                let value = f32::from_le_bytes(raw.try_into().ok()?);
                cursor += 4;
                match field {
                    6 => speed_a = value,
                    7 => speed_b = value,
                    16 => bpm_a = value,
                    _ => bpm_b = value,
                }
            }
            (field @ (10 | 11), 2) => {
                let (payload, next) = read_bytes(timing, cursor)?;
                cursor = next;
                let uri = std::str::from_utf8(payload).ok()?.to_string();
                if field == 10 { from = Some(uri) } else { to = Some(uri) }
            }
            (_, wire) => cursor = skip_field(timing, cursor, wire)?,
        }
    }

    let preset_id = preset.and_then(|preset| {
        let (tag, next) = read_varint(preset, 0)?;
        (tag == 1 << 3).then(|| read_varint(preset, next).map(|(id, _)| id)).flatten()
    });

    Some(json!({
        "from": from?,
        "to": to?,
        "outMs": out_ms?,
        "inMs": in_ms,
        "fadeMs": fade_ms?,
        "speedA": speed_a,
        "speedB": speed_b,
        "bpmA": bpm_a,
        "bpmB": bpm_b,
        "kind": kind,
        "preset": preset_id,
    }))
}

/// Tempo and key of many tracks at once, as a JSON object keyed by URI, each
/// `{bpm, key, mode, camelot}`. A track Spotify has no numbers for is left out.
pub fn tracks_audio_attributes(uris_json: &str) -> EngineResult<String> {
    batched_extension(uris_json, AUDIO_ATTRIBUTES_V2, |data| audio_attributes_in(data, 0))
}

/// What Square's automatic transitions are worked out from, for a few tracks
/// at once: a JSON object keyed by URI, each
/// `{lengthMs, downbeats, fadeInMs, fadeOutMs, vocals, points, sections}`.
///
/// Spotify's own Automix works its transitions out on the phone, from these
/// same traits, and keeps the result to itself; see Mixes.kt for what Square
/// makes of them. Asked for the pair about to play rather than for a whole
/// playlist: the waveforms alone are some forty kilobytes a song.
///
/// - `downbeats`: where each bar starts, from `MIX_BEATS`.
/// - `fadeInMs`, `fadeOutMs`: where the song is first and last really
///   sounding, read off `MIX_WAVEFORMS` (see [loudness_edges]).
/// - `vocals`: how much singing there is, 0 to 100, every tenth of a second,
///   from `MIX_VOCALS`.
/// - `points`: `[kind, ms]`, Spotify's own suggestions from
///   `TRANSITION_POINTS`; 1 and 2 where a song may start and finish leaving,
///   3 and 4 where the next may start and be fully in.
/// - `sections`: where each part of the song starts, from `TRACK_SECTIONS`.
///
/// A song missing its beats or waveform is left out, and has no automatic
/// transition.
pub fn tracks_mix_analysis(uris_json: &str) -> EngineResult<String> {
    let uris: Vec<String> = serde_json::from_str(uris_json).map_err(|e| format!("bad uris: {e}"))?;
    let session = with_session(|s| s.clone())?;

    block_on(async move {
        let (beats, waves, vocals, points, sections) = tokio::join!(
            extension_payloads(&session, &uris, MIX_BEATS),
            extension_payloads(&session, &uris, MIX_WAVEFORMS),
            extension_payloads(&session, &uris, MIX_VOCALS),
            extension_payloads(&session, &uris, TRANSITION_POINTS),
            extension_payloads(&session, &uris, TRACK_SECTIONS),
        );
        let (beats, waves) = (beats?, waves?);
        // Without these the transitions are only less careful.
        let (vocals, points, sections) =
            (vocals.unwrap_or_default(), points.unwrap_or_default(), sections.unwrap_or_default());

        let mut found = serde_json::Map::new();
        for uri in &uris {
            let downbeats = beats.get(uri).map(|b| downbeats_in(b)).unwrap_or_default();
            if downbeats.len() < 10 {
                continue;
            }
            let Some((length_ms, fade_in_ms, fade_out_ms)) = waves.get(uri).and_then(|w| loudness_edges(w))
            else {
                continue;
            };
            found.insert(
                uri.clone(),
                json!({
                    "lengthMs": length_ms,
                    "downbeats": downbeats,
                    "fadeInMs": fade_in_ms,
                    "fadeOutMs": fade_out_ms,
                    "vocals": vocals.get(uri).map(|v| vocal_curve(v)).unwrap_or_default(),
                    "points": points.get(uri).map(|p| transition_points_in(p)).unwrap_or_default(),
                    "sections": sections.get(uri).map(|s| sections_in(s)).unwrap_or_default(),
                }),
            );
        }
        Ok(Value::Object(found).to_string())
    })
}

/// `spotify.playlistmixing.extensions.mixbeats.Beats`:
/// `{ 1 beats_per_bar; 2 repeated Beat { 1 float seconds; 3 beat_in_bar } }`.
const MIX_BEATS: u64 = 217;
/// `...mixvocalactivity.VocalActivity`:
/// `{ 1 float sample_rate; 4 hop in samples; 5 bytes one value a hop }`.
const MIX_VOCALS: u64 = 218;
/// `...mixthreebandwaveforms.ThreeBandWaveforms`:
/// `{ 1 sample_rate; 2 hop in ms; 3, 4, 5 bytes low, mid and high, one a hop }`.
const MIX_WAVEFORMS: u64 = 237;
/// `...commontransitionpoint.TransitionPoints`:
/// `{ 1 repeated Point { 1 kind; 2 position_ms; 3 double weight } }`.
const TRANSITION_POINTS: u64 = 247;
/// `...tracksections.TrackSections`: `{ 1 repeated Section { 1 start_ms } }`.
const TRACK_SECTIONS: u64 = 261;

/// Where each bar starts, in milliseconds.
fn downbeats_in(bytes: &[u8]) -> Vec<u64> {
    message_fields(bytes, 2)
        .into_iter()
        .filter_map(|beat| {
            let fields = scalar_fields(beat);
            let seconds = fields.iter().find(|(f, _)| *f == 1).map(|(_, raw)| f32::from_bits(*raw as u32))?;
            let in_bar = fields.iter().find(|(f, _)| *f == 3).map(|(_, v)| *v)?;
            (in_bar == 1 && seconds >= 0.0).then(|| (seconds * 1000.0).round() as u64)
        })
        .collect()
}

/// A song's length and where it is first and last really sounding, from its
/// waveform: the first and last half second louder on average than
/// [EDGE_LEVEL] of the song's median loudness.
///
/// Where Spotify's own plain crossfades centre themselves, as near as could be
/// told from what its app chose for some 150 pairs of songs: the next song
/// comes in two and a half seconds before its first such moment, and the
/// overlap is centred on the last one.
fn loudness_edges(bytes: &[u8]) -> Option<(u64, u64, u64)> {
    let hop = scalar_fields(bytes).into_iter().find(|(f, _)| *f == 2).map(|(_, v)| v)?;
    let bands: Vec<&[u8]> = (3..=5).filter_map(|f| read_first_message_field(bytes, f)).collect();
    let n = bands.iter().map(|b| b.len()).min()?;
    if hop == 0 || bands.len() < 3 || n < 2 {
        return None;
    }
    let level: Vec<u64> = (0..n).map(|i| bands.iter().map(|b| b[i] as u64).sum()).collect();
    let mut sorted = level.clone();
    sorted.sort_unstable();
    let threshold = sorted[n / 2] as f64 * EDGE_LEVEL;
    let window = ((500 / hop) as usize).max(1);
    let mut prefix = vec![0u64; n + 1];
    for i in 0..n {
        prefix[i + 1] = prefix[i] + level[i];
    }
    let mean = |i: usize| {
        let end = (i + window).min(n);
        (prefix[end] - prefix[i]) as f64 / (end - i).max(1) as f64
    };
    let first = (0..n).find(|&i| mean(i) >= threshold).unwrap_or(0);
    let last = (1..n).rev().find(|&i| mean(i) >= threshold).unwrap_or(n - 1);
    Some((n as u64 * hop, first as u64 * hop, last as u64 * hop))
}

/// See [loudness_edges].
const EDGE_LEVEL: f64 = 0.15;

/// The singing, 0 to 100, every tenth of a second. The trait's own scale
/// rests at about 14 where nobody sings and tops out at 100.
fn vocal_curve(bytes: &[u8]) -> Vec<u8> {
    let fields = scalar_fields(bytes);
    let rate = fields.iter().find(|(f, _)| *f == 1).map(|(_, raw)| f32::from_bits(*raw as u32) as f64);
    let hop = fields.iter().find(|(f, _)| *f == 4).map(|(_, v)| *v as f64);
    let (Some(rate), Some(hop), Some(values)) = (rate, hop, read_first_message_field(bytes, 5)) else {
        return Vec::new();
    };
    if rate <= 0.0 || hop <= 0.0 {
        return Vec::new();
    }
    let frame_ms = hop * 1000.0 / rate;
    let tenths = (values.len() as f64 * frame_ms / 100.0) as usize;
    (0..tenths)
        .map(|k| {
            let from = ((k as f64 * 100.0) / frame_ms) as usize;
            let to = ((((k + 1) as f64 * 100.0) / frame_ms) as usize).clamp(from + 1, values.len());
            let slice = &values[from.min(values.len() - 1)..to];
            let mean = slice.iter().map(|&v| v as f64).sum::<f64>() / slice.len() as f64;
            (((mean - 14.0) / 86.0).clamp(0.0, 1.0) * 100.0).round() as u8
        })
        .collect()
}

/// `[kind, ms]` for each of Spotify's suggested transition points.
fn transition_points_in(bytes: &[u8]) -> Vec<[u64; 2]> {
    message_fields(bytes, 1)
        .into_iter()
        .filter_map(|point| {
            let fields = scalar_fields(point);
            let kind = fields.iter().find(|(f, _)| *f == 1).map(|(_, v)| *v)?;
            let ms = fields.iter().find(|(f, _)| *f == 2).map(|(_, v)| *v).unwrap_or(0);
            Some([kind, ms])
        })
        .collect()
}

/// Where each section starts; the first, at 0, carries no number at all.
fn sections_in(bytes: &[u8]) -> Vec<u64> {
    message_fields(bytes, 1)
        .into_iter()
        .map(|section| scalar_fields(section).into_iter().find(|(f, _)| *f == 1).map(|(_, v)| v).unwrap_or(0))
        .collect()
}

/// One extended-metadata trait of many tracks, a hundred to a request: the
/// trait's own message for each, out of the `Any` it arrives in, by URI.
async fn extension_payloads(
    session: &Session,
    uris: &[String],
    kind: u64,
) -> Result<std::collections::HashMap<String, Vec<u8>>, String> {
    let mut found = std::collections::HashMap::new();
    for (uri, data) in extension_data(session, uris, kind).await? {
        if let Some(value) = read_first_message_field(&data, 3).and_then(|any| read_first_message_field(any, 2)) {
            found.insert(uri, value.to_vec());
        }
    }
    Ok(found)
}

/// Every `EntityExtensionData` answering one trait of many tracks, by URI.
async fn extension_data(session: &Session, uris: &[String], kind: u64) -> Result<Vec<(String, Vec<u8>)>, String> {
    let mut found = Vec::new();
    for chunk in uris.chunks(100) {
        let mut body = Vec::new();
        for uri in chunk {
            let mut query = Vec::new();
            write_varint(&mut query, 1 << 3);
            write_varint(&mut query, kind);
            let mut entity = Vec::new();
            write_string_field(&mut entity, 1, uri);
            write_len_delimited(&mut entity, 2, &query);
            write_len_delimited(&mut body, 2, &entity);
        }
        let bytes = session
            .spclient()
            .request(&Method::POST, "/extended-metadata/v0/extended-metadata", None, Some(&body))
            .await
            .map_err(|e| format!("extended metadata failed: {e}"))?;
        // BatchedExtensionResponse { 2 EntityExtensionDataArray { 3
        // EntityExtensionData { 2 entity_uri, 3 Any } } }, one per entity.
        for array in message_fields(&bytes, 2) {
            for data in message_fields(array, 3) {
                if let Some(uri) = read_first_message_field_str(data, 2) {
                    found.push((uri, data.to_vec()));
                }
            }
        }
    }
    Ok(found)
}

/// One extended-metadata trait of many tracks, each answer read by [read]
/// into the JSON object returned, keyed by URI.
fn batched_extension(
    uris_json: &str,
    kind: u64,
    read: fn(&[u8]) -> Option<Value>,
) -> EngineResult<String> {
    let uris: Vec<String> = serde_json::from_str(uris_json).map_err(|e| format!("bad uris: {e}"))?;
    let session = with_session(|s| s.clone())?;

    block_on(async move {
        let mut found = serde_json::Map::new();
        for (uri, data) in extension_data(&session, &uris, kind).await? {
            if let Some(value) = read(&data) {
                found.insert(uri, value);
            }
        }
        Ok(Value::Object(found).to_string())
    })
}

/// Every scalar field of a message as `(number, raw)`: a varint as read, a
/// fixed32 or fixed64 as its bits, for the caller to read as the float it is.
fn scalar_fields(bytes: &[u8]) -> Vec<(u32, u64)> {
    let mut found = Vec::new();
    let mut cursor = 0usize;
    while cursor < bytes.len() {
        let Some((key, next)) = read_varint(bytes, cursor) else { break };
        cursor = next;
        let field = (key >> 3) as u32;
        match key & 0x7 {
            0 => {
                let Some((value, next)) = read_varint(bytes, cursor) else { break };
                found.push((field, value));
                cursor = next;
            }
            5 => {
                let Some(raw) = bytes.get(cursor..cursor + 4) else { break };
                found.push((field, u32::from_le_bytes(raw.try_into().unwrap_or_default()) as u64));
                cursor += 4;
            }
            1 => {
                let Some(raw) = bytes.get(cursor..cursor + 8) else { break };
                found.push((field, u64::from_le_bytes(raw.try_into().unwrap_or_default())));
                cursor += 8;
            }
            wire => {
                let Some(next) = skip_field(bytes, cursor, wire) else { break };
                cursor = next;
            }
        }
    }
    found
}

/// Every length-delimited field [field] of a message.
fn message_fields(bytes: &[u8], field: u32) -> Vec<&[u8]> {
    let mut found = Vec::new();
    let mut cursor = 0usize;
    while cursor < bytes.len() {
        let Some((key, next)) = read_varint(bytes, cursor) else { break };
        cursor = next;
        let wire = key & 0x7;
        if (key >> 3) as u32 == field && wire == 2 {
            let Some((payload, next)) = read_bytes(bytes, cursor) else { break };
            found.push(payload);
            cursor = next;
        } else {
            let Some(next) = skip_field(bytes, cursor, wire) else { break };
            cursor = next;
        }
    }
    found
}

/// The title of one playlist, or `null`.
///
/// For a playlist arriving as a link there is nowhere else to read it: the
/// rootlist only holds what the account follows, and the Web API answers 404 for
/// everything Spotify generates itself. The access point knows them all.
pub fn playlist_name(uri: &str) -> EngineResult<String> {
    let parsed = SpotifyUri::from_uri(uri).map_err(|e| format!("bad uri: {e}"))?;
    let SpotifyUri::Playlist { id, .. } = parsed else {
        return Ok("null".into());
    };

    let session = with_session(|s| s.clone())?;
    block_on(async move {
        let bytes = session
            .spclient()
            .get_playlist(&id)
            .await
            .map_err(|e| format!("playlist read failed: {e}"))?;

        let list = SelectedListContent::parse_from_bytes(&bytes)
            .map_err(|e| format!("playlist was not a SelectedListContent: {e}"))?;

        let name = list.attributes.name().trim().to_string();
        Ok(json!(if name.is_empty() { None } else { Some(name) }).to_string())
    })
}

/// Accepts either a URL or a `spotify:image:<hex>` URI, and returns a URL.
fn cdn_url(value: &str) -> Option<String> {
    if value.is_empty() {
        None
    } else if value.starts_with("http") {
        Some(value.to_string())
    } else {
        value
            .rsplit(':')
            .next()
            .filter(|id| !id.is_empty())
            .map(|id| format!("{IMAGE_CDN}{id}"))
    }
}

/// A CDN URL for a raw image id, or None when the field is empty.
///
/// Playlist artwork arrives as the image's raw file id rather than a URL, and
/// the same 20-byte id hex-encoded is what the CDN serves. Playlists with no
/// custom cover simply carry nothing here — Spotify builds a mosaic from the
/// tracks client-side, which is why the fallback tile matters.
fn image_url(picture: &[u8]) -> Option<String> {
    if picture.is_empty() {
        return None;
    }
    let hex: String = picture.iter().map(|byte| format!("{byte:02x}")).collect();
    Some(format!("{IMAGE_CDN}{hex}"))
}

/// The biggest available cover, as a CDN URL.
fn largest_cover(covers: &Images) -> Option<String> {
    covers
        .iter()
        .max_by_key(|image| image.width * image.height)
        .and_then(|image| image.id.to_base16().ok())
        .map(|id| format!("{IMAGE_CDN}{id}"))
}

/// Run an access-point request on the engine's runtime.
///
/// These calls arrive on Kotlin background threads that know nothing about
/// tokio, so they cannot await anything themselves. The runtime handle is taken
/// first and the engine lock released before blocking, so a slow catalogue
/// request cannot hold up play/pause coming from the notification.
fn block_on<T>(future: impl std::future::Future<Output = Result<T, String>>) -> EngineResult<T> {
    crate::engine::runtime_handle()?.block_on(future)
}

/// The Canvas for a track: a short looping video Spotify shows behind the
/// player, when the artist has uploaded one.
///
/// Returns `null` — not an error — when the track has none. Most tracks do not,
/// so absence is the ordinary answer.
///
/// The request and response are hand-encoded rather than built from
/// `librespot_protocol::canvaz`. That module exists but its `EntityCanvazResponse`
/// carries only the nested `Canvaz` type and none of the repeated field that
/// actually holds the results, and there is no request message at all — the
/// extracted proto is incomplete. The wire format here is small enough to write
/// out directly, and doing so keeps the shape visible instead of hidden behind a
/// generated type that does not match the endpoint.
pub fn canvas(track_uri: &str) -> EngineResult<String> {
    let session = with_session(|s| s.clone())?;
    let uri = track_uri.to_string();

    block_on(async move {
        // EntityCanvazRequest { repeated Entity entities = 1 }
        //   Entity { string entity_uri = 1 }
        let mut entity = Vec::new();
        write_string_field(&mut entity, 1, &uri);

        let mut body = Vec::new();
        write_len_delimited(&mut body, 1, &entity);

        let bytes = session
            .spclient()
            .request(
                &Method::POST,
                "/canvaz-cache/v0/canvases",
                None,
                Some(&body),
            )
            .await
            .map_err(|e| format!("canvas request failed: {e}"))?;

        Ok(parse_canvas(&bytes).unwrap_or_else(|| "null".to_string()))
    })
}

/// Pulls the first canvas out of an `EntityCanvazResponse`.
///
/// Only the two fields the player needs are read — the media URL and the kind —
/// and anything else is skipped by length. A response that does not parse yields
/// `None`, which the caller shows as "no canvas" rather than as a failure.
fn parse_canvas(bytes: &[u8]) -> Option<String> {
    // EntityCanvazResponse { repeated Canvaz canvases = 1 }
    let canvas = read_first_message_field(bytes, 1)?;

    let mut url: Option<String> = None;
    let mut kind: u64 = 0;
    let mut cursor = 0usize;

    while cursor < canvas.len() {
        let (key, next) = read_varint(canvas, cursor)?;
        cursor = next;
        let field = key >> 3;
        let wire = key & 0x7;

        match (field, wire) {
            // Canvaz { string url = 2 }
            (2, 2) => {
                let (value, next) = read_bytes(canvas, cursor)?;
                url = Some(String::from_utf8(value.to_vec()).ok()?);
                cursor = next;
            }
            // Canvaz { Type type = 4 }
            (4, 0) => {
                let (value, next) = read_varint(canvas, cursor)?;
                kind = value;
                cursor = next;
            }
            _ => cursor = skip_field(canvas, cursor, wire)?,
        }
    }

    let url = url?;
    // 0 is IMAGE, everything above it is some flavour of video or GIF. The
    // player only needs to know whether to hand this to a video surface.
    let is_video = kind != 0;
    Some(json!({ "url": url, "isVideo": is_video }).to_string())
}

// --- Minimal protobuf wire helpers -----------------------------------------

/// An access token minted by the engine's own session.
///
/// The app has one of its own from signing in, but that one lapses while the
/// engine goes on working: playback holds the credential the access point
/// issued and never needs the OAuth half. Anything that talks to spclient
/// outside the engine — the video manifest, the licence — should ask here
/// instead, or it stops working on exactly the days playback does not.
pub fn access_token() -> EngineResult<String> {
    let session = with_session(|s| s.clone())?;
    block_on(async move {
        session
            .login5()
            .auth_token()
            .await
            .map(|token| token.access_token)
            .map_err(|e| format!("no access token: {e}"))
    })
}

/// The music video a track has, or `null`.
///
/// Two questions to the catalogue, because the answer lives in two places.
/// `VIDEO_ASSOCIATIONS` on an ordinary track names the *other* track — the
/// variant of the same song that is the video — and `ORIGINAL_VIDEO` on that
/// one carries the file id the video manifest is addressed by.
///
/// Asked here rather than read from the player state, which is where the
/// official client takes it from: the account only attaches it for a device it
/// believes can show video, and it does not believe that of this one whatever
/// it declares about itself. The catalogue answers anybody who asks.
///
/// Hand-encoded like the canvas request below, and read the same way: the
/// request is three fields deep and the answer is one field inside an `Any`,
/// so writing the bytes out keeps both shapes visible.
///
///   BatchedEntityRequest { repeated EntityRequest entity_request = 2 }
///   EntityRequest       { string entity_uri = 1, repeated ExtensionQuery query = 2 }
///   ExtensionQuery      { ExtensionKind extension_kind = 1 }
/// The other Spotify ids for the same recording, and its ISRC.
///
/// A song is on Spotify many times over — the single, the album, the remaster,
/// the market-specific pressing — and each of those is its own id. Anything
/// addressed by id therefore misses whenever the listener happens to be playing
/// a different edition of the same recording, which is what the word-by-word
/// lyric database does; see AmllLyrics.
///
/// Both answers come from metadata the account already serves: `alternatives`
/// is Spotify's own list of the copies it relinks between, and the ISRC is the
/// recording's number in the industry's register, which is the same on every
/// pressing of it.
pub fn track_relatives(track_uri: &str) -> EngineResult<String> {
    let uri = SpotifyUri::from_uri(track_uri).map_err(|e| format!("bad track uri: {e}"))?;
    let session = with_session(|s| s.clone())?;

    block_on(async move {
        let track = Track::get(&session, &uri)
            .await
            .map_err(|e| format!("track lookup failed: {e}"))?;

        let alternatives: Vec<String> = track
            .alternatives
            .0
            .iter()
            .filter_map(|id| id.to_uri().ok())
            .collect();

        let isrc = track
            .external_ids
            .0
            .iter()
            .find(|id| id.external_type.eq_ignore_ascii_case("isrc"))
            .map(|id| id.id.clone())
            .unwrap_or_default();

        Ok(json!({ "isrc": isrc, "alternatives": alternatives }).to_string())
    })
}

pub fn track_video(track_uri: &str) -> EngineResult<String> {
    let session = with_session(|s| s.clone())?;
    let uri = track_uri.to_string();

    block_on(async move {
        // A video podcast's episode is its own video: there is no second
        // entity to be pointed at, so the file is asked of the episode itself.
        let associated = if uri.starts_with("spotify:episode:") {
            Ok(uri.clone())
        } else {
            match extension(&session, &uri, VIDEO_ASSOCIATIONS).await? {
                Some(bytes) => uris_in(&bytes)
                    .into_iter()
                    .find(|found| found != &uri)
                    .ok_or_else(|| String::new()),
                None => Err(String::new()),
            }
        };

        // No association is the ordinary answer: almost no track has a video.
        let Ok(video_uri) = associated else {
            return Ok("null".to_string());
        };

        let Some(bytes) = extension(&session, &video_uri, ORIGINAL_VIDEO).await? else {
            return Ok("null".to_string());
        };

        match file_id_in(&bytes) {
            Some(file_id) => Ok(json!({ "trackUri": video_uri, "fileId": file_id }).to_string()),
            None => Ok("null".to_string()),
        }
    })
}

/// `AUDIO_ATTRIBUTES_V2`: a track's tempo and key, as the official app's Mix
/// reads them. The public audio-features endpoint is shut to new apps, and the
/// old `audio-attributes` one is gone; this is where the numbers went. See
/// [audio_attributes_in] for the message.
const AUDIO_ATTRIBUTES_V2: u64 = 222;

/// The first message in [bytes] shaped like `AudioAttributes`, as JSON.
///
///   AudioAttributes { double bpm = 1; AudioKey key = 2 }
///   AudioKey        { string key = 1; int32 mode = 2; Camelot camelot = 3 }
///   Camelot         { string value = 1; string color = 2 }
///
/// `mode` is 1 for minor and 2 for major, as the Camelot letter agrees.
/// Searched for rather than walked down to: it sits inside an `Any` inside the
/// batched reply, at a depth that is the reply's business.
fn audio_attributes_in(bytes: &[u8], depth: u32) -> Option<Value> {
    if depth > 8 {
        return None;
    }
    let mut bpm = None;
    let mut key = None;
    let mut nested = Vec::new();
    let mut cursor = 0usize;
    while cursor < bytes.len() {
        let (tag, next) = read_varint(bytes, cursor)?;
        cursor = next;
        let (field, wire) = (tag >> 3, tag & 0x7);
        match wire {
            1 => {
                let raw = bytes.get(cursor..cursor + 8)?;
                if field == 1 {
                    bpm = Some(f64::from_le_bytes(raw.try_into().ok()?));
                }
                cursor += 8;
            }
            2 => {
                let (payload, next) = read_bytes(bytes, cursor)?;
                if field == 2 {
                    key = audio_key_in(payload);
                }
                nested.push(payload);
                cursor = next;
            }
            _ => cursor = skip_field(bytes, cursor, wire)?,
        }
    }
    if let Some(bpm) = bpm.filter(|bpm| (20.0..400.0).contains(bpm)) {
        let (name, mode, camelot) = key.unwrap_or_default();
        return Some(json!({ "bpm": bpm, "key": name, "mode": mode, "camelot": camelot }));
    }
    nested.into_iter().find_map(|payload| audio_attributes_in(payload, depth + 1))
}

/// `AudioKey`'s name, mode and Camelot value, whichever are there.
fn audio_key_in(bytes: &[u8]) -> Option<(String, i64, String)> {
    let mut name = String::new();
    let mut mode = -1i64;
    let mut camelot = String::new();
    let mut cursor = 0usize;
    while cursor < bytes.len() {
        let (tag, next) = read_varint(bytes, cursor)?;
        cursor = next;
        match (tag >> 3, tag & 0x7) {
            (1, 2) => {
                let (payload, next) = read_bytes(bytes, cursor)?;
                name = std::str::from_utf8(payload).ok()?.to_string();
                cursor = next;
            }
            (2, 0) => {
                let (value, next) = read_varint(bytes, cursor)?;
                mode = value as i64;
                cursor = next;
            }
            (3, 2) => {
                let (payload, next) = read_bytes(bytes, cursor)?;
                if let Some(value) = read_first_message_field_str(payload, 1) {
                    camelot = value;
                }
                cursor = next;
            }
            (_, wire) => cursor = skip_field(bytes, cursor, wire)?,
        }
    }
    Some((name, mode, camelot))
}

/// The first string field [field] of a message.
fn read_first_message_field_str(bytes: &[u8], field: u32) -> Option<String> {
    read_first_message_field(bytes, field)
        .and_then(|payload| std::str::from_utf8(payload).ok())
        .map(str::to_string)
}

/// `VIDEO_ASSOCIATIONS`, which names a song's video as another track.
const VIDEO_ASSOCIATIONS: u64 = 99;

/// `ORIGINAL_VIDEO`, which carries the file id that addresses the video itself.
const ORIGINAL_VIDEO: u64 = 85;

/// One extended-metadata question about one entity.
async fn extension(
    session: &Session,
    entity_uri: &str,
    kind: u64,
) -> Result<Option<Vec<u8>>, String> {
    let mut query = Vec::new();
    write_varint(&mut query, 1 << 3);
    write_varint(&mut query, kind);

    let mut entity = Vec::new();
    write_string_field(&mut entity, 1, entity_uri);
    write_len_delimited(&mut entity, 2, &query);

    let mut body = Vec::new();
    write_len_delimited(&mut body, 2, &entity);

    let bytes = session
        .spclient()
        .request(
            &Method::POST,
            "/extended-metadata/v0/extended-metadata",
            None,
            Some(&body),
        )
        .await
        .map_err(|e| format!("extended metadata failed: {e}"))?;

    Ok(Some(bytes.to_vec()).filter(|bytes| !bytes.is_empty()))
}

/// Every `spotify:track:` uri in a response, whatever it is nested inside.
///
/// Scanned rather than parsed: the reply wraps the extension's own message in
/// an `Any`, and the uri appears at a depth that changes with the extension.
/// What is being looked for is unmistakable on sight.
fn uris_in(bytes: &[u8]) -> Vec<String> {
    let needle = b"spotify:track:";
    let mut found = Vec::new();
    for start in 0..bytes.len() {
        if bytes[start..].starts_with(needle) {
            let end = bytes[start..]
                .iter()
                .position(|byte| !(0x20..0x7f).contains(byte))
                .map(|offset| start + offset)
                .unwrap_or(bytes.len());
            if let Ok(uri) = std::str::from_utf8(&bytes[start..end]) {
                found.push(uri.to_string());
            }
        }
    }
    found
}

/// The sixteen bytes of a file id, hex encoded.
///
/// `OriginalVideo { bytes file_id = 1 }`, inside the `Any` the extension
/// replies with: the tail of the message is `0a 10` and then the id, which is
/// the one length-delimited sixteen-byte field in the whole answer.
fn file_id_in(bytes: &[u8]) -> Option<String> {
    let position = bytes
        .windows(2)
        .rposition(|window| window == [0x0a, 0x10])?;
    let start = position + 2;
    let end = start + 16;
    if end > bytes.len() {
        return None;
    }
    Some(bytes[start..end].iter().map(|b| format!("{b:02x}")).collect())
}

fn write_varint(out: &mut Vec<u8>, mut value: u64) {
    loop {
        let byte = (value & 0x7f) as u8;
        value >>= 7;
        if value == 0 {
            out.push(byte);
            return;
        }
        out.push(byte | 0x80);
    }
}

fn write_len_delimited(out: &mut Vec<u8>, field: u32, payload: &[u8]) {
    write_varint(out, ((field as u64) << 3) | 2);
    write_varint(out, payload.len() as u64);
    out.extend_from_slice(payload);
}

fn write_string_field(out: &mut Vec<u8>, field: u32, value: &str) {
    write_len_delimited(out, field, value.as_bytes());
}

fn read_varint(bytes: &[u8], mut cursor: usize) -> Option<(u64, usize)> {
    let mut value = 0u64;
    let mut shift = 0u32;
    loop {
        let byte = *bytes.get(cursor)?;
        cursor += 1;
        value |= ((byte & 0x7f) as u64) << shift;
        if byte & 0x80 == 0 {
            return Some((value, cursor));
        }
        shift += 7;
        if shift > 63 {
            return None;
        }
    }
}

fn read_bytes(bytes: &[u8], cursor: usize) -> Option<(&[u8], usize)> {
    let (len, cursor) = read_varint(bytes, cursor)?;
    let end = cursor.checked_add(len as usize)?;
    Some((bytes.get(cursor..end)?, end))
}

/// Advances past a field whose contents we do not care about.
fn skip_field(bytes: &[u8], cursor: usize, wire: u64) -> Option<usize> {
    match wire {
        0 => Some(read_varint(bytes, cursor)?.1),
        1 => cursor.checked_add(8),
        2 => Some(read_bytes(bytes, cursor)?.1),
        5 => cursor.checked_add(4),
        _ => None,
    }
}

/// The payload of the first length-delimited field with the given number.
fn read_first_message_field(bytes: &[u8], field: u32) -> Option<&[u8]> {
    let mut cursor = 0usize;
    while cursor < bytes.len() {
        let (key, next) = read_varint(bytes, cursor)?;
        cursor = next;
        let wire = key & 0x7;
        if (key >> 3) as u32 == field && wire == 2 {
            return Some(read_bytes(bytes, cursor)?.0);
        }
        cursor = skip_field(bytes, cursor, wire)?;
    }
    None
}

/// Appends one item to one of the user's playlists, through the playlist
/// service rather than the Web API.
///
/// For the one kind of item the Web API refuses: a local file, written as
/// `spotify:local:artist:album:title:seconds`, which is how Spotify's own apps
/// put one in a playlist. The change is the same ADD those apps send, at the
/// end of the list and against its current revision.
pub fn add_to_playlist(playlist_uri: &str, item_uri: &str) -> EngineResult<()> {
    use librespot_protocol::playlist4_external::{
        op::Kind, Add, Delta, Item, ItemAttributes, ListChanges, Op,
    };

    let parsed = SpotifyUri::from_uri(playlist_uri).map_err(|e| format!("bad uri: {e}"))?;
    let SpotifyUri::Playlist { id, .. } = parsed else {
        return Err(format!("{playlist_uri} is not a playlist"));
    };
    let base62 = id.to_base62().map_err(|e| format!("bad playlist id: {e}"))?;

    let session = with_session(|s| s.clone())?;
    block_on(async move {
        let bytes = session
            .spclient()
            .get_playlist(&id)
            .await
            .map_err(|e| format!("playlist read failed: {e}"))?;
        let list = SelectedListContent::parse_from_bytes(&bytes)
            .map_err(|e| format!("playlist was not a SelectedListContent: {e}"))?;

        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as i64)
            .unwrap_or_default();

        // Only the moment it went in: who added it is the session's, and the
        // service fills it in itself. Sent, it was refused as an invalid
        // argument.
        let mut attributes = ItemAttributes::new();
        attributes.set_timestamp(now);
        let mut item = Item::new();
        item.set_uri(item_uri.to_string());
        item.attributes = Some(attributes).into();

        // At the end, said once: an index as well as "last" is two positions.
        let mut add = Add::new();
        add.items.push(item);
        add.set_add_last(true);

        let mut op = Op::new();
        op.set_kind(Kind::ADD);
        op.add = Some(add).into();

        let mut delta = Delta::new();
        delta.ops.push(op);

        let mut changes = ListChanges::new();
        changes.set_base_revision(list.revision().to_vec());
        changes.deltas.push(delta);
        changes.set_want_resulting_revisions(true);

        session
            .spclient()
            .request_with_protobuf(
                &Method::POST,
                &format!("/playlist/v2/playlist/{base62}/changes"),
                None,
                &changes,
            )
            .await
            .map_err(|e| format!("playlist change refused: {e}"))?;
        Ok(())
    })
}

/// Takes an item out of one of the user's playlists, through the playlist
/// service: the way out for a local file, which the Web API cannot remove
/// any more than it can add one. Every occurrence goes, as with the Web API's
/// removal; the item is named rather than placed, so a long playlist does not
/// have to be read to find where it is.
pub fn remove_from_playlist(playlist_uri: &str, item_uri: &str) -> EngineResult<()> {
    use librespot_protocol::playlist4_external::{op::Kind, Delta, Item, ListChanges, Op, Rem};

    let parsed = SpotifyUri::from_uri(playlist_uri).map_err(|e| format!("bad uri: {e}"))?;
    let SpotifyUri::Playlist { id, .. } = parsed else {
        return Err(format!("{playlist_uri} is not a playlist"));
    };
    let base62 = id.to_base62().map_err(|e| format!("bad playlist id: {e}"))?;

    let session = with_session(|s| s.clone())?;
    block_on(async move {
        let bytes = session
            .spclient()
            .get_playlist(&id)
            .await
            .map_err(|e| format!("playlist read failed: {e}"))?;
        let list = SelectedListContent::parse_from_bytes(&bytes)
            .map_err(|e| format!("playlist was not a SelectedListContent: {e}"))?;

        let mut item = Item::new();
        item.set_uri(item_uri.to_string());

        let mut rem = Rem::new();
        rem.items.push(item);
        rem.set_items_as_key(true);

        let mut op = Op::new();
        op.set_kind(Kind::REM);
        op.rem = Some(rem).into();

        let mut delta = Delta::new();
        delta.ops.push(op);

        let mut changes = ListChanges::new();
        changes.set_base_revision(list.revision().to_vec());
        changes.deltas.push(delta);
        changes.set_want_resulting_revisions(true);

        session
            .spclient()
            .request_with_protobuf(
                &Method::POST,
                &format!("/playlist/v2/playlist/{base62}/changes"),
                None,
                &changes,
            )
            .await
            .map_err(|e| format!("playlist change refused: {e}"))?;
        Ok(())
    })
}

/// Whether a playlist is the account's own, from the playlist service, or
/// `null` when it does not say. For the lists the Web API will not describe.
pub fn playlist_mine(uri: &str) -> EngineResult<String> {
    let parsed = SpotifyUri::from_uri(uri).map_err(|e| format!("bad uri: {e}"))?;
    let SpotifyUri::Playlist { id, .. } = parsed else {
        return Ok("null".into());
    };

    let session = with_session(|s| s.clone())?;
    block_on(async move {
        let bytes = session
            .spclient()
            .get_playlist(&id)
            .await
            .map_err(|e| format!("playlist read failed: {e}"))?;
        let list = SelectedListContent::parse_from_bytes(&bytes)
            .map_err(|e| format!("playlist was not a SelectedListContent: {e}"))?;
        let owner = list.owner_username();
        Ok(if owner.is_empty() {
            "null".into()
        } else {
            json!(owner == session.username()).to_string()
        })
    })
}
