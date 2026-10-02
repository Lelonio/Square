package dev.lelonio.square.backend.youtube

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One speaker for the YouTube players, the way the Spotify engine has one.
 *
 * ExoPlayer gives every player its own `AudioTrack`, and two tracks cannot be
 * lined up closer than their reported positions allow, which on this hardware
 * is far enough apart to hear a stretch of the outgoing song twice when it is
 * handed from one player to the other. Here each player keeps its decoder and
 * its processors (speed, pitch, karaoke) and writes the finished PCM into a
 * [MixerChannel]; one thread sums the channels into one track. Two copies of
 * the same song can then be swapped at the very sample, see [handOver].
 *
 * Fixed format: float stereo at [RATE]. The players resample to it with Sonic
 * (see YouTubePlayerFactory); anything else falls back to an ordinary track.
 */
@UnstableApi
class PcmMixer {

    private val lock = Object()
    private val channels = mutableListOf<MixerChannel>()
    private val track: AudioTrack
    private val thread: Thread
    @Volatile private var running = true

    /** Frames handed to the track since it was built. */
    @Volatile private var framesWritten = 0L

    // What the track has played, as last read, and when. Read from the players'
    // threads through [playedFrames]; written by the mixing thread.
    private val clockLock = Object()
    private var headFrames = 0L
    private var headNanos = 0L
    private var headAdvancing = false
    private var lastRawHead = 0L
    private var headWraps = 0L
    private var lastPlayed = 0L
    private val timestamp = android.media.AudioTimestamp()

    private var pending: Handover? = null
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SquareMixerAlign").apply { isDaemon = true }
    }

    init {
        val minBuffer = AudioTrack.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT,
        )
        track = AudioTrack.Builder()
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            // Short: a fade is applied as the audio is mixed, so the track's
            // buffer is how late every volume change is heard.
            .setBufferSizeInBytes(max(minBuffer, TRACK_BUFFER_FRAMES * 2 * 4))
            .build()
        thread = Thread(::loop, "SquareMixer").apply {
            isDaemon = true
            start()
        }
    }

    val audioSessionId: Int get() = track.audioSessionId

    internal fun add(channel: MixerChannel) = synchronized(lock) {
        channels += channel
        lock.notifyAll()
    }

    internal fun remove(channel: MixerChannel) = synchronized(lock) {
        channels -= channel
        lock.notifyAll()
    }

    internal fun wake() = synchronized(lock) { lock.notifyAll() }

    /** Frames of the track that have reached the speaker, or near enough. */
    internal fun playedFrames(): Long = synchronized(clockLock) {
        val estimate = if (!headAdvancing) {
            headFrames
        } else {
            val since = (System.nanoTime() - headNanos).coerceIn(0, MAX_EXTRAPOLATION_NS)
            min(headFrames + since * RATE / 1_000_000_000L, framesWritten)
        }
        // Never backwards: a video is shown against this clock, and a clock
        // that steps back a few ms and forward again holds and drops frames.
        lastPlayed = max(lastPlayed, estimate)
        lastPlayed
    }

    /**
     * Moves what [from] is playing onto [to], which must be playing the same
     * song a little ahead, held back by [MixerSlot.held] so its frames pile up.
     *
     * The last moment of [from] is looked for in [to]'s pile; [to] then starts
     * from the frame after it, in the same block in which [from] goes silent,
     * with a block-long dissolve between the two. [done] is called on the
     * mixing thread: false when the two could not be matched.
     */
    fun handOver(from: MixerSlot, to: MixerSlot, done: (Boolean) -> Unit) {
        synchronized(lock) {
            pending?.done?.invoke(false)
            pending = Handover(from, to, done, System.nanoTime())
            lock.notifyAll()
        }
    }

    /**
     * Lets what [slot] has already decoded play out on its own, fading to
     * nothing over [fadeMs], while the player moves on: the outgoing half of a
     * change of song the listener asked for, as the engine has it. The pile
     * holds up to two seconds ahead, so there is no decoder to wait for.
     *
     * False when there is nothing to fade.
     */
    fun detach(slot: MixerSlot, fadeMs: Int): Boolean = synchronized(lock) {
        val channel = slot.channel ?: return false
        if (!channel.playing || channel.queuedFrames() == 0) return false
        channel.orphan(slot.gain(), max(1, fadeMs * RATE / 1000))
        slot.channel = null
        lock.notifyAll()
        true
    }

    fun cancelHandOver() = synchronized(lock) {
        pending?.done?.invoke(false)
        pending = null
    }

    fun release() {
        running = false
        wake()
        thread.join(500)
        worker.shutdownNow()
        runCatching { track.stop() }
        track.release()
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val block = FloatArray(BLOCK_FRAMES * 2)
        while (running) {
            val active: List<MixerChannel>
            synchronized(lock) {
                channels.removeAll { it.finished }
                while (running && channels.none { it.playing }) {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.pause()
                        readHead(advancing = false)
                    }
                    lock.wait(250)
                }
                active = channels.filter { it.playing }
            }
            if (!running) break
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
            block.fill(0f)
            runHandover()
            for (channel in active) {
                if (channel.slot.held && !channel.orphaned) continue
                channel.mixInto(block, BLOCK_FRAMES, framesWritten)
            }
            var offset = 0
            while (offset < block.size && running) {
                val wrote = track.write(block, offset, block.size - offset, AudioTrack.WRITE_BLOCKING)
                if (wrote < 0) break
                offset += wrote
            }
            framesWritten += BLOCK_FRAMES
            readHead(advancing = true)
        }
    }

    /**
     * Where the track is, and since when. The timestamp says both exactly, once
     * the track has one; the head position only moves in the output's periods,
     * so it is dated by when it last moved, not by when it was read.
     */
    private fun readHead(advancing: Boolean) {
        val now = System.nanoTime()
        val stamped = track.getTimestamp(timestamp) && now - timestamp.nanoTime in 0..STALE_TIMESTAMP_NS
        val raw = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
        synchronized(clockLock) {
            if (raw < lastRawHead) headWraps++
            val moved = raw != lastRawHead
            lastRawHead = raw
            if (stamped && advancing) {
                headFrames = timestamp.framePosition
                headNanos = timestamp.nanoTime
            } else if (moved || !advancing || !headAdvancing) {
                headFrames = (headWraps shl 32) + raw
                headNanos = now
            }
            headAdvancing = advancing
        }
    }

    private fun runHandover() {
        val request = synchronized(lock) { pending } ?: return
        fun finish(ok: Boolean) {
            synchronized(lock) { if (pending === request) pending = null }
            request.done(ok)
        }
        val source = request.from.channel
        val target = request.to.channel
        if (source == null || target == null || System.nanoTime() - request.startedNanos > HANDOVER_TIMEOUT_NS) {
            android.util.Log.w(TAG, "handover gave up: source=${source != null} target=${target != null}")
            finish(false)
            return
        }
        val result = request.result
        if (request.computing && result == null) return
        if (result != null && (result.source !== source || result.target !== target)) {
            request.result = null
            request.computing = false
            return
        }
        if (result == null) {
            // Wait until the source has said enough to be recognised and the
            // target has piled up enough to recognise it in.
            if (source.historyFrames() < REF_FRAMES || target.queuedFrames() < MIN_PILE_FRAMES) return
            val reference = source.history(REF_FRAMES)
            val consumed = source.consumedFrames()
            val pile = target.pileMono()
            // Where the timestamps put it: within a few tens of ms, never a bar
            // off, which in a looped track the correlation alone can be.
            val heardUs = source.mediaTimeAt(consumed)
            val expected = heardUs?.let { target.frameAt(it) }?.let { (it - target.consumedFrames()).toInt() }
            request.computing = true
            worker.execute {
                val (offset, score) = locate(reference, pile, expected?.minus(REF_FRAMES))
                request.result = Match(source, target, consumed, offset, score)
                request.computing = false
            }
            return
        }
        request.result = null
        val skip = result.offset + REF_FRAMES + (source.consumedFrames() - result.consumed)
        if (result.score < MIN_SCORE || skip < 0) {
            if (++request.attempts >= MAX_ATTEMPTS) finish(false)
            return
        }
        if (skip + BLOCK_FRAMES > target.queuedFrames()) {
            // The source has outrun the pile: more will not come while it is held full.
            if (target.queuedFrames() >= target.capacity - BLOCK_FRAMES || ++request.attempts >= MAX_ATTEMPTS) {
                finish(false)
            }
            return
        }
        target.skip(skip.toInt())
        request.from.fade = 0f
        request.to.fade = 1f
        request.to.held = false
        finish(true)
    }

    private class Handover(
        val from: MixerSlot,
        val to: MixerSlot,
        val done: (Boolean) -> Unit,
        val startedNanos: Long,
    ) {
        @Volatile var computing = false
        @Volatile var result: Match? = null
        var attempts = 0
    }

    private class Match(
        val source: MixerChannel,
        val target: MixerChannel,
        val consumed: Long,
        val offset: Int,
        val score: Float,
    )

    companion object {
        const val RATE = 48_000
        private const val BLOCK_FRAMES = 480
        private const val TRACK_BUFFER_FRAMES = 3_840
        private const val MAX_EXTRAPOLATION_NS = 100_000_000L
        /** A timestamp older than this is from before a pause, not a reading. */
        private const val STALE_TIMESTAMP_NS = 500_000_000L
        internal const val REF_FRAMES = 4_096
        private const val MIN_PILE_FRAMES = RATE / 2
        private const val MIN_SCORE = 0.6f
        private const val MAX_ATTEMPTS = 8
        private const val HANDOVER_TIMEOUT_NS = 3_000_000_000L
        private const val DECIMATION = 4
        /** How far from the timestamps' guess the match may be: 100 ms. */
        private const val SEARCH_RADIUS = RATE / 10
        internal const val TAG = "SquareMixer"

        /**
         * Where [reference] starts inside [pile], by normalised cross-correlation:
         * coarsely on both decimated, then sample by sample around the best.
         */
        internal fun locate(reference: FloatArray, pile: FloatArray, around: Int?): Pair<Int, Float> {
            if (pile.size < reference.size) return -1 to 0f
            val ref = decimate(reference)
            val hay = decimate(pile)
            val refEnergy = ref.sumOf { (it * it).toDouble() }
            if (refEnergy < 1e-6) return -1 to 0f
            val prefix = DoubleArray(hay.size + 1)
            for (i in hay.indices) prefix[i + 1] = prefix[i] + hay[i] * hay[i]
            var best = 0
            var bestScore = -1.0
            val first = around?.let { max(0, (it - SEARCH_RADIUS) / DECIMATION) } ?: 0
            val last = around?.let { min(hay.size - ref.size, (it + SEARCH_RADIUS) / DECIMATION) } ?: (hay.size - ref.size)
            if (first > last) return -1 to 0f
            for (k in first..last) {
                var dot = 0.0
                for (i in ref.indices) dot += ref[i] * hay[k + i]
                val energy = prefix[k + ref.size] - prefix[k]
                if (energy <= 0) continue
                val score = dot / sqrt(refEnergy * energy)
                if (score > bestScore) {
                    bestScore = score
                    best = k
                }
            }
            val fullEnergy = reference.sumOf { (it * it).toDouble() }
            var fine = best * DECIMATION
            var fineScore = -1.0
            val from = max(0, best * DECIMATION - 2 * DECIMATION)
            val to = min(pile.size - reference.size, best * DECIMATION + 2 * DECIMATION)
            for (k in from..to) {
                var dot = 0.0
                var energy = 0.0
                for (i in reference.indices) {
                    val v = pile[k + i]
                    dot += reference[i] * v
                    energy += v * v
                }
                if (energy <= 0) continue
                val score = dot / sqrt(fullEnergy * energy)
                if (score > fineScore) {
                    fineScore = score
                    fine = k
                }
            }
            return fine to fineScore.toFloat()
        }

        private fun decimate(samples: FloatArray): FloatArray {
            val out = FloatArray(samples.size / DECIMATION)
            for (i in out.indices) {
                var sum = 0f
                for (j in 0 until DECIMATION) sum += samples[i * DECIMATION + j]
                out[i] = sum / DECIMATION
            }
            return out
        }
    }
}

/**
 * One player's place in the mix, kept across the outputs ExoPlayer builds and
 * throws away (it releases its output on every seek).
 */
class MixerSlot(
    /** Whose volume this slot obeys: the tail follows the main player's, ducking included. */
    private val follows: MixerSlot? = null,
    initialFade: Float = 1f,
) {
    /** The controller's own multiplier, on top of the player's volume. */
    @Volatile var fade = initialFade

    /** While true the slot's frames are left to pile up rather than played. */
    @Volatile var held = false

    /** The volume the player itself asked for. */
    @Volatile internal var volume = 1f

    @Volatile internal var channel: MixerChannel? = null

    /**
     * Whether the player's audio goes through the mixer: true from its first
     * mixer output, and still true in the gap between one output and the next.
     */
    @Volatile var mixed = false
        internal set

    internal fun gain(): Float = fade * (follows ?: this).volume
}

/** One output of one player, as ExoPlayer sees it. */
@UnstableApi
class MixerChannel internal constructor(
    private val mixer: PcmMixer,
    internal val slot: MixerSlot,
    private val inputChannels: Int,
    private val floatInput: Boolean,
) : AudioOutput {

    internal val capacity = PcmMixer.RATE * 2
    private val ring = FloatArray(capacity * 2)
    private var readIndex = 0
    private var queued = 0
    private var consumed = 0L

    // (channel frame, media time in µs) at the start of each buffer written:
    // what lets the two copies of a song be lined up roughly before precisely.
    private val stamps = ArrayDeque<LongArray>()
    private var lastStampUs = Long.MIN_VALUE
    private var lastGain = -1f

    private val history = FloatArray(PcmMixer.REF_FRAMES)
    private var historyIndex = 0
    private var historyCount = 0

    // Which frames of the track carry which frames of this channel: (track frame
    // the block started at, channel frames consumed before it, frames in it).
    private val blocks = ArrayDeque<LongArray>()
    private var playedBase = 0L

    @Volatile internal var playing = false
    private val listeners = CopyOnWriteArraySet<AudioOutput.Listener>()
    private var released = false

    init {
        slot.channel = this
        mixer.add(this)
    }

    override fun play() {
        playing = true
        mixer.wake()
    }

    override fun pause() {
        // An orphan plays out whatever its player does next.
        if (!orphaned) playing = false
    }

    // Set by [orphan]: the gain it started from, how long its fade is, how far in.
    @Volatile internal var orphaned = false
        private set
    private var orphanFrom = 0f
    private var orphanTotal = 1
    private var orphanDone = 0

    /** True once an orphan has faded out or run dry; the mixer drops it then. */
    @Volatile internal var finished = false
        private set

    internal fun orphan(gain: Float, frames: Int) = synchronized(this) {
        orphanFrom = gain
        orphanTotal = frames
        orphanDone = 0
        orphaned = true
        playing = true
    }

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        val bytesPerSample = if (floatInput) 4 else 2
        val frameBytes = bytesPerSample * inputChannels
        synchronized(this) {
            if (released) return true
            val available = buffer.remaining() / frameBytes
            if (presentationTimeUs >= 0 && presentationTimeUs != lastStampUs) {
                stamps.addLast(longArrayOf(consumed + queued, presentationTimeUs))
                lastStampUs = presentationTimeUs
                while (stamps.size > MAX_STAMPS) stamps.removeFirst()
            }
            val frames = min(available, capacity - queued)
            val data = buffer.duplicate().order(ByteOrder.nativeOrder())
            var write = (readIndex + queued) % capacity
            for (f in 0 until frames) {
                val left: Float
                val right: Float
                if (floatInput) {
                    left = data.getFloat()
                    right = if (inputChannels > 1) data.getFloat() else left
                    repeat(inputChannels - 2) { data.getFloat() }
                } else {
                    left = data.getShort() / 32768f
                    right = if (inputChannels > 1) data.getShort() / 32768f else left
                    repeat(inputChannels - 2) { data.getShort() }
                }
                ring[write * 2] = left
                ring[write * 2 + 1] = right
                write = (write + 1) % capacity
            }
            queued += frames
            buffer.position(buffer.position() + frames * frameBytes)
            return frames == available
        }
    }

    /** Adds this channel's next frames to [block], which starts at [trackFrame]. */
    internal fun mixInto(block: FloatArray, frames: Int, trackFrame: Long) = synchronized(this) {
        val target = slot.gain()
        val start = if (lastGain < 0) target else lastGain
        lastGain = target
        val n = min(frames, queued)
        if (orphaned && (n == 0 || orphanDone >= orphanTotal)) {
            finished = true
            return@synchronized
        }
        if (n == 0) return@synchronized
        for (i in 0 until n) {
            val gain = if (orphaned) {
                val x = min(1f, (orphanDone + i).toFloat() / orphanTotal)
                orphanFrom * cos(x * PI.toFloat() / 2)
            } else {
                start + (target - start) * (i + 1) / frames
            }
            val left = ring[readIndex * 2]
            val right = ring[readIndex * 2 + 1]
            block[i * 2] += left * gain
            block[i * 2 + 1] += right * gain
            history[historyIndex] = (left + right) * 0.5f
            historyIndex = (historyIndex + 1) % history.size
            if (historyCount < history.size) historyCount++
            readIndex = (readIndex + 1) % capacity
        }
        queued -= n
        if (orphaned) orphanDone += n
        blocks.addLast(longArrayOf(trackFrame, consumed, n.toLong()))
        consumed += n
        while (blocks.size > MAX_BLOCKS) blocks.removeFirst().let { playedBase = it[1] + it[2] }
    }

    /** Drops [frames] from the front of the pile, counted as played. */
    internal fun skip(frames: Int) = synchronized(this) {
        val n = min(frames, queued)
        readIndex = (readIndex + n) % capacity
        queued -= n
        // Heard as a jump forward once the track reaches the next block, which
        // starts from here: the frames skipped were the source's to play.
        consumed += n
        lastGain = 0f
    }

    internal fun queuedFrames(): Int = synchronized(this) { queued }

    internal fun consumedFrames(): Long = synchronized(this) { consumed }

    internal fun historyFrames(): Int = synchronized(this) { historyCount }

    /** The last [frames] this channel played, oldest first, as mono. */
    internal fun history(frames: Int): FloatArray = synchronized(this) {
        FloatArray(frames) { i -> history[(historyIndex - frames + i + history.size * 2) % history.size] }
    }

    /** Everything piled up, oldest first, as mono. */
    /** The media time of channel frame [frame], from the stamps around it. */
    internal fun mediaTimeAt(frame: Long): Long? = synchronized(this) {
        val before = stamps.lastOrNull { it[0] <= frame } ?: return null
        val after = stamps.firstOrNull { it[0] > frame }
        if (after == null || after[0] == before[0]) {
            return before[1] + (frame - before[0]) * 1_000_000L / PcmMixer.RATE
        }
        before[1] + (after[1] - before[1]) * (frame - before[0]) / (after[0] - before[0])
    }

    /** The channel frame that carries media time [timeUs], from the stamps around it. */
    internal fun frameAt(timeUs: Long): Long? = synchronized(this) {
        val before = stamps.lastOrNull { it[1] <= timeUs } ?: return null
        val after = stamps.firstOrNull { it[1] > timeUs }
        if (after == null || after[1] == before[1]) {
            return before[0] + (timeUs - before[1]) * PcmMixer.RATE / 1_000_000L
        }
        before[0] + (after[0] - before[0]) * (timeUs - before[1]) / (after[1] - before[1])
    }

    internal fun pileMono(): FloatArray = synchronized(this) {
        FloatArray(queued) { i ->
            val at = (readIndex + i) % capacity
            (ring[at * 2] + ring[at * 2 + 1]) * 0.5f
        }
    }

    private fun playedFrames(): Long = synchronized(this) {
        val heard = mixer.playedFrames()
        while (blocks.isNotEmpty()) {
            val first = blocks.first()
            if (heard >= first[0] + first[2]) {
                playedBase = max(playedBase, first[1] + first[2])
                blocks.removeFirst()
            } else {
                break
            }
        }
        val first = blocks.firstOrNull() ?: return playedBase
        if (heard <= first[0]) return playedBase
        first[1] + (heard - first[0])
    }

    override fun flush() = synchronized(this) {
        if (orphaned) return@synchronized
        readIndex = 0
        queued = 0
        consumed = 0
        playedBase = 0
        blocks.clear()
        stamps.clear()
        lastStampUs = Long.MIN_VALUE
        historyCount = 0
    }

    override fun stop() = Unit

    override fun release() {
        synchronized(this) {
            if (released) return
            released = true
            if (!orphaned) playing = false
        }
        if (slot.channel === this) slot.channel = null
        // An orphan stays in the mix until its fade is over; see PcmMixer.detach.
        if (!orphaned) mixer.remove(this)
        listeners.forEach { it.onReleased() }
    }

    override fun setVolume(volume: Float) {
        slot.volume = volume
    }

    override fun isOffloadedPlayback() = false
    override fun getAudioSessionId() = mixer.audioSessionId
    override fun getSampleRate() = PcmMixer.RATE
    override fun getBufferSizeInFrames() = capacity.toLong()
    override fun getPositionUs(): Long = playedFrames() * 1_000_000L / PcmMixer.RATE
    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
    override fun isStalled() = false
    override fun addListener(listener: AudioOutput.Listener) { listeners += listener }
    override fun removeListener(listener: AudioOutput.Listener) { listeners -= listener }
    override fun setPlaybackParameters(playbackParams: PlaybackParameters) = Unit
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = Unit
    override fun setOffloadEndOfStream() = Unit
    override fun attachAuxEffect(effectId: Int) = Unit
    override fun setAuxEffectSendLevel(level: Float) = Unit
    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) = Unit

    private companion object {
        /** About two seconds of blocks: far more than the track ever holds unplayed. */
        const val MAX_BLOCKS = 200

        /** Buffers are a few ms each: comfortably over the two-second pile. */
        const val MAX_STAMPS = 2_000
    }
}

/**
 * Hands ExoPlayer a [MixerChannel] for every output it asks for in the mixer's
 * format, and an ordinary track for anything else.
 */
@UnstableApi
class MixerOutputProvider(
    context: Context,
    private val mixer: PcmMixer,
    private val slot: MixerSlot,
) : ForwardingAudioOutputProvider(AudioTrackAudioOutputProvider.Builder(context).build()) {

    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        val channels = Integer.bitCount(config.channelMask)
        val mixable = config.sampleRate == PcmMixer.RATE &&
            (config.encoding == C.ENCODING_PCM_16BIT || config.encoding == C.ENCODING_PCM_FLOAT) &&
            channels in 1..8 && !config.isOffload && !config.isTunneling
        if (!mixable) {
            android.util.Log.w(PcmMixer.TAG, "not mixable: ${config.sampleRate} Hz enc=${config.encoding} ch=$channels")
            slot.channel = null
            slot.mixed = false
            return super.getAudioOutput(config)
        }
        slot.mixed = true
        return MixerChannel(mixer, slot, channels, config.encoding == C.ENCODING_PCM_FLOAT)
    }
}
