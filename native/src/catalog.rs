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
                json!({ "uri": item.uri(), "name": name, "artworkUrl": artwork })
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
        "album": track.album.name,
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
        let cover = image_url(attributes.picture())
            .or_else(|| {
                attributes
                    .format_attributes
                    .iter()
                    .find(|attribute| attribute.key().contains("image_url"))
                    .and_then(|attribute| cdn_url(attribute.value()))
            })
            .or_else(|| {
                // The sized variants, when there is no single picture: the
                // largest is last in Spotify's own order, and any of them is a
                // better tile than none.
                attributes
                    .picture_size
                    .iter()
                    .last()
                    .and_then(|size| cdn_url(size.url()))
            });

        Ok(json!(cover).to_string())
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
