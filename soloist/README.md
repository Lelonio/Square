# Soloist on Android

Spotify's Soloist (the official Linux arm64 player, from
soloist-builds.spotifycdn.com) run inside the app as this phone's player, in
place of librespot. librespot stays as the fallback and for downloads.

Nothing here reimplements Spotify's DRM: the official binary runs as is, under
its own glibc, and these pieces only adapt the process to Android.

## Runtime layout

The app targets API 35, so nothing in its data directory may be executed or
mapped as code. The runtime therefore ships as native libraries, extracted at
install into the native library dir (`useLegacyPackaging`), from
`app/src/soloist/jniLibs/arm64-v8a`:

    libsqlaunch.so    launch/launch.c, an Android executable
    libsqld.so        glibc 2.41's ld-linux-aarch64.so.1, patched (below)
    libsqc.so         libc.so.6, patched
    libsqm, libsqpthread, libsqdl, libsqrt, libsqgcc, libsqatomic
    libsqpulse.so     pulse/pulse.c (libpulse.so.0)
    libsqaudit.so     seccomp/audit.c (LD_AUDIT)
    libsqguard.so     seccomp/guard.c (LD_PRELOAD)

`SoloistRuntime` keeps in `filesDir/soloist`:

    soloist   the official binary, downloaded from Spotify's CDN
    key       the user's Soloist key (never committed)
    lib/      links under glibc's names (libc.so.6, ...) to the libraries above
    ca.pem    Android's trusted roots, as one bundle
    r         resolv.conf written from the active network's DNS
    data/, cache/

and runs

    libsqlaunch.so soloist libsqld.so --library-path lib @BIN <soloist args>

The launcher copies the binary into a memfd (mappable as code, as ART's JIT
cache is) and execs the loader on `/proc/self/fd/N` in the same process.
glibc's `LD_PRELOAD` and `LD_AUDIT` are passed as `SQ_LD_PRELOAD` and
`SQ_LD_AUDIT`, which the launcher renames just before the exec, so Android's
own linker, which starts the launcher, does not act on them.

Environment: `GLIBC_TUNABLES=glibc.pthread.rseq=0`, `SSL_CERT_FILE`, `HOME`,
`TMPDIR`, and for the audio `SQUARE_PCM_FIFO`, `SQUARE_XFADE_MS`,
`SQUARE_XFADE_MAX_MS`, `SQUARE_XFADE_FLAG`, `SQUARE_XFADE_CONF`.

## glibc patches

The app sandbox's seccomp filter kills the process (SIGSYS) on
`set_robust_list` (99), `rseq` (293), `clone3` (435) and `accept` (202).

- `svc #0` replaced by `movn x0, #37` (returns -ENOSYS, so glibc falls back) in
  ld.so at 0x106f4 and in libc at 0xee2f0, 0x85f4c, 0xbfbc8.
- `accept` made `accept4`: `mov x6, #0xca` to `#0xf2` in libc at 0xf0368.
- The string `/etc/resolv.conf` in libc replaced by `/proc/self/cwd/r`.
- rseq off through the tunable above.

`audit.c` catches any other SIGSYS, logs `SQSECCOMP syscall=N` and returns
-ENOSYS; `guard.c` keeps that handler from being replaced and SIGSYS from being
blocked.

## Audio

Soloist dlopens libpulse. `pulse.c` is a libpulse with only what Soloist calls:
a threaded mainloop, a context and playback streams. Each stream's PCM goes to
Square over a FIFO as packets (`SQPC`, a type, three bytes of padding, a
little-endian length, the payload):

    H  format: u32 rate, u32 channels, u32 encoding (1 s16, 2 float)
    A  interleaved PCM in that encoding
    P  pause    R  resume    F  flush
    N  the song after a dissolve is first heard
    L  u32 ms of music given by Soloist and not yet heard, five times a second

`SoloistAudio` reads them into Square's own AudioOutput, so the equaliser,
karaoke, reverb, speed and pitch apply. Crossfades are made here: Square writes
the dissolve length to the `SQUARE_XFADE_FLAG` file before a skip, and the
stream keeps what was ahead of the old song to fade under the new one.
`SQUARE_XFADE_CONF` names a file (`<ms kept ahead> <dissolve ms>`) read again
each second, so a change of the crossfade or skip fade needs no new run of
Soloist; the environment's values stand until it exists.

Soloist announces a song when it decodes it, as far ahead of the music as the
stream keeps (`L`): the player shows it once that much has been heard, and its
position runs from the music written to the output, not from the clock.

Soloist makes its own crossfade when the account's playback settings on
Spotify's side say so (`getAudioTransition: crossfade` in its log); nothing
here turns it off, and Square then makes none of its own over it.

Teardown: jobs still queued for a stream or a context are taken out when it is
let go of, and mainloops and contexts are not freed (a stream let go of after
them still reaches them); freed memory read there crashed Soloist (SIGSEGV)
whenever playback moved to another device.

## Building

The launcher, with the NDK's clang for Android:

    $B/clang --target=aarch64-linux-android26 -O2 -o libsqlaunch.so launch/launch.c

The rest with the NDK's clang (`B=$ANDROID_SDK/ndk/*/toolchains/llvm/prebuilt/*/bin`) and
the patched `libc.so.6` to link against:

    $B/clang --target=aarch64-linux-gnu -fPIC -shared -nostdlib -O2 \
      -fvisibility=hidden -fno-stack-protector -fuse-ld=lld \
      -Wl,-soname,libpulse.so.0 -Wl,--no-undefined \
      -o libpulse.so.0 pulse/pulse.c lib/libc.so.6

    $B/clang --target=aarch64-linux-gnu -fPIC -shared -nostdlib -O2 \
      -fno-stack-protector -fuse-ld=lld -Wl,-soname,libsqaudit.so \
      -o libsqaudit.so seccomp/audit.c

    $B/clang --target=aarch64-linux-gnu -fPIC -shared -nostdlib -O2 \
      -fvisibility=hidden -fno-stack-protector -fuse-ld=lld \
      -Wl,-soname,libsqguard.so -o libsqguard.so seccomp/guard.c lib/libc.so.6

## Licences

- `libsqld.so`, `libsqc.so`, `libsqm.so`, `libsqpthread.so`, `libsqdl.so`,
  `libsqrt.so`: the GNU C Library from Debian's `glibc 2.41-12+deb13u5`
  (arm64), under the LGPL-2.1-or-later. Its source is Debian's source package
  of that version (`apt-get source glibc=2.41-12+deb13u5`, or
  snapshot.debian.org); the only changes are the byte patches listed under
  "glibc patches" above, applied to the built ld.so and libc.so.6.
- `libsqgcc.so`, `libsqatomic.so`: libgcc_s and libatomic from the same Debian
  release, under the GPL-3.0 with the GCC Runtime Library Exception.
- `pulse.c`, `audit.c`, `guard.c`, `launch.c`: Square's own.
- Soloist itself is Spotify's: never redistributed here, downloaded from
  Spotify's CDN on the user's device, and used with the user's own key under
  Spotify's terms for Soloist.
