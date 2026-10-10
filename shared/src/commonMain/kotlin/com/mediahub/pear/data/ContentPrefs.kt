package com.mediahub.pear.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable

/**
 * A kind of title the user can switch off. The raw names are the Swift `ContentCategory` raw values, so stored
 * settings carry over. Titles, details, icons and TMDB genre ids arrive with Batch 3.4 / Phase 3.
 */
enum class ContentCategory(val raw: String) {
    Anime("anime"), Cartoons("cartoons"), Kids("kids"), Documentary("documentary"), Reality("reality"),
    TalkNews("talkNews"), Soap("soap"), Horror("horror"), Romance("romance"),
    Asian("asian"), Indian("indian"), Foreign("foreign"), Music("music"), TvMovies("tvMovies");

    val isRegion: Boolean get() = this in regions

    companion object {
        val regions = listOf(Asian, Indian, Foreign)
        fun fromRaw(raw: String): ContentCategory? = entries.firstOrNull { it.raw == raw }
    }
}

/** What one profile wants to see (spec: `ContentRules`). */
data class ContentRules(
    val hiddenCategories: Set<ContentCategory> = emptySet(),
    /** Rows switched off on Home and Explore. */
    val hiddenRows: Set<String> = emptySet(),
    /** Add-on catalogues the user put on Home (AIOMetadata-style add-ons start empty). */
    val addedRows: Set<String> = emptySet(),
    /** Search is an explicit request, so by default it isn't filtered. */
    val filterSearch: Boolean = false,
) {
    val hasFilters: Boolean get() = hiddenCategories.isNotEmpty()
    val hidesAnything: Boolean get() = hiddenCategories.isNotEmpty() || hiddenRows.isNotEmpty() || filterSearch
}

/** The Swift JSON shape: `{"categories":[],"rows":[],"added":[],"filterSearch":false}`, arrays sorted. */
@Serializable
private class ContentRulesJson(
    val categories: List<String> = emptyList(),
    val rows: List<String> = emptyList(),
    val added: List<String> = emptyList(),
    val filterSearch: Boolean = false,
)

/**
 * Content settings of the active profile (spec: `ContentPrefs`). Stored per profile under `content.prefs`
 * (first profile) or `content.prefs.<id>`. Call [load] when the active profile changes.
 */
class ContentPrefs(private val prefs: PrefStore, initialProfile: String = ProfileKeys.DEFAULT_ID) {
    private var profileID = initialProfile
    var rules: ContentRules by mutableStateOf(read(initialProfile))
        private set

    /** Switches to another profile's settings. Does nothing when already loaded. */
    fun load(profile: String) {
        if (profile == profileID) return
        profileID = profile
        rules = read(profile)
    }

    /** Re-reads from storage (after a settings import). */
    fun reload() { rules = read(profileID) }

    fun setHidden(category: ContentCategory, hidden: Boolean) {
        rules = rules.copy(hiddenCategories = if (hidden) rules.hiddenCategories + category else rules.hiddenCategories - category)
        save()
    }

    fun setRowHidden(key: String, hidden: Boolean) {
        rules = when {
            // A catalogue the user added is taken off Home rather than "hidden": it was never on by default.
            hidden && key in rules.addedRows -> rules.copy(addedRows = rules.addedRows - key)
            hidden -> rules.copy(hiddenRows = rules.hiddenRows + key)
            else -> rules.copy(hiddenRows = rules.hiddenRows - key)
        }
        save()
    }

    fun setRowsAdded(keys: List<String>, added: Boolean) {
        rules = if (added) rules.copy(addedRows = rules.addedRows + keys, hiddenRows = rules.hiddenRows - keys.toSet())
        else rules.copy(addedRows = rules.addedRows - keys.toSet())
        save()
    }

    fun setFilterSearch(on: Boolean) {
        if (rules.filterSearch == on) return
        rules = rules.copy(filterSearch = on)
        save()
    }

    /** Everything shown again. Catalogues the user added to Home stay. */
    fun reset() {
        rules = ContentRules(addedRows = rules.addedRows)
        save()
    }

    private fun key(profile: String) = ProfileKeys.scoped(PrefKeys.CONTENT_PREFS, profile)

    private fun read(profile: String): ContentRules {
        val dto = prefs.jsonText(key(profile))
            ?.let { runCatching { PearJson.decodeFromString(ContentRulesJson.serializer(), it) }.getOrNull() }
            ?: return ContentRules()
        // A category written by a newer build is dropped, never fatal (same as the Swift decoder).
        return ContentRules(
            hiddenCategories = dto.categories.mapNotNull { ContentCategory.fromRaw(it) }.toSet(),
            hiddenRows = dto.rows.toSet(),
            addedRows = dto.added.toSet(),
            filterSearch = dto.filterSearch,
        )
    }

    private fun save() {
        val r = rules
        val dto = ContentRulesJson(r.hiddenCategories.map { it.raw }.sorted(), r.hiddenRows.sorted(), r.addedRows.sorted(), r.filterSearch)
        prefs.setJsonText(key(profileID), PearJson.encodeToString(ContentRulesJson.serializer(), dto))
    }
}
