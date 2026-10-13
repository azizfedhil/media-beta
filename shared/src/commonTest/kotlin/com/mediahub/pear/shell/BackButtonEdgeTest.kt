package com.mediahub.pear.shell

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackButtonEdgeTest {

    @Test
    fun aPressCountsOnceAndAHoldDoesNotRepeat() {
        val edge = BackButtonEdge()
        assertFalse(edge.pressed(false))
        assertTrue(edge.pressed(true), "the press itself")
        assertFalse(edge.pressed(true), "still held: no second back")
        assertFalse(edge.pressed(true), "still held: no third back")
        assertFalse(edge.pressed(false), "let go")
    }

    @Test
    fun eachNewPressCountsAgain() {
        val edge = BackButtonEdge()
        assertTrue(edge.pressed(true))
        assertFalse(edge.pressed(false))
        assertTrue(edge.pressed(true), "a second press after letting go")
    }
}
