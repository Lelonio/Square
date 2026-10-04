package dev.lelonio.square.playback.websdk

import android.app.*
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.MediaMetadata
import android.os.Binder
import android.os.IBinder
import android.webkit.*
import androidx.core.app.NotificationCompat
import dev.lelonio.square.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Official SDK runtime shared by recovery playback and its setup screen. */
data class WebSdkState(
        val stage: String = "idle",
        val error: String? = null,
        val deviceId: String? = null,
        val paused: Boolean = true,
        val position: Long = 0,
        val duration: Long = 0,
        val title: String = "",
        val artist: String = "",
        val busy: Boolean = false,
        val uri: String = "",
        val ended: Boolean = false,
    )

class WebSdkEngine(private val context: android.content.Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(WebSdkState())
    val state = mutableState.asStateFlow()
    internal val account = (context.applicationContext as dev.lelonio.square.SquareApplication).spotifySdkAccount
    var webView: WebView? = null
        private set
    private var timeout: Job? = null
    private var pollJob: Job? = null
    private var playJob: Job? = null
    private var revision = 0
    private var playVersion = 0
    private var registeredDeviceId: String? = null
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    // Chromium owns audio focus for the actual output.
    private fun update(next: WebSdkState) { mutableState.value = next }

    /** Called from a visible user action, after the notification service has started. */
    fun connect(probeOnly: Boolean = false) {
        if (state.value.stage == "loading" || state.value.deviceId != null) return
        stopWebView()
        val generation = revision
        update(WebSdkState(stage = "loading"))
        val page = context.assets.open("websdk/player.html").bufferedReader().use { it.readText() }
            .replace("__CONNECT__", (!probeOnly).toString())
        webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Never navigate the bridge-bearing page to external content.
                    // The official SDK may load its own embedded frame.
                    return request.isForMainFrame || request.url.scheme != "https" ||
                        request.url.host != "sdk.scdn.co"
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    fail("renderer_error")
                    stopWebView()
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    val allowedOrigin = request.origin.toString().trimEnd('/') == ORIGIN.trimEnd('/') ||
                        (request.origin.scheme == "https" && request.origin.host == "sdk.scdn.co")
                    if (allowedOrigin && PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in request.resources) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
                    } else request.deny()
                }
            }
            addJavascriptInterface(object {
                @JavascriptInterface fun event(json: String) {
                    if (json.length > 16_384) return
                    scope.launch { if (generation == revision) receive(json, probeOnly) }
                }
                @JavascriptInterface fun token(id: Int) {
                    scope.launch {
                        if (generation != revision) return@launch
                        try {
                            val token = withContext(Dispatchers.IO) { account.tokens.validAccessToken() }
                            if (generation == revision) evaluateJavascript(
                                "window.receiveToken($id,${JSONObject.quote(token)})", null,
                            )
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { if (generation == revision) fail("authentication_error") }
                    }
                }
            }, "SquareSdk")
            loadDataWithBaseURL(ORIGIN, page, "text/html", "UTF-8", null)
        }
        timeout = scope.launch {
            delay(45_000)
            if (state.value.stage == "loading") fail("sdk_timeout")
        }
    }
    private fun receive(raw: String, probeOnly: Boolean) {
        val event = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (event.optString("type")) {
            "capable" -> {
                android.util.Log.i(TAG, "WebView supports EME")
                if (probeOnly) {
                    timeout?.cancel()
                    update(state.value.copy(stage = "capable"))
                }
            }
            "ready" -> {
                timeout?.cancel()
                event.optString("deviceId").takeIf { it.isNotBlank() }?.let {
                    registeredDeviceId = it
                    dev.lelonio.square.data.RemoteConnect.registerSdkDevice(it)
                }
                update(state.value.copy(stage = "ready", error = null,
                    deviceId = event.optString("deviceId").takeIf { it.isNotBlank() }))
                pollJob?.cancel()
                pollJob = scope.launch {
                    while (isActive) {
                        delay(1_000)
                        command("poll")
                    }
                }
                android.util.Log.i(TAG, "SDK ready")
            }
            "not_ready" -> fail("device_offline")
            "error" -> fail(event.optString("code"))
            "inactive" -> update(state.value.copy(paused = true))
            "state" -> {
                update(state.value.copy(paused = event.optBoolean("paused", true),
                    position = event.optLong("position"), duration = event.optLong("duration"),
                    title = event.optString("title"), artist = event.optString("artist"),
                    uri = event.optString("uri"), ended = event.optBoolean("ended")))
            }
        }
    }
    private fun fail(code: String) {
        dev.lelonio.square.data.RemoteConnect.unregisterSdkDevice(registeredDeviceId)
        registeredDeviceId = null
        timeout?.cancel()
        pollJob?.cancel()
        playJob?.cancel()
        update(state.value.copy(stage = "error", error = code, deviceId = null, paused = true, busy = false))
        command("pause")
        // No tokens, URLs or server response bodies enter the logs.
        android.util.Log.w(TAG, "SDK error: $code")
    }
    fun command(name: String, position: Long = 0) {
        webView?.evaluateJavascript("window.command(${JSONObject.quote(name)},$position)", null)
    }
    fun resume() {
        if (state.value.deviceId == null) return
        command("resume")
    }
    fun seek(position: Long) { command("seek", position.coerceIn(0, state.value.duration)) }

    fun play(input: String, following: List<String> = emptyList(), positionMs: Long = 0) {
        val uri = trackUri(input)
        if (uri == null) { update(state.value.copy(error = "invalid_track")); return }
        val device = state.value.deviceId ?: return
        command("activate")
        playJob?.cancel()
        val generation = revision
        val requestVersion = ++playVersion
        update(state.value.copy(busy = true, error = null, ended = false))
        playJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val uris = listOf(uri) + following.mapNotNull(::trackUri).take(99)
                    val body = JSONObject().put("uris", org.json.JSONArray(uris))
                        .put("position_ms", positionMs.coerceAtLeast(0)).toString()
                    val url = "https://api.spotify.com/v1/me/player/play".toHttpUrl()
                        .newBuilder().addQueryParameter("device_id", device).build()
                    suspend fun request(token: String): Int {
                        val call = http.newCall(Request.Builder().url(url)
                            .header("Authorization", "Bearer $token")
                            .put(body.toRequestBody("application/json".toMediaType())).build())
                        return suspendCancellableCoroutine { continuation ->
                            continuation.invokeOnCancellation { call.cancel() }
                            call.enqueue(object : Callback {
                                override fun onFailure(call: Call, error: java.io.IOException) {
                                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                                }
                                override fun onResponse(call: Call, response: Response) {
                                    response.use {
                                        if (continuation.isActive) continuation.resumeWith(Result.success(it.code))
                                    }
                                }
                            })
                        }
                    }
                    var code = request(account.tokens.validAccessToken())
                    if (code == 401) code = request(account.tokens.forceRefresh())
                    if (code !in 200..299) error("http_$code")
                }
                if (generation == revision && requestVersion == playVersion) update(state.value.copy(busy = false))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == revision && requestVersion == playVersion) {
                    val code = e.message?.takeIf { it.matches(Regex("http_[0-9]{3}")) } ?: "playback_error"
                    update(state.value.copy(busy = false, error = code))
                }
            }
        }
    }
    fun stopPlayback() {
        stopWebView()
        update(WebSdkState())
    }
    private fun stopWebView() {
        dev.lelonio.square.data.RemoteConnect.unregisterSdkDevice(registeredDeviceId)
        registeredDeviceId = null
        revision++
        playVersion++
        timeout?.cancel()
        pollJob?.cancel()
        playJob?.cancel()
        webView?.let { view ->
            view.evaluateJavascript("window.command('disconnect')", null)
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.removeJavascriptInterface("SquareSdk")
            view.stopLoading()
            view.destroy()
        }
        webView = null
    }
    fun release() {
        stopWebView()
        scope.cancel()
    }
    companion object {
        private const val ORIGIN = "https://square-sdk.invalid/"
        private const val TAG = "SquareWebSdk"
        internal fun trackUri(input: String): String? {
            val value = input.trim()
            val id = when {
                value.startsWith("spotify:track:") -> value.removePrefix("spotify:track:")
                else -> android.net.Uri.parse(value).takeIf {
                    it.scheme == "https" && it.host == "open.spotify.com"
                }?.pathSegments?.let { parts ->
                    val offset = if (parts.firstOrNull()?.startsWith("intl-") == true) 1 else 0
                    if (parts.getOrNull(offset) == "track" && parts.size == offset + 2) parts.last() else null
                }
            }
            return id?.takeIf { it.matches(Regex("[a-zA-Z0-9]{22}")) }?.let { "spotify:track:$it" }
        }
    }
}
