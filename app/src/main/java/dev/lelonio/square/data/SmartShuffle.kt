package dev.lelonio.square.data

/** Spotify's enhanced playlist context, rather than songs drawn from a track radio. */
object SmartShuffle {
    const val COMMAND = "dev.lelonio.square.SMART_SHUFFLE"
    const val MODE = "dev.lelonio.square.SMART_SHUFFLE_ACTIVE"
    const val RECOMMENDED = "dev.lelonio.square.SMART_RECOMMENDED"

    suspend fun recommendations(context: String, excluded: Set<String>): List<CatalogTrack> {
        require(context.startsWith("spotify:playlist:") || context.startsWith("spotify:user:") && context.endsWith(":collection"))
        val originals = Catalog.contextTrackUris(context).toSet()
        val enhanced = Catalog.contextTrackUris("$context?spotify-apply-lenses=enhance")
        val uris = candidates(enhanced, originals + excluded)
        android.util.Log.i("SquareSmartShuffle", "enhanced=${enhanced.size}, original=${originals.size}, recommendations=${uris.size}")
        return Catalog.tracks(uris.take(50))
    }

    fun candidates(enhanced: List<String>, excluded: Set<String>): List<String> =
        enhanced.filter { it.startsWith("spotify:track:") && it !in excluded }.distinct()

    /** Positions in the existing order; explicit play-next entries are never displaced. */
    fun insertionPoints(queued: List<Boolean>, current: Int, count: Int): List<Int> {
        if (current !in queued.indices || count <= 0) return emptyList()
        var songs = 1
        val points = mutableListOf<Int>()
        for (index in current + 1 until queued.size) {
            if (!queued[index]) songs++
            if (!queued[index] && songs % 3 == 0 && points.size < count) points += index + 1
        }
        if (points.isEmpty()) points += queued.size
        return points.take(count)
    }
}
