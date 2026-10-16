package com.mediahub.pear.player

import com.mediahub.pear.player.ui.PillItem
import com.mediahub.pear.player.ui.desktopPillItems
import com.mediahub.pear.player.ui.pillItemsAvailable
import com.mediahub.pear.player.ui.pillLayout
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopPillTest {
    @Test
    fun theDesktopButtonsAreSpeedSourcesVolumeAndDisplay() {
        assertEquals(setOf(PillItem.Speed, PillItem.Sources, PillItem.Volume, PillItem.Display), desktopPillItems)
    }

    @Test
    fun aMovieHasFourCountedButtonsAndNothingFolds() {
        val layout = pillLayout(PillItem.defaultOrder, PillItem.defaultPinned, pillItemsAvailable(hasNext = false))
        assertEquals(0, layout.folded)
    }

    @Test
    fun anEpisodeHasFiveCountedButtonsSoTheExtrasFoldAway() {
        val layout = pillLayout(PillItem.defaultOrder, PillItem.defaultPinned, pillItemsAvailable(hasNext = true))
        assertEquals(3, layout.folded)
        assertEquals(listOf(PillItem.Sources, PillItem.Next, PillItem.Display), layout.items.take(3))
        assertEquals(listOf(PillItem.Speed, PillItem.Volume), layout.items.drop(3))
    }
}
