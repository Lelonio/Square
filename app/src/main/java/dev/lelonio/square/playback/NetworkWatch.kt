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

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = recheck()
        override fun onLost(network: Network) = recheck()
        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) = recheck()
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

    private fun evaluate() {
        val connected = connected()
        android.util.Log.i(TAG, "network is ${if (connected) "up" else "gone"}: ${describe()}")
        pending?.cancel()
        if (connected) {
            OfflineMode.setNoSession(false)
            OfflineMode.setSlow(false)
            return
        }
        pending = scope.launch {
            delay(GRACE_MS)
            android.util.Log.i(TAG, "still gone: going offline")
            OfflineMode.setNoSession(true)
        }
    }

    /**
     * Whether there is an active link that can carry traffic.
     *
     * Wi-Fi, Ethernet, Cellular and VPNs are treated as connected without strictly
     * requiring NET_CAPABILITY_VALIDATED, because Android's captive portal check
     * (Google 204 probe) frequently fails or is blocked on local/regional networks
     * despite having working internet.
     */
    private fun describe(): String {
        val network = connectivity?.activeNetwork ?: return "no active network"
        val capabilities = connectivity.getNetworkCapabilities(network)
            ?: return "active network with no capabilities"
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

    private fun connected(): Boolean {
        val network = connectivity?.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val isCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

        return hasInternet && (isWifi || isEthernet || isCellular || isVpn)
    }

    private companion object {
        const val TAG = "SquareNetwork"

        /** How long the network has to stay gone before the app believes it. */
        const val GRACE_MS = 6_000L

        /** How long the system takes to stop naming a network that has gone. */
        const val SETTLE_MS = 1_200L
    }
}
