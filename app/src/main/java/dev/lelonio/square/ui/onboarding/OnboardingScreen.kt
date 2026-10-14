package dev.lelonio.square.ui.onboarding

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
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.ArrowUpRight
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
    val last = STEP_COUNT - 1

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
        bottom = {
            StepDots(STEP_COUNT, step)
            Spacer(Modifier.weight(1f))
            PrimaryButton(
                stringResource(if (step == last) R.string.onboarding_start else R.string.next_step),
                onClick = { if (step == last) onFinish() else step++ },
            )
        },
    ) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState > initialState
                val width = { w: Int -> if (forward) w / 5 else -w / 5 }
                (slideInHorizontally(tween(260), width) + fadeIn(tween(200)))
                    .togetherWith(
                        slideOutHorizontally(tween(200)) { w -> -width(w) } +
                            fadeOut(tween(140)),
                    )
            },
            label = "onboarding",
            modifier = Modifier.fillMaxSize(),
        ) { current ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PAGE_MARGIN),
            ) {
                when (current) {
                    0 -> Welcome(language, onLanguage)
                    1 -> LogIn(
                        loggedIn = loggedIn,
                        connecting = state is MainViewModel.UiState.Loading,
                        onLogIn = onLogIn,
                    )
                    2 -> Dashboard(redirectUri = webApi.redirectUri)
                    3 -> ClientId(webApi, onClientIdChange, onConnectWebApi)
                    else -> Done()
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

private const val STEP_COUNT = 5

/** The steps that are work, as the header counts them: the first and last are not. */
private const val WORK_STEPS = 3

@Composable
private fun stepOf(index: Int): String = stringResource(R.string.onboarding_step_of, index, WORK_STEPS)

@Composable
private fun Welcome(language: String, onLanguage: (String) -> Unit) {
    Spacer(Modifier.height(20.dp))
    AppGlyph(64.dp)
    Spacer(Modifier.height(22.dp))
    SquareWordmark(height = 28.dp)
    ProseText(stringResource(R.string.onboarding_welcome_1))
    Note(stringResource(R.string.onboarding_welcome_2))
    ProseText(stringResource(R.string.onboarding_welcome_3))

    // First screen, before a word of the setup: someone who cannot read this
    // page is exactly the person who needs the picker, and asking them to
    // finish the tutorial first to find it would be the wrong way round.
    LanguageChips(language, onLanguage)
}

@Composable
private fun LogIn(
    loggedIn: Boolean,
    connecting: Boolean,
    onLogIn: () -> Unit,
) {
    StepHeader(stringResource(R.string.onboarding_login_title), stepOf(1))
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

    StepHeader(stringResource(R.string.onboarding_key_title), stepOf(2))
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
    StepHeader(stringResource(R.string.onboarding_paste_title), stepOf(3))
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

@Composable
private fun Done() {
    Spacer(Modifier.height(20.dp))
    AppGlyph(56.dp)
    StepHeader(stringResource(R.string.onboarding_done_title))
    ProseText(stringResource(R.string.onboarding_done_1))
    ProseText(stringResource(R.string.onboarding_done_2))
    Note(stringResource(R.string.onboarding_done_3))
}

/**
 * The languages, as a row of the app's own chips.
 *
 * Chips rather than the list the settings screen uses: this is one line of a
 * page that is mostly prose, and a seven-row list here would read as the first
 * task rather than as a choice already made for most people.
 */
@Composable
private fun LanguageChips(language: String, onLanguage: (String) -> Unit) {
    Row(
        Modifier
            .padding(top = 24.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppLanguages.forEach { (tag, name) ->
            FilterChip(
                label = name.ifEmpty { stringResource(R.string.system_language) },
                selected = tag == language,
                onClick = { onLanguage(tag) },
            )
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
