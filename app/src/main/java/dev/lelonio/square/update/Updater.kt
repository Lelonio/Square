package dev.lelonio.square.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.lelonio.square.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Updates the app from its own GitHub releases.
 *
 * Square cannot go on the Play Store — it re-implements a protocol whose terms
 * forbid it — so there is nothing to deliver an update but the app itself. The
 * releases page is already the distribution channel; this reads it.
 *
 * Nothing here has to verify what it downloads. Android refuses an update
 * signed with a different key than the installed copy, so an APK that is not
 * the one built with the project's keystore simply fails to install. A hash
 * check on top would look reassuring and add nothing the platform does not
 * already enforce.
 */
class Updater(context: Context) {

    private val app = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val version: String, val url: String, val bytes: Long) : State
        /** 0f..1f, or null while the server sends no length to measure against. */
        data class Downloading(val progress: Float?) : State
        /** Handed to the system installer; the dialog is Android's, not ours. */
        data object Installing : State
        data class Failed(val reason: String) : State
    }

    /**
     * Asks GitHub what the latest release is.
     *
     * Unauthenticated, which allows sixty requests an hour per address — far
     * more than a button can spend. It is still a request the user did not ask
     * for, which is why nothing here runs on its own: it is called when the
     * button is pressed and at no other time.
     */
    suspend fun check() {
        _state.value = State.Checking
        val result = runCatching { withContext(Dispatchers.IO) { latestRelease() } }
            .getOrElse {
                android.util.Log.w(TAG, "check failed: $it")
                _state.value = State.Failed(it.message ?: "network")
                return
            }

        if (result == null || !isNewer(result.version, BuildConfig.VERSION_NAME)) {
            _state.value = State.UpToDate
            return
        }
        _state.value = State.Available(result.version, result.url, result.bytes)
    }

    private data class Release(val version: String, val url: String, val bytes: Long)

    private fun latestRelease(): Release? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject
                ?: return null
            val tag = body["tag_name"]?.jsonPrimitive?.content?.removePrefix("v") ?: return null
            val asset = (body["assets"] as? JsonArray)
                ?.filterIsInstance<JsonObject>()
                ?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
                ?: return null
            val url = asset["browser_download_url"]?.jsonPrimitive?.content ?: return null
            val size = asset["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            return Release(tag, url, size)
        }
    }

    /**
     * Downloads the APK and hands it to the system installer.
     *
     * The confirmation dialog is Android's and cannot be skipped: only a system
     * app installs silently. That is the right outcome — an app that could
     * replace itself unattended is one the user has to trust rather more than
     * this one asks to be trusted.
     */
    suspend fun install(update: State.Available) {
        if (!canInstall()) {
            _state.value = State.Failed(REASON_PERMISSION)
            return
        }

        _state.value = State.Downloading(null)
        val apk = runCatching { withContext(Dispatchers.IO) { download(update) } }
            .getOrElse {
                android.util.Log.w(TAG, "download failed: $it")
                _state.value = State.Failed(it.message ?: "download")
                return
            }

        runCatching { withContext(Dispatchers.IO) { commit(apk) } }
            .onSuccess { _state.value = State.Installing }
            .onFailure {
                android.util.Log.w(TAG, "install failed: $it")
                apk.delete()
                _state.value = State.Failed(it.message ?: "install")
            }
    }

    private fun download(update: State.Available): File {
        // cacheDir, not filesDir: once the installer has read it the file is
        // dead weight, and twenty megabytes is worth letting the system reclaim
        // if the install never happens.
        val target = File(app.cacheDir, "update.apk")
        target.delete()

        val request = Request.Builder().url(update.url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("empty body")
            val total = body.contentLength().takeIf { it > 0 } ?: update.bytes
            var written = 0L

            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            _state.value = State.Downloading(written.toFloat() / total)
                        }
                    }
                }
            }
        }
        return target
    }

    private fun commit(apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        )
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("square", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            session.commit(
                PendingIntent.getBroadcast(
                    app,
                    0,
                    Intent(app, InstallReceiver::class.java)
                        .setPackage(app.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                ).intentSender,
            )
        }
        apk.delete()
    }

    /** Whether the user has allowed this app to install packages at all. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || app.packageManager.canRequestPackageInstalls()

    /** The settings page where that permission is granted. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun dismiss() {
        _state.value = State.Idle
    }

    companion object {
        private const val TAG = "Updater"

        const val REPO = "Lelonio/Square"

        /** Told apart from a network failure so the UI can offer the way out. */
        const val REASON_PERMISSION = "permission"

        /**
         * Compares two dotted versions numerically.
         *
         * String comparison would put 1.10.0 before 1.9.0, which is exactly the
         * release where a self-updater stops offering updates and nobody
         * notices for a month.
         */
        fun isNewer(candidate: String, installed: String): Boolean {
            val a = candidate.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val b = installed.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val left = a.getOrElse(i) { 0 }
                val right = b.getOrElse(i) { 0 }
                if (left != right) return left > right
            }
            return false
        }
    }
}
