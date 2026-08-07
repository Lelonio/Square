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
        return if (isMetered()) Quality.Medium.kbps else Quality.High.kbps
    }

    /**
     * Whether the connection is one the user pays by the megabyte.
     *
     * The system's own answer, not a guess from the transport: a phone tethering
     * over wi-fi is metered, and an unlimited mobile plan the user has marked as
     * such is not.
     */
    private fun isMetered(): Boolean {
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private companion object {
        const val FILE_NAME = "square_quality"
        const val KEY_QUALITY = "quality"
    }
}
