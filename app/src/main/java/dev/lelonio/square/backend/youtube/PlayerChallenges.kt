package dev.lelonio.square.backend.youtube

import android.content.Context
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * The two puzzles in a signed-in YouTube stream address, solved by YouTube's
 * own player script.
 *
 * An address YouTube gives an account comes enciphered: its signature has to
 * be run through a function in the website's player script, and so does the
 * `n` parameter, without which the stream is throttled to a crawl. The
 * function changes with every version of the script and is buried in three
 * megabytes of obfuscated code, which is why NewPipe's patterns for finding it
 * stopped matching. This runs yt-dlp's solver (ejs, public domain) over the
 * script instead: it parses the script and lifts the two functions out of it,
 * whatever they are called this week.
 *
 * In Android's isolated JavaScript engine, not a web page: the solver sets up
 * the `location` and `document` the script expects, which in a page would
 * navigate it away. The script is fetched once per version and kept on disk,
 * and the solver's preparation of it is kept in the engine for as long as the
 * engine lives.
 */
object PlayerChallenges {

    /** A version of YouTube's player script, and the signature timestamp it was built for. */
    class Script(val id: String, val code: String, val signatureTimestamp: Int)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var sandbox: JavaScriptSandbox? = null
    private var isolate: JavaScriptIsolate? = null

    /** The player version the isolate has prepared; see [prepare]. */
    private var prepared: String? = null

    private var script: Script? = null
    private var scriptCheckedAt = 0L

    /** The current player script; its version looked up at most once an hour. */
    @Synchronized
    fun script(context: Context): Script {
        val now = android.os.SystemClock.elapsedRealtime()
        script?.takeIf { now - scriptCheckedAt < SCRIPT_TTL_MS }?.let { return it }
        val id = currentPlayerId()
        val kept = File(context.cacheDir, "yt-player/$id.js")
        val code = kept.takeIf { it.exists() && it.length() > 0 }?.readText()
            ?: get("https://www.youtube.com/s/player/$id/player_ias.vflset/en_US/base.js").also {
                kept.parentFile?.mkdirs()
                // One version kept: they are three megabytes each.
                kept.parentFile?.listFiles()?.forEach { old -> if (old != kept) old.delete() }
                kept.writeText(it)
            }
        val timestamp = TIMESTAMP.find(code)?.groupValues?.get(1)?.toInt()
            ?: error("no signature timestamp in player $id")
        return Script(id, code, timestamp).also {
            script = it
            scriptCheckedAt = now
        }
    }

    /**
     * Solves [n] and [sig] challenges with [script], blocking; the answers by
     * challenge. Runs on the player's loading thread, never the main one.
     */
    @Synchronized
    fun solve(context: Context, script: Script, n: List<String>, sig: List<String>): Pair<Map<String, String>, Map<String, String>> {
        val engine = isolate(context)
        prepare(engine, script)
        val requests = JSONArray().apply {
            if (n.isNotEmpty()) put(JSONObject().put("type", "n").put("challenges", JSONArray(n)))
            if (sig.isNotEmpty()) put(JSONObject().put("type", "sig").put("challenges", JSONArray(sig)))
        }
        val answer = engine.evaluateJavaScriptAsync(
            "JSON.stringify(jsc({type: 'preprocessed', preprocessed_player: globalThis.__player, requests: $requests}))",
        ).get(SOLVE_TIMEOUT_S, TimeUnit.SECONDS)
        val responses = JSONObject(answer).getJSONArray("responses")
        var nIndex = -1
        var sigIndex = -1
        var at = 0
        if (n.isNotEmpty()) nIndex = at++
        if (sig.isNotEmpty()) sigIndex = at
        fun results(index: Int): Map<String, String> {
            if (index < 0) return emptyMap()
            val response = responses.getJSONObject(index)
            if (response.optString("type") != "result") error("challenge unsolved: ${response.optString("error")}")
            val data = response.getJSONObject("data")
            return data.keys().asSequence().associateWith { data.getString(it) }
        }
        return results(nIndex) to results(sigIndex)
    }

    /** The solver's parse of [script], made once per version and kept in the engine. */
    private fun prepare(engine: JavaScriptIsolate, script: Script) {
        if (prepared == script.id) return
        val started = android.os.SystemClock.elapsedRealtime()
        engine.evaluateJavaScriptAsync(
            "globalThis.__player = jsc({type: 'player', player: ${JSONObject.quote(script.code)}, " +
                "requests: [], output_preprocessed: true}).preprocessed_player; 'ok'",
        ).get(PREPARE_TIMEOUT_S, TimeUnit.SECONDS)
        prepared = script.id
        android.util.Log.i(TAG, "player ${script.id} prepared in ${android.os.SystemClock.elapsedRealtime() - started} ms")
    }

    private fun isolate(context: Context): JavaScriptIsolate {
        isolate?.let { return it }
        if (!JavaScriptSandbox.isSupported()) error("this phone's WebView has no isolated JavaScript engine")
        val box = sandbox ?: JavaScriptSandbox.createConnectedInstanceAsync(context.applicationContext)
            .get(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .also { sandbox = it }
        if (!box.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)) {
            error("this phone's JavaScript engine cannot take a script the size of YouTube's player")
        }
        val parameters = IsolateStartupParameters().apply {
            if (box.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) {
                maxHeapSizeBytes = MAX_HEAP_BYTES
            }
        }
        val engine = box.createIsolate(parameters)
        // The solver and the two libraries it is bundled with (meriyah, which
        // parses the script, and astring, which writes the lifted functions
        // back out). The core reads them as globals of those names.
        val lib = context.assets.open("ejs/yt.solver.lib.min.js").bufferedReader().use { it.readText() }
        val core = context.assets.open("ejs/yt.solver.core.min.js").bufferedReader().use { it.readText() }
        engine.evaluateJavaScriptAsync(
            "$lib\n;var meriyah = lib.meriyah, astring = lib.astring;\n$core\n;globalThis.jsc = jsc; 'ok'",
        ).get(PREPARE_TIMEOUT_S, TimeUnit.SECONDS)
        prepared = null
        return engine.also { isolate = it }
    }

    /** Throws the engine away, so the next solve starts a fresh one; after a failure. */
    @Synchronized
    fun reset() {
        runCatching { isolate?.close() }
        isolate = null
        prepared = null
    }

    private fun currentPlayerId(): String {
        val page = get("https://www.youtube.com/iframe_api")
        return PLAYER_ID.find(page)?.groupValues?.get(1) ?: error("no player version in the iframe API")
    }

    private fun get(url: String): String =
        http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} for $url")
            response.body?.string() ?: error("empty answer from $url")
        }

    private val PLAYER_ID = Regex("""player\\?/([0-9a-f]{8})""")
    private val TIMESTAMP = Regex("""signatureTimestamp:(\d+)""")

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"
    private const val SCRIPT_TTL_MS = 60 * 60 * 1000L
    private const val CONNECT_TIMEOUT_S = 10L
    private const val PREPARE_TIMEOUT_S = 30L
    private const val SOLVE_TIMEOUT_S = 15L
    private const val MAX_HEAP_BYTES = 256L * 1024 * 1024
    private const val TAG = "SquareYTChallenges"
}
