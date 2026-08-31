package dev.lelonio.square.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.serialization.Serializable

data class DspFormat(val sampleRate: Int, val channelCount: Int)

@Serializable
data class EqualizerBand(
    val frequencyHz: Float,
    val gainDb: Float,
    val q: Float = 0.707f,
) {
    init {
        require(frequencyHz > 0f)
        require(gainDb in -12f..12f)
        require(q > 0f)
    }
}

@Serializable
data class AdvancedDspConfig(
    val enabled: Boolean = false,
    val gainDb: Float = 0f,
    val bassBoostDb: Float = 0f,
    val equalizer: List<EqualizerBand> = emptyList(),
    val virtualizer: Float = 0f,
    val reverb: Float = 0f,
    /** Only measured/metadata-derived gain is accepted; no loudness is guessed. */
    val normalizationGainDb: Float? = null,
    val limiterEnabled: Boolean = true,
) {
    init {
        require(gainDb in -18f..12f)
        require(bassBoostDb in 0f..9f)
        require(virtualizer in 0f..1f)
        require(reverb in 0f..1f)
        normalizationGainDb?.let { require(it in -18f..12f) }
    }
}

/** A processing stage independent of UI, providers and persistence. */
interface DspStage {
    fun configure(format: DspFormat)
    fun reset()
    fun process(samples: FloatArray, frames: Int, channels: Int)
}

private class GainStage : DspStage {
    @Volatile var gain = 1f
    override fun configure(format: DspFormat) = Unit
    override fun reset() = Unit
    override fun process(samples: FloatArray, frames: Int, channels: Int) {
        if (gain == 1f) return
        val count = frames * channels
        for (i in 0 until count) samples[i] *= gain
    }
}

private class BiquadStage : DspStage {
    private data class Coefficients(
        val b0: Float,
        val b1: Float,
        val b2: Float,
        val a1: Float,
        val a2: Float,
    )

    private var format = DspFormat(48_000, 2)
    @Volatile private var coefficients: List<Coefficients> = emptyList()
    private var z1 = FloatArray(0)
    private var z2 = FloatArray(0)

    fun setBands(bands: List<EqualizerBand>, bassBoostDb: Float) {
        val all = if (bassBoostDb > 0f) {
            listOf(EqualizerBand(90f, bassBoostDb, 0.7f)) + bands
        } else bands
        coefficients = all.take(MAX_BANDS).map { peakCoefficients(it, format.sampleRate) }
    }

    override fun configure(format: DspFormat) {
        this.format = format
        z1 = FloatArray(MAX_BANDS * format.channelCount)
        z2 = FloatArray(MAX_BANDS * format.channelCount)
    }

    override fun reset() {
        z1.fill(0f)
        z2.fill(0f)
    }

    override fun process(samples: FloatArray, frames: Int, channels: Int) {
        val current = coefficients
        if (current.isEmpty()) return
        for (stageIndex in current.indices) {
            val c = current[stageIndex]
            val stateOffset = stageIndex * channels
            for (frame in 0 until frames) {
                val base = frame * channels
                for (channel in 0 until channels) {
                    val index = base + channel
                    val x = samples[index]
                    val y = c.b0 * x + z1[stateOffset + channel]
                    z1[stateOffset + channel] = c.b1 * x - c.a1 * y + z2[stateOffset + channel]
                    z2[stateOffset + channel] = c.b2 * x - c.a2 * y
                    samples[index] = y
                }
            }
        }
    }

    private fun peakCoefficients(band: EqualizerBand, sampleRate: Int): Coefficients {
        val a = 10f.pow(band.gainDb / 40f)
        val omega = (2.0 * Math.PI * band.frequencyHz / sampleRate).toFloat()
        val alpha = sin(omega) / (2f * band.q)
        val cosOmega = cos(omega)
        val b0 = 1f + alpha * a
        val b1 = -2f * cosOmega
        val b2 = 1f - alpha * a
        val a0 = 1f + alpha / a
        val a1 = -2f * cosOmega
        val a2 = 1f - alpha / a
        return Coefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }
}

private class VirtualizerStage : DspStage {
    @Volatile var amount = 0f
    override fun configure(format: DspFormat) = Unit
    override fun reset() = Unit
    override fun process(samples: FloatArray, frames: Int, channels: Int) {
        if (amount <= 0f || channels < 2) return
        val width = 1f + amount.coerceIn(0f, 1f) * 0.45f
        for (frame in 0 until frames) {
            val i = frame * channels
            val left = samples[i]
            val right = samples[i + 1]
            val mid = (left + right) * 0.5f
            val side = (left - right) * 0.5f * width
            samples[i] = mid + side
            samples[i + 1] = mid - side
        }
    }
}

private class ReverbStage : DspStage {
    @Volatile var amount = 0f
    private var delayL = FloatArray(1)
    private var delayR = FloatArray(1)
    private var cursor = 0

    override fun configure(format: DspFormat) {
        val delaySamples = max(1, (format.sampleRate * 0.047).toInt())
        delayL = FloatArray(delaySamples)
        delayR = FloatArray(delaySamples)
        cursor = 0
    }

    override fun reset() {
        delayL.fill(0f)
        delayR.fill(0f)
        cursor = 0
    }

    override fun process(samples: FloatArray, frames: Int, channels: Int) {
        if (amount <= 0f || channels == 0) return
        val wet = amount.coerceIn(0f, 1f) * 0.28f
        val feedback = 0.32f
        for (frame in 0 until frames) {
            val i = frame * channels
            val dryL = samples[i]
            val dryR = if (channels > 1) samples[i + 1] else dryL
            val delayedL = delayL[cursor]
            val delayedR = delayR[cursor]
            delayL[cursor] = (dryL + delayedL * feedback).coerceIn(-1f, 1f)
            delayR[cursor] = (dryR + delayedR * feedback).coerceIn(-1f, 1f)
            samples[i] = dryL * (1f - wet) + delayedL * wet
            if (channels > 1) samples[i + 1] = dryR * (1f - wet) + delayedR * wet
            cursor = (cursor + 1) % delayL.size
        }
    }
}

private class LimiterStage : DspStage {
    @Volatile var enabled = true
    private var envelope = 0f
    private var release = 0.9992f

    override fun configure(format: DspFormat) {
        release = exp(-1f / max(1f, format.sampleRate * 0.18f))
        envelope = 0f
    }

    override fun reset() {
        envelope = 0f
    }

    override fun process(samples: FloatArray, frames: Int, channels: Int) {
        if (!enabled) return
        for (i in 0 until frames * channels) {
            val magnitude = abs(samples[i])
            envelope = max(magnitude, envelope * release)
            if (envelope > 0.98f) samples[i] *= 0.98f / envelope
            samples[i] = samples[i].coerceIn(-0.98f, 0.98f)
        }
    }
}

/**
 * Media3 PCM processor used by local/ExoPlayer playback. Spotify's native sink
 * remains on its existing path; no provider-specific DSP contract is changed.
 */
@OptIn(UnstableApi::class)
class AdvancedDspAudioProcessor : BaseAudioProcessor() {
    @Volatile private var configuration = AdvancedDspConfig()
    private var format = DspFormat(48_000, 2)
    private var workBuffer = FloatArray(0)
    private val gain = GainStage()
    private val eq = BiquadStage()
    private val virtualizer = VirtualizerStage()
    private val reverb = ReverbStage()
    private val limiter = LimiterStage()

    fun setConfiguration(config: AdvancedDspConfig) {
        configuration = config
        gain.gain = dbToLinear(config.gainDb + (config.normalizationGainDb ?: 0f))
        eq.setBands(config.equalizer, config.bassBoostDb)
        virtualizer.amount = config.virtualizer
        reverb.amount = config.reverb
        limiter.enabled = config.limiterEnabled
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..2) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        format = DspFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        gain.configure(format)
        eq.configure(format)
        virtualizer.configure(format)
        reverb.configure(format)
        limiter.configure(format)
        workBuffer = FloatArray(MAX_WORK_FRAMES * format.channelCount)
        setConfiguration(configuration)
        return inputAudioFormat
    }

    override fun onFlush() {
        gain.reset()
        eq.reset()
        virtualizer.reset()
        reverb.reset()
        limiter.reset()
    }

    override fun onReset() {
        onFlush()
        workBuffer.fill(0f)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val config = configuration
        val size = inputBuffer.remaining()
        if (size == 0) return
        if (!config.enabled || (!config.limiterEnabled && config.gainDb == 0f && config.bassBoostDb == 0f &&
                    config.equalizer.isEmpty() && config.virtualizer == 0f && config.reverb == 0f &&
                    config.normalizationGainDb == null)
        ) {
            replaceOutputBuffer(size).put(inputBuffer).flip()
            return
        }

        val frames = size / (format.channelCount * 2)
        val sampleCount = frames * format.channelCount
        if (sampleCount > workBuffer.size) {
            replaceOutputBuffer(size).put(inputBuffer).flip()
            return
        }

        val input = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val output = replaceOutputBuffer(size)
        for (i in 0 until sampleCount) workBuffer[i] = input.short.toFloat() / 32768f

        gain.process(workBuffer, frames, format.channelCount)
        eq.process(workBuffer, frames, format.channelCount)
        virtualizer.process(workBuffer, frames, format.channelCount)
        reverb.process(workBuffer, frames, format.channelCount)
        limiter.process(workBuffer, frames, format.channelCount)

        output.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            output.putShort((workBuffer[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        output.flip()
    }

    private fun dbToLinear(db: Float): Float = 10f.pow(db.coerceIn(-18f, 12f) / 20f)

    private companion object {
        const val MAX_WORK_FRAMES = 16_384
        const val MAX_BANDS = 16
    }
}
