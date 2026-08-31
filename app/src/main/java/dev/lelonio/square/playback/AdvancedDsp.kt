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
data class EqualizerBand(val frequencyHz: Float, val gainDb: Float, val q: Float = 0.707f) {
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
        for (i in 0 until frames * channels) samples[i] *= gain
    }
}

/** Fixed-size biquad bank with reusable coefficient/state arrays. */
private class BiquadStage : DspStage {
    private var format = DspFormat(48_000, 2)
    private val b0 = FloatArray(MAX_BANDS)
    private val b1 = FloatArray(MAX_BANDS)
    private val b2 = FloatArray(MAX_BANDS)
    private val a1 = FloatArray(MAX_BANDS)
    private val a2 = FloatArray(MAX_BANDS)
    private var count = 0
    private var z1 = FloatArray(0)
    private var z2 = FloatArray(0)

    fun setBands(bands: List<EqualizerBand>, bassBoostDb: Float) {
        count = 0
        if (bassBoostDb > 0f) addBand(90f, bassBoostDb, 0.7f)
        var index = 0
        while (index < bands.size && count < MAX_BANDS) {
            val band = bands[index]
            addBand(band.frequencyHz, band.gainDb, band.q)
            index++
        }
    }

    private fun addBand(frequencyHz: Float, gainDb: Float, q: Float) {
        val a = 10f.pow(gainDb / 40f)
        val omega = (2.0 * Math.PI * frequencyHz / format.sampleRate).toFloat()
        val alpha = sin(omega) / (2f * q)
        val cosOmega = cos(omega)
        val normalizer = 1f + alpha / a
        b0[count] = (1f + alpha * a) / normalizer
        b1[count] = (-2f * cosOmega) / normalizer
        b2[count] = (1f - alpha * a) / normalizer
        a1[count] = (-2f * cosOmega) / normalizer
        a2[count] = (1f - alpha / a) / normalizer
        count++
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
        if (count == 0) return
        for (stageIndex in 0 until count) {
            val stateOffset = stageIndex * channels
            for (frame in 0 until frames) {
                val base = frame * channels
                for (channel in 0 until channels) {
                    val index = base + channel
                    val x = samples[index]
                    val y = b0[stageIndex] * x + z1[stateOffset + channel]
                    z1[stateOffset + channel] = b1[stageIndex] * x - a1[stageIndex] * y + z2[stateOffset + channel]
                    z2[stateOffset + channel] = b2[stageIndex] * x - a2[stageIndex] * y
                    samples[index] = y
                }
            }
        }
    }

    private companion object { const val MAX_BANDS = 16 }
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

    override fun reset() { envelope = 0f }

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
 * Media3 PCM processor for local/ExoPlayer playback. Spotify's native sink keeps
 * its established speed/pitch/reverb path; no provider-specific contract changes.
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
        gain.reset(); eq.reset(); virtualizer.reset(); reverb.reset(); limiter.reset()
    }

    override fun onReset() = onFlush()

    override fun queueInput(inputBuffer: ByteBuffer) {
        val latest = AudioEffects.dsp.value
        if (latest !== configuration) setConfiguration(latest)
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
    private companion object { const val MAX_WORK_FRAMES = 16_384 }
}
