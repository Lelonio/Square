package dev.lelonio.square.auth

import android.content.Context
import java.io.File

/**
 * The credential the access point issued for this device, as seen from Kotlin.
 *
 * Signing in is an OAuth flow, but staying signed in is not. The token that flow
 * hands back lives an hour, and the refresh token behind it is rotated on every
 * use and revoked on the first one that goes wrong, so an app that needs OAuth
 * at every launch is one bad refresh away from an empty login screen. The blob
 * the access point answers a successful handshake with has neither limit: the
 * native side keeps it and logs in with it from then on.
 *
 * This is the Kotlin half of that: whether the engine can authenticate on its
 * own. It is the difference between "the OAuth session has lapsed, ask the user
 * to sign in" and "the OAuth session has lapsed, and playback carries on".
 */
object EngineCredentials {

    /** Where librespot keeps its own copy; the native side is given this path. */
    fun dir(context: Context): File = context.filesDir.resolve("librespot")

    /**
     * True when the engine has a credential of its own to log in with.
     *
     * Read, not only found. A file left empty by a process killed while
     * writing it is there and holds nothing, and taking it for a credential
     * kept the app out of the login screen with no way in: offline, for good,
     * with nothing saying why.
     */
    fun exist(context: Context): Boolean {
        val file = dir(context).resolve(KEPT).resolve(FILE)
        if (!file.isFile || file.length() == 0L) return false
        return runCatching { org.json.JSONObject(file.readText()) }.isSuccess
    }

    /** Dropped along with the OAuth session, so signing out really signs out. */
    fun clear(context: Context) {
        dir(context).deleteRecursively()
    }

    /** Matches the directory the native side keeps the reusable blob in. */
    private const val KEPT = "reusable"
    private const val FILE = "credentials.json"
}
