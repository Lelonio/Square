package dev.lelonio.square.ui.onboarding

import dev.lelonio.square.ui.glass.pressable
import com.adamglin.phosphoricons.regular.CheckCircle
import com.adamglin.phosphoricons.regular.Circle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.size
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.animateContentSize
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.ArrowUpRight
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.SpotifyLogo
import com.adamglin.phosphoricons.regular.Code
import com.adamglin.phosphoricons.regular.Key
import com.adamglin.phosphoricons.regular.Waveform
import com.adamglin.phosphoricons.regular.DownloadSimple
import com.adamglin.phosphoricons.regular.Devices
import dev.lelonio.square.R
import dev.lelonio.square.data.AppLanguages
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.components.AppGlyph
import dev.lelonio.square.ui.components.FilterChip
import dev.lelonio.square.ui.components.SquareWordmark
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim

/**
 * What a new install has to be told before it can play anything.
 *
 * This exists because Square cannot be configured by tapping "sign in" once,
 * and no amount of design makes that go away: playback authenticates as
 * librespot's shared client id, whose Web API quota is spent by every
 * librespot-based client on earth, so search and everything else that reads the
 * Web API needs an application the *user* registers. That is a trip to a
 * developer dashboard, a redirect URI that must match to the character, and a
 * client id pasted back: steps nobody would guess, and each of which fails
 * silently and differently when got wrong.
 *
 * So it is a tutorial rather than a form: one instruction per screen, in the
 * order the work has to be done, with the exact strings to type made copyable
 * instead of described.
 *
 * Shown once, over everything, on the first run. It can be left at any point,
 * since the app still works, badly, without a Web API application, and it stays
 * reachable from the settings afterwards. Drawn in ink on the page's ground;
 * see SetupPieces for why.
 */
@Composable
fun OnboardingScreen(
    state: MainViewModel.UiState,
    webApi: MainViewModel.WebApiState,
    onLogIn: () -> Unit,
    onClientIdChange: (String) -> Unit,
    onConnectWebApi: () -> Unit,
    /** The chosen language tag, empty for the phone's own. */
    language: String,
    onLanguage: (String) -> Unit,
    onFinish: () -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    val loggedIn = state is MainViewModel.UiState.Ready
    // The engine page only where Soloist runs (arm64).
    val context = androidx.compose.ui.platform.LocalContext.current
    val engine = remember { dev.lelonio.square.playback.soloist.SoloistRuntime.isSupported(context) }
    val soloist = dev.lelonio.square.ui.settings.rememberSoloistSetup()
    // Soloist chosen: its three steps follow the choice, one page each.
    val pages = buildList {
        add(Page.WELCOME); add(Page.LOG_IN); add(Page.DASHBOARD); add(Page.CLIENT_ID)
        if (engine) {
            add(Page.ENGINE)
            if (soloist.chosen) { add(Page.SOLOIST_KEY); add(Page.SOLOIST_DOWNLOAD); add(Page.SOLOIST_SIGN_IN) }
        }
        add(Page.DONE)
    }
    val last = pages.lastIndex
    val current = pages[step.coerceIn(0, last)]
    // A Soloist step moves on once it is done; until then the way on waits.
    val stepDone = soloistStepDone(current, soloist)

    SetupScaffold(
        top = {
            if (step > 0) {
                RoundButton(PhosphorIcons.Regular.ArrowLeft, stringResource(R.string.back)) { step-- }
            }
            Spacer(Modifier.weight(1f))
            QuietButton(
                stringResource(if (step == last) R.string.close else R.string.skip),
                onClick = onFinish,
            )
        },
        primary = {
            PrimaryButton(
                stringResource(if (step == last) R.string.onboarding_start else R.string.next_step),
                enabled = stepDone,
                onClick = { if (step == last) onFinish() else step++ },
            )
        },
        progress = step to pages.size,
    ) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState > initialState
                val width = { w: Int -> if (forward) w / 6 else -w / 6 }
                (slideInHorizontally(tween(280), width) + fadeIn(tween(220, delayMillis = 60)))
                    .togetherWith(slideOutHorizontally(tween(200)) { w -> -width(w) } + fadeOut(tween(120)))
                    .using(androidx.compose.animation.SizeTransform(clip = false))
            },
            label = "onboarding",
            modifier = Modifier.fillMaxWidth(),
        ) { shown ->
            Column(Modifier.fillMaxWidth()) {
                when (pages.getOrElse(shown) { Page.DONE }) {
                    Page.WELCOME -> Welcome(language, onLanguage)
                    Page.LOG_IN -> LogIn(
                        loggedIn = loggedIn,
                        connecting = state is MainViewModel.UiState.Loading,
                        onLogIn = onLogIn,
                    )
                    Page.DASHBOARD -> Dashboard(redirectUri = webApi.redirectUri)
                    Page.CLIENT_ID -> ClientId(webApi, onClientIdChange, onConnectWebApi)
                    Page.ENGINE -> Engine(soloist)
                    Page.SOLOIST_KEY -> SoloistKeyStep(soloist)
                    Page.SOLOIST_DOWNLOAD -> SoloistDownloadStep(soloist)
                    Page.SOLOIST_SIGN_IN -> SoloistSignInStep(soloist, onDone = { if (step < last) step++ })
                    Page.DONE -> Done()
                }
            }
        }
    }
}

/** The guide's pages, in order; the Soloist ones only when it is chosen. */
internal enum class Page { WELCOME, LOG_IN, DASHBOARD, CLIENT_ID, ENGINE, SOLOIST_KEY, SOLOIST_DOWNLOAD, SOLOIST_SIGN_IN, DONE }

/** Whether a Soloist step is done, so the way on opens; any other page always is. */
internal fun soloistStepDone(page: Page, soloist: dev.lelonio.square.ui.settings.SoloistSetupUi): Boolean = when (page) {
    Page.SOLOIST_KEY -> soloist.savedKey.isNotEmpty()
    Page.SOLOIST_DOWNLOAD -> soloist.installed
    Page.SOLOIST_SIGN_IN -> soloist.signedIn
    else -> true
}

/** The steps that are work, as the header counts them: the first and last are not. */
private const val WORK_STEPS = 3

@Composable
private fun stepOf(index: Int): String = stringResource(R.string.onboarding_step_of, index, WORK_STEPS)

@Composable
private fun Welcome(language: String, onLanguage: (String) -> Unit) {
    AppIcon()
    Spacer(Modifier.height(24.dp))
    SquareWordmark(height = 26.dp)
    ProseText(stringResource(R.string.onboarding_welcome_1))
    // First screen, before a word of the setup: someone who cannot read this
    // page is exactly the person who needs the picker.
    LanguageChips(language, LocalSetupBackdrop.current, onLanguage)
    Note(stringResource(R.string.onboarding_welcome_2))
}

@Composable
private fun LogIn(
    loggedIn: Boolean,
    connecting: Boolean,
    onLogIn: () -> Unit,
) {
    StepHeader(stringResource(R.string.onboarding_login_title), stepOf(1), PhosphorIcons.Fill.SpotifyLogo)
    ProseText(stringResource(R.string.onboarding_login_1))
    Note(stringResource(R.string.onboarding_login_2))

    Spacer(Modifier.height(22.dp))
    if (loggedIn) {
        DoneRow(stringResource(R.string.onboarding_login_done))
    } else {
        SecondaryButton(
            stringResource(R.string.log_in_with_spotify),
            onClick = onLogIn,
            busy = connecting,
        )
    }
}

@Composable
private fun Dashboard(redirectUri: String) {
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current

    StepHeader(stringResource(R.string.onboarding_key_title), stepOf(2), PhosphorIcons.Regular.Code)
    ProseText(stringResource(R.string.onboarding_key_intro))

    CardButton(
        title = stringResource(R.string.onboarding_open_dashboard),
        detail = DASHBOARD_URL.removePrefix("https://"),
        icon = PhosphorIcons.Regular.ArrowUpRight,
        onClick = { openOrCopy(uriHandler, clipboard, context, DASHBOARD_URL) },
        modifier = Modifier.padding(top = 18.dp),
    )

    // The seven things to do there, as one list in one card rather than seven
    // paragraphs down the page: it is a checklist, and it reads as one when
    // the numbers line up and the address to copy sits in its own line.
    Column(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .setupCard()
            .padding(vertical = 4.dp),
    ) {
        ListStep(1, stringResource(R.string.onboarding_step_login))
        ListDivider()
        ListStep(2, stringResource(R.string.onboarding_step_create))
        ListDivider()
        ListStep(3, stringResource(R.string.onboarding_step_names))
        ListDivider()
        ListStep(4, stringResource(R.string.onboarding_step_redirect)) {
            Row(
                Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Ink.copy(alpha = 0.06f))
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    redirectUri,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                QuietButton(
                    stringResource(R.string.copy),
                    onClick = { clipboard.setText(AnnotatedString(redirectUri)) },
                )
            }
        }
        ListDivider()
        ListStep(5, stringResource(R.string.onboarding_step_api))
        ListDivider()
        ListStep(6, stringResource(R.string.onboarding_step_terms))
        ListDivider()
        ListStep(7, stringResource(R.string.onboarding_step_client_id))
    }
    Note(stringResource(R.string.onboarding_step_secret))
}

@Composable
private fun ClientId(
    webApi: MainViewModel.WebApiState,
    onClientIdChange: (String) -> Unit,
    onConnectWebApi: () -> Unit,
) {
    StepHeader(stringResource(R.string.onboarding_paste_title), stepOf(3), PhosphorIcons.Regular.Key)
    if (webApi.connected) {
        ProseText(stringResource(R.string.onboarding_paste_done))
        Spacer(Modifier.height(18.dp))
        DoneRow(stringResource(R.string.onboarding_key_linked))
    } else {
        ProseText(stringResource(R.string.onboarding_paste_hint))
        // The field and the button the settings show, in the guide's ink: the
        // same value and the same action, so what is linked here is linked
        // there. The settings' own form is drawn in the accent, and here the
        // accent would be whatever record is playing.
        OutlinedTextField(
            value = webApi.clientId,
            onValueChange = onClientIdChange,
            label = { Text(stringResource(R.string.client_id)) },
            singleLine = true,
            enabled = !webApi.connecting,
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp),
        )
        if (webApi.error != null) {
            Text(
                webApi.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        SecondaryButton(
            stringResource(R.string.connect),
            onClick = onConnectWebApi,
            busy = webApi.connecting,
            modifier = Modifier.padding(top = 14.dp),
        )
        Note(stringResource(R.string.onboarding_paste_error))
    }
}

/**
 * Which player plays the music: librespot, ready as it is, or Spotify's own
 * Soloist, which asks for a key, a download and one sign-in. Optional, and
 * the default needs nothing; the same steps are in the settings for later.
 */
@Composable
private fun Engine(soloist: dev.lelonio.square.ui.settings.SoloistSetupUi) {
    StepHeader(stringResource(R.string.engine), stringResource(R.string.onboarding_optional), PhosphorIcons.Regular.Waveform)
    ProseText(stringResource(R.string.onboarding_engine_intro))

    Column(
        Modifier.padding(top = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EngineCard(
            title = stringResource(R.string.engine_librespot),
            detail = stringResource(R.string.onboarding_engine_librespot),
            selected = !soloist.chosen,
            onClick = { soloist.choose(false) },
        )
        EngineCard(
            title = stringResource(R.string.engine_soloist),
            detail = stringResource(R.string.onboarding_engine_soloist),
            selected = soloist.chosen,
            onClick = { soloist.choose(true) },
        )
    }
    if (!soloist.chosen) Note(stringResource(R.string.onboarding_engine_later))
}

/** Soloist's steps, one page each, in the guide and from the settings. */
@Composable
internal fun SoloistKeyStep(soloist: dev.lelonio.square.ui.settings.SoloistSetupUi) {
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var draft by remember(soloist.savedKey) { mutableStateOf(soloist.savedKey) }

    StepHeader(stringResource(R.string.soloist_step_key), stringResource(R.string.onboarding_step_of, 1, 3), PhosphorIcons.Regular.Key)
    ProseText(stringResource(R.string.soloist_key_note))
    CardButton(
        title = stringResource(R.string.soloist_get_key),
        detail = dev.lelonio.square.ui.settings.SOLOIST_KEY_PAGE.removePrefix("https://"),
        icon = PhosphorIcons.Regular.ArrowUpRight,
        onClick = { openOrCopy(uriHandler, clipboard, context, dev.lelonio.square.ui.settings.SOLOIST_KEY_PAGE) },
        modifier = Modifier.padding(top = 18.dp),
    )
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text(stringResource(R.string.soloist_key)) },
        singleLine = true,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        shape = RoundedCornerShape(20.dp),
        colors = guideFieldColors(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp),
    )
    if (draft.trim() != soloist.savedKey && draft.isNotBlank()) {
        SecondaryButton(
            stringResource(R.string.save),
            onClick = { soloist.saveKey(draft) },
            modifier = Modifier.padding(top = 12.dp),
        )
    } else if (soloist.savedKey.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        DoneRow(stringResource(R.string.onboarding_engine_key_saved))
    }
}

@Composable
internal fun SoloistDownloadStep(soloist: dev.lelonio.square.ui.settings.SoloistSetupUi) {
    // Started on arriving here: the page is the download.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!soloist.installed && soloist.download !is dev.lelonio.square.playback.soloist.SoloistDownload.Progress.Running) {
            soloist.fetch()
        }
    }
    StepHeader(stringResource(R.string.soloist_step_download), stringResource(R.string.onboarding_step_of, 2, 3), PhosphorIcons.Regular.DownloadSimple)
    ProseText(stringResource(R.string.soloist_download_note))
    Column(
        Modifier
            .padding(top = 18.dp)
            .fillMaxWidth()
            .setupCard()
            .padding(18.dp),
    ) {
        Text(
            dev.lelonio.square.ui.settings.soloistDownloadStatus(soloist),
            style = MaterialTheme.typography.bodyMedium,
            color = if (soloist.download is dev.lelonio.square.playback.soloist.SoloistDownload.Progress.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                Prose
            },
        )
        dev.lelonio.square.ui.settings.SoloistDownloadBar(
            soloist.download,
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
        when {
            soloist.download is dev.lelonio.square.playback.soloist.SoloistDownload.Progress.Running -> Unit
            soloist.installed -> {
                Spacer(Modifier.height(14.dp))
                DoneRow(stringResource(R.string.soloist_downloaded))
            }
            else -> SecondaryButton(
                stringResource(R.string.soloist_download),
                onClick = soloist.fetch,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
internal fun SoloistSignInStep(soloist: dev.lelonio.square.ui.settings.SoloistSetupUi, onDone: () -> Unit) {
    // Signed in from the Spotify app, wherever the listener is: on by itself.
    androidx.compose.runtime.LaunchedEffect(soloist.signedIn) {
        if (soloist.signedIn) {
            kotlinx.coroutines.delay(900)
            onDone()
        }
    }
    StepHeader(stringResource(R.string.soloist_step_account), stringResource(R.string.onboarding_step_of, 3, 3), PhosphorIcons.Regular.Devices)
    ProseText(dev.lelonio.square.ui.settings.soloistAccountStatus(soloist))
    Row(
        Modifier
            .padding(top = 18.dp)
            .fillMaxWidth()
            .setupCard()
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (soloist.signedIn) {
            DoneRow(stringResource(R.string.onboarding_engine_ready))
        } else {
            androidx.compose.material3.CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = Ink,
                modifier = Modifier.size(20.dp),
            )
            Text(
                stringResource(R.string.engine_soloist),
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
    }
    soloist.error?.takeIf { !soloist.signedIn }?.let {
        Text(
            stringResource(R.string.soloist_error, it),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/** One of the two engines, as a card that is picked by tapping it. */
@Composable
private fun EngineCard(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .pressable(onClick, pressedScale = 0.98f)
            .fillMaxWidth()
            .setupCard()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = InkDim,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        androidx.compose.material3.Icon(
            if (selected) PhosphorIcons.Regular.CheckCircle else PhosphorIcons.Regular.Circle,
            contentDescription = null,
            tint = if (selected) Ink else InkDim,
            modifier = Modifier
                .padding(start = 12.dp)
                .size(24.dp),
        )
    }
}

@Composable
private fun guideFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Ink,
    unfocusedTextColor = Ink,
    focusedBorderColor = Ink,
    unfocusedBorderColor = Ink.copy(alpha = 0.22f),
    focusedLabelColor = Ink,
    unfocusedLabelColor = InkDim,
    cursorColor = Ink,
)

@Composable
private fun Done() {
    StepHeader(stringResource(R.string.onboarding_done_title), icon = PhosphorIcons.Regular.CheckCircle)
    ProseText(stringResource(R.string.onboarding_done_1))
    Note(stringResource(R.string.onboarding_done_3))
}

/**
 * The languages, as a row of the app's own chips.
 *
 * Chips rather than the list the settings screen uses: this is one line of a
 * page that is mostly prose, and a seven-row list here would read as the first
 * task rather than as a choice already made for most people.
 */
/**
 * The language, as a settings group: every one the app speaks, the chosen one
 * ticked, all in view. A row of chips scrolling off the side was a filter
 * bar, not a question asked once.
 */
@Composable
private fun LanguageChips(language: String, backdrop: dev.lelonio.square.ui.glass.backdrop.Backdrop, onLanguage: (String) -> Unit) {
    val systemName = stringResource(R.string.system_language)
    // A section header over the group, as in the settings.
    Text(
        stringResource(R.string.language).uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        color = InkDim,
        modifier = Modifier.padding(top = 28.dp, start = 16.dp, bottom = 8.dp),
    )
    Column(
        Modifier
            .fillMaxWidth()
            .setupCard(),
    ) {
        AppLanguages.forEachIndexed { i, (tag, name) ->
            if (i > 0) ListDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable({ onLanguage(tag) }, pressedScale = 0.99f)
                    .padding(horizontal = 16.dp, vertical = 17.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    name.ifEmpty { systemName },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
                    color = Ink,
                    modifier = Modifier.weight(1f),
                )
                if (tag == language) {
                    Icon(PhosphorIcons.Regular.Check, contentDescription = null, tint = Ink, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private const val DASHBOARD_URL = "https://developer.spotify.com/dashboard"

/**
 * Opens [url] in the browser, or copies it and says so when nothing will open
 * it. A phone with no browser set to answer, or one whose launcher refuses a
 * link from an app, left the button doing nothing at all, with no way to the
 * page the setup depends on.
 */
internal fun openOrCopy(
    uriHandler: androidx.compose.ui.platform.UriHandler,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    context: android.content.Context,
    url: String,
) {
    val opened = runCatching { uriHandler.openUri(url) }.isSuccess
    if (opened) return
    clipboard.setText(androidx.compose.ui.text.AnnotatedString(url))
    android.widget.Toast.makeText(
        context,
        context.getString(dev.lelonio.square.R.string.link_copied_open_browser),
        android.widget.Toast.LENGTH_LONG,
    ).show()
}
