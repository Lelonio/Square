package dev.emanuele.spot.nativecore

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
        System.loadLibrary("spotcore")
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
        accessToken: String,
        credentialsDir: String,
        cacheDir: String,
        listener: NativeEvents,
    ) = nativeStart(clientId, deviceName, accessToken, credentialsDir, cacheDir, listener)

    fun load(uri: String, startPlaying: Boolean, positionMs: Int = 0) =
        nativeLoad(uri, startPlaying, positionMs)

    fun play() = nativePlay()
    fun pause() = nativePause()
    fun stop() = nativeStop()
    fun seek(positionMs: Long) = nativeSeek(positionMs)

    /** Volume in librespot's raw 0..65535 range. */
    var volume: Int
        get() = nativeVolume()
        set(value) = nativeSetVolume(value)

    val isConnected: Boolean get() = nativeIsConnected()

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

    private external fun nativeInitContext(context: android.content.Context)
    private external fun nativeSetAudioOutput(output: Any)
    private external fun nativeStart(
        clientId: String,
        deviceName: String,
        accessToken: String,
        credentialsDir: String,
        cacheDir: String,
        listener: NativeEvents,
    )

    private external fun nativeLoad(uri: String, startPlaying: Boolean, positionMs: Int)
    private external fun nativePlay()
    private external fun nativePause()
    private external fun nativeStop()
    private external fun nativeSeek(positionMs: Long)
    private external fun nativeSetVolume(volume: Int)
    private external fun nativeVolume(): Int
    private external fun nativeIsConnected(): Boolean
    private external fun nativeShutdown()
    private external fun nativeUsername(): String
    private external fun nativeCollectionUri(): String
    private external fun nativeRootlist(): String
    private external fun nativeContextTracks(contextUri: String): String
    private external fun nativeTracksMetadata(urisJson: String): String
    private external fun nativeLyrics(trackUri: String): String
    private external fun nativeCanvas(trackUri: String): String
}
