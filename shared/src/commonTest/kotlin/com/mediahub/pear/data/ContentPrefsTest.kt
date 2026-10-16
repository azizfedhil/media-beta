package com.mediahub.pear.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContentPrefsTest {
    @Test
    fun savesPerProfileInTheSwiftShape() {
        val prefs = PrefStore()
        val c = ContentPrefs(prefs)
        c.setHidden(ContentCategory.Horror, true)
        c.setHidden(ContentCategory.Anime, true)
        c.setFilterSearch(true)
        assertEquals("""{"categories":["anime","horror"],"rows":[],"added":[],"filterSearch":true}""", prefs.jsonText("content.prefs"))
        c.load("kid")
        assertTrue(c.rules.hiddenCategories.isEmpty())
        c.setHidden(ContentCategory.Kids, true)
        assertEquals(setOf(ContentCategory.Kids), ContentPrefs(prefs, "kid").rules.hiddenCategories)
        assertEquals(setOf(ContentCategory.Anime, ContentCategory.Horror), ContentPrefs(prefs).rules.hiddenCategories)
        assertTrue(prefs.jsonText("content.prefs.kid") != null)
    }

    @Test
    fun anUnknownCategoryFromANewerBuildIsDropped() {
        val prefs = PrefStore()
        prefs.setJsonText("content.prefs", """{"categories":["horror","somethingNew"],"rows":["addon:x"],"added":[],"filterSearch":false}""")
        val r = ContentPrefs(prefs).rules
        assertEquals(setOf(ContentCategory.Horror), r.hiddenCategories)
        assertEquals(setOf("addon:x"), r.hiddenRows)
    }

    @Test
    fun hidingAnAddedRowRemovesItInsteadOfHidingIt() {
        val c = ContentPrefs(PrefStore())
        c.setRowsAdded(listOf("addon:a", "addon:b"), true)
        c.setRowHidden("addon:a", true)
        assertEquals(setOf("addon:b"), c.rules.addedRows)
        assertTrue(c.rules.hiddenRows.isEmpty())
        c.setRowHidden("builtin:trending", true)
        c.setRowsAdded(listOf("builtin:trending"), true) // adding clears the hidden flag
        assertTrue(c.rules.hiddenRows.isEmpty())
    }

    @Test
    fun resetKeepsAddedCatalogues() {
        val c = ContentPrefs(PrefStore())
        c.setRowsAdded(listOf("addon:a"), true)
        c.setHidden(ContentCategory.Soap, true)
        c.reset()
        assertEquals(ContentRules(addedRows = setOf("addon:a")), c.rules)
    }
}
