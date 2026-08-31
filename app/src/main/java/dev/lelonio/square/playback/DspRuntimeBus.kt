package dev.lelonio.square.playback

import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Control-plane event transport only. It does not own DSP state: AudioEffects
 * remains the persisted source of truth and processors receive snapshots here.
 */
internal object DspRuntimeBus {
    private val listeners = CopyOnWriteArrayList<WeakReference<(AdvancedDspConfig) -> Unit>>()

    fun subscribe(listener: (AdvancedDspConfig) -> Unit): AutoCloseable {
        listeners += WeakReference(listener)
        return AutoCloseable { listeners.removeAll { it.get() === listener || it.get() == null } }
    }

    fun publish(config: AdvancedDspConfig) {
        listeners.removeAll { ref ->
            val listener = ref.get()
            if (listener == null) true else {
                listener(config)
                false
            }
        }
    }
}
