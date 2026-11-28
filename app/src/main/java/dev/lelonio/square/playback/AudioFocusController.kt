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
    /** Whether to stop for another app taking the sound for good; see PreferencesStore. */
    private val pauseOnLoss: () -> Boolean = { true },
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
        // Told rather than ducked. The app still ducks rather than pauses for
        // a notification (onDuck, not onPause, on CAN_DUCK), but does it
        // itself: the reverb is an auxiliary effect on the output mix, which
        // the system's own ducking does not reach, so a song ducked by the
        // system came back as mostly room. Ducking here lowers both.
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener(::onFocusChange)
        .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            // A deliberate disconnect: do not resume when focus comes back.
            pausedByFocusLoss = false
            android.util.Log.i("SquareFocus", "headphones gone: paused")
            noisyAt = System.currentTimeMillis()
            onPause()
        }
    }

    /** When the headphones last went, for [headphonesBack]; 0 when not waiting. */
    private var noisyAt = 0L

    /**
     * Headphones that come back a moment after they went play on. Bluetooth
     * earphones drop off for a few seconds now and then, more with the screen
     * off and the phone in a pocket, and the music stayed paused on their
     * return, as if the listener had taken them off.
     */
    private val headphonesBack = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>) {
            val at = noisyAt
            if (at == 0L || !holdsFocus) return
            if (System.currentTimeMillis() - at > HEADPHONES_BACK_MS) {
                noisyAt = 0L
                return
            }
            if (added.none { it.isSink && it.type in HEADPHONES }) return
            noisyAt = 0L
            android.util.Log.i("SquareFocus", "headphones back: playing on")
            onResume()
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(headphonesBack, android.os.Handler(android.os.Looper.getMainLooper()))
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
        noisyAt = 0L
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
                // Unless the listener has said not to. The focus is gone either
                // way, so it is let go of; the next play asks for it again.
                if (pauseOnLoss()) {
                    onPause()
                } else {
                    android.util.Log.i("SquareFocus", "focus lost for good, playing on as asked")
                }
                pausedByFocusLoss = false
                abandonFocus()
            }

            // A call, or another app that will hand focus back.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                android.util.Log.i("SquareFocus", "focus lost for a while: paused")
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
    fun release() {
        abandonFocus()
        audioManager.unregisterAudioDeviceCallback(headphonesBack)
    }

    private companion object {
        /** How soon headphones must come back for the music to play on; see [headphonesBack]. */
        const val HEADPHONES_BACK_MS = 20_000L

        val HEADPHONES = setOf(
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
            android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }
}
