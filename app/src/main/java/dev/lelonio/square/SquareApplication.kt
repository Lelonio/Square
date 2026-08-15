package dev.lelonio.square

import android.app.Application
import dev.lelonio.square.auth.TokenStore
import dev.lelonio.square.auth.WebApiAccount
import dev.lelonio.square.data.ContextCacheStore
import dev.lelonio.square.data.LanguageStore
import dev.lelonio.square.data.PlaylistOrderStore
import dev.lelonio.square.data.PreferencesStore
import dev.lelonio.square.data.RecentStore
import dev.lelonio.square.playback.EffectPresetStore
import dev.lelonio.square.data.ApiFactory
import dev.lelonio.square.data.SpotifyApi

/**
 * Manual dependency container.
 *
 * Small enough not to need a DI framework, and keeping it explicit makes the
 * one thing that matters obvious: a single [TokenStore] instance, so token
 * refreshes really are serialised across the whole process.
 */
class SquareApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Before anything can read or write them. The service used to do this on
        // creation, which is late: the player screen and the media session both
        // exist by then and either can announce a default that would be saved
        // over what the listener had set.
        dev.lelonio.square.playback.AudioEffects.load(this)
    }

    val tokenStore: TokenStore by lazy { TokenStore(this) }

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

    /** Which playlists were opened most recently, for ordering the home page. */
    val playlistOrder: PlaylistOrderStore by lazy { PlaylistOrderStore(this) }

    /** Playlists the listener keeps at the top of the library; this device's own. */
    val pinnedPlaylists: dev.lelonio.square.data.PinnedPlaylistStore by lazy {
        dev.lelonio.square.data.PinnedPlaylistStore(this)
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

    /** Which stretcher works out speed and pitch. */
    val effectQuality: dev.lelonio.square.data.EffectQualityStore by lazy {
        dev.lelonio.square.data.EffectQualityStore(this)
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

    val api: SpotifyApi by lazy { ApiFactory.create(webApi.tokens, debug = BuildConfig.DEBUG) }

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
