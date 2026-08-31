# Phase 6 — Advanced DSP + Lyrics

## Actual audio architecture

The repository has two playback paths:

- Spotify/librespot: native PCM is written through the existing `AudioOutput`/Rust sink. Its established speed, pitch and reverb path is intentionally preserved.
- Local/offline and other Media3 playback: ExoPlayer decodes PCM and sends it through `DefaultAudioSink.AudioProcessorChain`.

Phase 6 extends only the Media3 PCM chain with `AdvancedDspAudioProcessor`; it does not replace the Spotify/native audio path.

Conceptually the Media3 path is:

`source → decoder → VocalAudioProcessor → AdvancedDspAudioProcessor → BungeeAudioProcessor → AudioTrack`

Speed/pitch remain Media3 playback parameters and the existing Bungee processor remains responsible for time/pitch processing. The advanced stage is downstream of vocal extraction and upstream of time stretching so EQ/reverb operate on the original PCM signal.

## DSP stage responsibilities

`AdvancedDspConfig` is an immutable control-plane snapshot. It contains bounded gain, bass boost, EQ bands, stereo widening, reverb, optional loudness correction and limiter state.

`DspStage` defines configure/reset/process lifecycle. Stateful stages reset on Media3 flush/reset and allocate their working state during format configuration, not per PCM packet.

The processing order is:

`gain + metadata-derived normalization → EQ/bass → virtualizer → reverb → limiter`

The limiter is last so gain-producing stages cannot exceed the bounded PCM output. Normalization is intentionally metadata-driven only: the application does not invent LUFS or loudness values.

## Real-time safety

- The processor supports 16-bit PCM, mono and stereo.
- Working PCM storage is preallocated during format configuration up to 16,384 frames.
- Decoder packets larger than the fixed work area pass through unchanged rather than triggering an allocation or being dropped on the audio thread.
- No filesystem, network, database or UI work is performed by the DSP processor.
- Configuration reads use the immutable `StateFlow.value` snapshot; configuration application uses fixed primitive arrays for EQ coefficients/state.
- Provider implementations are not referenced by the DSP stage.

## Presets and persistence

`EffectPreset` now carries a real `AdvancedDspConfig`. Built-ins include Normal, Bass Boost, Vocal, Rock, Classical, Night, Podcast, Slowed + Reverb and the pre-existing Sped Up preset. Custom presets persist their DSP configuration through the existing `EffectPresetStore` JSON store.

The selected live configuration remains separate from the playback state authority. Speed/pitch continue to be mirrored only for persistence and are applied to the actual Media3 player by the existing service path.

## Lyrics architecture

Existing provider parsers (`Catalog`, LrcLib, TTML and the other backend lyric sources) remain intact. Phase 6 adds `LyricsRepository` as a provider-independent boundary, with:

- bounded 32-entry in-memory cache;
- six-hour cache TTL;
- backend/source + track URI cache identity;
- cancellable suspend calls through the existing backend boundary;
- explicit Found/Unavailable/Failed result states;
- lyrics search through the backend search contract where lyric matches are supported;
- persisted per-track/source manual offset with SHA-256 preference keys;
- offset clamped to ±30 seconds.

`LyricsSync` derives the active line/word from authoritative playback position. It never writes to playback state and uses binary search, so synchronization work is logarithmic in lyric length.

Word-level timing is consumed only when the source supplies word timings. Existing TTML parsing remains the source for translated/word-timed lyrics; no generated lyrics or translations are introduced.

## Offline behavior

Cached lyrics are usable without a new network request when the repository cache is still valid. Local playback remains governed by the existing offline/player architecture; Phase 6 does not introduce a second queue or playback authority.

## Crossfade

No new crossfade implementation is introduced in Phase 6. The repository already owns crossfade configuration in the playback service. Extending it at the DSP layer without a verified dual-source mixer would risk queue, MediaSession and position regressions, so it remains outside this change.

## Spotify/native limitations

Advanced EQ, bass boost, virtualizer and the new PCM reverb/limiter stages are integrated into the Media3 PCM path. The Spotify/librespot native sink continues to use its existing DSP implementation. A full cross-provider DSP parity layer would require a targeted native PCM-stage integration and device testing; Phase 6 deliberately avoids a wholesale Rust rewrite.

## Validation and measurements

The environment available to this change exposes repository/GitHub operations but does not provide a runnable Android SDK/emulator/audio device. Therefore no claim is made here about hardware CPU usage, audio glitches, Bluetooth behavior, audible artifact quality, or device-specific lyrics scrolling.

Automated unit coverage added in this phase checks DSP configuration serialization/range validation and deterministic lyrics line/word synchronization. Gradle/device validation must be reported from an environment where the Android toolchain and device/emulator are available.
