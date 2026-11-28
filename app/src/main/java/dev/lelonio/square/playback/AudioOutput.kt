package dev.lelonio.square.playback

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
    private var configuredEncoding = AudioFormat.ENCODING_PCM_16BIT

    private var reverb: EnvironmentalReverb? = null
    private var auxAttached = false

    private var writtenFrames = 0L
    private var lastPlaybackHead = 0L
    private var playbackHeadWraps = 0L

    @Volatile
    private var bufferGeneration = 0L

    private val stretcherLock = Any()
    private var stretcherGeneration = -1L

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

    /**
     * Which stretcher does the work; see EffectQuality.
     *
     * Read on the audio thread, set from the settings, so it is volatile like
     * the rest of the values that cross that line.
     */
    @Volatile
    private var lightEffects = false

    /** Reverb amount, 0 (off) to 1. */
    @Volatile
    private var reverbAmount = 0f

    /** Where the fade currently is, so a new ramp starts from it. */
    @Volatile
    private var currentGain = 1f

    /**
     * How far another app has asked this output to step back (a voice note, a
     * navigation prompt), on top of whatever fade is running: 1 for not at all.
     */
    @Volatile
    private var duckFactor = 1f

    /**
     * Decibels put on what is written as float (Soloist's music, normalised by
     * Soloist to Spotify's target): the listener's normalisation amount, as
     * librespot's pregain is for its own. A limiter keeps the loudest moments
     * from clipping, as librespot's does. 0 leaves the samples untouched.
     */
    @Volatile
    private var floatGain = 1f
    private var limiterGain = 1f

    fun setFloatGainDb(db: Float) {
        floatGain = Math.pow(10.0, db / 20.0).toFloat()
    }

    /** [floatGain] on [samples] floats, in place, held under full scale. */
    private fun applyFloatGain(floats: java.nio.FloatBuffer, samples: Int, channels: Int) {
        val gain = floatGain
        if (gain == 1f && limiterGain == 1f) return
        val step = channels.coerceAtLeast(1)
        var reduction = limiterGain
        var i = 0
        while (i + step <= samples) {
            var peak = 0f
            for (c in 0 until step) peak = maxOf(peak, kotlin.math.abs(floats.get(i + c) * gain))
            // At once on a peak, back over a tenth of a second or so.
            val wanted = if (peak > LIMIT) LIMIT / peak else 1f
            reduction = if (wanted < reduction) wanted else reduction + (1f - reduction) * LIMITER_RELEASE
            for (c in 0 until step) floats.put(i + c, floats.get(i + c) * gain * reduction)
            i += step
        }
        limiterGain = if (reduction > 0.9999f) 1f else reduction
    }

    /**
     * Lowers the output under another app's sound, or brings it back; the
     * reverb's room goes down with it. For a player with no engine volume of
     * its own to lower (Soloist), see AudioFocusController.
     */
    fun duck(factor: Float) {
        val wanted = factor.coerceIn(0f, 1f)
        if (wanted == duckFactor) return
        duckFactor = wanted
        duckReverb(wanted)
        val output = synchronized(this) { track?.takeIf { it.state == AudioTrack.STATE_INITIALIZED } } ?: return
        runCatching { output.setVolume(currentGain * wanted) }
    }

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

    /** Paused by the player while the sink stays open; see [setPlaybackActive]. */
    @Volatile
    private var held = false

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

    /**
     * Chooses between the phase vocoder and the platform's own stretcher.
     *
     * Switching throws away whichever one was running: the Bungee stretcher
     * holds a window of audio that would otherwise be resynthesised over the
     * first moments of the new path, and the platform's has to be told to stop
     * altering a stream this app is about to alter itself.
     */
    fun setLightEffects(light: Boolean) {
        if (light == lightEffects) return
        lightEffects = light
        synchronized(this) {
            synchronized(stretcherLock) { resetStretcher() }
            applyPlatformRate()
        }
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
    /**
     * How far the room is held down while something else is talking.
     *
     * 1 at rest. The reverb is an auxiliary effect on the output mix and its
     * send does not pass through the track's volume — which is the whole reason
     * it can be attached at all — so a duck that quietens the music leaves the
     * room at full level, and a song ducked under a voice note comes back as
     * mostly reverb. See [duckReverb].
     */
    private var reverbDuck = 1f

    /**
     * Whether the room is inside this track's session rather than on the mix.
     *
     * A session effect needs no send level and no attachment: it is part of the
     * track. See [applyReverb] for why that is worth preferring.
     */
    private var reverbOnSession = false

    /**
     * Holds the room down by the same amount as everything else.
     *
     * Called from the player's own ducking, since that is where the decision is
     * made; the level is re-applied rather than remembered separately, so the
     * listener moving the reverb slider mid-duck still lands in the right place.
     */
    fun duckReverb(factor: Float) {
        synchronized(this) {
            val wanted = factor.coerceIn(0f, 1f)
            if (wanted == reverbDuck) return
            reverbDuck = wanted
            if (reverbAmount > 0f) applyReverb()
        }
    }

    private fun applyReverb() {
        val output = track
        val amount = reverbAmount

        // Only while this output is the one making sound. The room lives on the
        // phone's output mix and is shared by every handle to it (Soloist's
        // output and the Spotify engine's open the same one): built here for a
        // new track, a slider moved or a duck while paused, it stayed enabled
        // after the music stopped and every other app played into it.
        if (output == null || amount <= 0f || !playing || held || voice) {
            suspendReverb()
            return
        }

        val fresh = reverb == null
        // On the output mix, always. It used to go inside this track's own
        // session where the device allowed it, so that the system's own
        // ducking took the room down with the music. But on a session the
        // platform builds the *insert* Environmental Reverb, which takes over
        // the track's volume (EFFECT_FLAG_VOLUME_CTRL) and on some phones
        // never applies it: with the reverb on, the music played at full
        // volume whatever the volume keys said, muted included (OnePlus,
        // Android 17). The ducking that the session was for is now the app's
        // own (AudioFocusController asks to be told rather than ducked), and
        // [duckReverb] takes the room down with it.
        val effect = reverb ?: run {
            runCatching {
                // Priority 0: no reason to outbid anything else on the mix.
                reverbOnSession = false
                EnvironmentalReverb(0, 0)
            }.onFailure {
                android.util.Log.w(TAG, "reverb unavailable: ${it.message}")
            }.getOrNull()
        } ?: return

        reverb = effect
        runCatching {
            // The room itself is described in one place, since the players that
            // are not this one put a listener in the same one; see ReverbTuning.
            ReverbTuning.tune(effect, amount)
            effect.enabled = true

            // A session effect is already in the path; only an auxiliary one
            // has to be sent to.
            if (!reverbOnSession) {
                if (!auxAttached) auxAttached = output.attachAuxEffect(effect.id) == AudioTrack.SUCCESS
                output.setAuxEffectSendLevel(ReverbTuning.sendLevel(amount) * reverbDuck)
            }
            if (fresh) android.util.Log.i(TAG, "reverb on at $amount, id ${effect.id}")
        }.onFailure { android.util.Log.w(TAG, "reverb not applied: ${it.message}") }
    }

    /** Called by the sink when playback begins. */
    @Suppress("unused")
    fun start() {
        // A gate left closed by a load that never got its fade-in — a track
        // loaded paused, say — would otherwise silence the next play outright.
        synchronized(this) {
            val generation = fadeGeneration.incrementAndGet()
            discarding = false
            playing = true
            held = false
            val output = track?.takeIf { it.state == AudioTrack.STATE_INITIALIZED } ?: return
            runCatching {
                output.setVolume(0f)
                currentGain = 0f
                output.play()
            }
            applyReverb()
            fadeExecutor.execute { ramp(output, 1f, FADE_IN_MS, generation) }
        }
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
        val (output, generation) = synchronized(this) {
            playing = false
            if (voice) {
                voice = false
                dev.lelonio.square.backend.spotify.SpotifyDj.setSpeaking(false)
            }
            track to fadeGeneration.incrementAndGet()
        }
        if (output != null && !held && output.playState == AudioTrack.PLAYSTATE_PLAYING) {
            ramp(output, 0f, FADE_OUT_MS, generation)
        }
        synchronized(this) {
            if (generation != fadeGeneration.get() || output !== track) return
            discarding = false
            if (output != null) flushBuffered(output, resume = false)
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
    /**
     * Called by the player on pause and on resume.
     *
     * The sink's own [start]/[stop] are not enough: librespot keeps the sink
     * open across a pause, so nothing here would run and the reverb would sit on
     * the output mix ringing out and colouring every other sound the phone
     * makes. This is the pause the *player* knows about.
     */
    fun setPlaybackActive(active: Boolean) {
        synchronized(this) {
            val resumed = active && held
            held = !active
            val output = track?.takeIf { it.state == AudioTrack.STATE_INITIALIZED }
            if (resumed) {
                val generation = fadeGeneration.incrementAndGet()
                if (output != null && !discarding) {
                    fadeExecutor.execute { ramp(output, 1f, FADE_IN_MS, generation) }
                }
            }
            output?.runCatching {
                if (active && playState != AudioTrack.PLAYSTATE_PLAYING) play()
                if (!active && playState == AudioTrack.PLAYSTATE_PLAYING) pause()
            }
            if (active) applyReverb() else suspendReverb()
        }
    }

    private fun suspendReverb() {
        // Only an auxiliary effect has a send to close; a session one goes with
        // the track it lives in.
        if (auxAttached) {
            track?.runCatching {
                setAuxEffectSendLevel(0f)
                attachAuxEffect(0)
            }
            auxAttached = false
        }
        reverbOnSession = false
        dropReverb()
    }

    /**
     * Throws away the reverb, and with it whatever is still ringing inside it.
     *
     * Released, not just disabled. Disabling stops the effect processing but
     * leaves it on the output mix, and the tail already inside it went on
     * ringing out over whatever came next. Releasing it takes it off the mix
     * entirely; the next play builds a fresh one, which starts silent by
     * definition.
     */
    private fun dropReverb() {
        reverb?.let { android.util.Log.i(TAG, "reverb off, id ${it.id}") }
        reverb?.runCatching {
            enabled = false
            release()
        }
        reverb = null
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
        fadeOutThen(SKIP_FADE_OUT_MS, action)
    }

    fun fadeOutThen(durationMs: Long, action: () -> Unit) {
        val output = synchronized(this) {
            track?.takeIf { it.state == AudioTrack.STATE_INITIALIZED }
        }
        if (output == null) {
            action()
            return
        }
        val generation = fadeGeneration.incrementAndGet()
        fadeExecutor.execute {
            ramp(output, 0f, durationMs, generation)
            // Order matters: the gate closes before the buffer is emptied, so
            // nothing can slip back in between the two.
            synchronized(this) {
                if (generation == fadeGeneration.get() && output === track) {
                    discarding = true
                    flushBuffered(output, resume = !held)
                    suspendReverb()
                }
            }
            action()
        }
    }

    /** Brings the level back up after a load. */
    fun fadeIn() {
        val output = synchronized(this) {
            track?.takeIf { it.state == AudioTrack.STATE_INITIALIZED }
        } ?: return
        val generation = fadeGeneration.incrementAndGet()
        fadeExecutor.execute {
            // Whatever arrived while the gate was closing is still old audio.
            synchronized(this) {
                if (generation != fadeGeneration.get() || output !== track) return@execute
                flushBuffered(output, resume = !held)
                suspendReverb()
                discarding = false
            }
            ramp(output, 1f, FADE_IN_MS, generation)
        }
    }

    /**
     * Which fade is the current one.
     *
     * Fades run one at a time on a single thread, and a fade-in takes a quarter
     * of a second: a skip during one used to wait for it to finish before its
     * own fade could even start, which is a quarter of a second between the tap
     * and the engine hearing about it. A ramp now stops as soon as a later one
     * is asked for, and the later one begins immediately.
     */
    private val fadeGeneration = java.util.concurrent.atomic.AtomicLong(0)

    /** Reopens the PCM gate when the player confirms the replacement track. */
    fun finishTrackChange() {
        synchronized(this) {
            val generation = fadeGeneration.incrementAndGet()
            discarding = false
            val output = track ?: return
            fadeExecutor.execute { ramp(output, 1f, FADE_IN_MS, generation) }
        }
    }

    fun isDiscarding(): Boolean = discarding

    fun flushForSeek() {
        synchronized(this) {
            track?.let {
                flushBuffered(it, resume = !held && (playing || it.playState == AudioTrack.PLAYSTATE_PLAYING))
            }
        }
    }

    fun bufferedLatencyMs(): Long = synchronized(this) {
        val output = track ?: return@synchronized 0L
        if (output.playState != AudioTrack.PLAYSTATE_PLAYING || configuredSampleRate <= 0) {
            return@synchronized 0L
        }
        val head = output.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        if (lastPlaybackHead - head > 0x80000000L) playbackHeadWraps++
        lastPlaybackHead = head
        val playedFrames = (playbackHeadWraps shl 32) + head
        ((writtenFrames - playedFrames).coerceAtLeast(0L) * 1000L / configuredSampleRate)
            .coerceAtMost(1500L)
    }

    /**
     * Media milliseconds currently queued in [AudioTrack] ahead of the speaker.
     *
     * In light-effects mode, [AudioTrack] itself applies [speed], so the queued
     * source frames already represent media time. In Bungee mode, queued frames
     * are stretched real-time output frames, so scaling by [speed] converts them
     * back to media milliseconds.
     */
    fun bufferedMediaLatencyMs(): Long = synchronized(this) {
        val rawMs = bufferedLatencyMs()
        if (rawMs <= 0L || lightEffects) rawMs else (rawMs * speed).toLong().coerceIn(0L, 1500L)
    }

    // The next writer resets the stretcher before processing this generation.
    private fun resetBufferedPosition() {
        bufferGeneration++
        writtenFrames = 0L
        lastPlaybackHead = 0L
        playbackHeadWraps = 0L
    }

    private fun flushBuffered(output: AudioTrack, resume: Boolean) {
        runCatching {
            if (output.playState == AudioTrack.PLAYSTATE_PLAYING) output.pause()
            output.flush()
            resetBufferedPosition()
            if (resume) output.play()
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
    private fun ramp(
        output: AudioTrack,
        target: Float,
        durationMs: Long,
        generation: Long,
    ) {
        val steps = (durationMs / FADE_STEP_MS).toInt().coerceAtLeast(1)
        val start = currentGain
        for (step in 1..steps) {
            if (fadeGeneration.get() != generation) return
            val gain = start + (target - start) * step / steps
            currentGain = gain
            if (runCatching { output.setVolume(gain * duckFactor) }.isFailure) return
            try {
                Thread.sleep(durationMs * step / steps - durationMs * (step - 1) / steps)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        if (fadeGeneration.get() == generation) currentGain = target
    }

    /**
     * Writes one packet of PCM.
     *
     * @param data direct buffer owned by the caller, valid only for this call
     * @param sizeInBytes bytes to consume from [data]
     */
    @Suppress("unused")
    /** Microseconds of music taken in, ever (16-bit and float alike): see [takenMs]. */
    private val takenUs = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * How much of the song has been handed to this output, in milliseconds:
     * a clock that runs at the tempo the music is really played at.
     */
    fun takenMs(): Double = takenUs.get() / 1000.0

    fun write(data: ByteBuffer, sizeInBytes: Int, sampleRate: Int, channels: Int) {
        // Returning rather than blocking: the sink is being told this audio is
        // unwanted, and holding the decoder here would only delay the load that
        // closed the gate in the first place.
        if (discarding) return
        if (!floatIn) {
            takenUs.addAndGet(sizeInBytes * 1_000_000L / (sampleRate.toLong() * channels * 2).coerceAtLeast(1))
            AudioLevel.ofShorts(data.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer(), sizeInBytes / 2)
        }
        // 16-bit through the effects: not what a DAC opened bit-perfect is for.
        HiFi.release()

        val (output, generation) = synchronized(this) {
            val output = ensureTrack(sampleRate, channels) ?: return
            output to bufferGeneration
        }
        if (voice) leaveVoice()

        val currentSpeed = speed
        val currentPitch = pitch

        // Before anything else touches the audio: this reads where each sound
        // sits between the two channels, and a stretcher that has resynthesised
        // them no longer has the same two channels. See CentreExtractor.
        val karaoke = AudioEffects.karaoke.value
        if (karaoke > 0f) {
            vocals.apply(data, sizeInBytes, channels, sampleRate, karaoke)
        }

        // The platform does it below this app when the light path is chosen, so
        // the packets go out untouched and the rate is set on the track itself.
        if (lightEffects) {
            synchronized(this) { applyPlatformRate() }
            equalizer.apply(data, sizeInBytes, channels, sampleRate)
            writeAll(output, data, sizeInBytes, generation)
            return
        }

        // Only pay for the stretcher when it would actually change something.
        // At 1.0/1.0 Bungee is not a no-op — it still analyses and resynthesises
        // every grain — so bypassing it keeps normal playback exactly as it was.
        val stretched = if (currentSpeed != 1f || currentPitch != 1f) {
            synchronized(stretcherLock) {
                if (generation != bufferGeneration) return
                if (stretcherGeneration != generation) {
                    resetStretcher()
                    stretcherGeneration = generation
                }
                stretcher(sampleRate, channels)
                    ?.process(data.duplicate(), sizeInBytes, currentSpeed, currentPitch)
            }
        } else {
            null
        }

        if (stretched != null) {
            val size = stretched.remaining()
            // After the stretch, as in the other players' chain; see
            // EqualizerAudioProcessor. Its own view of the buffer, so the
            // equaliser's reads leave the position the write starts from.
            equalizer.apply(stretched.duplicate(), size, channels, sampleRate)
            writeAll(output, stretched, size, generation)
            return
        }

        equalizer.apply(data, sizeInBytes, channels, sampleRate)
        writeAll(output, data, sizeInBytes, generation)
    }

    /** Nothing the listener set changes the sound: no curve, no karaoke, no stretch, no room. */
    private fun effectsNeutral(): Boolean =
        AudioEffects.karaoke.value == 0f && speed == 1f && pitch == 1f &&
            !equalizer.isActive && reverbAmount == 0f

    /**
     * Interleaved little-endian 32-bit float, as Soloist decodes lossless
     * FLAC (up to 24-bit).
     *
     * With every effect neutral it goes out as it is: a float track, or, with
     * a USB DAC that takes it, the format the DAC is opened in bit-perfect
     * ([HiFi]), so Android neither mixes nor resamples it. With any effect on,
     * the sound is reworked anyway and goes through the same 16-bit chain as
     * everything else.
     */
    /** Set while [writeFloat] hands its samples on to [write], so they are counted once. */
    @Volatile
    private var floatIn = false

    fun writeFloat(data: ByteBuffer, sizeInBytes: Int, sampleRate: Int, channels: Int) {
        if (discarding) return
        takenUs.addAndGet(sizeInBytes * 1_000_000L / (sampleRate.toLong() * channels * 4).coerceAtLeast(1))
        val samples = sizeInBytes / 4
        // A view of its own: the caller's buffer keeps the byte order it had.
        val floats = data.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        applyFloatGain(floats, samples, channels)
        AudioLevel.ofFloats(floats, samples)

        val neutral = effectsNeutral()
        if (neutral != wasNeutral) {
            wasNeutral = neutral
            android.util.Log.i(
                TAG,
                if (neutral) "lossless path: as decoded" else "lossless path off: equaliser ${equalizer.isActive}, " +
                    "karaoke ${AudioEffects.karaoke.value}, speed $speed, pitch $pitch, reverb $reverbAmount",
            )
        }
        if (!neutral) {
            HiFi.release()
            val out = scratch(samples * 2)
            for (i in 0 until samples) {
                out.putShort((floats.get(i) * 32767f).coerceIn(-32768f, 32767f).toInt().toShort())
            }
            out.flip()
            floatIn = true
            try {
                write(out, samples * 2, sampleRate, channels)
            } finally {
                floatIn = false
            }
            return
        }

        val encoding = HiFi.encodingFor(sampleRate, channels)
        val (output, generation) = synchronized(this) {
            val output = ensureTrack(sampleRate, channels, encoding) ?: return
            output to bufferGeneration
        }
        if (voice) leaveVoice()
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> writeAll(output, data, sizeInBytes, generation)
            AudioFormat.ENCODING_PCM_32BIT -> {
                val out = scratch(samples * 4)
                for (i in 0 until samples) {
                    out.putInt((floats.get(i).toDouble() * 2147483647.0).coerceIn(-2147483648.0, 2147483647.0).toInt())
                }
                out.flip()
                writeAll(output, out, samples * 4, generation)
            }
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                val out = scratch(samples * 3)
                for (i in 0 until samples) {
                    val v = (floats.get(i) * 8388607f).coerceIn(-8388608f, 8388607f).toInt()
                    out.put(v.toByte()).put((v shr 8).toByte()).put((v shr 16).toByte())
                }
                out.flip()
                writeAll(output, out, samples * 3, generation)
            }
            else -> {
                val out = scratch(samples * 2)
                for (i in 0 until samples) {
                    out.putShort((floats.get(i) * 32767f).coerceIn(-32768f, 32767f).toInt().toShort())
                }
                out.flip()
                writeAll(output, out, samples * 2, generation)
            }
        }
    }

    private var wasNeutral: Boolean? = null

    private var scratchBuffer: ByteBuffer? = null

    private fun scratch(bytes: Int): ByteBuffer {
        val current = scratchBuffer
        val buffer = if (current != null && current.capacity() >= bytes) current else {
            ByteBuffer.allocateDirect(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).also { scratchBuffer = it }
        }
        buffer.clear()
        return buffer
    }

    /**
     * Puts the current speed and pitch on the AudioTrack itself.
     *
     * Only for the light path, and set rather than left alone even at 1.0: a
     * track keeps whatever rate it was given, so returning to normal speed has
     * to say so. Failures are ignored on purpose, since a device that refuses a
     * rate should still play the music.
     */
    private fun applyPlatformRate() {
        val output = track?.takeIf { it.state == AudioTrack.STATE_INITIALIZED } ?: return
        val wantedSpeed = if (lightEffects) speed else 1f
        val wantedPitch = if (lightEffects) pitch else 1f
        if (wantedSpeed == platformSpeed && wantedPitch == platformPitch) return
        runCatching {
            output.playbackParams = output.playbackParams
                .setSpeed(wantedSpeed)
                .setPitch(wantedPitch)
            platformSpeed = wantedSpeed
            platformPitch = wantedPitch
        }.onFailure { android.util.Log.w(TAG, "the platform refused $wantedSpeed x: ${it.message}") }
    }

    /**
     * Writes one packet of Spotify's DJ talking: as it is, past every effect.
     *
     * Speed, pitch, karaoke and the room are the listener's choices about the
     * music, and the DJ is not the music: slowed and reverbed it was a voice
     * nobody chose to hear that way, and the karaoke took its words out. The
     * effects come back with the next packet of music. See `speaking` in the
     * vendored player for how the engine tells the two apart.
     */
    @Suppress("unused")
    fun writeVoice(data: ByteBuffer, sizeInBytes: Int, sampleRate: Int, channels: Int) {
        if (discarding) return
        val (output, generation) = synchronized(this) {
            val output = ensureTrack(sampleRate, channels) ?: return
            output to bufferGeneration
        }
        if (!voice) enterVoice(output)
        dev.lelonio.square.backend.spotify.SpotifyDj.hear(data, sizeInBytes)
        // The one thing the voice does go through: the curve is about the
        // headphones, not the music, and a voice on them needs it as much.
        equalizer.apply(data, sizeInBytes, channels, sampleRate)
        writeAll(output, data, sizeInBytes, generation)
    }

    /** Whether the packets being written are the DJ's voice; see [writeVoice]. */
    @Volatile
    private var voice = false

    private fun enterVoice(output: AudioTrack) {
        voice = true
        synchronized(this) {
            // What the stretcher holds is music, and would be heard after the
            // line; the room would ring with the voice.
            synchronized(stretcherLock) { resetStretcher() }
            suspendReverb()
            if (platformSpeed != 1f || platformPitch != 1f) {
                runCatching {
                    output.playbackParams = output.playbackParams.setSpeed(1f).setPitch(1f)
                    platformSpeed = 1f
                    platformPitch = 1f
                }
            }
        }
        dev.lelonio.square.backend.spotify.SpotifyDj.setSpeaking(true)
    }

    private fun leaveVoice() {
        voice = false
        synchronized(this) {
            if (reverbAmount > 0f) applyReverb()
            if (lightEffects) applyPlatformRate()
        }
        dev.lelonio.square.backend.spotify.SpotifyDj.setSpeaking(false)
    }

    /** Takes the middle of the stage away; see [CentreExtractor]. */
    private val vocals = CentreExtractor()

    /** The listener's curve for whatever is plugged in; see [Equalizer]. */
    private val equalizer = EqualizerFilter()

    /** What the track was last told, so it is not told again every packet. */
    private var platformSpeed = 1f
    private var platformPitch = 1f

    private fun writeAll(output: AudioTrack, buffer: ByteBuffer, sizeInBytes: Int, generation: Long) {
        val bytesPerFrame = synchronized(this) {
            if (output !== track || generation != bufferGeneration || discarding) return
            configuredChannels * bytesPerSample(configuredEncoding)
        }
        var written = 0
        while (written < sizeInBytes && generation == bufferGeneration && !discarding) {
            val result = runCatching {
                output.write(buffer, sizeInBytes - written, AudioTrack.WRITE_BLOCKING)
            }.getOrDefault(-1)
            if (result <= 0) return
            synchronized(this) {
                if (generation != bufferGeneration) return
                writtenFrames += (written + result) / bytesPerFrame - written / bytesPerFrame
            }
            written += result
        }
    }

    private fun resetStretcher() {
        stretcher?.release()
        stretcher = null
    }

    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        else -> 2
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
            fadeGeneration.incrementAndGet()
            suspendReverb()
            resetBufferedPosition()
            synchronized(stretcherLock) { resetStretcher() }
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
    private fun ensureTrack(
        sampleRate: Int,
        channels: Int,
        encoding: Int = AudioFormat.ENCODING_PCM_16BIT,
    ): AudioTrack? {
        val existing = track
        if (existing != null &&
            configuredSampleRate == sampleRate &&
            configuredChannels == channels &&
            configuredEncoding == encoding
        ) {
            return existing
        }

        existing?.run {
            runCatching { pause() }
            release()
        }
        auxAttached = false
        resetBufferedPosition()

        val channelMask = if (channels == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            encoding,
        )
        if (minBuffer <= 0) {
            android.util.Log.e(TAG, "unsupported output format: $sampleRate Hz, $channels ch")
            track = null
            return null
        }

        // Four times the reported minimum, with a floor of at least 500 ms of audio.
        // The minimum is the point at which the track underruns if anything at all is late,
        // and decoding competes with the UI and the network on a phone. Sizing for at least
        // 500 ms ensures that UI garbage collection (GC) or Compose frame animation bursts
        // will never cause audible dropouts or buffer starvation.
        val bytesPerMs = sampleRate * channels * bytesPerSample(encoding) / 1000
        val minBuffer500ms = bytesPerMs * 500
        val bufferSize = maxOf(minBuffer * BUFFER_MULTIPLIER, minBuffer500ms)

        val created = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
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
            "AudioTrack ready: $sampleRate Hz, $channels ch, encoding $encoding, buffer ${bufferSize}B " +
                "(min ${minBuffer}B)",
        )

        // Silent until something fades it up, so the first track of a session
        // arrives the same way every later one does — and if playback has
        // already started, that something is here: `start` may well have run
        // before this track existed.
        runCatching { created.setVolume(0f) }
        currentGain = 0f
        if (!held) created.play()
        if (playing && !held) {
            val generation = fadeGeneration.incrementAndGet()
            fadeExecutor.execute { ramp(created, 1f, FADE_IN_MS, generation) }
        }
        track = created
        configuredSampleRate = sampleRate
        configuredChannels = channels
        configuredEncoding = encoding
        // A new track knows nothing of the rate the old one was given.
        platformSpeed = 1f
        platformPitch = 1f
        applyPlatformRate()
        // The old track took its effect and its playback params with it, so both
        // have to be put back or a format change silently resets the sound.
        applyReverb()
        return created
    }

    private companion object {
        /** Where the limiter holds the music: half a decibel under full scale. */
        const val LIMIT = 0.944f

        /** How far back to no reduction each frame: about a tenth of a second at 44.1 kHz. */
        const val LIMITER_RELEASE = 0.0002f

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
        const val FADE_OUT_MS = 25L

        /** Default fade for a track change. */
        const val SKIP_FADE_OUT_MS = 70L
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
