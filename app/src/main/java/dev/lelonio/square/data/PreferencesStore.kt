package dev.lelonio.square.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Small UI choices that should survive the screen being left.
 *
 * Kept as strings rather than as an enum so this module does not have to know
 * about the screens that use it, and so a value written by an older version that
 * no longer exists reads back as "not set" instead of crashing.
 */
class PreferencesStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _trackSort = MutableStateFlow(prefs.getString(KEY_TRACK_SORT, null))

    /** How the detail screen's track list is ordered; null until first chosen. */
    val trackSort: StateFlow<String?> = _trackSort.asStateFlow()

    fun setTrackSort(value: String) {
        _trackSort.value = value
        prefs.edit().putString(KEY_TRACK_SORT, value).apply()
    }

    private val _trackSortDescending = MutableStateFlow(prefs.getBoolean(KEY_TRACK_DESC, false))

    /** Whether that order runs backwards; the direction is part of the sort. */
    val trackSortDescending: StateFlow<Boolean> = _trackSortDescending.asStateFlow()

    fun setTrackSortDescending(value: Boolean) {
        _trackSortDescending.value = value
        prefs.edit().putBoolean(KEY_TRACK_DESC, value).apply()
    }

    /**
     * This phone's Connect id, made once and kept for good.
     *
     * A uuid rather than anything drawn from the hardware: it identifies an
     * install to one account's device list and nothing else, and there is no
     * reason for it to survive the app being removed.
     */
    fun deviceId(): String = prefs.getString(KEY_DEVICE_ID, null) ?: run {
        val id = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        id
    }

    private val _onboarded = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDED, false))

    /**
     * Whether the welcome tutorial has been finished at least once.
     *
     * Kept apart from "is the app configured": the setup can be undone later —
     * logging out, unlinking the Web API application — and re-running the whole
     * tutorial at that point would be answering a question nobody asked. The
     * tutorial stays reachable from the settings instead.
     */
    val onboarded: StateFlow<Boolean> = _onboarded.asStateFlow()

    fun setOnboarded(value: Boolean) {
        _onboarded.value = value
        prefs.edit().putBoolean(KEY_ONBOARDED, value).apply()
    }

    /**
     * Whether the player was open when the app was last left.
     *
     * Read straight rather than as a flow: the answer decides what the *first*
     * composed frame is, so it has to be available before anything is drawn. A
     * value that arrives a moment later is what produced the flash of the home
     * page this replaced.
     */
    fun playerWasOpen(): Boolean = prefs.getBoolean(KEY_PLAYER_OPEN, false)

    fun setPlayerOpen(value: Boolean) {
        prefs.edit().putBoolean(KEY_PLAYER_OPEN, value).apply()
    }

    private val _backend = MutableStateFlow(
        runCatching { dev.lelonio.square.backend.BackendId.valueOf(prefs.getString(KEY_BACKEND, null) ?: "") }
            .getOrDefault(dev.lelonio.square.backend.BackendId.SPOTIFY),
    )

    /** Which [dev.lelonio.square.backend.MusicBackend] the app plays through. */
    val backend: StateFlow<dev.lelonio.square.backend.BackendId> = _backend.asStateFlow()

    fun setBackend(value: dev.lelonio.square.backend.BackendId) {
        _backend.value = value
        _backendChosen.value = true
        prefs.edit().putString(KEY_BACKEND, value.name).apply()
    }

    private val _backendChosen = MutableStateFlow(prefs.contains(KEY_BACKEND))

    /**
     * Whether the user has ever picked a source.
     *
     * Separate from [backend] having a value: that one defaults to Spotify so
     * the rest of the app never has to handle "none", while this stays false
     * until the choice was actually made. It is what puts the picker in front
     * of a fresh install — and only once.
     */
    val backendChosen: StateFlow<Boolean> = _backendChosen.asStateFlow()

    /** When GitHub was last asked about a newer release. See [UpdateChecker]. */
    fun lastUpdateCheck(): Long = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)

    fun setLastUpdateCheck(value: Long) {
        prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()
    }

    /**
     * The version the user has already been told about and dismissed.
     *
     * One version rather than a set: releases only move forward, so a newer one
     * than the dismissed one is news again, and an older one cannot appear.
     */
    fun skippedUpdate(): String? = prefs.getString(KEY_SKIPPED_UPDATE, null)

    fun setSkippedUpdate(value: String) {
        prefs.edit().putString(KEY_SKIPPED_UPDATE, value).apply()
    }

    private companion object {
        const val FILE_NAME = "square_preferences"
        const val KEY_TRACK_SORT = "track_sort"
        const val KEY_TRACK_DESC = "track_sort_descending"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_PLAYER_OPEN = "player_open"
        const val KEY_BACKEND = "backend"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        const val KEY_SKIPPED_UPDATE = "skipped_update"
    }
}
