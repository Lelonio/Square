package dev.lelonio.square.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.util.Log

/**
 * Lossless audio as it is, as far as the phone lets it go.
 *
 * With a USB DAC attached (Android 14 and later), the DAC's output is opened
 * bit-perfect: Android neither mixes nor resamples nor changes the volume of
 * what this app plays, and the samples reach the DAC as they were decoded, in
 * the best format it takes at the song's own rate. Other sounds of the phone
 * are not heard on it meanwhile, as Android does it. Anywhere else (the
 * speaker, a jack, Bluetooth, whose codec re-encodes anyway) the music goes out
 * as float, and Android's mixer takes it from there.
 *
 * Asked for only while no effect changes the sound; see AudioOutput.writeFloat.
 */
object HiFi {
    private const val TAG = "HiFi"

    @Volatile
    private var app: Context? = null

    /** The DAC and the mixer it was opened with, while it is. */
    @Volatile
    private var chosen: Pair<Int, AudioMixerAttributes>? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    private val media: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /** The encoding to write a stream of this rate and channel count in. */
    fun encodingFor(sampleRate: Int, channels: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return AudioFormat.ENCODING_PCM_FLOAT
        val context = app ?: return AudioFormat.ENCODING_PCM_FLOAT
        val audio = context.getSystemService(AudioManager::class.java) ?: return AudioFormat.ENCODING_PCM_FLOAT
        val dac = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }
        if (dac == null) {
            release()
            return AudioFormat.ENCODING_PCM_FLOAT
        }
        chosen?.let { (device, mixer) ->
            if (device == dac.id && mixer.format.sampleRate == sampleRate && mixer.format.channelCount == channels) {
                return mixer.format.encoding
            }
        }
        val mixer = runCatching { audio.getSupportedMixerAttributes(dac) }.getOrDefault(emptyList())
            .filter {
                it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                    it.format.sampleRate == sampleRate &&
                    it.format.channelCount == channels
            }
            .minByOrNull { PREFERRED.indexOf(it.format.encoding).let { i -> if (i < 0) PREFERRED.size else i } }
            ?.takeIf { it.format.encoding in PREFERRED }
        if (mixer == null) {
            Log.i(TAG, "${dac.productName}: no bit-perfect mode at $sampleRate Hz, $channels ch; float through the mixer")
            release()
            return AudioFormat.ENCODING_PCM_FLOAT
        }
        val set = runCatching { audio.setPreferredMixerAttributes(media, dac, mixer) }
            .onFailure { Log.w(TAG, "bit-perfect refused: ${it.message}") }
            .getOrDefault(false)
        if (!set) return AudioFormat.ENCODING_PCM_FLOAT
        chosen = dac.id to mixer
        Log.i(TAG, "${dac.productName}: bit-perfect, $sampleRate Hz, encoding ${mixer.format.encoding}")
        return mixer.format.encoding
    }

    /** Hands the DAC back to Android's mixer: an effect is on, or the DAC is gone. */
    fun release() {
        val (device, _) = chosen ?: return
        chosen = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val audio = app?.getSystemService(AudioManager::class.java) ?: return
        val dac = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == device } ?: return
        runCatching { audio.clearPreferredMixerAttributes(media, dac) }
        Log.i(TAG, "bit-perfect released")
    }

    /** Best first: float and 32-bit carry 24-bit FLAC whole, as does packed 24-bit. */
    private val PREFERRED = listOf(
        AudioFormat.ENCODING_PCM_FLOAT,
        AudioFormat.ENCODING_PCM_32BIT,
        AudioFormat.ENCODING_PCM_24BIT_PACKED,
        AudioFormat.ENCODING_PCM_16BIT,
    )
}
