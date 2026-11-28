package dev.lelonio.square.playback.soloist

import android.util.Log
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Soloist's audio into Square's own output.
 *
 * Soloist plays through our libpulse.so.0, which with SQUARE_PCM_FIFO set
 * writes packets to a FIFO this reads: a format packet when a stream begins,
 * 16-bit PCM, and pause, resume and flush. The PCM goes to the same
 * AudioOutput the Spotify engine writes to, so karaoke, tempo and pitch, the
 * equaliser and the reverb apply to it as they do to everything else. The
 * pace is the AudioTrack's: a write blocks until it has room, the read stops
 * with it, and Soloist's writes stop behind that.
 */
object SoloistAudio {
    /** How soon after a flush a cork is Soloist changing song rather than pausing. */
    private const val CHANGE_CORK_MS = 3_000L

    private const val TAG = "SoloistAudio"

    @Volatile
    private var reader: Thread? = null

    /**
     * Bumped when the song after a dissolve is first heard (libpulse's 'N'):
     * the player shows the new song then, not at the skip, which comes a
     * second or so before while the new one loads under the old.
     */
    val newSongHeard = kotlinx.coroutines.flow.MutableStateFlow(0)

    /**
     * Bumped when music reaches the output after a stop: the moment a player
     * handing over to Soloist can let its own song go without a silence.
     */
    val sounding = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Microseconds of Soloist's music written to the output, ever: a clock that runs as the music does. */
    private val playedUs = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * How much music has gone out, in milliseconds of the song: it moves at
     * the tempo the output plays it at (whatever the listener set, as the
     * stretcher makes it) and stops when the music does.
     */
    fun playedMs(): Double = playedUs.get() / 1000.0

    /** How much music had gone out at a few moments of the last seconds, oldest first. */
    private val historyAt = LongArray(HISTORY)
    private val historyPlayed = DoubleArray(HISTORY)
    private var historyNext = 0
    private var historyCount = 0

    private fun noteHistory() {
        val now = System.currentTimeMillis()
        synchronized(historyAt) {
            val last = (historyNext - 1 + HISTORY) % HISTORY
            if (historyCount > 0 && now - historyAt[last] < 40) return
            historyAt[historyNext] = now
            historyPlayed[historyNext] = playedMs()
            historyNext = (historyNext + 1) % HISTORY
            if (historyCount < HISTORY) historyCount++
        }
    }

    /**
     * How much music had gone out at [wallMs] (System.currentTimeMillis): what
     * Soloist's word on the position, taken at that moment, is to be reckoned
     * from. Reckoned from when the word arrived instead, a song whose state
     * came late (a slow load) ran behind its music for as long as it was late.
     */
    fun playedAt(wallMs: Long): Double {
        synchronized(historyAt) {
            if (historyCount == 0) return playedMs()
            var best = -1
            for (k in 0 until historyCount) {
                val i = (historyNext - 1 - k + HISTORY * 2) % HISTORY
                if (historyAt[i] <= wallMs) {
                    best = i
                    break
                }
            }
            if (best < 0) return historyPlayed[(historyNext - historyCount + HISTORY) % HISTORY]
            // From the moment just before, on as far as the music went in the
            // little time between, when there is a moment after it.
            val after = (best + 1) % HISTORY
            if (after != historyNext && historyAt[after] > historyAt[best]) {
                val share = (wallMs - historyAt[best]).toDouble() / (historyAt[after] - historyAt[best])
                return historyPlayed[best] + (historyPlayed[after] - historyPlayed[best]) * share.coerceIn(0.0, 1.0)
            }
            val since = System.currentTimeMillis() - historyAt[best]
            return if (wallMs >= historyAt[best] && since < 100) playedMs() else historyPlayed[best]
        }
    }

    /**
     * How much music Soloist has handed libpulse that is not heard yet, as
     * libpulse tells it five times a second: Soloist's word on the song runs
     * this far ahead of the music.
     */
    @Volatile
    var aheadMs: Int = 0
        private set

    /** When libpulse last dropped what it held (a skip, a seek), elapsedRealtime. */
    @Volatile
    var flushedAt: Long = 0
        private set

    /**
     * Where in the music the DJ's voice starts or stops, by [playedMs]: Soloist
     * says so as it prepares each piece, as far ahead of the music as libpulse
     * holds, and the switch is made when that point is written.
     */
    private val speechAt = java.util.ArrayDeque<Pair<Double, Boolean>>()

    /** Whether what is being written is the DJ talking; see AudioOutput.writeVoice. */
    @Volatile
    private var speaking = false

    /** Soloist began preparing speech ([speech]) or music: heard once what is ahead of it is. */
    fun speechNext(speech: Boolean) {
        val at = playedMs() + aheadMs
        synchronized(speechAt) { speechAt.addLast(at to speech) }
    }
    @Volatile
    private var awaitingSound = true

    /** Soloist's output, for the player to start and stop with the music. */
    val output = dev.lelonio.square.playback.AudioOutput()

    /** Whether the output is stopped: by Soloist's pause, or by a button ahead of it. */
    private var stopped = true
    private val gate = Any()
    private val stopper = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * Stops the sound now, ahead of Soloist's own pause, which comes a moment
     * later with a second or so of music still on its way. Off the caller's
     * thread: the output fades before it stops.
     */
    fun pauseNow() {
        synchronized(gate) {
            if (stopped) return
            stopped = true
        }
        stopper.execute { output.stop() }
    }

    fun resumeNow() {
        synchronized(gate) {
            if (!stopped) return
            stopped = false
        }
        stopper.execute { output.start() }
    }

    fun open(fifo: File) {
        // A Soloist that was just stopped closes its end a moment after the
        // next one starts; its reader is let finish rather than kept, or the
        // new Soloist writes into a FIFO nobody reads and nothing is heard.
        reader?.let { old ->
            old.join(3000)
            if (old.isAlive) old.interrupt()
        }
        if (fifo.exists()) fifo.delete()
        android.system.Os.mkfifo(fifo.path, "600".toInt(8))
        // Its own output, not the Spotify engine's: the engine's Connect device
        // goes idle when Soloist takes playback on the same account and
        // releases its track, and a write into that track crashed the app.
        // Same class, so the same effects.
        val output = this.output
        reader = thread(name = "soloist-audio") {
            try {
                DataInputStream(BufferedInputStream(FileInputStream(fifo), 1 shl 16)).use { input ->
                    pump(input, output)
                }
            } catch (_: EOFException) {
                Log.i(TAG, "Soloist closed the stream")
            } catch (t: Throwable) {
                Log.e(TAG, "reader failed", t)
            } finally {
                output.stop()
                reader = null
            }
        }
    }

    private const val HISTORY = 256

    private var shortBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 shl 15).order(ByteOrder.LITTLE_ENDIAN)

    /** Float samples as 16-bit, for the voice's path, which takes nothing else. */
    private fun toShorts(floats: ByteBuffer, length: Int): ByteBuffer {
        val count = length / 4
        if (shortBuffer.capacity() < count * 2) shortBuffer = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        val out = shortBuffer
        out.clear()
        val f = floats.duplicate().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        for (i in 0 until count) out.putShort((f.get(i) * 32767f).coerceIn(-32768f, 32767f).toInt().toShort())
        out.flip()
        return out
    }

    private fun pump(input: DataInputStream, output: dev.lelonio.square.playback.AudioOutput) {
        val head = ByteArray(12)
        var bytes = ByteArray(1 shl 16)
        var direct = ByteBuffer.allocateDirect(1 shl 16)
        var rate = 44100
        var channels = 2
        var float = false
        var audioBytes = 0L
        synchronized(gate) { stopped = true }
        while (true) {
            input.readFully(head)
            check(head[0] == 'S'.code.toByte() && head[1] == 'Q'.code.toByte() &&
                head[2] == 'P'.code.toByte() && head[3] == 'C'.code.toByte()) { "lost framing" }
            val length = ByteBuffer.wrap(head, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
            when (head[4].toInt().toChar()) {
                'H' -> {
                    val format = ByteArray(length).also { input.readFully(it) }
                    val b = ByteBuffer.wrap(format).order(ByteOrder.LITTLE_ENDIAN)
                    rate = b.int
                    channels = b.int
                    // 2 is float, Soloist's own; 1 (or a header without it) 16-bit.
                    float = length >= 12 && b.int == 2
                    Log.i(TAG, "stream: $rate Hz, $channels ch, ${if (float) "float" else "16-bit"}")
                }
                'A' -> {
                    if (length > bytes.size) {
                        bytes = ByteArray(length)
                        direct = ByteBuffer.allocateDirect(length)
                    }
                    input.readFully(bytes, 0, length)
                    direct.clear()
                    direct.put(bytes, 0, length)
                    direct.flip()
                    val now = playedMs()
                    synchronized(speechAt) {
                        while (speechAt.isNotEmpty() && speechAt.peekFirst().first <= now) speaking = speechAt.pollFirst().second
                    }
                    when {
                        // The DJ's voice as it was recorded: no tempo, pitch or
                        // room on it, as on the Spotify engine.
                        speaking -> {
                            val shorts = if (float) toShorts(direct, length) else direct
                            val bytes = if (float) length / 2 else length
                            output.writeVoice(shorts, bytes, rate, channels)
                            playedUs.addAndGet(bytes * 1_000_000L / (rate.toLong() * channels * 2))
                        }
                        float -> output.writeFloat(direct, length, rate, channels)
                        else -> output.write(direct, length, rate, channels)
                    }
                    if (awaitingSound) {
                        awaitingSound = false
                        sounding.value++
                    }
                    audioBytes += length
                    val perSecond = rate * channels * (if (float) 4L else 2L)
                    if (!speaking) playedUs.addAndGet(length * 1_000_000L / perSecond)
                    noteHistory()
                    if (audioBytes / (perSecond * 10) != (audioBytes - length) / (perSecond * 10)) {
                        Log.i(TAG, "${audioBytes / perSecond} s of audio played")
                    }
                }
                // Only a real pause stops the output and only a resume after
                // one starts it again: a new stream opening after a change of
                // song resumes too, and starting a running output faded it to
                // nothing in the middle of the dissolve.
                'R' -> { Log.i(TAG, "resume"); resumeNow() }
                // Soloist corks the stream while it loads the next song, a
                // moment after the change of song's flush. That is no pause: the
                // song being left is still coming, to be dissolved into the new
                // one, and stopping the output for it silenced those seconds,
                // so the music stopped and then jumped into the next song.
                // Only a cork while Soloist is not playing, or not just after a
                // change of song, stops the output.
                'P' -> {
                    val changing = android.os.SystemClock.elapsedRealtime() - flushedAt < CHANGE_CORK_MS
                    if (changing && SoloistEngine.state.value.playing) {
                        Log.i(TAG, "pause in a change of song, playing on")
                    } else {
                        Log.i(TAG, "pause")
                        pauseNow()
                    }
                    awaitingSound = true
                }
                'F' -> {
                    Log.i(TAG, "flush")
                    // What was ahead is gone, and the points in it with it;
                    // and nothing is ahead any more until libpulse says so.
                    synchronized(speechAt) { speechAt.clear() }
                    aheadMs = 0
                    flushedAt = android.os.SystemClock.elapsedRealtime()
                }
                'N' -> newSongHeard.value++
                'L' -> {
                    val told = ByteArray(length).also { input.readFully(it) }
                    if (length >= 4) aheadMs = ByteBuffer.wrap(told).order(ByteOrder.LITTLE_ENDIAN).int
                }
                else -> input.skipBytes(length)
            }
        }
    }
}
