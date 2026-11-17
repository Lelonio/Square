package dev.lelonio.square.playback.soloist

import android.content.Context
import android.util.Log
import dev.lelonio.square.playback.StreamQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Spotify Soloist, Spotify's own headless player, run inside this app.
 *
 * Soloist is a glibc program; it runs on a glibc of our own (Debian's, with
 * the few system calls Android's app sandbox refuses answered the way glibc
 * already handles), its audio comes through our libpulse.so.0 into
 * [SoloistAudio] and Square's own output, and it is driven two ways:
 *
 *  - its local WebSocket, for everything it says about itself (what is
 *    playing, where, paused or not) and for the transport (pause, resume,
 *    seek, skip, shuffle, repeat);
 *  - Spotify Connect, through the Spotify engine's session, for starting a
 *    queue: the WebSocket's `play` takes one URI, and a queue is a list, a
 *    place in it and a time.
 *
 * Soloist logs in once, by being picked in a Spotify app (Connect pairing);
 * after that it restores the session by itself on every start.
 */
object SoloistEngine {
    private const val TAG = "Soloist"

    /** The position Soloist reported, and the moment it was true. */
    data class Anchor(val positionMs: Long, val atMs: Long, val speed: Double) {
        fun now(): Long =
            positionMs + ((System.currentTimeMillis() - atMs).coerceAtLeast(0) * speed).toLong()
    }

    /** A song as Soloist describes it, with what the player shows of it. */
    data class Song(
        val uri: String,
        val name: String = "",
        val artists: List<Pair<String, String?>> = emptyList(),
        val album: String = "",
        val albumUri: String? = null,
        val coverUrl: String? = null,
        val durationMs: Long = 0,
        val explicit: Boolean = false,
    )

    /** One place in Soloist's queue: [source] is context, queue or autoplay. */
    data class QueueEntry(val uid: String, val source: String, val song: Song)

    data class State(
        val running: Boolean = false,
        val loggedIn: Boolean = false,
        val active: Boolean = false,
        /** idle, playing, paused or buffering, as Soloist says it. */
        val status: String = "idle",
        val uri: String? = null,
        /** What Soloist plays the song from: a playlist, an album, or our own list. */
        val contextUri: String? = null,
        val durationMs: Long = 0,
        val anchor: Anchor? = null,
        val shuffle: Boolean = false,
        val repeat: String = "off",
        /** Bumped on every track_changed, so the same URI twice is still news. */
        val trackSerial: Int = 0,
        /** The song playing, described. */
        val song: Song? = null,
        /** Soloist's queue: what played, newest first, and what comes, next first. */
        val previous: List<QueueEntry> = emptyList(),
        val upcoming: List<QueueEntry> = emptyList(),
    ) {
        val playing get() = status == "playing" || status == "buffering"
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Soloist's Connect device id, once it has said it. */
    @Volatile
    var deviceId: String? = null
        private set

    private lateinit var app: Context
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var socket: WebSocket? = null

    private var codec: String? = null
    private var bitrate: Int? = null

    // ------------------------------------------------------------- settings

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("soloist", Context.MODE_PRIVATE)

    /**
     * Whether Soloist plays: chosen by the listener, and with all it needs (a
     * key, the binary, an account it signed in to). Chosen and not yet signed
     * in, librespot plays and Soloist waits on the network for the sign-in.
     */
    fun isEnabled(context: Context): Boolean = isWanted(context) && isReady(context) && _failed.value == null

    /**
     * Why Soloist was given up on for now, librespot playing meanwhile: it
     * would not stay up, or its build ran out. Cleared by anything that may
     * have mended it (a key, a new build, the choice made again, a new run of
     * the app).
     */
    private val _failed = MutableStateFlow<String?>(null)
    val failed: StateFlow<String?> = _failed.asStateFlow()

    /** Exits in a row, each soon after it started. */
    @Volatile
    private var crashes = 0

    private fun giveUp(reason: String?) {
        Log.w(TAG, "giving up on Soloist for now: $reason")
        _failed.value = reason ?: "Soloist stopped"
        refresh(app)
        // A build past its date is the likeliest cause, and the cure.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            SoloistDownload.fetch(app, force = true)
        }
    }

    /** Something changed that may let Soloist run again. */
    fun retry(context: Context) {
        crashes = 0
        if (_failed.value == null) return
        _failed.value = null
        refresh(context)
    }

    /** The listener's choice, kept from the moment it is made, the setup half done or not. */
    fun isWanted(context: Context): Boolean = prefs(context).getBoolean("enabled", false)

    /** A key and the binary: all Soloist needs to start. */
    fun canStart(context: Context): Boolean =
        SoloistRuntime.isInstalled(context) && SoloistRuntime.key(context).isNotEmpty()

    /** Able to start, and signed in once: Soloist keeps its credentials. */
    fun isReady(context: Context): Boolean = canStart(context) && signedInAccount(context) != null

    /** The account Soloist last signed in to, remembered across its restarts. */
    fun signedInAccount(context: Context): String? = prefs(context).getString("account", null)

    private val _enabled = MutableStateFlow<Boolean?>(null)
    private val _wanted = MutableStateFlow<Boolean?>(null)

    /** [isEnabled] as it changes, for the playback service to swap players on. */
    fun enabled(context: Context): StateFlow<Boolean?> {
        if (_enabled.value == null) refresh(context)
        return _enabled.asStateFlow()
    }

    /** [isWanted] as it changes, for the setup to show. */
    fun wanted(context: Context): StateFlow<Boolean?> {
        if (_wanted.value == null) refresh(context)
        return _wanted.asStateFlow()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        if (enabled) retry(context)
        val playing = _enabled.value == true
        prefs(context).edit().putBoolean("enabled", enabled).apply()
        refresh(context)
        // Let go of while it was only waiting for its sign-in: nothing plays
        // through it, so it simply stops. (Playing, the service stops it once
        // librespot has taken the queue back.)
        if (!enabled && !playing) stop()
    }

    /** Re-reads what Soloist has, after a key, a download or a sign-in. */
    fun refresh(context: Context) {
        _wanted.value = isWanted(context)
        _enabled.value = isEnabled(context)
    }

    /** What the setup shows: the account Soloist is in, its build's days left, what went wrong. */
    data class Setup(
        val account: String? = null,
        val expiresInDays: Int? = null,
        val error: String? = null,
    )

    private val _setup = MutableStateFlow(Setup())

    /** The account remembered from an earlier run, until Soloist says it again. */
    fun rememberedSetup(context: Context): Setup =
        _setup.value.let { if (it.account == null) it.copy(account = signedInAccount(context)) else it }
    val setup: StateFlow<Setup> = _setup.asStateFlow()

    /**
     * The stream quality, as Soloist's prefs number it: 5 is lossless, 4 very
     * high, 3 high, 2 normal. Read by Soloist when it starts.
     */
    fun quality(context: Context): Int = prefs(context).getInt("quality", 5)

    // ------------------------------------------------------------- lifecycle

    @Synchronized
    fun start(context: Context) {
        app = context.applicationContext
        dev.lelonio.square.playback.HiFi.init(app)
        if (process?.isAlive == true) return
        thread(name = "soloist-launch") { runCatching { launch() }.onFailure { Log.e(TAG, "start failed", it) } }
    }

    @Synchronized
    fun stop() {
        socket?.close(1000, null)
        socket = null
        process?.let { running ->
            running.destroy()
            runCatching { running.waitFor(3, TimeUnit.SECONDS) }
            if (running.isAlive) running.destroyForcibly()
        }
        process = null
        _state.value = State()
        StreamQuality.clear()
        if (::app.isInitialized) ZeroConf.withdraw(app)
    }

    /**
     * Stops Soloist and starts it again, for what it reads only on start (the
     * stream quality). The session is restored by Soloist itself; what was
     * playing is put back by whoever drives it, which [restarted] tells.
     */
    fun restart(context: Context) {
        stop()
        start(context)
    }

    /** Bumped when a restart has brought Soloist back logged in. */
    private val _restarted = MutableStateFlow(0)
    val restarted: StateFlow<Int> = _restarted.asStateFlow()
    private var restarting = false

    /** Settings Soloist reads only on start (the crossfade): restarted, the song carries on. */
    fun reconfigure(context: Context) {
        if (process?.isAlive != true) return
        restarting = true
        restart(context)
    }

    fun setQuality(context: Context, level: Int) {
        prefs(context).edit().putInt("quality", level).apply()
        restarting = true
        restart(context)
    }

    private fun launch() {
        val dir = SoloistRuntime.install(app)
        if (!SoloistRuntime.binary(app).exists()) {
            Log.w(TAG, "Soloist not downloaded")
            return
        }
        // A newer build, when Spotify has one: Soloist builds stop working
        // about ninety days after release. Taken at the next start.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            SoloistDownload.fetch(app)
        }
        SoloistRuntime.writeResolver(app, dir)
        val crossfadeMs = dev.lelonio.square.data.CrossfadeStore(app).durationMs()
        SoloistRuntime.writeQuality(dir, quality(app))
        val fifo = File(app.cacheDir, "soloist-pcm")
        SoloistAudio.open(fifo)

        val key = SoloistRuntime.key(app)
        if (key.isEmpty()) {
            Log.w(TAG, "no Soloist key")
            return
        }
        val builder = ProcessBuilder(
            SoloistRuntime.command(app, dir) + listOf(
                "-n", "Square",
                "-D", File(dir, "data").path,
                "-C", File(dir, "cache").path,
                "-z", "300",
                "-i", "100",
                "-w", "127.0.0.1:0",
                "-v",
                "-k", key,
            ),
        ).directory(dir).redirectErrorStream(true)
        SoloistRuntime.environment(app, dir, builder.environment())
        builder.environment()["SQUARE_PCM_FIFO"] = fifo.path
        // The listener's skip fade, done by our libpulse as a real dissolve:
        // the song being left keeps playing under the new one.
        // And the dissolve into a song Square starts itself (a song queued by
        // hand): the skip fade when it is on, else the crossfade, as far as
        // what is kept ahead of the old song allows.
        val skipFade = (app as? dev.lelonio.square.SquareApplication)?.preferences?.skipFadeMs() ?: 0
        val dissolve = if (skipFade > 0) skipFade else crossfadeMs.coerceAtMost(1500)
        builder.environment()["SQUARE_XFADE_MS"] = dissolve.toString()
        // Soloist has no crossfade of its own to turn on (its setting lives in
        // the account's device settings now): Square makes every transition
        // itself, and libpulse keeps this much of the old song ahead for it.
        builder.environment()["SQUARE_XFADE_MAX_MS"] = maxOf(skipFade, crossfadeMs).coerceAtMost(12_000).toString()
        builder.environment()["SQUARE_XFADE_FLAG"] = skipFlag(app).path

        val started = builder.start()
        val startedAt = System.currentTimeMillis()
        process = started
        _state.update { it.copy(running = true) }
        Log.i(TAG, "started")
        val recent = ArrayDeque<String>()
        runCatching {
            started.inputStream.bufferedReader().forEachLine { line ->
                recent.addLast(line)
                if (recent.size > 30) recent.removeFirst()
                readLog(line)
            }
        }
        val code = runCatching { started.waitFor() }.getOrNull()
        Log.i(TAG, "exited $code")
        // What it said last, when it did not mean to stop.
        val unasked = process === started
        var said: String? = null
        if (code != 0 && unasked) {
            recent.forEach { Log.w(TAG, "| $it") }
            said = (recent.lastOrNull { " E [" in it || "rror" in it } ?: recent.lastOrNull())
                ?.replace(colour, "")?.substringAfter("] ")?.trim()
            _setup.update { it.copy(error = said) }
        }
        ZeroConf.withdraw(app)
        if (unasked) {
            process = null
            _state.value = State()
            StreamQuality.clear()
        }
        // Not asked to stop: up again, once, unless it keeps falling over
        // soon after starting or its build has run out; then librespot plays
        // until something changes.
        if (unasked && isWanted(app)) {
            val soon = System.currentTimeMillis() - startedAt < 30_000
            crashes = if (soon) crashes + 1 else 1
            val expired = (_setup.value.expiresInDays ?: 1) <= 0
            if (expired || crashes >= 2) {
                giveUp(said)
            } else {
                Thread.sleep(2000)
                start(app)
            }
        }
    }

    private val zeroconfLine = Regex("""ZeroConf service on port (\d+) path (\S+)""")
    private val expiresLine = Regex("""client expires in (-?\d+) days""")
    private val accountLine = Regex("""logged in as (\S+)""")
    private val deviceLine = Regex("""device_id=([0-9a-f-]{8,})""")
    private val socketLine = Regex("""websocket server listening on 127\.0\.0\.1:(\d+)""")
    private val codecLine = Regex("""\bcodec: (\w+)""")
    private val bitrateLine = Regex("""FileStreamer file average bitrate: (\d+)""")

    /** What Soloist says about itself that its WebSocket does not. */
    private val colour = Regex("\u001B\\[[0-9;]*m")

    private fun readLog(raw: String) {
        // Soloist colours its warnings: the codes are not part of what it says.
        val line = raw.replace(colour, "")
        if (line.startsWith("SQ") || " E [" in line || " W [" in line) Log.i(TAG, line)
        deviceLine.find(line)?.let {
            deviceId = it.groupValues[1]
            dev.lelonio.square.data.RemoteConnect.registerSdkDevice(it.groupValues[1])
        }
        socketLine.find(line)?.let { connect(it.groupValues[1].toInt()) }
        // Soloist's own mDNS finds no IPv4 address in the app sandbox: the
        // device is announced for it, so the Spotify app sees it to sign in.
        zeroconfLine.find(line)?.let { ZeroConf.announce(app, it.groupValues[1].toInt(), it.groupValues[2]) }
        expiresLine.find(line)?.let { m -> _setup.update { it.copy(expiresInDays = m.groupValues[1].toInt()) } }
        expiresLine.find(line)?.let { m ->
            if (m.groupValues[1].toInt() <= 0) Log.w(TAG, "this Soloist build has expired")
        }
        accountLine.find(line)?.let { m ->
            _setup.update { it.copy(account = m.groupValues[1], error = null) }
            // Signed in: with Soloist chosen, it is the engine from here on.
            prefs(app).edit().putString("account", m.groupValues[1]).apply()
            refresh(app)
        }
        // The bitrate is logged as the file opens, before the codec.
        bitrateLine.find(line)?.let {
            val value = it.groupValues[1].toInt()
            if (value > 0) {
                bitrate = value
                StreamQuality.stream(codec, value)
            }
        }
        codecLine.find(line)?.let {
            codec = it.groupValues[1]
            StreamQuality.stream(codec, bitrate)
        }
        if ("logged in as" in line && restarting) {
            restarting = false
            _restarted.update { it + 1 }
        }
    }

    /**
     * Present for the moment of a skip Square asked for: libpulse then takes
     * Soloist's cork as the change of song it is, not a pause, and dissolves
     * the old song into the new one.
     */
    fun skipFlag(context: Context) = File(context.applicationContext.cacheDir, "soloist-skip")

    // ------------------------------------------------------------- WebSocket

    private fun connect(port: Int) {
        val request = Request.Builder().url("ws://127.0.0.1:$port").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                queueAsked = 0
                webSocket.send(JSONObject().put("type", "command").put("command", "get_queue").put("limit", 0).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { onEvent(JSONObject(text)) }
                    .onFailure { Log.w(TAG, "event not understood: $text", it) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "socket failed: $t")
                if (socket === webSocket) socket = null
            }
        })
    }

    private fun onEvent(event: JSONObject) {
        when (event.optString("type")) {
            "auth_state" -> _state.update {
                it.copy(loggedIn = event.optBoolean("logged_in"), active = event.optBoolean("is_active"))
            }
            "playback_state" -> {
                val item = event.optJSONObject("item")
                val options = event.optJSONObject("options")
                _state.update {
                    it.copy(
                        loggedIn = true,
                        status = event.optString("status", it.status),
                        uri = item?.optString("uri")?.takeIf(String::isNotEmpty),
                        song = item?.let(::song)?.takeIf { s -> s.uri.isNotEmpty() },
                        contextUri = event.optJSONObject("context")?.optString("uri")?.takeIf(String::isNotEmpty),
                        durationMs = duration(item),
                        anchor = anchor(event.optJSONObject("position")) ?: it.anchor,
                        active = event.optBoolean("is_active", it.active),
                        shuffle = options?.optBoolean("shuffle", it.shuffle) ?: it.shuffle,
                        repeat = options?.optString("repeat", it.repeat) ?: it.repeat,
                    )
                }
            }
            "track_changed" -> {
                val item = event.optJSONObject("item")
                _state.update {
                    it.copy(
                        uri = item?.optString("uri")?.takeIf(String::isNotEmpty),
                        song = item?.let(::song)?.takeIf { s -> s.uri.isNotEmpty() },
                        durationMs = duration(item),
                        trackSerial = it.trackSerial + 1,
                    )
                }
                askQueue()
            }
            "playback_changed" -> _state.update { it.copy(status = event.optString("status", it.status)) }
            "position_sync" -> anchor(event.optJSONObject("position"))?.let { a -> _state.update { it.copy(anchor = a) } }
            "device_changed" -> _state.update { it.copy(active = event.optBoolean("is_active", it.active)) }
            "context_changed" -> _state.update {
                it.copy(contextUri = event.optJSONObject("context")?.optString("uri")?.takeIf(String::isNotEmpty))
            }
            "options_changed" -> event.optJSONObject("options")?.let { options ->
                _state.update {
                    it.copy(
                        shuffle = options.optBoolean("shuffle", it.shuffle),
                        repeat = options.optString("repeat", it.repeat),
                    )
                }
            }
            "queue_changed" -> {
                val previous = entries(event.optJSONArray("previous"))
                val upcoming = entries(event.optJSONArray("upcoming"))
                _state.update { it.copy(previous = previous, upcoming = upcoming) }
                Log.i(TAG, "queue: ${previous.size} before, ${upcoming.size} next (${upcoming.take(3).joinToString { it.source + " " + it.song.name }})")
                // A broadcast is cut at ten either way; the whole of it is asked
                // for once it has changed.
                if (previous.size >= 10 || upcoming.size >= 10) askQueue()
            }
            "error" -> Log.w(TAG, "Soloist says: ${event.optString("message")}")
        }
    }

    private var queueAsked = 0L

    /** The whole queue, not the ten a broadcast carries. At most once a second. */
    fun askQueue() {
        val now = System.currentTimeMillis()
        if (now - queueAsked < 1000) return
        queueAsked = now
        command("get_queue", "limit" to 0)
    }

    private fun entries(list: JSONArray?): List<QueueEntry> =
        (0 until (list?.length() ?: 0)).mapNotNull { i ->
            val entry = list!!.optJSONObject(i) ?: return@mapNotNull null
            val item = entry.optJSONObject("item") ?: return@mapNotNull null
            val described = song(item)
            if (described.uri.isEmpty()) return@mapNotNull null
            QueueEntry(entry.optString("uid", "$i:${described.uri}"), entry.optString("source"), described)
        }

    /** A song out of Soloist's entity envelope. */
    private fun song(item: JSONObject): Song {
        val decorations = item.optJSONObject("decorations")
        fun nameOf(entity: JSONObject?) =
            entity?.optJSONObject("decorations")?.optJSONObject("identity")?.optString("name").orEmpty()
        val covers = decorations?.optJSONObject("visual_identity")?.optJSONArray("cover")
        val cover = (0 until (covers?.length() ?: 0)).mapNotNull { covers!!.optJSONObject(it) }
            .sortedBy { listOf("xlarge", "large", "default", "small").indexOf(it.optString("size")).let { r -> if (r < 0) 9 else r } }
            .firstOrNull()?.optString("url")?.takeIf(String::isNotEmpty)
        val creators = decorations?.optJSONArray("creators")
        val artists = (0 until (creators?.length() ?: 0)).mapNotNull { i ->
            val entity = creators!!.optJSONObject(i)?.optJSONObject("entity") ?: return@mapNotNull null
            nameOf(entity).takeIf(String::isNotEmpty)?.let { it to entity.optString("uri").takeIf(String::isNotEmpty) }
        }
        val parent = decorations?.optJSONObject("parent")?.optJSONObject("entity")
        val playback = decorations?.optJSONObject("playback")
        val ratings = playback?.optJSONArray("content_ratings")
        val explicit = (0 until (ratings?.length() ?: 0)).any {
            val rating = ratings!!.opt(it)
            (rating as? String ?: (rating as? JSONObject)?.toString()).orEmpty().contains("explicit", ignoreCase = true)
        }
        return Song(
            uri = item.optString("uri"),
            name = decorations?.optJSONObject("identity")?.optString("name").orEmpty(),
            artists = artists,
            album = nameOf(parent),
            albumUri = parent?.optString("uri")?.takeIf(String::isNotEmpty),
            coverUrl = cover,
            durationMs = playback?.optLong("duration_ms") ?: 0,
            explicit = explicit,
        )
    }

    private fun duration(item: JSONObject?): Long =
        item?.optJSONObject("decorations")?.optJSONObject("playback")?.optLong("duration_ms") ?: 0L

    private fun anchor(position: JSONObject?): Anchor? = position?.let {
        if (!it.has("position_ms")) return null
        Anchor(it.optLong("position_ms"), it.optLong("timestamp_ms", System.currentTimeMillis()), it.optDouble("speed", 1.0))
    }

    private fun command(name: String, vararg fields: Pair<String, Any>) {
        val message = JSONObject().put("type", "command").put("command", name)
        fields.forEach { (k, v) -> message.put(k, v) }
        val sent = socket?.send(message.toString()) ?: false
        if (!sent) Log.w(TAG, "not sent, no socket: $name")
    }

    fun resume() = command("play")
    fun pause() = command("pause")
    fun next() = command("skip_next")
    fun previous() = command("skip_prev")
    fun seek(positionMs: Long) = command("seek", "position_ms" to positionMs)
    fun setShuffle(on: Boolean) = command("set_shuffle", "enabled" to on)

    /**
     * Straight to one song of Soloist's queue, as Spotify's apps jump to a song
     * in the list of what is next: a Connect skip naming it.
     */
    fun skipTo(entry: QueueEntry): Boolean {
        val device = deviceId ?: return false
        val track = JSONObject().put("uri", entry.song.uri).put("uid", entry.uid)
        val body = JSONObject().put("command", JSONObject().put("endpoint", "skip_next").put("track", track))
        return runCatching { dev.lelonio.square.nativecore.NativeBridge.remoteCommand(device, body.toString()) }
            .onFailure { Log.w(TAG, "skip did not reach Soloist", it) }
            .isSuccess
    }

    /** A track into Soloist's own queue, played after the current one. */
    fun addToQueue(uri: String) = command("add_to_queue", "uri" to uri)

    fun setRepeat(mode: String) {
        when (mode) {
            "track" -> { command("set_repeat_context", "enabled" to false); command("set_repeat_track", "enabled" to true) }
            "context" -> { command("set_repeat_track", "enabled" to false); command("set_repeat_context", "enabled" to true) }
            else -> { command("set_repeat_track", "enabled" to false); command("set_repeat_context", "enabled" to false) }
        }
    }

    /**
     * Starts a list of songs on Soloist, at one of them and a time.
     *
     * A Connect `play` from the Spotify engine's session, with the songs as
     * the context's own pages: what Spotify's apps send a speaker, and what
     * the WebSocket, which takes one URI, cannot say. Blocking; call it off
     * the main thread.
     */
    fun playList(uris: List<String>, index: Int, positionMs: Long, paused: Boolean, source: String? = null): Boolean {
        val tracks = JSONArray().apply { uris.forEach { put(JSONObject().put("uri", it)) } }
        // Named after where the songs came from, so another device the
        // playback is moved to can load it: a context only this app knows
        // (spotify:internal:...) cannot be opened anywhere else, and a transfer
        // of it failed. The songs and their order are the pages either way.
        // A song's own URI as the name made Soloist play that song alone and
        // drop the pages: a list is named after where it came from, or after
        // this app's own list.
        val name = source ?: LIST_CONTEXT
        val context = JSONObject()
            .put("uri", name)
            .put("url", "context://$name")
            .put("metadata", JSONObject())
            .put("pages", JSONArray().put(JSONObject().put("tracks", tracks)))
        return play(context, uris[index], index, positionMs, paused)
    }

    /**
     * Starts a playlist, an album or an artist on Soloist as Spotify knows it,
     * at one of its songs: the account then shows that context everywhere, the
     * desktop app included, and Soloist finds it again when it next starts.
     */
    fun playContext(contextUri: String, uris: List<String>, index: Int, positionMs: Long, paused: Boolean): Boolean {
        // The songs go with it as the context's pages: left to resolve the
        // playlist itself, Soloist at times played the song alone with
        // nothing after it (the playlist's first one, every time).
        val tracks = JSONArray().apply { uris.forEach { put(JSONObject().put("uri", it)) } }
        val context = JSONObject()
            .put("uri", contextUri)
            .put("url", "context://$contextUri")
            .put("metadata", JSONObject())
            .put("pages", JSONArray().put(JSONObject().put("tracks", tracks)))
        return play(context, uris[index], index, positionMs, paused)
    }

    /**
     * Soloist's queue in a new order, or with songs taken out: a Connect
     * `set_queue` to its device, as the Spotify app sends when its queue is
     * edited. [previous] oldest first, [next] next first. Blocking; call it
     * off the main thread.
     */
    fun setQueue(previous: List<QueueEntry>, next: List<QueueEntry>): Boolean {
        val device = deviceId ?: return false
        val revision = runCatching {
            JSONObject(dev.lelonio.square.nativecore.NativeBridge.remoteState())
                .takeIf { it.optString("activeDevice") == device }
                ?.optString("queueRevision")
        }.getOrNull().orEmpty()
        fun tracks(entries: List<QueueEntry>) = JSONArray().apply {
            entries.forEach { entry ->
                put(
                    JSONObject()
                        .put("uri", entry.song.uri)
                        .put("uid", entry.uid)
                        .put("provider", entry.source)
                        .put(
                            "metadata",
                            JSONObject().apply { if (entry.source == "queue") put("is_queued", "true") },
                        ),
                )
            }
        }
        val body = JSONObject().put(
            "command",
            JSONObject()
                .put("endpoint", "set_queue")
                .put("next_tracks", tracks(next))
                .put("prev_tracks", tracks(previous))
                .put("queue_revision", revision)
                .put("logging_params", JSONObject()),
        )
        Log.i(TAG, "queue set: ${previous.size} before, ${next.size} next (revision ${revision.take(8)})")
        return runCatching { dev.lelonio.square.nativecore.NativeBridge.remoteCommand(device, body.toString()) }
            .onFailure { Log.w(TAG, "queue not set: ${it.message}") }
            .isSuccess
    }

    /** Shows a queue edited here until Soloist reports it, which it does a moment later. */
    fun expectQueue(next: List<QueueEntry>) {
        _state.update { it.copy(upcoming = next) }
    }

    /** The list Square plays when it is not a context of Spotify's. */
    const val LIST_CONTEXT = "spotify:internal:square"

    private fun play(context: JSONObject, trackUri: String, index: Int, positionMs: Long, paused: Boolean): Boolean {
        val device = deviceId ?: return false
        Log.i(TAG, "play $trackUri of ${context.optString("uri")} at $positionMs ms${if (paused) ", paused" else ""}")
        val body = JSONObject().put(
            "command",
            JSONObject()
                .put("endpoint", "play")
                .put("context", context)
                .put("play_origin", JSONObject().put("feature_identifier", "square").put("feature_version", "1"))
                .put(
                    "options",
                    JSONObject()
                        // The index is into the pages sent along, so the two agree.
                        .put("skip_to", JSONObject().put("track_index", index).put("track_uri", trackUri))
                        .put("seek_to", positionMs)
                        .put("initially_paused", paused)
                        .put(
                            "player_options_override",
                            JSONObject()
                                .put("shuffling_context", false)
                                .put("repeating_context", false)
                                .put("repeating_track", false),
                        ),
                ),
        )
        // Refused while Soloist's device is still being registered with the
        // account (a 502 right after it logs in): asked again for a while.
        repeat(8) { attempt ->
            val sent = runCatching { dev.lelonio.square.nativecore.NativeBridge.remoteCommand(device, body.toString()) }
            if (sent.isSuccess) return true
            Log.w(TAG, "play did not reach Soloist (try ${attempt + 1}): ${sent.exceptionOrNull()?.message}")
            Thread.sleep(750L * (attempt + 1))
        }
        // Refused throughout: its Connect device is no longer what the account
        // knows (a broken queue, a lost registration). A new run registers it
        // again, and the player sends its queue once it is back; see [restarted].
        Log.w(TAG, "Soloist refused every play: starting it again")
        restarting = true
        restart(app)
        return false
    }
}
