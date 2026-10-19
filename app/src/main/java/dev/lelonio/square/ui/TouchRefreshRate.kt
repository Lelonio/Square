package dev.lelonio.square.ui

import android.app.Activity
import android.os.Build
import android.view.Display

/**
 * Asks for the screen's fastest mode for as long as the app is on screen.
 *
 * Some phones decide the rate app by app. The Nothing Phone (3a) held this app
 * at 90 Hz while other apps scrolled at 120: it gives an app that asks only
 * for "fast" its middle rate, and a frame rate requested from a view is
 * clamped to that too. What it does honour is a window naming the display mode
 * it wants.
 *
 * It used to be named only while a finger was on the app and let go two
 * seconds after, to spare the battery over a still picture. Everything that
 * moves on its own then ran at the phone's lower rate (the lyrics scrolling,
 * the player opening on a tap that had already lifted, a cover playing), and
 * on Xiaomi's phones, which drop to sixty for an app that does not ask, the
 * app felt like it ran at sixty (#85). The phone still lowers the rate itself
 * when nothing on screen changes, wherever its panel can.
 *
 * Where the phone already runs at its top rate, or has only one, asking
 * changes nothing.
 */
internal class TouchRefreshRate(private val activity: Activity) {

    /** The mode last written to the window, so it is not rewritten. */
    private var requested = NO_PREFERENCE

    /** Asks for the fastest mode; called as the app comes on screen. */
    fun hold() = request(fastestMode())

    /** Lets the mode go, for an activity that is leaving the screen. */
    fun reset() = request(NO_PREFERENCE)

    /**
     * The fastest mode at the resolution the screen is using now: a mode at
     * another resolution would be a resolution change, not a faster one.
     */
    private fun fastestMode(): Int {
        val display = display() ?: return NO_PREFERENCE
        val current = display.mode
        val fastest = display.supportedModes
            .filter {
                it.physicalWidth == current.physicalWidth &&
                    it.physicalHeight == current.physicalHeight
            }
            .maxByOrNull { it.refreshRate }
            ?: return NO_PREFERENCE
        return fastest.modeId
    }

    @Suppress("DEPRECATION")
    private fun display(): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            activity.windowManager.defaultDisplay
        }

    private fun request(modeId: Int) {
        if (modeId == requested) return
        requested = modeId
        val window = activity.window
        val attributes = window.attributes
        attributes.preferredDisplayModeId = modeId
        window.attributes = attributes
    }

    private companion object {
        const val NO_PREFERENCE = 0
    }
}
