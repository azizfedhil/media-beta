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
 * Pushes the local library to Pear.pair now, when that is this profile's library and this device is signed in. The local
 * write already marks the library for the next push; this makes the push immediate, as iOS does.
 */
private fun AppData.pushPairLibrary() {
    if (pairStore.isSignedIn && libraryPrefs.source(simkl.isConnected) == LibrarySource.PearPair) pairSync.syncNow()
}

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

/**
 * Saves the title as Plan to Watch in the active library. [alias] is the title's other id, when the caller knows it. Returns
 * whether the save went through.
 */
suspend fun AppData.addToActiveLibrary(item: MetaPreview, alias: String? = null): Boolean {
    if (!simklIsLibrary()) {
        val isNew = library.entry(item.id) == null
        library.set(item, LibraryStatus.PlanToWatch)
        // A title saved for the first time also answers to its other id, as iOS's saveLocal records it.
        if (isNew) alias?.let { if (it != item.id) library.setAlias(it, item.id) }
        pushPairLibrary()
        return true
    }
    val entry = LibrarySync.add(item, listOfNotNull(alias), "plantowatch")
    if (entry == null) {
        PearLog.w(LIBRARY_TAG, "This title has no id Simkl can read, so it was not added.")
        return false
    }
    return if (simkl.add(listOf(entry))) {
        simkl.sync(force = true) // the Library tab should show it at once
        true
    } else {
        PearLog.w(LIBRARY_TAG, "Simkl did not accept the title, so it was not added.")
        false
    }
}

/** Takes the title out of the active library. Returns whether the removal went through. */
suspend fun AppData.removeFromActiveLibrary(item: MetaPreview, alias: String? = null): Boolean {
    if (!simklIsLibrary()) {
        library.remove(item.id)
        pushPairLibrary()
        return true
    }
    val entry = LibrarySync.add(item, listOfNotNull(alias), list = "") ?: return false
    return if (simkl.removeFromList(listOf(entry))) {
        simkl.sync(force = true)
        true
    } else {
        PearLog.w(LIBRARY_TAG, "Simkl did not remove the title, so it stays in the library.")
        false
    }
}
