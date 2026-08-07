package dev.lelonio.square.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lelonio.square.update.Updater
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.ArrowUpRight
import com.adamglin.phosphoricons.regular.CaretDown
import com.adamglin.phosphoricons.regular.CaretUp
import com.kyant.backdrop.Backdrop
import dev.lelonio.square.BuildConfig
import dev.lelonio.square.R
import dev.lelonio.square.data.AppLanguages
import dev.lelonio.square.backend.BackendId
import dev.lelonio.square.data.Quality
import dev.lelonio.square.ui.MainViewModel
import dev.lelonio.square.ui.components.Artwork
import dev.lelonio.square.ui.glass.LiquidButton
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import dev.lelonio.square.ui.theme.softShadow

/**
 * Everything that is configuration rather than listening.
 *
 * Reached from the avatar in the home header, not from the tab bar. The bar has
 * three places you go to play something and settings is not one of them — it is
 * somewhere you visit twice a year, and giving it a permanent quarter of the
 * navigation would say otherwise.
 *
 * Deliberately short. Most of what a music app usually puts here is decided by
 * the account or by the engine and is not ours to offer: bitrate comes from
 * Premium, the device name from the phone. What is left is the account itself,
 * the Web API application the user has to register for search, and the licences
 * this app owes attribution to.
 */
@Composable
fun SettingsScreen(
    state: MainViewModel.UiState,
    webApi: MainViewModel.WebApiState,
    contentPadding: PaddingValues,
    backdrop: Backdrop,
    deviceName: String,
    onClientIdChange: (String) -> Unit,
    onConnectWebApi: () -> Unit,
    onDisconnectWebApi: () -> Unit,
    onLogOut: () -> Unit,
    onShowTutorial: () -> Unit,
    /** The chosen language tag, empty for the phone's own. */
    language: String,
    onLanguage: (String) -> Unit,
    onBack: () -> Unit,
    /** Opens the Google sign-in web view; see YouTubeLoginScreen. */
    onYouTubeSignIn: () -> Unit = {},
) {
    val ready = state as? MainViewModel.UiState.Ready
    val context = LocalContext.current
    val app = remember(context) {
        context.applicationContext as dev.lelonio.square.SquareApplication
    }
    /**
     * Spotify's own settings are hidden while another source is playing.
     *
     * Not disabled — removed. The account, the registered Web API application,
     * the Connect device and the tutorial that explains all three describe a
     * service the app is not currently using, and leaving them on screen makes
     * the two sources look like one confused one.
     */
    val spotifyActive by app.preferences.backend.collectAsStateWithLifecycle()
    val showSpotify = spotifyActive == BackendId.SPOTIFY

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        item("top") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 24.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LiquidButton(onClick = onBack, backdrop = backdrop) {
                    Icon(PhosphorIcons.Regular.ArrowLeft, contentDescription = stringResource(R.string.back))
                }
                Text(
                    stringResource(R.string.settings),
                    style = MaterialTheme.typography.displayLarge,
                    modifier = Modifier.padding(start = 14.dp),
                )
            }
        }

        if (showSpotify) item("account") {
            Section(stringResource(R.string.account)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(
                        url = ready?.avatarUrl,
                        title = ready?.displayName.orEmpty(),
                        modifier = Modifier
                            .size(54.dp)
                            .softShadow(CircleShape, elevation = 10.dp),
                        corner = 27.dp,
                        decodeSize = 54.dp,
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = 14.dp),
                    ) {
                        Text(
                            ready?.displayName ?: stringResource(R.string.not_connected),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (ready != null) {
                                stringResource(R.string.playlist_count, ready.playlists.size)
                            } else {
                                stringResource(R.string.log_in_to_resume)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = InkDim,
                        )
                    }
                }

                RowDivider()
                InfoRow(stringResource(R.string.connect_device), deviceName)
            }
        }

        if (showSpotify) item("webapi") {
            Section(stringResource(R.string.web_api)) {
                if (webApi.connected) {
                    InfoRow(stringResource(R.string.application), stringResource(R.string.connected))
                    RowDivider()
                    InfoRow(
                        stringResource(R.string.client_id),
                        webApi.clientId.take(8) + if (webApi.clientId.length > 8) "…" else "",
                    )
                    RowDivider()
                    ActionRow(stringResource(R.string.disconnect), destructive = true, onClick = onDisconnectWebApi)
                } else {
                    // The full explanation, because with no application
                    // connected search does not work at all and the reason is
                    // not something anyone would guess.
                    WebApiSetup(webApi, backdrop, onClientIdChange, onConnectWebApi)
                }
            }
        }

        if (showSpotify) item("tutorial") {
            Section(stringResource(R.string.guide)) {
                ActionRow(stringResource(R.string.see_setup_again), destructive = false) {
                    onShowTutorial()
                }
            }
        }

        item("backend") {
            BackendSection()
        }

        item("youtube-account") {
            YouTubeAccountSection(onSignIn = onYouTubeSignIn)
        }

        // The bitrate is librespot's; ExoPlayer takes what YouTube serves.
        if (showSpotify) item("quality") {
            QualitySection()
        }

        item("language") {
            Section(stringResource(R.string.language)) {
                AppLanguages.forEachIndexed { index, (tag, name) ->
                    if (index > 0) RowDivider()
                    ChoiceRow(
                        label = name.ifEmpty { stringResource(R.string.system_language) },
                        selected = tag == language,
                    ) { onLanguage(tag) }
                }
            }
        }

        item("permissions") {
            Section(stringResource(R.string.permissions_asked)) {
                Text(
                    stringResource(R.string.permissions_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkDim,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                )
                SCOPES.forEach { (scope, why) ->
                    RowDivider()
                    InfoRow(scope, stringResource(why))
                }
            }
        }

        item("author") {
            Section(stringResource(R.string.developed_by)) {
                val uriHandler = LocalUriHandler.current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { uriHandler.openUri(GITHUB_URL) }
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // GitHub serves the account picture at `<user>.png`, so the
                    // avatar follows whatever it is set to rather than being a
                    // copy checked in here.
                    Artwork(
                        url = "$GITHUB_URL.png",
                        title = GITHUB_USER,
                        modifier = Modifier
                            .size(54.dp)
                            .softShadow(CircleShape, elevation = 10.dp),
                        corner = 27.dp,
                        decodeSize = 54.dp,
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = 14.dp),
                    ) {
                        Text(GITHUB_USER, style = MaterialTheme.typography.titleMedium)
                        Text(
                            GITHUB_URL.removePrefix("https://"),
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
            }
        }

        item("about") {
            Section(stringResource(R.string.about)) {
                InfoRow(stringResource(R.string.version), "${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
                RowDivider()
                UpdateRow()
                RowDivider()
                Licences()
            }
        }

        if (ready != null) {
            item("logout") {
                Section(null) {
                    ActionRow(stringResource(R.string.log_out), destructive = true, onClick = onLogOut)
                }
            }
        }
    }
}

/**
 * Which file the engine asks Spotify for.
 *
 * The three fixed steps are the ones the account is offered, and there is no
 * fourth: this client is served Ogg Vorbis at 320 kbps and below, never a
 * lossless file, so a "lossless" row would be a promise nothing can keep.
 */
/**
 * Which service the app plays from.
 *
 * The two are not equivalent and the note says so rather than letting the user
 * find out: Spotify is the account's own library, its playlists and its Connect
 * devices, while YouTube Music here is anonymous — the catalogue and search
 * work, an account's own library does not exist to read.
 *
 * Changing it restarts playback, so it is a setting rather than a switch in the
 * player.
 */
@Composable
private fun BackendSection() {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as dev.lelonio.square.SquareApplication).preferences
    }
    val chosen by store.backend.collectAsStateWithLifecycle()

    Section(stringResource(R.string.backend)) {
        BackendId.entries.forEachIndexed { index, backend ->
            if (index > 0) RowDivider()
            ChoiceRow(
                label = stringResource(
                    when (backend) {
                        BackendId.SPOTIFY -> R.string.backend_spotify
                        BackendId.YOUTUBE_MUSIC -> R.string.backend_youtube
                    },
                ),
                selected = backend == chosen,
            ) { store.setBackend(backend) }
        }
        RowDivider()
        Text(
            stringResource(R.string.backend_note),
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        )
    }
}

/**
 * The Google account YouTube Music reads a library with.
 *
 * Only shown while that backend is the active one: on Spotify it would be an
 * account for a service the app is not currently playing from.
 *
 * Signing in is optional and the section says so. Search and playback work
 * without it; what it adds is the user's own playlists.
 */
@Composable
private fun YouTubeAccountSection(onSignIn: () -> Unit) {
    val context = LocalContext.current
    val app = remember(context) {
        context.applicationContext as dev.lelonio.square.SquareApplication
    }
    val backend by app.preferences.backend.collectAsStateWithLifecycle()
    if (backend != BackendId.YOUTUBE_MUSIC) return

    val account = remember(app) { app.youtubeAccount }
    val name by account.accountName.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Section(stringResource(R.string.youtube_account)) {
        if (name == null) {
            ChoiceRow(
                label = stringResource(R.string.youtube_sign_in),
                selected = false,
                onClick = onSignIn,
            )
            RowDivider()
            Text(
                stringResource(R.string.youtube_sign_in_note),
                style = MaterialTheme.typography.bodySmall,
                color = InkDim,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            )
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    name.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
            }
            RowDivider()
            ChoiceRow(
                label = stringResource(R.string.youtube_sign_out),
                selected = false,
            ) {
                scope.launch { app.youtubeBackend.logOut() }
            }
        }
    }
}

@Composable
private fun QualitySection() {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as dev.lelonio.square.SquareApplication).quality
    }
    val chosen by store.quality.collectAsStateWithLifecycle()

    Section(stringResource(R.string.quality)) {
        Quality.entries.forEachIndexed { index, quality ->
            if (index > 0) RowDivider()
            ChoiceRow(
                label = stringResource(quality.label),
                selected = quality == chosen,
            ) { store.set(quality) }
        }
        RowDivider()
        Text(
            stringResource(R.string.quality_auto_note) + " " +
                stringResource(R.string.quality_restarts),
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        )
    }
}

/**
 * Checking for, and installing, a new version.
 *
 * A button rather than something that happens on its own: the check is a
 * request to GitHub carrying the user's address, made for the app's benefit
 * rather than theirs, and nothing here needs it badly enough to make it
 * automatic.
 */
@Composable
private fun UpdateRow() {
    val context = LocalContext.current
    val updater = remember(context) {
        (context.applicationContext as dev.lelonio.square.SquareApplication).updater
    }
    val state by updater.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val status = when (val current = state) {
        is Updater.State.Idle -> null
        is Updater.State.Checking -> stringResource(R.string.update_checking)
        is Updater.State.UpToDate -> stringResource(R.string.update_none)
        is Updater.State.Available -> stringResource(R.string.update_available, current.version)
        is Updater.State.Downloading ->
            current.progress?.let { "${(it * 100).toInt()}%" } ?: stringResource(R.string.update_downloading)
        is Updater.State.Installing -> stringResource(R.string.update_installing)
        is Updater.State.Failed ->
            if (current.reason == Updater.REASON_PERMISSION) stringResource(R.string.update_needs_permission)
            else stringResource(R.string.update_failed)
    }

    val busy = state is Updater.State.Checking ||
        state is Updater.State.Downloading ||
        state is Updater.State.Installing

    // Held across the trip to the system settings, so granting the permission
    // continues the install instead of ending in a row that has to be pressed
    // again.
    var pending by remember { mutableStateOf<Updater.State.Available?>(null) }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val update = pending ?: return@rememberLauncherForActivityResult
        pending = null
        scope.launch { if (updater.canInstall()) updater.install(update) }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy) {
                scope.launch {
                    pending = updater.checkAndInstall()
                    pending?.let { permission.launch(updater.permissionIntent()) }
                }
            }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.check_for_updates),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (status != null) {
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = InkDim,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}

/**
 * The third-party code this app ships, and under what.
 *
 * Not a nicety: Bungee is MPL-2.0 and the Backdrop components are Apache-2.0,
 * and both licences require the notice to travel with the binary. Collapsed by
 * default because it is an obligation to the authors, not a feature.
 */
@Composable
private fun Licences() {
    var open by remember { mutableStateOf(false) }

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.licences), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(
                if (open) PhosphorIcons.Regular.CaretUp else PhosphorIcons.Regular.CaretDown,
                contentDescription = null,
                tint = InkDim,
                modifier = Modifier.size(18.dp),
            )
        }
        if (open) {
            LICENCES.forEach { (what, licence) ->
                Text(
                    "$what — $licence",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkDim,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Section(title: String?, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
        if (title != null) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = InkDim,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Ink.copy(alpha = 0.07f)),
            content = { content() },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = InkDim,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ActionRow(label: String, destructive: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        color = if (destructive) MaterialTheme.colorScheme.error else Ink,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    )
}

/** A row of a list where one is picked, with a tick on the one that is. */
@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) Ink else InkDim,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                PhosphorIcons.Regular.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp)
            .height(1.dp)
            .background(Ink.copy(alpha = 0.08f)),
    )
}

/** Kept next to the request that asks for them; see MainViewModel.connectWebApi. */
private val SCOPES = listOf(
    "user-top-read" to R.string.scope_top_artists,
    "user-read-recently-played" to R.string.scope_history,
    "user-read-playback-state" to R.string.scope_devices,
    "user-modify-playback-state" to R.string.scope_transfer,
    "playlist-modify-private" to R.string.scope_private_playlists,
    "playlist-modify-public" to R.string.scope_public_playlists,
)

private val LICENCES = listOf(
    "librespot" to "MIT",
    "Bungee" to "MPL-2.0",
    "AndroidLiquidGlass" to "Apache-2.0",
    "Phosphor Icons" to "MIT",
    "Coil" to "Apache-2.0",
    "OkHttp / Retrofit" to "Apache-2.0",
)

private const val GITHUB_USER = "Lelonio"
private const val GITHUB_URL = "https://github.com/Lelonio"
