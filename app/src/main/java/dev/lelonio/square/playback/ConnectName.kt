package dev.lelonio.square.playback

import android.content.Context

/**
 * What this phone is called in Spotify's device list: the phone's own name,
 * as its owner set it in the system, and the app's after it, so two phones
 * running Square are told apart ("OnePlus 8T (Square)" and "Phone (3a) (Square)")
 * rather than both reading "Square".
 */
object ConnectName {
    fun of(context: Context): String {
        val phone = runCatching {
            android.provider.Settings.Global.getString(context.contentResolver, "device_name")
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(android.os.Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }, android.os.Build.MODEL)
                .joinToString(" ").trim().ifEmpty { "Android" }
        return "$phone (Square)"
    }
}
