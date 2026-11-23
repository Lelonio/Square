package dev.lelonio.square.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Spotify's Jam: one queue that several accounts play and add to together.
 *
 * Soloist runs a Jam whole once it is in one, as any Spotify device does: it
 * follows the session, plays the shared queue and obeys every member. What is
 * left here is the part a Spotify app does around it, through the same
 * social-connect endpoints, asked as the engine's session: starting a Jam with
 * this phone's Soloist as the device that plays it, joining someone else's from
 * their link, leaving and ending one, and who is in it.
 *
 * Only with Soloist: librespot does not follow a Jam.
 */
object Jam {
    private const val TAG = "Jam"

    data class Member(
        val id: String,
        val name: String,
        val imageUrl: String?,
        val owner: Boolean,
        val me: Boolean,
        val listening: Boolean,
    )

    data class Session(
        val id: String,
        val token: String,
        val members: List<Member>,
        val ownedByMe: Boolean,
        /** The device the Jam plays on, by name. */
        val hostDevice: String?,
    ) {
        /** What is shared to invite someone. */
        val link: String get() = "https://open.spotify.com/socialsession/$token"
    }

    private val _session = MutableStateFlow<Session?>(null)

    /** The Jam this account is in, null when none worth showing; see [refresh]. */
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Started from this phone in this run: shown as a Jam from the moment it
     * is made, before anyone else has joined it. A session of the account's
     * own with nobody else in it otherwise is not one: Spotify keeps one ready
     * for a speaker like Soloist at all times, a new one the moment the last
     * is ended.
     */
    @Volatile
    private var startedHere: String? = null

    /** The token in a Jam link, as shared ("open.spotify.com/socialsession/…") or as a URI. */
    fun tokenIn(text: String?): String? =
        text?.let { Regex("""socialsession[/:]([A-Za-z0-9]{8,})""").find(it)?.groupValues?.get(1) }

    /** The account's Jam as it is now. */
    suspend fun refresh() {
        // Read in the background: what goes wrong here (no engine yet, no
        // network) is not shown under the section as if a button had failed.
        val found = request("GET", "/social-connect/v2/sessions/current", quiet = true)
        val was = _session.value
        val now = found?.let(::parse)?.takeIf(::worthShowing)
        _session.value = now
        // Someone new in a Jam this phone plays: shown what is playing.
        if (now != null && now.ownedByMe && was != null && was.id == now.id && now.members.size > was.members.size) nudge()
    }

    /** A Jam with this phone's Soloist as the device that plays it, or the one already going. */
    suspend fun start(): Boolean = act {
        val device = soloist() ?: return@act fail("no Soloist device")
        val found = request("GET", "/social-connect/v2/sessions/current_or_new?local_device_id=$device&type=REMOTE")
            ?.let(::parse) ?: return@act false
        startedHere = found.id
        _session.value = found
        nudge()
        true
    }

    /**
     * Has Soloist say again what it is playing. A Jam started while a song
     * plays, or a member just in, showed nothing playing to the guests until
     * the host changed something: Soloist publishes its state on a change,
     * and a seek to where it already is, is one.
     */
    private fun nudge() {
        val engine = dev.lelonio.square.playback.soloist.SoloistEngine
        val now = engine.state.value
        if (now.song == null) return
        val at = now.anchor?.let { if (now.playing) it.now() else it.positionMs } ?: return
        engine.seek(at)
    }

    /** Into someone else's Jam, from its link's token, listening on this phone's Soloist. */
    suspend fun join(token: String): Boolean = act {
        val device = soloist() ?: return@act fail("no Soloist device")
        val found = request(
            "POST",
            "/social-connect/v2/sessions/join/$token" +
                "?playback_control=listen_and_control&join_type=deeplinking&local_device_id=$device",
        )?.let(::parse) ?: return@act false
        _session.value = found
        true
    }

    /** Out of a Jam someone else runs. */
    suspend fun leave(): Boolean = act {
        val now = _session.value ?: return@act true
        request("POST", "/social-connect/v3/sessions/${now.id}/leave") ?: return@act false
        _session.value = null
        true
    }

    /** The Jam this phone runs, ended for everyone in it. */
    suspend fun end(): Boolean = act {
        val now = _session.value ?: return@act true
        request("DELETE", "/social-connect/v3/sessions/${now.id}") ?: return@act false
        startedHere = null
        _session.value = null
        true
    }

    private fun worthShowing(session: Session): Boolean =
        !session.ownedByMe || session.members.size > 1 || session.id == startedHere

    private fun soloist(): String? = dev.lelonio.square.playback.soloist.SoloistEngine.deviceId

    private suspend fun act(block: suspend () -> Boolean): Boolean {
        _busy.value = true
        _error.value = null
        return try {
            block()
        } finally {
            _busy.value = false
        }
    }

    private fun fail(why: String): Boolean {
        _error.value = why
        return false
    }

    private suspend fun request(method: String, path: String, quiet: Boolean = false): String? =
        withContext(Dispatchers.IO) {
            runCatching { dev.lelonio.square.nativecore.NativeBridge.spclient(method, path) }
                .onSuccess { if (quiet) _error.value = null }
                .onFailure {
                    val missing = it.message?.contains("404") == true
                    if (quiet) {
                        // No Jam (404) is an answer; anything else is said in the log only.
                        if (missing) _error.value = null else Log.i(TAG, "$method ${path.substringBefore('?')}: ${it.message}")
                    } else {
                        Log.w(TAG, "$method ${path.substringBefore('?')}: ${it.message}")
                        _error.value = it.message
                    }
                }
                .getOrNull()
        }

    private fun parse(body: String): Session? = runCatching {
        val root = JSONObject(body)
        val members = root.optJSONArray("session_members")?.let { list ->
            (0 until list.length()).map { i ->
                val m = list.getJSONObject(i)
                Member(
                    id = m.optString("id"),
                    name = m.optString("display_name").ifEmpty { m.optString("username") },
                    imageUrl = m.optString("image_url").takeIf(String::isNotEmpty),
                    owner = m.optString("id") == root.optString("session_owner_id"),
                    me = m.optBoolean("is_current_user"),
                    listening = m.optBoolean("is_listening"),
                )
            }
        }.orEmpty()
        Session(
            id = root.getString("session_id"),
            token = root.getString("join_session_token"),
            members = members,
            ownedByMe = root.optBoolean("is_session_owner"),
            hostDevice = root.optJSONObject("host_device_info")?.optString("device_name")?.takeIf(String::isNotEmpty),
        )
    }.onFailure { Log.w(TAG, "a session that could not be read: ${it.message}") }.getOrNull()
}
