# SPOTLIGHT — Playback Engine 2.0 Reliability Pass

## Audit status

This document records the Phase 2 audit against the existing Phase 1 branch. The implementation is incremental: the Media3/ExoPlayer player, `PlaybackService`, `MediaLibrarySession`, `PlayQueue`, Spotify/librespot, YouTube backend, JNI bridge, and Rust engine remain the existing playback infrastructure.

A local Android/Rust build could not be executed in the available workspace because the repository checkout is unavailable and outbound GitHub DNS is blocked. Consequently runtime stress results and performance numbers are explicitly **unverified**.

## Existing playback lifecycle

```text
UI / MediaController
        |
        v
PlaybackService / MediaLibrarySession
        |
        +--> active Media3 Player
        |       +--> YouTube / ExoPlayer
        |       +--> Spotify / LibrespotPlayer
        |       +--> local media
        |
        +--> PlayQueue (Spotify/native path)
        |
        +--> PlaybackStore (persisted snapshot)
        |
        +--> MediaSession notification / Android Auto / Bluetooth controls
        |
        +--> NativeBridge --> Rust engine --> librespot --> audio sink
```

`PlaybackService` owns the service lifecycle and the active player. The existing Spotify path keeps its `PlayQueue`; plain Media3 players use their own timeline. `PlaybackStore` is persistence only and does not own playback.

## Reliability findings and changes

### 1. Bounded recovery policy

`PlaybackRecoveryPolicy` provides a pure retry decision with:

- transient retry only for network/backend availability failures,
- three attempts by default,
- exponential delays of 500 ms, 1 s, and 2 s,
- an 8 s maximum delay cap,
- no automatic retry for authentication, malformed/unavailable track, persistence, unsupported, or unexpected terminal errors.

The policy deliberately does not sleep or call a player. The service remains the owner of cancellation and recovery execution. This avoids introducing a retry loop that can outlive service lifecycle.

### 2. Async operation ordering

`PlaybackOperationGate` provides two small primitives for the service/recovery layer:

- a generation token that invalidates stale asynchronous callbacks,
- a cancellation-aware `Mutex` for mutations that must not overlap.

It is not a playback singleton and owns no coroutine scope. The intended integration point is service-owned operations such as backend switching, recovery/reconfiguration, and asynchronous stream preparation.

### 3. Explicit recovery state

`PlaybackStateSnapshot` now distinguishes `RECOVERING` from `BUFFERING`, `PLAYING`, `ENDED`, and terminal `ERROR`. `PlaybackStateReducer` preserves track identity and position when entering recovery.

This does not replace the Media3 player as runtime authority; it gives service/domain code a testable vocabulary for asynchronous failure and recovery.

### 4. Persistent queue

The existing implementation already persists the minimum useful Spotify queue information through `PlaybackStore`: original queue, shuffle permutation, current index, position, repeat mode, and context metadata. Plain Media3 timelines are persisted from their media items.

The queue implementation preserves logical ordering while shuffled, including add/play-next/remove operations. Its documented confinement is the playback application's looper rather than arbitrary concurrent access.

### 5. Crossfade / next-track behavior

Crossfade already exists in the native Spotify configuration and is applied when the engine is rebuilt. The Phase 2 pass does not replace that implementation blindly.

Spotify/librespot and YouTube do not share identical transition capabilities. Any future gapless/crossfade improvement must therefore be backend-specific and measurement-driven rather than generalized through `MusicBackend`.

### 6. Native/JNI boundary

The existing Rust JNI layer already wraps engine calls in `catch_unwind`, converts Rust `Result` failures into Java `IllegalStateException`, validates/clamps several numeric inputs, and logs native panic boundaries without exposing arbitrary panic payloads. This is an important safety boundary and was preserved rather than rewritten.

The Kotlin `NativeBridge` currently contains several `runCatching { ... }.getOrDefault(...)` convenience accessors. Those are acceptable for optional informational queries but are not sufficient evidence for critical playback commands; critical command paths should preserve the exception as a typed playback failure and log the diagnostic before recovery.

## Instrumentation

`PlaybackTelemetry` provides low-overhead monotonic timing markers using `SystemClock.elapsedRealtime()`.

Recommended event points:

- `play_request`
- `stream_prepare`
- `player_ready`
- `first_audio_output`
- `skip_request`
- `queue_transition`
- `buffering`
- `recovery`
- `native_reconfigure`

The implementation deliberately does not claim values that were not measured. CPU, memory, battery, and audio-output latency require an Android device/emulator with suitable profiling hooks and therefore remain unmeasured in this execution environment.

## Stress-test matrix

The following scenarios are required before calling the playback engine reliable:

| Scenario | Required evidence | Current status |
| --- | --- | --- |
| Single Spotify track | repeated successful start/seek/pause | Unverified locally |
| YouTube track | repeated prepare/play/seek | Unverified locally |
| Long queue | deterministic transitions | Unverified locally |
| Shuffle/repeat | stable ordering after mutations/restart | Unit coverage exists; runtime unverified |
| Rapid skips | no stale-track start | Unverified locally |
| Rapid seeks | final seek wins | Unverified locally |
| Network loss/recovery | bounded retry and recovery | Policy unit coverage; runtime unverified |
| Bluetooth/headset | session/player state remains consistent | Unverified locally |
| Background playback | foreground service + notification | Unverified locally |
| Service recreation | queue/state reconstruction | Unverified locally |
| Process kill | persisted state restoration | Unverified locally |
| Malformed/unavailable track | controlled failure / queue continuation | Unverified locally |
| Native failure | no process-wide crash | JNI guard inspected; runtime unverified |

## Performance methodology

Before/after comparisons must use the same device, backend, network conditions, queue size, and build variant. Record median and p95 where sample count permits.

Primary metrics:

- play request → ready,
- ready → first measurable audio output,
- skip request → next playable,
- buffering start → resume,
- queue transition duration,
- retry count/recovery duration,
- memory/CPU during a representative long queue,
- battery delta over a fixed playback interval.

No fabricated baseline or post-change numbers are included here.

## Known limitations

- Spotify/librespot and YouTube have different stream/control pipelines; a single universal preloader is not justified without measurements.
- Spotify's native queue and Media3 timelines are intentionally not merged into a second universal queue implementation.
- Gapless support depends on the active backend/native pipeline and has not been certified by device-level stress testing in this environment.
- Crossfade is already a Spotify/native configuration capability; changing it reconstructs the native player session and therefore requires careful service-side serialization.
- Android process death can restore only persisted state; live player/native handles cannot survive process death.
- CPU, memory, battery, and real audio-output latency are not measured in this environment.
- Full integration/stress validation is blocked until the repository can be checked out into a runnable Android/Rust environment.

## Deferred work

The next reliability increment should wire `PlaybackOperationGate` and `PlaybackRecoveryPolicy` into the service's critical asynchronous command paths, then add device-level instrumentation and stress tests. Smart preloading should be added only after baseline transition latency demonstrates that it materially improves the relevant backend.

No wholesale Rust/librespot rewrite is part of this phase.
