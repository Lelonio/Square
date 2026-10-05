package dev.lelonio.square.playback

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * When the music is to stop on its own.
 *
 * Held here rather than on the screen that sets it: what stops the music is
 * the service, which outlives every screen, and a countdown that lived in the
 * player's panel would be one that only ran while somebody was watching it.
 * The same reason OfflineMode is an object.
 *
 * Two kinds, because they answer two different questions. A deadline is "I am
 * going to sleep now"; the end of the track is "let this one finish". Asking
 * for either clears the other, since holding both would leave the listener
 * guessing which one stops the music.
 *
 * The deadline is on the clock that counts while the phone sleeps and does not
 * move when the time of day does: a timer set for twenty minutes is twenty
 * minutes whatever the phone thinks the hour is.
 */
object SleepTimer {

    private val _endsAt = MutableStateFlow<Long?>(null)

    /** Elapsed realtime, in milliseconds, at which playback stops. */
    val endsAt: StateFlow<Long?> = _endsAt.asStateFlow()

    private val _minutes = MutableStateFlow<Int?>(null)

    /** What was asked for, so the row can show which length is running. */
    val minutes: StateFlow<Int?> = _minutes.asStateFlow()

    private val _atTrackEnd = MutableStateFlow(false)

    /** Stop when the track that is playing ends, rather than at a time. */
    val atTrackEnd: StateFlow<Boolean> = _atTrackEnd.asStateFlow()

    fun inMinutes(minutes: Int) {
        _atTrackEnd.value = false
        _minutes.value = minutes
        _endsAt.value = SystemClock.elapsedRealtime() + minutes * 60_000L
    }

    /**
     * A length in seconds, as another Spotify device asks for it over Connect.
     * The row shows it rounded up to the minute.
     */
    fun inSeconds(seconds: Long) {
        _atTrackEnd.value = false
        _minutes.value = ((seconds + 59) / 60).toInt()
        _endsAt.value = SystemClock.elapsedRealtime() + seconds * 1_000L
    }

    fun atEndOfTrack() {
        _minutes.value = null
        _endsAt.value = null
        _atTrackEnd.value = true
    }

    fun cancel() {
        _minutes.value = null
        _endsAt.value = null
        _atTrackEnd.value = false
    }

    /** How long is left, or null when nothing is set. Never negative. */
    fun remaining(): Long? = _endsAt.value?.let {
        (it - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }
}
