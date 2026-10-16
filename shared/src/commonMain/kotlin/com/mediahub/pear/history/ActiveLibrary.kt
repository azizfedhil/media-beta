package com.mediahub.pear.history

import com.mediahub.pear.data.AppData
import com.mediahub.pear.log.PearLog
import com.mediahub.pear.model.MetaPreview

private const val LIBRARY_TAG = "Library"

/*
 * Saving and reading the library a title is in, whichever library the profile uses. The detail page and the title menu both
 * go through these, so a title shows as saved in the same places.
 *
 * A connected Simkl is the library until the profile picks "This device" (LibraryPrefs.source). The Library tab then shows
 * Simkl's lists, so a save has to go to Simkl as well: a save only to the local library never shows there.
 */

private fun AppData.simklIsLibrary(): Boolean = libraryPrefs.usesSimkl(simkl.isConnected)

/**
 * Whether the title is in the library the profile uses right now: one of Simkl's lists, or the local library. [alias] is the
 * title's other id, such as its IMDb id on a TMDB page. Both sources are observable state, so a button that reads this
 * redraws when the list changes.
 */
fun AppData.isInActiveLibrary(id: String, alias: String? = null): Boolean {
    if (!simklIsLibrary()) return library.entry(id) != null
    val ids = setOfNotNull(id, alias)
    return simkl.library.any { row -> row.items.any { it.id in ids } }
}

/** Saves the title as Plan to Watch in the active library. */
suspend fun AppData.addToActiveLibrary(item: MetaPreview, alias: String? = null) {
    if (!simklIsLibrary()) {
        library.set(item, LibraryStatus.PlanToWatch)
        return
    }
    val entry = LibrarySync.add(item, listOfNotNull(alias), "plantowatch")
    if (entry == null) {
        PearLog.w(LIBRARY_TAG, "This title has no id Simkl can read, so it was not added.")
        return
    }
    if (simkl.add(listOf(entry))) {
        simkl.sync(force = true) // the Library tab should show it at once
    } else {
        PearLog.w(LIBRARY_TAG, "Simkl did not accept the title, so it was not added.")
    }
}

/** Takes the title out of the active library. */
suspend fun AppData.removeFromActiveLibrary(item: MetaPreview, alias: String? = null) {
    if (!simklIsLibrary()) {
        library.remove(item.id)
        return
    }
    val entry = LibrarySync.add(item, listOfNotNull(alias), list = "") ?: return
    if (simkl.removeFromList(listOf(entry))) {
        simkl.sync(force = true)
    } else {
        PearLog.w(LIBRARY_TAG, "Simkl did not remove the title, so it stays in the library.")
    }
}
