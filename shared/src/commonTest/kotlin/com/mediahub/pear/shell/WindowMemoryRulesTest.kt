package com.mediahub.pear.shell

import com.mediahub.pear.data.PrefStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowMemoryRulesTest {

    private val floating = WindowGeometry(x = 120.0, y = 80.0, width = 1280.0, height = 800.0)
    private val screen = ScreenBounds(0.0, 0.0, 1920.0, 1080.0)

    @Test
    fun aNormalWindowIsUsableAndAGarbageSizeIsNot() {
        assertTrue(WindowMemory.usable(floating))
        assertFalse(WindowMemory.usable(floating.copy(width = 10.0)), "a width of 10 dp is garbage")
        assertFalse(WindowMemory.usable(floating.copy(height = Double.NaN)))
        assertFalse(WindowMemory.usable(floating.copy(x = Double.POSITIVE_INFINITY)))
    }

    @Test
    fun aWindowWhoseTitleBarIsOnAScreenIsReachable() {
        assertTrue(WindowMemory.titleBarOnScreen(floating, listOf(screen)))
    }

    @Test
    fun aWindowOnAScreenThatIsGoneIsNot() {
        val gone = floating.copy(x = 5000.0)
        assertFalse(WindowMemory.titleBarOnScreen(gone, listOf(screen)), "a window past the right edge of the only screen")
        assertTrue(WindowMemory.titleBarOnScreen(gone, listOf(screen, ScreenBounds(4000.0, 0.0, 1920.0, 1080.0))), "a second screen there")
    }

    @Test
    fun aSizeIsFittedInsideTheScreen() {
        val (w, h) = WindowMemory.fit(width = 3000.0, height = 2000.0, screen = screen)
        assertEquals(1920.0, w)
        assertEquals(1080.0, h)
    }

    @Test
    fun aSizeIsNeverFittedBelowTheSanityMinimum() {
        val (w, h) = WindowMemory.fit(width = 50.0, height = 50.0, screen = screen)
        assertEquals(WindowMemory.MIN_WIDTH, w)
        assertEquals(WindowMemory.MIN_HEIGHT, h)
    }

    @Test
    fun aFloatingWindowRoundTripsThroughTheSettings() {
        val prefs = PrefStore()
        WindowMemory.write(prefs, WindowMode.Windowed, floating)
        assertEquals(SavedWindow(WindowMode.Windowed, floating), WindowMemory.read(prefs))
    }

    @Test
    fun aMaximizedWindowKeepsTheFloatingGeometryFromBefore() {
        val prefs = PrefStore()
        WindowMemory.write(prefs, WindowMode.Windowed, floating)
        WindowMemory.write(prefs, WindowMode.Maximized, geometry = null)
        assertEquals(SavedWindow(WindowMode.Maximized, floating), WindowMemory.read(prefs))
    }

    @Test
    fun aFullScreenWindowIsNeverRestoredFullScreen() {
        // The player's full screen is not a state to reopen in: the app would start with no way out of it.
        val prefs = PrefStore()
        WindowMemory.write(prefs, WindowMode.Fullscreen, geometry = null)
        assertEquals(SavedWindow(WindowMode.Maximized, geometry = null), WindowMemory.read(prefs))
    }

    @Test
    fun nothingSavedMeansNothingRestored() {
        assertNull(WindowMemory.read(PrefStore()))
    }

    @Test
    fun anUnusableGeometryIsDroppedButTheModeKept() {
        val prefs = PrefStore()
        WindowMemory.write(prefs, WindowMode.Windowed, floating.copy(width = 3.0))
        assertEquals(SavedWindow(WindowMode.Windowed, geometry = null), WindowMemory.read(prefs))
    }

    @Test
    fun aWindowNeverOpensFullScreen() {
        assertEquals(WindowMode.Maximized, WindowMemory.openingMode(WindowMode.Fullscreen))
        assertEquals(WindowMode.Maximized, WindowMemory.openingMode(WindowMode.Maximized))
        assertEquals(WindowMode.Windowed, WindowMemory.openingMode(WindowMode.Windowed))
    }

    @Test
    fun aFullScreenWindowIsSavedAsTheModeItCameFrom() {
        assertEquals(WindowMode.Windowed, WindowMemory.savedMode(WindowMode.Fullscreen, beforeFullscreen = WindowMode.Windowed))
        assertEquals(WindowMode.Maximized, WindowMemory.savedMode(WindowMode.Fullscreen, beforeFullscreen = WindowMode.Maximized))
        assertEquals(WindowMode.Maximized, WindowMemory.savedMode(WindowMode.Maximized, beforeFullscreen = WindowMode.Windowed))
    }
}
