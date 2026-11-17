package dev.lelonio.square.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lelonio.square.playback.soloist.SoloistDownload
import dev.lelonio.square.playback.soloist.SoloistEngine
import dev.lelonio.square.playback.soloist.SoloistRuntime
import kotlinx.coroutines.launch

/**
 * Where a listener is in setting Soloist up, and what they can do next: the
 * same three steps in the welcome guide and in the settings.
 *
 * Their own key from Spotify's developer dashboard, the binary from Spotify's
 * CDN, and a sign-in from the Spotify app, which sees "Square" on the network.
 * Signed in, Soloist becomes the engine by itself.
 */
internal class SoloistSetupUi(
    /** Soloist chosen (or being set up) rather than librespot. */
    val chosen: Boolean,
    val savedKey: String,
    val installed: Boolean,
    val download: SoloistDownload.Progress,
    val expiresInDays: Int?,
    val signedIn: Boolean,
    val account: String?,
    val error: String?,
    val choose: (soloist: Boolean) -> Unit,
    val saveKey: (String) -> Unit,
    val fetch: () -> Unit,
)

@Composable
internal fun rememberSoloistSetup(): SoloistSetupUi {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wanted by SoloistEngine.wanted(context).collectAsStateWithLifecycle()
    val setup by SoloistEngine.setup.collectAsStateWithLifecycle()
    val running by SoloistEngine.state.collectAsStateWithLifecycle()
    val download by SoloistDownload.progress.collectAsStateWithLifecycle()
    var savedKey by remember { mutableStateOf(SoloistRuntime.key(context)) }
    var installed by remember { mutableStateOf(SoloistRuntime.binary(context).exists()) }
    val chosen = wanted == true
    val account = setup.account ?: SoloistEngine.signedInAccount(context)

    LaunchedEffect(download) {
        if (download is SoloistDownload.Progress.Done) installed = SoloistRuntime.binary(context).exists()
    }
    // The sign-in needs Soloist up, announcing itself on the network. Once it
    // is signed in, the playback service takes it from here.
    LaunchedEffect(chosen, savedKey, installed) {
        if (chosen && savedKey.isNotEmpty() && installed) SoloistEngine.start(context)
    }

    return SoloistSetupUi(
        chosen = chosen,
        savedKey = savedKey,
        installed = installed,
        download = download,
        expiresInDays = setup.expiresInDays,
        signedIn = running.loggedIn || account != null,
        account = account,
        error = setup.error,
        // Kept at once: Soloist becomes the engine by itself when it has a
        // key, the binary and an account, wherever the listener is by then.
        choose = { soloist -> SoloistEngine.setEnabled(context, soloist) },
        saveKey = { key ->
            SoloistRuntime.setKey(context, key)
            savedKey = key.trim()
            // A key changed under a running Soloist is read at its next start.
            if (running.running) SoloistEngine.restart(context)
        },
        fetch = { scope.launch { SoloistDownload.fetch(context, force = true) } },
    )
}

internal const val SOLOIST_KEY_PAGE = "https://developer.spotify.com/dashboard/soloist"

/** Step 2 in words: what is downloaded, how long it lasts, or what went wrong. */
@Composable
internal fun soloistDownloadStatus(soloist: SoloistSetupUi): String = when (val now = soloist.download) {
    is SoloistDownload.Progress.Running -> androidx.compose.ui.res.stringResource(dev.lelonio.square.R.string.soloist_downloading)
    is SoloistDownload.Progress.Failed ->
        androidx.compose.ui.res.stringResource(dev.lelonio.square.R.string.soloist_download_failed, now.message)
    else -> when {
        !soloist.installed -> androidx.compose.ui.res.stringResource(dev.lelonio.square.R.string.soloist_download_note)
        soloist.expiresInDays != null ->
            androidx.compose.ui.res.stringResource(dev.lelonio.square.R.string.soloist_expires, soloist.expiresInDays)
        else -> androidx.compose.ui.res.stringResource(dev.lelonio.square.R.string.soloist_downloaded)
    }
}

/** Step 3 in words. */
@Composable
internal fun soloistAccountStatus(soloist: SoloistSetupUi): String = when {
    soloist.signedIn -> androidx.compose.ui.res.stringResource(
        dev.lelonio.square.R.string.soloist_account_done, soloist.account ?: "Spotify",
    )
    soloist.savedKey.isEmpty() || !soloist.installed ->
        androidx.compose.ui.res.stringResource(dev.lelonio.square.R.string.soloist_account_wait)
    else -> androidx.compose.ui.res.stringResource(dev.lelonio.square.R.string.soloist_account_pair)
}

/** The download's progress, while there is one. */
@Composable
internal fun SoloistDownloadBar(download: SoloistDownload.Progress, modifier: androidx.compose.ui.Modifier) {
    val running = download as? SoloistDownload.Progress.Running ?: return
    val fraction = running.fraction
    if (fraction != null) {
        androidx.compose.material3.LinearProgressIndicator(progress = { fraction }, modifier = modifier)
    } else {
        androidx.compose.material3.LinearProgressIndicator(modifier = modifier)
    }
}
