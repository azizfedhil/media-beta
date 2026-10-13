package com.mediahub.pear

import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformTest {
    @Test
    fun platformNameIsSet() {
        assertTrue(platformName.isNotBlank())
    }
}
