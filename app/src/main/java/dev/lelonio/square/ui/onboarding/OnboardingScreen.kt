package dev.lelonio.square.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import dev.lelonio.square.data.AppLanguages
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.ArrowUpRight
import com.adamglin.phosphoricons.regular.Check
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.R
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.components.AppIcon
import dev.lelonio.square.ui.components.SquareWordmark
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.settings.WebApiSetupInline
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
 * client id pasted back — steps nobody would guess, and each of which fails
 * silently and differently when got wrong.
 *
 * So it is a tutorial rather than a form: one instruction per screen, in the
 * order the work has to be done, with the exact strings to type made copyable
 * instead of described.
 *
 * Shown once, over everything, on the first run. It is dismissable at any point
 * — the app still works, badly, without a Web API application — and remains
 * reachable from the settings afterwards.
 */
@Composable
fun OnboardingScreen(
    state: MainViewModel.UiState,
    webApi: MainViewModel.WebApiState,
    backdrop: Backdrop,
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

    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        Modifier
            .fillMaxSize()
            // Opaque, unlike every other surface in this app. The tutorial is
            // the only thing on screen that is not about listening, and glass
            // over a home page the user has not configured yet would be showing
            // them the thing the tutorial exists to explain.
            .background(Color(0xFF101012))
            // Nothing behind this is meant to be reachable while it is up.
            .clickable(interactionSource = null, indication = null) {},
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = statusBar, bottom = navBar),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step > 0) {
                    LiquidButton(onClick = { step-- }, backdrop = backdrop) {
                        Icon(PhosphorIcons.Regular.ArrowLeft, contentDescription = stringResource(R.string.back))
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onFinish) {
                    Text(stringResource(if (step == last) R.string.close else R.string.skip), color = InkDim)
                }
            }

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
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { current ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 26.dp),
                ) {
                    when (current) {
                        0 -> Welcome(language, onLanguage)
                        1 -> LogIn(
                            loggedIn = loggedIn,
                            connecting = state is MainViewModel.UiState.Loading,
                            backdrop = backdrop,
                            onLogIn = onLogIn,
                        )
                        2 -> Dashboard(redirectUri = webApi.redirectUri)
                        3 -> ClientId(webApi, backdrop, onClientIdChange, onConnectWebApi)
                        else -> Done()
                    }
                    Spacer(Modifier.height(28.dp))
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 26.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(STEP_COUNT) { index ->
                        Box(
                            Modifier
                                .size(if (index == step) 9.dp else 7.dp)
                                .clip(CircleShape)
                                .background(
                                    if (index == step) Ink else Ink.copy(alpha = 0.28f),
                                ),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                LiquidButton(
                    onClick = { if (step == last) onFinish() else step++ },
                    backdrop = backdrop,
                    tint = MaterialTheme.colorScheme.primary,
                    contentHeight = 52.dp,
                    contentPadding = 26.dp,
                ) {
                    Text(
                        stringResource(if (step == last) R.string.onboarding_start else R.string.next_step),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

private const val STEP_COUNT = 5

@Composable
private fun Welcome(language: String, onLanguage: (String) -> Unit) {
    Spacer(Modifier.height(24.dp))
    AppIcon(104.dp)
    Spacer(Modifier.height(22.dp))
    SquareWordmark(height = 34.dp)
    Body(stringResource(R.string.onboarding_welcome_1))
    Note(stringResource(R.string.onboarding_welcome_2))
    Body(stringResource(R.string.onboarding_welcome_3))

    // First screen, before a word of the setup: someone who cannot read this
    // page is exactly the person who needs the picker, and asking them to
    // finish the tutorial first to find it would be the wrong way round.
    LanguageChips(language, onLanguage)
}

@Composable
private fun LogIn(
    loggedIn: Boolean,
    connecting: Boolean,
    backdrop: Backdrop,
    onLogIn: () -> Unit,
) {
    StepTitle(stringResource(R.string.onboarding_login_title))
    Body(stringResource(R.string.onboarding_login_1))
    Note(stringResource(R.string.onboarding_login_2))

    Spacer(Modifier.height(20.dp))
    if (loggedIn) {
        DoneRow(stringResource(R.string.onboarding_login_done))
    } else {
        LiquidButton(
            onClick = { if (!connecting) onLogIn() },
            backdrop = backdrop,
            tint = MaterialTheme.colorScheme.primary,
            contentHeight = 52.dp,
        ) {
            if (connecting) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.log_in_with_spotify), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun Dashboard(redirectUri: String) {
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current

    StepTitle(stringResource(R.string.onboarding_key_title))
    Body(stringResource(R.string.onboarding_key_intro))

    Row(
        Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Ink.copy(alpha = 0.08f))
            .clickable { uriHandler.openUri(DASHBOARD_URL) }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.onboarding_open_dashboard), style = MaterialTheme.typography.titleMedium)
            Text(
                DASHBOARD_URL.removePrefix("https://"),
                style = MaterialTheme.typography.bodySmall,
                color = InkDim,
            )
        }
        Icon(
            PhosphorIcons.Regular.ArrowUpRight,
            contentDescription = null,
            tint = InkDim,
            modifier = Modifier.size(18.dp),
        )
    }

    Numbered(1, stringResource(R.string.onboarding_step_login))
    Numbered(2, stringResource(R.string.onboarding_step_create))
    Numbered(3, stringResource(R.string.onboarding_step_names))
    Numbered(4, stringResource(R.string.onboarding_step_redirect))

    Row(
        Modifier
            .padding(top = 10.dp, start = 34.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Ink.copy(alpha = 0.08f))
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            redirectUri,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { clipboard.setText(AnnotatedString(redirectUri)) }) {
            Text(stringResource(R.string.copy))
        }
    }

    Numbered(5, stringResource(R.string.onboarding_step_api))
    Numbered(6, stringResource(R.string.onboarding_step_terms))
    Numbered(7, stringResource(R.string.onboarding_step_client_id))
    Note(stringResource(R.string.onboarding_step_secret))
}

@Composable
private fun ClientId(
    webApi: MainViewModel.WebApiState,
    backdrop: Backdrop,
    onClientIdChange: (String) -> Unit,
    onConnectWebApi: () -> Unit,
) {
    StepTitle(stringResource(R.string.onboarding_paste_title))
    if (webApi.connected) {
        Body(stringResource(R.string.onboarding_paste_done))
        Spacer(Modifier.height(16.dp))
        DoneRow(stringResource(R.string.onboarding_key_linked))
    } else {
        Body(stringResource(R.string.onboarding_paste_hint))
        // The same form the settings screen shows, so what is learned here is
        // where it stays.
        Box(Modifier.padding(top = 8.dp, start = 0.dp)) {
            WebApiSetupInline(webApi, backdrop, onClientIdChange, onConnectWebApi)
        }
        Note(stringResource(R.string.onboarding_paste_error))
    }
}

@Composable
private fun Done() {
    Spacer(Modifier.height(16.dp))
    AppIcon(72.dp)
    StepTitle(stringResource(R.string.onboarding_done_title))
    Body(stringResource(R.string.onboarding_done_1))
    Body(stringResource(R.string.onboarding_done_2))
    Note(stringResource(R.string.onboarding_done_3))
}

/**
 * The languages, as a row of pills.
 *
 * Chips rather than the list the settings screen uses: this is one line of a
 * page that is mostly prose, and a seven-row list here would read as the first
 * task rather than as a choice already made for most people.
 */
@Composable
private fun LanguageChips(language: String, onLanguage: (String) -> Unit) {
    Row(
        Modifier
            .padding(top = 22.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppLanguages.forEach { (tag, name) ->
            val selected = tag == language
            Text(
                name.ifEmpty { stringResource(R.string.system_language) },
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) Ink else InkDim,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Ink.copy(alpha = if (selected) 0.18f else 0.07f))
                    .clickable { onLanguage(tag) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/* ---- pieces ---- */

@Composable
private fun StepTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.displayLarge,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Body(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = InkDim,
        modifier = Modifier.padding(top = 12.dp),
    )
}

/** A step of the dashboard walkthrough: the number stays out of the text column. */
@Composable
private fun Numbered(index: Int, text: String) {
    Row(Modifier.padding(top = 14.dp)) {
        Text(
            "$index",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Ink.copy(alpha = 0.10f))
                .padding(top = 1.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = InkDim,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/** Something that will cost the user time if skipped, rather than mere prose. */
@Composable
private fun Note(text: String) {
    Row(
        Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Ink)
    }
}

@Composable
private fun DoneRow(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            PhosphorIcons.Regular.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 10.dp).size(20.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private const val DASHBOARD_URL = "https://developer.spotify.com/dashboard"
