package dev.lelonio.square.data.sources

import dev.lelonio.square.data.CatalogTrack

/** Resolves a catalogue track through enabled addons, then returns null for fallback. */
object AddonResolver {
    suspend fun resolve(store: AddonSourceStore, track: CatalogTrack): ResolvedAddonStream? {
        for (source in store.sources.value.filter { it.enabled }) {
            try {
                val client = AddonSourceClient(source)
                val candidates = client.search("${track.name} ${track.artist}".trim(), limit = 10)
                val match = AddonTrackMatcher.bestMatch(candidates, track.name, track.artist, track.durationMs)
                    ?: continue
                val stream = client.stream(match) ?: continue
                return ResolvedAddonStream(source.id, match.id, track, stream)
            } catch (_: Exception) {
                // One unavailable addon must not block the next source or YouTube fallback.
            }
        }
        return null
    }

    data class ResolvedAddonStream(
        val sourceId: String,
        val trackId: String,
        val catalogueTrack: CatalogTrack,
        val stream: AddonStream,
    )
}
