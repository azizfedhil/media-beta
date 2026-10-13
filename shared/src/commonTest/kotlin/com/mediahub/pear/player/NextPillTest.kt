package com.mediahub.pear.player

import com.mediahub.pear.player.ui.PillItem
import com.mediahub.pear.player.ui.desktopPillItems
import com.mediahub.pear.player.ui.pillItemsAvailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NextPillTest {
    @Test
    fun nextJoinsThePillOnlyWhileThereIsAnEpisodeAfterThisOne() {
        assertFalse(PillItem.Next in pillItemsAvailable(hasNext = false))
        assertTrue(PillItem.Next in pillItemsAvailable(hasNext = true))
        assertEquals(desktopPillItems, pillItemsAvailable(hasNext = false))
        assertEquals(desktopPillItems + PillItem.Next, pillItemsAvailable(hasNext = true))
    }
}
