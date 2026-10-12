package com.mediahub.pear.player

import com.mediahub.pear.player.ui.PillItem
import com.mediahub.pear.player.ui.PillLayout
import com.mediahub.pear.player.ui.PillMode
import com.mediahub.pear.player.ui.pillLayout
import com.mediahub.pear.player.ui.pillMode
import com.mediahub.pear.player.ui.pillWindowSlots
import kotlin.test.Test
import kotlin.test.assertEquals

class PillItemsTest {
    private val all = PillItem.entries.toSet()

    @Test
    fun defaultPinnedEncodesInDeclarationOrder() {
        assertEquals("speed,volume,pip,subtitles", PillItem.encodePinned(PillItem.defaultPinned))
    }

    @Test
    fun emptyOrUnknownPinnedFallsBackToDefaults() {
        assertEquals(PillItem.defaultPinned, PillItem.decodePinned(null))
        assertEquals(PillItem.defaultPinned, PillItem.decodePinned(""))
        assertEquals(PillItem.defaultPinned, PillItem.decodePinned("nonsense,also-nonsense"))
    }

    @Test
    fun pinnedKeepsTheFirstFourNamesLikeIOS() {
        assertEquals(
            setOf(PillItem.Volume, PillItem.Pip, PillItem.Subtitles, PillItem.Speed),
            PillItem.decodePinned("volume,pip,subtitles,speed,audio"),
        )
        // Swift takes the first four names, repeats included, so a repeat leaves the set with three.
        assertEquals(
            setOf(PillItem.Volume, PillItem.Pip, PillItem.Subtitles),
            PillItem.decodePinned("volume,volume,pip,subtitles,speed,audio"),
        )
    }

    @Test
    fun orderKeepsSavedItemsFirstAndAddsTheRest() {
        assertEquals(
            listOf(
                PillItem.Sources, PillItem.Speed, PillItem.Episodes, PillItem.Volume,
                PillItem.Pip, PillItem.Subtitles, PillItem.Audio, PillItem.Next,
            ),
            PillItem.decodeOrder("sources,speed"),
        )
    }

    @Test
    fun orderIgnoresUnknownNamesAndRepeats() {
        assertEquals(PillItem.defaultOrder, PillItem.decodeOrder(""))
        assertEquals(
            listOf(
                PillItem.Speed, PillItem.Episodes, PillItem.Sources, PillItem.Volume,
                PillItem.Pip, PillItem.Subtitles, PillItem.Audio, PillItem.Next,
            ),
            PillItem.decodeOrder("speed,speed,bogus"),
        )
    }

    @Test
    fun orderRoundTrips() {
        val order = listOf(PillItem.Next, PillItem.Volume, PillItem.Speed) +
            PillItem.defaultOrder.filter { it !in setOf(PillItem.Next, PillItem.Volume, PillItem.Speed) }
        assertEquals(order, PillItem.decodeOrder(PillItem.encodeOrder(order)))
    }

    @Test
    fun noFoldingUpToFourButtons() {
        val available = setOf(PillItem.Speed, PillItem.Sources, PillItem.Volume)
        val layout = pillLayout(PillItem.defaultOrder, PillItem.defaultPinned, available)
        assertEquals(PillLayout(listOf(PillItem.Speed, PillItem.Sources, PillItem.Volume), 0), layout)
    }

    @Test
    fun foldingPutsUnpinnedFirstAndKeepsEachGroupInOrder() {
        val available = setOf(PillItem.Speed, PillItem.Sources, PillItem.Volume, PillItem.Pip, PillItem.Subtitles, PillItem.Audio)
        val layout = pillLayout(PillItem.defaultOrder, PillItem.defaultPinned, available)
        assertEquals(
            PillLayout(
                listOf(PillItem.Sources, PillItem.Audio, PillItem.Speed, PillItem.Volume, PillItem.Pip, PillItem.Subtitles),
                2,
            ),
            layout,
        )
    }

    @Test
    fun pinnedItemsThatDoNotExistFallBackToDefaults() {
        val available = setOf(PillItem.Speed, PillItem.Sources, PillItem.Volume, PillItem.Pip, PillItem.Subtitles, PillItem.Audio)
        val fromNext = pillLayout(PillItem.defaultOrder, setOf(PillItem.Next), available)
        val fromDefaults = pillLayout(PillItem.defaultOrder, PillItem.defaultPinned, available)
        assertEquals(fromDefaults, fromNext)
    }

    @Test
    fun pinningChangesWhatFoldsOnlyWhenFolding() {
        val available = all - setOf(PillItem.Episodes, PillItem.Next)
        val pinned = setOf(PillItem.Audio, PillItem.Sources, PillItem.Speed, PillItem.Volume)
        val layout = pillLayout(PillItem.defaultOrder, pinned, available)
        assertEquals(listOf(PillItem.Pip, PillItem.Subtitles), layout.items.take(layout.folded))
    }

    @Test
    fun windowWidthCountsPinnedPlusChevronWhenFolded() {
        val layout = PillLayout(
            listOf(PillItem.Sources, PillItem.Audio, PillItem.Speed, PillItem.Volume, PillItem.Pip, PillItem.Subtitles),
            2,
        )
        assertEquals(5, pillWindowSlots(layout, PillMode.Folded))
        assertEquals(7, pillWindowSlots(layout, PillMode.Unfolded))
        assertEquals(5, pillWindowSlots(layout, PillMode.Volume))
    }

    @Test
    fun windowWidthOfAnUnfoldablePillIsItsItems() {
        val layout = PillLayout(listOf(PillItem.Speed, PillItem.Sources, PillItem.Volume), 0)
        assertEquals(3, pillWindowSlots(layout, PillMode.Folded))
        assertEquals(3, pillWindowSlots(layout, PillMode.Unfolded))
    }

    @Test
    fun modeFollowsTheVolumeSliderThenTheChevron() {
        assertEquals(PillMode.Volume, pillMode(expanded = true, volumeOpen = true, folded = 2))
        assertEquals(PillMode.Volume, pillMode(expanded = false, volumeOpen = true, folded = 0))
        assertEquals(PillMode.Unfolded, pillMode(expanded = true, volumeOpen = false, folded = 2))
        assertEquals(PillMode.Folded, pillMode(expanded = true, volumeOpen = false, folded = 0))
        assertEquals(PillMode.Folded, pillMode(expanded = false, volumeOpen = false, folded = 2))
    }
}
