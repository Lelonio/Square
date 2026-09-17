package dev.lelonio.square.ui

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.MotionEvent

/**
 * Asks for the screen's fastest mode while a finger is on the app, and gives
 * it back shortly after.
 *
 * Some phones decide the rate app by app. The Nothing Phone (3a) held this app
 * at 90 Hz while other apps scrolled at 120: it gives an app that asks only
 * for "fast" its middle rate, and a frame rate requested from a view is
 * clamped to that too. What it does honour is a window naming the display mode
 * it wants. Named for good, though, that mode keeps the panel at its top rate
 * over a picture that is not moving, which is battery spent on nothing. So it
 * is named when a finger comes down and released a little after the last one
 * lifts, which leaves time for a fling to settle and for the bar to finish
 * folding.
 *
 * Where the phone already runs at its top rate, or has only one, asking
 * changes nothing.
 */
internal class TouchRefreshRate(private val activity: Activity) {

    private val handler = Handler(Looper.getMainLooper())
    private val release = Runnable { request(NO_PREFERENCE) }

    /** The mode last written to the window, so a tap does not rewrite it. */
    private var requested = NO_PREFERENCE

    fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handler.removeCallbacks(release)
                request(fastestMode())
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(release)
                handler.postDelayed(release, HOLD_MS)
            }
        }
    }

    /** Lets the mode go at once, for an activity that is leaving the screen. */
    fun reset() {
        handler.removeCallbacks(release)
        request(NO_PREFERENCE)
    }

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

        /** Long enough for a fling and the bar's fold after the finger lifts. */
        const val HOLD_MS = 2_000L
    }
}
