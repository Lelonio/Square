package dev.lelonio.square.data

/** Provider-independent lyrics identity used for caching and offset persistence. */
data class LyricsKey(
    val source: String,
    val trackUri: String,
)

/**
 * Pure synchronization helpers. Playback position remains authoritative; lyrics
 * only derive which timed line/word should be displayed.
 */
object LyricsSync {
    fun lineIndex(lyrics: Lyrics, positionMs: Long, offsetMs: Long = 0L): Int {
        val target = (positionMs + offsetMs).coerceAtLeast(0L)
        var low = 0
        var high = lyrics.lines.lastIndex
        var result = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val start = lyrics.lines[middle].startTimeMs
            if (start == null || start > target) {
                high = middle - 1
            } else {
                result = middle
                low = middle + 1
            }
        }
        return result
    }

    fun wordIndex(line: LyricLine, positionMs: Long, offsetMs: Long = 0L): Int {
        if (line.words.isEmpty()) return -1
        val target = (positionMs + offsetMs).coerceAtLeast(0L)
        var low = 0
        var high = line.words.lastIndex
        var result = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val word = line.words[middle]
            if (word.startMs > target) {
                high = middle - 1
            } else {
                result = middle
                low = middle + 1
            }
        }
        return result.takeIf { it >= 0 && target < line.words[it].endMs } ?: -1
    }
}
