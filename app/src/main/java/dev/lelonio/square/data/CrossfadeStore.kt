package dev.lelonio.square.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How long one track dissolves into the next, in seconds.
 *
 * Fixed steps rather than a free slider: the difference between six seconds and
 * seven is not something anyone can hear, and a list of choices says what the
 * setting is for without having to be dragged to find out.
 *
 * Zero is off, and off is the same code path as before crossfading existed:
 * the player is told a duration of zero and behaves exactly as upstream does.
 */
val CrossfadeSteps = listOf(0, 3, 6, 9, 12)

/** What a fresh install gets. Long enough to hear, short enough to stay out of the way. */
const val CROSSFADE_DEFAULT_SECONDS = 6

class CrossfadeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _seconds = MutableStateFlow(
        prefs.getInt(KEY_SECONDS, CROSSFADE_DEFAULT_SECONDS),
    )
    val seconds: StateFlow<Int> = _seconds.asStateFlow()

    fun set(seconds: Int) {
        if (seconds == _seconds.value) return
        _seconds.value = seconds
        prefs.edit().putInt(KEY_SECONDS, seconds).apply()
    }

    /**
     * What the engine is started with.
     *
     * Read when a player is built rather than watched, like the bitrate: the
     * player owns its configuration for its whole life, so changing this means
     * building another one.
     */
    fun durationMs(): Int = _seconds.value * 1000

    private companion object {
        const val FILE_NAME = "square_crossfade"
        const val KEY_SECONDS = "seconds"
    }
}
