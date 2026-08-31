# Phase 4 — UI/UX 2.0

## Audit baseline

The existing interface already has a strong Liquid Glass direction rather than a generic Material surface. The audit found several areas worth preserving:

- `SquareTheme` already derives a restrained accent from artwork and keeps the page background transparent so the backdrop remains authoritative.
- `GlassEffectConfig` already has per-surface controls and a transparent fallback for unsupported rendering hardware.
- `Artwork` already sizes Coil requests from the displayed surface and disables crossfade in scrolling artwork.
- `HomeScreen` already models shelves/feed sections as data, which is a useful seam for future recommendations.
- `LibraryScreen` already separates filter, layout and sort state and uses stable list keys.
- `SquareApp` already keeps playback state outside individual screens and handles the mini-player/player chrome centrally.

The Phase 4 changes therefore refine shared primitives instead of replacing the navigation or playback architecture.

## Design-system decisions

`SquareUiTokens` centralizes spacing, corner radii, icon sizes and content-width limits. Material 3 receives matching shape and typography tokens from `SquareTheme`, while the existing glass/backdrop layer remains the visual material.

Accent animation now follows Compose's `LocalMotionDurationScale`; reduced-motion configurations can snap the transition instead of forcing a 600ms colour animation.

## Accessibility decisions

The existing custom `pressable` interaction is retained, but its minimum height is now 48dp and it exposes a button role to accessibility services. Its press animation follows the same motion-duration scale rather than being an unavoidable animation.

The project should continue to prefer Material controls for toggles, sliders and text fields where possible because they carry baseline semantics and focus behaviour.

## Adaptive decisions

`SquareWindowClass` uses compact (<600dp), medium (600–839dp) and expanded (>=840dp) width classes. The helper also provides bounded content widths and conservative grid-column counts. It is intentionally dependency-free so Phase 4 does not introduce a second adaptive-layout framework.

Existing video landscape/PiP behaviour remains a separate concern in `SquareApp`; it must not be coupled to playback state or animation state.

## Downloads UI

`DownloadsScreen` is a read/command UI over the Phase 3 `DownloadManager.records` `StateFlow`. It does not maintain a second download state. It exposes persistent Wi-Fi-only and quality settings, progress, storage usage, retry/pause/resume/cancel/delete actions and all persisted terminal/intermediate states.

The UI deliberately does not invent provider capabilities. An `UNAVAILABLE` record remains visible and actionable as a provider limitation instead of pretending it is a playable local asset.

## Performance rules

- Do not add full-screen backdrop captures unless a glass surface actually samples them.
- Keep artwork decode sizes close to rendered bounds.
- Use stable `LazyColumn`/`LazyGrid` keys.
- Avoid per-frame allocations in progress rendering.
- Keep filesystem/storage accounting off the Main thread.
- Keep animation independent from Media3 timing and `PlaybackService` state.

## Verification limits

This repository was modified through the GitHub workspace, but the environment does not expose a local Android Gradle checkout/device. Therefore Gradle compilation, screenshot capture, TalkBack traversal, Macrobenchmark/FrameTiming, tablet/foldable rendering and real playback interaction remain unverified here. They must not be reported as passing without device/toolchain evidence.

## Screen audit status

| Area | Existing condition | Phase 4 action |
|---|---|---|
| Home | Modular feed/shelves already present | Preserve architecture; use shared tokens and future recommendation seam |
| Search | Existing search-specific UI and keyboard handling | Preserve; avoid duplicate request/state ownership |
| Library | Existing grid/list and stable keys | Adaptive helper available for follow-up layout tuning |
| Album / Artist / Playlist | Shared detail pattern already present | Preserve context-specific hierarchy |
| Player | Centralized in `SquareApp`, glass/performance controls already present | Preserve playback authority; motion is UI-only |
| Mini Player | Centralized and synchronized with playback state | Preserve; accessibility improvements flow through shared controls |
| Queue | Existing player-owned queue behaviour | Presentation-only changes permitted; no queue duplication |
| Lyrics | Existing player surface | Preserve authoritative playback/lyrics state |
| Settings | Existing nested information architecture | Phase 4 tokens/motion apply globally; download UI is a dedicated reusable surface |
| Downloads | Phase 3 state existed without a dedicated polished management surface | Add `DownloadsScreen` over the persistent manager |
| Audio Effects | Existing engine-backed controls | Do not expose unsupported effects |
| Backend / Login | Existing provider-specific flows | Keep provider identity behind existing abstractions |
| Error / Empty / Loading | Several screen-local variants existed | Add reusable `UiStateSurface` primitive for incremental consolidation |

## Architectural guardrails

Phase 4 does not move playback authority into Compose, does not add a second offline queue, does not modify `MusicBackend`, and does not make visual animation responsible for audio timing.
