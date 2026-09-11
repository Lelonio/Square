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
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
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
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.CaretUp
import dev.lelonio.square.ui.glass.backdrop.Backdrop
import dev.lelonio.square.BuildConfig
import dev.lelonio.square.R
import dev.lelonio.square.data.AppLanguages
import dev.lelonio.square.backend.BackendId
import dev.lelonio.square.data.CrossfadeSteps
import dev.lelonio.square.data.EffectQuality
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
    /** After switching channel: the library and the home belong to the new one. */
    onYouTubeChannelChange: () -> Unit = {},
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

    /**
     * Which page is open, or null for the list of them.
     *
     * One screen with a page inside it rather than a second destination: every
     * page here reads the same state and the same stores, and threading all of
     * it through a navigation graph would buy nothing but the plumbing.
     * Remembered across a rotation, since coming back to the top of the
     * settings after turning the phone is not what anyone meant to do.
     */
    var open by rememberSaveable { mutableStateOf<SettingsPage?>(null) }

    // The back gesture closes the page first and leaves the settings second,
    // which is the order the screen is read in.
    BackHandler(enabled = open != null) { open = null }

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
                LiquidButton(
                    onClick = { if (open != null) open = null else onBack() },
                    backdrop = backdrop,
                ) {
                    Icon(PhosphorIcons.Regular.ArrowLeft, contentDescription = stringResource(R.string.back))
                }
                Text(
                    stringResource(open?.title ?: R.string.settings),
                    style = MaterialTheme.typography.displayLarge,
                    modifier = Modifier.padding(start = 14.dp),
                )
            }
        }

        if (open == null) item("pages") {
            Section(null) {
                SettingsPage.entries.forEachIndexed { index, page ->
                    if (index > 0) RowDivider()
                    PageRow(stringResource(page.title), stringResource(page.summary)) {
                        open = page
                    }
                }
            }
        }

        if (open == SettingsPage.Account && showSpotify) item("account") {
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

        if (open == SettingsPage.Account && showSpotify) item("webapi") {
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

        if (open == SettingsPage.Account && showSpotify) item("tutorial") {
            Section(stringResource(R.string.guide)) {
                ActionRow(stringResource(R.string.see_setup_again), destructive = false) {
                    onShowTutorial()
                }
            }
        }

        if (open == SettingsPage.Playback) item("backend") {
            BackendSection()
        }

        if (open == SettingsPage.Account) item("youtube-account") {
            YouTubeAccountSection(
                onSignIn = onYouTubeSignIn,
                onChannelChange = onYouTubeChannelChange,
            )
        }

        // What is kept on the phone. Under Playback rather than Account: it is
        // about how the music arrives, and it belongs beside the bitrate it
        // shares its wording with.
        if (open == SettingsPage.Playback && showSpotify) item("downloads") {
            DownloadsSection(backdrop)
        }

        // The bitrate is librespot's; ExoPlayer takes what YouTube serves.
        if (open == SettingsPage.Playback && showSpotify) item("quality") {
            QualitySection()
        }

        // Crossfade: mixed by the engine on Spotify, volume-shaped on YouTube Music.
        if (open == SettingsPage.Playback) item("crossfade") {
            CrossfadeSection(backdrop)
        }

        // Autoplay: automatically append similar tracks when queue reaches the end.
        if (open == SettingsPage.Playback) item("autoplay") {
            AutoplaySection(backdrop)
        }

        // Spotify's own, served by its access point: on another source there is
        // no clip to ask for and nothing this switch could turn off.
        if (open == SettingsPage.Playback && showSpotify) item("canvas") {
            CanvasSection(backdrop)
        }

        // The effects run on our own output, so this one holds for both backends.
        if (open == SettingsPage.Playback) item("effect-quality") {
            EffectQualitySection()
        }

        // How the app looks and what that costs, so under the app rather than
        // under playback: nothing here touches a note of audio.
        if (open == SettingsPage.App) item("glass") {
            GlassSection(backdrop)
        }

        if (open == SettingsPage.App) item("language") {
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

        if (open == SettingsPage.App && android.os.Build.VERSION.SDK_INT >= 31) {
            item("links") {
                Section(stringResource(R.string.spotify_links)) {
                    Text(
                        stringResource(R.string.spotify_links_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = InkDim,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    )
                    RowDivider()
                    ActionRow(
                        stringResource(R.string.open_by_default),
                        destructive = false,
                    ) {
                        // Android's own screen, because this is Android's own
                        // decision: an app cannot claim a domain it does not
                        // own, and the listener granting it here is the whole
                        // point of the design.
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings
                                        .ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                                    android.net.Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }
                    }
                }
            }
        }

        if (open == SettingsPage.About) item("permissions") {
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

        if (open == SettingsPage.About) item("author") {
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

        if (open == SettingsPage.About) item("about") {
            Section(stringResource(R.string.about)) {
                InfoRow(stringResource(R.string.version), "${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
                RowDivider()
                UpdateRow()
                RowDivider()
                Licences()
            }
        }

        if (ready != null && open == SettingsPage.Account) {
            item("logout") {
                Section(null) {
                    ActionRow(stringResource(R.string.log_out), destructive = true, onClick = onLogOut)
                }
            }
        }
    }
}

/**
 * The pages the settings are divided into.
 *
 * Four, and no deeper: a page that holds one row is a row that was hidden, and
 * a tree that goes further than this is somewhere to lose things in.
 *
 * The order is how often they are wanted. The account is what a first launch
 * comes here for, playback is what a settled install comes back for, and the
 * rest is read once.
 */
private enum class SettingsPage(
    @StringRes val title: Int,
    /** One line under the name, so a page can be chosen without opening it. */
    @StringRes val summary: Int,
) {
    Account(R.string.account, R.string.page_account_summary),
    Playback(R.string.page_playback, R.string.page_playback_summary),
    App(R.string.page_app, R.string.page_app_summary),
    About(R.string.about, R.string.page_about_summary),
}

/** A row that opens a page. */
@Composable
private fun PageRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = InkDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            PhosphorIcons.Regular.CaretRight,
            contentDescription = null,
            tint = InkDim,
            modifier = Modifier.size(18.dp),
        )
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
private fun YouTubeAccountSection(onSignIn: () -> Unit, onChannelChange: () -> Unit) {
    val context = LocalContext.current
    val app = remember(context) {
        context.applicationContext as dev.lelonio.square.SquareApplication
    }
    val backend by app.preferences.backend.collectAsStateWithLifecycle()
    if (backend != BackendId.YOUTUBE_MUSIC) return

    val account = remember(app) { app.youtubeAccount }
    val name by account.accountName.collectAsStateWithLifecycle()
    val expired by account.expired.collectAsStateWithLifecycle()
    val pageId by account.pageId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Asked for once the account is there, and only then: it is a signed-in
    // call, and there is nothing to switch between while signed out.
    var channels by remember {
        mutableStateOf<List<com.metrolist.innertube.models.YouTubeChannel>>(emptyList())
    }
    LaunchedEffect(name) {
        channels = if (name == null) emptyList() else account.channels()
    }

    Section(stringResource(R.string.youtube_account)) {
        if (name == null) {
            ChoiceRow(
                label = stringResource(R.string.youtube_sign_in),
                selected = false,
                onClick = onSignIn,
            )
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        name.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                    // A cookie Google has stopped accepting. Said here rather
                    // than left to be guessed from a library that went empty.
                    if (expired) {
                        Text(
                            stringResource(R.string.youtube_session_expired),
                            style = MaterialTheme.typography.bodySmall,
                            color = InkDim,
                        )
                    }
                }
            }
            if (expired) {
                RowDivider()
                ChoiceRow(
                    label = stringResource(R.string.youtube_sign_in_again),
                    selected = false,
                    onClick = onSignIn,
                )
            }

            // The channels this Google account owns. One of them is the
            // personal account nobody uses and another is the channel with the
            // subscriptions on it, and until this list existed there was no
            // way to say which one the app was reading.
            if (channels.size > 1) {
                RowDivider()
                Text(
                    stringResource(R.string.youtube_channel),
                    style = MaterialTheme.typography.labelMedium,
                    color = InkDim,
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp),
                )
                channels.forEach { channel ->
                    ChoiceRow(
                        label = channel.handle?.takeIf { it.isNotBlank() }
                            ?.let { "${channel.name} · $it" }
                            ?: channel.name,
                        selected = channel.pageId == pageId,
                    ) {
                        scope.launch {
                            // Only a switch that Google accepted is worth
                            // reloading a library for.
                            if (account.useChannel(channel)) onChannelChange()
                        }
                    }
                }
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

/**
 * The looping clip, on or off for everything.
 *
 * Off is not only "do not draw it": no clip is fetched at all, so a listener who
 * turns this off stops paying for a video on every track as well as seeing one.
 */
@Composable
private fun CanvasSection(backdrop: Backdrop) {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as dev.lelonio.square.SquareApplication).preferences
    }
    val enabled by store.canvasEnabled.collectAsStateWithLifecycle()

    Section(stringResource(R.string.canvas)) {
        DownloadSwitch(
            label = stringResource(R.string.canvas_show),
            checked = enabled,
            backdrop = backdrop,
            onChange = store::setCanvasEnabled,
        )
    }
}

@Composable
private fun AutoplaySection(backdrop: Backdrop) {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as dev.lelonio.square.SquareApplication).preferences
    }
    val enabled by store.autoplayInfinite.collectAsStateWithLifecycle()

    Section(stringResource(R.string.autoplay)) {
        DownloadSwitch(
            label = stringResource(R.string.autoplay_infinite_title),
            checked = enabled,
            backdrop = backdrop,
            onChange = store::setAutoplayInfinite,
        )
    }
}

@Composable
private fun EffectQualitySection() {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as dev.lelonio.square.SquareApplication).effectQuality
    }
    val chosen by store.quality.collectAsStateWithLifecycle()

    Section(stringResource(R.string.effect_quality)) {
        EffectQuality.entries.forEachIndexed { index, quality ->
            if (index > 0) RowDivider()
            ChoiceRow(
                label = stringResource(quality.label),
                selected = quality == chosen,
            ) { store.set(quality) }
        }
    }
}

@Composable
private fun CrossfadeSection(backdrop: Backdrop) {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as dev.lelonio.square.SquareApplication).crossfade
    }
    val preferences = remember(context) {
        (context.applicationContext as dev.lelonio.square.SquareApplication).preferences
    }
    val chosen by store.seconds.collectAsStateWithLifecycle()
    val trimSilence by preferences.trimSilence.collectAsStateWithLifecycle()

    Section(stringResource(R.string.crossfade)) {
        CrossfadeSteps.forEachIndexed { index, seconds ->
            if (index > 0) RowDivider()
            ChoiceRow(
                label = if (seconds == 0) {
                    stringResource(R.string.crossfade_off)
                } else {
                    stringResource(R.string.crossfade_seconds, seconds)
                },
                selected = seconds == chosen,
            ) { store.set(seconds) }
        }
        RowDivider()
        DownloadSwitch(
            label = stringResource(R.string.trim_silence),
            checked = trimSilence,
            backdrop = backdrop,
            onChange = preferences::setTrimSilence,
        )
        RowDivider()
        Text(
            stringResource(R.string.quality_restarts),
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        )
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
        // What automatic is deciding, and from what. A setting that answers
        // "it depends" should say what it depends on.
        if (chosen == Quality.Auto) {
            RowDivider()
            val link = store.linkKbps()
            InfoRow(
                stringResource(R.string.quality_link),
                if (link > 0) {
                    stringResource(R.string.quality_link_value, link, store.automatic())
                } else {
                    stringResource(R.string.quality_link_unknown, store.automatic())
                },
            )
        }
        RowDivider()
        Text(
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

/**
 * What is kept on the phone, and the rules for keeping it.
 *
 * The numbers first, because the question a downloads screen is opened with is
 * almost always how much room this is taking.
 */
@Composable
private fun DownloadsSection(backdrop: Backdrop) {
    val context = LocalContext.current
    val app = remember(context) {
        context.applicationContext as dev.lelonio.square.SquareApplication
    }
    val store = app.downloads
    val settings = app.downloadSettings

    val files by store.files.collectAsStateWithLifecycle()
    val failures by store.failures.collectAsStateWithLifecycle()
    val quality by settings.quality.collectAsStateWithLifecycle()
    val wifiOnly by settings.wifiOnly.collectAsStateWithLifecycle()
    val likedSongs by settings.downloadLikedSongs.collectAsStateWithLifecycle()
    val offline by settings.offlineMode.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Two taps rather than a dialog, and the row says so in between: deleting a
    // library is worth a moment's thought, and a page of settings is the wrong
    // place to grow a modal.
    var confirmingClear by remember { mutableStateOf(false) }

    Section(stringResource(R.string.downloads)) {
        // The audio plus everything kept beside it. Counted rather than summed
        // from the index, because the Canvases are video and a library of them
        // is not a rounding error next to the music.
        val extrasBytes by androidx.compose.runtime.produceState(0L, files.size) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                dev.lelonio.square.download.DownloadExtras.bytes()
            }
        }
        InfoRow(
            stringResource(R.string.download_storage),
            stringResource(
                R.string.download_storage_used,
                android.text.format.Formatter.formatShortFileSize(
                    context,
                    files.values.sumOf { it.bytes } + extrasBytes,
                ),
            ),
        )
        RowDivider()
        InfoRow(stringResource(R.string.downloaded_tracks), files.size.toString())

        RowDivider()
        Text(
            stringResource(R.string.download_quality),
            style = MaterialTheme.typography.labelLarge,
            color = InkDim,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp),
        )
        dev.lelonio.square.data.DownloadQuality.entries.forEach { entry ->
            ChoiceRow(
                label = stringResource(entry.label),
                selected = entry == quality,
            ) { settings.setQuality(entry) }
        }

        RowDivider()
        DownloadSwitch(
            label = stringResource(R.string.download_wifi_only),
            checked = wifiOnly,
            backdrop = backdrop,
            onChange = settings::setWifiOnly,
        )

        RowDivider()
        DownloadSwitch(
            label = stringResource(R.string.download_liked_songs),
            checked = likedSongs,
            backdrop = backdrop,
            onChange = { enable ->
                settings.setDownloadLikedSongs(enable)
                if (enable) {
                    scope.launch {
                        val tracks = app.likedStore.likedTracks.value
                        if (tracks.isNotEmpty()) {
                            val likedTracksList = tracks.map { uri ->
                                store.trackOf(uri) ?: dev.lelonio.square.data.CatalogTrack(
                                    uri = uri,
                                    name = "",
                                    artist = "",
                                )
                            }
                            store.setOwner(dev.lelonio.square.data.DownloadStore.LIKED, likedTracksList, label = null)
                            dev.lelonio.square.download.DownloadService.start(app)
                        }
                    }
                } else {
                    scope.launch {
                        store.removeOwner(dev.lelonio.square.data.DownloadStore.LIKED)
                        store.pruneOrphans().forEach { orphanUri ->
                            runCatching { dev.lelonio.square.nativecore.NativeBridge.removeDownload(orphanUri) }
                            dev.lelonio.square.download.DownloadExtras.forget(orphanUri)
                        }
                    }
                }
            },
        )

        RowDivider()
        DownloadSwitch(
            label = stringResource(R.string.offline_mode),
            checked = offline,
            backdrop = backdrop,
            onChange = settings::setOfflineMode,
        )

        // Only when there is something to say. A row reporting zero failures is
        // a row about nothing, on a screen that is already long.
        val givenUp = failures.count {
            it.value.attempts >= dev.lelonio.square.data.DownloadStore.MAX_ATTEMPTS
        }
        if (givenUp > 0) {
            RowDivider()
            InfoRow(
                stringResource(R.string.downloads),
                stringResource(R.string.download_failed_count, givenUp),
            )
            ActionRow(stringResource(R.string.download_retry_failed), destructive = false) {
                scope.launch {
                    store.retryFailed()
                    dev.lelonio.square.download.DownloadService.start(context)
                }
            }
        }

        if (files.isNotEmpty()) {
            RowDivider()
            ActionRow(
                if (confirmingClear) {
                    stringResource(R.string.remove_all_downloads_confirm)
                } else {
                    stringResource(R.string.remove_all_downloads)
                },
                destructive = true,
            ) {
                if (confirmingClear) {
                    confirmingClear = false
                    scope.launch { store.clearAll() }
                } else {
                    confirmingClear = true
                }
            }
        }
    }
}

@Composable
private fun DownloadSwitch(
    label: String,
    checked: Boolean,
    backdrop: Backdrop,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        dev.lelonio.square.ui.glass.LiquidToggle(
            selected = { checked },
            onSelect = onChange,
            backdrop = backdrop,
            accent = MaterialTheme.colorScheme.primary,
        )
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
    "user-library-modify" to R.string.scope_library,
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
