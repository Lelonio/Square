package dev.lelonio.square.playback

/**
 * Framework-neutral lifecycle classification for the playback layer.
 *
 * Media3 remains the actual playback engine. This vocabulary is for durable
 * application state and deterministic tests around asynchronous transitions.
 */
enum class PlaybackStatus {
    IDLE,
    LOADING,
    BUFFERING,
    PLAYING,
    PAUSED,
    SEEKING,
    RECOVERING,
    ENDED,
    ERROR,
}

/**
 * Immutable application playback state.
 *
 * The playback service/player is the runtime authority that produces this
 * state. UI layers derive presentation state from it and never mutate or
 * independently persist a competing copy.
 */
data class PlaybackStateSnapshot(
    val currentMediaId: String? = null,
    val queueMediaIds: List<String> = emptyList(),
    val currentIndex: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val repeatMode: Int = 0,
    val shuffleEnabled: Boolean = false,
    val backendId: String? = null,
    val error: PlaybackError? = null,
) {
    init {
        require(currentIndex >= 0) { "currentIndex must be non-negative" }
        require(positionMs >= 0) { "positionMs must be non-negative" }
        require(durationMs >= 0) { "durationMs must be non-negative" }
    }
}

/** Typed application-level failures crossing the playback boundary. */
sealed interface PlaybackError {
    data object BackendUnavailable : PlaybackError
    data object TrackUnavailable : PlaybackError
    data object Network : PlaybackError
    data object Authentication : PlaybackError
    data object Persistence : PlaybackError
    data object Unsupported : PlaybackError
    data class Unexpected(val diagnosticCode: String) : PlaybackError
}
