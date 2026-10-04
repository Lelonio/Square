package dev.lelonio.square.playback.websdk

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only the engine's explicit account-level rejection activates this recovery. */
object WebSdkRecovery {
    private var sequence = 0
    private var setupOpen = false
    private val requests = MutableStateFlow(0)
    val setupRequests = requests.asStateFlow()
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val configured = changes.asSharedFlow()
    fun requestSetup() { if (!setupOpen && requests.value == 0) requests.value = ++sequence }
    fun consume(request: Int) {
        if (requests.value == request) { setupOpen = true; requests.value = 0 }
    }
    fun closeSetup() { setupOpen = false }
    private val stops = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val disconnected = stops.asSharedFlow()
    fun stopPlayback() { stops.tryEmit(Unit) }
    fun isConfigured(context: Context) = (context.applicationContext as dev.lelonio.square.SquareApplication).spotifySdkAccount.configured

    private val use = MutableStateFlow(false)

    /**
     * Whether Spotify plays through the official SDK: Spotify is the source, the
     * account is refused and the SDK is set up. What the SDK cannot do — crossfade, effects, karaoke,
     * bitrate, downloads — is taken off screen while this holds.
     */
    val inUse: StateFlow<Boolean> = use.asStateFlow()

    /** Read again after anything that can change either half of [inUse]. */
    fun refresh(context: Context) {
        val app = context.applicationContext as dev.lelonio.square.SquareApplication
        use.value = app.preferences.backend.value == dev.lelonio.square.backend.BackendId.SPOTIFY &&
            dev.lelonio.square.backend.spotify.SpotifyWebPlayback.needed(app) && isConfigured(app)
    }

    fun complete(context: Context) {
        (context.applicationContext as dev.lelonio.square.SquareApplication).spotifySdkAccount.enable()
        refresh(context)
        changes.tryEmit(Unit)
    }
}
