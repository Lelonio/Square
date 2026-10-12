package dev.lelonio.square.backend.youtube

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.metrolist.innertube.YouTube
import kotlinx.coroutines.launch

/**
 * Signing into Google, in a web view.
 *
 * Uncomfortable and unavoidable in equal measure. YouTube Music has no public
 * API and hands out no tokens, so the only session it recognises is the one its
 * own web player uses: a Google cookie, plus the `VISITOR_DATA` and
 * `DATASYNC_ID` the page keeps in `window.yt.config_`. There is no OAuth screen
 * to send the user to instead.
 *
 * The web view therefore loads Google's real sign-in page and nothing else —
 * the credentials are typed into Google's own form, and this app never sees
 * them, only the cookie that results. That is the same arrangement every
 * third-party YouTube Music client uses, and it is worth being plain that it
 * still means holding a Google session cookie on the device.
 *
 * @param onDone called once the session has been captured, or on going back.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeLoginScreen(account: YouTubeAccount, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var completing by remember { mutableStateOf(false) }
    var webView: WebView? = null

    val context = androidx.compose.ui.platform.LocalContext.current

    // [visitorData] and [dataSyncId] are read out of the page rather than out
    // of the cookie: they are not cookie values at all, they live in the
    // page's own config object, and without them a Google account with several
    // YouTube channels resolves to the wrong one. Handed in once the page has
    // answered; see onPageFinished.
    fun complete(visitorData: String = "", dataSyncId: String = "") {
        if (completing) return
        val cookie = CookieManager.getInstance().getCookie(MUSIC_URL).orEmpty()
        // Backing out before signing in is an ordinary outcome, not a failure.
        if (cookie.isBlank()) {
            onDone()
            return
        }

        completing = true

        scope.launch {
            YouTube.cookie = cookie
            YouTube.visitorData = visitorData
            YouTube.dataSyncId = dataSyncId

            fun keep(name: String) {
                webView?.apply {
                    stopLoading()
                    clearHistory()
                }
                account.save(
                    cookie = cookie,
                    visitorData = visitorData,
                    dataSyncId = dataSyncId,
                    name = name,
                )
                onDone()
            }

            // The session is only worth keeping if it can actually read the
            // account. Asking now turns a cookie that will never work into a
            // login that visibly did not happen, rather than a library that is
            // mysteriously empty later.
            YouTube.accountInfo()
                .onSuccess { info -> keep(info.name) }
                .onFailure { error ->
                    val message = error.message.orEmpty()
                    val refused = "401" in message || "403" in message
                    android.util.Log.e(
                        TAG,
                        "youtube login check failed (refused=$refused, sapisid=${"SAPISID=" in cookie}, " +
                            "visitorData=${visitorData.isNotEmpty()}, dataSyncId=${dataSyncId.isNotEmpty()}): $error",
                        error,
                    )
                    // Only a refusal says the session does not work. Anything
                    // else is the menu this asks coming back in a shape it
                    // does not read: an account with no YouTube channel has
                    // no header in it at all. Turned away for that, people
                    // signed in, were sent back signed out, and the next try
                    // found Google already signed in and did the same.
                    if (!refused && "SAPISID=" in cookie) {
                        val name = YouTube.accounts().getOrNull()?.firstOrNull()?.name
                        keep(name ?: context.getString(dev.lelonio.square.R.string.youtube_account_fallback_name))
                        return@onFailure
                    }
                    // Put back what was there, or the refused cookie went on
                    // being sent for the rest of the run.
                    account.reapply()
                    completing = false
                    android.widget.Toast.makeText(
                        context,
                        context.getString(dev.lelonio.square.R.string.youtube_sign_in_failed),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    onDone()
                }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            // Landing back on music.youtube.com with a cookie is
                            // what "signed in" looks like: Google's flow can take
                            // any number of pages — password, 2FA, account
                            // picker — and this is the one state that means it
                            // finished.
                            val signedIn = url?.contains("music.youtube.com") == true &&
                                CookieManager.getInstance().getCookie(MUSIC_URL)
                                    .orEmpty().isNotBlank()
                            if (!signedIn || completing) return
                            // The page's ids first, then the check. Read
                            // through a bridge and checked straight after,
                            // the check always went before the page had
                            // answered, with both of them empty.
                            view.evaluateJavascript(
                                "(function(){try{var c=window.yt.config_;" +
                                    "return [c.VISITOR_DATA||'',c.DATASYNC_ID||''];}catch(e){return ['',''];}})()",
                            ) { result ->
                                val ids = runCatching { org.json.JSONArray(result) }.getOrNull()
                                complete(
                                    visitorData = ids?.optString(0).orEmpty(),
                                    // Two ids separated by `||`; the first is
                                    // this account's.
                                    dataSyncId = ids?.optString(1).orEmpty().substringBefore("||"),
                                )
                            }
                        }
                    }
                    settings.javaScriptEnabled = true
                    webView = this
                    loadUrl(SIGN_IN_URL)
                }
            },
        )
    }

    BackHandler {
        val view = webView
        // Google's flow is several pages deep, so back should walk it rather
        // than abandoning a sign-in halfway through.
        if (view?.canGoBack() == true) view.goBack() else complete()
    }
}

private const val TAG = "SquareYouTubeLogin"
private const val MUSIC_URL = "https://music.youtube.com"
private const val SIGN_IN_URL =
    "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
