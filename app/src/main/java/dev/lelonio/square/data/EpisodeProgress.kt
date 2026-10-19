package dev.lelonio.square.data

import android.content.Context

/**
 * How far into each podcast episode the listener got, on this phone.
 *
 * Spotify keeps its own answer and sends it with every episode, but what it
 * knows is what its own apps told it: an episode heard here, through the
 * engine, may never reach it. So the phone keeps its own, and an episode's row
 * says whichever of the two got further — unless either says it was finished,
 * which wins: an episode heard to the end elsewhere is not one to resume here.
 */
class EpisodeProgress(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private class Entry(val positionMs: Long, val finished: Boolean, val at: Long)

    /** Where [uri] stands, from a position the player reported. */
    fun record(uri: String, positionMs: Long, durationMs: Long) {
        if (!isEpisode(uri) || positionMs < MIN_POSITION_MS) return
        val finished = durationMs > 0 && positionMs >= durationMs - END_MARGIN_MS
        write(uri, if (finished) 0 else positionMs, finished)
    }

    /** [uri] played through to the next one. */
    fun finished(uri: String) {
        if (isEpisode(uri)) write(uri, 0, true)
    }

    /** Where to start [uri] from, or zero for the beginning. */
    fun resumeFor(track: CatalogTrack): Long {
        val known = apply(track)
        if (known.played) return 0
        // Within the last half minute is the end, and starting there is
        // starting at the credits.
        if (known.durationMs > 0 && known.resumeMs >= known.durationMs - END_MARGIN_MS) return 0
        return known.resumeMs
    }

    /** [track] with this phone's progress folded into the source's. */
    fun apply(track: CatalogTrack): CatalogTrack {
        if (!track.isEpisode) return track
        val local = read(track.uri) ?: return track
        val played = track.played || local.finished
        return track.copy(
            played = played,
            resumeMs = if (played) 0 else maxOf(track.resumeMs, local.positionMs),
        )
    }

    private fun read(uri: String): Entry? {
        val raw = prefs.getString(uri, null) ?: return null
        val parts = raw.split(',')
        return Entry(
            positionMs = parts.getOrNull(0)?.toLongOrNull() ?: return null,
            finished = parts.getOrNull(1) == "1",
            at = parts.getOrNull(2)?.toLongOrNull() ?: 0L,
        )
    }

    private fun write(uri: String, positionMs: Long, finished: Boolean) {
        val edit = prefs.edit()
            .putString(uri, "$positionMs,${if (finished) 1 else 0},${System.currentTimeMillis()}")
        // Kept to the most recent few hundred: nobody is half-way through more.
        val all = prefs.all
        if (all.size > MAX_ENTRIES) {
            all.entries
                .sortedBy { (it.value as? String)?.split(',')?.getOrNull(2)?.toLongOrNull() ?: 0L }
                .take(all.size - MAX_ENTRIES)
                .forEach { edit.remove(it.key) }
        }
        edit.apply()
    }

    private fun isEpisode(uri: String) = uri.startsWith("spotify:episode:")

    private companion object {
        const val FILE_NAME = "episode_progress"
        const val MAX_ENTRIES = 400

        /** Less than this in is not having started. */
        const val MIN_POSITION_MS = 15_000L

        /** And this close to the end is having finished. */
        const val END_MARGIN_MS = 30_000L
    }
}
