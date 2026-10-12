package dev.lelonio.square.data

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * Which file on this phone each local entry of a Spotify playlist is, kept.
 *
 * A `spotify:local:` entry plays from a file only if the engine is told which
 * one, and that used to be worked out when a playlist page opened and held in
 * memory only. The queue survived a restart and this did not: a local song
 * that was playing came back as an entry the engine could not find, silent
 * until its playlist was opened again and the match made anew. Kept on disk
 * and handed to the engine as it starts, the queue comes back whole.
 */
object LocalFileBindings {

    private val bindings = ConcurrentHashMap<String, String>()
    private var store: File? = null

    fun attach(context: Context) {
        store = File(context.applicationContext.filesDir, "local-file-bindings.json")
        runCatching {
            val json = JSONObject(store?.takeIf { it.exists() }?.readText() ?: return@runCatching)
            json.keys().forEach { uri -> json.optString(uri).takeIf { it.isNotEmpty() }?.let { bindings[uri] = it } }
        }
    }

    fun has(uri: String): Boolean = bindings.containsKey(uri)

    /** New matches, kept and told to the engine. */
    fun bind(entries: Map<String, String>) {
        if (entries.isEmpty() || entries.all { (uri, path) -> bindings[uri] == path }) {
            push()
            return
        }
        bindings.putAll(entries)
        runCatching { store?.writeText(JSONObject(bindings.toMap()).toString()) }
        push()
    }

    /** Everything kept, to the engine: on every match, and when it starts. */
    fun push() {
        if (bindings.isEmpty()) return
        runCatching { dev.lelonio.square.nativecore.NativeBridge.setLocalFiles(JSONObject(bindings.toMap()).toString()) }
    }
}
