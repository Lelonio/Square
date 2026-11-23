package dev.lelonio.square.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the song playing is actually heard as, from whichever engine plays it.
 *
 * Soloist names the codec of every stream it opens and its average bitrate
 * ("FLAC", "OGG 320"); librespot says the bitrate of the song it plays, a
 * stream's or a download's ("OGG 320"). The player shows it where the "switch
 * to video" button sits, as the button that picks the quality of that engine.
 * Soloist stays up behind librespot (for the sign-in and Connect), so what it
 * says is shown only while it is the player in front.
 */
object StreamQuality {
    private val _soloist = MutableStateFlow<String?>(null)
    private val _librespot = MutableStateFlow<String?>(null)
    private val _shown = MutableStateFlow<String?>(null)
    private val _soloistInFront = MutableStateFlow(false)

    val label: StateFlow<String?> = _shown.asStateFlow()

    /** Whether the quality on show, and the menu under it, are Soloist's or librespot's. */
    val soloistInFront: StateFlow<Boolean> = _soloistInFront.asStateFlow()

    /** Which engine the music comes from; see PlaybackService. */
    fun setFront(soloist: Boolean) {
        _soloistInFront.value = soloist
        publish()
    }

    private fun publish() {
        _shown.value = if (_soloistInFront.value) _soloist.value else _librespot.value
    }

    /** From Soloist: a codec as it logs it ("flac", "vorbis", ...) and its bitrate in bit/s. */
    fun stream(codec: String?, bitrate: Int?) {
        _soloist.value = when {
            codec == null -> _soloist.value
            codec.equals("flac", ignoreCase = true) -> "FLAC"
            codec.equals("vorbis", ignoreCase = true) -> "OGG" + kbps(bitrate).orEmpty()
            else -> codec.uppercase() + kbps(bitrate).orEmpty()
        }
        publish()
    }

    /** From librespot: the song playing, in kbit/s; 0 for none yet. */
    fun librespot(kbps: Int) {
        _librespot.value = if (kbps <= 0) null else "OGG" + kbps(kbps * 1000).orEmpty()
        publish()
    }

    /** The nearest of Spotify's lossy rates, from an average that wanders. */
    private fun kbps(bitrate: Int?): String? {
        if (bitrate == null || bitrate <= 0) return null
        val nearest = listOf(96, 160, 320).minBy { kotlin.math.abs(it * 1000 - bitrate) }
        return " $nearest"
    }

    /** For the probe: a codec name alone. */
    fun codec(name: String?) = stream(name, null)

    /** Soloist gone: nothing of its to show. */
    fun clear() {
        _soloist.value = null
        publish()
    }
}
