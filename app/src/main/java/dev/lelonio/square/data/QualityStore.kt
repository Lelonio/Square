package dev.lelonio.square.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.annotation.StringRes
import dev.lelonio.square.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which file the engine asks Spotify for.
 *
 * The three fixed steps are the ones the account is actually offered: Ogg
 * Vorbis at 320, 160 and 96 kbps. Nothing higher exists for this client, so
 * there is no "lossless" here to choose.
 */
enum class Quality(val key: String, @StringRes val label: Int, val kbps: Int) {
    /** Full quality on wi-fi, the middle step on a metered connection. */
    Auto("auto", R.string.quality_auto, 0),
    High("high", R.string.quality_high, 320),
    Medium("medium", R.string.quality_medium, 160),
    Low("low", R.string.quality_low, 96),
}

class QualityStore(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _quality = MutableStateFlow(
        Quality.entries.firstOrNull { it.key == prefs.getString(KEY_QUALITY, null) }
            ?: Quality.Auto,
    )
    val quality: StateFlow<Quality> = _quality.asStateFlow()

    fun set(quality: Quality) {
        _quality.value = quality
        prefs.edit().putString(KEY_QUALITY, quality.key).apply()
    }

    /**
     * The bitrate to start the engine with, resolving [Quality.Auto].
     *
     * Read when an engine is built rather than watched: the player owns its
     * configuration for its whole life, so a connection that changes from wi-fi
     * to mobile mid-track keeps the bitrate it started on until the next time
     * the engine is built.
     */
    fun bitrateKbps(): Int {
        val chosen = _quality.value
        if (chosen != Quality.Auto) return chosen.kbps
        return automatic()
    }

    /**
     * What automatic means: how fast the connection actually is.
     *
     * It used to mean "is this connection metered", which answers a question
     * about the bill rather than about the link. A limited wi-fi is unmetered
     * and can still be slower than the stream, which is exactly the case where
     * the setting matters: 320 kbps over a link that cannot carry it is a track
     * that stalls, and the app went on asking for it because the connection was
     * free.
     *
     * The system's own downstream estimate decides, with room above each step:
     * a 320 kbps stream needs about 40 kB/s and nothing else on the phone is
     * idle, so asking for roughly three times the stream keeps the buffer ahead
     * of the decoder. Where there is no estimate — some networks report
     * nothing — the old question is still worth asking, and metered falls back
     * to the middle step.
     */
    fun automatic(): Int {
        val capabilities = capabilities()
        val kbps = capabilities?.linkDownstreamBandwidthKbps ?: 0
        if (kbps <= 0) {
            return if (isMetered()) Quality.Medium.kbps else Quality.High.kbps
        }
        return when {
            kbps < LOW_CEILING_KBPS -> Quality.Low.kbps
            kbps < MEDIUM_CEILING_KBPS -> Quality.Medium.kbps
            else -> Quality.High.kbps
        }
    }

    /** The estimate the choice above is made from, for the settings to show. */
    fun linkKbps(): Int = capabilities()?.linkDownstreamBandwidthKbps ?: 0

    /**
     * Whether the connection is one the user pays by the megabyte.
     *
     * The system's own answer, not a guess from the transport: a phone tethering
     * over wi-fi is metered, and an unlimited mobile plan the user has marked as
     * such is not.
     */
    private fun isMetered(): Boolean {
        val capabilities = capabilities() ?: return false
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun capabilities(): NetworkCapabilities? {
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return null
        return manager.getNetworkCapabilities(manager.activeNetwork)
    }

    private companion object {
        /**
         * Below this, ask for the smallest file; below the next, the middle one.
         *
         * Three times the stream itself, near enough: 96 kbps of audio wants
         * 300 of link, 160 wants 600. Under those the buffer never gets ahead.
         */
        const val LOW_CEILING_KBPS = 300
        const val MEDIUM_CEILING_KBPS = 700

        const val FILE_NAME = "square_quality"
        const val KEY_QUALITY = "quality"
    }
}
