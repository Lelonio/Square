package dev.lelonio.square.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.lelonio.square.nativecore.NativeBridge
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Spotify's Mix, as Square reads and plays it.
 *
 * A playlist mixed in Spotify's own app carries its transitions: on the row
 * a transition starts from, where the song hands over, where the next comes
 * in and how long the two overlap. They are read through the playlist's `mix`
 * lens (see catalog.rs, playlist_mix) and kept here, for every playlist whose
 * mix is on, by the playlist's URI. The player asks for the pair it is about
 * to play and hands the engine that one transition; see LibrespotPlayer.
 */
object Mixes {

    /** One transition, from [from] into [to]. Times in milliseconds. */
    data class Transition(
        /** The row of the playlist it starts from. */
        val index: Int,
        val from: String,
        val to: String,
        /** Where [from] starts handing over. */
        val outMs: Long,
        /** Where [to] comes in. */
        val inMs: Long,
        /** How long the two play together. */
        val fadeMs: Long,
        /** The tempo both are brought to, when Spotify matched them; 0 when it did not. */
        val bpm: Float,
        /** Spotify's number for the transition's style. Its names are not known. */
        val preset: Int?,
        /** How fast [to] plays during the overlap, to match [from]'s beats. */
        val speedIn: Float = 1f,
    ) {
        /** What the engine is told; see librespot-playback's MixPlan. */
        fun planJson(): String = JSONObject()
            .put("from", from)
            .put("to", to)
            .put("outMs", outMs)
            .put("inMs", inMs)
            .put("fadeMs", fadeMs)
            .put("speedIn", speedIn.toDouble())
            .toString()
    }

    /** A playlist's mix: whether Spotify has it mixed, and the transitions saved with it. */
    data class PlaylistMix(
        val mixed: Boolean,
        val transitions: List<Transition>,
        /** The songs' lengths, which the automatic transitions fit inside. */
        val durations: Map<String, Long> = emptyMap(),
    )

    /**
     * What a song's automatic transitions are worked out from; see catalog.rs,
     * tracks_mix_analysis. Times in milliseconds.
     */
    class Analysis(
        val lengthMs: Long,
        /** Where each bar starts. */
        val downbeats: LongArray,
        /** Where the song is first and last really sounding. */
        val fadeInMs: Long,
        val fadeOutMs: Long,
        /** The singing, 0 to 100, every tenth of a second. */
        val vocals: IntArray,
        /** Spotify's suggested points, by kind: 1 and 2 to leave, 3 and 4 to come in. */
        val points: Map<Int, LongArray>,
        /** Where each part of the song starts. */
        val sections: LongArray,
    ) {
        /** How much singing there is between [from] and [to], 0 to 1. */
        fun singing(from: Long, to: Long): Double {
            val first = (from / 100).toInt().coerceAtLeast(0)
            val last = (to / 100).toInt().coerceAtMost(vocals.size)
            if (last <= first) return 0.0
            var sum = 0
            for (i in first until last) sum += vocals[i]
            return sum / (100.0 * (last - first))
        }
    }

    /** A track's tempo and key, as Spotify's Mix shows them. */
    data class Attributes(val bpm: Float, val camelot: String)

    fun parseMix(json: String?): PlaylistMix? = runCatching {
        val root = JSONObject(json ?: return null)
        val list = root.optJSONArray("transitions")
        val transitions = (0 until (list?.length() ?: 0)).mapNotNull { i ->
            val t = list!!.getJSONObject(i)
            Transition(
                index = t.optInt("index", -1),
                from = t.getString("from"),
                to = t.getString("to"),
                outMs = t.getLong("outMs"),
                inMs = t.optLong("inMs", 0),
                fadeMs = t.getLong("fadeMs"),
                bpm = t.optDouble("bpmB", 0.0).toFloat()
                    .takeIf { t.optInt("kind", 0) != 0 } ?: 0f,
                preset = t.optInt("preset", -1).takeIf { it >= 0 },
                // Only a beatmatched transition moves the tempo: kind 0 is a
                // plain fade, whatever speed the recipe records.
                speedIn = if (t.optInt("kind", 0) != 0) {
                    (t.optDouble("speedB", 1.0) / t.optDouble("speedA", 1.0)).toFloat()
                } else {
                    1f
                },
            ).takeIf { it.fadeMs > 0 && it.outMs > 0 }
        }
        PlaylistMix(root.optBoolean("mixed", false), transitions)
    }.getOrNull()

    fun parseAnalyses(json: String?): Map<String, Analysis> = runCatching {
        val root = JSONObject(json ?: return emptyMap())
        root.keys().asSequence().map { uri ->
            val a = root.getJSONObject(uri)
            fun longs(name: String) = a.optJSONArray(name).let { list ->
                LongArray(list?.length() ?: 0) { list!!.getLong(it) }
            }
            val points = a.optJSONArray("points")
            val byKind = (0 until (points?.length() ?: 0))
                .map { points!!.getJSONArray(it) }
                .groupBy({ it.getInt(0) }, { it.getLong(1) })
                .mapValues { (_, list) -> list.toLongArray() }
            val vocals = a.optJSONArray("vocals")
            uri to Analysis(
                lengthMs = a.optLong("lengthMs"),
                downbeats = longs("downbeats"),
                fadeInMs = a.optLong("fadeInMs"),
                fadeOutMs = a.optLong("fadeOutMs"),
                vocals = IntArray(vocals?.length() ?: 0) { vocals!!.getInt(it) },
                points = byKind,
                sections = longs("sections"),
            )
        }.toMap()
    }.getOrDefault(emptyMap())

    fun parseAttributes(json: String?): Map<String, Attributes> = runCatching {
        val root = JSONObject(json ?: return emptyMap())
        root.keys().asSequence().mapNotNull { uri ->
            val a = root.getJSONObject(uri)
            val bpm = a.optDouble("bpm", 0.0).toFloat()
            if (bpm <= 0f) null else uri to Attributes(bpm, a.optString("camelot"))
        }.toMap()
    }.getOrDefault(emptyMap())

    /** The mixes that are on, by playlist URI. */
    private val playingMixes = ConcurrentHashMap<String, PlaylistMix>()

    private val _playing = MutableStateFlow(false)

    /**
     * Whether what is playing is a playlist with its mix on, for the player's
     * Play button, which wears the mix's ring while it is. Kept by the player.
     */
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    /** Called by the player with the context it is playing; null when none. */
    fun follow(contextUri: String?) {
        context = contextUri
        _playing.value = contextUri != null && playingMixes.containsKey(contextUri)
    }

    /** The context the player last said it was playing; see [follow]. */
    @Volatile private var context: String? = null

    /** Puts [mix] on for the playlist at [uri], or takes it off with null. */
    fun set(uri: String, mix: PlaylistMix?) {
        if (mix == null) playingMixes.remove(uri) else playingMixes[uri] = mix
        // The mix of what is already playing, switched on or off from its
        // page, or read only after the queue was put back: the player works
        // its plan out again rather than waiting for the next song.
        follow(context)
        onChange?.invoke()
    }

    /** Set by the player, to hear about [set]; see LibrespotPlayer.followMix. */
    @Volatile var onChange: (() -> Unit)? = null

    /**
     * The transition from [from] into [to] while [contextUri] plays, when that
     * playlist's mix is on and has one for exactly this pair.
     */
    fun transition(contextUri: String?, from: String, to: String): Transition? {
        val mix = playingMixes[contextUri ?: return null] ?: return null
        return mix.transitions.firstOrNull { it.from == from && it.to == to } ?: auto(mix, from, to)
    }

    /** The songs' analyses, by URI, as they arrive; see [prepare]. */
    private val analyses = ConcurrentHashMap<String, Analysis>()

    /** The songs whose analysis has been asked for. */
    private val asked = ConcurrentHashMap.newKeySet<String>()

    private val fetcher = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * Asks for the analyses of [uris] not already asked for, and has the
     * player work its plan out again once they are in. A failed request is
     * asked again the next time the pair comes up.
     */
    private fun prepare(uris: List<String>) {
        val missing = uris.filter { it.startsWith("spotify:track:") && asked.add(it) }
        if (missing.isEmpty()) return
        fetcher.execute {
            val found = parseAnalyses(NativeBridge.tracksMixAnalysis(org.json.JSONArray(missing).toString()))
            analyses.putAll(found)
            (missing - found.keys).forEach { asked.remove(it) }
            android.util.Log.i("SquareMix", "analysis for ${found.size} of ${missing.size} songs")
            if (found.isNotEmpty()) onChange?.invoke()
        }
    }

    /**
     * The automatic transition, where nobody saved one, worked out the way
     * Spotify's Automix does, from the same traits; see catalog.rs.
     *
     * Its app keeps its own result to itself, and doing exactly what it does
     * was not possible: what it chooses among the beat-matched transitions
     * depends on more than these traits tell. What it does was measured on
     * some 200 pairs of songs, and this follows the same rules:
     *
     * - The two are beat-matched when, somewhere near the end of the first and
     *   the start of the second, a bar of one is within 10% of a bar of the
     *   other: every pair it matched and none it did not, but for a few. Then
     *   the second is brought to the first one's tempo over two, four or eight
     *   bars, starting on a downbeat in both; see [beatmatched].
     * - Otherwise it is a plain five-second crossfade, centred where the first
     *   song stops really sounding, with the second coming in two and a half
     *   seconds before it starts to; see [crossfade].
     */
    private fun auto(mix: PlaylistMix, from: String, to: String): Transition? {
        val a = analyses[from]
        val b = analyses[to]
        if (a == null || b == null) {
            prepare(listOf(from, to))
            return null
        }
        val length = mix.durations[from]?.takeIf { it > 0 } ?: a.lengthMs
        return beatmatched(from, to, a, b) ?: crossfade(from, to, a, b, length)
    }

    /** See [auto]. Within half of it each side of the first song's last sound. */
    private fun crossfade(from: String, to: String, a: Analysis, b: Analysis, length: Long): Transition? {
        val centre = minOf(a.fadeOutMs, length - 10)
        val out = (centre - CROSSFADE_MS / 2).coerceAtLeast(0)
        val end = minOf(length, centre + CROSSFADE_MS / 2)
        if (end <= out) return null
        return Transition(
            index = -1,
            from = from,
            to = to,
            outMs = out,
            inMs = (b.fadeInMs - CROSSFADE_MS / 2).coerceAtLeast(0),
            fadeMs = end - out,
            bpm = 0f,
            preset = null,
        )
    }

    /**
     * See [auto]. Every way of laying [BEATMATCH_BARS] bars of the first song's
     * last forty over as many of the second one's first thirty-two, at a
     * tempo within 10%, scored on what Spotify's choices were seen to favour:
     *
     * - ending near where the first stops really sounding, and starting near
     *   where the second starts to;
     * - two bars rather than four, four rather than eight;
     * - nobody singing in both at once, no singing of the second skipped and
     *   none of the first cut off;
     * - Spotify's own suggested points, and as little change of tempo as can be.
     *
     * The weights were fitted to its choices. Null when no bars match.
     */
    private fun beatmatched(from: String, to: String, a: Analysis, b: Analysis): Transition? {
        val da = a.downbeats
        val db = b.downbeats
        if (da.size < 10 || db.size < 10) return null
        val barA = (da.last() - da[da.size - 9]) / 8.0
        val barB = (db[8] - db[0]) / 8.0
        val leave = a.fadeOutMs
        val arrive = b.fadeInMs
        val outStarts = a.points[1] ?: LongArray(0)
        val outEnds = a.points[2] ?: LongArray(0)
        val inStarts = b.points[3] ?: LongArray(0)
        val inEnds = b.points[4] ?: LongArray(0)

        var best: Transition? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (bars in BEATMATCH_BARS) {
            for (end in da.lastIndex downTo maxOf(bars, da.size - 40) + 1) {
                if (da[end] > leave + 2 * barA) continue
                val start = end - bars
                val overlap = (da[end] - da[start]).toDouble()
                for (j in 0 until minOf(32, db.size - 1 - bars)) {
                    val speed = (db[j + bars] - db[j]) / overlap
                    if (speed < 0.9 || speed > 1.1) continue
                    val early = (leave - da[end]) / barA
                    val late = (db[j] - arrive) / barB
                    val singingA = a.singing(da[start], da[end])
                    val singingB = b.singing(db[j], db[j + bars])
                    val score = -0.083 * early - 0.021 * early * early / 10 -
                        0.018 * late - 0.034 * late * late / 10 -
                        (if (bars == 4) 2.223 else 0.0) - (if (bars == 8) 3.907 else 0.0) +
                        0.342 * singingA - 0.705 * singingB - 5.813 * singingA * singingB -
                        2.738 * (if (db[j] > arrive) b.singing(arrive, db[j]) else 0.0) -
                        3.637 * (if (leave > da[end]) a.singing(da[end], leave) else 0.0) -
                        7.13 * kotlin.math.abs(speed - 1) +
                        (if (outStarts.near(da[start])) 1.501 else 0.0) +
                        (if (outEnds.near(da[end])) 1.138 else 0.0) +
                        (if (inStarts.near(db[j])) 0.827 else 0.0) +
                        (if (inEnds.near(db[j + bars])) 0.686 else 0.0) -
                        (if (b.sections.near(db[j])) 0.582 else 0.0) +
                        (if (a.sections.near(da[end])) 0.553 else 0.0)
                    if (score > bestScore) {
                        bestScore = score
                        best = Transition(
                            index = -1,
                            from = from,
                            to = to,
                            outMs = da[start],
                            inMs = db[j],
                            fadeMs = overlap.toLong(),
                            bpm = (bars * 240_000 / overlap).toFloat(),
                            preset = null,
                            speedIn = speed.toFloat(),
                        )
                    }
                }
            }
        }
        return best
    }

    /** Whether any of these times is within a few milliseconds of [ms]. */
    private fun LongArray.near(ms: Long) = any { kotlin.math.abs(it - ms) <= 60 }

    private const val CROSSFADE_MS = 5_000L

    private val BEATMATCH_BARS = intArrayOf(2, 4, 8)
}
