package com.mediahub.pear.theme

import androidx.compose.ui.graphics.vector.PathParser
import com.mediahub.pear.theme.icons.lucideIcons
import com.mediahub.pear.theme.icons.sfToLucide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IconMapTest {
    /** SF Symbols used by the Swift app's navigation, player and common screens. Add to it as screens are ported. */
    private val required = listOf(
        "house.fill", "safari", "safari.fill", "books.vertical", "books.vertical.fill", "gearshape", "gearshape.fill",
        "magnifyingglass", "chevron.left", "chevron.right", "chevron.up", "chevron.down", "play.fill", "pause.fill",
        "forward.end.fill", "captions.bubble", "waveform", "pip", "square.stack.3d.up.fill", "arrow.down.circle",
        "arrow.down.circle.fill", "checkmark", "checkmark.circle", "checkmark.circle.fill", "trash", "plus", "xmark",
        "ellipsis", "square.and.arrow.up", "film", "film.stack", "tv", "star.fill", "flame.fill", "sparkles",
        "puzzlepiece.extension.fill", "wifi.slash", "info.circle", "exclamationmark.triangle.fill", "pin", "pin.fill",
        "bookmark", "bookmark.fill", "eye.slash", "clock", "calendar", "link", "pencil", "person.fill", "lock.fill",
        "hare.fill", "tortoise.fill", "speaker.wave.3.fill", "speaker.slash.fill", "goforward", "gobackward", "goforward.10", "gobackward.10",
    )

    @Test
    fun everyRequiredSymbolHasAnIcon() {
        val missing = required.filter { !PearIcons.has(it) }
        assertTrue(missing.isEmpty(), "unmapped SF Symbols: $missing")
    }

    @Test
    fun everyIconParsesToDrawingCommands() {
        for (name in PearIcons.symbols) {
            val shapes = PearIcons.shapes(name)
            assertTrue(shapes.isNotEmpty(), "no parts for $name")
            for (shape in shapes) {
                assertTrue(shape.d.isNotBlank(), "empty path in $name")
                assertTrue(PathParser().parsePathString(shape.d).toNodes().isNotEmpty(), "no path nodes in $name")
            }
        }
    }

    @Test
    fun everyMappedLucideNameHasItsDrawing() {
        val missing = sfToLucide.values.distinct().filter { it !in lucideIcons }
        assertTrue(missing.isEmpty(), "Lucide names without drawing data: $missing")
    }

    @Test
    fun unknownSymbolFallsBackInsteadOfCrashing() {
        assertFalse(PearIcons.has("no.such.symbol"))
        assertTrue(PearIcons.shapes("no.such.symbol").isNotEmpty())
    }

    @Test
    fun tabSymbolsMatchTheSwiftTabs() {
        assertEquals("house.fill", com.mediahub.pear.shell.AppTab.Home.symbol)
        for (tab in com.mediahub.pear.shell.AppTab.entries) assertTrue(PearIcons.has(tab.symbol), tab.symbol)
    }
}
