package dev.lelonio.square.backend.youtube

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.LockSimple
import com.adamglin.phosphoricons.regular.ShieldCheck
import com.adamglin.phosphoricons.regular.X
import dev.lelonio.square.R
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
    // Remembered: a plain local was lost every time the page redrew, and the
    // progress bar redraws it many times a second.
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }
    // Every way out goes through here: the page lets go of the focus first.
    // Taken away while focused, it sent Android looking for the next thing to
    // focus in a screen still being composed, and the app stopped.
    val close: () -> Unit = {
        webViewHolder[0]?.apply {
            clearFocus()
            isFocusable = false
        }
        onDone()
    }
    var progress by remember { mutableStateOf(0) }
    var host by remember { mutableStateOf("accounts.google.com") }

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
            close()
            return
        }

        completing = true

        scope.launch {
            YouTube.cookie = cookie
            YouTube.visitorData = visitorData
            YouTube.dataSyncId = dataSyncId

            fun keep(name: String) {
                webViewHolder[0]?.apply {
                    stopLoading()
                    clearHistory()
                    // Let go of the focus before the page goes: taken away
                    // while focused, it sent Android looking for the next
                    // thing to focus in a screen still being composed, and the
                    // app stopped right as the sign-in finished.
                    clearFocus()
                    isFocusable = false
                }
                account.save(
                    cookie = cookie,
                    visitorData = visitorData,
                    dataSyncId = dataSyncId,
                    name = name,
                )
                close()
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
                    close()
                }
        }
    }

    // Google's own page, inside the app's: a header that says what it is and
    // where it is, the page on a card under it, and a word on what is kept.
    // The page has to stay Google's and stay visible as Google's — a password
    // typed into anything else is the thing never to ask for.
    val ink = androidx.compose.ui.graphics.Color.White
    androidx.compose.foundation.layout.Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(ink.copy(alpha = 0.12f))
                    .clickable {
                        webViewHolder[0]?.stopLoading()
                        close()
                    },
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Icon(
                    PhosphorIcons.Regular.X,
                    contentDescription = stringResource(R.string.close),
                    tint = ink,
                    modifier = Modifier.size(20.dp),
                )
            }
            androidx.compose.foundation.layout.Column(Modifier.padding(start = 14.dp)) {
                Text(
                    stringResource(R.string.youtube_sign_in),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = ink,
                )
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Icon(
                        PhosphorIcons.Fill.LockSimple,
                        contentDescription = null,
                        tint = ink.copy(alpha = 0.6f),
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        host,
                        style = MaterialTheme.typography.bodySmall,
                        color = ink.copy(alpha = 0.6f),
                        maxLines = 1,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }

        // How far the page has got, on a hairline under the header.
        val shown by androidx.compose.animation.core.animateFloatAsState(
            targetValue = progress / 100f,
            label = "loginProgress",
        )
        val barAlpha by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (progress in 1..99) 1f else 0f,
            label = "loginProgressAlpha",
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .padding(horizontal = 16.dp)
                .graphicsLayer { alpha = barAlpha },
        ) {
            Box(
                Modifier
                    .fillMaxWidth(shown.coerceIn(0f, 1f))
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(ink.copy(alpha = 0.85f)),
            )
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 10.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(androidx.compose.ui.graphics.Color.White),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                url?.let { android.net.Uri.parse(it).host }?.let { host = it }
                            }

                            // Anything that is not a web page, which the WebView
                            // can only show as an error. Google sends some phones
                            // on from music.youtube.com to the YouTube Music app,
                            // as an `intent://` link to the Play Store, and the
                            // sign-in ended on "Webpage not available" with the
                            // account already signed in behind it (#110). The
                            // link is left alone and the page it came from is
                            // checked instead.
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: android.webkit.WebResourceRequest,
                            ): Boolean {
                                val scheme = request.url.scheme?.lowercase()
                                if (scheme == "http" || scheme == "https") return false
                                android.util.Log.i(TAG, "not following a $scheme link")
                                finishIfSignedIn(view, view.url)
                                return true
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                finishIfSignedIn(view, url)
                            }

                            private fun finishIfSignedIn(view: WebView, url: String?) {
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
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        settings.javaScriptEnabled = true
                        webViewHolder[0] = this
                        loadUrl(SIGN_IN_URL)
                    }
                },
            )
            // The account being checked, once Google has let it in: a moment
            // with nothing to press, said rather than left to look stuck.
            androidx.compose.animation.AnimatedVisibility(
                visible = completing,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(androidx.compose.ui.graphics.Color(0xFF101012).copy(alpha = 0.88f)),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    androidx.compose.foundation.layout.Column(
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            color = ink,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(32.dp),
                        )
                        Text(
                            stringResource(R.string.youtube_signing_in),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ink,
                            modifier = Modifier.padding(top = 14.dp),
                        )
                    }
                }
            }
        }

        androidx.compose.foundation.layout.Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Icon(
                PhosphorIcons.Regular.ShieldCheck,
                contentDescription = null,
                tint = ink.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp),
            )
            Text(
                stringResource(R.string.youtube_sign_in_private),
                style = MaterialTheme.typography.bodySmall,
                color = ink.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }

    BackHandler {
        val view = webViewHolder[0]
        // Google's flow is several pages deep, so back should walk it rather
        // than abandoning a sign-in halfway through.
        if (view?.canGoBack() == true) view.goBack() else complete()
    }
}

private const val TAG = "SquareYouTubeLogin"
private const val MUSIC_URL = "https://music.youtube.com"
private const val SIGN_IN_URL =
    "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
