package dev.lelonio.square.auth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.lelonio.square.R

/**
 * Keeps the app awake while the listener signs in to Spotify in the browser.
 *
 * The sign-in comes back as a request to the app's own listener on
 * 127.0.0.1, and the app is in the background the whole time the browser is
 * up. Android freezes a background process with nothing in the foreground —
 * by default since Android 14, on some phones before — and a frozen process
 * still has its socket accept the browser's connection but never answers it:
 * the Spotify page loaded for ever, then gave up with ERR_CONNECTION_TIMED_OUT,
 * and the app came back to a spinner. A foreground service is what Android
 * leaves running, so one is held for exactly as long as the sign-in waits.
 */
class LoginKeepAlive : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.login_in_progress))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun channel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.login_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val CHANNEL_ID = "square_login"
        private const val NOTIFICATION_ID = 4_301

        /**
         * Held from before the browser opens. Failing to start is not a
         * reason to refuse the sign-in: it still works wherever the process
         * is not frozen, as it did before this existed.
         */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, LoginKeepAlive::class.java))
            }.onFailure { android.util.Log.w("SpotOAuth", "sign-in keep-alive not started: $it") }
        }

        /** Allowed from the background, unlike starting one. */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LoginKeepAlive::class.java)) }
        }
    }
}
