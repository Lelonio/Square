//! Spotify's DJ: a session of tracks, and the lines it says around them.
//!
//! The DJ is listed among the account's playlists, but asked for as one it has
//! no tracks: what it plays comes from the DJ's own session service, a page at
//! a time, each page naming the next. Some tracks carry a line to say before
//! them and some a line to say after, written out as SSML in the track's
//! metadata, with the voice and the synthesiser to read it with. Spotify reads
//! it on request — a POST to `client-tts`, answered with a redirect to an MP3 —
//! and the official client plays that around the track, one after the other.
//!
//! This is the same, worked out from go-librespot's DJ support and from what
//! Spotify answered this client: the session is asked for here rather than
//! handed over by another device, which Spotify allows. The lines are fetched
//! and decoded ahead, as soon as a page arrives, because synthesising one takes
//! seconds; the player asks for them at the moment a track starts and ends
//! (see `NarrationMoment` in the vendored player), and a line that is not ready
//! by then is not said. The music never waits for the DJ.
//!
//! The language the DJ speaks is the client's to say: without a platform and a
//! version, Spotify answers in English whatever the account's language is.

use crate::engine;
use bytes::Bytes;
use http::{header::HeaderValue, Method, Request};
use librespot_core::Session;
use librespot_playback::player::{self, NarrationMoment};
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex, Once};
use std::time::{Duration, Instant};

/// The DJ, as the account's library lists it; the same for every account.
const DJ_URI: &str = "spotify:playlist:37i9dQZF1EYkqdzj48dyYq";

const SPCLIENT: &str = "https://spclient.wg.spotify.com";

/// What the lines are synthesised at: the player's own rate, so nothing has to
/// be resampled on the way.
const LINE_SAMPLE_RATE: u32 = 44_100;

/// The loudness every line is brought to, Spotify's normalisation target.
const TARGET_LUFS: f64 = -14.0;

/// How long one line may take to be synthesised and fetched.
const LINE_TIMEOUT: Duration = Duration::from_secs(20);

/// The lines the DJ has for a track, as its metadata gives them.
#[derive(Clone)]
struct Line {
    ssml: String,
    voice: i32,
    provider: i32,
    loudness: Option<f64>,
    true_peak: Option<f64>,
}

#[derive(Clone)]
enum Clip {
    Pending,
    Ready(Arc<Vec<f64>>),
    Failed,
}

/// Lines by track and kind (`intro`, `jump`, `outro`), as they are fetched.
static CLIPS: Mutex<Option<HashMap<(String, &'static str), Clip>>> = Mutex::new(None);

/// Tracks the listener went straight to: introduced with the jump line, which
/// is written for arriving out of order, rather than the intro.
static JUMPED: Mutex<Option<HashSet<String>>> = Mutex::new(None);

/// Every line this session has registered, so a page that repeats the
/// session's history (they all do) does not have them synthesised again, or
/// a line already said said again.
static REGISTERED: Mutex<Option<HashSet<(String, &'static str)>>> = Mutex::new(None);

/// The session's tracks in its order, for what follows a track; see `follows`.
static ORDER: Mutex<Vec<String>> = Mutex::new(Vec::new());

/// The lines each track of the session has, as its pages gave them, waiting
/// for the app to say which tracks it will play; see [`prepare`].
static LINES: Mutex<Option<HashMap<String, Vec<(&'static str, Line)>>>> = Mutex::new(None);

/// The track the player last started, whose closing line, if still unsaid, is
/// what a skip would otherwise lose; see [`install`].
static LAST_STARTED: Mutex<Option<String>> = Mutex::new(None);

static INSTALL: Once = Once::new();

/// Gives the player where to find the lines. Once, the first time a session
/// is turned on.
fn install() {
    INSTALL.call_once(|| {
        player::set_narration_provider(Box::new(|uri, moment| match moment {
            NarrationMoment::Before => {
                let previous = LAST_STARTED
                    .lock()
                    .ok()
                    .and_then(|mut last| last.replace(uri.to_string()));
                let jumped = JUMPED
                    .lock()
                    .ok()
                    .and_then(|mut set| set.as_mut().map(|set| set.remove(uri)))
                    .unwrap_or(false);
                let own = if jumped {
                    take(uri, "jump").or_else(|| take(uri, "intro"))
                } else {
                    take(uri, "intro")
                };
                // A track skipped before its end never says its closing line,
                // and that line is the DJ talking between two songs: said
                // here instead, before the one skipped to, unless that one has
                // something of its own to say.
                own.or_else(|| previous.filter(|previous| previous != uri).and_then(|previous| take(&previous, "outro")))
            }
            NarrationMoment::After => take(uri, "outro"),
        }));
        player::set_narration_follows(Box::new(follows));
    });
}

/// Whether [uri] ends in a line: its own closing one, or the opening one of
/// the track the session plays after it.
fn follows(uri: &str) -> bool {
    let next = ORDER.lock().ok().and_then(|order| {
        order.iter().position(|it| it == uri).and_then(|at| order.get(at + 1).cloned())
    });
    let Ok(clips) = CLIPS.lock() else { return false };
    let Some(clips) = clips.as_ref() else { return false };
    let waiting = |uri: &str, kind: &'static str| {
        matches!(clips.get(&(uri.to_string(), kind)), Some(Clip::Pending | Clip::Ready(_)))
    };
    waiting(uri, "outro") || next.is_some_and(|next| waiting(&next, "intro"))
}

/// A line ready to be said, taken so it is said once. One still being fetched
/// is left: the music does not wait for it.
fn take(uri: &str, kind: &'static str) -> Option<Arc<Vec<f64>>> {
    let mut clips = CLIPS.lock().ok()?;
    let clips = clips.as_mut()?;
    match clips.get(&(uri.to_string(), kind)) {
        Some(Clip::Ready(samples)) => {
            let samples = samples.clone();
            clips.remove(&(uri.to_string(), kind));
            log::info!("dj: saying the {kind} of {uri}");
            Some(samples)
        }
        Some(Clip::Pending) => {
            log::info!("dj: the {kind} of {uri} is not ready, playing without it");
            None
        }
        _ => None,
    }
}

/// Turns a DJ session on, or off with everything it had fetched.
pub fn set_active(on: bool) {
    install();
    player::set_narrating(on);
    if !on {
        if let Ok(mut clips) = CLIPS.lock() {
            *clips = None;
        }
        if let Ok(mut jumped) = JUMPED.lock() {
            *jumped = None;
        }
        if let Ok(mut last) = LAST_STARTED.lock() {
            *last = None;
        }
        if let Ok(mut registered) = REGISTERED.lock() {
            *registered = None;
        }
        if let Ok(mut lines) = LINES.lock() {
            *lines = None;
        }
        if let Ok(mut order) = ORDER.lock() {
            order.clear();
        }
    }
}

/// Says that [uri] is being gone to directly; see [`JUMPED`].
pub fn jumped(uri: &str) {
    if let Ok(mut jumped) = JUMPED.lock() {
        jumped.get_or_insert_with(HashSet::new).insert(uri.to_string());
    }
}

/// Waits for the line that opens [uri] to be ready, or to fail, up to
/// [timeout_ms]. For the first track, which would otherwise always start
/// before its introduction had been synthesised.
pub fn await_opening(uri: &str, timeout_ms: u64) -> bool {
    let deadline = Instant::now() + Duration::from_millis(timeout_ms);
    loop {
        let state = CLIPS.lock().ok().and_then(|clips| {
            clips
                .as_ref()
                .and_then(|clips| clips.get(&(uri.to_string(), "intro")).cloned())
        });
        match state {
            Some(Clip::Ready(_)) => return true,
            Some(Clip::Pending) if Instant::now() < deadline => {
                std::thread::sleep(Duration::from_millis(100))
            }
            _ => return false,
        }
    }
}

/// One page of the DJ's session: `{"tracks": [...], "next": "hm://..."}`.
///
/// [page] is the `next` of the page before, or empty for the session itself,
/// which answers with its whole history and a few new tracks at the end. The
/// lines are read, not fetched: the app says which tracks it will play, and
/// [`prepare`] has theirs made.
pub fn session(page: &str, language: &str, app_version: &str) -> engine::EngineResult<String> {
    let handle = engine::runtime_handle()?;
    let session = engine::with_session(|session| session.clone())?;
    let path = if page.is_empty() {
        format!("/lexicon-session-provider/context-resolve/v2/session?contextUri={DJ_URI}")
    } else {
        let rest = page
            .strip_prefix("hm://")
            .ok_or_else(|| format!("not a session page: {page}"))?;
        format!("/{rest}")
    };
    let language = if language.is_empty() { "en" } else { language }.to_string();
    let app_version = app_version.to_string();

    let body = handle.block_on({
        let session = session.clone();
        async move {
            let request = authorised(&session, Method::GET, &format!("{SPCLIENT}{path}"), Bytes::new(), &language, &app_version).await?;
            session
                .http_client()
                .request_body(request)
                .await
                .map_err(|e| format!("dj session failed: {e}"))
        }
    })?;
    let context: serde_json::Value =
        serde_json::from_slice(&body).map_err(|e| format!("unreadable dj session: {e}"))?;

    let mut tracks = Vec::new();
    let mut next = String::new();
    let mut lines = Vec::new();
    // The session answers with a context of pages; a page asked for by its
    // own address answers as that one page, its tracks at the top.
    let pages: Vec<&serde_json::Value> = match context["pages"].as_array() {
        Some(pages) => pages.iter().collect(),
        None => vec![&context],
    };
    for page in pages {
        if let Some(url) = page["next_page_url"].as_str().filter(|url| !url.is_empty()) {
            next = url.to_string();
        }
        for track in page["tracks"].as_array().into_iter().flatten() {
            let Some(uri) = track["uri"].as_str().filter(|uri| uri.starts_with("spotify:track:")) else {
                continue;
            };
            tracks.push(uri.to_string());
            let metadata = &track["metadata"];
            for kind in ["intro", "jump", "outro"] {
                if let Some(line) = line_in(metadata, kind) {
                    lines.push((uri.to_string(), kind, line));
                }
            }
        }
    }
    log::info!(
        "dj: session of {} tracks with {} lines, next page {}",
        tracks.len(),
        lines.len(),
        if next.is_empty() { "none" } else { "named" }
    );
    if let Ok(mut order) = ORDER.lock() {
        if page.is_empty() {
            *order = tracks.clone();
        } else {
            order.extend(tracks.iter().cloned());
        }
    }
    if let Ok(mut known) = LINES.lock() {
        let known = known.get_or_insert_with(HashMap::new);
        for (uri, kind, line) in lines {
            let entry = known.entry(uri).or_default();
            if !entry.iter().any(|(k, _)| *k == kind) {
                entry.push((kind, line));
            }
        }
    }

    // The DJ's own colours, for what is shown while it talks.
    let colours = &context["metadata"];
    let colour = |key: &str| colours[key].as_str().unwrap_or_default().to_string();
    Ok(serde_json::json!({
        "tracks": tracks,
        "next": next,
        "colors": {
            "top": colour("narration.jellyfish.top.color"),
            "mid": colour("narration.jellyfish.mid.color"),
            "bottom": colour("narration.jellyfish.bottom.color"),
            "background": colour("narration.background.color"),
        },
    })
    .to_string())
}

/// Has the lines of [uris] synthesised, in that order, each once a session:
/// the tracks the app is about to queue. Every page repeats the session's
/// whole history, and synthesising all of its lines each time cost seconds of
/// the service's time for lines nobody would hear.
pub fn prepare(uris: &[String]) -> engine::EngineResult<()> {
    let handle = engine::runtime_handle()?;
    let session = engine::with_session(|session| session.clone())?;
    let mut lines = Vec::new();
    {
        let known = LINES.lock().map_err(|_| "dj lines unavailable".to_string())?;
        let mut registered = REGISTERED.lock().map_err(|_| "dj lines unavailable".to_string())?;
        let registered = registered.get_or_insert_with(HashSet::new);
        for uri in uris {
            for (kind, line) in known.as_ref().and_then(|known| known.get(uri)).into_iter().flatten() {
                if registered.insert((uri.clone(), *kind)) {
                    lines.push((uri.clone(), *kind, line.clone()));
                }
            }
        }
    }
    if lines.is_empty() {
        return Ok(());
    }
    log::info!("dj: preparing {} lines", lines.len());
    if let Ok(mut clips) = CLIPS.lock() {
        let clips = clips.get_or_insert_with(HashMap::new);
        for (uri, kind, _) in &lines {
            clips.insert((uri.clone(), *kind), Clip::Pending);
        }
    }
    // One after another, in the order they will be needed: they share one
    // synthesiser, and the first track's opening is the one being waited for.
    handle.spawn(async move {
        for (uri, kind, line) in lines {
            let fetched = tokio::time::timeout(LINE_TIMEOUT, fetch_line(&session, &line)).await;
            let clip = match fetched {
                Ok(Ok(samples)) => Clip::Ready(Arc::new(samples)),
                Ok(Err(e)) => {
                    log::warn!("dj: the {kind} of {uri} could not be fetched: {e}");
                    Clip::Failed
                }
                Err(_) => {
                    log::warn!("dj: the {kind} of {uri} took too long");
                    Clip::Failed
                }
            };
            if let Ok(mut clips) = CLIPS.lock() {
                // Only into a session still on: one turned off meanwhile
                // has no use for it.
                if let Some(clips) = clips.as_mut() {
                    clips.insert((uri, kind), clip);
                }
            }
        }
    });
    Ok(())
}

/// The line of [kind] in a track's metadata, if it has something to say.
fn line_in(metadata: &serde_json::Value, kind: &str) -> Option<Line> {
    let field = |name: &str| metadata[format!("narration.{kind}.{name}")].as_str();
    let ssml = field("ssml").filter(|ssml| !ssml.is_empty())?.to_string();
    Some(Line {
        ssml,
        voice: field("voice")
            .and_then(|voice| voice.strip_prefix("VOICE"))
            .and_then(|n| n.parse().ok())
            .unwrap_or(1),
        provider: field("tts_provider").map(provider_number).unwrap_or(6),
        loudness: field("loudness").and_then(|v| v.parse().ok()),
        true_peak: field("true_peak").and_then(|v| v.parse().ok()),
    })
}

/// `spotify.narration.ResolveRequest.TtsProvider`, by name.
fn provider_number(name: &str) -> i32 {
    match name {
        "CLOUD_TTS" => 1,
        "READSPEAKER" => 2,
        "POLLY" => 3,
        "WELL_SAID" => 4,
        "SONANTIC_DEPRECATED" => 5,
        "SONANTIC_FAST" => 6,
        "OPENAI" => 7,
        "SONANTIC_LARGE" => 8,
        "ELEVEN_LABS_DEPRECATED" => 9,
        "ADS_PUBLIC" => 10,
        _ => 6,
    }
}

/// A request as the web player would make it, signed in.
async fn authorised(
    session: &Session,
    method: Method,
    url: &str,
    body: Bytes,
    language: &str,
    app_version: &str,
) -> engine::EngineResult<Request<Bytes>> {
    let token = session
        .login5()
        .auth_token()
        .await
        .map_err(|e| format!("no access token: {e}"))?;
    let mut request = Request::builder()
        .method(method)
        .uri(url)
        .body(body)
        .map_err(|e| format!("bad request: {e}"))?;
    let headers = request.headers_mut();
    if let Ok(value) = HeaderValue::from_str(&format!("{} {}", token.token_type, token.access_token)) {
        headers.insert(http::header::AUTHORIZATION, value);
    }
    headers.insert(http::header::ACCEPT, HeaderValue::from_static("application/json"));
    // What makes the DJ speak the account's language; see the note at the top.
    headers.insert("app-platform", HeaderValue::from_static(crate::pathfinder::APP_PLATFORM));
    let version = if app_version.is_empty() { crate::pathfinder::APP_VERSION } else { app_version };
    if let Ok(value) = HeaderValue::from_str(version) {
        headers.insert("spotify-app-version", value);
    }
    if let Ok(value) = HeaderValue::from_str(language) {
        headers.insert(http::header::ACCEPT_LANGUAGE, value);
    }
    Ok(request)
}

/// Has [line] read out, fetches it and decodes it to the player's samples.
async fn fetch_line(session: &Session, line: &Line) -> engine::EngineResult<Vec<f64>> {
    let mut request = authorised(
        session,
        Method::POST,
        &format!("{SPCLIENT}/client-tts/v1/fulfill"),
        Bytes::from(tts_request(line)),
        "",
        "",
    )
    .await?;
    request.headers_mut().insert(
        http::header::CONTENT_TYPE,
        HeaderValue::from_static("application/x-protobuf"),
    );
    // Not through `request`, which takes anything but a success for an error:
    // the answer is a redirect, and the address is all that is wanted of it.
    let response = session
        .http_client()
        .request_fut(request)
        .map_err(|e| format!("tts request failed: {e}"))?
        .await
        .map_err(|e| format!("tts request failed: {e}"))?;
    let status = response.status();
    let location = response
        .headers()
        .get(http::header::LOCATION)
        .and_then(|value| value.to_str().ok())
        .map(str::to_string)
        .ok_or_else(|| format!("tts answered {status} without an address"))?;

    let audio = Request::builder()
        .method(Method::GET)
        .uri(&location)
        .body(Bytes::new())
        .map_err(|e| format!("bad clip address: {e}"))?;
    let mp3 = session
        .http_client()
        .request_body(audio)
        .await
        .map_err(|e| format!("clip download failed: {e}"))?;

    let samples = tokio::task::spawn_blocking(move || decode(mp3.to_vec()))
        .await
        .map_err(|e| format!("decoding stopped: {e}"))??;
    Ok(level(samples, line.loudness, line.true_peak))
}

/// `spotify.narration.TtsRequest`, written by hand: ssml = 2, audio_format = 3
/// (MP3 is 5), tts_voice = 5, tts_provider = 6, sample_rate_hz = 7.
fn tts_request(line: &Line) -> Vec<u8> {
    fn varint(out: &mut Vec<u8>, mut value: u64) {
        while value >= 0x80 {
            out.push((value as u8 & 0x7F) | 0x80);
            value >>= 7;
        }
        out.push(value as u8);
    }
    let mut out = Vec::new();
    let text = line.ssml.as_bytes();
    varint(&mut out, (2 << 3) | 2);
    varint(&mut out, text.len() as u64);
    out.extend_from_slice(text);
    for (field, value) in [(3u64, 5u64), (5, line.voice as u64), (6, line.provider as u64), (7, LINE_SAMPLE_RATE as u64)] {
        varint(&mut out, field << 3);
        varint(&mut out, value);
    }
    out
}

/// An MP3 as interleaved stereo at the player's rate.
fn decode(mp3: Vec<u8>) -> engine::EngineResult<Vec<f64>> {
    use symphonia::core::audio::SampleBuffer;
    use symphonia::core::codecs::DecoderOptions;
    use symphonia::core::formats::FormatOptions;
    use symphonia::core::io::MediaSourceStream;
    use symphonia::core::meta::MetadataOptions;
    use symphonia::core::probe::Hint;

    let stream = MediaSourceStream::new(Box::new(std::io::Cursor::new(mp3)), Default::default());
    let mut hint = Hint::new();
    hint.with_extension("mp3");
    let probed = symphonia::default::get_probe()
        .format(&hint, stream, &FormatOptions::default(), &MetadataOptions::default())
        .map_err(|e| format!("not an mp3: {e}"))?;
    let mut format = probed.format;
    let track = format
        .default_track()
        .ok_or_else(|| "no audio in the clip".to_string())?;
    let track_id = track.id;
    let mut decoder = symphonia::default::get_codecs()
        .make(&track.codec_params, &DecoderOptions::default())
        .map_err(|e| format!("no decoder: {e}"))?;

    let mut rate = LINE_SAMPLE_RATE;
    let mut stereo = Vec::new();
    loop {
        let packet = match format.next_packet() {
            Ok(packet) => packet,
            Err(_) => break,
        };
        if packet.track_id() != track_id {
            continue;
        }
        let decoded = match decoder.decode(&packet) {
            Ok(decoded) => decoded,
            Err(symphonia::core::errors::Error::DecodeError(_)) => continue,
            Err(_) => break,
        };
        let spec = *decoded.spec();
        rate = spec.rate;
        let channels = spec.channels.count().max(1);
        let mut buffer = SampleBuffer::<f32>::new(decoded.capacity() as u64, spec);
        buffer.copy_interleaved_ref(decoded);
        for frame in buffer.samples().chunks(channels) {
            let left = frame[0] as f64;
            let right = if channels > 1 { frame[1] as f64 } else { left };
            stereo.push(left);
            stereo.push(right);
        }
    }
    if stereo.is_empty() {
        return Err("the clip decoded to nothing".into());
    }
    Ok(if rate == LINE_SAMPLE_RATE { stereo } else { resample(&stereo, rate, LINE_SAMPLE_RATE) })
}

/// Linear resampling of interleaved stereo, for a clip that came back at
/// another rate than the one asked for. Speech survives it.
fn resample(stereo: &[f64], from: u32, to: u32) -> Vec<f64> {
    let frames = stereo.len() / 2;
    let out_frames = (frames as u64 * to as u64 / from as u64) as usize;
    let mut out = Vec::with_capacity(out_frames * 2);
    for i in 0..out_frames {
        let at = i as f64 * from as f64 / to as f64;
        let index = at as usize;
        let next = (index + 1).min(frames - 1);
        let t = at - index as f64;
        for channel in 0..2 {
            let a = stereo[index * 2 + channel];
            let b = stereo[next * 2 + channel];
            out.push(a + (b - a) * t);
        }
    }
    out
}

/// Brings a line to the loudness music plays at, without letting its peaks
/// past full scale. Levels the clip did not come with leave it as rendered.
fn level(mut samples: Vec<f64>, loudness: Option<f64>, true_peak: Option<f64>) -> Vec<f64> {
    let Some(loudness) = loudness else { return samples };
    let mut gain_db = TARGET_LUFS - loudness;
    if let Some(peak) = true_peak {
        gain_db = gain_db.min(-1.0 - peak);
    }
    let gain = 10f64.powf(gain_db / 20.0);
    for sample in samples.iter_mut() {
        *sample = (*sample * gain).clamp(-1.0, 1.0);
    }
    samples
}
