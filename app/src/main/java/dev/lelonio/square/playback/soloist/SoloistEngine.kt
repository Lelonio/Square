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

    /** Spotify's name for smart shuffle, in the options' modes. */
    private const val SMART_SHUFFLE_MODE = "RECOMMENDATION"

    /** How long a run started for refused plays is given to sign in before another; see [play]. */
    private const val RESTART_PATIENCE_MS = 30_000L

    /** How long a new run is given for Spotify to know its device; see [play]. */
    private const val REGISTER_GRACE_MS = 30_000L

    /** The position Soloist reported, and the moment it was true. */
    data class Anchor(val positionMs: Long, val atMs: Long, val speed: Double) {
        /**
         * The music gone out when this was taken (later, for a song still
         * behind the one heard); not part of what makes two the same.
         */
        var audioAtMs: Double = SoloistAudio.playedAt(atMs)

        /**
         * Where the song is now: as far on from [positionMs] as the music that
         * has gone out since. Not the clock times a speed: the tempo the
         * listener sets is done on the way out of Soloist, and the music moves
         * at what the stretcher makes of it, which is close to the slider and
         * not quite it. Reckoned by the clock, the lyrics drifted from the
         * song between two reports of Soloist's, more the longer it played.
         * Paused (or waiting for data) the music stops, and so does this.
         */
        fun now(): Long {
            if (speed == 0.0) return positionMs
            val gone = SoloistAudio.playedMs() - audioAtMs
            return positionMs + gone.coerceAtLeast(0.0).toLong()
        }
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
        /** Smart shuffle: Spotify's recommendations mixed into the shuffled context. */
        val smartShuffle: Boolean = false,
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

    /** Between a start and its process being there: a second start meanwhile ran a second Soloist. */
    @Volatile
    private var launching = false

    @Synchronized
    fun start(context: Context) {
        app = context.applicationContext
        dev.lelonio.square.playback.HiFi.init(app)
        if (process?.isAlive == true || launching) return
        launching = true
        thread(name = "soloist-launch") {
            try {
                launch()
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
            } finally {
                launching = false
            }
        }
    }

    @Synchronized
    fun stop() {
        socket?.close(1000, null)
        socket = null
        process?.let { running ->
            running.destroy()
            runCatching { running.waitFor(3, TimeUnit.SECONDS) }
            if (running.isAlive) running.destroyForcibly()
            runCatching { running.waitFor(2, TimeUnit.SECONDS) }
        }
        process = null
        // And any other: one the app lost track of holds Soloist's data
        // directory, and every new run stops at once on "another session is
        // running" until the app itself is killed.
        killStrays()
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
    @Volatile private var restarting = false

    /** When a refused play last started Soloist again; see [play]. */
    @Volatile private var restartedForPlayAt = 0L

    /** When the running Soloist was started; see [play]. */
    @Volatile private var runStartedAt = 0L

    /** Counts the plays sent, so an older one still asking gives way; see [play]. */
    private val playGeneration = java.util.concurrent.atomic.AtomicInteger()

    /** Whether the running Soloist was started normalising; see [setNormalisation]. */
    @Volatile private var normalisedWith: Boolean? = null

    private fun normalisationDb(app: Context): Int =
        (app.applicationContext as? dev.lelonio.square.SquareApplication)?.preferences?.normalisationDb?.value
            ?: dev.lelonio.square.data.Normalisation.DEFAULT_DB

    private fun gainFor(db: Int): Float =
        if (db == dev.lelonio.square.data.Normalisation.OFF) 0f else db.toFloat()

    /**
     * The listener's normalisation amount: the gain over Soloist's own
     * normalisation at once, and normalisation itself on or off with a new
     * run, as Soloist reads it only on start (the song carries on).
     */
    fun setNormalisation(context: Context, db: Int) {
        SoloistAudio.output.setFloatGainDb(gainFor(db))
        val on = db != dev.lelonio.square.data.Normalisation.OFF
        if (normalisedWith != null && normalisedWith != on) reconfigure(context)
    }

    /** Settings Soloist reads only on start (the crossfade): restarted, the song carries on. */
    fun reconfigure(context: Context) {
        if (process?.isAlive != true) return
        restarting = true
        restart(context)
    }

    private fun dissolvesFile(context: Context) = File(SoloistRuntime.home(context), "xfade")

    /**
     * How far libpulse keeps the music ahead, and the dissolve into a song
     * Square starts itself: written where libpulse reads them each second, so
     * a change of the crossfade or of the skip fade needs no new run of
     * Soloist (which stopped the music, and was taken for a crash).
     */
    fun writeDissolves(context: Context) {
        val crossfade = dev.lelonio.square.data.CrossfadeStore(context).durationMs()
        val skipFade = (context.applicationContext as? dev.lelonio.square.SquareApplication)?.preferences?.skipFadeMs() ?: 0
        val dissolve = if (skipFade > 0) skipFade else crossfade.coerceAtMost(1500)
        runCatching { dissolvesFile(context).writeText("${aheadMs(context) - 500} $dissolve\n") }
    }

    /** What libpulse keeps ahead of what is heard, as it works it out from [writeDissolves]. */
    fun aheadMs(context: Context): Long {
        val crossfade = dev.lelonio.square.data.CrossfadeStore(context).durationMs()
        val skipFade = (context.applicationContext as? dev.lelonio.square.SquareApplication)?.preferences?.skipFadeMs() ?: 0
        return maxOf(skipFade, crossfade).coerceAtMost(12_000).toLong().coerceAtLeast(1_000) + 500
    }

    fun setQuality(context: Context, level: Int) {
        prefs(context).edit().putInt("quality", level).apply()
        restarting = true
        restart(context)
    }

    /**
     * Kills every Soloist of this app's that is still alive, found by its
     * command line: the app's own processes are the only ones it can see.
     */
    private fun killStrays() {
        val data = "/soloist/data"
        val found = File("/proc").listFiles { f -> f.name.all(Char::isDigit) }.orEmpty().mapNotNull { dir ->
            val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
            if (pid == android.os.Process.myPid()) return@mapNotNull null
            val cmdline = runCatching { File(dir, "cmdline").readBytes() }.getOrNull() ?: return@mapNotNull null
            val args = String(cmdline).split('\u0000')
            pid.takeIf { args.firstOrNull()?.endsWith("libsqld.so") == true && args.any { data in it } }
        }
        if (found.isEmpty()) return
        found.forEach {
            Log.w(TAG, "killing a Soloist left running (pid $it)")
            android.os.Process.sendSignal(it, android.os.Process.SIGNAL_KILL)
        }
        val until = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < until && found.any { File("/proc/$it").exists() }) Thread.sleep(50)
    }

    /**
     * How many megabytes Soloist may keep of what it plays. Lossless songs are
     * 25 to 35 MB each, and the 300 that this was held only the last ten or
     * so: a song heard a moment ago was fetched again. A tenth of the space
     * the phone has free, between those 300 and 2 GB.
     */
    private fun cacheSizeMb(dir: File): Long {
        val free = runCatching { android.os.StatFs(dir.path).availableBytes / (1024 * 1024) }.getOrDefault(0L)
        return (free / 10).coerceIn(300L, 2048L)
    }

    private fun launch() {
        killStrays()
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
        val normalisationDb = normalisationDb(app)
        SoloistRuntime.writeQuality(dir, quality(app), normalisationDb != dev.lelonio.square.data.Normalisation.OFF)
        normalisedWith = normalisationDb != dev.lelonio.square.data.Normalisation.OFF
        SoloistAudio.output.setFloatGainDb(gainFor(normalisationDb))
        val fifo = File(app.cacheDir, "soloist-pcm")
        SoloistAudio.open(fifo)

        val key = SoloistRuntime.key(app)
        if (key.isEmpty()) {
            Log.w(TAG, "no Soloist key")
            return
        }
        val builder = ProcessBuilder(
            SoloistRuntime.command(app, dir) + listOf(
                "-n", dev.lelonio.square.playback.ConnectName.of(app),
                "-D", File(dir, "data").path,
                "-C", File(dir, "cache").path,
                "-z", cacheSizeMb(dir).toString(),
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
        // The same two, read again while Soloist runs: see writeDissolves.
        writeDissolves(app)
        builder.environment()["SQUARE_XFADE_CONF"] = dissolvesFile(app).path

        val started = builder.start()
        val startedAt = System.currentTimeMillis()
        runStartedAt = startedAt
        process = started
        launching = false
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

    private val _automixing = MutableStateFlow(false)

    /**
     * Whether Soloist is playing a mixed playlist as Spotify mixes it (it
     * says so as it works the transitions out): the player wears the mix's
     * button meanwhile. Reset with each play sent.
     */
    val automixing: StateFlow<Boolean> = _automixing.asStateFlow()

    /** Whether Soloist makes its own crossfade between songs; see readLog. */
    @Volatile
    var ownCrossfade = false
        private set

    /** Every line Soloist writes to the log, not only the useful ones: for a probe. */
    private const val VERBOSE_SOLOIST = false

    /** When Soloist was last started again for a sign-in the network failed. */
    @Volatile
    private var offlineRestartAt = 0L

    /**
     * Soloist started while the phone kept the app off the network (asleep,
     * just installed) never signs in afterwards: its resolver stays without an
     * answer ("CURL error: 6") and it does not try again by itself. Started
     * again a little later, with the network's servers read anew.
     */
    private fun afterFailedSignIn() {
        if (_state.value.loggedIn) return
        val now = System.currentTimeMillis()
        if (now - offlineRestartAt < 60_000) return
        offlineRestartAt = now
        Log.w(TAG, "sign-in failed on the network: starting Soloist again shortly")
        thread(name = "soloist-retry") {
            Thread.sleep(10_000)
            if (!_state.value.loggedIn && process?.isAlive == true && isWanted(app)) restart(app)
        }
    }

    private fun readLog(raw: String) {
        // Soloist colours its warnings: the codes are not part of what it says.
        val line = raw.replace(colour, "")
        if ("CURL error: 6" in line || "CURL error: 7" in line) afterFailedSignIn()
        if (VERBOSE_SOLOIST || line.startsWith("SQ") || " E [" in line || " W [" in line || "getAudioTransition" in line || "utomix" in line || "ransition" in line || "uepoint" in line ||
            "arration" in line || "lexicon" in line || " dj" in line || "DJ" in line || "Jam" in line) Log.i(TAG, line)
        // Soloist's own crossfade, from the account's playback settings on
        // Spotify's side, which nothing here can turn off: while it makes
        // one, Square does not make a second over it.
        // The DJ's voice or the music, as Soloist prepares each piece.
        if ("GainCalculator" in line && "is_speech: true" in line) SoloistAudio.speechNext(true)
        if ("GainCalculator" in line && "is_speech: false" in line) SoloistAudio.speechNext(false)
        if ("calculateAutoTransition" in line && line.trimEnd().endsWith("YES")) _automixing.value = true
        if ("getAudioTransition: crossfade" in line) ownCrossfade = true
        if ("getAudioTransition: gapless" in line) ownCrossfade = false
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
                // Each kind of message once, the first time it comes.
                runCatching { JSONObject(text).optString("type") }.getOrNull()?.let { type ->
                    if (unread.add(type)) Log.i(TAG, "Soloist sends $type: ${text.take(300)}")
                }
                runCatching { onEvent(JSONObject(text)) }
                    .onFailure { Log.w(TAG, "event not understood: $text", it) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "socket failed: $t")
                if (socket === webSocket) socket = null
            }
        })
    }

    /**
     * Soloist signed in, as its WebSocket says. Signed in from the Spotify app
     * (the setup's last step), it says so there long before it writes "logged
     * in as" to its log, which it does at its next start: waited for, Soloist
     * became the engine only once the app was closed and opened again.
     */
    private fun signedIn(event: JSONObject) {
        if (signedInAccount(app) != null) return
        val name = listOf("username", "user", "canonical_username", "display_name")
            .firstNotNullOfOrNull { key -> event.optString(key).takeIf(String::isNotEmpty) }
            ?: _setup.value.account ?: "Spotify"
        Log.i(TAG, "signed in")
        _setup.update { it.copy(account = it.account ?: name, error = null) }
        prefs(app).edit().putString("account", name).apply()
        refresh(app)
    }

    /** Types of message seen from Soloist that are not read, said once each. */
    private val unread = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Asks Soloist where it is, every second while it plays: its word on the
     * position is right, and the player is put back on it at each answer.
     * What the player works out between two of Soloist's own reports (a song
     * ending into the next, an automix that brings the next one in at its own
     * cue point, a skip from a Jam's guest) could leave the time and the lyrics
     * off the music until something changed; asked, any such slip is gone
     * within a second.
     */
    private fun onEvent(event: JSONObject) {
        when (event.optString("type")) {
            "auth_state" -> {
                _state.update {
                    it.copy(loggedIn = event.optBoolean("logged_in"), active = event.optBoolean("is_active"))
                }
                if (event.optBoolean("logged_in")) signedIn(event)
            }
            // Soloist's own word on where the song is, sent when it re-reckons
            // it (a seek, a song starting, a transition): the anchor every
            // position on screen is worked out from.
            "position_sync" -> {
                anchor(event.optJSONObject("position"))?.let { at ->
                    _state.update { it.copy(anchor = at) }
                }
            }
            "playback_state", "state", "get_state" -> {
                signedIn(event)
                val item = event.optJSONObject("item")
                // Soloist's position runs on its own clock from its last
                // reckoning, not on the music: at a tempo set in Square it
                // falls behind what is heard, and after a song change it runs
                // from when Soloist started the song, the old song's buffer
                // before it is heard. Only its reckonings (position_sync) and a
                // song's first word are anchors; between them the music's own
                // clock is right, and this one only ever set the lyrics off.
                val told = anchor(event.optJSONObject("position"))
                val sameSong = item?.optString("uri") == _state.value.uri
                val trusted = told != null && (!sameSong || _state.value.anchor == null)
                val options = event.optJSONObject("options")
                _state.update {
                    it.copy(
                        loggedIn = true,
                        status = event.optString("status", it.status),
                        uri = item?.optString("uri")?.takeIf(String::isNotEmpty),
                        song = item?.let(::song)?.takeIf { s -> s.uri.isNotEmpty() },
                        contextUri = event.optJSONObject("context")?.optString("uri")?.takeIf(String::isNotEmpty),
                        durationMs = duration(item),
                        anchor = if (trusted) told else it.anchor,
                        active = event.optBoolean("is_active", it.active),
                        shuffle = options?.optBoolean("shuffle", it.shuffle) ?: it.shuffle,
                        smartShuffle = options?.let(::smart) ?: it.smartShuffle,
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
                        smartShuffle = smart(options) ?: it.smartShuffle,
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
            val source = entry.optString("source")
            val provider = entry.optJSONObject("metadata")?.optString("provider").orEmpty()
            if (source !in seenSources) {
                seenSources.add(source)
                Log.i(TAG, "queue source seen: $source (provider $provider, keys ${entry.keys().asSequence().toList()})")
            }
            if (RECOMMENDED in source || provider == RECOMMENDED) recommended.add(described.uri)
            QueueEntry(entry.optString("uid", "$i:${described.uri}"), source, described)
        }

    /** Sources of queue entries already said in the log, for what smart shuffle adds. */
    private val seenSources = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Songs smart shuffle put in (Spotify's recommendations, not the
     * context's own): kept past the queue, so the one playing is known for
     * one too. Spotify marks them so, as its web player reads them.
     */
    val recommended: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    private const val RECOMMENDED = "enhanced_recommendation"

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
     * Smart shuffle as the listener last chose it, kept from one context to
     * the next (a play turns it off on Soloist); see SoloistPlayer.send.
     */
    @Volatile var smartWanted = false

    /** Whether [options] (Soloist's word on them) have smart shuffle on; null when they do not say. */
    private fun smart(options: JSONObject): Boolean? =
        options.optJSONObject("modes")?.optString("context_enhancement")?.takeIf(String::isNotEmpty)
            ?.let { it == SMART_SHUFFLE_MODE }

    /**
     * Shuffle, smart or plain, or off: the Connect `set_options` Spotify's
     * own players send, which Soloist's local commands have no word for.
     * Smart shuffle is shuffle with Spotify's recommendations mixed in, the
     * mode the web player sets as `context_enhancement`. Blocking.
     */
    fun setShuffleMode(shuffle: Boolean, smart: Boolean): Boolean {
        val device = deviceId ?: return false
        val body = JSONObject().put(
            "command",
            JSONObject()
                .put("endpoint", "set_options")
                .put("shuffling_context", shuffle || smart)
                .put("modes", JSONObject().put("context_enhancement", if (smart) SMART_SHUFFLE_MODE else "NONE"))
                .put("logging_params", JSONObject()),
        )
        // Twice: asked while Soloist is still opening a context it was just
        // sent, the command went unanswered.
        repeat(2) { attempt ->
            val sent = runCatching { dev.lelonio.square.nativecore.NativeBridge.remoteCommand(device, body.toString()) }
            if (sent.isSuccess) {
                Log.i(TAG, "shuffle mode set: ${if (smart) "smart" else if (shuffle) "on" else "off"}")
                if (smart) reshuffleUpcoming()
                return true
            }
            val message = sent.exceptionOrNull()?.message.orEmpty()
            Log.w(TAG, "shuffle mode not set (try ${attempt + 1}): $message")
            // Refused outright: no second try changes that.
            if ("403" in message) return false
            Thread.sleep(1_000)
        }
        return false
    }

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
    fun addToQueue(uri: String): Unit {
        queuedByHand.add(uri)
        command("add_to_queue", "uri" to uri)
    }

    /** Songs put in the queue by hand, never smart shuffle's however Soloist labels them. */
    val queuedByHand: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

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

    /**
     * Smart shuffle's queue in a new order. Soloist orders a context the
     * same way every time smart shuffle is turned on, whatever order it was
     * sent in, where Spotify's own app gives a new one each time: the songs
     * it chose (the recommendations among them) are mixed up here, those
     * queued by hand left first. Blocking.
     */
    private fun reshuffleUpcoming() {
        // Once Soloist has put its smart queue out.
        val until = System.currentTimeMillis() + 4_000
        val before = _state.value.upcoming
        while (System.currentTimeMillis() < until && (_state.value.upcoming == before || !_state.value.smartShuffle)) Thread.sleep(150)
        Thread.sleep(300)
        val now = _state.value
        if (!now.smartShuffle || now.upcoming.size < 3) return
        val (hand, rest) = now.upcoming.partition { it.source == "queue" || it.song.uri in queuedByHand }
        // The playlist's own songs and the recommendations each mixed up,
        // then one recommendation every third song, as smart shuffle spaces
        // them.
        val own = now.contextUri?.let { context ->
            runCatching {
                val list = JSONArray(dev.lelonio.square.nativecore.NativeBridge.contextTracks(context))
                (0 until list.length()).map { list.getString(it) }.toSet()
            }.getOrNull()
        }.orEmpty()
        val (mine, added) = if (own.isEmpty()) rest to emptyList() else rest.partition { it.song.uri in own }
        val ownMixed = ArrayDeque(mine.shuffled())
        val addedMixed = ArrayDeque(added.shuffled())
        val mixed = mutableListOf<QueueEntry>()
        while (ownMixed.isNotEmpty() || addedMixed.isNotEmpty()) {
            val third = mixed.size % 3 == 2
            val take = if (third && addedMixed.isNotEmpty() || ownMixed.isEmpty()) addedMixed else ownMixed
            mixed.add(take.removeFirst())
        }
        val next = hand + mixed
        Log.i(TAG, "smart queue mixed up: ${mine.size} of the playlist, ${added.size} recommended")
        if (setQueue(now.previous.reversed(), next)) expectQueue(next)
    }

    /**
     * Where a paused song was sent to: Soloist, paused, says nothing of the
     * new place, and the time showed the start of the song until play.
     */
    fun expectPosition(positionMs: Long) {
        _state.update { if (it.playing) it else it.copy(anchor = Anchor(positionMs, System.currentTimeMillis(), 0.0)) }
    }

    /** Shows a queue edited here until Soloist reports it, which it does a moment later. */
    fun expectQueue(next: List<QueueEntry>) {
        _state.update { it.copy(upcoming = next) }
    }

    /**
     * Where the list Soloist plays came from (a playlist, an album), when it
     * is Square's own list of it: what another device is told it plays, as it
     * cannot open this app's.
     */
    @Volatile
    var listSource: String? = null

    /** The list Square plays when it is not a context of Spotify's. */
    const val LIST_CONTEXT = "spotify:internal:square"

    /**
     * Square's list started on another Connect device, as on Soloist: the
     * songs as the context's pages. For a device the Web API will not start
     * ("Restriction violated", a phone's Spotify app). Blocking.
     */
    fun playListOn(device: String, uris: List<String>, index: Int, positionMs: Long, source: String? = null): Boolean {
        if (uris.isEmpty()) return false
        val tracks = JSONArray().apply { uris.forEach { put(JSONObject().put("uri", it)) } }
        val name = source ?: LIST_CONTEXT
        val context = JSONObject()
            .put("uri", name)
            .put("url", "context://$name")
            .put("metadata", JSONObject())
            .put("pages", JSONArray().put(JSONObject().put("tracks", tracks)))
        val at = index.coerceIn(uris.indices)
        Log.i(TAG, "play on $device: ${uris.size} songs at ${uris[at]}, $positionMs ms")
        return runCatching {
            dev.lelonio.square.nativecore.NativeBridge.remoteCommand(device, playBody(context, uris[at], at, positionMs, false).toString())
        }.onFailure { Log.w(TAG, "play on $device refused: ${it.message}") }.isSuccess
    }

    /**
     * Spotify's DJ, started as Spotify's own apps start it: the DJ's playlist
     * resolved through its session service, from the "dj" feature. Soloist
     * then fetches the songs, says the lines between them and carries on by
     * itself, as nothing does for it when the DJ is sent as a list of songs.
     */
    fun playDj(paused: Boolean): Boolean {
        val uri = dev.lelonio.square.data.Catalog.DJ_URI
        val context = JSONObject()
            .put("uri", uri)
            .put("url", "hm://lexicon-session-provider/context-resolve/v2/session?contextUri=$uri")
            .put("metadata", JSONObject())
        return play(context, "", -1, 0, paused, feature = "dj")
    }

    private fun playBody(
        context: JSONObject,
        trackUri: String,
        index: Int,
        positionMs: Long,
        paused: Boolean,
        feature: String = "square",
    ): JSONObject =
        JSONObject().put(
            "command",
            JSONObject()
                .put("endpoint", "play")
                .put("context", context)
                .put("play_origin", JSONObject().put("feature_identifier", feature).put("feature_version", "1"))
                .put(
                    "options",
                    JSONObject()
                        // The index is into the pages sent along, so the two
                        // agree; none for a context Soloist resolves itself.
                        .put(
                            "skip_to",
                            if (index < 0) JSONObject() else JSONObject().put("track_index", index).put("track_uri", trackUri),
                        )
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

    private fun play(
        context: JSONObject,
        trackUri: String,
        index: Int,
        positionMs: Long,
        paused: Boolean,
        feature: String = "square",
    ): Boolean {
        val device = deviceId ?: return false
        val generation = playGeneration.incrementAndGet()
        _automixing.value = false
        Log.i(TAG, "play $trackUri of ${context.optString("uri")} at $positionMs ms${if (paused) ", paused" else ""}")
        val body = playBody(context, trackUri, index, positionMs, paused, feature)
        // Refused while Soloist's device is still being registered with the
        // account (a 502 right after it logs in): asked again for a while.
        // A device the account no longer knows (404, 410) is not a matter of
        // waiting: three of those and Soloist is started again at once, rather
        // than leaving the music stopped for the half minute of all eight.
        var unknown = 0
        var attempt = -1
        while (true) {
            attempt++
            // Eight tries, and for as long as a new run is still being made
            // known to Spotify.
            if (attempt >= 8 && System.currentTimeMillis() - runStartedAt > REGISTER_GRACE_MS) break
            // A later play (another song picked meanwhile) is the one that
            // counts: this one gives way rather than going on asking, and
            // starting Soloist again under the new one. Three at once, each
            // starting it again in turn, kept it down for half a minute.
            if (playGeneration.get() != generation) {
                Log.i(TAG, "play of $trackUri given up for a later one")
                return false
            }
            val sent = runCatching { dev.lelonio.square.nativecore.NativeBridge.remoteCommand(device, body.toString()) }
            if (sent.isSuccess) return true
            val message = sent.exceptionOrNull()?.message.orEmpty()
            Log.w(TAG, "play did not reach Soloist (try ${attempt + 1}): $message")
            // Not in the first half minute of a run: Spotify takes that long
            // to know a device just signed in, and answers 404 and 410 for it
            // meanwhile. Counted then, each new run was started again before
            // it was known, and the next the same, round and round.
            if (("404" in message || "410" in message) && System.currentTimeMillis() - runStartedAt > REGISTER_GRACE_MS) unknown++
            // Gone from the account's devices altogether: Soloist drops out of
            // Connect after another device has taken the playback from it, and
            // no wait brings it back; a new run does, at once.
            if (("404" in message || "410" in message) && System.currentTimeMillis() - runStartedAt > REGISTER_GRACE_MS &&
                dev.lelonio.square.data.RemoteConnect.devices.value.isNotEmpty() &&
                dev.lelonio.square.data.RemoteConnect.devices.value.none { it.id == device }
            ) {
                Log.w(TAG, "Soloist is no longer among the account's devices")
                break
            }
            if (unknown >= 3) break
            Thread.sleep(minOf(750L * (attempt + 1), 3_000L))
        }
        if (playGeneration.get() != generation) return false
        // Refused throughout: its Connect device is no longer what the account
        // knows (a broken queue, a lost registration). A new run registers it
        // again, and the player sends its queue once it is back; see [restarted].
        // Once: a run started a moment ago is still signing in, and starting
        // it again only began the wait anew.
        val now = System.currentTimeMillis()
        if (restarting && now - restartedForPlayAt < RESTART_PATIENCE_MS) {
            Log.w(TAG, "Soloist refused every play: already starting again")
            return false
        }
        Log.w(TAG, "Soloist refused every play: starting it again")
        restartedForPlayAt = now
        restarting = true
        restart(app)
        return false
    }
}
