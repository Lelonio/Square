package dev.lelonio.square.data

import android.content.Context

/**
 * How the listener left the library looking.
 *
 * A grid and a list are not two ways of showing the same thing to the same
 * person: whoever wants rows wants them every time, and having to say so again
 * on every visit is the app forgetting something it was told. Kept on the
 * device, like the pins, because no service has an opinion about it.
 */
class LibraryViewStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** True for the grid, which is what a library opens as until told otherwise. */
    var grid: Boolean
        get() = prefs.getBoolean(KEY_GRID, true)
        set(value) {
            prefs.edit().putBoolean(KEY_GRID, value).apply()
        }

    /** The sort, by its own key; unknown values fall back to the default. */
    var order: String?
        get() = prefs.getString(KEY_ORDER, null)
        set(value) {
            prefs.edit().putString(KEY_ORDER, value).apply()
        }

    /** Whether the sort runs backwards; the direction is part of the sort. */
    var descending: Boolean
        get() = prefs.getBoolean(KEY_DESC, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DESC, value).apply()
        }

    private companion object {
        const val FILE_NAME = "square_library_view"
        const val KEY_GRID = "grid"
        const val KEY_ORDER = "order"
        const val KEY_DESC = "descending"
    }
}
