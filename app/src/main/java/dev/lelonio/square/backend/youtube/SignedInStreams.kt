package dev.lelonio.square.backend.youtube

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.response.PlayerResponse
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException

/**
 * Streams for the songs YouTube will only play to someone signed in.
 *
 * Every stream is asked for without an account, which is what keeps the
 * listener's account out of the playback requests; and for nearly every song
 * that is all it takes. A few, explicit records above all, answer "please sign
 * in" to any client that has no account, and played nothing: the player sat at
 * --:-- with no word of why. Those are asked for again here as the signed-in
 * account, through the client of YouTube's TV app, which takes the account's
 * cookie and needs no proof-of-origin token.
 *
 * What it answers is enciphered as on the website: the signature, and the
 * parameter YouTube throttles a stream by, are both undone by running the
 * site's own player script; see PlayerChallenges.
 */
object SignedInStreams {

    /** What to ask for: the sound, the picture alone, or both in one file. */
    enum class Kind { AUDIO, VIDEO, MUXED }

    /** Thrown when a song needs an account and none is signed in; see YouTubePlayerFactory. */
    class SignInRequired(videoId: String) : java.io.IOException("$videoId plays only when signed in")

    /**
     * Thrown when a song needs an account, one is signed in, and its stream
     * still could not be had: YouTube refused every client, or the address
     * could not be deciphered. Said as such, rather than as a sign-in to do.
     */
    class Refused(videoId: String, cause: Throwable) :
        java.io.IOException("$videoId could not be played signed in: ${cause.message}", cause)

    private val urls = ConcurrentHashMap<String, Pair<Long, String>>()

    /**
     * Whether [error], from asking without an account, is YouTube asking for
     * one: an age check, a song it serves to accounts alone, or its "confirm
     * you're not a bot", which it puts in front of every song for a network it
     * has stopped trusting.
     *
     * That last one is not a ContentNotAvailableException, so it was not taken
     * for a request for an account: a listener signed in, on such a network,
     * had every song fail and none tried as the account, which YouTube would
     * have played.
     */
    fun needsAccount(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any {
            it is AgeRestrictedContentException ||
                it is org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException ||
                (it is ContentNotAvailableException && it.message.orEmpty().contains("LOGIN_REQUIRED"))
        }

    val available: Boolean get() = YouTube.cookie != null

    /**
     * Forgets the addresses kept, after one failed to play: kept, it would be
     * handed out again for the hour, and the song could not be tried afresh.
     */
    fun forget() = urls.clear()

    /** The URL of [kind] for [videoId], as the signed-in account; blocking. */
    fun url(context: android.content.Context, videoId: String, kind: Kind, lowRate: Boolean): String {
        if (!available) throw SignInRequired(videoId)
        val key = "$videoId|$kind|$lowRate"
        val now = android.os.SystemClock.elapsedRealtime()
        urls[key]?.takeIf { now - it.first < URL_TTL_MS }?.let { return it.second }
        val script = PlayerChallenges.script(context)
        val response = player(videoId, script.signatureTimestamp)
        val issuedAt = android.os.SystemClock.elapsedRealtime()
        val formats = response.streamingData
        val chosen = when (kind) {
            Kind.AUDIO -> formats?.adaptiveFormats.orEmpty()
                .filter { it.isAudio && it.isOriginal }
                // AAC before Opus, as for every other stream; see the resolver.
                .sortedWith(
                    compareByDescending<PlayerResponse.StreamingData.Format> { it.mimeType.startsWith("audio/mp4") }
                        .thenBy { if (lowRate) it.bitrate else -it.bitrate },
                )
                .firstOrNull()
            Kind.VIDEO -> formats?.adaptiveFormats.orEmpty()
                .filter { !it.isAudio && (it.height ?: 0) in 1..MAX_VIDEO_HEIGHT }
                .sortedWith(
                    compareByDescending<PlayerResponse.StreamingData.Format> { it.height ?: 0 }
                        .thenByDescending { it.mimeType.startsWith("video/mp4") },
                )
                .firstOrNull()
            Kind.MUXED -> formats?.formats.orEmpty().maxByOrNull { it.height ?: 0 }
        } ?: error("no ${kind.name.lowercase()} stream for $videoId signed in")
        val url = try {
            urlOf(context, script, chosen)
        } catch (failure: Exception) {
            // A fresh engine next time: one that failed may be in any state.
            PlayerChallenges.reset()
            throw failure
        }
        awaitReady(videoId, url, issuedAt)
        urls[key] = now to url
        if (urls.size > 64) urls.entries.minByOrNull { it.value.first }?.let { urls.remove(it.key) }
        android.util.Log.i(
            TAG,
            "$videoId played signed in through $workingName (${kind.name.lowercase()}, itag ${chosen.itag})",
        )
        return url
    }

    /**
     * The first signed-in client that will play it. The TV app's comes first,
     * asked as YouTube's own TV app asks: of www.youtube.com, as a named TV. Its streams need no proof-of-origin token, which the website's
     * clients do: their addresses answer 403 without one. Asked through
     * music.youtube.com, or without a make and model, the TV client
     * answers "the page needs to be reloaded" instead, which is why the
     * library's own TV client is only a fallback. The rest are tried in turn
     * after it.
     */
    private fun player(videoId: String, timestamp: Int): PlayerResponse {
        val refusals = mutableListOf<String>()
        val tv = runCatching { tvPlayer(videoId, timestamp) }
            .onFailure { refusals += "TV: ${it.message}" }
            .getOrNull()
        val tvStatus = tv?.playabilityStatus
        if (tv != null && tvStatus?.status == "OK" && tv.streamingData != null) {
            workingName = "TVHTML5 (Samsung)"
            return tv
        }
        if (tv != null) refusals += "TV: ${tvStatus?.status} ${tvStatus?.reason.orEmpty()}"
        for (client in CLIENTS) {
            val response = runBlocking {
                YouTube.player(videoId, client = client, signatureTimestamp = timestamp)
            }.getOrNull()
            val status = response?.playabilityStatus
            if (response != null && status?.status == "OK" && response.streamingData != null) {
                workingName = client.clientName
                if (refusals.isNotEmpty()) android.util.Log.i(TAG, "$videoId refused first: ${refusals.joinToString(" / ")}")
                return response
            }
            refusals += "${client.clientName}: ${status?.status} ${status?.reason.orEmpty()}"
        }
        android.util.Log.w(TAG, "$videoId refused signed in: ${refusals.joinToString(" / ")}")
        error("signed in, $videoId is unplayable: ${refusals.joinToString(" / ")}")
    }

    /**
     * Waits for [url] to open. An address the TV client has just been given
     * answers 403 for its first three or four seconds, then plays; handed to
     * the player at once, it failed the song. Asked for its first two bytes
     * until it answers otherwise, for as long as [READY_WAIT_MS] after it was
     * issued; one that still refuses then is refused for good.
     */
    private fun awaitReady(videoId: String, url: String, issuedAt: Long) {
        while (true) {
            val code = runCatching {
                val request = Request.Builder().url(url).header("Range", "bytes=0-1").build()
                http.newCall(request).execute().use { it.code }
            }.getOrElse { return }
            if (code != 403) return
            val waited = android.os.SystemClock.elapsedRealtime() - issuedAt
            if (waited > READY_WAIT_MS) error("$videoId still refused after $waited ms")
            Thread.sleep(READY_POLL_MS)
        }
    }

    /** The player answer of YouTube's TV app, signed in; see [player]. */
    private fun tvPlayer(videoId: String, timestamp: Int): PlayerResponse {
        val locale = YouTube.locale
        val body = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", "TVHTML5")
                        .put("clientVersion", TV_VERSION)
                        .put("userAgent", TV_USER_AGENT)
                        .put("deviceMake", "Samsung")
                        .put("deviceModel", "UKS9800")
                        .put("osName", "Tizen")
                        .put("osVersion", "2.4.0")
                        .put("hl", locale.hl)
                        .put("gl", locale.gl)
                        .apply { YouTube.visitorData?.let { put("visitorData", it) } },
                ),
            )
            .put("videoId", videoId)
            .put(
                "playbackContext",
                JSONObject().put(
                    "contentPlaybackContext",
                    JSONObject().put("signatureTimestamp", timestamp).put("html5Preference", "HTML5_PREF_WANTS"),
                ),
            )
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        val cookie = YouTube.cookie ?: throw SignInRequired(videoId)
        val request = Request.Builder()
            .url("$WWW/youtubei/v1/player?prettyPrint=false")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", TV_USER_AGENT)
            .header("Origin", WWW)
            .header("X-Origin", WWW)
            .header("X-Youtube-Client-Name", "7")
            .header("X-Youtube-Client-Version", TV_VERSION)
            .header("Cookie", cookie)
            .header("X-Goog-AuthUser", "0")
            .apply {
                authorization(cookie)?.let { header("Authorization", it) }
                YouTube.visitorData?.let { header("X-Goog-Visitor-Id", it) }
                YouTube.pageId?.takeIf { it.isNotBlank() }?.let { header("X-Goog-PageId", it) }
            }
            .build()
        val text = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            response.body?.string() ?: error("empty answer")
        }
        return json.decodeFromString(PlayerResponse.serializer(), text)
    }

    /**
     * The hashes that prove the cookie's holder made the request, as the
     * website sends them: one for each of the account's three API cookies
     * the cookie carries, each over the time, the cookie and the origin.
     */
    private fun authorization(cookie: String): String? {
        val cookies = cookie.split(';').associate { it.trim().substringBefore('=') to it.trim().substringAfter('=', "") }
        val now = System.currentTimeMillis() / 1000
        fun hash(scheme: String, value: String?) = value?.takeIf { it.isNotEmpty() }?.let {
            val digest = java.security.MessageDigest.getInstance("SHA-1")
                .digest("$now $it $WWW".toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
            "$scheme ${now}_$digest"
        }
        return listOfNotNull(
            hash("SAPISIDHASH", cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"]),
            hash("SAPISID1PHASH", cookies["__Secure-1PAPISID"]),
            hash("SAPISID3PHASH", cookies["__Secure-3PAPISID"]),
        ).takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /** The name of the client that last played something, for the log. */
    @Volatile
    private var workingName: String? = null

    private val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val CLIENTS = listOf(
        YouTubeClient.TVHTML5,
        YouTubeClient.MOBILE,
        // The website's clients: their streams need a proof-of-origin token
        // this does not make, save for Premium accounts. Last, for those.
        YouTubeClient.WEB_CREATOR,
        YouTubeClient.WEB_REMIX,
        YouTubeClient.WEB_ACCOUNTS,
    )

    private const val WWW = "https://www.youtube.com"
    // A Samsung TV's, as yt-dlp's tv_samsung client: since August 2026 a TV
    // client that does not say what TV it is answers "the page needs to be
    // reloaded" to an account.
    private const val TV_VERSION = "5.20260707"
    private const val TV_USER_AGENT =
        "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/2.4.0 TV Safari/538.1"

    /**
     * A format's address with its signature and its throttling parameter
     * undone, both by YouTube's own player script; see PlayerChallenges.
     */
    private fun urlOf(
        context: android.content.Context,
        script: PlayerChallenges.Script,
        format: PlayerResponse.StreamingData.Format,
    ): String {
        var url: String
        var signature: String? = null
        var signatureParameter = "signature"
        val cipher = format.signatureCipher ?: format.cipher
        val direct = format.url
        if (direct != null) {
            url = direct
        } else {
            cipher ?: error("no address for itag ${format.itag}")
            val params = cipher.split('&').associate { pair ->
                pair.substringBefore('=') to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            }
            url = params["url"] ?: error("no url in the cipher")
            signature = params["s"]
            params["sp"]?.let { signatureParameter = it }
        }
        val throttle = android.net.Uri.parse(url).getQueryParameter("n")
        val (nAnswers, sigAnswers) = PlayerChallenges.solve(
            context,
            script,
            listOfNotNull(throttle),
            listOfNotNull(signature),
        )
        if (signature != null) {
            val solved = sigAnswers[signature] ?: error("signature unsolved")
            url += "&$signatureParameter=${URLEncoder.encode(solved, "UTF-8")}"
        }
        if (throttle != null) {
            val solved = nAnswers[throttle] ?: error("throttling parameter unsolved")
            url = url.replace(THROTTLE, "$1n=${URLEncoder.encode(solved, "UTF-8")}")
        }
        return url
    }

    private val THROTTLE = Regex("""([?&])n=[^&]*""")

    /** As long as the anonymous answers are kept; the addresses last for hours. */
    private const val URL_TTL_MS = 60 * 60 * 1000L

    private const val MAX_VIDEO_HEIGHT = 1080

    private const val READY_WAIT_MS = 10_000L
    private const val READY_POLL_MS = 500L

    private const val TAG = "SquareYTSignedIn"
}
