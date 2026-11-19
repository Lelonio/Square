#![warn(missing_docs)]
#![doc=include_str!("../README.md")]

#[macro_use]
extern crate log;

use librespot_core as core;
use librespot_playback as playback;
use librespot_protocol as protocol;

mod context_resolver;
mod model;
mod shuffle_vec;
/// LOCAL PATCH: see the module.
pub mod sleep_timer;
mod spirc;
mod state;

/// LOCAL PATCH: whether this device keeps out of the account's device lists.
///
/// Square plays through Spotify's own player (Soloist) when the listener
/// chooses it, and that is a Connect device of its own: with this one listed
/// too, every other client showed the phone twice. Hidden, it still plays
/// (downloads, from their files) and still sends Connect commands; it is only
/// not offered. Read each time the state is sent.
pub static HIDE_DEVICE: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

pub use model::*;
pub use spirc::*;
pub use state::*;
