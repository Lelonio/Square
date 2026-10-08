# Square — request for packaging

- Application ID: `dev.lelonio.square`
- Source: https://github.com/Lelonio/Square
- Releases: https://github.com/Lelonio/Square/releases
- Issue tracker: https://github.com/Lelonio/Square/issues
- License: GPL-3.0-or-later, with the lyrics view under AGPL-3.0 as documented
  in the upstream README. Please review the combined-work licensing.
- Donation: https://ko-fi.com/lelonio
- Current release: 2.4.5 (versionCode 46)

I am the upstream author and would like Square included in F-Droid.
Square is an Android music client for Spotify Premium and YouTube Music with
Liquid Glass UI, synced lyrics, Spotify Connect, local files, audio effects
and a sleep timer. It does not require the official Spotify application for
native playback. Android 8.0 or later is required.

The app uses proprietary network services; I expect `NonFreeNet` to apply.
There are no bundled advertising/analytics SDKs in the direct Gradle dependency
list. A complete transitive dependency scan has not yet been performed.

## Question about optional Web Playback

The APK does not bundle Spotify's official Web Playback SDK. An optional,
user-selected mode runs an upstream HTML wrapper in Android WebView and loads
`https://sdk.scdn.co/spotify-player.js` at runtime. Native Spotify playback uses
the Rust librespot core. Is this remote SDK mode acceptable under your policy,
or should an F-Droid variant exclude it? I am disclosing it before claiming
the current release meets the inclusion criteria.

## Packaging details

The project uses Gradle, Kotlin/Compose, Rust via cargo-ndk, and CMake for
Bungee. Two Android ABIs are built; a universal APK is available. Native
libraries are built from source, not committed binaries. The repository's
release CI and `docs/DEVELOPMENT.md` describe the existing build.

The F-Droid buildserver recipe is not yet validated. Bungee must be prefetched
as pinned source with its submodules before an offline build. The Rust
toolchain must be pinned and the Maven/JitPack dependency tree audited.
The project also has a GitHub updater whose behavior and signing compatibility
need review for an F-Droid build.

Fastlane English listing metadata has been prepared upstream. Existing app
screenshots contain third-party album artwork; they should be omitted from
the listing unless redistribution rights are confirmed or replaced with
cleared demonstration artwork.

Please advise on the optional remote SDK and any required packaging changes.
