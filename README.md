# Spot

Client Android non ufficiale per Spotify Premium, costruito su
[librespot](https://github.com/librespot-org/librespot). L'app ufficiale non
serve: lo streaming avviene in-process tramite una libreria nativa Rust.

## Architettura

```
┌─────────────────────────────────────────┐
│ Compose UI                              │
├─────────────────────────────────────────┤
│ MediaController ──► MediaSession        │  notifica, lock screen, Bluetooth
├─────────────────────────────────────────┤
│ PlaybackService                         │  foreground service, possiede il motore
│   └─ LibrespotPlayer : SimpleBasePlayer │  adatta il motore a Media3
├──────────────────┬──────────────────────┤
│ Web API (HTTPS)  │ JNI ──► libspotcore  │
│ metadata,        │        librespot     │
│ libreria, search │        + AAudio      │
└──────────────────┴──────────────────────┘
```

Tutto passa dall'access point, **non** da `api.spotify.com`:

- **Metadata** da `native/src/catalog.rs`, via `spclient` e `librespot-metadata`.
- **Audio** dal motore nativo. La Web API non espone nessun endpoint di
  streaming — non è una limitazione aggirabile, semplicemente non esiste.

La Web API resta cablata (`data/SpotifyApi.kt`) ma non è più usata per la
libreria: mette in conto le richieste per applicazione, e il client id che
serve qui è condiviso con ogni altra istanza librespot, quindi la quota risulta
esaurita da altri e risponde `429` a prescindere da quanto poco chiediamo noi.

Il player non tiene una copia dello stato: `LibrespotPlayer` riporta solo ciò che
il motore ha confermato via evento. È il motivo per cui la seek bar non parte
prima dell'audio.

## Prerequisiti

- Android Studio con SDK platform 35 e **NDK 28.2.13676358**
- Rust ≥ 1.86 con i target Android
- `cargo-ndk`

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
sdkmanager --install "ndk;28.2.13676358"
```

Gradle passa da sé `ANDROID_NDK_HOME` al task Cargo; la variabile serve solo se
compili il core a mano.

## Build

Il task Gradle `cargoBuild` cross-compila il core e copia i `.so` in
`app/src/main/jniLibs`, quindi basta:

```bash
./gradlew :app:assembleDebug
```

Per iterare più in fretta, riduci `nativeAbis` in `app/build.gradle.kts` al solo
`arm64-v8a`.

## Note tecniche

**`librespot-core` è patchato.** Copia locale in `native/vendor/`, agganciata con
`[patch.crates-io]`. Una riga: `OS` forzato a `"linux"`. Senza, ogni richiesta di
catalogo fallisce con `BAD_REQUEST` — dettagli e motivo in
[native/vendor/README.md](native/vendor/README.md).

**OAuth: client id keymaster, porta 5588 fissa.** Il redirect deve essere
esattamente `http://127.0.0.1:5588/login`, l'unico registrato per quel client id.
Il socket va legato al loopback **IPv4** esplicito: su Android
`InetAddress.getLoopbackAddress()` restituisce `::1`, e il browser fallisce il
redirect con `ERR_CONNECTION_REFUSED`.

**`MediaSession.Callback` è obbligatorio.** Una sessione non passa mai al player
i `MediaItem` di un controller: chiede prima all'app di risolverli, e
l'implementazione di default rifiuta ogni item privo di URI di riproduzione. I
nostri portano solo il `mediaId`, quindi senza `onAddMediaItems` la chiamata
sparisce in silenzio.

**Non ricostruire la playlist in `getState()`.** Viene invocato a ogni
`invalidateState`, quindi anche a ogni aggiornamento di posizione: rigenerare
decine di `MediaItemData` due volte al secondo produce abbastanza spazzatura da
far sentire lo stuttering. La lista è in cache e invalidata solo quando la coda
cambia davvero.

**`vergen` va tenuto a 9.0.6.** Il build script di `librespot-core` 0.8.0 non
compila con 9.1.0: il bump, semver-compatibile, ha cambiato il trait `Add`. Il
pin è in `native/Cargo.lock`; non rigenerarlo senza verificare.

**TLS via `rustls-tls-webpki-roots`.** Evita di cross-compilare OpenSSL e non
dipende dal keystore Java.

**`minSdk` 26.** Il backend audio è cpal → AAudio, che l'NDK espone da API 26.

**Refresh dei token serializzato.** `TokenStore.validAccessToken()` prende un
mutex prima di rinfrescare. Spotify può ruotare il refresh token: senza il mutex,
richieste parallele attorno alla scadenza ne rinfrescherebbero più d'una e le
perdenti salverebbero un token già invalidato. È il bug che fa sloggare a caso
gran parte dei client esistenti.

## Stato

Funziona end-to-end: login, catalogo, riproduzione audio.

## Limiti attuali

- Micro-stuttering raro e appena percettibile. Sospetto il dimensionamento del
  buffer di `cpal`/AAudio; la via d'uscita sarebbe un sink che passa il PCM a un
  `AudioTrack` lato Kotlin, come fa Outify.
- `panic = "abort"` nel profilo release: qualunque panic di librespot chiude
  l'app senza messaggio, diagnosticabile solo dal tombstone. Servirebbe un
  `catch_unwind` al confine JNI che li converta in eccezioni Java.
- La libreria mostra solo la prima playlist dell'account. I preferiti non sono
  raggiungibili allo stesso modo: `spotify:user:{id}:collection` non è un
  context valido per quell'endpoint e risponde `503`.
- Nessuna coda persistente: `PlayQueue` vive in memoria e muore col servizio.
- Niente shuffle/repeat — non sono esposti tra i `Player.Commands` proprio per
  non mostrare pulsanti che non fanno nulla.
- Niente Spotify Connect: `librespot-connect` non è cablato.
- La UI è uno scheletro.

## Licenza e termini di servizio

Reimplementare il protocollo Spotify viola i Terms of Service di Spotify.
Il progetto non è pubblicabile sul Play Store e richiede un account Premium.
Uso personale, a tuo rischio.
