package dev.lelonio.square.ui.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Where the sound is going, as far as the timing of the words cares (#143).
 *
 * [key] tells one pair of headphones from another, so each keeps its own
 * delay; [name] is what the panel calls them, or null for the phone itself.
 */
@Immutable
data class AudioOutput(val key: String, val name: String?)

/** The output playing now, followed as headphones come and go. */
@Composable
fun rememberAudioOutput(): AudioOutput {
    val context = LocalContext.current
    val audio = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var output by remember(audio) { mutableStateOf(currentOutput(audio)) }
    DisposableEffect(audio) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                output = currentOutput(audio)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                output = currentOutput(audio)
            }
        }
        // A null handler delivers on the main thread.
        audio.registerAudioDeviceCallback(callback, null)
        onDispose { audio.unregisterAudioDeviceCallback(callback) }
    }
    return output
}

private val PHONE = AudioOutput("phone", null)

private val WIRELESS = setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    // Bluetooth LE Audio; these values are only defined from Android 12 and 13,
    // but are plain numbers an older phone never reports.
    26, // TYPE_BLE_HEADSET
    27, // TYPE_BLE_SPEAKER
    30, // TYPE_BLE_BROADCAST
)

private val WIRED = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE,
)

/**
 * The device music is routed to: asked directly from Android 13, which can say;
 * before that, the Bluetooth device connected for media if there is one, since
 * that is where Android sends music whenever one is.
 */
private fun currentOutput(audio: AudioManager): AudioOutput {
    val routed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        runCatching {
            audio.getAudioDevicesForAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build(),
            ).firstOrNull()
        }.getOrNull()
    } else {
        null
    }
    val device = routed ?: runCatching {
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        outputs.firstOrNull { it.type in WIRELESS } ?: outputs.firstOrNull { it.type in WIRED }
    }.getOrNull() ?: return PHONE
    val name = device.productName?.toString()?.trim().orEmpty()
    return when (device.type) {
        in WIRELESS -> AudioOutput("bt:$name", name.ifBlank { null })
        in WIRED -> AudioOutput("wired", name.ifBlank { null })
        else -> PHONE
    }
}
