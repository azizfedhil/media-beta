package com.mediahub.pear.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable

/**
 * A kind of title the user can switch off.
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
    val hiddenRows: Set<String> = emptySet(),
    val addedRows: Set<String> = emptySet(),
    val filterSearch: Boolean = false,
) {
    val hasFilters: Boolean get() = hiddenCategories.isNotEmpty()
    val hidesAnything: Boolean get() = hiddenCategories.isNotEmpty() || hiddenRows.isNotEmpty() || filterSearch
}

@Serializable
private class ContentRulesJson(
    val categories: List<String> = emptyList(),
    val rows: List<String> = emptyList(),
    val added: List<String> = emptyList(),
    val filterSearch: Boolean = false,
)

/**
 * Content settings of the active profile (spec: `ContentPrefs`).
 */
class ContentPrefs(private val prefs: PrefStore, initialProfile: String = ProfileKeys.DEFAULT_ID) {
    private var profileID = initialProfile
    var rules: ContentRules by mutableStateOf(read(initialProfile))
        private set

    fun load(profile: String) {
        if (profile == profileID) return
        profileID = profile
        rules = read(profile)
    }

    fun reload() { rules = read(profileID) }

    fun setHidden(category: ContentCategory, hidden: Boolean) {
        rules = rules.copy(hiddenCategories = if (hidden) rules.hiddenCategories + category else rules.hiddenCategories - category)
        save()
    }

    fun setRowHidden(key: String, hidden: Boolean) {
        rules = when {
            hidden && key in rules.addedRows -> rules.copy(addedRows = rules.addedRows - key)
            hidden -> rules.copy(hiddenRows = rules.hiddenRows + key)
            else -> rules.copy(hiddenRows = rules.hiddenRows - key)
        }
        save()
    }

    fun setRowAdded(key: String, added: Boolean) {
        setRowsAdded(listOf(key), added)
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

    fun reset() {
        rules = ContentRules(addedRows = rules.addedRows)
        save()
    }

    private fun key(profile: String) = ProfileKeys.scoped(PrefKeys.CONTENT_PREFS, profile)

    private fun read(profile: String): ContentRules {
        val dto = prefs.jsonText(key(profile))
            ?.let { runCatching { PearJson.decodeFromString(ContentRulesJson.serializer(), it) }.getOrNull() }
            ?: return ContentRules()
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