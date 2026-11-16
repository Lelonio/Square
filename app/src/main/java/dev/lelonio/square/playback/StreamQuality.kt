package dev.lelonio.square.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the song playing is actually streamed as.
 *
 * Set by Soloist, which names the codec of every stream it opens and its
 * average bitrate. The player shows it where the "switch to video" button
 * sits, as the button that picks the quality: so it is there for every
 * stream Soloist plays ("FLAC", "OGG 320"), and null whenever another engine
 * is playing, which says nothing about its streams.
 */
object StreamQuality {
    private val _label = MutableStateFlow<String?>(null)
    val label: StateFlow<String?> = _label.asStateFlow()

    /** From a codec as Soloist logs it ("flac", "vorbis", ...) and its bitrate in bit/s. */
    fun stream(codec: String?, bitrate: Int?) {
        _label.value = when {
            codec == null -> _label.value
            codec.equals("flac", ignoreCase = true) -> "FLAC"
            codec.equals("vorbis", ignoreCase = true) -> "OGG" + kbps(bitrate).orEmpty()
            else -> codec.uppercase() + kbps(bitrate).orEmpty()
        }
    }

    /** The nearest of Spotify's lossy rates, from an average that wanders. */
    private fun kbps(bitrate: Int?): String? {
        if (bitrate == null || bitrate <= 0) return null
        val nearest = listOf(96, 160, 320).minBy { kotlin.math.abs(it * 1000 - bitrate) }
        return " $nearest"
    }

    /** For the probe: a codec name alone. */
    fun codec(name: String?) = stream(name, null)

    fun clear() {
        _label.value = null
    }
}
