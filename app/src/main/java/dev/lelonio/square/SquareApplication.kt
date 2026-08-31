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
import dev.lelonio.square.offline.DownloadManager
import dev.lelonio.square.offline.OfflineLibrary

class SquareApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        dev.lelonio.square.playback.AudioEffects.load(this)
        reportProfileStatus(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
        )
    }

    val tokenStore: TokenStore by lazy { TokenStore(this) }
    val spotifySignedIn: Boolean
        get() = tokenStore.isLoggedIn || dev.lelonio.square.auth.EngineCredentials.exist(this)
    val recentStore: RecentStore by lazy { RecentStore(this) }
    val searchHistory: dev.lelonio.square.data.SearchHistoryStore by lazy { dev.lelonio.square.data.SearchHistoryStore(this) }
    val playlistOrder: PlaylistOrderStore by lazy { PlaylistOrderStore(this) }
    val pinnedPlaylists: dev.lelonio.square.data.PinnedPlaylistStore by lazy { dev.lelonio.square.data.PinnedPlaylistStore(this) }
    val libraryView: dev.lelonio.square.data.LibraryViewStore by lazy { dev.lelonio.square.data.LibraryViewStore(this) }
    val preferences: PreferencesStore by lazy { PreferencesStore(this) }
    val quality: dev.lelonio.square.data.QualityStore by lazy { dev.lelonio.square.data.QualityStore(this) }
    val pathfinderKeys: dev.lelonio.square.data.PathfinderKeys by lazy { dev.lelonio.square.data.PathfinderKeys(this) }
    val gateway: dev.lelonio.square.data.Gateway by lazy { dev.lelonio.square.data.Gateway(pathfinderKeys) }
    val effectQuality: dev.lelonio.square.data.EffectQualityStore by lazy { dev.lelonio.square.data.EffectQualityStore(this) }
    val glass: dev.lelonio.square.data.GlassStore by lazy { dev.lelonio.square.data.GlassStore(this) }
    val crossfade: dev.lelonio.square.data.CrossfadeStore by lazy { dev.lelonio.square.data.CrossfadeStore(this) }
    val language: LanguageStore by lazy { LanguageStore(this) }
    val contextCache: ContextCacheStore by lazy { ContextCacheStore(this) }
    val webApi: WebApiAccount by lazy { WebApiAccount(this) }
    val api: SpotifyApi by lazy { ApiFactory.create(webApi.tokens, debug = BuildConfig.DEBUG) }
    val updater: dev.lelonio.square.update.Updater by lazy { dev.lelonio.square.update.Updater(this) }
    val effectPresets: EffectPresetStore by lazy { EffectPresetStore(this) }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val spotifyBackend: dev.lelonio.square.backend.SpotifyBackend by lazy { dev.lelonio.square.backend.SpotifyBackend(this) }

    val youtubeAccount: dev.lelonio.square.backend.youtube.YouTubeAccount by lazy {
        dev.lelonio.square.backend.youtube.YouTubeAccount(this)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val youtubeBackend: dev.lelonio.square.backend.youtube.YouTubeBackend by lazy {
        dev.lelonio.square.backend.youtube.YouTubeBackend(youtubeAccount)
    }

    /** Persistent offline-download orchestration; it never owns playback state. */
    val downloadManager: DownloadManager by lazy { DownloadManager(this) }

    /** Read-only offline-library facade over the download state. */
    val offlineLibrary: OfflineLibrary by lazy { OfflineLibrary(downloadManager) }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val activeBackend: dev.lelonio.square.backend.MusicBackend
        get() = when (preferences.backend.value) {
            dev.lelonio.square.backend.BackendId.SPOTIFY -> spotifyBackend
            dev.lelonio.square.backend.BackendId.YOUTUBE_MUSIC -> youtubeBackend
        }
}
