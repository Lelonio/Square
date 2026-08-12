package dev.lelonio.square.data

import dev.lelonio.square.nativecore.NativeBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the account is playing on its other devices.
 *
 * Square is a Connect device, so it is already told: Spotify pushes a cluster
 * update to every device of an account whenever anything changes anywhere, and
 * the engine surfaces one as an event. Nothing here polls, and nothing here
 * asks the Web API.
 *
 * An object rather than something injected, for the same reason the engine is
 * one: there is a single session behind it, and the parts that care about this
 * are spread from the service to the player screen.
 */
object RemoteConnect {

    /** This device's own id, read once the engine has a session. */
    private var ownId: String = ""

    private val _playback = MutableStateFlow<RemotePlayback?>(null)

    /**
     * The account's playback when it is happening somewhere else, null when it
     * is happening here or not at all.
     *
     * Deliberately null for this device: what plays here is the app's own
     * player, and two sources for one truth is how a seek bar ends up fighting
     * itself.
     */
    val playback: StateFlow<RemotePlayback?> = _playback.asStateFlow()

    private val _elsewhere = MutableStateFlow(false)

    /**
     * Whether the account's playback belongs to another device.
     *
     * The engine's own answer, kept here so the screen can read it without a
     * JNI call on every frame. This is the one fact everything else is decided
     * from, which is how go-librespot is built: one boolean, and no guessing
     * around it.
     */
    val elsewhereActive: StateFlow<Boolean> = _elsewhere.asStateFlow()

    private val _here = MutableStateFlow<RemotePlayback?>(null)

    /**
     * The account's playback when this phone is the one doing it.
     *
     * The mirror image of [playback], and it exists because being handed
     * playback is something that happens *to* this app: another client presses
     * "Square" in its own device list, Spotify sends the engine a transfer, and
     * the engine starts playing a track the app knows nothing about. This is
     * where it finds out what it is playing and, more importantly, what queue it
     * came from.
     */
    val here: StateFlow<RemotePlayback?> = _here.asStateFlow()

    private val _devices = MutableStateFlow<List<RemoteDevice>>(emptyList())

    /** Everything the account can play on, this phone included. */
    val devices: StateFlow<List<RemoteDevice>> = _devices.asStateFlow()

    /** Whether an id is this phone's own. */
    fun isThisPhone(deviceId: String): Boolean {
        if (ownId.isEmpty()) ownId = NativeBridge.deviceId()
        return deviceId.isNotEmpty() && deviceId == ownId
    }

    /** Called on every `cluster` event from the engine. */
    fun refresh() {
        if (ownId.isEmpty()) {
            ownId = NativeBridge.deviceId()
            android.util.Log.i(TAG, "this device is $ownId")
        }

        _devices.value = runCatching { parseDevices(NativeBridge.remoteDevices()) }
            .getOrDefault(emptyList())

        val state = runCatching { JSONObject(NativeBridge.remoteState()) }.getOrNull()
        val active = state?.optString("activeDevice").orEmpty()
        val playback = state?.let { read(it, active) }?.filled()

        // Asked of the engine, not worked out from the id in the update.
        //
        // The two disagree exactly when it matters: after playback is handed to
        // this phone it starts playing at once, while the account goes on naming
        // the device it came from until that device lets go. Comparing ids meant
        // the app believed the account and not its own ears.
        val elsewhere = NativeBridge.playbackElsewhere
        _elsewhere.value = elsewhere
        _playback.value = playback?.takeIf { elsewhere }
        _here.value = playback?.takeIf { !elsewhere && active.isNotEmpty() }
    }

    private fun read(state: JSONObject, active: String): RemotePlayback? {
        if (active.isEmpty()) return null
        return RemotePlayback(
            deviceId = active,
            deviceName = state.optString("deviceName"),
            uri = state.optString("uri"),
            title = state.optString("title"),
            artist = state.optString("artist"),
            album = state.optString("album"),
            coverUri = state.optString("coverUri"),
            contextUri = state.optString("contextUri"),
            positionMs = state.optLong("positionMs"),
            durationMs = state.optLong("durationMs"),
            playing = state.optBoolean("playing"),
            shuffle = state.optBoolean("shuffle"),
            repeatContext = state.optBoolean("repeatContext"),
            repeatTrack = state.optBoolean("repeatTrack"),
        )
    }

    /**
     * Replaces what is known about the playback elsewhere, with more of it.
     *
     * The cluster carries a track's title and artist as metadata beside the
     * uri, and whether it carries them at all is up to whichever client is
     * playing: some publish everything, some publish the uri and little else.
     * The uri is always there, so the rest can be looked up; this is where the
     * answer comes back.
     */
    fun describe(playback: RemotePlayback) {
        known[playback.uri] = playback
        if (_playback.value?.uri != playback.uri) return
        _playback.value = playback
    }

    /**
     * Titles, artists and covers looked up for tracks the cluster named without
     * describing, kept by uri.
     *
     * Remembered here rather than only where the answer was needed, because
     * every cluster update rebuilds the state from what the other device
     * published: without this the notification kept the filled-in version while
     * the screen, reading the same flow a moment later, went back to a song
     * with no artist.
     */
    private val known = mutableMapOf<String, RemotePlayback>()

    /** What was learned about this track earlier, over what arrived now. */
    private fun RemotePlayback.filled(): RemotePlayback {
        val earlier = known[uri] ?: return this
        return copy(
            title = title.ifEmpty { earlier.title },
            artist = artist.ifEmpty { earlier.artist },
            album = album.ifEmpty { earlier.album },
            coverUri = coverUri.ifEmpty { earlier.coverUri },
        )
    }

    /** Forgets everything. For a session ending, where none of it is true any more. */
    fun clear() {
        _elsewhere.value = false
        _playback.value = null
        _here.value = null
        _devices.value = emptyList()
    }

    private fun parseDevices(json: String): List<RemoteDevice> {
        val array = JSONArray(json)
        return (0 until array.length()).map { index ->
            val device = array.getJSONObject(index)
            val id = device.optString("id")
            RemoteDevice(
                id = id,
                name = device.optString("name"),
                type = device.optString("type"),
                volume = device.optInt("volume"),
                active = device.optBoolean("active"),
                isThisPhone = id == ownId,
            )
        }
    }

    // --- Commands ---
    //
    // Worded the way Spotify's own clients word them, because that is what the
    // device on the other end is written to obey. Each one blocks on a request
    // to the access point, so none of them may be called from the main thread.

    fun play(deviceId: String) = command(deviceId, "resume")

    fun pause(deviceId: String) = command(deviceId, "pause")

    fun next(deviceId: String) = command(deviceId, "skip_next")

    fun previous(deviceId: String) = command(deviceId, "skip_prev")

    fun seek(deviceId: String, positionMs: Long) =
        send(deviceId, """{"command":{"endpoint":"seek_to","value":$positionMs}}""")

    fun setShuffle(deviceId: String, shuffle: Boolean) =
        send(deviceId, """{"command":{"endpoint":"set_shuffling_context","value":$shuffle}}""")

    fun setRepeat(deviceId: String, context: Boolean, track: Boolean) {
        send(
            deviceId,
            """{"command":{"endpoint":"set_options","repeating_context":$context,""" +
                """"repeating_track":$track}}""",
        )
    }

    fun setVolume(deviceId: String, volume: Int) {
        runCatching { NativeBridge.remoteVolume(deviceId, volume) }
            .onFailure { android.util.Log.w(TAG, "volume did not reach $deviceId", it) }
    }

    /**
     * Moves playback to another device, this phone included.
     *
     * The command goes *to* the device that should take over, which is how the
     * protocol reads: a device is told to take the account's playback, not told
     * to give it away.
     */
    fun transferTo(deviceId: String) {
        if (deviceId == ownId) {
            // Coming back here is not a request to the server. A device cannot
            // address a command to itself: it goes out to the access point and
            // comes back refused, which is why choosing Square in the list did
            // nothing at all.
            runCatching { NativeBridge.takeOver() }
                .onFailure { android.util.Log.w(TAG, "could not take playback back", it) }
            return
        }
        send(
            deviceId,
            """{"command":{"endpoint":"transfer","transfer_options":{"restore_paused":"restore"}}}""",
        )
    }

    private fun command(deviceId: String, endpoint: String) =
        send(deviceId, """{"command":{"endpoint":"$endpoint"}}""")

    private fun send(deviceId: String, body: String) {
        runCatching { NativeBridge.remoteCommand(deviceId, body) }
            .onFailure { android.util.Log.w(TAG, "command did not reach $deviceId: $body", it) }
    }

    private const val TAG = "RemoteConnect"
}

/** Playback happening on another of the account's devices. */
data class RemotePlayback(
    val deviceId: String,
    val deviceName: String,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    /**
     * The cover as the cluster carries it, which is a plain https URL to
     * i.scdn.co and not the `spotify:image:` form the rest of the protocol uses.
     */
    val coverUri: String,
    /** The playlist or album the other device is playing from, if any. */
    val contextUri: String,
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val shuffle: Boolean,
    val repeatContext: Boolean,
    val repeatTrack: Boolean,
) {
    /**
     * The cover as something that can actually be fetched.
     *
     * The cluster sends a URL already. It was being treated as a
     * `spotify:image:` uri and cut at the last colon, which turned
     * `https://i.scdn.co/…` into `//i.scdn.co/…` and fetched nothing: the
     * artwork went missing everywhere the moment another device took over.
     * Both forms are accepted, since only one of them is documented anywhere
     * and neither is promised.
     */
    val coverUrl: String
        get() = when {
            coverUri.isEmpty() -> ""
            coverUri.startsWith("http") -> coverUri
            else -> "https://i.scdn.co/image/${coverUri.substringAfterLast(':')}"
        }

    /**
     * The playlist or album behind this playback, or null when there is none.
     *
     * `spotify:web-api` is not one. It is the placeholder librespot publishes
     * for a queue handed over as a bare list of track URIs, and asking Spotify
     * to resolve it answers 400: it was taken for a playlist twice, once when
     * bringing playback back to this phone and once when filling in a queue
     * handed to it, and both simply failed.
     */
    val realContext: String?
        get() = contextUri.takeIf { it.startsWith("spotify:") && it != WEB_API_CONTEXT }
}

/** What librespot publishes in place of a context it does not have. */
const val WEB_API_CONTEXT = "spotify:web-api"

/** One device the account can play on. */
data class RemoteDevice(
    val id: String,
    val name: String,
    /** librespot's own spelling of the device type: `Smartphone`, `Computer`. */
    val type: String,
    /** Raw 0..65535, as everywhere else in the protocol. */
    val volume: Int,
    val active: Boolean,
    val isThisPhone: Boolean,
)
