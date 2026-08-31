package dev.lelonio.square.offline

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DownloadPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _wifiOnly = MutableStateFlow(prefs.getBoolean(KEY_WIFI_ONLY, false))
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    private val _quality = MutableStateFlow(
        runCatching { DownloadQuality.valueOf(prefs.getString(KEY_QUALITY, null).orEmpty()) }
            .getOrDefault(DownloadQuality.STANDARD),
    )
    val quality: StateFlow<DownloadQuality> = _quality.asStateFlow()

    fun setWifiOnly(value: Boolean) {
        _wifiOnly.value = value
        prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()
    }

    fun setQuality(value: DownloadQuality) {
        _quality.value = value
        prefs.edit().putString(KEY_QUALITY, value.name).apply()
    }

    private companion object {
        const val FILE = "square_download_preferences"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_QUALITY = "quality"
    }
}
