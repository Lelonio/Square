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

    val tokenStore: TokenStore by lazy { TokenStore(this) }
    val recentStore: RecentStore by lazy { RecentStore(this) }

    /** Which playlists were opened most recently, for ordering the home page. */
    val playlistOrder: PlaylistOrderStore by lazy { PlaylistOrderStore(this) }
    val preferences: PreferencesStore by lazy { PreferencesStore(this) }

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
}
