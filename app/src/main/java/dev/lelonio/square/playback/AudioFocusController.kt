package dev.lelonio.square.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.core.content.ContextCompat

/**
 * Audio focus and the "becoming noisy" broadcast.
 *
 * ExoPlayer does this for you; [SimpleBasePlayer][androidx.media3.common.SimpleBasePlayer]
 * does not, so a custom engine has to handle it or it will talk over phone calls
 * and keep playing out loud after the headphones are unplugged.
 *
 * Everything here runs on the main thread, which is where the player lives.
 */
class AudioFocusController(
    context: Context,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
    private val onDuck: (ducked: Boolean) -> Unit,
) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    /** True when we paused ourselves and should resume once focus returns. */
    private var pausedByFocusLoss = false
    private var holdsFocus = false
    private var noisyReceiverRegistered = false

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        // Duck rather than pause for notifications: pausing for a two-second
        // chime is more disruptive than briefly dropping the volume.
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(::onFocusChange)
        .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            // A deliberate disconnect: do not resume when focus comes back.
            pausedByFocusLoss = false
            onPause()
        }
    }

    /**
     * Ask for focus before starting playback.
     *
     * @return false when the system refuses, in which case playback must not
     *   start — something else owns the output.
     */
    fun requestFocus(): Boolean {
        if (holdsFocus) return true

        val granted = audioManager.requestAudioFocus(request) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) {
            holdsFocus = true
            registerNoisyReceiver()
        }
        return granted
    }

    /** Release focus when playback stops or the user pauses. */
    fun abandonFocus() {
        if (!holdsFocus) return
        audioManager.abandonAudioFocusRequest(request)
        holdsFocus = false
        pausedByFocusLoss = false
        unregisterNoisyReceiver()
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                onDuck(false)
                if (pausedByFocusLoss) {
                    pausedByFocusLoss = false
                    onResume()
                }
            }

            // Someone took the output for good — a different player started.
            // Give up focus entirely rather than waiting for a return.
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedByFocusLoss = false
                onPause()
                abandonFocus()
            }

            // A call, or another app that will hand focus back.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                pausedByFocusLoss = true
                onPause()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onDuck(true)
        }
    }

    private fun registerNoisyReceiver() {
        if (noisyReceiverRegistered) return
        ContextCompat.registerReceiver(
            appContext,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisyReceiverRegistered = true
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered) return
        runCatching { appContext.unregisterReceiver(noisyReceiver) }
        noisyReceiverRegistered = false
    }

    /** Call from the service's onDestroy. */
    fun release() = abandonFocus()
}
