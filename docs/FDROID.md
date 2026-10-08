# F-Droid submission preparation

Status: upstream listing metadata published; request for packaging submitted:
https://gitlab.com/fdroid/rfp/-/work_items/4524
Inclusion and the buildserver recipe are not yet validated. No playback
feature has been removed.

## Listing

English listing assets are in `fastlane/metadata/android/en-US`. The current
release is 2.4.5, versionCode 46. Screenshots and icon are copied from the
existing documentation. Donation URL: https://ko-fi.com/lelonio.

## Review questions

- Spotify and YouTube Music are proprietary network services. Propose
  `NonFreeNet`; let reviewers determine additional labels.
- `app/src/main/assets/websdk/player.html` dynamically loads
  `https://sdk.scdn.co/spotify-player.js` in an optional WebView playback mode.
  The SDK is not bundled. The official policy does not explicitly resolve
  this specific case: ask reviewers whether it is acceptable or requires a
  build variant excluding that mode. A network-service label alone does not
  establish acceptance of proprietary executable code.
- Screenshots show third-party album artwork. Verify redistribution rights or
  replace them with screenshots using cleared/demo artwork before submission.
- Review the GitHub updater's consent and signing behavior for F-Droid builds;
  APKs signed by F-Droid cannot update using the upstream signing key.

## Build recipe still to validate

- JDK 21; compile SDK 37 (`platforms;android-37.0` in current CI), build tools
  36.0.0, NDK 28.2.13676358, CMake 3.22.1. Confirm availability and toolchain
  acceptance in F-Droid's buildserver.
- Pin a Rust toolchain compatible with the full Cargo.lock dependency tree;
  current CI uses moving `stable`. cargo-ndk is pinned to 4.1.2.
- Prefetch Bungee and its submodules as pinned source before the offline build;
  the current Gradle fetch task clones from GitHub when sources are absent.
- Resolve and audit Maven/JitPack dependencies, including the Jellyfin FFmpeg
  decoder AAR and MetrolistExtractor; verify licenses and source availability.
- Compile both Rust ABIs and Bungee from source. Do not reuse local jniLibs.
- Start with the universal release APK. ABI-specific APKs currently share the
  same versionCode, so a split listing needs a deliberate version-code scheme.
- Run fdroid scanner/lint and a clean build of the selected tagged revision.
  Do not claim a working recipe or reproducibility until these checks pass.

## References

- https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/
- https://f-droid.org/docs/Inclusion_Policy/
- https://f-droid.org/docs/Anti-Features/

After the review questions and build checks are resolved, submit a merge
request to fdroid/fdroiddata with `metadata/dev.lelonio.square.yml` and the
verified build instructions. Upstream metadata must be present in the chosen
release tag; the existing v2.4.5 tag predates these listing files.
