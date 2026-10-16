package com.mediahub.pear.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediahub.pear.data.PearJson
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.data.ProfileKeys
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.net.attempt
import kotlinx.serialization.builtins.ListSerializer

private val pinsSerializer = ListSerializer(MetaPreview.serializer())

/**
 * The titles pinned to the sidebar, per profile (`sidebar.pins`, or `.<profileId>` for the others). The newest pin is last.
 * Like the other stores here, use it from the UI thread. A pin that cannot be read is skipped, never thrown.
 */
class SidebarPins(private val prefs: PrefStore, initialProfile: String = ProfileKeys.DEFAULT_ID) {
    private var profileID = initialProfile

    var items: List<MetaPreview> by mutableStateOf(read(initialProfile))
        private set

    private fun storeKey(profile: String) = ProfileKeys.scoped(PrefKeys.SIDEBAR_PINS, profile)

    private fun read(profile: String): List<MetaPreview> {
        val text = prefs.jsonText(storeKey(profile)) ?: return emptyList()
        return attempt { PearJson.decodeFromString(pinsSerializer, text) } ?: emptyList()
    }

    fun isPinned(id: String): Boolean = items.any { it.id == id }

    /** Pins the title. Beyond [MAX_PINS] the new pin is ignored. */
    fun pin(item: MetaPreview) {
        if (isPinned(item.id) || items.size >= MAX_PINS) return
        items = items + item
        save()
    }

    fun unpin(id: String) {
        if (!isPinned(id)) return
        items = items.filterNot { it.id == id }
        save()
    }

    fun toggle(item: MetaPreview) {
        if (isPinned(item.id)) unpin(item.id) else pin(item)
    }

    /** Switches to another profile's pins. */
    fun load(profile: String) {
        if (profile != profileID) reload(profile)
    }

    /** Re-reads from storage (after a settings import, or a switch of profile). */
    fun reload(profile: String = profileID) {
        profileID = profile
        items = read(profile)
    }

    private fun save() {
        prefs.setJsonText(storeKey(profileID), PearJson.encodeToString(pinsSerializer, items))
    }

    companion object {
        const val MAX_PINS = 30
    }
}
