package dev.lelonio.square.backend.lyrics

/**
 * Trims what upload titles carry and song titles do not.
 *
 * Uploads are named for a video — "(Official Video)", "[4K Remaster]",
 * "(Lyrics)" — and searching a lyrics database for any of that finds nothing.
 * Every source in this package matches on the words of the title, so they all
 * want the same cleaning.
 */
internal fun String.songTitle(): String =
    replace(NOISE, "")
        .replace(FEAT_NOISE, "")
        .replace(EDITION_NOISE, "")
        .replace(WHITESPACE, " ")
        .trim()

/**
 * Extracts the lead artist from collaboration strings like "Artist A, Artist B"
 * or "Artist A feat. Artist B", which lyrics databases often file under the primary name.
 */
internal fun String.primaryArtist(): String =
    split(',', '&', '/', ';')
        .firstOrNull { it.isNotBlank() }
        ?.replace(FEAT_TRAILING, "")
        ?.trim()
        ?.ifEmpty { this.trim() }
        ?: this.trim()

/**
 * Extracts all distinct artists from collaboration strings like "Artist A, Artist B",
 * "Artist A & Artist B", or "Artist A feat. Artist B".
 */
internal fun String.allArtists(): List<String> {
    val candidates = mutableListOf<String>()
    val raw = this.trim()
    if (raw.isNotBlank()) candidates += raw

    val splitArtists = split(',', '&', '/', ';')
        .flatMap { part ->
            part.split(Regex("\\s+(?:feat|ft)\\.?\\s+", RegexOption.IGNORE_CASE))
        }
        .map { it.trim() }
        .filter { it.isNotBlank() }

    for (artist in splitArtists) {
        if (artist !in candidates) {
            candidates += artist
        }
    }
    return candidates
}

private val NOISE = Regex(
    "\\((?:official|lyric|audio|video|visualizer|hd|4k|mv|m/v)[^)]*\\)" +
        "|\\[[^\\]]*(?:official|lyric|audio|video|remaster|hd|4k)[^\\]]*\\]",
    RegexOption.IGNORE_CASE,
)

private val FEAT_NOISE = Regex(
    "\\s*\\((?:feat|ft)\\.?\\s+[^)]+\\)|\\s*\\[(?:feat|ft)\\.?\\s+[^\\]]+\\]",
    RegexOption.IGNORE_CASE,
)

private val FEAT_TRAILING = Regex(
    "\\s+(?:feat|ft)\\.?\\s+.*",
    RegexOption.IGNORE_CASE,
)

private val EDITION_NOISE = Regex(
    "\\s*-\\s*(?:(?:\\d{4}\\s+)?remaster(?:ed)?|live(?:\\s+at\\s+[^\\-]+)?|anniversary(?:\\s+edition)?|deluxe(?:\\s+edition)?|radio\\s+edit|mono|stereo|bonus\\s+track).*",
    RegexOption.IGNORE_CASE,
)

private val WHITESPACE = Regex("\\s+")
