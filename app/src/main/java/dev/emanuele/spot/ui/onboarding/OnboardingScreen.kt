package dev.emanuele.spot.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import dev.emanuele.spot.R
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
                        1 -> LogIn(loggedIn = loggedIn, connecting = state is MainViewModel.UiState.Loading, backdrop = backdrop, onLogIn = onLogIn)
                        2 -> WhyOwnApp()
                        3 -> Dashboard(redirectUri = webApi.redirectUri)
                        4 -> ClientId(webApi, backdrop, onClientIdChange, onConnectWebApi)
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

private const val STEP_COUNT = 6

@Composable
private fun Welcome() {
    Spacer(Modifier.height(20.dp))
    // The launcher art itself, not a drawing of it: this is the first screen
    // after tapping the icon, and showing the same thing they just tapped is
    // what says the two are one app.
    Image(
        painter = painterResource(R.mipmap.ic_launcher_foreground),
        contentDescription = null,
        modifier = Modifier
            .size(104.dp)
            .clip(RoundedCornerShape(26.dp)),
    )
    Spacer(Modifier.height(20.dp))
    SquareWordmark(Modifier.height(34.dp))
    Body("Benvenuto.")
    Body(
        "Square riproduce la tua libreria Spotify con un motore audio proprio: " +
            "stream diretto, time-stretch, riverbero, canvas e testi sincronizzati.",
    )
    Note("Serve un account Spotify Premium.")
    Body(
        "Non è una preferenza nostra: il protocollo di riproduzione rifiuta gli " +
            "account free, e con uno di quelli l'accesso riesce ma nessun brano parte.",
    )
    Body(
        "La configurazione è in due parti — l'accesso al tuo account e una " +
            "applicazione Spotify registrata da te — e questo tutorial le percorre " +
            "entrambe. Cinque minuti, una volta sola.",
    )
    Note("Square non è un'app ufficiale e non è affiliata a Spotify.")
}

@Composable
private fun LogIn(
    loggedIn: Boolean,
    connecting: Boolean,
    backdrop: Backdrop,
    onLogIn: () -> Unit,
) {
    StepTitle("1. Accedi al tuo account")
    Body(
        "Si apre il browser sulla pagina di accesso di Spotify. Autorizzi e torni " +
            "qui da solo.",
    )
    Note(
        "La password la inserisci nella pagina di Spotify, dentro il browser. " +
            "Square non la vede e non la conserva: riceve solo il token che " +
            "Spotify gli consegna dopo.",
    )
    Body(
        "Questo accesso serve alla riproduzione. Da qui arrivano le tue playlist, " +
            "i brani e il controllo Connect.",
    )

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
private fun WhyOwnApp() {
    StepTitle("2. Perché serve una tua applicazione")
    Body(
        "La riproduzione si autentica come librespot, il client open source su cui " +
            "Square è costruito. Quel client id è condiviso da ogni applicazione " +
            "basata su librespot al mondo, e la sua quota di richieste alle API web " +
            "di Spotify è esaurita da tempo.",
    )
    Body(
        "Le API web servono per: ricerca, artisti che ascolti, elenco dei " +
            "dispositivi Connect, aggiunta di brani a una playlist e caricamento " +
            "veloce delle playlist lunghe.",
    )
    Body(
        "Registrando un'applicazione tua, quella quota è solo tua e nessun altro " +
            "la consuma. È gratis, non richiede una carta e si fa dal dashboard per " +
            "sviluppatori con lo stesso account Spotify di prima.",
    )
    Note(
        "Senza questo passo l'app funziona ma a metà: la riproduzione va, la " +
            "ricerca no.",
    )
}

@Composable
private fun Dashboard(redirectUri: String) {
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current

    StepTitle("3. Crea l'applicazione")

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

    Numbered(1, "Accedi con lo stesso account Spotify Premium che hai usato prima.")
    Numbered(2, "In alto a destra premi «Create app».")
    Numbered(
        3,
        "App name: quello che vuoi, per esempio Square. App description: una " +
            "riga qualsiasi. Sono etichette, nessuno le controlla.",
    )
    Numbered(
        4,
        "Website: lascia vuoto.",
    )
    Numbered(
        5,
        "Redirect URI: incolla esattamente la riga qui sotto e premi «Add». " +
            "Deve comparire nell'elenco sotto al campo: se resta solo scritta nel " +
            "campo non viene salvata, ed è l'errore che poi fa fallire il " +
            "collegamento con INVALID_CLIENT: Invalid redirect URI.",
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
    Body(
        "È un indirizzo locale: al ritorno dal browser risponde Square stessa, " +
            "sul telefono. Non esce nulla dal dispositivo.",
    )

    Numbered(
        6,
        "Alla voce «Which API/SDKs are you planning to use?» spunta Web API. " +
            "Le altre non servono.",
    )
    Numbered(7, "Accetta i termini per sviluppatori e premi «Save».")
    Numbered(
        8,
        "Si apre la pagina dell'app: premi «Settings». Sotto «Basic Information» " +
            "trovi il Client ID. Copialo.",
    )
    Note(
        "Il Client secret non serve e non va inserito da nessuna parte in " +
            "Square: l'accesso usa PKCE, che è fatto apposta per non tenere un " +
            "segreto dentro un'app installata. Se lo hai già mostrato in giro, " +
            "rigeneralo dal dashboard.",
    )
}

@Composable
private fun ClientId(
    webApi: MainViewModel.WebApiState,
    backdrop: Backdrop,
    onClientIdChange: (String) -> Unit,
    onConnectWebApi: () -> Unit,
) {
    StepTitle("4. Collega l'applicazione")
    if (webApi.connected) {
        Body("L'applicazione è collegata: ricerca, feed e dispositivi funzionano.")
        Spacer(Modifier.height(16.dp))
        DoneRow("Applicazione collegata")
    } else {
        Body(
            "Incolla qui il Client ID copiato dal dashboard e premi Collega. Si " +
                "riapre il browser una seconda volta: è la stessa autorizzazione di " +
                "prima ma verso la tua applicazione, perché un token vale solo per " +
                "l'app a cui è stato rilasciato.",
        )
        // The same form the settings screen shows, so what is learned here is
        // where it stays.
        Box(Modifier.padding(top = 8.dp, start = 0.dp)) {
            WebApiSetupInline(webApi, backdrop, onClientIdChange, onConnectWebApi)
        }
        Note(
            "Se il browser dice INVALID_CLIENT, il redirect URI nel dashboard non " +
                "corrisponde: torna indietro di un passo e ricontrolla che sia " +
                "nell'elenco, senza spazi e senza barra finale.",
        )
    }
}

@Composable
private fun Done() {
    StepTitle("Tutto pronto")
    Body(
        "La home mostra le tue playlist e cosa hai ascoltato di recente, la lente " +
            "cerca in tutto il catalogo, e toccando un brano parte la riproduzione.",
    )
    Body(
        "Nel player: scorri per cambiare brano, apri testi, coda ed effetti dalla " +
            "barra sotto la copertina, e il più aggiunge il brano a una playlist.",
    )
    Note("Puoi rivedere questo tutorial da Impostazioni, dall'avatar in alto nella home.")
}

/* ---- pieces ---- */

/**
 * The name, drawn rather than typeset.
 *
 * The icon is a square wave: one stroke width, right angles only, no curve
 * anywhere. No font shipped with Android says that — a bold sans still has
 * round bowls on S and Q and a diagonal on R — and pulling in a display face
 * for six letters costs more than the six letters do.
 *
 * So the letterforms are the same primitive as the icon: polylines on a 6×10
 * grid, orthogonal segments, square caps and joins. The Q's tail drops below
 * the baseline, which is the one thing that keeps it from reading as an O.
 */
@Composable
private fun SquareWordmark(modifier: Modifier = Modifier) {
    val ink = Ink
    Canvas(modifier.fillMaxWidth()) {
        // The grid: glyphs are 6 wide on a 9.5-unit advance, 10 tall with 2 more
        // for the descender. The stroke stays under 1.2 units and the advance
        // well over the glyph width, or the counters close up and the whole
        // word turns into one dark block.
        val unit = size.height / 12f
        val stroke = unit * 1.15f
        val path = Path()
        GLYPHS.forEachIndexed { index, lines ->
            val originX = index * 9.5f * unit + stroke / 2f
            lines.forEach { points ->
                points.forEachIndexed { at, (gx, gy) ->
                    val x = originX + gx * unit
                    val y = gy * unit + stroke / 2f
                    if (at == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
            }
        }
        drawPath(
            path,
            color = ink,
            style = Stroke(
                width = stroke,
                cap = StrokeCap.Square,
                join = StrokeJoin.Miter,
            ),
        )
    }
}

/** S Q U A R E, as polylines on the grid described in [SquareWordmark]. */
private val GLYPHS: List<List<List<Pair<Float, Float>>>> = listOf(
    // S
    listOf(listOf(6f to 0f, 0f to 0f, 0f to 5f, 6f to 5f, 6f to 10f, 0f to 10f)),
    // Q: a closed box with the tail dropped out of the bottom right.
    listOf(
        listOf(0f to 0f, 6f to 0f, 6f to 10f, 0f to 10f, 0f to 0f),
        listOf(4f to 8f, 4f to 12f),
    ),
    // U
    listOf(listOf(0f to 0f, 0f to 10f, 6f to 10f, 6f to 0f)),
    // A: the apex is a corner, not a point.
    listOf(
        listOf(0f to 10f, 0f to 0f, 6f to 0f, 6f to 10f),
        listOf(0f to 6f, 6f to 6f),
    ),
    // R: the leg comes straight down instead of splaying.
    listOf(
        listOf(0f to 10f, 0f to 0f, 6f to 0f, 6f to 5f, 0f to 5f),
        listOf(3f to 5f, 3f to 10f),
    ),
    // E
    listOf(
        listOf(6f to 0f, 0f to 0f, 0f to 10f, 6f to 10f),
        listOf(0f to 5f, 4f to 5f),
    ),
)

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
            modifier = Modifier.size(20.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

private const val DASHBOARD_URL = "https://developer.spotify.com/dashboard"
