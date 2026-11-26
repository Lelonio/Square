package dev.lelonio.square.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Carries the installer's answer back.
 *
 * The first thing a session reports is [PackageInstaller.STATUS_PENDING_USER_ACTION],
 * and the intent inside it *is* the confirmation dialog: without starting it the
 * install waits for an approval nobody is ever asked for, which looks exactly
 * like a download that silently did nothing.
 */
class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                // Started by the app's own window, not from here. A receiver is
                // in the background as far as Android is concerned, and from
                // Android 15 on its start of the dialog was dropped without a
                // word: the download reached 100% and nothing followed (#229).
                // The screen shows it as soon as it is in front; see
                // Updater.confirm.
                val updater = (context.applicationContext as? dev.lelonio.square.SquareApplication)?.updater
                if (confirm != null && updater != null) {
                    updater.askToConfirm(confirm)
                } else {
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    confirm?.let(context::startActivity)
                }
            }

            PackageInstaller.STATUS_SUCCESS ->
                android.util.Log.i(TAG, "update installed")

            else -> {
                // The message names the package and the failure, nothing the
                // user typed.
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
                android.util.Log.w(TAG, "install did not complete ($status): $message")
                // So the update is offered again; see Updater.installAbandoned.
                (context.applicationContext as? dev.lelonio.square.SquareApplication)
                    ?.updater
                    ?.installAbandoned()
            }
        }
    }

    private companion object {
        const val TAG = "InstallReceiver"
    }
}
