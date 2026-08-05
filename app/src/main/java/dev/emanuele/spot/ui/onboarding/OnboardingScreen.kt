package dev.emanuele.spot.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import com.kyant.backdrop.Backdrop
import dev.emanuele.spot.ui.MainViewModel
import dev.emanuele.spot.ui.components.AppIcon
import dev.emanuele.spot.ui.components.SquareWordmark
import dev.emanuele.spot.ui.glass.LiquidButton
import dev.emanuele.spot.ui.settings.WebApiSetupInline
import dev.emanuele.spot.ui.theme.Ink
import dev.emanuele.spot.ui.theme.InkDim

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
                        Icon(PhosphorIcons.Regular.ArrowLeft, contentDescription = "Indietro")
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onFinish) {
                    Text(if (step == last) "Chiudi" else "Salta", color = InkDim)
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
                        0 -> Welcome()
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
                        if (step == last) "Inizia ad ascoltare" else "Avanti",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

private const val STEP_COUNT = 5

@Composable
private fun Welcome() {
    Spacer(Modifier.height(24.dp))
    AppIcon(104.dp)
    Spacer(Modifier.height(22.dp))
    SquareWordmark(height = 34.dp)
    Body("La tua musica Spotify, con testi, canvas ed effetti audio.")
    Note("Serve un account Spotify Premium.")
    Body("Due passaggi e sei pronto. Ci vogliono cinque minuti, una volta sola.")
}

@Composable
private fun LogIn(
    loggedIn: Boolean,
    connecting: Boolean,
    backdrop: Backdrop,
    onLogIn: () -> Unit,
) {
    StepTitle("1. Accedi a Spotify")
    Body("Si apre il browser sulla pagina di Spotify. Autorizzi e torni qui da solo.")
    Note("La password la scrivi solo nella pagina di Spotify: Square non la vede.")

    Spacer(Modifier.height(20.dp))
    if (loggedIn) {
        DoneRow("Accesso completato")
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
                Text("Accedi con Spotify", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun Dashboard(redirectUri: String) {
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current

    StepTitle("2. Crea la tua chiave")
    Body(
        "La ricerca e i consigli passano dalle API di Spotify, che vanno usate con " +
            "una chiave personale. Si crea in un minuto, è gratis e non serve " +
            "nessuna carta.",
    )

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
            Text("Apri il dashboard", style = MaterialTheme.typography.titleMedium)
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

    Numbered(1, "Accedi con lo stesso account Spotify.")
    Numbered(2, "In alto a destra premi «Create app».")
    Numbered(
        3,
        "App name e App description: scrivi quello che vuoi, per esempio Square. " +
            "Website: lascia vuoto.",
    )
    Numbered(
        4,
        "Redirect URI: incolla la riga qui sotto e premi «Add». Controlla che " +
            "compaia nell'elenco sotto al campo, altrimenti non viene salvata.",
    )

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
            Text("Copia")
        }
    }

    Numbered(
        5,
        "Alla voce «Which API/SDKs are you planning to use?» spunta Web API.",
    )
    Numbered(6, "Accetta i termini e premi «Save».")
    Numbered(
        7,
        "Nella pagina dell'app premi «Settings»: sotto «Basic Information» trovi " +
            "il Client ID. Copialo.",
    )
    Note("Il Client secret non serve: non copiarlo e non inserirlo da nessuna parte.")
}

@Composable
private fun ClientId(
    webApi: MainViewModel.WebApiState,
    backdrop: Backdrop,
    onClientIdChange: (String) -> Unit,
    onConnectWebApi: () -> Unit,
) {
    StepTitle("3. Incolla il Client ID")
    if (webApi.connected) {
        Body("Fatto: ricerca e consigli sono attivi.")
        Spacer(Modifier.height(16.dp))
        DoneRow("Chiave collegata")
    } else {
        Body(
            "Incolla qui il Client ID e premi Collega. Il browser si apre un'ultima " +
                "volta per confermare.",
        )
        // The same form the settings screen shows, so what is learned here is
        // where it stays.
        Box(Modifier.padding(top = 8.dp, start = 0.dp)) {
            WebApiSetupInline(webApi, backdrop, onClientIdChange, onConnectWebApi)
        }
        Note(
            "Se il browser dà errore, torna al passo prima: quasi sempre il " +
                "Redirect URI non è stato aggiunto all'elenco.",
        )
    }
}

@Composable
private fun Done() {
    Spacer(Modifier.height(16.dp))
    AppIcon(72.dp)
    StepTitle("Tutto pronto")
    Body("In home ci sono le tue playlist, con la lente cerchi in tutto il catalogo.")
    Body(
        "Nel player scorri per cambiare brano e apri testi, coda ed effetti dalla " +
            "barra sotto la copertina.",
    )
    Note("Puoi rivedere questa guida da Impostazioni, toccando la tua foto in home.")
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
