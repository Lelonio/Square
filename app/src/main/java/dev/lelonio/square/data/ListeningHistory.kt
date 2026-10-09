package dev.lelonio.square.data

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

@Serializable
data class ListeningEvent(
    val id: String,
    val track: CatalogTrack,
    val startedAt: Long,
    val endedAt: Long = startedAt,
    val listenedMs: Long = 0,
    val qualified: Boolean = false,
    val measuredMonths: Map<String, Long> = emptyMap(),
    val spotifyAt: Long? = null,
)

data class MonthlyListening(
    val month: YearMonth,
    val plays: Int,
    val minutes: Long,
    val tracks: List<Pair<CatalogTrack, Int>>,
    val artists: List<Pair<String, Int>>,
)

/** Pure aggregation and reconciliation, shared by the store and regression tests. */
object ListeningHistory {
    fun record(events: List<ListeningEvent>, event: ListeningEvent): List<ListeningEvent> {
        val old = events.firstOrNull { it.id == event.id }
        if (old != null && old.listenedMs > event.listenedMs) return events
        if (old != null && old.listenedMs == event.listenedMs && old.track == event.track &&
            old.qualified == event.qualified) return events
        // The API may return an in-progress listen before the next local flush.
        val imported = if (old?.spotifyAt == null) events.firstOrNull {
            it.id.startsWith("spotify:") && it.track.uri == event.track.uri &&
                it.startedAt in (event.startedAt - 15_000)..(event.endedAt + 15_000)
        } else null
        val spotifyAt = old?.spotifyAt ?: imported?.spotifyAt ?: event.spotifyAt
        return events.filterNot { it.id == event.id || it.id == imported?.id } + event.copy(
            spotifyAt = spotifyAt, qualified = event.qualified || spotifyAt != null,
        )
    }

    fun merge(events: List<ListeningEvent>, track: CatalogTrack, at: Long): List<ListeningEvent> {
        if (events.any { it.spotifyAt == at && it.track.uri == track.uri }) return events
        // Match a local play only once. Repeated listens to the same song stay distinct.
        val match = events.filter { it.spotifyAt == null && it.id.startsWith("local:") &&
            it.track.uri == track.uri && at in (it.startedAt - 15_000)..(it.endedAt + 15_000) }
            .minByOrNull { kotlin.math.abs(it.startedAt - at) }
        return if (match != null) events.map {
            if (it.id == match.id) it.copy(spotifyAt = at, qualified = true) else it
        } else events + ListeningEvent("spotify:$at:${track.uri}", track, at, qualified = true, spotifyAt = at)
    }

    /**
     * A play read from an account that keeps no times; see [YouTubeListening].
     *
     * Square's own measurement of the same song inside [window] is that play,
     * once: it is marked as seen by the account and nothing is added. Otherwise
     * the play is added at [at].
     */
    fun mergeRemote(events: List<ListeningEvent>, track: CatalogTrack, at: Long, window: LongRange): List<ListeningEvent> {
        val match = events.filter {
            it.spotifyAt == null && it.id.startsWith("local:") && it.qualified &&
                it.track.uri == track.uri && it.startedAt in window
        }.minByOrNull { it.startedAt }
        return if (match != null) events.map {
            if (it.id == match.id) it.copy(spotifyAt = it.startedAt) else it
        } else events + ListeningEvent("remote:$at:${track.uri}", track, at, qualified = true, spotifyAt = at)
    }

    fun summarize(events: List<ListeningEvent>, month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): MonthlyListening {
        val start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val counted = events.filter { it.qualified && (it.spotifyAt ?: it.startedAt) in start until end }
        val tracks = counted.groupBy { it.track.uri }.values.map { it.last().track to it.size }
            .sortedWith(compareByDescending<Pair<CatalogTrack, Int>> { it.second }.thenBy { it.first.name })
        val artists = counted.flatMap { event ->
            event.track.artists.map { it.name }.ifEmpty { listOf(event.track.artist) }.filter { it.isNotBlank() }.distinct()
        }.groupingBy { it }.eachCount().toList().sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
        val ms = events.sumOf { it.measuredMonths[month.toString()] ?: 0L }
        return MonthlyListening(month, counted.size, ms / 60_000, tracks, artists)
    }
}

/** Measures elapsed audible time, never the seek position or the nominal song length. */
class ListeningSampler {
    private var event: ListeningEvent? = null
    private var lastTick: Long? = null
    private var wasPlaying = false
    fun sample(track: CatalogTrack?, playing: Boolean, now: Long, tick: Long): ListeningEvent? {
        val previous = event
        if (track == null || previous?.track?.uri != track.uri) {
            event = track?.takeIf { playing }?.let { ListeningEvent("local:$now:${it.uri}", it, now) }
            lastTick = tick
            wasPlaying = playing
            return previous
        }
        val delta = if (playing && wasPlaying) (tick - (lastTick ?: tick)).coerceIn(0, 2_000) else 0
        val updated = (previous ?: return null).copy(track = track, endedAt = now, listenedMs = previous.listenedMs + delta)
        val threshold = if (track.durationMs > 0) minOf(track.durationMs / 2, 240_000) else 30_000
        val measured = previous.measuredMonths.toMutableMap()
        if (delta > 0) {
            val zone = ZoneId.systemDefault()
            var from = now - delta
            while (from < now) {
                val local = Instant.ofEpochMilli(from).atZone(zone)
                val month = YearMonth.from(local)
                val boundary = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val until = minOf(now, boundary)
                measured[month.toString()] = (measured[month.toString()] ?: 0L) + until - from
                from = until
            }
        }
        event = updated.copy(qualified = updated.listenedMs >= threshold, measuredMonths = measured)
        lastTick = tick
        wasPlaying = playing
        return event
    }
    fun reset() { event = null; lastTick = null; wasPlaying = false }
}
