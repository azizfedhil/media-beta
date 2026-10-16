package com.mediahub.pear.shell

import kotlin.test.Test
import kotlin.test.assertEquals

class AppExitTest {
    @Test
    fun hooksRunNewestFirstAndARemovedHookDoesNotRun() {
        val ran = mutableListOf<String>()
        val a = AppExit.register { ran.add("a") }
        val b = AppExit.register { ran.add("b") }
        val c = AppExit.register { ran.add("c") }
        b() // the screen went away
        AppExit.runAll()
        assertEquals(listOf("c", "a"), ran)
        a(); c()
        ran.clear()
        AppExit.runAll()
        assertEquals(emptyList(), ran)
    }

    @Test
    fun aFailingHookDoesNotStopTheOthers() {
        val ran = mutableListOf<String>()
        val first = AppExit.register { ran.add("first") }
        val boom = AppExit.register { error("broken") }
        AppExit.runAll()
        assertEquals(listOf("first"), ran)
        first(); boom()
    }
}
