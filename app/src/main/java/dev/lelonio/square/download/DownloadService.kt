package dev.lelonio.square.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.lelonio.square.R
import dev.lelonio.square.SquareApplication
import dev.lelonio.square.playback.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the download queue alive and says what it is doing.
 *
 * Separate from [PlaybackService] because the two have different lifetimes: a
 * playlist can be downloading with nothing playing, and music can play with
 * nothing left to download. Sharing one service would have meant either
 * notification saying the wrong thing, and a queue that died whenever the
 * listener stopped the music.
 *
 * It does need the engine, though — the audio comes down a librespot session —
 * so starting this also asks [PlaybackService] to connect. The engine belongs
 * to the process rather than to either service, so whichever asks first gets it
 * and the other simply finds it there.
 */
class DownloadService : android.app.Service() {

    private val container get() = application as SquareApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            container.downloadQueue.stop()
            stop()
            return START_NOT_STICKY
        }

        // Up front and unconditionally: Android gives a service started into
        // the foreground a few seconds to say so, and the queue's first answer
        // may take longer than that if it has to wait for a session.
        goForeground(container.downloadQueue.status.value)

        // The audio comes down the librespot session, so there has to be one.
        // Harmless when there already is: the service guards its own repeated
        // connects.
        PlaybackService.connect(this)

        container.downloadQueue.wake()
        watch()
        // Re-created after a kill, the queue works out what it still owes from
        // the store; there is nothing in the intent worth keeping.
        return START_STICKY
    }

    private fun watch() {
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            container.downloadQueue.status.collectLatest { status ->
                if (!status.running) {
                    stop()
                    return@collectLatest
                }
                notify(status)
            }
        }
    }

    private fun stop() {
        watcher?.cancel()
        watcher = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // --------------------------------------------------------- the notification

    private fun goForeground(status: DownloadQueue.Status) {
        if (foreground) return
        foreground = true
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            build(status),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun notify(status: DownloadQueue.Status) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        // Posting is allowed to fail: without the notification permission there
        // is no notification, and the download is none the worse for it.
        runCatching { manager.notify(NOTIFICATION_ID, build(status)) }
    }

    private fun build(status: DownloadQueue.Status): Notification {
        val percent = if (status.total > 0) {
            ((status.done.toFloat() / status.total) * 100).toInt().coerceIn(0, 100)
        } else 0
        val countText = getString(R.string.download_progress_count, status.done, status.total)
        val progressText = "$countText ($percent%)"

        val text = when {
            status.waiting == DownloadQueue.Waiting.WIFI ->
                getString(R.string.download_waiting_wifi)
            status.waiting == DownloadQueue.Waiting.NETWORK ->
                getString(R.string.download_waiting_network)
            status.waiting == DownloadQueue.Waiting.ENGINE ->
                getString(R.string.download_waiting_engine)
            // The music is all here; what is left is what goes beside it.
            status.extras -> getString(R.string.download_extras)
            !status.currentTitle.isNullOrBlank() -> "${status.currentTitle} · $progressText"
            else -> progressText
        }

        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, DownloadService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.downloading))
            .setContentText(text)
            // Indeterminate while waiting: a bar frozen at 30% reads as a stuck
            // download, and the reason is in the text right beside it.
            .setProgress(
                status.total.coerceAtLeast(1),
                status.done,
                status.waiting != null || status.extras || status.total == 0,
            )
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, getString(android.R.string.cancel), stop)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.downloads),
                // Low: this is a progress bar, not news. It should be findable
                // in the shade and never interrupt anything.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "dev.lelonio.square.action.STOP_DOWNLOADS"

        /**
         * Starts working through whatever the store says is owed.
         *
         * Safe to call whenever that might have changed — adding a playlist,
         * removing one, the app starting. A service already running simply
         * wakes its queue, which was going to ask the store again anyway.
         */
        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
