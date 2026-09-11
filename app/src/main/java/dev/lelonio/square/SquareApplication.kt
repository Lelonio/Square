package dev.lelonio.square

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.metrolist.innertube.YouTube
import dev.lelonio.square.auth.TokenStore
import dev.lelonio.square.auth.WebApiAccount
import dev.lelonio.square.data.ContextCacheStore
import dev.lelonio.square.data.LanguageStore
import dev.lelonio.square.data.PlaylistOrderStore
import dev.lelonio.square.data.PreferencesStore
import kotlinx.coroutines.launch
import dev.lelonio.square.data.RecentStore
import dev.lelonio.square.playback.EffectPresetStore
import dev.lelonio.square.data.ApiFactory
import dev.lelonio.square.data.SpotifyApi
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container.
 *
 * Small enough not to need a DI framework, and keeping it explicit makes the
 * one thing that matters obvious: a single [TokenStore] instance, so token
 * refreshes really are serialised across the whole process.
 */
class SquareApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // The vendored InnerTube module logs through Timber, and with nothing
        // planted every one of those lines went nowhere — which is why a
        // YouTube page that came back in an unexpected shape could only be
        // guessed at. Debug builds only: a release build has no business
        // writing what the account is doing to the system log.
        if (BuildConfig.DEBUG || BuildConfig.VERBOSE_LOG) {
            timber.log.Timber.plant(timber.log.Timber.DebugTree())
        }

        // Persistent HTTP cache for InnerTube requests in Android cache directory
        YouTube.cacheDir = cacheDir

        // Before anything can read or write them. The service used to do this on
        // creation, which is late: the player screen and the media session both
        // exist by then and either can announce a default that would be saved
        // over what the listener had set.
        dev.lelonio.square.playback.AudioEffects.load(this)

        // Where the extras live, and which tracks are worth keeping them for.
        // Attached here rather than in the service: Catalog reaches for it on
        // any thread and long before anything has started playing.
        dev.lelonio.square.download.DownloadExtras.attach(downloads.root, sharedHttpClient) {
            downloads.isDownloaded(it)
        }

        // The other catalogue's answers, kept between runs. Without a store to
        // write to it still works and simply asks again every time — which is
        // the difference between a page that opens on the right picture and one
        // that opens on Spotify's and changes it a second later.
        dev.lelonio.square.data.AppleCatalog.attach(this)

        // Two things feed the offline state, and one thing reads it out to the
        // engine. The switch below is the listener's; the watch is the network
        // going; and the engine has to be told either way, because it cannot
        // see either of them and would otherwise go on fetching.
        val offlineScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
        )
        dev.lelonio.square.playback.NetworkWatch(this, offlineScope).start()
        offlineScope.launch {
            dev.lelonio.square.playback.OfflineMode.active.collect { offline ->
                runCatching { dev.lelonio.square.nativecore.NativeBridge.setOfflineOnly(offline) }
            }
        }

        // The listener's own offline switch is persisted, so it has to be put
        // back before anything reads it. Collected rather than read once: it is
        // the one input to OfflineMode that can change without the service
        // being involved.
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
        ).launch {
            downloadSettings.offlineMode.collect(
                dev.lelonio.square.playback.OfflineMode::setManual,
            )
        }

        // Not a feature: a line in the log saying whether this install has been
        // compiled ahead of time yet. See reportProfileStatus.
        reportProfileStatus(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
        )
    }

    val tokenStore: TokenStore by lazy { TokenStore(this) }

    /**
     * What is downloaded and who asked for it.
     *
     * One instance, like the token store and for the same reason: the index is
     * a file, and two of these would write over each other. The audio it points
     * at belongs to the engine, which finds it without going through here.
     */
    val downloads: dev.lelonio.square.data.DownloadStore by lazy {
        dev.lelonio.square.data.DownloadStore(this)
    }

    /** Download quality, Wi-Fi only, the offline switch. */
    val downloadSettings: dev.lelonio.square.data.DownloadSettingsStore by lazy {
        dev.lelonio.square.data.DownloadSettingsStore(this)
    }

    /** Works through what [downloads] says is still owed. */
    val downloadQueue: dev.lelonio.square.download.DownloadQueue by lazy {
        dev.lelonio.square.download.DownloadQueue(this, downloads, downloadSettings)
    }

    /**
     * Whether Spotify can be used, which is not the same as holding a token.
     *
     * The engine keeps the credential the access point gave it and logs in with
     * that, so an OAuth session that has lapsed is no longer a reason to show
     * the login screen: everything the app plays goes through the access point,
     * and the access point is still answering. Only the Web API, which is the
     * user's own registered application, needs the token itself.
     */
    val spotifySignedIn: Boolean
        get() = tokenStore.isLoggedIn ||
            dev.lelonio.square.auth.EngineCredentials.exist(this)
    val recentStore: RecentStore by lazy { RecentStore(this) }

    /** Songs found by searching and then played; see [SearchHistoryStore]. */
    val searchHistory: dev.lelonio.square.data.SearchHistoryStore by lazy {
        dev.lelonio.square.data.SearchHistoryStore(this)
    }

    /** Which playlists were opened most recently, for ordering the home page. */
    val playlistOrder: PlaylistOrderStore by lazy { PlaylistOrderStore(this) }

    /** Tracks the listener has liked ("Tus me gusta"). */
    val likedStore: dev.lelonio.square.data.LikedStore by lazy {
        dev.lelonio.square.data.LikedStore(this)
    }

    /** Playlists the listener keeps at the top of the library; this device's own. */
    val pinnedPlaylists: dev.lelonio.square.data.PinnedPlaylistStore by lazy {
        dev.lelonio.square.data.PinnedPlaylistStore(this)
    }
    /** How the library was left looking: grid or list, and the sort. */
    val libraryView: dev.lelonio.square.data.LibraryViewStore by lazy {
        dev.lelonio.square.data.LibraryViewStore(this)
    }
    val preferences: PreferencesStore by lazy { PreferencesStore(this) }

    /** Which file the engine asks Spotify for. */
    val quality: dev.lelonio.square.data.QualityStore by lazy {
        dev.lelonio.square.data.QualityStore(this)
    }

    /** The identifiers Spotify's gateway wants; kept fresh from the repository. */
    val pathfinderKeys: dev.lelonio.square.data.PathfinderKeys by lazy {
        dev.lelonio.square.data.PathfinderKeys(this)
    }

    /** Track lists from Spotify's own gateway; see [dev.lelonio.square.data.Gateway]. */
    val gateway: dev.lelonio.square.data.Gateway by lazy {
        dev.lelonio.square.data.Gateway(pathfinderKeys)
    }

    /** Which stretcher works out speed and pitch. */
    val effectQuality: dev.lelonio.square.data.EffectQualityStore by lazy {
        dev.lelonio.square.data.EffectQualityStore(this)
    }

    /** What the glass is made of, and how much of it the phone has to pay for. */
    val glass: dev.lelonio.square.data.GlassStore by lazy {
        dev.lelonio.square.data.GlassStore(this)
    }

    /** How long one track dissolves into the next. */
    val crossfade: dev.lelonio.square.data.CrossfadeStore by lazy {
        dev.lelonio.square.data.CrossfadeStore(this)
    }

    /** What language the app is read in, and what the engine asks Spotify for. */
    val language: LanguageStore by lazy { LanguageStore(this) }

    /** Track lists already resolved, so reopening a playlist is not a reload. */
    val contextCache: ContextCacheStore by lazy { ContextCacheStore(this) }

    /**
     * The user's own Spotify application. Web API calls go through it so they
     * are metered against a quota nobody else shares — see [WebApiAccount].
     */
    val webApi: WebApiAccount by lazy { WebApiAccount(this) }

    /**
     * Shared [OkHttpClient] providing a common connection pool, DNS cache, and dispatcher
     * across Spotify API, DownloadExtras, and Coil image loading.
     */
    val sharedHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override fun newImageLoader(): ImageLoader {
        val am = getSystemService(android.app.ActivityManager::class.java)
        val isLowRam = am?.isLowRamDevice ?: false
        val memoryCachePercent = if (isLowRam) 0.12 else 0.20

        return ImageLoader.Builder(this)
            .okHttpClient { sharedHttpClient }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(memoryCachePercent)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02)
                    .build()
            }
            .allowRgb565(isLowRam)
            .allowHardware(true)
            .respectCacheHeaders(false)
            .build()
    }

    /**
     * The account's country code (e.g. "ES", "MX", "US"), drawn from the authenticated
     * account profile and cached in preferences. If the profile hasn't been fetched yet,
     * falls back to the device's locale country, or "from_token".
     */
    val userCountry: String
        get() = preferences.userCountry?.takeIf { it.length == 2 }?.uppercase()
            ?: java.util.Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase()
            ?: "US"

    val api: SpotifyApi by lazy {
        ApiFactory.create(
            tokens = webApi.tokens,
            fallbackTokens = tokenStore,
            nativeToken = { dev.lelonio.square.nativecore.NativeBridge.accessToken() },
            baseClient = sharedHttpClient,
            countryProvider = { userCountry },
            debug = BuildConfig.DEBUG,
        )
    }

    /** Checks the project's own releases; there is no store to do it. */
    val updater: dev.lelonio.square.update.Updater by lazy {
        dev.lelonio.square.update.Updater(this)
    }

    /** The user's saved speed / pitch / reverb combinations. */
    val effectPresets: EffectPresetStore by lazy { EffectPresetStore(this) }

    /**
     * The Spotify source, behind the common backend interface.
     *
     * A single instance because it owns the AudioTrack the native sink writes
     * into, and a second one would be a second sink for the same engine.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val spotifyBackend: dev.lelonio.square.backend.SpotifyBackend by lazy {
        dev.lelonio.square.backend.SpotifyBackend(this)
    }

    /** The Google session YouTube Music reads a personal library with. */
    val youtubeAccount: dev.lelonio.square.backend.youtube.YouTubeAccount by lazy {
        dev.lelonio.square.backend.youtube.YouTubeAccount(this)
    }

    /** YouTube Music; see [dev.lelonio.square.backend.youtube.YouTubeBackend]. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val youtubeBackend: dev.lelonio.square.backend.youtube.YouTubeBackend by lazy {
        dev.lelonio.square.backend.youtube.YouTubeBackend(youtubeAccount)
    }

    /**
     * Whichever of the two the user picked.
     *
     * Read afresh each time rather than held: the setting can change while the
     * app is running, and a cached backend would keep answering as the old one.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val activeBackend: dev.lelonio.square.backend.MusicBackend
        get() = when (preferences.backend.value) {
            dev.lelonio.square.backend.BackendId.SPOTIFY -> spotifyBackend
            dev.lelonio.square.backend.BackendId.YOUTUBE_MUSIC -> youtubeBackend
        }
}
