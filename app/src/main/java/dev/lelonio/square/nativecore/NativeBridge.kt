package dev.lelonio.square.nativecore

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Events pushed from the librespot engine. Called on a native tokio worker
 * thread, never on the main thread — implementations must marshal themselves.
 */
interface NativeEvents {
    /**
     * @param type one of `loading`, `playing`, `paused`, `position`, `stopped`,
     *   `end_of_track`, `unavailable`
     * @param uri the Spotify URI the event refers to, possibly empty
     * @param positionMs playback position, 0 for events that carry no position
     */
    fun onEvent(type: String, uri: String, positionMs: Long)
}

/**
 * Thin JNI surface over the Rust playback core.
 *
 * All methods throw [IllegalStateException] with the native error message on
 * failure. None of them block on network I/O except [start], which completes
 * the Spotify access-point handshake and must not be called from the main
 * thread.
 */
object NativeBridge {

    init {
        System.loadLibrary("squarecore")
    }

    /**
     * Publishes the application [android.content.Context] to the native side so
     * cpal can open an AAudio stream. Call once, before [start].
     */
    fun initContext(context: android.content.Context) =
        nativeInitContext(context.applicationContext)

    /**
     * Registers the object the native sink writes PCM to.
     *
     * Must be called before [start]; the sink has nowhere to send audio without
     * it. The object must expose `start()`, `stop()` and
     * `write(ByteBuffer, Int, Int, Int)` — see `native/src/sink.rs`.
     */
    fun setAudioOutput(output: Any) = nativeSetAudioOutput(output)

    /**
     * Authenticates and builds the player.
     *
     * @param clientId must match the client id the [accessToken] was issued for,
     *   otherwise login5 rejects the token. Pass an empty string to fall back to
     *   the platform default baked into librespot.
     * @param credentialsDir where librespot persists reusable credentials —
     *   required, not optional: catalogue requests fail without it
     * @param cacheDir writable directory for librespot's temporary audio files
     */
    fun start(
        clientId: String,
        deviceName: String,
        /**
         * This phone's Connect id, the same on every launch.
         *
         * Chosen and kept by the app because librespot's own default is a fresh
         * one per session, which shows the account a brand new device every time
         * the engine starts.
         */
        deviceId: String,
        accessToken: String,
        credentialsDir: String,
        cacheDir: String,
        /** What language Spotify should answer in, as a bare tag: `it`, `en`. */
        language: String,
        /**
         * Which file to ask for: 96, 160 or 320 kbps.
         *
         * Fixed for the life of the engine. The player reads it when a track
         * loads but owns its configuration until it is dropped, so changing the
         * setting means starting a new engine.
         */
        bitrateKbps: Int,
        /**
         * How long one track dissolves into the next, in milliseconds; zero is
         * off. Fixed for the life of the engine for the same reason as the
         * bitrate: it belongs to the player's configuration.
         */
        crossfadeMs: Int,
        listener: NativeEvents,
    ) = nativeStart(
        clientId,
        deviceName,
        deviceId,
        accessToken,
        credentialsDir,
        cacheDir,
        language,
        bitrateKbps,
        crossfadeMs,
        listener,
    )

    /**
     * Hands the whole queue to the Connect device, starting at [index].
     *
     * A list rather than one track: since the engine went through Spirc, the
     * queue belongs to the Connect state — it is what the account and every
     * other device see, and it is Spirc that advances at the end of a track.
     */
    fun loadQueue(
        uris: List<String>,
        index: Int,
        startPlaying: Boolean,
        positionMs: Int = 0,
        /** The playlist or album the queue came from; empty when it came from none. */
        contextUri: String = "",
        /** Hand playback to Spotify as that context rather than as loose tracks. */
        playAsContext: Boolean = false,
    ) =
        nativeLoadQueue(
            Json.encodeToString(ListSerializer(String.serializer()), uris),
            index,
            startPlaying,
            positionMs,
            contextUri,
            playAsContext,
        )

    /**
     * Tells the device a new running order, leaving the current track playing.
     *
     * For a shuffle turned on or off under a song: what comes after changes,
     * the song does not, and a reload would put a gap in the middle of it.
     */
    fun setQueueOrder(uris: List<String>, index: Int) =
        nativeSetQueueOrder(
            Json.encodeToString(ListSerializer(String.serializer()), uris),
            index,
        )

    fun play() = nativePlay()
    fun pause() = nativePause()
    fun stop() = nativeStop()
    fun seek(positionMs: Long) = nativeSeek(positionMs)
    fun next() = nativeNext()
    fun previous() = nativePrevious()
    fun setShuffle(shuffle: Boolean) = nativeSetShuffle(shuffle)
    fun setRepeat(repeatContext: Boolean, repeatTrack: Boolean) =
        nativeSetRepeat(repeatContext, repeatTrack)

    /** Volume in librespot's raw 0..65535 range. */
    var volume: Int
        get() = nativeVolume()
        set(value) = nativeSetVolume(value)

    val isConnected: Boolean get() = nativeIsConnected()

    /**
     * Whether the Connect device is gone and the engine wants rebuilding.
     *
     * Distinct from [isConnected], which asks about the session: a session can
     * be perfectly valid while the Spirc task behind it has died, and that is
     * the state where transport commands stop landing and the player carries on
     * making sound. Set the first time a command has to go around Spirc to
     * reach the player, and cleared by the next [start].
     */
    val spircLost: Boolean get() = nativeSpircLost()

    /**
     * Rebuilds the player with a new bitrate and crossfade.
     *
     * Both belong to a configuration the player owns for its whole life, so a
     * change means another player. This replaces the session and the player and
     * leaves the runtime and the audio output where they are: tearing those
     * down from here is what used to abort the process. The queue is the
     * caller's to put back. Blocking.
     */
    fun setQuality(bitrateKbps: Int, crossfadeMs: Int) = nativeSetQuality(bitrateKbps, crossfadeMs)

    /**
     * Builds a new session, player and Connect device, keeping everything else.
     *
     * The answer to [spircLost]. A dead Connect device cannot be revived on the
     * session it belonged to, because librespot hands out the Spirc builder once
     * per session, so this discards the session as well. The tokio runtime and
     * the audio output survive: tearing those down from here is what used to
     * abort the process on a destroyed mutex.
     *
     * Blocks on the handshake. Never call it from the main thread.
     */
    fun reconnect() = nativeReconnect()

    // --- The account's other devices ---
    //
    // Square is a Connect device, and this is the other half of the same
    // protocol: what the official client does when it shows you a speaker
    // playing in another room and lets you press pause on it.
    //
    // Nothing here is polled. Spotify pushes a cluster update to every device
    // whenever anything changes, and the engine reports one as an event named
    // "cluster"; these read what that update left behind.

    /**
     * Whether the account's playback belongs to another device right now.
     *
     * The one question that decides whether this app may touch the engine at
     * all. go-librespot keeps the same answer as a single boolean it owns, and
     * everything it does is gated on it; here it comes from the cluster, which
     * is the same source by another road.
     */
    val playbackElsewhere: Boolean
        get() = runCatching { nativePlaybackElsewhere() }.getOrDefault(false)

    /**
     * Republishes the queue as the playlist it came from.
     *
     * For the moment before playback is handed to another device: a queue sent
     * as a bare list of URIs carries a context nobody else can resolve, and the
     * device receiving it ends up with a queue that does not play. Blocking.
     */
    fun publishContext(positionMs: Int) = nativePublishContext(positionMs)

    /**
     * Starts playing here what another device was playing, at its position.
     *
     * One call, so the music moves before the queue behind it has been read:
     * resolving a long playlist first is a second of silence in which the only
     * honest thing the screen can say is nothing. Blocking.
     */
    fun resumeHere(contextUri: String, trackUri: String, positionMs: Int) =
        nativeResumeHere(contextUri, trackUri, positionMs)

    /**
     * Takes the account's playback for this device.
     *
     * The picker's own row. A transfer is addressed from one device to another
     * through the server, and this phone cannot address itself that way: the
     * request goes out and comes back refused. Activating does it directly.
     */
    fun takeOver() = nativeTakeOver()

    /**
     * The account's personalised home, as raw JSON from Spotify's gateway.
     *
     * A different service from everything else here, and a more fragile one:
     * the query is sent as a hash of one Spotify already knows, and those
     * hashes change when its web client is rebuilt. Callers must be able to
     * carry on with nothing. Blocking.
     */
    fun homeFeed(
        timeZone: String,
        language: String,
        /**
         * The persisted query's hash and the web client version to claim.
         *
         * Passed in rather than built in: Spotify retires both on its own
         * schedule, and a string that ages should not need a release of this
         * app to replace. See PathfinderKeys.
         */
        hash: String,
        appVersion: String,
    ): String = nativeHomeFeed(timeZone, language, hash, appVersion)

    /** This device's own Connect id, which is what says "here" rather than "there". */
    fun deviceId(): String = runCatching { nativeDeviceId() }.getOrDefault("")

    /** What the account is playing and where, as JSON; `{}` when nothing is. */
    fun remoteState(): String = nativeRemoteState()

    /**
     * What this device is playing, as `{"contextUri": …, "trackUri": …}`.
     *
     * Not the same question as [remoteState], which reads the account's own
     * picture: that picture is not sent to the device it describes, so while
     * another client drives this one this is the only current answer.
     */
    fun playingHere(): String = runCatching { nativePlayingHere() }.getOrDefault("{}")

    /** Every device the account can see, this one included, as a JSON array. */
    fun remoteDevices(): String = nativeRemoteDevices()

    /**
     * Sends one command to another device.
     *
     * [body] is the JSON Spotify's own clients send, so the caller names the
     * endpoint. Accepted for delivery is not obeyed: the device is what acts,
     * and the cluster update that follows is what says it did.
     *
     * Blocking. Never call it from the main thread.
     */
    fun remoteCommand(deviceId: String, body: String) = nativeRemoteCommand(deviceId, body)

    /** Volume in librespot's raw 0..65535 range, on another device. */
    fun remoteVolume(deviceId: String, volume: Int) = nativeRemoteVolume(deviceId, volume)

    fun shutdown() = nativeShutdown()

    // --- Catalogue, served by the access point rather than api.spotify.com ---

    /** Logged-in account name. */
    fun username(): String = nativeUsername()

    /** URI of the account's "Liked Songs" pseudo-playlist. */
    fun collectionUri(): String = nativeCollectionUri()

    /** The account's own playlists, as raw JSON from the access point. */
    fun rootlist(): String = nativeRootlist()

    /** JSON array of track URIs contained in a playlist, album or collection. */
    fun contextTracks(contextUri: String): String = nativeContextTracks(contextUri)

    /**
     * Display metadata for the given tracks.
     *
     * @param urisJson JSON array of Spotify track URIs
     * @return JSON array of objects; tracks that cannot be resolved are omitted,
     *   so the result may be shorter than the input
     */
    fun tracksMetadata(urisJson: String): String = nativeTracksMetadata(urisJson)

    /** Lyrics JSON for a track, or the string `null` when Spotify has none. */
    fun lyrics(trackUri: String): String = nativeLyrics(trackUri)

    /**
     * Canvas JSON for a track — the short looping clip shown behind the player —
     * or the string `null` when the track has none, which is the common case.
     */
    fun canvas(trackUri: String): String = nativeCanvas(trackUri)

    /**
     * A playlist's cover URL as a JSON string, or the string `null`.
     *
     * The account's index of its playlists carries a picture only for the ones
     * the user made; Spotify's own keep theirs on the playlist itself.
     */
    fun playlistCover(uri: String): String = nativePlaylistCover(uri)

    private external fun nativePlaylistCover(uri: String): String
    private external fun nativeInitContext(context: android.content.Context)
    private external fun nativeSetAudioOutput(output: Any)
    private external fun nativeStart(
        clientId: String,
        deviceName: String,
        deviceId: String,
        accessToken: String,
        credentialsDir: String,
        cacheDir: String,
        language: String,
        bitrateKbps: Int,
        crossfadeMs: Int,
        listener: NativeEvents,
    )

    private external fun nativeLoadQueue(
        urisJson: String,
        index: Int,
        startPlaying: Boolean,
        positionMs: Int,
        contextUri: String,
        playAsContext: Boolean,
    )
    private external fun nativeNext()
    private external fun nativePrevious()
    private external fun nativeSetShuffle(shuffle: Boolean)
    private external fun nativeSetRepeat(repeatContext: Boolean, repeatTrack: Boolean)
    private external fun nativePlay()
    private external fun nativePause()
    private external fun nativeStop()
    private external fun nativeSeek(positionMs: Long)
    private external fun nativeSetVolume(volume: Int)
    private external fun nativeVolume(): Int
    private external fun nativeIsConnected(): Boolean
    private external fun nativeSpircLost(): Boolean
    private external fun nativeSetQuality(bitrateKbps: Int, crossfadeMs: Int)
    private external fun nativeReconnect()
    private external fun nativePlaybackElsewhere(): Boolean
    private external fun nativePublishContext(positionMs: Int): Boolean
    private external fun nativeResumeHere(contextUri: String, trackUri: String, positionMs: Int)
    private external fun nativeTakeOver()
    private external fun nativeHomeFeed(
        timeZone: String,
        language: String,
        hash: String,
        appVersion: String,
    ): String
    private external fun nativeDeviceId(): String
    private external fun nativeSetQueueOrder(urisJson: String, index: Int)
    private external fun nativePlayingHere(): String
    private external fun nativeRemoteState(): String
    private external fun nativeRemoteDevices(): String
    private external fun nativeRemoteCommand(deviceId: String, body: String)
    private external fun nativeRemoteVolume(deviceId: String, volume: Int)
    private external fun nativeShutdown()
    private external fun nativeUsername(): String
    private external fun nativeCollectionUri(): String
    private external fun nativeRootlist(): String
    private external fun nativeContextTracks(contextUri: String): String
    private external fun nativeTracksMetadata(urisJson: String): String
    private external fun nativeLyrics(trackUri: String): String
    private external fun nativeCanvas(trackUri: String): String
}
