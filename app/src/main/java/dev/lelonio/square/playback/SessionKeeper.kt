package dev.lelonio.square.playback

import dev.lelonio.square.nativecore.NativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Brings Spotify's session back, and is the one that says when it is back.
 *
 * Before this, nothing reconnected the engine when the network returned: the
 * network watch declared the session back on the strength of a network being
 * there, the app rebuilt its library, asked the engine, and the engine, which
 * had come up offline and been told nothing, answered offline. The library
 * stayed on the downloads with no banner to say why, until a song was played
 * or the banner's button pressed, the only two things that rebuilt the
 * session. And a phone with signal and no data never got a session at all:
 * the network was "up", so nothing ever tried again.
 *
 * Here a network that comes back, or an engine that came up with no session
 * while a network is there, starts tries: at once, then further and further
 * apart, then once a minute for as long as the network stays. Offline ends only
 * when the engine itself reports a live session.
 */
object SessionKeeper {

    private var scope: CoroutineScope? = null
    private var tries: Job? = null

    /** Whether the engine is wanted at all: Spotify the source, and signed in. */
    private var wanted: () -> Boolean = { false }

    /**
     * The service's own: whether its engine is up, and how to start it.
     *
     * An engine whose start failed (no data, nothing downloaded to fall back
     * on) is not there to reconnect; it has to be started again, which only the
     * service can do. Null while no service is running.
     */
    @Volatile
    var engine: Engine? = null

    class Engine(val running: () -> Boolean, val start: () -> Unit)

    fun attach(scope: CoroutineScope, wanted: () -> Boolean) {
        this.scope = scope
        this.wanted = wanted
    }

    /** The network came back after being gone. */
    fun networkReturned() = start("network returned")

    /** The network went; no point trying until it is back. */
    fun networkGone() {
        tries?.cancel()
        tries = null
    }

    /**
     * The engine came up, or came back, with no session while a network is up:
     * signal and no data, a server that did not answer, a login that timed out.
     */
    fun sessionMissing() = start("no session")

    private fun start(why: String) {
        val scope = scope ?: return
        if (tries?.isActive == true) return
        tries = scope.launch {
            for ((attempt, wait) in schedule().withIndex()) {
                delay(wait)
                if (!wanted() || OfflineMode.reason.value == OfflineMode.Reason.MANUAL) return@launch
                if (live()) {
                    OfflineMode.setNoSession(false)
                    return@launch
                }
                val engine = engine
                if (engine != null && !engine.running()) {
                    android.util.Log.i(TAG, "starting the engine again ($why, try ${attempt + 1})")
                    engine.start()
                    // The start runs on its own and reports through the
                    // service; give the handshake its time before looking.
                    delay(START_WAIT_MS)
                } else {
                    android.util.Log.i(TAG, "reconnecting ($why, try ${attempt + 1})")
                    withContext(Dispatchers.IO) { runCatching { NativeBridge.reconnect() } }
                }
                if (live()) {
                    android.util.Log.i(TAG, "session back")
                    OfflineMode.setNoSession(false)
                    return@launch
                }
                // Still nothing: offline for what it is worth, and try again.
                OfflineMode.setNoSession(true)
            }
        }
    }

    /** A session the engine itself vouches for, not a network that happens to be there. */
    private fun live(): Boolean =
        runCatching { NativeBridge.isConnected && !NativeBridge.isOffline }.getOrDefault(false)

    /** At once, then further apart, then once a minute for good. */
    private fun schedule(): Sequence<Long> =
        sequenceOf(0L, 2_000L, 5_000L, 15_000L, 30_000L) + generateSequence { 60_000L }

    private const val TAG = "SquareSession"

    /** How long a fresh start is given before it is looked at. */
    private const val START_WAIT_MS = 8_000L
}
