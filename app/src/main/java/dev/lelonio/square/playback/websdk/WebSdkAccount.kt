package dev.lelonio.square.playback.websdk

import android.content.Context
import dev.lelonio.square.auth.SpotifyOAuth
import dev.lelonio.square.auth.TokenStore

/** App-owned OAuth credentials, separate from both the native engine and search. */
internal class WebSdkAccount(context: Context) {
    private val app = context.applicationContext
    private val prefs = context.getSharedPreferences("square_sdk_account", Context.MODE_PRIVATE)
    var clientId: String = prefs.getString("client_id", "").orEmpty()
        private set
    val tokens = TokenStore(context, "square_sdk_tokens") {
        clientId.takeIf { it.isNotBlank() } ?: error("SDK client id missing")
    }

    val enabled: Boolean get() = prefs.getBoolean("enabled", false)
    val configured: Boolean get() = enabled && clientId.isNotBlank() && tokens.isLoggedIn
    fun enable() { prefs.edit().putBoolean("enabled", true).commit() }
    fun disconnect() {
        tokens.clear()
        prefs.edit().putBoolean("enabled", false).commit()
        WebSdkRecovery.refresh(app)
        WebSdkRecovery.stopPlayback()
    }

    fun configure(value: String) {
        val next = value.trim()
        require(next.matches(Regex("[a-fA-F0-9]{32}"))) { "Invalid client id" }
        require(next != SpotifyOAuth.CLIENT_ID) { "Use your own Spotify developer application" }
        if (next == clientId) return
        disconnect()
        prefs.edit().putString("client_id", next).commit()
        clientId = next
    }

    suspend fun authorize(context: Context) {
        val result = SpotifyOAuth.authorize(context, clientId, listOf(
            "streaming", "user-read-private", "user-read-email",
            "user-read-playback-state", "user-modify-playback-state",
        ))
        tokens.save(result)
    }
}
