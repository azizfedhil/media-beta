package com.mediahub.pear.home

import kotlin.test.Test
import kotlin.test.assertEquals

class LoadingTextTest {
    @Test
    fun namesTheAddonsThatAreStillLoading() {
        assertEquals("", loadingText(emptyList()))
        assertEquals("Loading Cinemeta…", loadingText(listOf("Cinemeta")))
        assertEquals("Loading A and B…", loadingText(listOf("A", "B")))
        assertEquals("Loading A and 2 more add-ons…", loadingText(listOf("A", "B", "C")))
    }
}
