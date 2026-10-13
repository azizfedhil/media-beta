package com.mediahub.pear.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediahub.pear.data.PearJson
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.PrefStore
import com.mediahub.pear.data.ProfileKeys
import com.mediahub.pear.epochMillis
import com.mediahub.pear.model.AppleDate
import com.mediahub.pear.model.CatalogRow
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.net.attempt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray

@Serializable
enum class LibraryStatus {
    @SerialName("planToWatch") PlanToWatch,
    @SerialName("watched") Watched,
}

/** One saved title (spec: `LocalLibrary.Entry`). */
@Serializable
data class LibraryEntry(
    val item: MetaPreview,
    val status: LibraryStatus,
    /** The same title under its other id (a TMDB item saved from Explore also answers to its IMDb id). */
    val alias: String? = null,
    @Serializable(with = AppleDate::class) val added: Long,
) {
    val id: String get() = item.id
}

private val libSerializer = ListSerializer(LibraryEntry.serializer())

/**
 * The active profile's own watchlist, kept on this device (spec: `LocalLibrary`). Used by Library and the detail page
 * whenever Simkl isn't the library. Saved under `library.local` (or `.<profileId>`). Use from the UI thread.
 */
class LocalLibrary(
    private val prefs: PrefStore,
    initialProfile: String = ProfileKeys.DEFAULT_ID,
    private val now: () -> Long = ::epochMillis,
) {
    private var profileID = initialProfile

    var entries: List<LibraryEntry> by mutableStateOf(read(initialProfile))
        private set

    private fun storeKey(profile: String) = ProfileKeys.scoped(PrefKeys.LIBRARY_LOCAL, profile)

    private fun read(profile: String): List<LibraryEntry> {
        val text = prefs.jsonText(storeKey(profile)) ?: return emptyList()
        val arr = attempt { PearJson.parseToJsonElement(text) as? JsonArray } ?: return emptyList()
        return arr.mapNotNull { el -> attempt { PearJson.decodeFromJsonElement(LibraryEntry.serializer(), el) } }
    }

    /** Re-reads from storage (after a settings import, or a switch of profile). */
    fun reload(profile: String = profileID) {
        profileID = profile
        entries = read(profile)
    }

    fun load(profile: String) {
        if (profile != profileID) reload(profile)
    }

    fun entry(id: String): LibraryEntry? = entries.firstOrNull { it.item.id == id || it.alias == id }

    // MARK: Changes

    /** Adds the title, or changes its status. Either way it moves to the front of its row. */
    fun set(item: MetaPreview, status: LibraryStatus) {
        val list = entries.toMutableList()
        val i = list.indexOfFirst { it.item.id == item.id || it.alias == item.id }
        if (i >= 0) {
            val e = list.removeAt(i).copy(status = status, added = now())
            list.add(0, e)
        } else {
            list.add(0, LibraryEntry(item, status, null, now()))
        }
        entries = list
        persist()
    }

    /**
     * Applies many changes with a single save (library sync). New titles go to the front in the order given; an
     * existing title only moves when its status changes.
     */
    fun apply(changes: List<Pair<MetaPreview, LibraryStatus>>) {
        if (changes.isEmpty()) return
        val list = entries.toMutableList()
        val fresh = ArrayList<LibraryEntry>()
        val base = now()
        for ((n, change) in changes.withIndex()) {
            val (item, status) = change
            val stamp = base - n * 1000L // keeps the order the changes arrived in
            val i = list.indexOfFirst { it.item.id == item.id || it.alias == item.id }
            if (i >= 0) {
                if (list[i].status != status) list[i] = list[i].copy(status = status, added = stamp)
            } else {
                fresh.add(LibraryEntry(item, status, null, stamp))
            }
        }
        entries = fresh + list
        persist()
    }

    fun setAlias(alias: String, id: String) {
        val i = entries.indexOfFirst { it.item.id == id }
        if (i < 0 || entries[i].alias != null) return
        entries = entries.toMutableList().also { it[i] = it[i].copy(alias = alias) }
        persist()
    }

    fun remove(id: String) {
        entries = entries.filter { it.item.id != id && it.alias != id }
        persist()
    }

    /** Drops a saved title from the library without touching its watch history. */
    fun unsave(id: String) = remove(id)

    /** Called by the player when a movie finishes: only titles already saved are moved, nothing new is added. */
    fun markWatchedIfSaved(id: String) {
        val e = entry(id) ?: return
        if (e.status != LibraryStatus.Watched) set(e.item, LibraryStatus.Watched)
    }

    // MARK: Rows for the Library tab

    /** "Watching" comes from the profile's watch history; the other two are what the user saved. */
    fun rows(watching: List<MetaPreview>): List<CatalogRow> {
        val watched = entries.filter { it.status == LibraryStatus.Watched }
        val inProgress = watching.filter { w -> watched.none { it.item.id == w.id || it.alias == w.id } }
        val out = ArrayList<CatalogRow>()
        fun add(key: String, title: String, symbol: String, items: List<MetaPreview>) {
            if (items.isEmpty()) return
            // CatalogRow compares by id only, so the id carries the contents: a changed row must look like a new one.
            out.add(CatalogRow("local-$key-${items.map { it.id }.hashCode()}", title, items, symbol = symbol))
        }
        add("watching", "Watching", "eye.fill", inProgress)
        add("plan", "Plan to Watch", "bookmark.fill", entries.filter { it.status == LibraryStatus.PlanToWatch }.map { it.item })
        add("watched", "Watched", "checkmark.circle.fill", watched.map { it.item })
        return out
    }

    private fun persist() {
        prefs.setJsonText(storeKey(profileID), PearJson.encodeToString(libSerializer, entries))
    }
}
