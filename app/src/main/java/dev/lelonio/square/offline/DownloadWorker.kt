package dev.lelonio.square.offline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.core.app.NotificationCompat
import dev.lelonio.square.R
import dev.lelonio.square.SquareApplication

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        setForeground(foregroundInfo(jobId))
        return when ((applicationContext as SquareApplication).downloadManager.runWorker(jobId)) {
            DownloadManager.WorkerRunResult.DONE -> Result.success()
            DownloadManager.WorkerRunResult.RETRY -> Result.retry()
        }
    }

    private fun foregroundInfo(jobId: String): ForegroundInfo {
        val channelManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        channelManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Offline downloads", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText("Downloading music")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(
            NOTIFICATION_BASE + (jobId.hashCode() and 0x3FFF),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val KEY_JOB_ID = "download_job_id"
        private const val CHANNEL_ID = "offline_downloads"
        private const val NOTIFICATION_BASE = 51_000
    }
}
