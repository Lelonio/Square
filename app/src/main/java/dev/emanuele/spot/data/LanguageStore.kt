package dev.emanuele.spot.data

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The language the app is read in.
 *
 * Android 13 has a per-app language picker of its own, and this writes to it on
 * those versions so the two never disagree — a setting inside the app and a
 * different one in the system settings would be a bug nobody could explain.
 * Below 13 there is nothing to write to, so the choice is kept here and applied
 * by wrapping the context every screen is built from.
 *
 * Stored as a language tag, empty meaning "whatever the phone is set to". It is
 * also what the engine sends as `Accept-Language`, so the covers of Spotify's
 * generated playlists come back in the same language as the app.
 */
class LanguageStore(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _tag = MutableStateFlow(prefs.getString(KEY_LANGUAGE, null).orEmpty())

    /** The chosen tag, or empty for the system's. */
    val tag: StateFlow<String> = _tag.asStateFlow()

    /**
     * The two-letter language actually in force, which is what the engine wants.
     *
     * Falls back to English rather than to Italian: an unknown phone language
     * gets the same answer as the app's own base locale.
     */
    fun language(): String {
        val chosen = _tag.value
        if (chosen.isNotEmpty()) return chosen.substringBefore('-')
        val locale = systemLocale()
        return locale.language.takeIf { it.isNotEmpty() } ?: "en"
    }

    fun set(tag: String) {
        _tag.value = tag
        prefs.edit().putString(KEY_LANGUAGE, tag).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = app.getSystemService(android.app.LocaleManager::class.java)
            manager?.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList()
                else LocaleList.forLanguageTags(tag)
        }
    }

    /**
     * The same context, reading in the chosen language.
     *
     * Only needed below Android 13; above it the system has already applied the
     * choice by the time anything is created, and wrapping again would be a
     * second answer to a settled question.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val chosen = _tag.value
        if (chosen.isEmpty()) return base
        val locale = Locale.forLanguageTag(chosen)
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLocales(LocaleList(locale))
        return base.createConfigurationContext(configuration)
    }

    private fun systemLocale(): Locale =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.content.res.Resources.getSystem().configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            android.content.res.Resources.getSystem().configuration.locale
        }

    private companion object {
        const val FILE_NAME = "spot_language"
        const val KEY_LANGUAGE = "language"
    }
}

/**
 * The languages Square is translated into.
 *
 * Kept next to the store rather than in the screen that draws them: the same
 * list has to match res/xml/locales_config.xml, and one list is easier to keep
 * honest than two. Each name is written in its own language, which is how a
 * language picker is read by the person who needs it.
 */
val AppLanguages: List<Pair<String, String>> = listOf(
    "" to "",
    "en" to "English",
    "it" to "Italiano",
    "es" to "Español",
    "fr" to "Français",
    "de" to "Deutsch",
    "pt" to "Português",
)
