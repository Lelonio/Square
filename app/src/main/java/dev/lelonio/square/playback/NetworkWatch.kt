package dev.lelonio.square.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Turns the app offline when the connection goes, and back when it returns.
 *
 * Offline used to be decided once, when the engine started, which left the
 * common case unhandled: a phone that loses signal while playing kept behaving
 * as though it were online — queues full of tracks it could not fetch, no line
 * on the page saying why, and a listener wondering what had broken.
 *
 * Losing the network waits a few seconds before it counts. Signal drops for a
 * moment in a lift or between cells, and an app that rearranged itself every
 * time would be worse than one that noticed late. Coming back counts at once:
 * there is nothing to protect against there, and the sooner the library is
 * whole again the better.
 */
class NetworkWatch(context: Context, private val scope: CoroutineScope) {

    private val connectivity = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)

    /** Held so a connection that returns within the grace period cancels it. */
    private var pending: Job? = null

    /** A second look, because the first one races the system's own bookkeeping. */
    private var settle: Job? = null

    /**
     * The default network as the callbacks name it, and what it can do.
     *
     * Not `activeNetwork`: that answers null to an app in the background
     * whose network access the system is holding back (an app on standby, or
     * one it has frozen), Wi-Fi up and working all the while. Read from there,
     * leaving the app for a minute was the network going, and offline after
     * it. The callbacks say which network is the default whatever the app is
     * doing; it is gone when they say it is lost.
     */
    @Volatile
    private var current: Pair<Network, NetworkCapabilities?>? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (current?.first != network) current = network to connectivity?.getNetworkCapabilities(network)
            recheck()
        }
        override fun onLost(network: Network) {
            if (current?.first == network) current = null
            recheck()
        }
        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) {
            current = network to capabilities
            recheck()
        }
    }

    fun start() {
        val registered = runCatching {
            connectivity?.registerDefaultNetworkCallback(callback)
        }.isSuccess
        android.util.Log.i(TAG, "network watch started (registered=$registered)")
        recheck()
    }

    private fun recheck() {
        // Asked again a moment later, always.
        //
        // The callback for the last network going away arrives before the
        // system stops reporting that network as the active one, so reading it
        // here answered "still connected" — and since nothing changes after the
        // last network is gone, no further callback ever came to correct it.
        // The app stayed online, on a phone with the radios off, for as long as
        // it was left running.
        settle?.cancel()
        settle = scope.launch {
            delay(SETTLE_MS)
            evaluate()
        }
        evaluate()
    }

    /** What was last written to the log; see [evaluate]. */
    private var lastReport: String? = null
    private var lastConnected: Boolean? = null

    private fun evaluate() {
        val connected = connected()
        probeIfDoubtful()
        val returned = connected && lastConnected == false
        lastConnected = connected
        // Only when it changes. The system reports every step in signal
        // strength as a new set of capabilities, so this was a line every second
        // or two, and it pushed out of the phone's log the very playback lines
        // a bug report needs.
        val report = "network is ${if (connected) "up" else "gone"}: ${describe()}"
        if (report != lastReport) {
            lastReport = report
            android.util.Log.i(TAG, report)
        }
        pending?.cancel()
        if (connected) {
            // Signal-strength updates do not prove that Spotify's session is
            // back. Only an actual network return starts a reconnection.
            // A network is not a session: the keeper reconnects the engine and
            // ends offline once the engine itself has one. Declaring it back
            // here was how the library stayed on the downloads after the
            // signal returned.
            SessionKeeper.networkUp(returned)
            OfflineMode.setSlow(false)
            return
        }
        SessionKeeper.networkGone()
        pending = scope.launch {
            delay(GRACE_MS)
            android.util.Log.i(TAG, "still gone: going offline")
            OfflineMode.setNoSession(true)
        }
    }

    /** The default network's capabilities: from the callbacks, or asked before the first one. */
    private fun capabilities(): NetworkCapabilities? =
        current?.let { (network, known) -> known ?: connectivity?.getNetworkCapabilities(network) }
            ?: connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }

    private fun describe(): String {
        val capabilities = capabilities() ?: return "no default network"
        val transports = listOfNotNull(
            "wifi".takeIf { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
            "cellular".takeIf { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) },
            "ethernet".takeIf { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) },
            "vpn".takeIf { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) },
            "usb".takeIf { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB) },
            "bluetooth".takeIf { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) },
        ).joinToString("+").ifEmpty { "unknown transport" }
        val internet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return "$transports internet=$internet validated=$validated"
    }

    /**
     * Whether there is an active link that can carry traffic.
     *
     * Wi-Fi, Ethernet and VPNs are treated as connected without strictly
     * requiring NET_CAPABILITY_VALIDATED, because Android's captive portal check
     * (Google 204 probe) frequently fails or is blocked on local/regional networks
     * despite having working internet.
     *
     * Mobile data is not given that benefit of the doubt. With data on and no
     * coverage the cellular network stays the default, with internet and
     * nothing getting through, and the app stayed online: a library full of
     * songs it could not fetch, and downloaded ones handed to a session that
     * could not answer. There it counts only while the system has validated it
     * or a probe of this app's own got through, and not once a probe has failed.
     */
    private fun connected(): Boolean {
        val capabilities = capabilities() ?: return false
        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val isCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

        if (!hasInternet || !(isWifi || isEthernet || isCellular || isVpn)) return false
        if (!onMobileData(capabilities)) return true
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val reached = reachable.takeIf { probed == current?.first }
        return when (reached) {
            false -> false
            true -> true
            // Not probed yet: the system's word, and a probe on its way when
            // that word is no.
            null -> validated
        }
    }

    /** Mobile data alone: nothing faster or steadier carrying the traffic. */
    private fun onMobileData(capabilities: NetworkCapabilities) =
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

    /** What the last probe of [probed] found; null before one has finished. */
    @Volatile
    private var reachable: Boolean? = null

    @Volatile
    private var probed: Network? = null

    private var probing: Job? = null
    private var lastProbeAt = 0L

    /**
     * Probes mobile data, when there is a reason to doubt it.
     *
     * The reasons are the system not having validated it, and its capabilities
     * changing, which on mobile data is mostly the signal getting weaker or
     * stronger: the moment coverage goes. Not more often than every
     * [PROBE_GAP_MS] while it answers, since every probe wakes the radio; and
     * again every [PROBE_RETRY_MS] once it has stopped answering, because
     * nothing else would notice it coming back.
     */
    private fun probeIfDoubtful() {
        val network = current?.first
        val capabilities = capabilities()
        if (network == null || capabilities == null || !onMobileData(capabilities)) {
            forgetProbe()
            return
        }
        if (probed != network) {
            probing?.cancel()
            probed = network
            reachable = null
            lastProbeAt = 0L
        }
        if (probing?.isActive == true) return
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val now = android.os.SystemClock.elapsedRealtime()
        if (validated && reachable != false && now - lastProbeAt < PROBE_GAP_MS) return
        lastProbeAt = now
        probing = scope.launch {
            while (current?.first == network) {
                val answered = answers(network)
                lastProbeAt = android.os.SystemClock.elapsedRealtime()
                if (probed != network) return@launch
                if (reachable != answered) {
                    android.util.Log.i(TAG, "mobile data ${if (answered) "answers" else "carries nothing"}")
                    reachable = answered
                    evaluate()
                }
                if (answered) return@launch
                delay(PROBE_RETRY_MS)
            }
        }
    }

    private fun forgetProbe() {
        probing?.cancel()
        probing = null
        probed = null
        reachable = null
    }

    /**
     * Whether a connection opens through [network] to a server the app needs.
     * Either one will do: the point is the link, not a service being down.
     */
    private suspend fun answers(network: Network): Boolean = PROBE_HOSTS.any { host ->
        kotlinx.coroutines.withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val address = network.getAllByName(host).first()
                    network.socketFactory.createSocket().use { socket ->
                        socket.connect(java.net.InetSocketAddress(address, 443), PROBE_TIMEOUT_MS.toInt())
                    }
                    true
                }.getOrDefault(false)
            }
        } ?: false
    }

    private companion object {
        const val TAG = "SquareNetwork"

        /** How long the network has to stay gone before the app believes it. */
        const val GRACE_MS = 6_000L

        /** How long the system takes to stop naming a network that has gone. */
        const val SETTLE_MS = 1_200L

        /** How long a probe waits for a connection before calling it gone. */
        const val PROBE_TIMEOUT_MS = 4_000L

        /** The least time between probes of mobile data that answers. */
        const val PROBE_GAP_MS = 30_000L

        /** How often mobile data that stopped answering is tried again. */
        const val PROBE_RETRY_MS = 10_000L

        val PROBE_HOSTS = listOf("apresolve.spotify.com", "music.youtube.com")
    }
}
