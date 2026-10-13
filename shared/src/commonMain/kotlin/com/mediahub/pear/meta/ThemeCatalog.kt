package com.mediahub.pear.meta

import com.mediahub.pear.addons.AddonClient
import com.mediahub.pear.addons.MetaExtras
import com.mediahub.pear.data.ContentRules
import com.mediahub.pear.data.isRowHidden
import com.mediahub.pear.glass.PowerStatus
import com.mediahub.pear.model.Addon
import com.mediahub.pear.model.ThemedTitle
import com.mediahub.pear.net.Http
import com.mediahub.pear.net.attempt
import com.mediahub.pear.net.loadRemoteBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /**
     * The rows as TMDB has them, with no add-on involved. This is phase 1 of [loadProgressive] on its own, for callers
     * that want the whole list at once. Home and Explore use [loadProgressive].
     */
    suspend fun load(
        tmdb: TmdbClient,
        count: Int,
        offset: Int = 0,
        limit: Int = 8,
        show: Int = HOME_ROWS,
        rules: ContentRules = ContentRules(),
        dayNumber: Int,
    ): List<ThemeRow> {
        if (!tmdb.hasKey || rules.isRowHidden("builtin:themes")) return emptyList()
        val done = ThemeLoader.loadRows(
            candidates = today(count, offset, dayNumber),
            show = show,
            fetch = { themedTitles(tmdb, it, limit, rules) },
            update = {},
        )
        return ThemeLoader.visible(done, show)
    }

    /**
     * Loads the theme rows for Home or Explore and reports them through [update] as they land (spec: `ThemeCatalog.load`).
     *
     * Phase 1 fetches every candidate from TMDB at once; this function returns when that is done, so the rows never wait
     * for the add-on. Phase 2 is the add-on enrichment ([ThemeEnrichment]): it runs in [enrichScope], which the caller
     * owns, and reports changed rows as they land. It is skipped without an add-on, and in Low Power Mode, where the
     * decorative extras aren't worth the requests.
     */
    suspend fun loadProgressive(
        tmdb: TmdbClient,
        count: Int,
        offset: Int = 0,
        limit: Int = 8,
        show: Int = HOME_ROWS,
        rules: ContentRules = ContentRules(),
        dayNumber: Int,
        http: Http,
        enrichWith: Addon? = null,
        addonClient: AddonClient? = null,
        enrichScope: CoroutineScope,
        lowPower: () -> Boolean = { PowerStatus.lowBattery },
        update: (List<ThemeRow>) -> Unit,
    ) {
        if (!tmdb.hasKey || rules.isRowHidden("builtin:themes")) {
            update(emptyList())
            return
        }
        val done = ThemeLoader.loadRows(
            candidates = today(count, offset, dayNumber),
            show = show,
            fetch = { themedTitles(tmdb, it, limit, rules) },
            update = update,
        )

        val addon = enrichWith ?: return
        val client = addonClient ?: return
        if (lowPower()) return
        val lookups = ThemeEnrichment.Lookups(
            provides = { type, id -> addon.provides("meta", type, id) },
            metaExtras = { type, id -> client.metaExtras(addon, type, id) },
            imdbId = { tmdbId, type -> tmdb.imdbId(tmdbId, type) },
            // Off enrichScope's thread: a logo check decodes an image, which is not main-thread work. Tests pass a
            // scope backed by the virtual-time scheduler, so advanceUntilIdle() still waits for every lookup.
            logoLoads = { url -> withContext(Dispatchers.Default) { loadRemoteBitmap(http, url) != null } },
        )
        enrichScope.launch {
            ThemeLoader.enrichRows(
                done = done,
                show = show,
                enrich = { row -> ThemeEnrichment.enrich(row, lookups) },
                update = update,
            )
        }
    }

    private suspend fun themedTitles(tmdb: TmdbClient, theme: ThemeDef, limit: Int, rules: ContentRules): List<ThemedTitle> =
        tmdb.themed(keywords = theme.keywords, language = theme.language, limit = limit, rules = rules)
}

// Theme loading in its two phases. TMDB and add-on calls arrive as parameters, so the rules are unit-tested directly.

/** The phases behind [ThemeCatalog.loadProgressive]. */
internal object ThemeLoader {
    /** A theme needs this many titles to make a row. */
    private const val MIN_TITLES = 3

    /**
     * Phase 1. Fetches every candidate at once and reports the rows to show after each one lands, in theme order. A theme
     * that fails, or has too few titles, is skipped. Returns the rows found, keyed by theme index, for phase 2.
     */
    suspend fun loadRows(
        candidates: List<ThemeDef>,
        show: Int,
        fetch: suspend (ThemeDef) -> List<ThemedTitle>,
        update: (List<ThemeRow>) -> Unit,
    ): MutableMap<Int, ThemeRow> {
        val done = HashMap<Int, ThemeRow>()
        coroutineScope {
            val landed = Channel<Pair<Int, ThemeRow?>>(Channel.UNLIMITED)
            candidates.forEachIndexed { index, theme ->
                launch {
                    val items = attempt { fetch(theme) } ?: emptyList()
                    val row = items.takeIf { it.size >= MIN_TITLES }?.let { ThemeRow("theme-${theme.title}", theme.title, it) }
                    landed.send(index to row)
                }
            }
            repeat(candidates.size) {
                val (index, row) = landed.receive()
                if (row != null) {
                    done[index] = row
                    update(visible(done, show))
                }
            }
        }
        update(visible(done, show))
        return done
    }

    /** The first [show] rows that have data, in theme order. */
    fun visible(done: Map<Int, ThemeRow>, show: Int): List<ThemeRow> =
        done.keys.sorted().take(show).map { done.getValue(it) }

    /**
     * Phase 2. Enriches the shown rows (the first [show] in [done]) concurrently, and reports each row as its enrichment
     * lands, but only when something in it changed. Runs after phase 1, so nothing else is writing to [done].
     */
    suspend fun enrichRows(
        done: MutableMap<Int, ThemeRow>,
        show: Int,
        enrich: suspend (ThemeRow) -> ThemeRow,
        update: (List<ThemeRow>) -> Unit,
    ) {
        val shown = done.keys.sorted().take(show)
        coroutineScope {
            val enriched = Channel<Pair<Int, ThemeRow>>(Channel.UNLIMITED)
            for (index in shown) {
                val row = done.getValue(index)
                launch { enriched.send(index to enrich(row)) }
            }
            repeat(shown.size) {
                val (index, row) = enriched.receive()
                currentCoroutineContext().ensureActive()
                if (row != done.getValue(index)) {
                    done[index] = row
                    update(visible(done, show))
                }
            }
        }
    }
}

/**
 * Fills theme rows with what the user's AIOMetadata add-on knows about each title (spec: `ThemeEnrichment`).
 *
 * - Genres under the logo come from the add-on's first two, so they match the rest of its metadata. Without any, TMDB's
 *   stay.
 * - The title logo is the add-on's, but only if that image really loads, so a dead address can't hide the TMDB logo. It
 *   takes priority over the TMDB / TVDB logo for this session. It is never saved anywhere.
 * - Only the first [LIMIT] titles of a row are looked up. A title the add-on doesn't know, or whose lookup fails, keeps its
 *   TMDB data.
 *
 * The network calls come in through [Lookups], so the rules can be tested without a network.
 */
internal object ThemeEnrichment {
    const val LIMIT = 8
    private const val GENRES = 2

    /** The calls enrichment makes. Each one may fail; a failure leaves that title as it was. */
    class Lookups(
        /** Whether the add-on serves `meta` for this title (`Addon.provides("meta", ...)`). */
        val provides: (type: String, id: String) -> Boolean,
        /** The add-on's genres and logo for a title, or [MetaExtras.Empty]. */
        val metaExtras: suspend (type: String, id: String) -> MetaExtras,
        /** The IMDb id of a TMDB title, or null when TMDB doesn't know it. */
        val imdbId: suspend (tmdbId: Int, type: String) -> String?,
        /** Whether the image at this address loads. */
        val logoLoads: suspend (url: String) -> Boolean,
    )

    /** The row with its first [LIMIT] titles enriched, looked up concurrently. Titles past the limit are untouched. */
    suspend fun enrich(row: ThemeRow, lookups: Lookups): ThemeRow = coroutineScope {
        val head = row.items.take(LIMIT).map { title -> async { enrichTitle(title, lookups) } }.awaitAll()
        row.copy(items = head + row.items.drop(LIMIT))
    }

    private suspend fun enrichTitle(title: ThemedTitle, lookups: Lookups): ThemedTitle {
        val item = title.item
        // Most add-ons that serve metadata take TMDB ids. One that only takes IMDb ids gets the translated id instead.
        var id = item.id
        if (!lookups.provides(item.type, id)) {
            val tmdbId = id.takeIf { it.startsWith("tmdb:") }?.removePrefix("tmdb:")?.toIntOrNull()
            if (tmdbId != null) attempt { lookups.imdbId(tmdbId, item.type) }?.let { id = it }
        }
        val extras = attempt { lookups.metaExtras(item.type, id) } ?: MetaExtras.Empty
        val logo = extras.logo?.takeIf { url -> attempt { lookups.logoLoads(url) } == true }
        val genres = if (extras.genres.isEmpty()) title.genres else extras.genres.take(GENRES)
        val enriched = if (logo != null) item.withLogo(logo) else item
        return if (genres == title.genres && enriched.logo == item.logo) title else ThemedTitle(enriched, genres)
    }
}
