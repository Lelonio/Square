//! The rest of the account's Spotify devices, watched and driven.
//!
//! Square is a Connect device: it publishes what it is playing and obeys what
//! the account tells it. This is the other half of the same protocol, the half
//! the official client also implements: seeing what *another* device is playing
//! and telling it what to do.
//!
//! Two channels, and they are not the same one.
//!
//! Reading is the dealer, the websocket the session already holds open. Spotify
//! pushes a `ClusterUpdate` to every device of the account whenever anything
//! changes: which device is active, what it is playing, where it is in the
//! track, how loud it is. Nothing is polled, and the last one received is kept
//! here for whoever asks.
//!
//! Writing is an HTTP request to the access point, addressed from this device to
//! that one. Spotify relays it; the target device is what actually obeys, so a
//! command that lands says nothing about whether the music changed. The next
//! cluster update is what says that.

use crate::engine;
use librespot_core::Session;
use librespot_protocol::connect::{Cluster, ClusterUpdate};
use librespot_protocol::player::PlayerState;
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};

/// The account's playback as Spotify last described it.
///
/// One value, replaced whole: a cluster update is a complete picture, not a
/// diff, so keeping anything from the previous one would be inventing state.
static CLUSTER: Mutex<Option<Cluster>> = Mutex::new(None);

/// Whether a watcher is already running, so a reconnection does not stack them.
static WATCHING: AtomicBool = AtomicBool::new(false);

/// Forgets the last cluster. Called when the session it came from is discarded.
pub fn clear() {
    if let Ok(mut cluster) = CLUSTER.lock() {
        *cluster = None;
    }
    WATCHING.store(false, Ordering::SeqCst);
}

/// Starts listening for cluster updates on `session`'s dealer.
///
/// Runs for the life of the session. The stream ends when the dealer goes, which
/// is what a lost connection does to it, and the next session starts another.
pub fn watch(session: &Session, notify: impl Fn() + Send + 'static) {
    let updates = match session
        .dealer()
        .listen_for("hm://connect-state/v1/cluster", Message_from_raw)
    {
        Ok(updates) => updates,
        Err(e) => {
            log::warn!("cannot watch the other devices: {e}");
            return;
        }
    };

    WATCHING.store(true, Ordering::SeqCst);
    // Spawned on whatever runtime is calling, which is the engine's own: this
    // runs inside the block_on that builds a session.
    tokio::spawn(async move {
        use futures_util::StreamExt;
        let mut updates = updates;
        while let Some(update) = updates.next().await {
            let mut update: ClusterUpdate = match update {
                Ok(update) => update,
                Err(e) => {
                    log::warn!("unreadable cluster update: {e}");
                    continue;
                }
            };
            let Some(cluster) = update.cluster.take() else {
                continue;
            };
            log::info!(
                "cluster update: active {} of {} devices, own {} [{}]",
                cluster.active_device_id,
                cluster.device.len(),
                device_id().unwrap_or_default(),
                cluster
                    .device
                    .iter()
                    .map(|(id, device)| format!("{id}={}", device.name))
                    .collect::<Vec<_>>()
                    .join(", "),
            );
            if let Ok(mut stored) = CLUSTER.lock() {
                *stored = Some(cluster);
            }
            notify();
        }
        log::info!("cluster watch ended");
        WATCHING.store(false, Ordering::SeqCst);
    });
}

/// `Message::from_raw` by another name, so the turbofish has somewhere to live.
#[allow(non_snake_case)]
fn Message_from_raw(
    message: librespot_core::dealer::protocol::Message,
) -> Result<ClusterUpdate, librespot_core::Error> {
    librespot_core::dealer::protocol::Message::from_raw(message)
}

/// Whether the account is playing on a device that is not this one.
///
/// The question every transport command has to ask before it does anything.
/// Taking over is an act, and it is one the listener has to have asked for:
/// go-librespot puts the same rule the other way round, stopping its own
/// playback the moment a cluster update names someone else, and never taking it
/// back on its own.
pub fn elsewhere_active() -> bool {
    let Ok(guard) = CLUSTER.lock() else {
        return false;
    };
    let Some(cluster) = guard.as_ref() else {
        return false;
    };
    if cluster.active_device_id.is_empty() {
        return false;
    }
    device_id()
        .map(|own| cluster.active_device_id != own)
        .unwrap_or(false)
}

/// This device's own Connect id, so the caller can tell "here" from "there".
pub fn device_id() -> engine::EngineResult<String> {
    engine::with_session(|session| session.device_id().to_string())
}

/// What is playing on the account right now, as JSON, or `{}` if nothing is.
///
/// Includes this device: the caller decides what to do with that, because
/// "playing here" and "playing there" are the same protocol and only the id
/// tells them apart.
pub fn state_json() -> String {
    let Ok(guard) = CLUSTER.lock() else {
        return "{}".into();
    };
    let Some(cluster) = guard.as_ref() else {
        return "{}".into();
    };

    let player: &PlayerState = &cluster.player_state;
    let track = player.track.as_ref();
    let device = cluster.device.get(&cluster.active_device_id);

    // Position has to be worked out, not read: Spotify sends where the track was
    // at a given timestamp, and the device has been playing ever since. Without
    // the arithmetic the bar would sit still until the next update arrived.
    let elapsed = if player.is_playing && !player.is_paused {
        (now_ms() as i64 - player.timestamp).max(0)
    } else {
        0
    };
    let position = (player.position_as_of_timestamp + elapsed).max(0);

    let escape = |value: &str| serde_json::to_string(value).unwrap_or_else(|_| "\"\"".into());
    let metadata = |key: &str| {
        track
            .map(|track| track.metadata.get(key).cloned().unwrap_or_default())
            .unwrap_or_default()
    };

    format!(
        concat!(
            "{{\"activeDevice\":{},\"deviceName\":{},\"uri\":{},\"title\":{},",
            "\"artist\":{},\"album\":{},\"coverUri\":{},\"contextUri\":{},\"positionMs\":{},",
            "\"durationMs\":{},\"playing\":{},\"shuffle\":{},\"repeatContext\":{},",
            "\"repeatTrack\":{}}}"
        ),
        escape(&cluster.active_device_id),
        escape(device.map(|device| device.name.as_str()).unwrap_or("")),
        escape(track.map(|track| track.uri.as_str()).unwrap_or("")),
        escape(&metadata("title")),
        escape(&metadata("artist_name")),
        escape(&metadata("album_title")),
        escape(&metadata("image_url")),
        escape(&player.context_uri),
        position,
        player.duration,
        player.is_playing && !player.is_paused,
        player.options.shuffling_context,
        player.options.repeating_context,
        player.options.repeating_track,
    )
}

/// Every device the account can see, as a JSON array.
pub fn devices_json() -> String {
    let Ok(guard) = CLUSTER.lock() else {
        return "[]".into();
    };
    let Some(cluster) = guard.as_ref() else {
        return "[]".into();
    };

    let escape = |value: &str| serde_json::to_string(value).unwrap_or_else(|_| "\"\"".into());
    let mut devices: Vec<String> = cluster
        .device
        .iter()
        .map(|(id, device)| {
            format!(
                "{{\"id\":{},\"name\":{},\"type\":{},\"volume\":{},\"active\":{}}}",
                escape(id),
                escape(&device.name),
                escape(&format!("{:?}", device.device_type.enum_value_or_default())),
                device.volume,
                *id == cluster.active_device_id,
            )
        })
        .collect();
    // A stable order, since a map has none and a list that reshuffles itself
    // every update is unusable.
    devices.sort();
    format!("[{}]", devices.join(","))
}

/// Sends one command to another device.
///
/// `body` is the JSON Spotify's own clients send, so the caller names the
/// endpoint and this only addresses the envelope. Blocking, and answered by the
/// access point rather than by the device: a request that succeeds means the
/// command was accepted for delivery.
pub fn command(device_id: &str, body: String) -> engine::EngineResult<()> {
    if device_id.is_empty() {
        return Err("no device to command".into());
    }

    let handle = engine::runtime_handle()?;
    let session = engine::with_session(|session| session.clone())?;
    let from = session.device_id().to_string();
    let endpoint = format!("/connect-state/v1/player/command/from/{from}/to/{device_id}");

    handle
        .block_on(async move {
            session
                .spclient()
                .request(&http::Method::POST, &endpoint, None, Some(body.as_bytes()))
                .await
        })
        .map(|_| ())
        .map_err(|e| format!("command failed: {e}"))
}

/// Sets another device's volume, which is a different endpoint from the rest.
pub fn set_volume(device_id: &str, volume: u16) -> engine::EngineResult<()> {
    if device_id.is_empty() {
        return Err("no device to command".into());
    }

    let handle = engine::runtime_handle()?;
    let session = engine::with_session(|session| session.clone())?;
    let from = session.device_id().to_string();
    let endpoint = format!("/connect-state/v1/connect/volume/from/{from}/to/{device_id}");
    let body = format!("{{\"volume\":{volume}}}");

    handle
        .block_on(async move {
            session
                .spclient()
                .request(&http::Method::PUT, &endpoint, None, Some(body.as_bytes()))
                .await
        })
        .map(|_| ())
        .map_err(|e| format!("volume failed: {e}"))
}

fn now_ms() -> u128 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|since| since.as_millis())
        .unwrap_or(0)
}
