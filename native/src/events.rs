//! Telling Spotify what was listened to.
//!
//! Playback through librespot never appeared in the account's history, and the
//! reason is not a setting: Spotify builds "recently played" from *events* the
//! client posts, and librespot posts none. It publishes its Connect state, so
//! the servers know the device exists and what it is doing, but nothing in that
//! path counts as a listen — verified on the device by starting a track through
//! the Web API and watching the history stay unchanged while the audio played.
//!
//! The wire format is not documented by Spotify. It is taken from
//! librespot-java's `EventService` (Apache-2.0, by devgianlu), reimplemented
//! here rather than copied: events are Mercury POSTs to
//! `hm://event-service/v1/events`, whose body is a list of fields separated by
//! `0x09` and beginning with a numeric event id and a second constant nobody
//! has a name for.
//!
//! Three events make up one listen:
//!
//! * `557` — a new *session*, meaning a context was started.
//! * `558` — a new *playback*, meaning a track began within it.
//! * `12` — the transition off that track, carrying how far it got. This is the
//!   one Spotify counts.
//!
//! The Rust Mercury client has neither POST nor header fields, so both were
//! added to the vendored copy of librespot-core; see native/vendor/README.md.
//! An event that fails is a missing history entry, never a failed playback, so
//! nothing here is allowed to break anything.

use librespot_core::session::Session;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

/// Separates fields in an event body.
const SEP: u8 = 0x09;

const EVENT_URI: &str = "hm://event-service/v1/events";

/// One event, built field by field.
struct Event {
    body: Vec<u8>,
}

impl Event {
    /// `id` and `unknown` identify the kind; see the module note.
    fn new(id: &str, unknown: &str) -> Self {
        let mut event = Event {
            body: id.as_bytes().to_vec(),
        };
        event.field(unknown);
        event
    }

    fn field(&mut self, value: &str) -> &mut Self {
        self.body.push(SEP);
        self.body.extend_from_slice(value.as_bytes());
        self
    }

    fn number(&mut self, value: impl ToString) -> &mut Self {
        self.field(&value.to_string())
    }
}

/// What ended a track, in Spotify's own vocabulary.
#[derive(Clone, Copy)]
pub enum EndReason {
    /// Played to the end.
    TrackDone,
    /// Skipped forwards.
    Forward,
    /// Skipped backwards.
    Backward,
    /// Playback stopped without another track following.
    EndPlay,
}

impl EndReason {
    fn as_str(self) -> &'static str {
        match self {
            EndReason::TrackDone => "trackdone",
            EndReason::Forward => "fwdbtn",
            EndReason::Backward => "backbtn",
            EndReason::EndPlay => "endplay",
        }
    }
}

/// Everything one finished track needs to be reported.
pub struct Listen<'a> {
    /// The track's id in hex — its gid, not the base62 in the URI.
    pub track_hex: &'a str,
    pub playback_id: &'a str,
    pub context_uri: &'a str,
    /// Where playback started within the track, in milliseconds.
    pub start_ms: u32,
    /// Where it stopped. This is what decides whether it counts as a listen.
    pub end_ms: u32,
    pub duration_ms: u32,
    pub reason_start: &'a str,
    pub reason_end: EndReason,
    /// Wall clock at the moment the track started, in milliseconds.
    pub started_at: u128,
}

/// Posts the events that make up the account's listening history.
pub struct EventService {
    session: Session,
    transitions: AtomicU64,
}

impl EventService {
    pub fn new(session: Session) -> Self {
        EventService {
            session,
            transitions: AtomicU64::new(0),
        }
    }

    /// A context was started — a playlist, an album, a single track.
    pub fn new_session(&self, session_id: &str, context_uri: &str, context_size: usize) {
        let mut event = Event::new("557", "3");
        event
            .field(session_id)
            .field(context_uri)
            .field(context_uri)
            .number(now_ms())
            .field("")
            .number(context_size)
            // The context's own URL, which the client uses to page through it.
            .field(&format!("context://{context_uri}"));
        self.post(event);
    }

    /// A track began within that context.
    pub fn new_playback(&self, playback_id: &str, session_id: &str) {
        let mut event = Event::new("558", "1");
        event.field(playback_id).field(session_id).number(now_ms());
        self.post(event);
    }

    /// A track ended. The one event Spotify turns into history.
    pub fn track_transition(&self, listen: &Listen) {
        let device_id = self.session.device_id().to_string();
        let count = self.transitions.fetch_add(1, Ordering::Relaxed);

        // Several of these are timings the official client measures and we do
        // not: how long the audio key took, how much was decrypted, how far the
        // decoder read ahead. Zero is what librespot-java sends when it has no
        // figure either, and Spotify has never rejected an event over them —
        // what it reads is the track, the context and how much was played.
        let mut event = Event::new("12", "38");
        event
            .number(count)
            .field(&device_id)
            .field(listen.playback_id)
            .field("00000000000000000000000000000000")
            // How playback began, and how it ended.
            .field("unknown")
            .field(listen.reason_start)
            .field("unknown")
            .field(listen.reason_end.as_str())
            // Bytes decoded, bytes total.
            .number(0)
            .number(0)
            // Where it stopped, twice.
            .number(listen.end_ms)
            .number(listen.end_ms)
            .number(listen.duration_ms)
            // Decrypt time, crossfade overlap, and two constants.
            .number(0)
            .number(0)
            .field("0")
            .field("0")
            // Whether it started from the beginning, and where from.
            .field(if listen.start_ms == 0 { "0" } else { "1" })
            .number(listen.start_ms)
            .field("0")
            .field("-1")
            .field("context")
            // Audio key timing.
            .number(0)
            .field("0")
            .field("0")
            .field("0")
            .field("0")
            .field("0")
            .number(listen.end_ms)
            .number(listen.end_ms)
            .field("0")
            .number(BITRATE)
            .field(listen.context_uri)
            .field(ENCODING)
            .field(listen.track_hex)
            .field("")
            .field("0")
            .number(listen.started_at)
            .field("0")
            .field("context")
            .field(REFERRER)
            .field(FEATURE_VERSION)
            .field("com.spotify")
            .field("none")
            .field("none")
            // Which device sent the last command. Ours: playback is started
            // here, not by a phone across the room.
            .field(&device_id)
            .field("na")
            .field("none");
        self.post(event);
    }

    /// Posts the event and logs what Spotify made of it.
    ///
    /// The reply matters more than it looks: a well-formed packet the server
    /// silently drops and a rejected one are indistinguishable without it, and
    /// this whole format is reverse-engineered. Upstream's Mercury client has
    /// neither POST nor header fields, so both were added to the vendored copy.
    fn post(&self, event: Event) {
        let body = event.body;
        let fields = vec![
            ("Accept-Language".to_string(), b"en".to_vec()),
            (
                "X-ClientTimeStamp".to_string(),
                now_ms().to_string().into_bytes(),
            ),
        ];

        let request = match self.session.mercury().post(EVENT_URI, body.clone(), fields) {
            Ok(request) => request,
            Err(e) => {
                log::warn!("event not sent: {e}");
                return;
            }
        };

        // Waited for on the engine's runtime rather than on the caller's
        // thread: this is called from the player's event pump, and blocking
        // there would hold up every other event behind a network round trip.
        // The event's kind, never its body. The body carries the track, the
        // context and the time — the user's listening, in a log any app holding
        // READ_LOGS can read.
        let kind = kind_of(&body);
        let Ok(handle) = crate::engine::runtime_handle() else {
            return;
        };
        handle.spawn(async move {
            match request.await {
                Ok(response) if response.status_code == 200 => {
                    log::debug!("event {kind} accepted")
                }
                Ok(response) => log::warn!("event {kind} refused: {}", response.status_code),
                Err(e) => log::warn!("event {kind} failed: {e}"),
            }
        });
    }
}

/// The event's numeric kind, which is all that may be logged; see [EventService::post].
fn kind_of(body: &[u8]) -> String {
    body.split(|byte| *byte == SEP)
        .next()
        .map(|id| String::from_utf8_lossy(id).to_string())
        .unwrap_or_default()
}

pub fn now_ms() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0)
}

/// A 32-character hex id, which is the shape Spotify uses for both of these.
pub fn random_id() -> String {
    let mut id = String::with_capacity(32);
    for _ in 0..4 {
        // Exactly eight characters per part: Spotify's ids are 32 long, and a
        // u64 printed as hex ran to sixteen when the value was large enough,
        // which made every id a different length.
        let part = (SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.subsec_nanos() as u64)
            .unwrap_or(0)
            ^ rand_seed()) as u32;
        id.push_str(&format!("{part:08x}"));
    }
    id
}

fn rand_seed() -> u64 {
    use std::collections::hash_map::RandomState;
    use std::hash::{BuildHasher, Hasher};
    RandomState::new().build_hasher().finish()
}

/// 160 kbps, the ordinary quality this client asks for.
const BITRATE: u32 = 160_000;
const ENCODING: &str = "vorbis";

/// Where the play came from, in the client's own vocabulary.
const REFERRER: &str = "unknown";
const FEATURE_VERSION: &str = "harmony:4.21.0";
