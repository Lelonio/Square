//! LOCAL PATCH: the sleep timer, set from any of the account's devices.
//!
//! Spotify's apps do not keep a sleep timer for a Connect device themselves:
//! they send `set_sleep_timer` to it, and read back from its published state
//! whether one is running and when it ends. The timer itself is the app's
//! (Square's own SleepTimer), so it is the same one whichever device set it:
//! a request is handed to the app through [on_request], and the app says what
//! is running through [set], which goes out with every state this device puts.
//!
//! The fields are newer than the protocol crate, so they are written by
//! number: `PlayerState.sleep_timer` is 38 and
//! `Capabilities.supports_remote_sleep_timer` is 34. After go-librespot-termux
//! 4c50725a, 304cd012 and af659717, which captured the payloads.

use std::sync::{
    OnceLock,
    atomic::{AtomicI64, Ordering},
};

use crate::protocol::{connect::Capabilities, player::PlayerState};

/// No timer.
pub const NONE: i64 = -1;
/// Stops when the playing track ends.
pub const END_OF_TRACK: i64 = 0;

const PLAYER_STATE_SLEEP_TIMER: u32 = 38;
const SUPPORTS_REMOTE_SLEEP_TIMER: u32 = 34;

static ON_REQUEST: OnceLock<fn(i64)> = OnceLock::new();

/// What is running: [NONE], [END_OF_TRACK], or the end as epoch milliseconds.
static CURRENT: AtomicI64 = AtomicI64::new(NONE);

/// Where requests from other devices go: [NONE] to cancel, [END_OF_TRACK], or
/// a length in seconds. Only the first call counts.
pub fn on_request(hook: fn(i64)) {
    let _ = ON_REQUEST.set(hook);
}

pub(crate) fn request(value: i64) {
    if let Some(hook) = ON_REQUEST.get() {
        hook(value);
    }
}

/// What the app's timer is doing now; see [CURRENT]. Published with the next
/// state; [crate::Spirc::push_state] sends one.
pub fn set(value: i64) {
    CURRENT.store(value, Ordering::SeqCst);
}

pub(crate) fn advertise(mut capabilities: Capabilities) -> Capabilities {
    capabilities
        .special_fields
        .mut_unknown_fields()
        .add_varint(SUPPORTS_REMOTE_SLEEP_TIMER, 1);
    capabilities
}

/// Written into every state before it is put, since a new context can replace
/// the player state and take an unknown field with it.
pub(crate) fn apply(player: &mut PlayerState) {
    let fields = player.special_fields.mut_unknown_fields();
    fields.remove(PLAYER_STATE_SLEEP_TIMER);
    if let Some(bytes) = encoded() {
        fields.add_length_delimited(PLAYER_STATE_SLEEP_TIMER, bytes);
    }
}

/// `SleepTimer { oneof { None none = 1; Timestamp timestamp = 2; EndOfTrack end_of_track = 3; } }`
fn encoded() -> Option<Vec<u8>> {
    match CURRENT.load(Ordering::SeqCst) {
        at if at < 0 => None,
        END_OF_TRACK => Some(vec![0x1A, 0x00]),
        at => {
            // Timestamp { int64 timestamp = 1; }
            let mut timestamp = vec![0x08];
            varint(at as u64, &mut timestamp);
            let mut out = vec![0x12];
            varint(timestamp.len() as u64, &mut out);
            out.extend(timestamp);
            Some(out)
        }
    }
}

fn varint(mut value: u64, out: &mut Vec<u8>) {
    while value >= 0x80 {
        out.push((value as u8) | 0x80);
        value >>= 7;
    }
    out.push(value as u8);
}
