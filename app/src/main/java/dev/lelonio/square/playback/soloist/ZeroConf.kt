package dev.lelonio.square.playback.soloist

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Soloist's Spotify Connect announcement, made by Android for it.
 *
 * Soloist answers Connect's sign-in on a local HTTP port (ZeroConf) and would
 * announce it over mDNS itself, but in the app sandbox it finds no IPv4
 * address to announce. Android's NsdManager announces the same service, so the
 * Spotify app on the network lists "Square" and signs Soloist in when it is
 * picked; after that Soloist keeps its credentials.
 */
internal object ZeroConf {
    private const val TAG = "SoloistZeroConf"
    private const val TYPE = "_spotify-connect._tcp"

    private var listener: NsdManager.RegistrationListener? = null

    @Synchronized
    fun announce(context: Context, port: Int, path: String) {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        withdraw(context)
        val info = NsdServiceInfo().apply {
            serviceName = dev.lelonio.square.playback.ConnectName.of(context)
            serviceType = TYPE
            setPort(port)
            setAttribute("CPath", path)
            setAttribute("VERSION", "1.0")
            setAttribute("Stack", "SP")
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "announced ${info.serviceName} on port $port")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.w(TAG, "announcement failed: $code")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }
            .onSuccess { listener = registration }
            .onFailure { Log.w(TAG, "announcement failed: $it") }
    }

    @Synchronized
    fun withdraw(context: Context) {
        val registration = listener ?: return
        listener = null
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        runCatching { nsd.unregisterService(registration) }
    }
}
