package com.mediahub.pear.shell

import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.data.PrefValue

/*
 * The window as it was last left (Batch 4.6c). Desktop only, and no iOS counterpart: the iOS app has no windows. The
 * settings keys are in PrefKeys, and are left out of a settings backup, because a window belongs to the machine.
 * Positions and sizes are in dp, the units Compose's window state uses.
 */

/** A floating window's place and size, in dp. */
data class WindowGeometry(val x: Double, val y: Double, val width: Double, val height: Double)

/** The window's mode, and its floating geometry when one was kept. */
data class SavedWindow(val mode: WindowMode, val geometry: WindowGeometry?)

/** A screen's bounds, in dp. */
data class ScreenBounds(val x: Double, val y: Double, val width: Double, val height: Double)

object WindowMemory {
    /**
     * Sanity limits for a saved size: they catch garbage, not a window that is merely small. The window's own minimum is
     * enforced by the window itself, and it is in pixels, so it is smaller in dp on a scaled screen.
     */
    const val MIN_WIDTH = 200.0
    const val MIN_HEIGHT = 300.0
    const val MAX_SIZE = 20_000.0

    /** How much of the window's top edge must be on a screen for the window to be reachable (the title bar's height). */
    const val TITLE_BAR_HEIGHT = 24.0

    fun usable(geometry: WindowGeometry): Boolean =
        geometry.x.isFinite() && geometry.y.isFinite() &&
            geometry.width in MIN_WIDTH..MAX_SIZE && geometry.height in MIN_HEIGHT..MAX_SIZE

    /** True when the window's title bar, at its centre, lies on one of [screens]. */
    fun titleBarOnScreen(geometry: WindowGeometry, screens: List<ScreenBounds>): Boolean {
        val px = geometry.x + geometry.width / 2
        val py = geometry.y + TITLE_BAR_HEIGHT / 2
        return screens.any { s ->
            px >= s.x && px < s.x + s.width && py >= s.y && py < s.y + s.height
        }
    }

    /** A size that fits inside [screen], and never less than the sanity minimum. */
    fun fit(width: Double, height: Double, screen: ScreenBounds): Pair<Double, Double> {
        val w = width.coerceIn(MIN_WIDTH, maxOf(MIN_WIDTH, screen.width))
        val h = height.coerceIn(MIN_HEIGHT, maxOf(MIN_HEIGHT, screen.height))
        return w to h
    }

    /**
     * The mode to open in for a saved [mode]. Full screen is never opened: it belongs to the player, which leaves it itself,
     * and a window that opens full screen has no title bar, no sidebar over a player and no button to leave it by. A state
     * saved before this rule (it was written as Fullscreen) opens maximized, the state a full-screen player came from.
     */
    fun openingMode(mode: WindowMode): WindowMode = if (mode == WindowMode.Fullscreen) WindowMode.Maximized else mode

    /**
     * The mode to save for a window that is in [current]. A full-screen window is saved as the mode it was in before
     * ([beforeFullscreen]), so closing the app from a full-screen player (or killing it) still opens the app as it was.
     */
    fun savedMode(current: WindowMode, beforeFullscreen: WindowMode): WindowMode =
        if (current == WindowMode.Fullscreen) openingMode(beforeFullscreen) else current

    /** The saved window, or null when nothing was saved or the mode is unknown. An unusable geometry is dropped, the mode kept. */
    fun read(prefs: PrefStore): SavedWindow? {
        val mode = prefs.string(PrefKeys.DESKTOP_WINDOW_MODE)
            ?.let { text -> WindowMode.entries.firstOrNull { it.name == text } }
            ?: return null
        val geometry = storedGeometry(prefs)?.takeIf { usable(it) }
        return SavedWindow(openingMode(mode), geometry)
    }

    /**
     * Stores the mode, and the geometry when one is given and usable. A null geometry keeps what was stored before, so a
     * maximized or full-screen window doesn't overwrite the floating size and place.
     */
    fun write(prefs: PrefStore, mode: WindowMode, geometry: WindowGeometry?) {
        val values = mutableMapOf<String, PrefValue>(PrefKeys.DESKTOP_WINDOW_MODE to PrefValue.Text(mode.name))
        if (geometry != null && usable(geometry)) {
            values[PrefKeys.DESKTOP_WINDOW_X] = PrefValue.Real(geometry.x)
            values[PrefKeys.DESKTOP_WINDOW_Y] = PrefValue.Real(geometry.y)
            values[PrefKeys.DESKTOP_WINDOW_WIDTH] = PrefValue.Real(geometry.width)
            values[PrefKeys.DESKTOP_WINDOW_HEIGHT] = PrefValue.Real(geometry.height)
        }
        prefs.putAll(values) // one save for the whole window
    }

    private fun storedGeometry(prefs: PrefStore): WindowGeometry? {
        val x = prefs.double(PrefKeys.DESKTOP_WINDOW_X) ?: return null
        val y = prefs.double(PrefKeys.DESKTOP_WINDOW_Y) ?: return null
        val width = prefs.double(PrefKeys.DESKTOP_WINDOW_WIDTH) ?: return null
        val height = prefs.double(PrefKeys.DESKTOP_WINDOW_HEIGHT) ?: return null
        return WindowGeometry(x, y, width, height)
    }
}
