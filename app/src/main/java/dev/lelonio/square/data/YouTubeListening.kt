package dev.lelonio.square.data

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/**
 * What YouTube Music's history page can say about when, and how often.
 *
 * The page is not a log. It lists each song once, where it was last played,
 * newest first, under headings rather than times ("Today", "Last week", a
 * month). So two things are read out of it here: the day a heading stands for,
 * for a first reading of what came before Square was there to watch; and,
 * between one reading and the next, which songs came back to the top, which is
 * what a song played again does.
 */
object YouTubeListening {
    /** A play read from the account, with the stretch of time it happened in. */
    data class RemotePlay(val track: CatalogTrack, val at: Long, val window: LongRange)

    /**
     * The day a heading of the page stands for, asked for in English; null for
     * a heading this does not know, whose songs are then left out rather than
     * given a day they may not have.
     */
    fun sectionDay(title: String, today: LocalDate): LocalDate? {
        val words = title.trim().lowercase(Locale.ENGLISH)
        val day = when (words) {
            "today" -> today
            "yesterday" -> today.minusDays(1)
            "this week" -> today.minusDays(2)
            "last week" -> today.minusWeeks(1)
            "this month" -> today.minusDays(14).coerceAtLeast(today.withDayOfMonth(1))
            "last month" -> today.minusMonths(1).withDayOfMonth(15)
            else -> month(words, today)?.let { month ->
                if (month == YearMonth.from(today)) today.withDayOfMonth(1) else month.atDay(15)
            }
        }
        return day?.takeIf { it <= today }
    }

    private val monthYear: DateTimeFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive().appendPattern("MMMM yyyy").toFormatter(Locale.ENGLISH)
    private val monthOnly: DateTimeFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive().appendPattern("MMMM").toFormatter(Locale.ENGLISH)

    private fun month(words: String, today: LocalDate): YearMonth? =
        runCatching { YearMonth.parse(words, monthYear) }.getOrNull()
            ?: runCatching {
                // A month without a year is this year's, or last year's when
                // it has not come round yet.
                val named = java.time.Month.from(monthOnly.parse(words))
                val month = YearMonth.of(today.year, named)
                if (month > YearMonth.from(today)) month.minusYears(1) else month
            }.getOrNull()

    /**
     * The songs played since [previous] was read, newest first.
     *
     * A song played again leaves its place and goes to the top, so [now] is
     * some songs on top of [previous] with those songs taken out of it. The
     * smallest such top is what was played. Null when the two readings have
     * nothing in common, which is not a later reading of the same history: a
     * different account, or one cleared.
     */
    fun replays(previous: List<String>, now: List<String>): List<String>? {
        if (now.isEmpty()) return emptyList()
        if (previous.isEmpty() || previous.none { it in now.toSet() }) return null
        for (top in 0..now.size) {
            val played = now.subList(0, top).toSet()
            val rest = now.subList(top, now.size)
            val before = previous.filter { it !in played }
            val overlap = minOf(rest.size, before.size)
            if (rest.subList(0, overlap) == before.subList(0, overlap)) return now.subList(0, top)
        }
        return now
    }

    /**
     * The plays to file from one reading of the page.
     *
     * The first reading, or one of a history that is no longer the one last
     * read, takes each song once on the day of its heading. Later readings take
     * only what came back to the top, spread over the time since the last one,
     * the newest at the end of it.
     */
    fun plays(
        sections: List<Pair<String, List<CatalogTrack>>>,
        previous: List<String>?,
        previousAt: Long,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<RemotePlay> {
        val songs = sections.flatMap { it.second }
        val fresh = previous?.let { replays(it, songs.map { song -> song.uri }) }
        if (fresh == null) {
            val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            return sections.flatMap { (title, tracks) ->
                val day = sectionDay(title, today) ?: return@flatMap emptyList()
                val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
                val end = minOf(day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1, now)
                val at = minOf(day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), now)
                tracks.map { RemotePlay(it, at, start..end) }
            }
        }
        val byUri = songs.associateBy { it.uri }
        val span = (now - previousAt).coerceAtLeast(0)
        return fresh.mapIndexed { index, uri ->
            RemotePlay(byUri.getValue(uri), now - span * index / fresh.size, previousAt..now)
        }
    }
}
