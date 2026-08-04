package dev.emanuele.spot.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.audiofx.EnvironmentalReverb
import java.nio.ByteBuffer

/**
 * PCM output for the native engine.
 *
 * The Rust sink calls [write] from librespot's playback thread with interleaved
 * little-endian 16-bit samples. Writes are blocking, which is deliberate: the
 * track's own buffer is the only queue in the pipeline, and blocking on it is
 * what paces the decoder. Adding a queue here would just move the underrun.
 *
 * Every method is called from native code and must keep its name and signature
 * in step with `native/src/sink.rs`.
 */
class AudioOutput {

    private var track: AudioTrack? = null
    private var configuredSampleRate = 0
    private var configuredChannels = 0

    private var reverb: EnvironmentalReverb? = null

    /**
     * Tempo and pitch, independently, done by [Stretcher] rather than by
     * AudioTrack.
     *
     * `setPlaybackParams` was the first implementation and is not used any more:
     * it runs the platform's Sonic stretcher, which is tuned for speech and
     * audibly poor on music. Bungee costs a buffer copy and some CPU per packet
     * and sounds like the same recording played differently, which is the point.
     */
    @Volatile
    private var speed = 1f

    @Volatile
    private var pitch = 1f

    private var stretcher: Stretcher? = null

    /** Logged once rather than per packet, which would be several a second. */
    private var stretchFailureReported = false

    /** Reverb amount, 0 (off) to 1. */
    @Volatile
    private var reverbAmount = 0f

    /** Where the fade currently is, so a new ramp starts from it. */
    @Volatile
    private var currentGain = 1f

    /**
     * Whether the sink has been told to play.
     *
     * Kept because [start] can arrive before there is an AudioTrack to start —
     * the sink is started when playback begins, and the track is built by the
     * first packet that follows. Without this the track was created silent and
     * nothing ever raised it: the dry signal stayed at zero while the reverb's
     * send, which does not go through the track's volume, carried on at full
     * level. That is the "everything is reverb and muffled until I change
     * track" this fixes — a track change ran start() again, this time with a
     * track present.
     */
    @Volatile
    private var playing = false

    /**
     * Drops incoming PCM instead of writing it.
     *
     * Set while a track change is in flight. Fading the volume down and flushing
     * the buffer is not enough on its own: librespot's decoder keeps pushing the
     * *old* track's packets for as long as it takes the load to take effect on
     * its side, and those land in the freshly emptied buffer and play at full
     * level the moment the fade-in starts. That is the fragment of the previous
     * song heard after the silence. Discarding at the door is the only place
     * that can tell the two apart, because by the time audio is in the track's
     * buffer it is just samples.
     */
    @Volatile
    private var discarding = false

    /**
     * Runs fade-ins off the caller's thread.
     *
     * Fade-*outs* deliberately block instead: they have to finish before the
     * track is flushed.
     */
    private val fadeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "SpotFade").apply { isDaemon = true }
    }

    fun setSpeedAndPitch(speed: Float, pitch: Float) {
        // Outside this range the stretcher produces artefacts bad enough to be
        // mistaken for a bug, and AudioTrack throws outright on non-positive
        // values.
        this.speed = speed.coerceIn(MIN_RATE, MAX_RATE)
        this.pitch = pitch.coerceIn(MIN_RATE, MAX_RATE)
    }

    fun setReverbAmount(amount: Float) {
        reverbAmount = amount.coerceIn(0f, 1f)
        synchronized(this) { applyReverb() }
    }

    /**
     * Attaches, tunes or detaches the reverb.
     *
     * [EnvironmentalReverb] rather than PresetReverb: the preset version only
     * exposes a handful of named rooms, so a slider over it would quantise into
     * four steps and pretend to be continuous. This one takes the room's decay
     * and level directly, which is what makes a real amount control possible.
     *
     * Wired as an **auxiliary** effect, not an insert one. That distinction is
     * why the first attempt was silent: an effect built on the track's own
     * session is created, enabled and reports no error, but nothing is routed
     * into it. A reverb needs a send — the track has to be pointed at the effect
     * with `attachAuxEffect` and given a send level above zero. Auxiliary
     * effects live on the global output mix, hence session 0.
     *
     * That last part is also why [suspendReverb] exists. An effect on the output
     * mix processes the *mix*, not this track: left running while playback is
     * paused it keeps ringing out its tail, and any other sound the phone makes
     * — a notification, another app — arrives into a room it was never meant to
     * be in. So the effect is only alive while this player is actually playing.
     */
    private fun applyReverb() {
        val output = track
        val amount = reverbAmount

        if (output == null || amount <= 0f) {
            output?.runCatching {
                setAuxEffectSendLevel(0f)
                attachAuxEffect(0)
            }
            reverb?.runCatching {
                enabled = false
                release()
            }
            reverb = null
            return
        }

        val effect = reverb ?: runCatching {
            // Priority 0: no reason to outbid anything else on the mix.
            EnvironmentalReverb(0, 0)
        }.onFailure {
            android.util.Log.w(TAG, "reverb unavailable: ${it.message}")
        }.getOrNull() ?: return

        reverb = effect
        runCatching {
            // The room grows with the amount: a longer tail and a louder
            // reflection, from a small live room up to a hall.
            effect.decayTime = (MIN_DECAY_MS + amount * (MAX_DECAY_MS - MIN_DECAY_MS)).toInt()
            effect.reverbLevel = (MIN_LEVEL_MB + amount * (MAX_LEVEL_MB - MIN_LEVEL_MB)).toInt().toShort()
            // This is the one that made the effect nearly inaudible. An
            // EnvironmentalReverb starts with roomLevel at -9000 mB — ninety
            // decibels down, which is silence — and it is a master attenuation
            // sitting in front of everything else, so raising reverbLevel and
            // the send alone changed almost nothing.
            effect.roomLevel = (MIN_ROOM_MB + amount * (MAX_ROOM_MB - MIN_ROOM_MB)).toInt().toShort()
            // Keeps the tail from being all low end, which is what a room with
            // its highs rolled off sounds like: muddy rather than spacious.
            effect.roomHFLevel = (-400).toShort()
            // Held steady: these shape the character of the room rather than its
            // size, and moving them with the slider makes the low end swell as
            // well as the tail, which reads as the track getting muddier rather
            // than more distant.
            effect.decayHFRatio = 700
            effect.density = 1000
            effect.diffusion = 1000
            effect.enabled = true

            output.attachAuxEffect(effect.id)
            // The dry path is untouched, so this is purely how much goes to the
            // tail. Curved rather than linear: the first part of the range is
            // where the audible change happens, and a straight mapping leaves
            // most of the slider doing almost nothing.
            output.setAuxEffectSendLevel(amount * amount * 0.85f + amount * 0.15f)
        }.onFailure { android.util.Log.w(TAG, "reverb not applied: ${it.message}") }
    }

    /** Called by the sink when playback begins. */
    @Suppress("unused")
    fun start() {
        // A gate left closed by a load that never got its fade-in — a track
        // loaded paused, say — would otherwise silence the next play outright.
        discarding = false
        playing = true
        val output = synchronized(this) {
            track?.takeIf { it.state == AudioTrack.STATE_INITIALIZED }
        } ?: return

        runCatching {
            output.setVolume(0f)
            output.play()
        }
        // Back on the mix now that this player is the thing making sound.
        synchronized(this) { applyReverb() }
        fadeExecutor.execute { ramp(output, 1f, FADE_IN_MS) }
    }

    /**
     * Called by the sink when playback stops; fades out, then drops anything
     * still buffered.
     *
     * The fade is run here, on the caller's thread, and finishes before the
     * track is flushed — the whole point is that the last samples are quiet
     * before they are thrown away. It is the librespot playback thread, and
     * holding it for the length of a fade is exactly the delay being asked for.
     */
    @Suppress("unused")
    fun stop() {
        discarding = false
        playing = false
        val output = synchronized(this) {
            track?.takeIf { it.state == AudioTrack.STATE_INITIALIZED }
        }

        if (output != null) {
            ramp(output, 0f, FADE_OUT_MS)
            runCatching {
                output.pause()
                output.flush()
            }
        }

        synchronized(this) {
            // The stretcher holds a window of the *previous* track. Kept across
            // a change, those grains are resynthesised over the first moments of
            // the next one — which is the half-second of the old song audible
            // under the new one when effects are on.
            stretcher?.release()
            stretcher = null

            suspendReverb()
        }
    }

    /**
     * Takes the reverb off the output mix until playback resumes.
     *
     * Not merely muted: the effect is disabled, because an enabled reverb on the
     * mix processes whatever the phone plays next. Muting the send alone left
     * the tail of the last few seconds ringing on after the pause, and every
     * system sound afterwards arriving through it.
     */
    private fun suspendReverb() {
        track?.runCatching {
            setAuxEffectSendLevel(0f)
            attachAuxEffect(0)
        }
        reverb?.runCatching { enabled = false }
    }

    /**
     * Fades out, then runs [action] — used when the player is about to load a
     * different track.
     *
     * librespot does not stop the sink on every transition, so [stop] alone
     * would leave skips as hard cuts. Off the caller's thread because this is
     * called from the main looper.
     */
    fun fadeOutThen(action: () -> Unit) {
        val output = synchronized(this) {
            track?.takeIf { it.state == AudioTrack.STATE_INITIALIZED }
        }
        if (output == null) {
            action()
            return
        }
        fadeExecutor.execute {
            ramp(output, 0f, FADE_OUT_MS)
            // Order matters: the gate closes before the buffer is emptied, so
            // nothing can slip back in between the two.
            discarding = true
            discardBuffered(output)
            action()
        }
    }

    /** Brings the level back up after a load. */
    fun fadeIn() {
        val output = synchronized(this) {
            track?.takeIf { it.state == AudioTrack.STATE_INITIALIZED }
        } ?: return
        fadeExecutor.execute {
            // Whatever arrived while the gate was closing is still old audio.
            discardBuffered(output)
            discarding = false
            ramp(output, 1f, FADE_IN_MS)
        }
    }

    /**
     * Empties the track's buffer and leaves it running again.
     *
     * `flush` is only honoured on a paused track, and the track has to be put
     * back into play afterwards or every following write blocks forever.
     */
    private fun discardBuffered(output: AudioTrack) {
        runCatching {
            output.pause()
            output.flush()
            output.play()
        }
        synchronized(this) {
            // The stretcher holds a window of the track being left behind; kept
            // across the change, those grains are resynthesised over the first
            // moments of the next one.
            stretcher?.release()
            stretcher = null
        }
    }

    /**
     * Walks the track's volume to [target].
     *
     * A volume ramp rather than a gain applied to the samples: it costs nothing
     * per frame, and it reaches the audio already queued in the track's buffer —
     * scaling the PCM on the way in would leave whatever is already buffered
     * playing at full level after the fade had supposedly finished.
     */
    private fun ramp(output: AudioTrack, target: Float, durationMs: Long) {
        val steps = (durationMs / FADE_STEP_MS).toInt().coerceAtLeast(1)
        val start = currentGain
        for (step in 1..steps) {
            val gain = start + (target - start) * step / steps
            currentGain = gain
            if (runCatching { output.setVolume(gain) }.isFailure) return
            try {
                Thread.sleep(FADE_STEP_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        currentGain = target
    }

    /**
     * Writes one packet of PCM.
     *
     * @param data direct buffer owned by the caller, valid only for this call
     * @param sizeInBytes bytes to consume from [data]
     */
    @Suppress("unused")
    fun write(data: ByteBuffer, sizeInBytes: Int, sampleRate: Int, channels: Int) {
        // Returning rather than blocking: the sink is being told this audio is
        // unwanted, and holding the decoder here would only delay the load that
        // closed the gate in the first place.
        if (discarding) return

        val output = synchronized(this) { ensureTrack(sampleRate, channels) } ?: return

        val currentSpeed = speed
        val currentPitch = pitch

        // Only pay for the stretcher when it would actually change something.
        // At 1.0/1.0 Bungee is not a no-op — it still analyses and resynthesises
        // every grain — so bypassing it keeps normal playback exactly as it was.
        val stretched = if (currentSpeed != 1f || currentPitch != 1f) {
            synchronized(this) {
                stretcher(sampleRate, channels)
                    ?.process(data, sizeInBytes, currentSpeed, currentPitch)
            }
        } else {
            null
        }

        if (stretched != null) {
            writeAll(output, stretched, stretched.remaining())
            return
        }

        writeAll(output, data, sizeInBytes)
    }

    private fun writeAll(output: AudioTrack, buffer: ByteBuffer, sizeInBytes: Int) {
        var written = 0
        while (written < sizeInBytes) {
            // WRITE_BLOCKING returns short only on error or when the track is
            // stopped, so a non-positive result must break the loop or this
            // spins.
            val result = output.write(buffer, sizeInBytes - written, AudioTrack.WRITE_BLOCKING)
            if (result <= 0) return
            written += result
        }
    }

    /**
     * The stretcher for the current stream format, built on first use.
     *
     * Tied to the format because the native side is constructed with a sample
     * rate and channel count and keeps streaming state across calls; a format
     * change has to start a new one rather than reuse the old.
     */
    private fun stretcher(sampleRate: Int, channels: Int): Stretcher? {
        stretcher?.let { return it }

        val created = Stretcher.create(sampleRate, channels, MAX_STRETCH_FRAMES)
        if (created == null) {
            if (!stretchFailureReported) {
                stretchFailureReported = true
                android.util.Log.w(TAG, "stretcher unavailable; playing at normal speed")
            }
            return null
        }
        stretcher = created
        return created
    }

    fun release() {
        synchronized(this) {
            reverb?.runCatching { release() }
            reverb = null
            stretcher?.release()
            stretcher = null
            track?.run {
                runCatching { pause() }
                runCatching { flush() }
                release()
            }
            track = null
            configuredSampleRate = 0
            configuredChannels = 0
        }
    }

    /** Rebuilds the track when the stream format changes, otherwise reuses it. */
    private fun ensureTrack(sampleRate: Int, channels: Int): AudioTrack? {
        val existing = track
        if (existing != null &&
            configuredSampleRate == sampleRate &&
            configuredChannels == channels
        ) {
            return existing
        }

        existing?.run {
            runCatching { pause() }
            release()
        }
        // Bound to the old format's rate and channel count.
        stretcher?.release()
        stretcher = null

        val channelMask = if (channels == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            android.util.Log.e(TAG, "unsupported output format: $sampleRate Hz, $channels ch")
            track = null
            return null
        }

        // Four times the reported minimum. The minimum is the point at which the
        // track underruns if anything at all is late, and decoding competes with
        // the UI and the network on a phone; the extra latency is irrelevant for
        // music playback and buys enough slack to stop the dropouts.
        val bufferSize = minBuffer * BUFFER_MULTIPLIER

        val created = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (created.state != AudioTrack.STATE_INITIALIZED) {
            android.util.Log.e(TAG, "AudioTrack failed to initialise")
            created.release()
            track = null
            return null
        }

        android.util.Log.i(
            TAG,
            "AudioTrack ready: $sampleRate Hz, $channels ch, buffer ${bufferSize}B " +
                "(min ${minBuffer}B)",
        )

        // Silent until something fades it up, so the first track of a session
        // arrives the same way every later one does — and if playback has
        // already started, that something is here: `start` may well have run
        // before this track existed.
        runCatching { created.setVolume(0f) }
        currentGain = 0f
        created.play()
        if (playing) {
            fadeExecutor.execute { ramp(created, 1f, FADE_IN_MS) }
        }
        track = created
        configuredSampleRate = sampleRate
        configuredChannels = channels
        // The old track took its effect and its playback params with it, so both
        // have to be put back or a format change silently resets the sound.
        applyReverb()
        return created
    }

    private companion object {
        const val TAG = "SpotAudio"
        const val BUFFER_MULTIPLIER = 4

        const val MIN_RATE = 0.25f
        const val MAX_RATE = 3f

        /**
         * Fade lengths. Long enough to soften the edge, short enough that
         * pressing pause still feels like it happened when you pressed it —
         * past about a quarter of a second the control starts to feel laggy
         * rather than smooth.
         */
        const val FADE_IN_MS = 260L
        const val FADE_OUT_MS = 200L
        const val FADE_STEP_MS = 10L

        /** Decay range, in milliseconds: a live room up to a large hall. */
        const val MIN_DECAY_MS = 500f
        const val MAX_DECAY_MS = 4500f

        /**
         * Reverb level in millibels. The API allows down to -9000, which is
         * inaudible, so the useful part of the range starts much higher.
         */
        const val MIN_LEVEL_MB = -2600f
        const val MAX_LEVEL_MB = -200f

        /**
         * Master room level, in millibels. 0 is unattenuated; the API default
         * of -9000 is inaudible.
         */
        const val MIN_ROOM_MB = -2000f
        const val MAX_ROOM_MB = 0f

        /**
         * Largest packet the stretcher is sized for. librespot's packets are a
         * few thousand frames; this leaves generous room, and anything larger
         * falls back to unmodified playback rather than being dropped.
         */
        const val MAX_STRETCH_FRAMES = 16384
    }
}
