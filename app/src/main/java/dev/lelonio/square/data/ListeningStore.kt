package dev.lelonio.square.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class ListeningStore(context: Context) {
    private val prefs = context.getSharedPreferences("square_listening", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ListeningEvent.serializer())
    private val lock = Mutex()
    private val mutable = MutableStateFlow(runCatching {
        json.decodeFromString(serializer, prefs.getString("events", "[]")!!)
    }.getOrDefault(emptyList()))
    val events = mutable.asStateFlow()
    val account get() = prefs.getString("account", null)

    suspend fun useAccount(id: String) = withContext(Dispatchers.IO) { lock.withLock {
        if (account != id) {
            mutable.value = emptyList()
            prefs.edit().putString("account", id).remove("events").apply()
        }
    } }
    suspend fun record(event: ListeningEvent, owner: String) = withContext(Dispatchers.IO) { lock.withLock {
        if (account != owner) return@withLock
        save(ListeningHistory.record(mutable.value, event))
    } }
    suspend fun import(plays: List<Pair<CatalogTrack, Long>>, owner: String) = withContext(Dispatchers.IO) { lock.withLock {
        if (account != owner) return@withLock
        save(plays.fold(mutable.value) { events, (track, at) -> ListeningHistory.merge(events, track, at) })
    } }
    suspend fun clear() = withContext(Dispatchers.IO) { lock.withLock {
        prefs.edit().clear().apply(); mutable.value = emptyList()
    } }
    private fun save(events: List<ListeningEvent>) {
        if (events == mutable.value) return
        mutable.value = events
        prefs.edit().putString("events", json.encodeToString(serializer, events)).apply()
    }
}
