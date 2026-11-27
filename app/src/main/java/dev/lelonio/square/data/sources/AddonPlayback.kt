package dev.lelonio.square.data.sources

/** Stable, temporary mapping used by the player to resolve addon streams. */
object AddonPlayback {
    const val URI_PREFIX = "square:addon:"

    fun uri(sourceId: String, trackId: String): String = "$URI_PREFIX$sourceId:$trackId"

    fun parse(uri: String): Pair<String, String>? {
        if (!uri.startsWith(URI_PREFIX)) return null
        val rest = uri.removePrefix(URI_PREFIX)
        val split = rest.indexOf(':')
        if (split <= 0 || split >= rest.length - 1) return null
        return rest.substring(0, split) to rest.substring(split + 1)
    }
}
