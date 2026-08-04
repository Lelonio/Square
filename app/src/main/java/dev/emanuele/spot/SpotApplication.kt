package dev.emanuele.spot

import android.app.Application
import dev.emanuele.spot.auth.TokenStore
import dev.emanuele.spot.auth.WebApiAccount
import dev.emanuele.spot.data.RecentStore
import dev.emanuele.spot.playback.EffectPresetStore
import dev.emanuele.spot.data.ApiFactory
import dev.emanuele.spot.data.SpotifyApi

/**
 * Manual dependency container.
 *
 * Small enough not to need a DI framework, and keeping it explicit makes the
 * one thing that matters obvious: a single [TokenStore] instance, so token
 * refreshes really are serialised across the whole process.
 */
class SpotApplication : Application() {

    val tokenStore: TokenStore by lazy { TokenStore(this) }
    val recentStore: RecentStore by lazy { RecentStore(this) }

    /**
     * The user's own Spotify application. Web API calls go through it so they
     * are metered against a quota nobody else shares — see [WebApiAccount].
     */
    val webApi: WebApiAccount by lazy { WebApiAccount(this) }

    val api: SpotifyApi by lazy { ApiFactory.create(webApi.tokens, debug = BuildConfig.DEBUG) }

    /** The user's saved speed / pitch / reverb combinations. */
    val effectPresets: EffectPresetStore by lazy { EffectPresetStore(this) }
}
