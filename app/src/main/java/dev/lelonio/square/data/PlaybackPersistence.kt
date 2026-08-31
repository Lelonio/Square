package dev.lelonio.square.data

/**
 * Persistence boundary for playback restoration.
 *
 * PlaybackService owns the live playback state. Implementations of this
 * interface only persist/restore a snapshot; they must not start, pause, seek,
 * or otherwise mutate a player.
 */
interface PlaybackPersistence {
    fun save(state: SavedPlayback)
    fun load(): SavedPlayback?
    fun clear()
}
