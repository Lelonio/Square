//! Keeping tracks on the phone, and finding them again.
//!
//! A download here is the file Spotify's CDN serves, byte for byte, still
//! encrypted with its own AES key — plus a small JSON sidecar holding that key
//! and the handful of metadata fields the player would otherwise have gone to
//! the network for. Nothing is decrypted on the way in.
//!
//! That choice is what keeps the feature small. `player.rs` already knows how
//! to turn one of these files into sound: it is exactly what arrives when a
//! track is streamed, and what librespot's own cache stores. So the offline
//! path is the online path with the first three steps removed, rather than a
//! second decoder, a second normalisation rule and a second set of bugs. It
//! also means a download is no more extractable than the cache already was.
//!
//! Layout under the root the Kotlin side chooses:
//!
//! ```text
//! <root>/.nomedia
//! <root>/audio/<xx>/<rest of the track id>        the encrypted file
//! <root>/meta/<xx>/<rest of the track id>.json    the sidecar
//! ```
//!
//! Sharded on the first byte of the id for the same reason librespot's cache
//! is: several thousand files in one directory is a directory nothing
//! enumerates quickly, and a library of downloads gets there.
//!
//! The sidecar is written last and atomically, so its presence is the single
//! signal that a download is complete. A `.part` file beside the audio is a
//! download in progress or an interrupted one, and either way it is where the
//! next attempt resumes from.

use crate::engine::{self, EngineResult};
use http::{header::RANGE, Method, Request, StatusCode};
use http_body_util::BodyExt;
use librespot_core::{cdn_url::CdnUrl, session::Session, spotify_uri::SpotifyUri, SpotifyId};
use librespot_metadata::audio::{AudioFileFormat, AudioItem, UniqueFields};
use librespot_playback::config::{DownloadLookup, DownloadedTrack};
use serde_json::{json, Value};
use std::{
    collections::HashSet,
    fs,
    io::Write,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
};

/// How much is asked for in one request.
///
/// Small enough that a cancellation is acted on promptly and the progress ring
/// moves, large enough that a three-minute track is a couple of dozen requests
/// rather than hundreds.
const CHUNK: usize = 512 * 1024;

/// How many times one chunk is retried before the download gives up.
///
/// The CDN URL is re-resolved between attempts: the usual reason a chunk fails
/// on a phone is that the signed URL expired while the queue was working
/// through a long playlist, and that is fixed by asking for a new one rather
/// than by waiting.
const CHUNK_ATTEMPTS: u32 = 3;

/// How long after playback asks for a key a download may ask for one.
///
/// Long enough to cover a track starting and its successor being preloaded,
/// which is the pair of requests a skip makes.
const PLAYBACK_PRIORITY: std::time::Duration = std::time::Duration::from_secs(12);

/// A chunk that has not arrived in this long is not going to.
const CHUNK_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(30);

/// What a download says when Spotify is refusing to hand out audio keys.
///
/// Matched by the Kotlin queue, so it stays a stable string.
pub const KEY_THROTTLED: &str = "audio keys are being refused";

/// Where downloads live. Set from Kotlin before the engine starts, and again if
/// the listener moves them to a memory card.
static ROOT: Mutex<Option<PathBuf>> = Mutex::new(None);

/// Track ids whose download has been asked to stop.
///
/// Checked between chunks. A cancelled download keeps its `.part` file: the
/// listener may have cancelled a whole queue because it started on mobile data,
/// and throwing away what was already paid for would be a poor answer to that.
static CANCELLED: Mutex<Option<HashSet<String>>> = Mutex::new(None);

pub fn set_root(path: &str) -> EngineResult<()> {
    let root = PathBuf::from(path);
    fs::create_dir_all(root.join("audio")).map_err(|e| format!("download dir failed: {e}"))?;
    fs::create_dir_all(root.join("meta")).map_err(|e| format!("download dir failed: {e}"))?;

    // Only matters on a memory card, where the store sits under a path a media
    // scanner will walk. Harmless on internal storage, and cheaper than
    // deciding which of the two this is.
    let _ = fs::File::create(root.join(".nomedia"));

    *ROOT.lock().map_err(|_| "download root poisoned")? = Some(root);
    Ok(())
}

fn root() -> Option<PathBuf> {
    ROOT.lock().ok()?.clone()
}

/// `<root>/<kind>/<xx>/<rest>`, with the id split the way librespot splits it.
fn shard(root: &Path, kind: &str, id: &str, suffix: &str) -> Option<PathBuf> {
    if id.len() < 3 {
        return None;
    }
    let mut path = root.join(kind).join(&id[0..2]);
    path.push(format!("{}{suffix}", &id[2..]));
    Some(path)
}

fn audio_path(root: &Path, id: &str) -> Option<PathBuf> {
    shard(root, "audio", id, "")
}

fn meta_path(root: &Path, id: &str) -> Option<PathBuf> {
    shard(root, "meta", id, ".json")
}

/// The base-16 track id, which is what everything here is keyed on.
///
/// Not the URI: the same track reached through a different market carries the
/// same id, and the id is also what the audio-key request is made with.
fn track_key(uri: &SpotifyUri) -> EngineResult<String> {
    let id: SpotifyId = uri.try_into().map_err(|_| "not a playable uri".to_string())?;
    id.to_base16().map_err(|e| format!("bad track id: {e}"))
}

fn hex_to_bytes(hex: &str) -> Option<Vec<u8>> {
    if hex.len() % 2 != 0 {
        return None;
    }
    (0..hex.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).ok())
        .collect()
}

fn bytes_to_hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// The name a format is stored under, and back again.
///
/// Spelled out rather than stored as the protobuf number, because the sidecar
/// outlives this build of the app: a number that shifted meaning in a later
/// version of the protocol would quietly make old downloads play as something
/// else, while a name that is no longer recognised simply fails the lookup and
/// the track streams as it always did.
fn format_name(format: AudioFileFormat) -> &'static str {
    match format {
        AudioFileFormat::OGG_VORBIS_96 => "OGG_VORBIS_96",
        AudioFileFormat::OGG_VORBIS_160 => "OGG_VORBIS_160",
        AudioFileFormat::OGG_VORBIS_320 => "OGG_VORBIS_320",
        AudioFileFormat::MP3_96 => "MP3_96",
        AudioFileFormat::MP3_160 => "MP3_160",
        AudioFileFormat::MP3_160_ENC => "MP3_160_ENC",
        AudioFileFormat::MP3_256 => "MP3_256",
        AudioFileFormat::MP3_320 => "MP3_320",
        AudioFileFormat::AAC_24 => "AAC_24",
        AudioFileFormat::AAC_48 => "AAC_48",
        AudioFileFormat::AAC_160 => "AAC_160",
        AudioFileFormat::AAC_320 => "AAC_320",
        AudioFileFormat::MP4_128 => "MP4_128",
        AudioFileFormat::FLAC_FLAC => "FLAC_FLAC",
        AudioFileFormat::FLAC_FLAC_24BIT => "FLAC_FLAC_24BIT",
        AudioFileFormat::XHE_AAC_12 => "XHE_AAC_12",
        AudioFileFormat::XHE_AAC_16 => "XHE_AAC_16",
        AudioFileFormat::XHE_AAC_24 => "XHE_AAC_24",
        AudioFileFormat::OTHER5 => "OTHER5",
    }
}

fn format_from_name(name: &str) -> Option<AudioFileFormat> {
    Some(match name {
        "OGG_VORBIS_96" => AudioFileFormat::OGG_VORBIS_96,
        "OGG_VORBIS_160" => AudioFileFormat::OGG_VORBIS_160,
        "OGG_VORBIS_320" => AudioFileFormat::OGG_VORBIS_320,
        "MP3_96" => AudioFileFormat::MP3_96,
        "MP3_160" => AudioFileFormat::MP3_160,
        "MP3_160_ENC" => AudioFileFormat::MP3_160_ENC,
        "MP3_256" => AudioFileFormat::MP3_256,
        "MP3_320" => AudioFileFormat::MP3_320,
        "AAC_24" => AudioFileFormat::AAC_24,
        "AAC_48" => AudioFileFormat::AAC_48,
        "AAC_160" => AudioFileFormat::AAC_160,
        "AAC_320" => AudioFileFormat::AAC_320,
        "MP4_128" => AudioFileFormat::MP4_128,
        "FLAC_FLAC" => AudioFileFormat::FLAC_FLAC,
        "FLAC_FLAC_24BIT" => AudioFileFormat::FLAC_FLAC_24BIT,
        "XHE_AAC_12" => AudioFileFormat::XHE_AAC_12,
        "XHE_AAC_16" => AudioFileFormat::XHE_AAC_16,
        "XHE_AAC_24" => AudioFileFormat::XHE_AAC_24,
        "OTHER5" => AudioFileFormat::OTHER5,
        _ => return None,
    })
}

/// Roughly what a format is worth, so a download can be compared with the copy
/// already on disk without the caller knowing anything about codecs.
///
/// Only the ordering matters. A track already downloaded at 320 is not fetched
/// again because some playlist asked for it at 160.
fn format_rank(format: AudioFileFormat) -> u32 {
    match format {
        AudioFileFormat::XHE_AAC_12 => 12,
        AudioFileFormat::XHE_AAC_16 => 16,
        AudioFileFormat::AAC_24 | AudioFileFormat::XHE_AAC_24 => 24,
        AudioFileFormat::AAC_48 => 48,
        AudioFileFormat::OGG_VORBIS_96 | AudioFileFormat::MP3_96 => 96,
        AudioFileFormat::MP4_128 => 128,
        AudioFileFormat::OGG_VORBIS_160
        | AudioFileFormat::MP3_160
        | AudioFileFormat::MP3_160_ENC
        | AudioFileFormat::AAC_160 => 160,
        AudioFileFormat::MP3_256 => 256,
        AudioFileFormat::OGG_VORBIS_320
        | AudioFileFormat::MP3_320
        | AudioFileFormat::AAC_320
        | AudioFileFormat::OTHER5 => 320,
        AudioFileFormat::FLAC_FLAC => 1000,
        AudioFileFormat::FLAC_FLAC_24BIT => 1100,
    }
}

/// Formats in the order this download would like them.
///
/// The same shape as the table in `player.rs`, and deliberately so: a
/// downloaded track should be the track that would have been streamed, not a
/// different master. Ogg leads at every rung, because it is what carries
/// Spotify's normalisation packet.
fn preferred_formats(kbps: i32) -> [AudioFileFormat; 7] {
    match kbps {
        k if k <= 96 => [
            AudioFileFormat::OGG_VORBIS_96,
            AudioFileFormat::MP3_96,
            AudioFileFormat::OGG_VORBIS_160,
            AudioFileFormat::MP3_160,
            AudioFileFormat::MP3_256,
            AudioFileFormat::OGG_VORBIS_320,
            AudioFileFormat::MP3_320,
        ],
        k if k <= 160 => [
            AudioFileFormat::OGG_VORBIS_160,
            AudioFileFormat::MP3_160,
            AudioFileFormat::OGG_VORBIS_96,
            AudioFileFormat::MP3_96,
            AudioFileFormat::MP3_256,
            AudioFileFormat::OGG_VORBIS_320,
            AudioFileFormat::MP3_320,
        ],
        _ => [
            AudioFileFormat::OGG_VORBIS_320,
            AudioFileFormat::MP3_320,
            AudioFileFormat::MP3_256,
            AudioFileFormat::OGG_VORBIS_160,
            AudioFileFormat::MP3_160,
            AudioFileFormat::OGG_VORBIS_96,
            AudioFileFormat::MP3_96,
        ],
    }
}

// ---------------------------------------------------------------- the sidecar

fn read_sidecar(path: &Path) -> Option<Value> {
    serde_json::from_str(&fs::read_to_string(path).ok()?).ok()
}

/// Writes the sidecar through a temporary file, so it is either the old one or
/// the new one and never half of either.
fn write_sidecar(path: &Path, value: &Value) -> Result<(), String> {
    let parent = path.parent().ok_or("sidecar has no directory")?;
    fs::create_dir_all(parent).map_err(|e| format!("sidecar dir failed: {e}"))?;
    let tmp = path.with_extension("json.tmp");
    fs::write(&tmp, value.to_string().as_bytes())
        .map_err(|e| format!("sidecar write failed: {e}"))?;
    fs::rename(&tmp, path).map_err(|e| format!("sidecar rename failed: {e}"))
}

/// What the player needs to play a downloaded track, or `None` if there is not
/// a complete download for it.
///
/// Deliberately reads from disk on every call. See `DownloadLookup` in
/// `librespot-playback`'s `config.rs` for why a cached map would be worse.
fn lookup_track(track_id: &SpotifyId) -> Option<DownloadedTrack> {
    let root = root()?;
    let id = track_id.to_base16().ok()?;
    let meta = meta_path(&root, &id)?;
    let path = audio_path(&root, &id)?;

    let value = read_sidecar(&meta)?;
    let format = format_from_name(value.get("format")?.as_str()?)?;

    let key = value
        .get("key")
        .and_then(Value::as_str)
        .and_then(hex_to_bytes)
        .and_then(|bytes| <[u8; 16]>::try_from(bytes.as_slice()).ok());

    let strings = |field: &str| -> Vec<String> {
        value
            .get(field)
            .and_then(Value::as_array)
            .map(|list| {
                list.iter()
                    .filter_map(Value::as_str)
                    .map(str::to_string)
                    .collect()
            })
            .unwrap_or_default()
    };

    Some(DownloadedTrack {
        path,
        key,
        format,
        name: value
            .get("name")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string(),
        duration_ms: value.get("durationMs").and_then(Value::as_u64)? as u32,
        is_explicit: value
            .get("isExplicit")
            .and_then(Value::as_bool)
            .unwrap_or(false),
        album: value
            .get("album")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string(),
        album_artists: strings("albumArtists"),
        number: value.get("number").and_then(Value::as_u64).unwrap_or(0) as u32,
        disc_number: value.get("discNumber").and_then(Value::as_u64).unwrap_or(0) as u32,
    })
}

/// Whether there is anything downloaded at all.
///
/// The question the engine asks before deciding that a failed handshake is
/// worth carrying on through. Stops at the first sidecar it finds rather than
/// counting: a library of five thousand songs and one are the same answer.
pub fn any() -> bool {
    let Some(root) = root() else { return false };
    let Ok(shards) = fs::read_dir(root.join("meta")) else {
        return false;
    };
    for shard in shards.flatten() {
        if let Ok(entries) = fs::read_dir(shard.path()) {
            if entries.flatten().next().is_some() {
                return true;
            }
        }
    }
    false
}

/// The lookup handed to `PlayerConfig` when the engine is built.
pub fn lookup() -> DownloadLookup {
    Arc::new(lookup_track)
}

// -------------------------------------------------------------- what is there

/// The sidecar for a track as JSON, or `"null"` when it is not downloaded.
pub fn stored(uri: &str) -> EngineResult<String> {
    let parsed = SpotifyUri::from_uri(uri).map_err(|e| format!("bad uri {uri}: {e}"))?;
    let id = track_key(&parsed)?;
    let Some(root) = root() else {
        return Ok("null".to_string());
    };
    let Some(meta) = meta_path(&root, &id) else {
        return Ok("null".to_string());
    };
    match read_sidecar(&meta) {
        Some(value) => Ok(value.to_string()),
        None => Ok("null".to_string()),
    }
}

/// Removes a download. Missing is not an error: the caller is asking for the
/// track to be gone, and it is.
pub fn remove_track(uri: &str) -> EngineResult<()> {
    let parsed = SpotifyUri::from_uri(uri).map_err(|e| format!("bad uri {uri}: {e}"))?;
    let id = track_key(&parsed)?;
    let Some(root) = root() else { return Ok(()) };

    // The sidecar goes first. It is what the player looks for, so removing it
    // first means a load happening at this moment falls back to streaming
    // rather than opening a file that is about to disappear underneath it.
    if let Some(meta) = meta_path(&root, &id) {
        let _ = fs::remove_file(meta);
    }
    if let Some(audio) = audio_path(&root, &id) {
        let _ = fs::remove_file(&audio);
        let _ = fs::remove_file(audio.with_extension("part"));
    }
    Ok(())
}

fn cancelled(id: &str) -> bool {
    CANCELLED
        .lock()
        .ok()
        .and_then(|guard| guard.as_ref().map(|set| set.contains(id)))
        .unwrap_or(false)
}

fn clear_cancel(id: &str) {
    if let Ok(mut guard) = CANCELLED.lock() {
        if let Some(set) = guard.as_mut() {
            set.remove(id);
        }
    }
}

/// Asks a download in progress to stop at the end of the current chunk.
pub fn cancel(uri: &str) -> EngineResult<()> {
    let parsed = SpotifyUri::from_uri(uri).map_err(|e| format!("bad uri {uri}: {e}"))?;
    let id = track_key(&parsed)?;
    let mut guard = CANCELLED.lock().map_err(|_| "cancel set poisoned")?;
    guard.get_or_insert_with(HashSet::new).insert(id);
    Ok(())
}

// ------------------------------------------------------------------ the fetch

/// One ranged request against the CDN: the bytes, and the size of the whole
/// file as the response reports it.
async fn get_range(
    session: &Session,
    url: &str,
    offset: usize,
    length: usize,
) -> Result<(bytes::Bytes, usize), String> {
    let request = Request::builder()
        .method(&Method::GET)
        .uri(url)
        .header(RANGE, format!("bytes={}-{}", offset, offset + length - 1))
        .body(bytes::Bytes::new())
        .map_err(|e| format!("bad range request: {e}"))?;

    let response = tokio::time::timeout(CHUNK_TIMEOUT, session.http_client().request(request))
        .await
        .map_err(|_| "the CDN did not answer in time".to_string())?
        .map_err(|e| format!("CDN request failed: {e}"))?;

    let status = response.status();
    if status != StatusCode::PARTIAL_CONTENT {
        return Err(format!("CDN answered {status}, expected partial content"));
    }

    // `bytes 0-524287/8912345`: the part after the slash is the only place the
    // size of the whole file is stated, which is why this asks for a range even
    // for a file it intends to read in full.
    let range = response
        .headers()
        .get(http::header::CONTENT_RANGE)
        .and_then(|value| value.to_str().ok())
        .ok_or("the CDN did not say how big the file is")?;
    let total: usize = range
        .rsplit('/')
        .next()
        .and_then(|size| size.parse().ok())
        .ok_or_else(|| format!("could not read the file size out of {range:?}"))?;

    let body = response
        .into_body()
        .collect()
        .await
        .map_err(|e| format!("CDN body failed: {e}"))?
        .to_bytes();

    Ok((body, total))
}

/// The track to actually download, following Spotify's relinking if the one
/// asked for is not the one this account can play.
///
/// The same walk `player.rs` does before streaming. Without it, the tracks that
/// are silently swapped on playback would be the tracks that refuse to
/// download, which is a difference the listener would have no way to explain.
async fn resolve_item(session: &Session, uri: SpotifyUri) -> Result<AudioItem, String> {
    let item = AudioItem::get_file(session, uri)
        .await
        .map_err(|e| format!("metadata failed: {e}"))?;

    if item.availability.is_err() {
        return Err("this track is not available on this account".to_string());
    }
    if !item.files.is_empty() {
        return Ok(item);
    }

    let Some(librespot_metadata::track::Tracks(alternatives)) = item.alternatives.clone() else {
        return Err("the track has no playable file".to_string());
    };

    for alternative in alternatives {
        if let Ok(other) = AudioItem::get_file(session, alternative).await {
            if other.availability.is_ok() && !other.files.is_empty() {
                return Ok(other);
            }
        }
    }

    Err("no alternative of this track can be played".to_string())
}

/// Downloads one track, resuming a part-finished one, and answers with the
/// sidecar it wrote.
///
/// Blocking: this is called from the Kotlin download queue's own worker thread,
/// which knows nothing about tokio. The runtime handle is taken before
/// blocking, exactly as the catalogue lookups do, so a download in progress
/// never holds up play or pause.
pub fn download_track(uri: &str, kbps: i32) -> EngineResult<String> {
    let session = engine::with_session(|session| session.clone())?;
    let uri = uri.to_string();
    engine::runtime_handle()?.block_on(fetch(session, uri, kbps))
}

async fn fetch(session: Session, uri_text: String, kbps: i32) -> Result<String, String> {
    let uri = SpotifyUri::from_uri(&uri_text).map_err(|e| format!("bad uri {uri_text}: {e}"))?;
    let id = track_key(&uri)?;
    let root = root().ok_or("downloads have no home yet")?;
    let audio = audio_path(&root, &id).ok_or("bad download path")?;
    let meta = meta_path(&root, &id).ok_or("bad download path")?;

    clear_cancel(&id);

    let wanted = format_rank(preferred_formats(kbps)[0]);

    // Already here, and no worse than what was asked for. Answering with the
    // sidecar rather than an error is what makes the whole feature idempotent:
    // the queue can re-add a playlist, or two playlists can share a track, and
    // neither costs anything.
    if let Some(existing) = read_sidecar(&meta) {
        let good = existing
            .get("format")
            .and_then(Value::as_str)
            .and_then(format_from_name)
            .map(|format| format_rank(format) >= wanted)
            .unwrap_or(false);
        if good && audio.exists() {
            return Ok(existing.to_string());
        }
    }

    let item = resolve_item(&session, uri.clone()).await?;
    let (format, file_id) = preferred_formats(kbps)
        .iter()
        .find_map(|format| item.files.get(format).map(|file| (*format, *file)))
        .ok_or("this track exists in no format the app can play")?;

    let track_id: SpotifyId = (&uri)
        .try_into()
        .map_err(|_| "not a playable uri".to_string())?;
    // Spotify hands out one AES key per file, and it refuses them when it
    // decides a session is asking too fast. That is a fact about the pace of
    // the queue, not about this track — librespot has already waited and tried
    // again by the time the error arrives here — so it is reported as its own
    // thing and the queue answers by slowing down rather than by giving up on
    // the song. See DownloadQueue, which treats this as a reason to wait.
    // Timed, and reported in the sidecar. Spotify refuses keys when a session
    // asks for them faster than it likes, and a refusal costs the whole cool-off
    // — three of them in a row is half a minute of a download queue doing
    // nothing. The queue cannot see any of that from here, so this is how it
    // finds out that it is going too fast; see DownloadQueue's own pacing.
    // Playback goes first, always.
    //
    // Spotify allows a session so many audio keys, and a refusal puts a hold on
    // the whole session — so a download asking at the wrong moment does not
    // merely wait its turn, it stops the next track from starting and keeps the
    // player waiting through the same backoff. Measured while downloading a
    // playlist: every track loaded for playback was followed by three refusals,
    // twenty-one seconds of holds, and a queue that crawled.
    //
    // So a download waits until playback has been quiet for a while. The
    // listener never waits for a download; a download always waits for the
    // listener.
    loop {
        if cancelled(&id) {
            clear_cancel(&id);
            return Err("cancelled".to_string());
        }
        let quiet = librespot_core::audio_key::since_playback_request()
            .map(|since| since >= PLAYBACK_PRIORITY)
            .unwrap_or(true);
        if quiet {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(500)).await;
    }

    let key_began = std::time::Instant::now();
    let audio_key = match session.audio_key().request(track_id, file_id).await {
        Ok(key) => key,
        Err(e) => {
            let refused = matches!(
                e.error
                    .downcast_ref::<librespot_core::audio_key::AudioKeyError>(),
                Some(librespot_core::audio_key::AudioKeyError::AesKey)
                    | Some(librespot_core::audio_key::AudioKeyError::Timeout)
            );
            return Err(if refused {
                format!("{KEY_THROTTLED}: {e}")
            } else {
                format!("no key for this track: {e}")
            });
        }
    };
    // Timed here rather than at the end of the download, which is what it used
    // to be: the number reported was the key wait plus the whole transfer, so
    // every track looked to the app like a track the key limiter had held up.
    // The queue reads it to pace itself and backed off on all of them, out to
    // its longest gap, where it stayed. See DownloadQueue.noteKeyWait.
    let key_wait_ms = key_began.elapsed().as_millis() as u64;

    let part = audio.with_extension("part");
    if let Some(parent) = part.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("download dir failed: {e}"))?;
    }
    let mut done = fs::metadata(&part).map(|meta| meta.len()).unwrap_or(0) as usize;
    let mut file = fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&part)
        .map_err(|e| format!("could not open the download: {e}"))?;

    let mut cdn = CdnUrl::new(file_id)
        .resolve_audio(&session)
        .await
        .map_err(|e| format!("could not find the file: {e}"))?;

    let mut total = 0usize;
    loop {
        if cancelled(&id) {
            clear_cancel(&id);
            return Err("cancelled".to_string());
        }
        if total > 0 && done >= total {
            break;
        }

        let mut attempt = 0;
        let chunk = loop {
            // Spotify hands out several mirrors for a file. Every one of them
            // is tried before concluding the problem is the URLs rather than
            // the mirror, which is what librespot's own opener does.
            let urls: Vec<String> = cdn
                .try_get_urls()
                .map_err(|e| format!("no CDN url: {e}"))?
                .into_iter()
                .map(str::to_string)
                .collect();

            let mut last = "the CDN offered no url".to_string();
            let mut got = None;
            for url in &urls {
                match get_range(&session, url, done, CHUNK).await {
                    Ok(chunk) => {
                        got = Some(chunk);
                        break;
                    }
                    Err(e) => last = e,
                }
            }
            if let Some(chunk) = got {
                break chunk;
            }

            attempt += 1;
            if attempt >= CHUNK_ATTEMPTS {
                return Err(last);
            }
            log::warn!("download of <{uri_text}> stumbled at {done}: {last}; asking again");
            // A fresh set of URLs, not a fresh wait: a signed URL that expired
            // while the queue worked through a long playlist is the common
            // failure here, and sleeping does not fix it.
            cdn = CdnUrl::new(file_id)
                .resolve_audio(&session)
                .await
                .map_err(|e| format!("could not find the file again: {e}"))?;
        };

        let (bytes, size) = chunk;
        total = size;
        if bytes.is_empty() {
            break;
        }
        file.write_all(&bytes)
            .map_err(|e| format!("could not write the download: {e}"))?;
        done += bytes.len();

        // Per mille rather than bytes: the one number the event carries has to
        // be enough to draw a ring, and the size is not known on the Kotlin
        // side until the sidecar lands.
        let permille = if total > 0 {
            (done as u64 * 1000 / total as u64) as i64
        } else {
            0
        };
        engine::emit_app("download_progress", &uri_text, permille.min(1000));
    }

    file.flush()
        .map_err(|e| format!("could not finish the download: {e}"))?;
    drop(file);

    if done < total {
        return Err(format!("the download stopped short: {done} of {total} bytes"));
    }

    fs::rename(&part, &audio).map_err(|e| format!("could not store the download: {e}"))?;

    // The same bytes may well be sitting in the 512 MB playback cache from the
    // last time this track was heard. Keeping both is paying twice for one
    // song, and the download is the copy that is meant to survive.
    if let Some(cache) = session.cache() {
        let _ = cache.remove_file(file_id);
    }

    let (album, album_artists, artists, number, disc_number) = match item.unique_fields {
        UniqueFields::Track {
            album,
            album_artists,
            artists,
            number,
            disc_number,
            ..
        } => {
            let artists = artists
                .0
                .into_iter()
                .map(|artist| {
                    json!({
                        "name": artist.name,
                        "uri": artist.id.to_uri().unwrap_or_default(),
                    })
                })
                .collect::<Vec<_>>();
            (album, album_artists, artists, number, disc_number)
        }
        _ => (String::new(), Vec::new(), Vec::new(), 0, 0),
    };

    // The biggest cover, kept as a URL. Offline the app reads the copy it
    // saved beside the track; this is what tells it which one that was, and
    // what it falls back to the moment there is a connection again.
    let cover = item
        .covers
        .iter()
        .max_by_key(|cover| cover.width.max(cover.height))
        .map(|cover| cover.url.clone())
        .unwrap_or_default();

    let sidecar = json!({
        "v": 1,
        "keyWaitMs": key_wait_ms,
        "uri": uri_text,
        "trackId": id,
        "fileId": file_id.to_base16().unwrap_or_default(),
        "format": format_name(format),
        "bytes": done,
        "key": bytes_to_hex(&audio_key.0),
        "name": item.name,
        "durationMs": item.duration_ms,
        "isExplicit": item.is_explicit,
        "artists": artists,
        "album": album,
        "albumArtists": album_artists,
        "number": number,
        "discNumber": disc_number,
        "coverUrl": cover,
        "downloadedAt": now_ms(),
    });

    write_sidecar(&meta, &sidecar)?;

    log::info!(
        "downloaded <{}> as {} ({} bytes)",
        item.name,
        format_name(format),
        done
    );
    engine::emit_app("download_done", &uri_text, done as i64);

    Ok(sidecar.to_string())
}

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|since| since.as_millis() as u64)
        .unwrap_or(0)
}
