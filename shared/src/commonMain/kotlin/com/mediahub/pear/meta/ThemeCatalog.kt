package com.mediahub.pear.meta

import com.mediahub.pear.addons.AddonClient
import com.mediahub.pear.data.ContentRules
import com.mediahub.pear.data.isRowHidden
import com.mediahub.pear.model.Addon
import com.mediahub.pear.model.ThemedTitle

/** Editorial theme collection metadata (spec: `Themerows.swift`). */
data class ThemeDef(
    val title: String,
    val keywords: List<String>,
    val language: String? = null,
)

data class ThemeRow(
    val id: String,
    val title: String,
    val items: List<ThemedTitle>,
)

object ThemeCatalog {
    const val HOME_ROWS = 5
    const val HOME_CANDIDATES = 6
    const val EXPLORE_ROWS = 3
    const val EXPLORE_CANDIDATES = 4
    private const val PER_DAY = HOME_CANDIDATES + EXPLORE_CANDIDATES

    val all: List<ThemeDef> = listOf(
        ThemeDef("Small-Town Secrets", listOf("small town")),
        ThemeDef("Cold Case Obsessions", listOf("cold case", "serial killer")),
        ThemeDef("Whodunit Weekends", listOf("whodunit", "murder mystery")),
        ThemeDef("Missing Without a Trace", listOf("missing person", "kidnapping")),
        ThemeDef("Heists & Con Artists", listOf("heist", "con artist")),
        ThemeDef("Mob Life", listOf("mafia", "organized crime")),
        ThemeDef("Behind Bars", listOf("prison", "prison escape")),
        ThemeDef("Undercover Operations", listOf("undercover")),
        ThemeDef("Hitmen & Assassins", listOf("hitman", "assassin")),
        ThemeDef("Mind Games", listOf("psychological thriller")),
        ThemeDef("Twist Endings", listOf("twist ending")),
        ThemeDef("Cult Fascinations", listOf("cult")),
        ThemeDef("Follow the Money", listOf("white collar crime", "fraud")),
        ThemeDef("Digital Danger", listOf("hacker", "cybercrime")),
        ThemeDef("Spy Games", listOf("spy", "espionage")),
        ThemeDef("Conspiracies & Cover-Ups", listOf("conspiracy", "cover-up")),
        ThemeDef("Courtroom Drama", listOf("courtroom", "lawyer")),
        ThemeDef("Revenge Served Cold", listOf("revenge")),
        ThemeDef("Beyond the Stars", listOf("space travel", "outer space")),
        ThemeDef("Time After Time", listOf("time travel", "time loop")),
        ThemeDef("Parallel Worlds", listOf("parallel universe", "multiverse")),
        ThemeDef("Machines Rising", listOf("artificial intelligence", "robot")),
        ThemeDef("Cyberpunk Futures", listOf("cyberpunk", "virtual reality")),
        ThemeDef("After the End", listOf("post-apocalyptic", "dystopia")),
        ThemeDef("Epic Fantasy Worlds", listOf("dragon", "sword and sorcery")),
        ThemeDef("Wizards & Witchcraft", listOf("witch", "wizard")),
        ThemeDef("Myths & Legends", listOf("mythology", "greek mythology")),
        ThemeDef("Superhero Origins", listOf("superhero", "super power")),
        ThemeDef("Lost Worlds", listOf("dinosaur", "lost world")),
        ThemeDef("Outbreak", listOf("pandemic", "virus")),
        ThemeDef("Ghost Stories", listOf("ghost", "haunted house")),
        ThemeDef("Creature Features", listOf("monster", "giant monster")),
        ThemeDef("Undead Obsessions", listOf("zombie", "vampire")),
        ThemeDef("Behind Closed Doors", listOf("dysfunctional family", "family secrets")),
        ThemeDef("Found Family", listOf("found family")),
        ThemeDef("Coming of Age", listOf("coming of age")),
        ThemeDef("Forbidden Love", listOf("forbidden love", "love triangle")),
        ThemeDef("Wedding Season", listOf("wedding")),
        ThemeDef("Road Trip Therapy", listOf("road trip")),
        ThemeDef("Boarding School Intrigue", listOf("boarding school")),
        ThemeDef("Anti-Heroes", listOf("antihero")),
        ThemeDef("Underdog Stories", listOf("underdog", "sports")),
        ThemeDef("Code Blue", listOf("hospital", "doctor")),
        ThemeDef("Stop the Presses", listOf("journalist", "newspaper")),
        ThemeDef("Politics & Power", listOf("political intrigue", "corruption")),
        ThemeDef("Crowns & Conspiracies", listOf("monarchy", "royal family")),
        ThemeDef("Kitchen Confidential", listOf("chef", "restaurant")),
        ThemeDef("Rock & Roll Stories", listOf("rock band", "musician")),
        ThemeDef("Wall Street Wolves", listOf("wall street", "stock market")),
        ThemeDef("Based on True Stories", listOf("based on true story")),
        ThemeDef("War Stories", listOf("world war ii")),
        ThemeDef("Cold War Tension", listOf("cold war")),
        ThemeDef("Medieval Intrigue", listOf("medieval", "knight")),
        ThemeDef("Samurai & Shinobi", listOf("samurai", "ninja")),
        ThemeDef("Survival Against the Odds", listOf("survival", "wilderness")),
        ThemeDef("Stranded", listOf("island", "shipwreck")),
        ThemeDef("High Seas", listOf("pirate", "sailing")),
        ThemeDef("Treasure Hunters", listOf("treasure hunt", "treasure")),
        ThemeDef("Disaster Zone", listOf("natural disaster")),
        ThemeDef("Martial Arts Mastery", listOf("martial arts", "kung fu")),
        ThemeDef("K-Drama Fever", emptyList(), language = "ko"),
        ThemeDef("Spanish-Language Standouts", emptyList(), language = "es"),
        ThemeDef("French Connection", emptyList(), language = "fr"),
        ThemeDef("Made in Germany", emptyList(), language = "de"),
        ThemeDef("Turkish Dramas", emptyList(), language = "tr"),
        ThemeDef("Bollywood & Beyond", emptyList(), language = "hi"),
    )

    fun today(count: Int, offset: Int = 0, dayNumber: Int): List<ThemeDef> {
        val n = all.size
        var position = dayNumber * PER_DAY + offset
        val picked = ArrayList<ThemeDef>()
        for (i in 0 until count) {
            picked.add(all[(position + i) % n])
        }
        return picked
    }

    suspend fun load(
        tmdb: TmdbClient,
        count: Int,
        offset: Int = 0,
        limit: Int = 8,
        show: Int = HOME_ROWS,
        rules: ContentRules = ContentRules(),
        dayNumber: Int,
        enrichWith: Addon? = null,
        addonClient: AddonClient? = null,
    ): List<ThemeRow> {
        if (!tmdb.hasKey || rules.isRowHidden("builtin:themes")) return emptyList()

        val candidates = today(count, offset, dayNumber)
        val loaded = ArrayList<ThemeRow>()

        for (t in candidates) {
            val items = tmdb.themed(keywords = t.keywords, language = t.language, limit = limit, rules = rules)
            if (items.size >= 3) {
                var rowItems = items
                if (enrichWith != null && addonClient != null) {
                    rowItems = items.map { title ->
                        val extras = addonClient.metaExtras(enrichWith, title.item.type, title.item.id)
                        val logo = extras.logo ?: title.item.logo
                        val genres = extras.genres.takeIf { it.isNotEmpty() }?.take(2) ?: title.genres
                        ThemedTitle(title.item.withLogo(logo), genres)
                    }
                }
                loaded.add(ThemeRow("theme-${t.title}", t.title, rowItems))
                if (loaded.size >= show) break
            }
        }
        return loaded
    }
}