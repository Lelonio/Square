package dev.lelonio.square.playback.websdk

import android.app.*
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.MediaMetadata
import android.os.Binder
import android.os.IBinder
import android.webkit.*
import androidx.core.app.NotificationCompat
import dev.lelonio.square.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Isolated SDK proof of concept. No access to DRM keys or native-engine credentials. */
class WebSdkService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@WebSdkService }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var engine: WebSdkEngine
    val state get() = engine.state
    internal val account get() = engine.account
    val webView get() = engine.webView
    private var foreground = false
    private lateinit var media: MediaSession

    override fun onCreate() {
        super.onCreate()
        engine = WebSdkEngine(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.web_sdk_title), NotificationManager.IMPORTANCE_LOW),
        )
        media = MediaSession(this, "SquareWebSdk").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { resume() }
                override fun onPause() { command("pause") }
                override fun onSeekTo(pos: Long) { seek(pos) }
                override fun onStop() { stopPlayback() }
            })
        }
        scope.launch { engine.state.collect { next ->
            update(next)
            if (next.stage in setOf("error", "idle", "capable") && foreground) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                foreground = false
                stopSelf()
            }
        } }
    }
    override fun onBind(intent: Intent): IBinder = LocalBinder()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) stopPlayback() else {
            promote()
            if (intent?.action == TOGGLE) {
                if (state.value.paused) resume() else command("pause")
            }
        }
        return START_NOT_STICKY
    }

    private fun promote() {
        startForeground(NOTIFICATION, notification())
        foreground = true
    }
    private fun notification(): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, WebSdkActivity::class.java), FLAGS)
        fun action(name: String, id: Int) = PendingIntent.getService(this, id,
            Intent(this, WebSdkService::class.java).setAction(name), FLAGS)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(state.value.title.ifBlank { getString(R.string.web_sdk_title) })
            .setContentText(state.value.artist.ifBlank { getString(R.string.web_sdk_running) })
            .setContentIntent(open).setOngoing(!state.value.paused)
            .addAction(0, getString(if (state.value.paused) R.string.play else R.string.pause), action(TOGGLE, 1))
            .addAction(0, getString(R.string.web_sdk_stop), action(STOP, 2))
            .build()
    }
    private fun update(next: WebSdkState) {
        media.isActive = next.deviceId != null
        media.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP)
            .setState(if (next.paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING,
                next.position, if (next.paused) 0f else 1f).build())
        media.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, next.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, next.artist)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, next.duration).build())
        if (foreground) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
    }

    fun connect(probeOnly: Boolean = false) {
        promote()
        engine.connect(probeOnly)
    }
    fun command(name: String, position: Long = 0) = engine.command(name, position)
    fun resume() = engine.resume()
    fun seek(position: Long) = engine.seek(position)
    fun play(input: String) = engine.play(input)
    fun stopPlayback() {
        engine.stopPlayback()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }
    override fun onDestroy() {
        engine.release()
        media.release()
        scope.cancel()
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "square_web_sdk"
        private const val NOTIFICATION = 740
        private const val STOP = "square.sdk.STOP"
        private const val TOGGLE = "square.sdk.TOGGLE"
        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
