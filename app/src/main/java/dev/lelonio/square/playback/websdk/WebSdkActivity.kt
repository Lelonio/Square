package dev.lelonio.square.playback.websdk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lelonio.square.R
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.auth.SpotifyOAuth
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.ArrowUpRight
import dev.lelonio.square.ui.onboarding.*
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import dev.lelonio.square.ui.theme.SquareTheme
import kotlinx.coroutines.*

/** Native controls around a service-owned WebView, retained when the screen goes off. */
class WebSdkActivity : ComponentActivity() {
    private var service by mutableStateOf<WebSdkService?>(null)
    private var signedIn by mutableStateOf(false)
    private var authorizing by mutableStateOf(false)
    private var authError by mutableStateOf<String?>(null)
    private var loginJob: Job? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as WebSdkService.LocalBinder).service
            signedIn = service?.account?.tokens?.isLoggedIn == true
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext((base.applicationContext as SquareApplication).language.wrap(base))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent bars, as MainActivity has them; SquareTheme sets the icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        bindService(Intent(this, WebSdkService::class.java), connection, BIND_AUTO_CREATE)
        setContent { SquareTheme {
            val recovery = intent.getBooleanExtra("recovery", false)
            val backend = service
            val fallback = remember { kotlinx.coroutines.flow.MutableStateFlow(WebSdkState()) }
            val state by (backend?.state ?: fallback).collectAsStateWithLifecycle()
            LaunchedEffect(recovery, state.stage) {
                if (recovery && state.stage == "ready") {
                    backend?.stopPlayback()
                    WebSdkRecovery.complete(this@WebSdkActivity)
                    finish()
                }
            }
            val hint = (application as SquareApplication).webApi.clientId.value.orEmpty()
            var clientId by rememberSaveable(backend) {
                mutableStateOf(backend?.account?.clientId?.takeIf { it.isNotBlank() } ?: hint)
            }
            val connected = state.deviceId != null
            val loading = state.stage == "loading"
            // Drawn with the welcome guide's pieces: this is the same kind of
            // trip to the developer dashboard, and it should look like one.
            SetupScaffold(
                top = {
                    RoundButton(PhosphorIcons.Regular.ArrowLeft, stringResource(R.string.back)) { finish() }
                },
                bottom = {
                    // The compatibility probe is a developer's question; someone
                    // recovering playback only needs the one way on.
                    if (!recovery && !connected) QuietButton(stringResource(R.string.web_sdk_check), onClick = {
                        if (backend != null && !loading && !authorizing) start(probeOnly = true)
                    })
                    // One control: sign in first, then turn the player on.
                    if (!signedIn) PrimaryButton(
                        stringResource(R.string.web_sdk_login),
                        onClick = { login(clientId) },
                        enabled = backend != null && !authorizing && clientId.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) else PrimaryButton(
                        stringResource(if (recovery) R.string.web_sdk_enable else R.string.web_sdk_connect),
                        onClick = { start(probeOnly = false) },
                        enabled = clientId.trim() == backend?.account?.clientId &&
                            !authorizing && !loading && !connected,
                        modifier = Modifier.weight(1f),
                    )
                },
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = PAGE_MARGIN),
                ) {
                    StepHeader(stringResource(if (recovery) R.string.web_sdk_recovery_title else R.string.web_sdk_title))
                    ProseText(stringResource(if (recovery) R.string.web_sdk_recovery_explainer else R.string.web_sdk_explainer))
                    // Said before the switch is turned on, not discovered afterwards.
                    if (recovery) Note(stringResource(R.string.web_sdk_limits))
                    Account(clientId, { clientId = it }, connected)
                    Status(state)
                    // The WebView belongs to the service. Detach when this screen leaves,
                    // without pausing or destroying the player being tested in background.
                    val view = backend?.webView
                    if (view != null) key(view) {
                        AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(1.dp),
                            onRelease = { (it.parent as? android.view.ViewGroup)?.removeView(it) })
                    }
                    if (!recovery) {
                        Tester(state)
                        val version = remember { WebView.getCurrentWebViewPackage()?.versionName.orEmpty() }
                        Text("Android WebView $version", style = MaterialTheme.typography.bodySmall, color = InkDim,
                            modifier = Modifier.padding(top = 18.dp))
                    }
                    Spacer(Modifier.height(28.dp))
                }
            }
        } }
    }
    /**
     * Where the developer app comes from and its Client ID, until the account is
     * authorized with it; after that, only who is signed in and the way out.
     */
    @Composable
    private fun Account(clientId: String, onClientId: (String) -> Unit, connected: Boolean) {
        val backend = service
        if (signedIn && !authorizing) {
            Row(Modifier.padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                DoneRow(stringResource(R.string.web_sdk_authorized))
                Spacer(Modifier.weight(1f))
                if (!connected) QuietButton(stringResource(R.string.disconnect), onClick = {
                    backend?.stopPlayback()
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { backend?.account?.disconnect() }
                        signedIn = false
                    }
                })
            }
        } else {
            val uriHandler = LocalUriHandler.current
            val clipboard = LocalClipboardManager.current
            CardButton(
                title = stringResource(R.string.onboarding_open_dashboard),
                detail = DASHBOARD_URL.removePrefix("https://"),
                icon = PhosphorIcons.Regular.ArrowUpRight,
                onClick = { uriHandler.openUri(DASHBOARD_URL) },
                modifier = Modifier.padding(top = 18.dp),
            )
            Text(stringResource(R.string.web_sdk_redirect), style = MaterialTheme.typography.bodyMedium,
                color = Prose, modifier = Modifier.padding(top = 18.dp))
            Row(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Ink.copy(alpha = 0.06f))
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(SpotifyOAuth.REDIRECT_URI, style = MaterialTheme.typography.bodyMedium, color = Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                QuietButton(stringResource(R.string.copy),
                    onClick = { clipboard.setText(AnnotatedString(SpotifyOAuth.REDIRECT_URI)) })
            }
            InkField(clientId, onClientId, stringResource(R.string.client_id),
                enabled = !authorizing, modifier = Modifier.padding(top = 18.dp))
            if (authorizing) Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 2.dp, color = Ink, modifier = Modifier.size(18.dp))
                Spacer(Modifier.weight(1f))
                QuietButton(stringResource(R.string.cancel), onClick = {
                    loginJob?.cancel(); SpotifyOAuth.cancelActiveAuthorization()
                })
            }
        }
        authError?.let { ErrorText(errorMessage(it)) }
    }
    @Composable
    private fun Status(state: WebSdkState) {
        val loading = state.stage == "loading"
        val label = stringResource(when (state.stage) {
            "loading" -> R.string.web_sdk_loading
            "ready" -> R.string.web_sdk_ready
            "capable" -> R.string.web_sdk_capable
            "error" -> R.string.web_sdk_failed
            else -> R.string.web_sdk_idle
        })
        Column(Modifier.padding(top = 22.dp)) {
            when {
                state.stage == "ready" -> DoneRow(label)
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(strokeWidth = 2.dp, color = Ink, modifier = Modifier.size(18.dp))
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = Ink,
                        modifier = Modifier.padding(start = 12.dp))
                }
                // The probe's answer; a failure is said by the error under it.
                state.stage == "capable" -> Note(label)
            }
            state.error?.let { ErrorText(errorMessage(it)) }
        }
    }
    /** The developer test: one track by hand, with the transport around it. */
    @Composable
    private fun Tester(state: WebSdkState) {
        val backend = service
        var track by rememberSaveable { mutableStateOf("") }
        var seekPosition by remember { mutableStateOf<Float?>(null) }
        val connected = state.deviceId != null
        Column(
            Modifier
                .padding(top = 22.dp)
                .fillMaxWidth()
                .setupCard()
                .padding(16.dp),
        ) {
            InkField(track, { track = it }, stringResource(R.string.web_sdk_track))
            SecondaryButton(stringResource(R.string.play), onClick = {
                if (connected && !state.busy) backend?.play(track)
            }, busy = state.busy, modifier = Modifier.padding(top = 12.dp))
            if (state.title.isNotBlank()) {
                Text(state.title, style = MaterialTheme.typography.titleLarge, color = Ink,
                    modifier = Modifier.padding(top = 18.dp))
                Text(state.artist, style = MaterialTheme.typography.bodyMedium, color = InkDim)
            }
            if (state.duration > 0) {
                Slider(value = seekPosition ?: state.position.toFloat().coerceIn(0f, state.duration.toFloat()),
                    onValueChange = { seekPosition = it },
                    onValueChangeFinished = { seekPosition?.let { backend?.seek(it.toLong()) }; seekPosition = null },
                    valueRange = 0f..state.duration.toFloat(), enabled = connected,
                    colors = SliderDefaults.colors(thumbColor = Ink, activeTrackColor = Ink,
                        inactiveTrackColor = Ink.copy(alpha = 0.15f)),
                    modifier = Modifier.padding(top = 8.dp))
                Text("${time(state.position)} / ${time(state.duration)}",
                    style = MaterialTheme.typography.bodySmall, color = InkDim)
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(if (state.paused) R.string.play else R.string.pause), onClick = {
                    if (connected) { if (state.paused) backend?.resume() else backend?.command("pause") }
                }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.web_sdk_stop), onClick = { backend?.stopPlayback() },
                    modifier = Modifier.weight(1f))
            }
        }
        Note(stringResource(R.string.web_sdk_background))
    }
    /** The guide's field: in ink, since the accent here would be whatever record is playing. */
    @Composable
    private fun InkField(
        value: String,
        onValueChange: (String) -> Unit,
        label: String,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            enabled = enabled,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Ink,
                unfocusedTextColor = Ink,
                focusedBorderColor = Ink,
                unfocusedBorderColor = Ink.copy(alpha = 0.22f),
                focusedLabelColor = Ink,
                unfocusedLabelColor = InkDim,
                cursorColor = Ink,
            ),
            modifier = modifier.fillMaxWidth(),
        )
    }
    @Composable
    private fun ErrorText(text: String) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp))
    }
    private fun start(probeOnly: Boolean) {
        ContextCompat.startForegroundService(this, Intent(this, WebSdkService::class.java))
        service?.connect(probeOnly)
    }
    private fun login(clientId: String) {
        if (loginJob?.isActive == true) return
        val backend = service ?: return
        backend.stopPlayback()
        loginJob = lifecycleScope.launch {
            authorizing = true
            authError = null
            try {
                withContext(Dispatchers.IO) { backend.account.configure(clientId) }
                signedIn = backend.account.tokens.isLoggedIn
                backend.account.authorize(this@WebSdkActivity)
                signedIn = true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { authError = "login_error" }
            finally { authorizing = false; signedIn = backend.account.tokens.isLoggedIn }
        }
    }
    @Composable
    private fun errorMessage(code: String): String = stringResource(when (code) {
        "eme_unavailable", "initialization_error" -> R.string.web_sdk_unsupported
        "account_error" -> R.string.web_sdk_premium
        "authentication_error", "login_error", "http_401" -> R.string.web_sdk_auth_error
        "http_403" -> R.string.web_sdk_forbidden
        "http_429" -> R.string.web_sdk_rate_limit
        "invalid_track" -> R.string.web_sdk_invalid_track
        "audio_focus" -> R.string.web_sdk_focus
        else -> R.string.web_sdk_play_error
    })
    private fun time(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1_000 % 60)
    private companion object {
        const val DASHBOARD_URL = "https://developer.spotify.com/dashboard"
    }
    override fun onDestroy() {
        if (intent.getBooleanExtra("recovery", false)) WebSdkRecovery.closeSetup()
        unbindService(connection)
        service = null
        super.onDestroy()
    }
}
