package dev.lelonio.square.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Tracks the listener has liked ("Tus me gusta").
 *
 * Persisted on this device and updated whenever a heart is added or removed,
 * so the notification, the lock screen and the player share one state without
 * waiting on a network round-trip.
 */
class LikedStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveLock = Mutex()

    private val _liked = MutableStateFlow(load())
    val likedTracks: StateFlow<Set<String>> = _liked.asStateFlow()

    fun isLiked(uri: String?): Boolean = uri != null && uri in _liked.value

    fun seed(uris: Collection<String>) {
        if (uris.isEmpty()) return
        val updated = _liked.value + uris
        if (updated.size != _liked.value.size) {
            _liked.value = updated
            save(updated)
        }
    }

    fun add(uri: String) {
        if (uri in _liked.value) return
        val updated = _liked.value + uri
        _liked.value = updated
        save(updated)
    }

    fun remove(uri: String) {
        if (uri !in _liked.value) return
        val updated = _liked.value - uri
        _liked.value = updated
        save(updated)
    }

    fun toggle(uri: String): Boolean {
        val nowLiked = uri !in _liked.value
        if (nowLiked) add(uri) else remove(uri)
        return nowLiked
    }

    private fun load(): Set<String> =
        prefs.getStringSet(KEY_LIKED, null)?.toSet() ?: emptySet()

    private fun save(tracks: Set<String>) {
        val snapshot = tracks.toSet()
        scope.launch {
            saveLock.withLock {
                prefs.edit().putStringSet(KEY_LIKED, snapshot).apply()
            }
        }
    }

    private companion object {
        const val FILE_NAME = "square_liked_tracks"
        const val KEY_LIKED = "liked_tracks"
    }
}
