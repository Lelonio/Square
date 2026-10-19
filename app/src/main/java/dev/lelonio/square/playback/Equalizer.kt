package dev.lelonio.square.playback

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ten bands of equalisation, one curve for each pair of headphones.
 *
 * Done in the app's own code rather than with the platform's
 * `android.media.audiofx.Equalizer`. That one is whatever the phone's maker put
 * behind it: five bands on one phone, eight on another, centred wherever they
 * fell, and on some phones silently not there. Every player here already hands
 * its audio through code of the app's own on the way out — the engine's sink
 * for Spotify, the processor chain for everything ExoPlayer plays — so the same
 * filters run on all of them, on every phone, and a curve sounds the same
 * wherever it was set.
 *
 * Per output, because a curve belongs to the thing it corrects: what makes one
 * pair of headphones sound right makes a car or the phone's own speaker sound
 * wrong. The output in use is watched (see [AudioRoute]) and its curve follows
 * it; a device not seen before starts flat and off.
 *
 * Like [AudioEffects], a process-wide object: the service and the UI share the
 * process, and the filters read [tuning] from the audio threads without a lock.
 */
object Equalizer {

    /** The centres of the ten bands, an octave apart. */
    val FREQUENCIES = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    /** How far a band, or the preamp, can be moved either way, in dB. */
    const val RANGE_DB = 12f

    /** What one output has been given. */
    @Serializable
    data class Curve(
        val enabled: Boolean = false,
        val gains: List<Float> = List(FREQUENCIES.size) { 0f },
        val preamp: Float = 0f,
    ) {
        val isFlat: Boolean get() = preamp == 0f && gains.all { it == 0f }

        /** Whether this sounds like [preset]: the bands, not the preamp. */
        fun matches(preset: List<Float>): Boolean =
            gains.size == preset.size &&
                gains.indices.all { kotlin.math.abs(gains[it] - preset[it]) < 0.05f }
    }

    /** A curve the listener named and kept. */
    @Serializable
    data class Preset(val id: String, val name: String, val gains: List<Float>)

    /** What the filters are running, or null for nothing: off, or flat. */
    @Volatile
    var tuning: Tuning? = null
        private set

    private val _curves = MutableStateFlow<Map<String, Curve>>(emptyMap())

    /** Every output given a curve, by [AudioRoute.Output.key]. */
    val curves: StateFlow<Map<String, Curve>> = _curves.asStateFlow()

    private val _names = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * What each output was called when it was last seen, for listing the ones
     * not plugged in now. The key says what kind it is; see [AudioRoute].
     */
    val names: StateFlow<Map<String, String>> = _names.asStateFlow()

    private val _presets = MutableStateFlow<List<Preset>>(emptyList())

    /** The listener's own presets; the built-in ones are [BuiltInEqPresets]. */
    val presets: StateFlow<List<Preset>> = _presets.asStateFlow()

    private var prefs: android.content.SharedPreferences? = null

    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        prefs = store
        _curves.value = store.getString(KEY_CURVES, null)
            ?.let { runCatching { json.decodeFromString(curvesSerializer, it) }.getOrNull() }
            .orEmpty()
            .mapValues { (_, curve) -> curve.normalised() }
        _names.value = store.getString(KEY_NAMES, null)
            ?.let { runCatching { json.decodeFromString(namesSerializer, it) }.getOrNull() }
            .orEmpty()
        _presets.value = store.getString(KEY_PRESETS, null)
            ?.let { runCatching { json.decodeFromString(presetsSerializer, it) }.getOrNull() }
            .orEmpty()

        AudioRoute.start(context)
        AudioRoute.onChange { output -> seen(output) }
        seen(AudioRoute.current.value)
    }

    /** The curve for [key], flat and off when it has none. */
    fun curveFor(key: String): Curve = _curves.value[key] ?: Curve()

    fun setEnabled(key: String, enabled: Boolean) = edit(key) { it.copy(enabled = enabled) }

    fun setGain(key: String, band: Int, db: Float) = edit(key) { curve ->
        val gains = curve.gains.toMutableList()
        if (band !in gains.indices) return@edit curve
        gains[band] = db.coerceIn(-RANGE_DB, RANGE_DB).snapped()
        // Moving a band is asking to hear it.
        curve.copy(gains = gains, enabled = true)
    }

    fun setPreamp(key: String, db: Float) = edit(key) {
        it.copy(preamp = db.coerceIn(-RANGE_DB, RANGE_DB).snapped())
    }

    fun applyPreset(key: String, gains: List<Float>) = edit(key) {
        it.copy(gains = gains.map { g -> g.coerceIn(-RANGE_DB, RANGE_DB) }, enabled = true)
    }

    fun reset(key: String) = edit(key) { Curve(enabled = it.enabled) }

    fun savePreset(name: String, gains: List<Float>) {
        val preset = Preset(
            id = java.util.UUID.randomUUID().toString(),
            name = name.trim(),
            gains = gains,
        )
        _presets.value = _presets.value + preset
        writePresets()
    }

    fun deletePreset(id: String) {
        _presets.value = _presets.value.filterNot { it.id == id }
        writePresets()
    }

    /** Forgets an output that is not plugged in, curve and name both. */
    fun forget(key: String) {
        if (key == AudioRoute.current.value.key) return
        _curves.value = _curves.value - key
        _names.value = _names.value - key
        writeCurves()
        prefs?.edit()?.putString(KEY_NAMES, json.encodeToString(namesSerializer, _names.value))?.apply()
    }

    private fun edit(key: String, change: (Curve) -> Curve) {
        val before = curveFor(key)
        val after = change(before)
        if (after == before) return
        _curves.value = _curves.value + (key to after)
        writeCurves()
        if (key == AudioRoute.current.value.key) retune()
    }

    /** An output has come into use: remember what it is called, and follow it. */
    private fun seen(output: AudioRoute.Output) {
        if (output.name.isNotEmpty() && _names.value[output.key] != output.name) {
            _names.value = _names.value + (output.key to output.name)
            prefs?.edit()?.putString(KEY_NAMES, json.encodeToString(namesSerializer, _names.value))?.apply()
        }
        retune()
    }

    private fun retune() {
        val curve = curveFor(AudioRoute.current.value.key)
        tuning = if (curve.enabled && !curve.isFlat) Tuning(curve.gains.toFloatArray(), curve.preamp) else null
    }

    private fun writeCurves() {
        prefs?.edit()?.putString(KEY_CURVES, json.encodeToString(curvesSerializer, _curves.value))?.apply()
    }

    private fun writePresets() {
        prefs?.edit()?.putString(KEY_PRESETS, json.encodeToString(presetsSerializer, _presets.value))?.apply()
    }

    /** A curve saved with a different number of bands is read as flat. */
    private fun Curve.normalised(): Curve =
        if (gains.size == FREQUENCIES.size) this else copy(gains = List(FREQUENCIES.size) { 0f })

    /** Half a decibel is finer than anyone hears, and keeps the numbers readable. */
    private fun Float.snapped(): Float = (this * 2f).roundToInt() / 2f

    private val curvesSerializer = MapSerializer(String.serializer(), Curve.serializer())
    private val namesSerializer = MapSerializer(String.serializer(), String.serializer())
    private val presetsSerializer = ListSerializer(Preset.serializer())

    private const val FILE_NAME = "equalizer"
    private const val KEY_CURVES = "curves"
    private const val KEY_NAMES = "names"
    private const val KEY_PRESETS = "presets"

    /**
     * One curve, as the filters need it.
     *
     * Immutable, so the audio threads can be handed a new one without a lock:
     * a filter notices it has changed by identity and works its coefficients
     * out again.
     */
    class Tuning(val gains: FloatArray, val preampDb: Float) {

        /**
         * What the whole signal is multiplied by before the bands.
         *
         * The preamp, less the curve's highest point. A boost can only add
         * level, and a song mastered to within a fraction of full scale has
         * none to give: six decibels of bass on top of it is six decibels of
         * clipping. Lowering everything by the most the curve raises anything
         * keeps the peaks where they were. Equalisers that do not do this sound
         * louder in the shop and break up on the first loud chorus.
         */
        val inputGain: Double = 10.0.pow((preampDb - headroom(gains)) / 20.0)
    }

    /** The highest point of [gains]' response, in dB, never below zero. */
    fun headroom(gains: FloatArray): Float {
        var peak = 0.0
        var f = 20.0
        while (f <= 20000.0) {
            peak = maxOf(peak, responseDb(gains, f, REFERENCE_RATE))
            f *= 1.05
        }
        return peak.toFloat()
    }

    /**
     * The curve's level at [frequency], the bands added together as the
     * filters will add them: for the drawing, and for [headroom].
     */
    fun responseDb(gains: FloatArray, frequency: Double, sampleRate: Int = REFERENCE_RATE): Double {
        val w = 2.0 * PI * frequency / sampleRate
        val cosW = cos(w)
        val cos2W = cos(2 * w)
        val sinW = sin(w)
        val sin2W = sin(2 * w)
        var total = 0.0
        for (band in FREQUENCIES.indices) {
            val c = Biquad.peaking(FREQUENCIES[band].toDouble(), gains[band].toDouble(), sampleRate) ?: continue
            // H(e^jw) = (b0 + b1 e^-jw + b2 e^-2jw) / (1 + a1 e^-jw + a2 e^-2jw)
            val numRe = c.b0 + c.b1 * cosW + c.b2 * cos2W
            val numIm = -(c.b1 * sinW + c.b2 * sin2W)
            val denRe = 1.0 + c.a1 * cosW + c.a2 * cos2W
            val denIm = -(c.a1 * sinW + c.a2 * sin2W)
            val magnitude = sqrt((numRe * numRe + numIm * numIm) / (denRe * denRe + denIm * denIm))
            total += 20.0 * log10(magnitude)
        }
        return total
    }

    private const val REFERENCE_RATE = 48000
}

/** One band's coefficients, normalised so a0 is one. */
internal class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    companion object {
        /** An octave wide: neighbouring bands meet without a dip or a bump between them. */
        private const val Q = 1.41

        /**
         * A peaking filter, from the Audio EQ Cookbook.
         *
         * Null for a band at zero, which is no filter at all, and for one too
         * near the top of what [sampleRate] can carry: at 22 kHz a 16 kHz bell
         * has no room either side and bends the whole top octave.
         */
        fun peaking(frequency: Double, gainDb: Double, sampleRate: Int): Biquad? {
            if (gainDb == 0.0 || frequency >= sampleRate * 0.45) return null
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * frequency / sampleRate
            val alpha = sin(w0) / (2.0 * Q)
            val cosW0 = cos(w0)
            val a0 = 1.0 + alpha / a
            return Biquad(
                b0 = (1.0 + alpha * a) / a0,
                b1 = -2.0 * cosW0 / a0,
                b2 = (1.0 - alpha * a) / a0,
                a1 = -2.0 * cosW0 / a0,
                a2 = (1.0 - alpha / a) / a0,
            )
        }
    }
}

/**
 * The bands themselves, run over one stream of sixteen-bit PCM.
 *
 * One per stream, because each band remembers the last two samples of every
 * channel it has filtered; two players sharing one would hear each other's
 * history. Reads [Equalizer.tuning] on every packet, so a curve moved while a
 * song plays is heard within the packet.
 */
class EqualizerFilter {

    private var tuning: Equalizer.Tuning? = null
    private var sampleRate = 0
    private var channels = 0
    private var bands: Array<Biquad> = emptyArray()

    /** Two values per band per channel: transposed direct form II. */
    private var state = DoubleArray(0)

    /** Whether there is anything to do, without touching the audio. */
    val isActive: Boolean get() = Equalizer.tuning != null

    /** In place, on [sizeInBytes] bytes of interleaved little-endian PCM. */
    fun apply(data: ByteBuffer, sizeInBytes: Int, channels: Int, sampleRate: Int) {
        val wanted = Equalizer.tuning
        if (wanted == null) {
            if (tuning != null) {
                tuning = null
                state.fill(0.0)
            }
            return
        }
        if (channels <= 0 || sampleRate <= 0) return
        if (wanted !== tuning || sampleRate != this.sampleRate || channels != this.channels) {
            configure(wanted, sampleRate, channels)
        }

        val shorts = data.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = minOf(sizeInBytes / 2, shorts.limit())
        val gain = wanted.inputGain
        val count = bands.size
        val z = state
        var i = 0
        while (i + channels <= samples) {
            for (c in 0 until channels) {
                var x = shorts.get(i + c) * gain
                var s = c * 2
                for (b in 0 until count) {
                    val f = bands[b]
                    val y = f.b0 * x + z[s]
                    z[s] = f.b1 * x - f.a1 * y + z[s + 1]
                    z[s + 1] = f.b2 * x - f.a2 * y
                    x = y
                    s += channels * 2
                }
                val out = when {
                    x > Short.MAX_VALUE -> Short.MAX_VALUE.toInt()
                    x < Short.MIN_VALUE -> Short.MIN_VALUE.toInt()
                    else -> x.roundToInt()
                }
                shorts.put(i + c, out.toShort())
            }
            i += channels
        }
    }

    private fun configure(wanted: Equalizer.Tuning, sampleRate: Int, channels: Int) {
        val fresh = Equalizer.FREQUENCIES.indices.mapNotNull { band ->
            Biquad.peaking(
                Equalizer.FREQUENCIES[band].toDouble(),
                wanted.gains[band].toDouble(),
                sampleRate,
            )
        }.toTypedArray()
        // The history carries over when only the curve moved: thrown away, the
        // filters would start from silence mid-song and click. A different
        // stream, or a different number of bands, has no history worth keeping.
        val sameShape = sampleRate == this.sampleRate && channels == this.channels &&
            fresh.size == bands.size
        if (!sameShape) state = DoubleArray(fresh.size * channels * 2)
        bands = fresh
        tuning = wanted
        this.sampleRate = sampleRate
        this.channels = channels
    }
}

/**
 * The curves everyone gets, by the name of the string that names them.
 *
 * Starting points rather than corrections: no preset knows the headphones it
 * is played on, and the point of a curve per output is that the listener moves
 * from one of these to what their own pair needs.
 */
val BuiltInEqPresets: List<Pair<Int, List<Float>>> = listOf(
    dev.lelonio.square.R.string.eq_flat to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    dev.lelonio.square.R.string.eq_bass_boost to listOf(6f, 5f, 4f, 2f, 0.5f, 0f, 0f, 0f, 0f, 0f),
    dev.lelonio.square.R.string.eq_bass_reducer to listOf(-6f, -5f, -4f, -2f, -0.5f, 0f, 0f, 0f, 0f, 0f),
    dev.lelonio.square.R.string.eq_treble_boost to listOf(0f, 0f, 0f, 0f, 0f, 0.5f, 2f, 3.5f, 5f, 6f),
    dev.lelonio.square.R.string.eq_treble_reducer to listOf(0f, 0f, 0f, 0f, 0f, -0.5f, -2f, -3.5f, -5f, -6f),
    dev.lelonio.square.R.string.eq_vocal to listOf(-2f, -2f, -1f, 1f, 3f, 4f, 3.5f, 1.5f, 0f, -1f),
    dev.lelonio.square.R.string.eq_spoken to listOf(-4f, -3f, -1.5f, 0.5f, 2.5f, 3.5f, 4f, 2.5f, 0.5f, -2f),
    dev.lelonio.square.R.string.eq_loudness to listOf(5f, 4f, 2f, 0f, -1f, -1f, 0f, 2f, 4f, 5f),
    dev.lelonio.square.R.string.eq_rock to listOf(4.5f, 3.5f, 2f, 0.5f, -1f, -1f, 0.5f, 2f, 3f, 4f),
    dev.lelonio.square.R.string.eq_pop to listOf(-1f, 0f, 1.5f, 3f, 4f, 3f, 1.5f, 0f, -0.5f, -1f),
    dev.lelonio.square.R.string.eq_hip_hop to listOf(5f, 4.5f, 2f, 2.5f, -1f, -1f, 1f, 0f, 1.5f, 2.5f),
    dev.lelonio.square.R.string.eq_electronic to listOf(4.5f, 4f, 1.5f, 0f, -2f, 1.5f, 0.5f, 1f, 3.5f, 4.5f),
    dev.lelonio.square.R.string.eq_classical to listOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f),
    dev.lelonio.square.R.string.eq_jazz to listOf(3f, 2f, 1f, 2f, -1.5f, -1.5f, 0f, 1f, 2f, 3f),
    dev.lelonio.square.R.string.eq_acoustic to listOf(4f, 4f, 3f, 1f, 2f, 1.5f, 3f, 3.5f, 3f, 2f),
)
