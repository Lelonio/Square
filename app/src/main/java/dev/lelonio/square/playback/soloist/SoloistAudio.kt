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

    /**
     * How much music Soloist has handed libpulse that is not heard yet, as
     * libpulse tells it five times a second: Soloist's word on the song runs
     * this far ahead of the music.
     */
    @Volatile
    var aheadMs: Int = 0
        private set
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
                    if (float) output.writeFloat(direct, length, rate, channels) else output.write(direct, length, rate, channels)
                    if (awaitingSound) {
                        awaitingSound = false
                        sounding.value++
                    }
                    audioBytes += length
                    val perSecond = rate * channels * (if (float) 4L else 2L)
                    playedUs.addAndGet(length * 1_000_000L / perSecond)
                    if (audioBytes / (perSecond * 10) != (audioBytes - length) / (perSecond * 10)) {
                        Log.i(TAG, "${audioBytes / perSecond} s of audio played")
                    }
                }
                // Only a real pause stops the output and only a resume after
                // one starts it again: a new stream opening after a change of
                // song resumes too, and starting a running output faded it to
                // nothing in the middle of the dissolve.
                'R' -> { Log.i(TAG, "resume"); resumeNow() }
                'P' -> { Log.i(TAG, "pause"); pauseNow(); awaitingSound = true }
                'F' -> Log.i(TAG, "flush")
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
