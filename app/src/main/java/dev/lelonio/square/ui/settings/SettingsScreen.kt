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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.heightIn
import com.adamglin.phosphoricons.regular.UserCircle
import com.adamglin.phosphoricons.regular.Waveform
import com.adamglin.phosphoricons.regular.Drop
import com.adamglin.phosphoricons.regular.SlidersHorizontal
import com.adamglin.phosphoricons.regular.Info
import com.adamglin.phosphoricons.regular.DownloadSimple
import androidx.compose.ui.res.pluralStringResource
import dev.lelonio.square.ui.glass.shapes.ContinuousCapsule
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import com.adamglin.phosphoricons.regular.DeviceMobile
import com.adamglin.phosphoricons.regular.Package
import com.adamglin.phosphoricons.regular.FilmStrip
import com.adamglin.phosphoricons.regular.Repeat
import com.adamglin.phosphoricons.regular.Scissors
import com.adamglin.phosphoricons.regular.WifiHigh
import com.adamglin.phosphoricons.regular.Heart
import com.adamglin.phosphoricons.regular.AirplaneTilt
import com.adamglin.phosphoricons.regular.HardDrives
import com.adamglin.phosphoricons.regular.Key
import com.adamglin.phosphoricons.regular.ArrowClockwise
import com.adamglin.phosphoricons.regular.Image
import com.adamglin.phosphoricons.regular.Gear
import com.adamglin.phosphoricons.regular.BatteryCharging
import com.adamglin.phosphoricons.regular.Broom
import com.adamglin.phosphoricons.regular.ArrowsInLineHorizontal
import com.adamglin.phosphoricons.regular.Playlist
import androidx.compose.foundation.layout.fillMaxHeight
import com.adamglin.phosphoricons.regular.Trash
import com.adamglin.phosphoricons.regular.HandHeart
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

    // One accent for the whole screen; see LocalSettingsAccent.
    val accent = rememberSettingsAccent()

    // Registered once for the screen, not once per row.
    //
    // Both of these used to be made inside a row of the About page, and a row
    // of a lazy list is made and thrown away every time it scrolls past the
    // edge: registering and unregistering an activity launcher on every pass
    // is what froze that page when it was scrolled down and back up.
    val scope = rememberCoroutineScope()
    var pendingReport by remember { mutableStateOf<java.io.File?>(null) }
    val saveReport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val file = pendingReport
        pendingReport = null
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    } ?: error("no stream for $uri")
                }
                android.widget.Toast.makeText(context, context.getString(R.string.report_saved), android.widget.Toast.LENGTH_SHORT).show()
            }.onFailure {
                android.util.Log.w("SquareReport", "report not saved: $it")
                android.widget.Toast.makeText(context, context.getString(R.string.report_failed), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    val askToSaveReport: (java.io.File) -> Unit = { file ->
        pendingReport = file
        saveReport.launch(file.name)
    }

    val updater = remember(context) {
        (context.applicationContext as dev.lelonio.square.SquareApplication).updater
    }
    var pendingUpdate by remember { mutableStateOf<Updater.State.Available?>(null) }
    val installPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val update = pendingUpdate ?: return@rememberLauncherForActivityResult
        pendingUpdate = null
        scope.launch { if (updater.canInstall()) updater.install(update) }
    }
    val askToInstall: (Updater.State.Available) -> Unit = { update ->
        pendingUpdate = update
        installPermission.launch(updater.permissionIntent())
    }

    // Set rather than assigned from inside, because the page being drawn is a
    // value handed to the animation below and cannot be written to.
    val goTo: (SettingsPage?) -> Unit = { open = it }

    // The page's own heading, wherever the page puts it: in the scrolling list
    // for most of them, above a pinned preview for the glass.
    val heading: @Composable (SettingsPage?) -> Unit = { shown ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 24.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiquidButton(
                onClick = { if (shown != null) goTo(null) else onBack() },
                backdrop = backdrop,
            ) {
                Icon(PhosphorIcons.Regular.ArrowLeft, contentDescription = stringResource(R.string.back))
            }
            Text(
                stringResource(shown?.title ?: R.string.settings),
                style = MaterialTheme.typography.displayLarge,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
    }

    // A page arrives from the side it was opened from and leaves the other way,
    // which is the one thing that said "this is a different page" and was
    // missing: the list simply swapped its rows for other rows.
    androidx.compose.runtime.CompositionLocalProvider(LocalSettingsAccent provides accent) {
    AnimatedContent(
        targetState = open,
        transitionSpec = {
            val forward = initialState == null
            val width = { w: Int -> if (forward) w / 4 else -w / 4 }
            val back = { w: Int -> if (forward) -w / 4 else w / 4 }
            (
                slideInHorizontally(tween(PAGE_TRAVEL_MS), width) +
                    fadeIn(tween(PAGE_TRAVEL_MS))
                ) togetherWith (
                slideOutHorizontally(tween(PAGE_TRAVEL_MS), back) +
                    fadeOut(tween(PAGE_FADE_MS))
                )
        },
        label = "settingsPage",
    ) { shown ->
    if (shown == SettingsPage.Glass) {
        GlassPage(
            backdrop = backdrop,
            contentPadding = contentPadding,
            header = { heading(shown) },
        )
        return@AnimatedContent
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        item("top") { heading(shown) }

        if (shown == null) item("pages") {
            SettingsIndex(
                name = ready?.displayName,
                note = if (ready != null) {
                    stringResource(R.string.playlist_count, ready.playlists.size)
                } else {
                    stringResource(R.string.log_in_to_resume)
                },
                avatarUrl = ready?.avatarUrl,
            ) { goTo(it) }
        }

        // Who this is, before anything that can be done about it. Nothing else
        // on the page is worth as much room: the picture and the name answer
        // the question people open this page with.
        if (shown == SettingsPage.Account && showSpotify) item("account") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Artwork(
                    url = ready?.avatarUrl,
                    title = ready?.displayName.orEmpty(),
                    modifier = Modifier
                        .size(96.dp)
                        .softShadow(CircleShape, elevation = 16.dp),
                    corner = 48.dp,
                    decodeSize = 96.dp,
                )
                Text(
                    ready?.displayName ?: stringResource(R.string.not_connected),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (ready != null) {
                        stringResource(R.string.playlist_count, ready.playlists.size)
                    } else {
                        stringResource(R.string.log_in_to_resume)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkDim,
                )
            }
            Section(null) {
                InfoRow(stringResource(R.string.connect_device), deviceName, icon = PhosphorIcons.Regular.DeviceMobile)
            }
        }

        if (shown == SettingsPage.Account && showSpotify) item("webapi") {
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

        if (shown == SettingsPage.Account && showSpotify) item("tutorial") {
            Section(stringResource(R.string.guide)) {
                ActionRow(stringResource(R.string.see_setup_again), destructive = false) {
                    onShowTutorial()
                }
            }
        }

        if (shown == SettingsPage.Playback) item("backend") {
            BackendSection()
        }

        if (shown == SettingsPage.Account) item("youtube-account") {
            YouTubeAccountSection(
                onSignIn = onYouTubeSignIn,
                onChannelChange = onYouTubeChannelChange,
            )
        }

        // What is kept on the phone. Under Playback rather than Account: it is
        // about how the music arrives, and it belongs beside the bitrate it
        // shares its wording with.
        // First on the page: where the space went, before anything that can be
        // done about it.
        if (shown == SettingsPage.Downloads) item("storage-chart") {
            StorageChart()
        }

        if (shown == SettingsPage.Downloads && showSpotify) item("downloads") {
            DownloadsSection(backdrop)
        }

        // The bitrate is librespot's; ExoPlayer takes what YouTube serves.
        if (shown == SettingsPage.Playback && showSpotify) item("quality") {
            QualitySection()
        }

        // Crossfade: mixed by the engine on Spotify, volume-shaped on YouTube Music.
        // What happens when the app is swiped away, which is a playback
        // decision rather than an app one; asked for in #27.
        if (shown == SettingsPage.Playback) item("keep-playing") {
            val prefs = remember(context) {
                (context.applicationContext as dev.lelonio.square.SquareApplication).preferences
            }
            val keepPlaying by prefs.keepPlayingOnClose.collectAsStateWithLifecycle()
            Section(stringResource(R.string.page_playback)) {
                DownloadSwitch(
                    label = stringResource(R.string.keep_playing_on_close),
                    checked = keepPlaying,
                    backdrop = backdrop,
                    icon = PhosphorIcons.Regular.Playlist,
                    onChange = prefs::setKeepPlayingOnClose,
                )
                Text(
                    stringResource(R.string.keep_playing_on_close_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkDim,
                    modifier = Modifier.padding(start = ROW_TEXT_START, end = 18.dp, bottom = 14.dp),
                )
            }
        }

        if (shown == SettingsPage.Playback) item("crossfade") {
            CrossfadeSection(backdrop)
        }

        // Autoplay: automatically append similar tracks when queue reaches the end.
        if (shown == SettingsPage.Playback) item("autoplay") {
            AutoplaySection(backdrop)
        }

        // Spotify's own, served by its access point: on another source there is
        // no clip to ask for and nothing this switch could turn off.
        if (shown == SettingsPage.Playback && showSpotify) item("canvas") {
            CanvasSection(backdrop)
        }

        // The effects run on our own output, so this one holds for both backends.
        if (shown == SettingsPage.Playback) item("effect-quality") {
            EffectQualitySection()
        }

        // The cleaning up, under everything it cleans: the songs first, then
        // the copies of playlists that make a long list open at once, then the
        // app's own working files. Each says what goes and what stays.
        if (shown == SettingsPage.Downloads) item("cleanup") {
            var clearedLists by remember { mutableStateOf(false) }
            var clearedCache by remember { mutableStateOf(false) }
            Section(stringResource(R.string.storage_cleanup)) {
                ActionRow(
                    stringResource(R.string.app_clear_list_cache),
                    destructive = false,
                    icon = PhosphorIcons.Regular.Broom,
                ) {
                    scope.launch {
                        dev.lelonio.square.data.ContextCacheStore(context).clear()
                        clearedLists = true
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.app_clear_list_cache_done),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                RowDivider()
                // Everything the app keeps to work faster and nothing it was
                // asked to keep: images it has drawn, pages it has read, the
                // engine's own scratch files. The music is not in here.
                ActionRow(
                    stringResource(R.string.app_clear_cache),
                    destructive = false,
                    icon = PhosphorIcons.Regular.Trash,
                ) {
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                            }
                        }
                        clearedCache = true
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.app_clear_cache_done),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            // Read so the two flags are used; they keep the rows from being
            // pressed twice in a row with nothing to show for it.
            if (clearedLists && clearedCache) Spacer(Modifier.height(0.dp))
        }

        if (shown == SettingsPage.App) item("language") {
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

        // What the bar does, which is behaviour rather than material: the glass
        // page says what it is made of, this says how it acts.
        if (shown == SettingsPage.App) item("bar") {
            val glass = remember(context) {
                (context.applicationContext as dev.lelonio.square.SquareApplication).glass
            }
            val folds by glass.barFolds.collectAsStateWithLifecycle()
            Section(stringResource(R.string.page_app_bar)) {
                DownloadSwitch(
                    label = stringResource(R.string.bar_folds),
                    checked = folds,
                    backdrop = backdrop,
                    icon = PhosphorIcons.Regular.ArrowsInLineHorizontal,
                    onChange = glass::setBarFolds,
                )
            }
        }

        // The two settings that are Android's rather than the app's, and the
        // two people are sent to when the music stops in the background or a
        // notification never arrives.
        if (shown == SettingsPage.App) item("system") {
            Section(stringResource(R.string.page_app_system)) {
                ActionRow(
                    stringResource(R.string.app_system_settings),
                    destructive = false,
                    icon = PhosphorIcons.Regular.Gear,
                ) {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    }
                }
                RowDivider()
                ActionRow(
                    stringResource(R.string.app_battery_settings),
                    destructive = false,
                    icon = PhosphorIcons.Regular.BatteryCharging,
                ) {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                        )
                    }
                }
            }
        }

        if (shown == SettingsPage.App && android.os.Build.VERSION.SDK_INT >= 31) {
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

        if (shown == SettingsPage.About) item("author") {
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
                RowDivider()
                // Beside the name rather than under About: it is about the
                // person who makes the app, not about the build.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { uriHandler.openUri(KOFI_URL) }
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.support_development), style = MaterialTheme.typography.titleMedium)
                        Text(
                            KOFI_URL.removePrefix("https://"),
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

        // The version as a statement rather than a row: it is the one fact
        // anybody is asked for when they report something, and it was the
        // smallest line on the page.
        if (shown == SettingsPage.About) item("version") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "Square ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    BuildConfig.BUILD_TYPE,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkDim,
                )
            }
        }

        if (shown == SettingsPage.About) item("about") {
            Section(stringResource(R.string.about)) {
                UpdateRow(askToInstall)
                RowDivider()
                Licences()
                RowDivider()
                ReportRows(name = ready?.displayName, onSave = askToSaveReport)
            }
        }

        // Last, and tighter than the rest: a reference for whoever wants to
        // check what the app asks Spotify for, not something anyone does.
        if (shown == SettingsPage.About) item("permissions") {
            Section(stringResource(R.string.permissions_asked)) {
                Text(
                    stringResource(R.string.permissions_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkDim,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                )
                SCOPES.forEach { (scope, why) ->
                    RowDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            scope,
                            style = MaterialTheme.typography.bodySmall,
                            color = InkDim,
                            modifier = Modifier.weight(0.9f),
                        )
                        Text(
                            stringResource(why),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1.1f),
                        )
                    }
                }
            }
        }

        // Spotify's sign-out, so only while Spotify is the source. On YouTube
        // Music the account section above has its own, and this one signed the
        // app out of Spotify from a page about a Google account.
        if (ready != null && shown == SettingsPage.Account && showSpotify) {
            item("logout") {
                Section(null) {
                    ActionRow(stringResource(R.string.log_out), destructive = true, onClick = onLogOut)
                }
            }
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
    /** The mark on its tile; see SettingsIndex. */
    val icon: ImageVector,
) {
    Account(R.string.account, R.string.page_account_summary, PhosphorIcons.Regular.UserCircle),
    Playback(R.string.page_playback, R.string.page_playback_summary, PhosphorIcons.Regular.Waveform),
    // Its own page rather than a section of the app's: the preview has to stay
    // on screen while the numbers under it move, and a section inside a list
    // scrolls away. See GlassPage.
    Glass(R.string.glass, R.string.page_glass_summary, PhosphorIcons.Regular.Drop),
    Downloads(R.string.page_downloads, R.string.page_downloads_summary, PhosphorIcons.Regular.DownloadSimple),
    App(R.string.page_app, R.string.page_app_summary, PhosphorIcons.Regular.SlidersHorizontal),
    About(R.string.about, R.string.page_about_summary, PhosphorIcons.Regular.Info),
}

/** How long a page takes to arrive, and how quickly the last one goes. */
private const val PAGE_TRAVEL_MS = 260
private const val PAGE_FADE_MS = 140

/**
 * The way in: whose account this is, and the four places to go.
 *
 * Five identical rows was a list of words where the first thing anyone wants
 * to see is whether they are signed in and as whom. The account is a card with
 * their picture on it, and the rest are tiles two abreast — a shape you can
 * aim at, and one that says these are four places rather than four settings.
 */
@Composable
private fun SettingsIndex(
    name: String?,
    note: String,
    avatarUrl: String?,
    onOpen: (SettingsPage) -> Unit,
) {
    Column(
        Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Ink.copy(alpha = 0.07f))
                .clickable { onOpen(SettingsPage.Account) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(
                url = avatarUrl,
                title = name.orEmpty(),
                modifier = Modifier
                    .size(56.dp)
                    .softShadow(CircleShape, elevation = 10.dp),
                corner = 28.dp,
                decodeSize = 56.dp,
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 14.dp),
            ) {
                Text(
                    name ?: stringResource(R.string.not_connected),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    note,
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

        // Five places, and a sixth tile that is not a place: it leaves the app
        // for the Ko-fi page. Last, and in the same shape as the rest, because
        // it is an offer rather than a setting — asking louder than that would
        // be asking on a screen somebody opened to change something.
        val uriHandler = LocalUriHandler.current
        val pages = SettingsPage.entries.filter { it != SettingsPage.Account }
        val rows = pages.chunked(2)
        rows.forEachIndexed { index, pair ->
            val last = index == rows.lastIndex
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { page -> PageTile(page, Modifier.weight(1f)) { onOpen(page) } }
                // The support tile fills the gap a row of one would leave, and
                // makes its own row when the pages divide evenly.
                if (last && pair.size == 1) {
                    SupportTile(Modifier.weight(1f)) { uriHandler.openUri(KOFI_URL) }
                }
            }
            if (last && pair.size == 2) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SupportTile(Modifier.weight(1f)) { uriHandler.openUri(KOFI_URL) }
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        IndexFooter()
    }
}

/**
 * What Square is keeping, as a bar and a legend.
 *
 * Three answers to one question — where did the space go — and they are not
 * the same kind of thing: the music is what was asked for, the covers and
 * words beside it are what make it read as music offline, and the caches are
 * the app's own workings, which it rebuilds if they go. A number for each
 * would make them look alike; a bar says which one is the space.
 */
@Composable
private fun StorageChart() {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as dev.lelonio.square.SquareApplication }
    val files by app.downloads.files.collectAsStateWithLifecycle()

    // Measured off the disk rather than from the index: a Canvas is video, and
    // the caches have nothing to be counted from but their own directories.
    val sizes by androidx.compose.runtime.produceState(Triple(0L, 0L, 0L), files.size) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val audio = files.values.sumOf { it.bytes }
            val extras = runCatching { dev.lelonio.square.download.DownloadExtras.bytes() }.getOrDefault(0L)
            val caches = runCatching {
                context.cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            }.getOrDefault(0L)
            Triple(audio, extras, caches)
        }
    }
    val (audio, extras, caches) = sizes
    val total = (audio + extras + caches).coerceAtLeast(1L)
    val accent = settingsAccent()
    val parts = listOf(
        Triple(R.string.storage_music, audio, accent),
        Triple(R.string.storage_extras, extras, accent.copy(alpha = 0.55f)),
        Triple(R.string.storage_caches, caches, Ink.copy(alpha = 0.3f)),
    )

    Column(
        Modifier
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Ink.copy(alpha = 0.07f))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            android.text.format.Formatter.formatShortFileSize(context, audio + extras + caches),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(ContinuousCapsule()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            parts.forEach { (_, bytes, colour) ->
                // A share too small to see is left out rather than drawn as a
                // sliver nobody can read.
                val share = bytes.toFloat() / total
                if (share > 0.004f) {
                    Box(Modifier.fillMaxHeight().weight(share).background(colour))
                }
            }
        }
        parts.forEach { (label, bytes, colour) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(10.dp).clip(ContinuousCapsule()).background(colour))
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    android.text.format.Formatter.formatShortFileSize(context, bytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkDim,
                )
            }
        }
    }
}

/** What the page ends on: which build this is. */
@Composable
private fun IndexFooter() {
    Text(
        "Square ${BuildConfig.VERSION_NAME}",
        style = MaterialTheme.typography.bodySmall,
        color = InkDim,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

/** One place to go, as a tile: its mark, its name, and what is in it. */
@Composable
private fun PageTile(page: SettingsPage, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Tile(
        icon = page.icon,
        title = stringResource(page.title),
        summary = stringResource(page.summary),
        modifier = modifier,
        onClick = onClick,
    )
}

/** Not a page: the one tile that leaves the app. */
@Composable
private fun SupportTile(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Tile(
        icon = PhosphorIcons.Regular.HandHeart,
        title = stringResource(R.string.support_development),
        summary = KOFI_URL.removePrefix("https://"),
        modifier = modifier,
        onClick = onClick,
    )
}

/** The shape every tile on the index has. */
@Composable
private fun Tile(
    icon: ImageVector,
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .heightIn(min = 132.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Ink.copy(alpha = 0.07f))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RowIcon(icon)
        Spacer(Modifier.weight(1f))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
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
            icon = PhosphorIcons.Regular.FilmStrip,
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
            icon = PhosphorIcons.Regular.Repeat,
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
    val skipFade by preferences.skipFade.collectAsStateWithLifecycle()

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
            icon = PhosphorIcons.Regular.Scissors,
            checked = trimSilence,
            backdrop = backdrop,
            onChange = preferences::setTrimSilence,
        )
        RowDivider()
        // Beside the crossfade, which is the same movement at the end of a
        // track rather than at the listener's hand, and separate from it: one
        // is about how records end, the other about what happens when somebody
        // has heard enough.
        DownloadSwitch(
            label = stringResource(R.string.skip_fade),
            icon = PhosphorIcons.Regular.Waveform,
            checked = skipFade,
            backdrop = backdrop,
            onChange = preferences::setSkipFade,
        )
        RowDivider()
        Text(
            stringResource(R.string.skip_fade_note),
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
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
private fun UpdateRow(onNeedsPermission: (Updater.State.Available) -> Unit) {
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

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy) {
                scope.launch { updater.checkAndInstall()?.let(onNeedsPermission) }
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
            icon = PhosphorIcons.Regular.WifiHigh,
            checked = wifiOnly,
            backdrop = backdrop,
            onChange = settings::setWifiOnly,
        )

        RowDivider()
        DownloadSwitch(
            label = stringResource(R.string.download_liked_songs),
            icon = PhosphorIcons.Regular.Heart,
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
                            runCatching { dev.lelonio.square.download.YouTubeDownloads.forget(context, orphanUri) }
                            dev.lelonio.square.download.DownloadExtras.forget(orphanUri)
                        }
                    }
                }
            },
        )

        RowDivider()
        DownloadSwitch(
            label = stringResource(R.string.offline_mode),
            icon = PhosphorIcons.Regular.AirplaneTilt,
            checked = offline,
            backdrop = backdrop,
            onChange = settings::setOfflineMode,
        )

        // Always offered, unlike the retry above: the music being here is not
        // the same as its covers and words being here, and a song kept by an
        // older build has none of them. Nothing is re-downloaded but the
        // extras; the audio stays where it is.
        RowDivider()
        ActionRow(
            stringResource(R.string.download_refetch_extras),
            destructive = false,
            icon = PhosphorIcons.Regular.Image,
        ) {
            scope.launch {
                (context.applicationContext as dev.lelonio.square.SquareApplication)
                    .downloadQueue.refetchExtras()
                dev.lelonio.square.download.DownloadService.start(context)
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.download_refetch_started),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }

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
            ActionRow(
                stringResource(R.string.download_retry_failed),
                destructive = false,
                icon = PhosphorIcons.Regular.ArrowClockwise,
            ) {
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
    icon: ImageVector? = null,
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
        icon?.let { RowIcon(it) }
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        // Drawn, not glass; see PlainToggle.
        PlainToggle(checked = checked, onChange = onChange)
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
private fun InfoRow(label: String, value: String, icon: ImageVector? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let { RowIcon(it) }
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

/**
 * Puts together a report of the app's last crashes and recent log, with the
 * account taken out, and saves it to a folder or opens the share sheet on it;
 * see Report.
 *
 * Saving is the first of the two because the share sheet on many phones has no
 * way to put a file in a folder, and a report that can only be sent to an app
 * cannot be attached to an issue from the browser.
 */
@Composable
private fun ReportRows(name: String?, onSave: (java.io.File) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    fun failed(error: Throwable) {
        android.util.Log.w("SquareReport", "report failed: $error")
        android.widget.Toast.makeText(context, context.getString(R.string.report_failed), android.widget.Toast.LENGTH_SHORT).show()
    }

    fun prepare(then: (java.io.File) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { dev.lelonio.square.diagnostics.Report.build(context, listOfNotNull(name)) }
                .onSuccess(then)
                .onFailure(::failed)
            busy = false
        }
    }

    Column {
        ActionRow(
            stringResource(if (busy) R.string.report_preparing else R.string.save_report),
            destructive = false,
        ) { prepare(onSave) }
        RowDivider()
        ActionRow(stringResource(R.string.send_report), destructive = false) {
            prepare { file ->
                runCatching { dev.lelonio.square.diagnostics.Report.share(context, file) }.onFailure(::failed)
            }
        }
        Text(
            stringResource(R.string.send_report_note),
            style = MaterialTheme.typography.bodySmall,
            color = InkDim,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
        )
    }
}

@Composable
private fun ActionRow(
    label: String,
    destructive: Boolean,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    if (icon != null) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RowIcon(icon)
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (destructive) MaterialTheme.colorScheme.error else Ink,
                modifier = Modifier.weight(1f),
            )
        }
        return
    }
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
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let { RowIcon(it) }
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
private const val KOFI_URL = "https://ko-fi.com/lelonio"
