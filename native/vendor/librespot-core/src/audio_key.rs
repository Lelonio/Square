use std::{
    collections::HashMap,
    io::Write,
    sync::Mutex,
    time::{Duration, Instant},
};

use byteorder::{BigEndian, ByteOrder, WriteBytesExt};
use bytes::Bytes;
use thiserror::Error;
use tokio::sync::oneshot;

use crate::{Error, FileId, SpotifyId, packet::PacketType, util::SeqGenerator};

#[derive(Debug, Hash, PartialEq, Eq, Copy, Clone)]
pub struct AudioKey(pub [u8; 16]);

#[derive(Debug, Error)]
pub enum AudioKeyError {
    #[error("audio key error")]
    AesKey,
    #[error("other end of channel disconnected")]
    Channel,
    #[error("unexpected packet type {0}")]
    Packet(u8),
    #[error("sequence {0} not pending")]
    Sequence(u32),
    #[error("audio key response timeout")]
    Timeout,
}

impl From<AudioKeyError> for Error {
    fn from(err: AudioKeyError) -> Self {
        match err {
            AudioKeyError::AesKey => Error::unavailable(err),
            AudioKeyError::Channel => Error::aborted(err),
            AudioKeyError::Sequence(_) => Error::aborted(err),
            AudioKeyError::Packet(_) => Error::unimplemented(err),
            AudioKeyError::Timeout => Error::aborted(err),
        }
    }
}

// LOCAL PATCH: `cool_off_until` is when the next key may be asked for, and
// `cool_off` how long the wait becomes if that one is refused too. The macro
// takes no doc comments on its fields, hence this note; see
// [AudioKeyManager::request] for what they are for.
component! {
    AudioKeyManager : AudioKeyManagerInner {
        sequence: SeqGenerator<u32> = SeqGenerator::new(0),
        pending: HashMap<u32, oneshot::Sender<Result<AudioKey, Error>>> = HashMap::new(),
        cool_off_until: Option<Instant> = None,
        cool_off: Duration = FIRST_COOL_OFF,
        known: Vec<((SpotifyId, FileId), AudioKey)> = Vec::new(),
    }
}

/// LOCAL PATCH: how many keys are remembered for the life of the session.
///
/// A key belongs to a file of a track and does not change, so a track played
/// again asks for nothing. Worth remembering because the thing that runs into
/// Spotify's limit is a listener going back and forth over the same handful of
/// songs: without this, every pass spent another request, and the refusals
/// that followed held up the song they actually stopped on.
///
/// Sixty-four of them is a few kilobytes and further back than anybody skips.
const KEYS_REMEMBERED: usize = 64;

/// LOCAL PATCH: how long to leave the key service alone after it refuses one.
///
/// Spotify meters these: a burst of requests — a run of skips, a preload
/// landing on top of a load, a failed load being tried again — is answered by
/// refusing everything for around a minute, and every retry inside that minute
/// makes it worse rather than better. Measured on this app: thirty-nine
/// requests in seventy seconds, four different tracks, none of them playable,
/// and everything fine on either side of the window.
///
/// So a refusal puts the whole session on hold, doubling until the cap, and one
/// key that comes back clears it.
/// LOCAL PATCH: when playback last asked for a key.
///
/// Spotify limits audio keys per session, and a download queue asking for one
/// spends the same allowance a track starting needs. Without somewhere to look
/// this up, a library being downloaded quietly starved playback: the queue took
/// the refusals, the session-wide hold that follows them applied to everything,
/// and pressing play did nothing for half a minute.
///
/// Only playback writes here. Downloads read it and wait; see `downloads.rs`.
static PLAYBACK_AT: Mutex<Option<Instant>> = Mutex::new(None);

/// Called by the player before it asks for a key.
pub fn note_playback_request() {
    if let Ok(mut at) = PLAYBACK_AT.lock() {
        *at = Some(Instant::now());
    }
}

/// How long ago playback last asked, or `None` if it never has.
pub fn since_playback_request() -> Option<Duration> {
    PLAYBACK_AT
        .lock()
        .ok()?
        .map(|at| Instant::now().saturating_duration_since(at))
}

const FIRST_COOL_OFF: Duration = Duration::from_secs(3);
const MAX_COOL_OFF: Duration = Duration::from_secs(30);

impl AudioKeyManager {
    pub(crate) fn dispatch(&self, cmd: PacketType, mut data: Bytes) -> Result<(), Error> {
        let seq = BigEndian::read_u32(data.split_to(4).as_ref());

        let sender = self
            .lock(|inner| inner.pending.remove(&seq))
            .ok_or(AudioKeyError::Sequence(seq))?;

        match cmd {
            PacketType::AesKey => {
                let mut key = [0u8; 16];
                key.copy_from_slice(data.as_ref());
                sender
                    .send(Ok(AudioKey(key)))
                    .map_err(|_| AudioKeyError::Channel)?
            }
            PacketType::AesKeyError => {
                error!(
                    "error audio key {:x} {:x}",
                    data.as_ref()[0],
                    data.as_ref()[1]
                );
                sender
                    .send(Err(AudioKeyError::AesKey.into()))
                    .map_err(|_| AudioKeyError::Channel)?
            }
            _ => {
                trace!("Did not expect {cmd:?} AES key packet with data {data:#?}");
                return Err(AudioKeyError::Packet(cmd as u8).into());
            }
        }

        Ok(())
    }

    /// LOCAL PATCH: retries a refused or timed-out key before giving up.
    ///
    /// Skipping quickly fires a burst of key requests, and Spotify answers some
    /// of them with `AesKeyError`. Upstream gives up on the first refusal, and
    /// the player then goes on to decode the still-encrypted bytes: the decoder
    /// dies on the garbage, the track ends immediately, the engine advances,
    /// and the next one is refused too. From the listener's side a run of skips
    /// turns into silence with the queue still moving.
    ///
    /// The refusal is rate limiting rather than a verdict on the track, so a
    /// short wait usually clears it. Only the transient failures are retried;
    /// a broken channel or an unexpected packet is structural and repeating it
    /// would just add latency to an error that is not going to change.
    pub async fn request(&self, track: SpotifyId, file: FileId) -> Result<AudioKey, Error> {
        // LOCAL PATCH: asked for before, and the answer does not change.
        if let Some(key) = self.lock(|inner| {
            inner
                .known
                .iter()
                .find(|((id, of), _)| *id == track && *of == file)
                .map(|(_, key)| *key)
        }) {
            return Ok(key);
        }

        const ATTEMPTS: usize = 3;

        let mut last = None;

        for attempt in 0..ATTEMPTS {
            // Whatever is left of the hold from the last refusal, whoever it
            // was that ran into it.
            if let Some(wait) = self.lock(|inner| {
                inner
                    .cool_off_until
                    .and_then(|until| until.checked_duration_since(Instant::now()))
            }) {
                debug!("waiting {wait:?} before asking for another audio key");
                tokio::time::sleep(wait).await;
            }

            match self.request_once(track, file).await {
                Ok(key) => {
                    self.lock(|inner| {
                        // LOCAL PATCH: remembered, so the next pass over this
                        // track costs nothing; see KEYS_REMEMBERED.
                        inner.known.retain(|((id, of), _)| !(*id == track && *of == file));
                        inner.known.push(((track, file), key));
                        if inner.known.len() > KEYS_REMEMBERED {
                            inner.known.remove(0);
                        }
                        inner.cool_off_until = None;
                        inner.cool_off = FIRST_COOL_OFF;
                    });
                    return Ok(key);
                }
                Err(e) => {
                    // Through the boxed cause, not the wrapper. This `Error` is
                    // librespot's own: a `kind` plus the error it was built
                    // from, and only the latter says which failure this was.
                    let transient = matches!(
                        e.error.downcast_ref::<AudioKeyError>(),
                        Some(AudioKeyError::AesKey) | Some(AudioKeyError::Timeout)
                    );
                    if transient {
                        // The hold belongs to the session, not to this request:
                        // the next track is about to ask for one too, and it
                        // will be refused for the same reason.
                        self.lock(|inner| {
                            let wait = inner.cool_off;
                            inner.cool_off_until = Some(Instant::now() + wait);
                            inner.cool_off = (wait * 2).min(MAX_COOL_OFF);
                            warn!("audio key refused, holding off for {wait:?}");
                        });
                    }
                    if !transient || attempt + 1 == ATTEMPTS {
                        return Err(e);
                    }
                    debug!("audio key attempt {} failed, retrying", attempt + 1);
                    last = Some(e);
                }
            }
        }

        Err(last.unwrap_or_else(|| AudioKeyError::Timeout.into()))
    }

    async fn request_once(&self, track: SpotifyId, file: FileId) -> Result<AudioKey, Error> {
        let (tx, rx) = oneshot::channel();

        let seq = self.lock(move |inner| {
            let seq = inner.sequence.get();
            inner.pending.insert(seq, tx);
            seq
        });

        self.send_key_request(seq, track, file)?;
        const KEY_RESPONSE_TIMEOUT: Duration = Duration::from_millis(1500);
        match tokio::time::timeout(KEY_RESPONSE_TIMEOUT, rx).await {
            Err(_) => {
                error!("Audio key response timeout");
                // Upstream leaves the entry behind, so a burst of timeouts
                // grows the map for the life of the session.
                self.lock(|inner| inner.pending.remove(&seq));
                Err(AudioKeyError::Timeout.into())
            }
            Ok(k) => k?,
        }
    }

    fn send_key_request(&self, seq: u32, track: SpotifyId, file: FileId) -> Result<(), Error> {
        let mut data: Vec<u8> = Vec::new();
        data.write_all(&file.0)?;
        data.write_all(&track.to_raw())?;
        data.write_u32::<BigEndian>(seq)?;
        data.write_u16::<BigEndian>(0x0000)?;

        self.session().send_packet(PacketType::RequestKey, data)
    }
}
